package app.socialguard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE) }

    // Refreshed in onResume: you come back here from the accessibility settings
    private var serviceEnabled by mutableStateOf(false)
    private var usage by mutableStateOf(Usage(0, 0))
    private var installed by mutableStateOf(emptySet<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DenoiseTheme {
                SettingsScreen(
                    prefs = prefs,
                    serviceEnabled = serviceEnabled,
                    usage = usage,
                    installed = installed,
                    openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    openAppInfo = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled()
        usage = Usage(
            igMin = prefs.getInt(Prefs.usageKey(Prefs.USAGE_IG), 0) / 60,
            ttMin = prefs.getInt(Prefs.usageKey(Prefs.USAGE_TT), 0) / 60,
        )
        installed = (listOf(BlockerService.IG, BlockerService.YT, BlockerService.FB) + BlockerService.TIKTOK)
            .filter { runCatching { packageManager.getApplicationInfo(it, 0) }.isSuccess }
            .toSet()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }
}

/** Minutes used today. */
private data class Usage(val igMin: Int, val ttMin: Int)

// ---------- Screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    prefs: SharedPreferences,
    serviceEnabled: Boolean,
    usage: Usage,
    installed: Set<String>,
    openAccessibility: () -> Unit,
    openAppInfo: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val focus = LocalFocusManager.current
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scroll.nestedScrollConnection)
            // A tap anywhere outside the text field closes it
            .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
        topBar = { LargeTopAppBar(title = { Text("SM De-noise") }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                if (serviceEnabled) ProtectionOn(openAccessibility) else ProtectionOff(openAccessibility, openAppInfo)
            }
            // Only apps that are on the phone get a section
            if (BlockerService.IG in installed) item { InstagramSection(prefs, usage.igMin) }
            if (BlockerService.YT in installed) item {
                AppSection(BlockerService.YT, "YouTube") {
                    SwitchRow("Block Shorts", rememberPref(prefs, Prefs.BLOCK_YT, true))
                }
            }
            if (BlockerService.FB in installed) item {
                AppSection(BlockerService.FB, "Facebook") {
                    SwitchRow("Block Reels", rememberPref(prefs, Prefs.BLOCK_FB, true))
                }
            }
            installed.firstOrNull { it in BlockerService.TIKTOK }?.let { pkg ->
                item { TikTokSection(prefs, pkg, usage.ttMin) }
            }
            item {
                Panel { SwitchRow("Debug logging", rememberPref(prefs, Prefs.DEBUG, false)) }
            }
        }
    }
}

@Composable
private fun ProtectionOn(openAccessibility: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // Container colours come from the wallpaper and are light in light mode, dark in dark mode
    val calm = Brush.linearGradient(listOf(c.primaryContainer, c.secondaryContainer, c.tertiaryContainer))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CardDefaults.shape)
            .background(calm)
            .padding(top = 20.dp, bottom = 12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides c.onPrimaryContainer) {
            Column {
                Header(MaterialTheme.typography.titleLarge, "Protection is on") { Lucide(R.drawable.lucide_shield_check) }
                // Quiet way out, lined up with the title
                TextButton(
                    onClick = openAccessibility,
                    colors = ButtonDefaults.textButtonColors(contentColor = c.onPrimaryContainer.copy(alpha = 0.8f)),
                    modifier = Modifier.padding(start = TEXT_START - 12.dp),
                ) {
                    Text("Turn off in accessibility settings")
                    Spacer(Modifier.width(4.dp))
                    Lucide(R.drawable.lucide_chevron_right, size = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun ProtectionOff(openAccessibility: () -> Unit, openAppInfo: () -> Unit) {
    Panel {
        Header(MaterialTheme.typography.titleLarge, "Protection is off") {
            Lucide(R.drawable.lucide_shield_off, tint = MaterialTheme.colorScheme.error)
        }
        Step(R.drawable.lucide_accessibility, "Open accessibility settings") {
            Button(onClick = openAccessibility) { Text("Open") }
        }
        Step(R.drawable.lucide_toggle_right, "Tap Installed apps › SM De-noise and turn it on")
        Step(R.drawable.lucide_lock_open, "Switch greyed out? In App info, tap ⋮ › Allow restricted settings") {
            OutlinedButton(onClick = openAppInfo) { Text("App info") }
        }
    }
}

@Composable
private fun InstagramSection(prefs: SharedPreferences, usedMin: Int) {
    val block = rememberPref(prefs, Prefs.BLOCK_IG, true)
    val allowOn = remember { PrefBool(prefs, Prefs.IG_ALLOWED_ON, Prefs.allowedOn(prefs)) }
    AppSection(BlockerService.IG, "Instagram") {
        SwitchRow("Block Reels", block)
        AnimatedVisibility(block.value) {
            Column {
                MinutesRow(prefs, Prefs.IG_FREE_MIN, 0, listOf(0, 5, 10, 15, 30), "Free minutes a day", usedMin)
                SwitchRow("Allowed accounts", allowOn)
                AnimatedVisibility(allowOn.value) { AllowList(prefs) }
            }
        }
    }
}

@Composable
private fun TikTokSection(prefs: SharedPreferences, pkg: String, usedMin: Int) {
    val limit = rememberPref(prefs, Prefs.LIMIT_TT, true)
    AppSection(pkg, "TikTok") {
        SwitchRow("Daily limit", limit)
        AnimatedVisibility(limit.value) {
            MinutesRow(prefs, Prefs.TT_LIMIT_MIN, Prefs.TT_LIMIT_DEFAULT, listOf(5, 10, 15, 30, 60), "Minutes a day", usedMin)
        }
    }
}

/** Instagram accounts whose reels always play: chips to remove, a field to add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllowList(prefs: SharedPreferences) {
    var names by remember { mutableStateOf(Prefs.allowedAuthors(prefs)) }
    var input by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val save = { list: List<String> ->
        names = list
        prefs.edit().putString(Prefs.IG_ALLOWED, list.joinToString(",")).apply()
    }
    val add = {
        if (input.isNotEmpty() && input !in names) save(names + input)
        input = ""
    }

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        if (names.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { name ->
                    InputChip(
                        selected = false,
                        onClick = { removing = name },
                        label = { Text("@$name") },
                        trailingIcon = { Lucide(R.drawable.lucide_x, size = 16.dp) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = normalizeUsername(it) },
            label = { Text("Add account") },
            prefix = { Text("@") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                add()
                focus.clearFocus()
            }),
            trailingIcon = {
                IconButton(onClick = add, enabled = input.isNotEmpty()) { Lucide(R.drawable.lucide_plus) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    removing?.let { name ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove @$name?") },
            confirmButton = {
                TextButton(onClick = {
                    save(names - name)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Takes a username, "@username" or a pasted profile link; keeps only what Instagram allows. */
private fun normalizeUsername(raw: String) = raw.trim()
    .substringAfter("instagram.com/").substringBefore('/').substringBefore('?')
    .lowercase()
    .filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }
    .take(30)

// ---------- Building blocks ----------

/** A card on the page. Spacing, not dividers, separates what's inside. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

/** A panel headed by the app's own icon from the phone. */
@Composable
private fun AppSection(pkg: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Panel {
        Header(MaterialTheme.typography.titleMedium, title) { AppIcon(pkg) }
        content()
    }
}

/**
 * Icon and bold title. Every card's header uses this, so the status icon and the app icons
 * sit in one column.
 */
@Composable
private fun Header(style: TextStyle, title: String, icon: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(HEADER_ICON), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(ICON_GAP))
        Text(title, style = style, fontWeight = FontWeight.Bold)
    }
}

private val HEADER_ICON = 28.dp
private val ICON_GAP = 14.dp
// Where header titles start inside a card
private val TEXT_START = 16.dp + HEADER_ICON + ICON_GAP

@Composable
private fun Step(@DrawableRes icon: Int, text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Box(Modifier.size(HEADER_ICON), contentAlignment = Alignment.Center) {
            Lucide(icon, size = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                action()
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, pref: PrefBool) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = { Switch(checked = pref.value, onCheckedChange = pref::set) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { pref.set(!pref.value) },
    )
}

/** Minutes a day as a row of choices, with today's use under it. 0 shows as "None". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinutesRow(prefs: SharedPreferences, key: String, default: Int, options: List<Int>, label: String, usedMin: Int) {
    var value by remember {
        // A value typed into the old number field snaps to the nearest choice
        val stored = prefs.getInt(key, default)
        val snapped = options.minBy { abs(it - stored) }
        if (snapped != stored) prefs.edit().putInt(key, snapped).apply()
        mutableIntStateOf(snapped)
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { min ->
                FilterChip(
                    selected = min == value,
                    onClick = {
                        value = min
                        prefs.edit().putInt(key, min).apply()
                    },
                    label = { Text(if (min == 0) "None" else "$min") },
                )
            }
        }
        if (value > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (usedMin.toFloat() / value).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${minOf(usedMin, value)} of $value min today",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Lucide(@DrawableRes id: Int, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val icon = remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toImageBitmap() }.getOrNull()
    }
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(HEADER_ICON))
}

private fun Drawable.toImageBitmap(px: Int = 112): ImageBitmap {
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, px, px)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

// ---------- Settings state ----------

/** A boolean setting the screen can read and write. */
private class PrefBool(private val prefs: SharedPreferences, private val key: String, default: Boolean) {
    var value by mutableStateOf(prefs.getBoolean(key, default))
        private set

    fun set(v: Boolean) {
        value = v
        prefs.edit().putBoolean(key, v).apply()
    }
}

@Composable
private fun rememberPref(prefs: SharedPreferences, key: String, default: Boolean) =
    remember(key) { PrefBool(prefs, key, default) }

// ---------- Theme ----------

@Composable
private fun DenoiseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Material You: take colours from the wallpaper on Android 12+, a calm teal otherwise
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFF80D5CB), primaryContainer = Color(0xFF00504A),
            secondaryContainer = Color(0xFF334B48), tertiaryContainer = Color(0xFF324863),
        )
        else -> lightColorScheme(
            primary = Color(0xFF006A62), primaryContainer = Color(0xFF9EF2E7),
            secondaryContainer = Color(0xFFCCE8E4), tertiaryContainer = Color(0xFFD1E4FF),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
