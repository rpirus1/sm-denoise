package app.socialguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches Instagram / Facebook / YouTube for the Reels and Shorts screens and presses Back
 * when one is open. A single video someone sent you is allowed, but swiping to the next one
 * is not. TikTok gets a daily time budget instead (its whole app is the feed).
 *
 * View ids and labels change between app versions. If blocking stops working after an
 * update, turn on "Debug logging" in the app, open the Reels/Shorts screen, and read
 * `adb logcat -s SocialGuard` to see which ids/labels are on screen; then adjust the
 * lists below.
 */
class BlockerService : AccessibilityService() {

    companion object {
        const val TAG = "SocialGuard"

        const val IG = "com.instagram.android"
        const val FB = "com.facebook.katana"
        const val YT = "com.google.android.youtube"
        val TIKTOK = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

        // Instagram: ids the reels viewer only shows for a reel someone sent you (the sender's
        // name and photo). Swiping on to a suggested reel removes them. Not the reply bar: your
        // own reels opened from your profile have one too.
        val IG_SENT_IDS = listOf("sender_username_or_fullname", "sender_profile_pic", "sender_timestamp")

        // Instagram: ids of the full-screen reels viewer. Not "reel_viewer": inside Instagram
        // that's Stories, and story avatars in the feed/DM inbox use it too.
        val IG_VIEWER_IDS = listOf("clips_viewer", "clips_swipe_refresh")

        // Instagram: the bottom-bar Reels tab, and the reel author's username in the viewer
        const val IG_REELS_TAB_ID = "clips_tab"
        const val IG_AUTHOR_ID = "clips_author_username"

        // Instagram: a profile screen, and the username in its top bar
        const val IG_PROFILE_ID = "profile_header_container"
        const val IG_TITLE_ID = "action_bar_title"
        val HIDDEN_COAUTHORS = Regex("""\s\d+\s*$""")

        // Facebook: ids of the reels viewer
        val FB_VIEWER_IDS = listOf("reels_viewer", "reel_viewer", "reels_tab_content")

        // Facebook barely sets view ids, so the reels player (also when opened from the feed) is
        // found by its accessibility labels. "fbshorts" is an internal name, the same in every
        // language; the others are the "Go to your Reels profile" button in English/Ukrainian.
        val FB_VIEWER_DESCS = listOf("fbshorts", "your reels profile", "ваш профіль reels")

        // YouTube: ids of the Shorts player. Keep these specific: "reel_shelf" etc. is the
        // Shorts row on the home feed, which shouldn't trigger a Back.
        val YT_VIEWER_IDS = listOf("reel_recycler", "reel_watch_player", "reel_player_page_container", "shorts_player")

        // A selected tab whose label contains one of these means the feed tab is open
        const val REELS_LABEL = "reels"
        const val SHORTS_LABEL = "shorts"

        // How long after arriving from another app a YouTube Short counts as "sent to you"
        const val YT_SHARED_WINDOW_MS = 4000L

        // The IG sender bar can draw a moment after the viewer: don't kick before then
        const val IG_SENT_GRACE_MS = 800L

        // Ignore scroll events right after an allowed video opens (layout settling)
        const val SINGLE_GRACE_MS = 1000L

        // The ticker counts TikTok and Reels time in steps of this many seconds
        const val TICK_S = 5
    }

    /** Per-app state for "you may watch the one video you were sent, but not scroll on". */
    private class ShortsState {
        var sharedUntil = 0L   // a viewer appearing before this time counts as "sent to you"
        var single = false     // currently watching one allowed video
        var singleStart = 0L
        var quietUntil = 0L    // just kicked out of a single video; let Back finish
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE) }
    private val homePkgs by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet() + "com.android.systemui"
    }

    private var currentPkg = ""
    private var lastAction = 0L
    private var streak = 0
    private var streakStart = 0L
    private var lastDebugLog = 0L
    private var igViewerSince = 0L
    private var igViewerWasSent = false
    private var igProfileUser: String? = null   // the profile the open reels viewer came from
    private var igAuthorOk = false              // the last author name seen in the viewer was allowed
    private var igInFeed = false   // the last check found a screen the block applies to
    private val igRecheck = Runnable {
        if (currentPkg == IG && prefs.getBoolean(Prefs.BLOCK_IG, true)) checkInstagram()
    }
    private val ytState = ShortsState()

    private val ticker = object : Runnable {
        override fun run() {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) {
                tickTikTok()
                tickInstagram()
            }
            main.postDelayed(this, TICK_S * 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 5000)
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(igRecheck)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // Track the foreground app across all apps, so we know when TikTok is left and
        // where YouTube was opened from. Keyboards/toasts aren't the active window: skip them.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName && pkg != currentPkg) {
            if (rootInActiveWindow?.packageName?.toString() == pkg) onAppSwitch(currentPkg, pkg)
        }

        val monitored = pkg == IG || pkg == FB || pkg == YT || pkg in TIKTOK
        if (!monitored) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pkg == YT && prefs.getBoolean(Prefs.BLOCK_YT, true)) onScrolled(event, ytState)
            return
        }

        when {
            pkg == IG && prefs.getBoolean(Prefs.BLOCK_IG, true) -> checkInstagram()
            pkg == FB && prefs.getBoolean(Prefs.BLOCK_FB, true) -> checkFacebook()
            pkg == YT && prefs.getBoolean(Prefs.BLOCK_YT, true) -> checkYouTube()
            pkg in TIKTOK && prefs.getBoolean(Prefs.LIMIT_TT, true) -> checkTikTokBudget()
        }
    }

    private fun onAppSwitch(from: String, to: String) {
        currentPkg = to
        if (to != IG) igInFeed = false
        // A Short opened from a link in another app (Telegram, WhatsApp...) is "sent to you".
        // Coming back via the launcher/recents isn't.
        if (to == YT) {
            ytState.sharedUntil = if (from.isNotEmpty() && from !in homePkgs) {
                SystemClock.elapsedRealtime() + YT_SHARED_WINDOW_MS
            } else 0
        }
    }

    // ---------- Instagram ----------

    private fun checkInstagram() {
        val scan = scan(IG) ?: return
        val viewer = IG_VIEWER_IDS.any { key -> scan.ids.any { it.contains(key) } }
        val sent = IG_SENT_IDS.any { key -> scan.ids.any { it.contains(key) } }
        // Only the bottom-bar Reels tab: profiles have their own "Reels" tab, which is fine.
        val reelsTab = IG_REELS_TAB_ID in scan.selectedIds
        // Reels by accounts you allowed (e.g. your own): fine, and so is swiping between them.
        val allowed = if (Prefs.allowedOn(prefs)) Prefs.allowedAuthors(prefs) else emptyList()
        val allowedAuthor = allowed.isNotEmpty() && scan.authors.isNotEmpty() &&
            scan.authors.all { a -> allowed.any { a.contains(it) } }
        // A reel opened from someone's profile: that person's reels play, swiping on to
        // anyone else's doesn't. The viewer covers the profile, so remember whose it was.
        val profile = if (IG_PROFILE_ID in scan.ids) scan.titles.firstOrNull() else null
        if (profile != null) igProfileUser = profile else if (!viewer) igProfileUser = null
        // A reel by many accounts reads "name and 4 more" in any language, so the profile's owner
        // may be among the hidden ones. Usernames have no spaces, so " 4" at the end means that.
        val profileAuthor = igProfileUser?.let { p ->
            scan.authors.isNotEmpty() && scan.authors.all { it.contains(p) || HIDDEN_COAUTHORS.containsMatchIn(it) }
        } == true

        val now = SystemClock.elapsedRealtime()
        if (!viewer) {
            igViewerSince = 0
            igViewerWasSent = false
        } else if (igViewerSince == 0L) {
            igViewerSince = now
        }
        if (sent) igViewerWasSent = true
        // The author's name disappears for a moment while swiping and when Instagram hides the
        // overlay. While watching allowed reels, "no name shown" isn't "someone else".
        if (!viewer) igAuthorOk = false
        else if (scan.authors.isNotEmpty()) igAuthorOk = allowedAuthor || profileAuthor
        val authorOk = igAuthorOk && (scan.authors.isEmpty() || allowedAuthor || profileAuthor)

        // Reels tab = the feed. Viewer showing who sent it = a reel from your DMs: allowed.
        // Viewer without a sender = the feed, or you swiped on from a sent reel.
        val block = when {
            reelsTab -> "Reels blocked"
            !viewer || sent || authorOk -> null
            now - igViewerSince < IG_SENT_GRACE_MS -> {
                // A playing video fires few events: look again once the grace period is over.
                main.removeCallbacks(igRecheck)
                main.postDelayed(igRecheck, IG_SENT_GRACE_MS - (now - igViewerSince) + 50)
                null
            }
            igViewerWasSent -> "One video only"
            else -> "Reels blocked"
        }
        igInFeed = block != null
        if (block == null) return
        if (prefs.getBoolean(Prefs.DEBUG, false)) {
            Log.d(TAG, "IG block: $block authors=${scan.authors} profile=$igProfileUser")
        }
        // Free minutes left today: watch freely while the ticker counts the time.
        val freeMin = prefs.getInt(Prefs.IG_FREE_MIN, 0)
        when {
            freeMin == 0 -> kick(block)
            igSecondsLeft(freeMin) <= 0 -> kick("Today's Reels minutes are used up")
        }
    }

    private fun igSecondsLeft(freeMin: Int) = freeMin * 60 - prefs.getInt(Prefs.usageKey(Prefs.USAGE_IG), 0)

    /** Counts Reels time while free minutes are left, and blocks once they run out. */
    private fun tickInstagram() {
        val freeMin = prefs.getInt(Prefs.IG_FREE_MIN, 0)
        if (currentPkg != IG || !igInFeed || freeMin == 0 || !prefs.getBoolean(Prefs.BLOCK_IG, true)) return
        checkInstagram() // still on a Reels screen?
        if (!igInFeed || igSecondsLeft(freeMin) <= 0) return
        val key = Prefs.usageKey(Prefs.USAGE_IG)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + TICK_S).apply()
        if (igSecondsLeft(freeMin) <= 0) checkInstagram()
    }

    // ---------- Facebook ----------

    private fun checkFacebook() {
        val scan = scan(FB) ?: return
        val viewer = FB_VIEWER_IDS.any { key -> scan.ids.any { it.contains(key) } } ||
            FB_VIEWER_DESCS.any { key -> scan.descs.any { it.contains(key) } }
        val reelsTab = scan.selected.any { it.contains(REELS_LABEL) }
        if (reelsTab || viewer) kick("Reels blocked")
    }

    // ---------- YouTube ----------

    private fun checkYouTube() {
        val scan = scan(YT) ?: return
        val viewer = YT_VIEWER_IDS.any { key -> scan.ids.any { it.contains(key) } }
        val shortsTab = scan.selected.any { it.contains(SHORTS_LABEL) }
        guardViewer(ytState, viewer, shortsTab, "Shorts blocked")
    }

    // ---------- One video only (YouTube) ----------

    /**
     * Feed tab selected = browsing the feed: Back. Viewer opened shortly after arriving from
     * another app = a video someone sent you: allowed (until you swipe, see [onScrolled]).
     * Any other viewer = the feed: Back.
     */
    private fun guardViewer(s: ShortsState, viewer: Boolean, feedTab: Boolean, msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < s.quietUntil) return
        when {
            feedTab -> {
                s.single = false
                s.sharedUntil = 0
                kick(msg)
            }
            !viewer -> s.single = false
            s.single -> {}
            now < s.sharedUntil -> {
                s.single = true
                s.singleStart = now
                s.sharedUntil = 0
            }
            else -> kick(msg)
        }
    }

    /** Swiping the full-screen pager while watching an allowed video = going to the next one. */
    private fun onScrolled(event: AccessibilityEvent, s: ShortsState) {
        if (!s.single) return
        val now = SystemClock.elapsedRealtime()
        if (now - s.singleStart < SINGLE_GRACE_MS) return
        // Only the full-screen pager counts; the comments sheet etc. also scroll.
        val src = event.source ?: return
        val bounds = Rect().also { src.getBoundsInScreen(it) }
        if (bounds.height() < resources.displayMetrics.heightPixels * 0.6) return
        s.single = false
        s.quietUntil = now + 1500
        kick("One video only")
    }

    // ---------- TikTok ----------

    private fun checkTikTokBudget() {
        if (usedSeconds() >= limitSeconds()) kickHome("TikTok limit reached for today")
    }

    private fun tickTikTok() {
        if (currentPkg !in TIKTOK || !prefs.getBoolean(Prefs.LIMIT_TT, true)) return
        prefs.edit().putInt(Prefs.usageKey(Prefs.USAGE_TT), usedSeconds() + TICK_S).apply()
        checkTikTokBudget()
    }

    private fun usedSeconds() = prefs.getInt(Prefs.usageKey(Prefs.USAGE_TT), 0)
    private fun limitSeconds() = prefs.getInt(Prefs.TT_LIMIT_MIN, Prefs.TT_LIMIT_DEFAULT) * 60

    // ---------- Helpers ----------

    private class Scan(
        val ids: MutableSet<String> = HashSet(),
        val selected: MutableList<String> = ArrayList(),
        val descs: MutableList<String> = ArrayList(),
        val selectedIds: MutableSet<String> = HashSet(),
        val authors: MutableList<String> = ArrayList(),
        val titles: MutableList<String> = ArrayList(),
    )

    private fun scan(expectedPkg: String): Scan? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != expectedPkg) return null
        val result = Scan()
        walk(root, result, intArrayOf(0))
        debugLog(expectedPkg, result)
        return result
    }

    /** [parentId]: id of the nearest ancestor that has one (tab icons sit inside the tab view). */
    private fun walk(node: AccessibilityNodeInfo?, out: Scan, budget: IntArray, parentId: String? = null) {
        if (node == null || budget[0]++ > 1500) return
        // Instagram keeps other tabs (Reels, DMs) alive off-screen, so their views stay in the
        // tree. Only count what's actually on screen.
        if (!node.isVisibleToUser) return
        val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase()
        if (id != null) out.ids.add(id)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.descs.add(it.lowercase()) }
        if (node.isSelected) {
            val label = (node.contentDescription?.toString() ?: node.text?.toString())?.lowercase()
            if (!label.isNullOrBlank()) out.selected.add(label)
            id?.let { out.selectedIds.add(it) }
            parentId?.let { out.selectedIds.add(it) }
        }
        if (id == IG_AUTHOR_ID) {
            val name = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase()
            if (!name.isNullOrBlank()) out.authors.add(name)
        }
        if (id == IG_TITLE_ID) {
            val title = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()?.lowercase()
            if (!title.isNullOrBlank()) out.titles.add(title)
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out, budget, id ?: parentId)
    }

    private fun debugLog(pkg: String, s: Scan) {
        if (!prefs.getBoolean(Prefs.DEBUG, false)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugLog < 1000) return
        lastDebugLog = now
        Log.d(TAG, "$pkg ids=${s.ids.sorted()} selected=${s.selected} selectedIds=${s.selectedIds} authors=${s.authors} titles=${s.titles} descs=${s.descs.take(40)}")
    }

    private fun kick(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAction < 700) return
        lastAction = now
        if (now - streakStart > 4000) {
            streak = 0
            streakStart = now
        }
        streak++
        Log.i(TAG, "Back: $msg")
        if (streak > 3) {
            // Back isn't getting us out (e.g. reels is the landing screen): go home instead.
            streak = 0
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun kickHome(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAction < 1500) return
        lastAction = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
