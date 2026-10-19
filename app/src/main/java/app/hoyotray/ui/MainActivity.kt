@file:OptIn(ExperimentalMaterial3Api::class)

package app.hoyotray.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoyotray.data.Repo
import app.hoyotray.data.Rewards
import app.hoyotray.data.RewardsOutcome
import app.hoyotray.data.UiState
import app.hoyotray.data.shownAccounts
import app.hoyotray.model.Game
import app.hoyotray.model.GameAccount
import app.hoyotray.model.Note
import app.hoyotray.model.clock
import app.hoyotray.model.codeKey
import app.hoyotray.model.noteKey
import app.hoyotray.widget.HoyoWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HoyoTheme { App() } }
    }
}

/** Refresh, then rewards, then the widget. Returns the rewards outcome to show as a snackbar. */
suspend fun syncAll(ctx: Context, redeem: Boolean = false, onlyMissing: Boolean = false): RewardsOutcome? {
    val ok = Repo.refresh(onlyMissing)
    val outcome = if (ok) Repo.runRewards(redeemNotifyCodes = redeem) else null
    HoyoWidget().updateAll(ctx)
    return outcome
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val state by Repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf("home") }

    fun sync(redeem: Boolean = false, onlyMissing: Boolean = false) = scope.launch {
        val outcome = syncAll(ctx, redeem, onlyMissing)
        outcome?.messages?.takeIf { it.isNotEmpty() }?.let { snackbar.showSnackbar(it.joinToString("\n")) }
    }

    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) sync()
    }
    fun openLogin() = login.launch(Intent(ctx, LoginActivity::class.java))

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!state.loggedIn) openLogin()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (Repo.state.value.loggedIn) sync() }

    BackHandler(enabled = screen != "home") { screen = "home" }
    when (screen) {
        "settings" -> SettingsScreen(
            state = state,
            onBack = { screen = "home" },
            onChanged = { Repo.saveSettings(it); sync(onlyMissing = true) },
            onLogin = { openLogin() },
            onLogout = { Repo.logout(); scope.launch { HoyoWidget().updateAll(ctx) } },
        )
        else -> HomeScreen(state, snackbar, onRefresh = { sync() }, onRedeem = { sync(redeem = true) },
            onSettings = { screen = "settings" }, onLogin = { openLogin() })
    }
}

@Composable
private fun HomeScreen(
    state: UiState,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onRedeem: () -> Unit,
    onSettings: () -> Unit,
    onLogin: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val shown = shownAccounts(state.settings, state.cache)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HoyoTray", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!state.loggedIn) item { Banner("Log in to HoYoLAB to see your resin, power and battery.", "Log in", onLogin) }
                state.globalError?.let { err ->
                    item {
                        if (err == Repo.LOGIN_EXPIRED) Banner("Your HoYoLAB login expired.", "Log in again", onLogin, Warn)
                        else Text("⚠  $err", color = Warn, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                for ((g, a) in shown) {
                    val several = shown.count { it.first == g } > 1
                    item(key = noteKey(g, a)) {
                        StaminaCard(g, a, several, state.cache.notes[noteKey(g, a)], state.errors[noteKey(g, a)],
                            checkInBadge(state, g), state.refreshing, now)
                    }
                }
                if (state.loggedIn && shown.isEmpty() && state.cache.accounts.isNotEmpty())
                    item { Text("Nothing selected. Pick games in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.loggedIn && state.cache.accounts.isNotEmpty()) item { RewardsCard(state, onRedeem) }
                item {
                    val status = when {
                        state.refreshing -> "Updating…"
                        state.rewardsRunning -> "Checking daily rewards…"
                        state.cache.lastUpdated != null -> "Updated ${clock(state.cache.lastUpdated)}"
                        else -> ""
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}

private fun checkInBadge(state: UiState, g: Game): Pair<String, Color>? {
    if (g.key !in state.settings.autoCheckIn) return null
    val r = state.cache.rewards
    return when (Rewards.today) {
        r.checkedIn[g.key] -> "✓ Checked in" to Good
        r.checkInNeedsYou[g.key] -> "⚠ Check-in" to Warn
        else -> null
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = color)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(9.dp).clip(CircleShape).background(color))

@Composable
private fun StaminaCard(
    g: Game, a: GameAccount, several: Boolean, note: Note?, error: String?,
    badge: Pair<String, Color>?, refreshing: Boolean, now: Long,
) {
    val accent = Color(g.accent)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(accent)
                Spacer(Modifier.width(8.dp))
                Text(g.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                badge?.let { (text, color) ->
                    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
                    Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = dim)
                }
                Text(if (several) "${a.nickname} · ${a.uid}" else a.nickname, style = MaterialTheme.typography.labelMedium, color = dim)
            }
            Spacer(Modifier.height(10.dp))
            val st = note?.stamina
            val full = st?.isFull(now) == true
            Row(verticalAlignment = Alignment.Bottom) {
                Text(g.staminaName, color = dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(bottom = 4.dp))
                Text(st?.estimate(now)?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = if (full) accent else if (st == null) dim else MaterialTheme.colorScheme.onSurface)
                if (st != null) Text(" / ${st.max}", color = dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                val f = st?.let { (it.estimate(now).toFloat() / it.max).coerceIn(0f, 1f) } ?: 0f
                if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(accent))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(st?.footer(now) ?: if (refreshing) "Loading…" else "No data yet",
                    style = MaterialTheme.typography.bodySmall, color = if (full) accent else dim, modifier = Modifier.weight(1f))
                if (note != null) Text(note.side, style = MaterialTheme.typography.bodySmall, color = if (note.sideDone) Good else dim)
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text("⚠  $error", style = MaterialTheme.typography.bodySmall, color = Warn)
            }
        }
    }
}

@Composable
private fun RewardsCard(state: UiState, onRedeem: () -> Unit) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val r = state.cache.rewards
    val shown = shownAccounts(state.settings, state.cache)
    val pending = remember(state) { Repo.notifyPending().map { it.third }.distinct() }
    val website = remember(state) { Repo.codesForWebsite() }
    val checkInsWeb = remember(state) { Repo.checkInsForWebsite() }
    val gamesShown = shown.map { it.first }.distinct()

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Daily rewards", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            for (g in gamesShown) {
                val checkIn = when {
                    g.key !in state.settings.autoCheckIn -> "Check-in: off (Settings)"
                    r.checkedIn[g.key] == Rewards.today -> "Check-in: ✓ claimed today"
                    r.checkInNeedsYou[g.key] == Rewards.today -> "Check-in: needs you"
                    else -> "Check-in: pending"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(Color(g.accent)); Spacer(Modifier.width(8.dp))
                    Text(g.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(checkIn, style = MaterialTheme.typography.bodySmall,
                        color = if ("✓" in checkIn) Good else if ("needs" in checkIn) Warn else dim)
                }
            }

            val offers = gamesShown.flatMap { g -> r.offers[g.key].orEmpty().map { g to it } }
            if (offers.isEmpty()) Text("No redemption codes on HoYoLAB right now.", style = MaterialTheme.typography.bodySmall, color = dim)
            else {
                Text("Codes on HoYoLAB", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                for ((g, o) in offers) {
                    val accounts = shown.filter { it.first == g }.map { it.second }
                    val keys = accounts.map { codeKey(g, it, o.code) }
                    val status = when {
                        keys.all { it in r.codesDone } -> "✓ done" to Good
                        keys.any { it in r.codesNeedYou } -> "website" to Warn
                        state.settings.codeMode(g) == "off" -> "ignored" to dim
                        else -> "not redeemed" to dim
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(Color(g.accent)); Spacer(Modifier.width(8.dp))
                        Text(o.code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (o.rewards.isNotEmpty()) Text("  ${o.rewards}", style = MaterialTheme.typography.bodySmall, color = dim)
                        Spacer(Modifier.weight(1f))
                        Text(status.first, style = MaterialTheme.typography.bodySmall, color = status.second)
                    }
                }
            }

            if (pending.isNotEmpty() && !state.rewardsRunning)
                FilledTonalButton(onClick = onRedeem, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Redeem, null); Spacer(Modifier.width(8.dp))
                    Text(if (pending.size == 1) "Redeem ${pending[0]}" else "Redeem ${pending.size} new codes")
                }
            for ((g, code) in website)
                OutlinedButton(onClick = { Rewards.open(ctx, Rewards.redeemPage(g, code)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Public, null); Spacer(Modifier.width(8.dp)); Text("Redeem $code on the website")
                }
            for (g in checkInsWeb)
                OutlinedButton(onClick = { Rewards.open(ctx, Rewards.checkInPage(g)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Public, null); Spacer(Modifier.width(8.dp)); Text("Claim ${g.shortName} check-in on the website")
                }
        }
    }
}
