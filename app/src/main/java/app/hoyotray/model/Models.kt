package app.hoyotray.model

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Everything game-specific; the rest of the app loops over [Game.entries]. */
enum class Game(
    val key: String,
    val title: String,
    val biz: String,
    val staminaName: String,
    val shortName: String,
    val accent: Long,
    val secondsPerUnit: Int,
) {
    GENSHIN("genshin", "Genshin Impact", "hk4e_global", "Original Resin", "Resin", 0xFF5AA9FF, 480),
    HSR("hsr", "Honkai: Star Rail", "hkrpg_global", "Trailblaze Power", "Power", 0xFFC492FF, 360),
    ZZZ("zzz", "Zenless Zone Zero", "nap_global", "Battery Charge", "Battery", 0xFFF5B83D, 360);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

@Serializable
data class GameAccount(val uid: String, val region: String, val nickname: String, val level: Int)

/** A regenerating stamina value (resin / power / battery) as of [fetchedAt] (epoch ms). */
@Serializable
data class Stamina(
    val current: Int,
    val max: Int,
    val recoverySeconds: Int,
    val secondsPerUnit: Int,
    val fetchedAt: Long,
) {
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Double =
        max(0.0, recoverySeconds - (now - fetchedAt) / 1000.0)

    /** Current value estimated from the regen timer, so it stays accurate between fetches. */
    fun estimate(now: Long = System.currentTimeMillis()): Int {
        if (recoverySeconds <= 0) return current
        val missing = ceil(remainingSeconds(now) / secondsPerUnit).toInt()
        return max(current, max - missing)
    }

    val fullAt: Long get() = fetchedAt + recoverySeconds * 1000L

    fun isFull(now: Long = System.currentTimeMillis()) = estimate(now) >= max

    /** "Full in 7h 44m · 23:40" or "Full!" */
    fun footer(now: Long = System.currentTimeMillis()): String {
        val r = remainingSeconds(now).toLong()
        if (r <= 0 || isFull(now)) return "Full!"
        val h = r / 3600
        val m = (r % 3600) / 60
        val left = if (h > 0) "Full in ${h}h ${m}m" else "Full in ${m}m"
        return "$left · ${clock(fullAt)}"
    }
}

/** Stamina plus one secondary daily stat (commissions / daily training / engagement). */
@Serializable
data class Note(val stamina: Stamina, val side: String, val sideDone: Boolean)

/** A redemption code offered on HoYoLAB (e.g. after a livestream). */
@Serializable
data class CodeOffer(val code: String, val rewards: String)

fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

fun noteKey(g: Game, a: GameAccount) = "${g.key}:${a.uid}"

fun codeKey(g: Game, a: GameAccount, code: String) = "${g.key}:${a.uid}:$code"
