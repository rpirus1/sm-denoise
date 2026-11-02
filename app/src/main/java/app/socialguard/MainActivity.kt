package app.socialguard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences(BlockerService.PREFS, Context.MODE_PRIVATE) }

    // Refreshed in onResume: you come back here from the accessibility settings
    private var serviceEnabled by mutableStateOf(false)
    private var tikTokUsedMin by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DenoiseTheme {
                SettingsScreen(
                    prefs = prefs,
                    serviceEnabled = serviceEnabled,
                    tikTokUsedMin = tikTokUsedMin,
                    openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled()
        tikTokUsedMin = prefs.getInt(BlockerService.usageKey("tiktok"), 0) / 60
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }
}

// ---------- Screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    prefs: SharedPreferences,
    serviceEnabled: Boolean,
    tikTokUsedMin: Int,
    openAccessibility: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("SM De-noise") },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { StatusCard(serviceEnabled, openAccessibility) }

            item {
                Section("Instagram") {
                    PrefSwitch(prefs, "block_ig", true, "Block Reels", "A reel sent to you in DMs plays, swiping on doesn't")
                    SectionDivider()
                    PrefText(
                        prefs, BlockerService.IG_ALLOWED_AUTHORS,
                        label = "Always allow reels by",
                        placeholder = "e.g. whosehabits",
                        supporting = "Usernames, comma-separated. Their reels play and you can swipe between them.",
                    )
                }
            }

            item {
                Section("YouTube") {
                    PrefSwitch(prefs, "block_yt", true, "Block Shorts", "A Short opened from a link plays, swiping on doesn't")
                }
            }

            item {
                Section("Facebook") {
                    PrefSwitch(prefs, "block_fb", true, "Block Reels", "Reels tab and reels opened from the feed")
                }
            }

            item { TikTokSection(prefs, tikTokUsedMin) }

            item {
                Section("Troubleshooting") {
                    PrefSwitch(prefs, "debug", false, "Debug logging", "Writes on-screen view ids to adb logcat -s SocialGuard")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(enabled: Boolean, openAccessibility: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) colors.primaryContainer else colors.errorContainer,
            contentColor = if (enabled) colors.onPrimaryContainer else colors.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    if (enabled) "Protection is on" else "Protection is off",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (enabled) "Feeds are filtered. DMs, profiles and posts work as usual."
                else "Turn on SM De-noise under Accessibility > Installed apps. If the switch is greyed out: " +
                    "Settings > Apps > SM De-noise > ⋮ > Allow restricted settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (enabled) {
                TextButton(onClick = openAccessibility, contentPadding = PaddingValues(0.dp)) {
                    Text("Accessibility settings")
                }
            } else {
                Button(onClick = openAccessibility) { Text("Open accessibility settings") }
            }
        }
    }
}

@Composable
private fun TikTokSection(prefs: SharedPreferences, usedMin: Int) {
    var limitText by remember { mutableStateOf(prefs.getInt("tt_limit_min", 10).toString()) }
    val limit = limitText.toIntOrNull() ?: 0
    Section("TikTok") {
        PrefSwitch(prefs, "limit_tt", true, "Daily time limit", "Sends you home when today's minutes are used up")
        SectionDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            OutlinedTextField(
                value = limitText,
                onValueChange = { v ->
                    val digits = v.filter { it.isDigit() }.take(3)
                    limitText = digits
                    digits.toIntOrNull()?.let { prefs.edit().putInt("tt_limit_min", it.coerceIn(0, 600)).apply() }
                },
                label = { Text("Minutes per day") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { if (limit > 0) (usedMin.toFloat() / limit).coerceIn(0f, 1f) else 1f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Used today: $usedMin of $limit min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- Building blocks ----------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun PrefSwitch(prefs: SharedPreferences, key: String, default: Boolean, headline: String, supporting: String) {
    var on by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    val set = { v: Boolean ->
        on = v
        prefs.edit().putBoolean(key, v).apply()
    }
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        trailingContent = { Switch(checked = on, onCheckedChange = set) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { set(!on) },
    )
}

@Composable
private fun PrefText(prefs: SharedPreferences, key: String, label: String, placeholder: String, supporting: String) {
    var text by remember { mutableStateOf(prefs.getString(key, "") ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            prefs.edit().putString(key, it).apply()
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = { Text(supporting) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

// ---------- Theme ----------

@Composable
private fun DenoiseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Material You: take colours from the wallpaper on Android 12+, a calm teal otherwise
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF80D5CB), primaryContainer = Color(0xFF00504A))
        else -> lightColorScheme(primary = Color(0xFF006A62), primaryContainer = Color(0xFF9EF2E7))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
