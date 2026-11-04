package app.socialguard

import android.content.SharedPreferences
import java.time.LocalDate

/** Every setting the screen writes and the service reads, in one place. */
object Prefs {
    const val FILE = "socialguard"

    const val BLOCK_IG = "block_ig"
    const val BLOCK_YT = "block_yt"
    const val BLOCK_FB = "block_fb"
    const val LIMIT_TT = "limit_tt"
    const val DEBUG = "debug"

    // Minutes a day of Reels before the Instagram block starts (0 = block right away)
    const val IG_FREE_MIN = "ig_free_min"
    const val TT_LIMIT_MIN = "tt_limit_min"
    const val TT_LIMIT_DEFAULT = 10

    // Instagram usernames whose reels always play, comma-separated, and whether that list is on
    const val IG_ALLOWED = "ig_allowed_authors"
    const val IG_ALLOWED_ON = "ig_allowed_on"

    const val USAGE_IG = "instagram"
    const val USAGE_TT = "tiktok"

    /** Seconds used today, per app group. A new date gives a new key, so it resets at midnight. */
    fun usageKey(group: String) = "usage_${group}_${LocalDate.now()}"

    fun allowedAuthors(p: SharedPreferences): List<String> = (p.getString(IG_ALLOWED, "") ?: "")
        .split(',').map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }

    /** Before the switch existed, a non-empty list was simply on. */
    fun allowedOn(p: SharedPreferences) = p.getBoolean(IG_ALLOWED_ON, allowedAuthors(p).isNotEmpty())
}
