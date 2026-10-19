package app.hoyotray.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.hoyotray.model.CodeOffer
import app.hoyotray.model.Game
import app.hoyotray.model.GameAccount
import app.hoyotray.model.Note
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User choices. */
@Serializable
data class Settings(
    val disabledGames: Set<String> = emptySet(),
    /** "game:uid" → shown. Accounts not listed fall back to "highest level only". */
    val accounts: Map<String, Boolean> = emptyMap(),
    /** Games whose HoYoLAB daily check-in is claimed automatically. Off by default. */
    val autoCheckIn: Set<String> = emptySet(),
    /** Game key → "off" / "notify" / "auto". Missing means "notify". */
    val codes: Map<String, String> = emptyMap(),
) {
    fun gameEnabled(g: Game) = g.key !in disabledGames
    fun accountShown(g: Game, a: GameAccount, all: List<GameAccount>) =
        accounts["${g.key}:${a.uid}"] ?: (all.firstOrNull()?.uid == a.uid)
    fun codeMode(g: Game) = codes[g.key] ?: "notify"
}

/** What the rewards features have done, so nothing is claimed, redeemed or announced twice. */
@Serializable
data class RewardState(
    val checkedIn: Map<String, String> = emptyMap(),       // game → day (UTC+8) confirmed claimed
    val checkInNeedsYou: Map<String, String> = emptyMap(), // game → day a captcha blocked it
    val offers: Map<String, List<CodeOffer>> = emptyMap(), // game → codes currently on HoYoLAB
    val codesDone: Set<String> = emptySet(),               // "game:uid:CODE" handled for good
    val codesNeedYou: Set<String> = emptySet(),            // "game:uid:CODE" to redeem on the website
    val codesAnnounced: Set<String> = emptySet(),          // "game:CODE" already notified
)

/** Last known data, shown instantly on launch and by the widget. Contains no credentials. */
@Serializable
data class Cache(
    val accounts: Map<String, List<GameAccount>> = emptyMap(), // by game key
    val accountsFetchedAt: Long = 0,
    val notes: Map<String, Note> = emptyMap(),                  // by "game:uid"
    val lastUpdated: Long? = null,
    val rewards: RewardState = RewardState(),
    val authNotified: Boolean = false,
)

/** Enabled games and the accounts the user chose to show, in display order. */
fun shownAccounts(settings: Settings, cache: Cache): List<Pair<Game, GameAccount>> =
    Game.entries.flatMap { g ->
        if (!settings.gameEnabled(g)) emptyList()
        else {
            val list = cache.accounts[g.key].orEmpty()
            list.filter { settings.accountShown(g, it, list) }.map { g to it }
        }
    }

object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val KEY_ALIAS = "hoyotray_cookie"

    private fun file(ctx: Context, name: String) = File(ctx.filesDir, name)

    fun loadSettings(ctx: Context): Settings =
        runCatching { json.decodeFromString<Settings>(file(ctx, "settings.json").readText()) }.getOrDefault(Settings())

    fun saveSettings(ctx: Context, s: Settings) = file(ctx, "settings.json").writeText(json.encodeToString(s))

    fun loadCache(ctx: Context): Cache =
        runCatching { json.decodeFromString<Cache>(file(ctx, "cache.json").readText()) }.getOrDefault(Cache())

    fun saveCache(ctx: Context, c: Cache) = runCatching { file(ctx, "cache.json").writeText(json.encodeToString(c)) }

    // ---- Login cookie, encrypted with a key that never leaves the Android Keystore ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("secure", Context.MODE_PRIVATE)

    fun saveCookie(ctx: Context, cookie: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(cookie.toByteArray())
        prefs(ctx).edit().putString("cookie", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun loadCookie(ctx: Context): String? = runCatching {
        val blob = Base64.decode(prefs(ctx).getString("cookie", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        String(cipher.doFinal(blob, 12, blob.size - 12))
    }.getOrNull()

    fun clearCookie(ctx: Context) = prefs(ctx).edit().remove("cookie").apply()
}
