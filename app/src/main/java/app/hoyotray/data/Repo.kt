package app.hoyotray.data

import android.content.Context
import android.util.Log
import app.hoyotray.model.Game
import app.hoyotray.model.GameAccount
import app.hoyotray.model.codeKey
import app.hoyotray.model.noteKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

data class UiState(
    val settings: Settings = Settings(),
    val cache: Cache = Cache(),
    val loggedIn: Boolean = false,
    val refreshing: Boolean = false,
    val rewardsRunning: Boolean = false,
    val globalError: String? = null,
    val errors: Map<String, String> = emptyMap(), // by "game:uid"
)

/** Result of a rewards run, for a notification (worker) or a snackbar (app). */
data class RewardsOutcome(
    val messages: List<String> = emptyList(),
    val newCodes: Boolean = false,
    val loginNeeded: Boolean = false,
    val needsYou: Boolean = false,
)

/** Single source of truth for the app, the widget and the background worker. */
object Repo {
    private const val TAG = "HoyoTray"
    private const val ACCOUNTS_MAX_AGE = 6 * 60 * 60 * 1000L
    const val LOGIN_EXPIRED = "Login expired"

    private lateinit var app: Context
    private val refreshLock = Mutex()
    private val rewardsLock = Mutex()
    private var cookie: String? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        cookie = Store.loadCookie(app)
        _state.value = UiState(Store.loadSettings(app), Store.loadCache(app), cookie != null)
    }

    private fun updateCache(f: (Cache) -> Cache) {
        _state.update { it.copy(cache = f(it.cache)) }
        Store.saveCache(app, _state.value.cache)
    }

    private fun updateRewards(f: (RewardState) -> RewardState) = updateCache { it.copy(rewards = f(it.rewards)) }

    fun saveSettings(s: Settings) {
        Store.saveSettings(app, s)
        _state.update { it.copy(settings = s) }
    }

    fun login(newCookie: String) {
        cookie = newCookie
        Store.saveCookie(app, newCookie)
        // Possibly a different HoYoLAB account: forget accounts/notes, keep reward history.
        _state.update {
            it.copy(loggedIn = true, globalError = null, errors = emptyMap(),
                cache = Cache(rewards = it.cache.rewards))
        }
        Store.saveCache(app, _state.value.cache)
    }

    fun logout() {
        cookie = null
        Store.clearCookie(app)
        _state.update { it.copy(loggedIn = false, cache = Cache(), errors = emptyMap(), globalError = null) }
        Store.saveCache(app, Cache())
    }

    fun shown(): List<Pair<Game, GameAccount>> = _state.value.let { shownAccounts(it.settings, it.cache) }

    private fun describe(e: Throwable) = when {
        e is HoyoException && e.notPublic -> "Enable Real-Time Notes in HoYoLAB"
        e is HoyoException && e.rateLimited -> "Captcha/rate limit, will retry"
        e is HoyoException -> "API error ${e.retcode}: ${e.message}"
        e is IOException -> "Network error"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Fetches accounts (when stale) and notes for all shown accounts. Returns true if HoYoLAB was reachable. */
    suspend fun refresh(onlyMissing: Boolean = false): Boolean = refreshLock.withLock {
        val c = cookie ?: run {
            _state.update { it.copy(globalError = "Not logged in") }
            return@withLock false
        }
        _state.update { it.copy(refreshing = true) }
        try {
            val now = System.currentTimeMillis()
            val cache = _state.value.cache
            if (cache.accounts.isEmpty() || now - cache.accountsFetchedAt > ACCOUNTS_MAX_AGE) {
                val lists = coroutineScope { Game.entries.map { g -> async { g.key to HoyoApi.getAccounts(c, g.biz) } }.awaitAll() }
                updateCache { it.copy(accounts = lists.toMap(), accountsFetchedAt = now) }
            }

            val targets = shown().filter { (g, a) -> !onlyMissing || noteKey(g, a) !in _state.value.cache.notes }
            val results = coroutineScope {
                targets.map { (g, a) -> async { Triple(g, a, runCatching { HoyoApi.fetchNote(c, g, a) }) } }.awaitAll()
            }

            val errors = _state.value.errors.toMutableMap()
            val notes = _state.value.cache.notes.toMutableMap()
            var authExpired = false
            var anyOk = false
            for ((g, a, r) in results) {
                val key = noteKey(g, a)
                r.onSuccess { notes[key] = it; errors.remove(key); anyOk = true }
                    .onFailure { e ->
                        if (e is HoyoException && e.authExpired) authExpired = true
                        else { errors[key] = describe(e); Log.w(TAG, "$key: ${describe(e)}") }
                    }
            }
            updateCache { it.copy(notes = notes, lastUpdated = if (anyOk) now else it.lastUpdated, authNotified = if (anyOk) false else it.authNotified) }
            _state.update { it.copy(errors = errors, globalError = if (authExpired) LOGIN_EXPIRED else null) }
            !authExpired && (anyOk || results.isEmpty())
        } catch (e: HoyoException) {
            _state.update { it.copy(globalError = if (e.authExpired) LOGIN_EXPIRED else describe(e)) }
            false
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            _state.update { it.copy(globalError = describe(e)) }
            false
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun markAuthNotified() = updateCache { it.copy(authNotified = true) }

    // ---------------- Daily rewards: check-in and redemption codes ----------------

    private fun shownAccounts(g: Game) = shown().filter { it.first == g }.map { it.second }

    /** Codes on HoYoLAB that still need redeeming for a shown account (skips ones left to the website). */
    fun pendingCodes(include: (Game) -> Boolean): List<Triple<Game, GameAccount, String>> {
        val s = _state.value
        val r = s.cache.rewards
        return Game.entries.filter { s.settings.gameEnabled(it) && s.settings.codeMode(it) != "off" && include(it) }
            .flatMap { g ->
                r.offers[g.key].orEmpty().flatMap { o ->
                    shownAccounts(g).filter { a -> codeKey(g, a, o.code).let { it !in r.codesDone && it !in r.codesNeedYou } }
                        .map { a -> Triple(g, a, o.code) }
                }
            }
    }

    fun notifyPending() = pendingCodes { _state.value.settings.codeMode(it) == "notify" }

    /** Codes that hit a security check or similar, to redeem on the official website. */
    fun codesForWebsite(): List<Pair<Game, String>> {
        val r = _state.value.cache.rewards
        return Game.entries.flatMap { g ->
            r.offers[g.key].orEmpty().filter { o -> shownAccounts(g).any { codeKey(g, it, o.code) in r.codesNeedYou } }.map { g to it.code }
        }
    }

    fun checkInsForWebsite(): List<Game> {
        val s = _state.value
        val today = Rewards.today
        return Game.entries.filter {
            it.key in s.settings.autoCheckIn && s.cache.rewards.checkInNeedsYou[it.key] == today && s.cache.rewards.checkedIn[it.key] != today
        }
    }

    /**
     * Claims today's check-in where enabled, looks for new codes, redeems them in "auto" mode
     * (or in "notify" mode when [redeemNotifyCodes]), and returns a summary.
     */
    suspend fun runRewards(redeemNotifyCodes: Boolean = false): RewardsOutcome = rewardsLock.withLock {
        val c = cookie ?: return@withLock RewardsOutcome()
        _state.update { it.copy(rewardsRunning = true) }
        val messages = mutableListOf<String>()
        var newCodes = false
        var needsYou = false
        var loginNeeded = false
        try {
            val today = Rewards.today
            val settings = _state.value.settings

            val claimed = mutableListOf<String>()
            for (g in Game.entries) {
                if (!settings.gameEnabled(g) || g.key !in settings.autoCheckIn || shownAccounts(g).isEmpty()) continue
                if (_state.value.cache.rewards.checkedIn[g.key] == today) continue
                try {
                    val (signed, days) = Rewards.getCheckIn(c, g)
                    if (signed) { updateRewards { it.copy(checkedIn = it.checkedIn + (g.key to today)) }; continue }
                    val result = Rewards.checkIn(c, g)
                    Log.i(TAG, "check-in ${g.key}: $result")
                    if (result == CheckInResult.NEEDS_YOU) {
                        if (_state.value.cache.rewards.checkInNeedsYou[g.key] != today)
                            messages += "${g.title} check-in hit a security check. Claim it on the website."
                        updateRewards { it.copy(checkInNeedsYou = it.checkInNeedsYou + (g.key to today)) }
                        needsYou = true
                        continue
                    }
                    updateRewards { it.copy(checkedIn = it.checkedIn + (g.key to today), checkInNeedsYou = it.checkInNeedsYou - g.key) }
                    if (result == CheckInResult.CLAIMED) {
                        val reward = runCatching { Rewards.checkInReward(c, g, days) }.getOrNull()
                        claimed += if (reward == null) g.title else "${g.title} ($reward)"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "check-in ${g.key} failed: ${describe(e)}")
                }
            }
            if (claimed.isNotEmpty()) messages.add(0, "Checked in: " + claimed.joinToString(", "))

            for (g in Game.entries) {
                if (!settings.gameEnabled(g) || settings.codeMode(g) == "off" || shownAccounts(g).isEmpty()) continue
                runCatching { Rewards.getCodes(g) }
                    .onSuccess { offers -> updateRewards { it.copy(offers = it.offers + (g.key to offers)) } }
                    .onFailure { Log.w(TAG, "codes ${g.key} failed: ${describe(it)}") }
            }

            val fresh = notifyPending().map { it.first to it.third }.distinct()
                .filter { (g, code) -> "${g.key}:$code" !in _state.value.cache.rewards.codesAnnounced }
            if (fresh.isNotEmpty()) {
                updateRewards { r -> r.copy(codesAnnounced = r.codesAnnounced + fresh.map { "${it.first.key}:${it.second}" }) }
                if (!redeemNotifyCodes) {
                    messages += if (fresh.size == 1) "New ${fresh[0].first.title} code: ${fresh[0].second}"
                    else "${fresh.size} new redemption codes"
                    newCodes = true
                }
            }

            val todo = pendingCodes { settings.codeMode(it) == "auto" || (redeemNotifyCodes && settings.codeMode(it) == "notify") }
            val redeemed = mutableListOf<String>()
            for ((i, t) in todo.withIndex()) {
                val (g, a, code) = t
                val key = codeKey(g, a, code)
                if (i > 0) delay(5_500) // HoYoverse rejects redemptions less than ~5 s apart
                try {
                    var (result, detail) = Rewards.redeem(c, g, a, code)
                    if (result == RedeemResult.COOLDOWN) {
                        delay(6_000)
                        Rewards.redeem(c, g, a, code).let { result = it.first; detail = it.second }
                    }
                    Log.i(TAG, "redeem $key: $result ($detail)")
                    when (result) {
                        RedeemResult.REDEEMED -> {
                            updateRewards { it.copy(codesDone = it.codesDone + key) }
                            redeemed += if (todo.count { it.first == g && it.third == code } > 1) "$code (${a.nickname})" else code
                        }
                        RedeemResult.ALREADY_USED, RedeemResult.EXPIRED, RedeemResult.INVALID, RedeemResult.LEVEL_TOO_LOW ->
                            updateRewards { it.copy(codesDone = it.codesDone + key) }
                        RedeemResult.LOGIN_NEEDED -> loginNeeded = true
                        else -> {
                            updateRewards { it.copy(codesNeedYou = it.codesNeedYou + key) }
                            messages += "$code needs to be redeemed on the website."
                            needsYou = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "redeem $key failed: ${describe(e)}")
                }
                if (loginNeeded) break
            }
            if (loginNeeded) messages += "Redeeming codes needs a fresh HoYoLAB login."
            if (redeemed.isNotEmpty()) messages.add(0, "Redeemed: " + redeemed.joinToString(", "))
        } finally {
            _state.update { it.copy(rewardsRunning = false) }
        }
        RewardsOutcome(messages, newCodes, loginNeeded, needsYou)
    }
}
