@file:OptIn(ExperimentalMaterial3Api::class)

package app.hoyotray.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.hoyotray.BuildConfig
import app.hoyotray.data.HoyoApi
import app.hoyotray.data.Settings
import app.hoyotray.data.UiState
import app.hoyotray.model.Game

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onChanged: (Settings) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val s = state.settings
    val accounts = state.cache.accounts
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Section("Games & accounts") {
                for (g in Game.entries) {
                    val list = accounts[g.key].orEmpty()
                    SwitchRow(g.title, if (list.isEmpty()) "No account on this HoYoLAB login" else null, s.gameEnabled(g),
                        accent = Color(g.accent), bold = true) {
                        onChanged(s.copy(disabledGames = if (it) s.disabledGames - g.key else s.disabledGames + g.key))
                    }
                    for (a in list) {
                        SwitchRow(a.nickname, "UID ${a.uid}  ·  Lv ${a.level}  ·  ${HoyoApi.regionName(a.region)}",
                            s.accountShown(g, a, list), indent = true, enabled = s.gameEnabled(g)) {
                            onChanged(s.copy(accounts = s.accounts + ("${g.key}:${a.uid}" to it)))
                        }
                    }
                }
            }

            Section("Daily rewards") {
                Text(
                    "Claims the HoYoLAB daily check-in and redeems codes posted on HoYoLAB (e.g. after livestreams). " +
                        "These act on your account, so check-in is off and codes only notify you by default.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (g in Game.entries.filter { accounts[it.key].orEmpty().isNotEmpty() }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(g.accent)))
                        Spacer(Modifier.width(8.dp))
                        Text(g.title, fontWeight = FontWeight.SemiBold)
                    }
                    SwitchRow("Daily check-in", "Claims the reward once a day", g.key in s.autoCheckIn, indent = true) {
                        onChanged(s.copy(autoCheckIn = if (it) s.autoCheckIn + g.key else s.autoCheckIn - g.key))
                    }
                    val modes = listOf("off" to "Off", "notify" to "Notify", "auto" to "Auto")
                    val descriptions = mapOf("off" to "Ignored", "notify" to "Tells you about new codes", "auto" to "Redeems new codes automatically")
                    Column(Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp)) {
                        Text("Redemption codes")
                        Text(descriptions.getValue(s.codeMode(g)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { i, (key, label) ->
                                SegmentedButton(
                                    selected = s.codeMode(g) == key,
                                    onClick = { onChanged(s.copy(codes = s.codes + (g.key to key))) },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                ) { Text(label) }
                            }
                        }
                    }
                }
            }

            Section("Background") { BackgroundSettings() }

            Section("HoYoLAB account") {
                FilledTonalButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.loggedIn) "Log in again" else "Log in")
                }
                if (state.loggedIn) TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
            }

            Text(
                "HoyoTray ${BuildConfig.VERSION_NAME} · Not affiliated with HoYoverse",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(
    title: String, subtitle: String?, checked: Boolean,
    accent: Color? = null, bold: Boolean = false, indent: Boolean = false, enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }
            .padding(start = if (indent) 20.dp else 0.dp, top = 6.dp, bottom = 6.dp),
    ) {
        if (accent != null) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(accent.copy(alpha = alpha)))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
    Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

/** Battery-optimisation exemption and (on Xiaomi) Autostart, so the widget and check-in run on time. */
@SuppressLint("BatteryLife")
@Composable
private fun BackgroundSettings() {
    val ctx = LocalContext.current
    var unrestricted by remember { mutableStateOf(isUnrestricted(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = isUnrestricted(ctx) }

    Text(
        "Android limits background apps. To keep the widget and the daily check-in on time, let HoyoTray run in the background." +
            if (isXiaomi()) " On Xiaomi phones, also turn on Autostart and set Battery saver to “No restrictions”." else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    if (unrestricted) Text("✓ Battery optimisation is off for HoyoTray", color = Good, style = MaterialTheme.typography.bodyMedium)
    else OutlinedButton(onClick = {
        ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }, modifier = Modifier.fillMaxWidth()) { Text("Allow running in the background") }
    if (isXiaomi()) {
        OutlinedButton(onClick = { openXiaomiAutostart(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Open Autostart settings") }
        OutlinedButton(onClick = { openAppDetails(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Open app battery settings") }
    }
}

private fun isUnrestricted(ctx: Context) =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

private fun openXiaomiAutostart(ctx: Context) {
    val intent = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }.onFailure { openAppDetails(ctx) }
}

private fun openAppDetails(ctx: Context) {
    ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
