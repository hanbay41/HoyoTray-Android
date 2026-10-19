package app.hoyotray.data

import app.hoyotray.model.Game
import app.hoyotray.model.GameAccount
import app.hoyotray.model.Note
import app.hoyotray.model.Stamina
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class HoyoException(val retcode: Int, message: String) : Exception(message) {
    val authExpired get() = retcode == -100 || retcode == 10001 || retcode == 10103
    val notPublic get() = retcode == 10102
    val rateLimited get() = retcode == 1034 || retcode == 10035 || retcode == 5003
}

/** HoYoLAB's unofficial API, as used by the HoYoLAB website and community tools. */
object HoyoApi {
    // Overseas (HoYoLAB) DS salt, as used by genshin.py and other community tools.
    private const val DS_SALT = "6s25p5ox5y14umn1p61aqyyvbvvl3lrt"
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private fun ds(): String {
        val t = System.currentTimeMillis() / 1000
        val letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val r = (1..6).map { letters.random() }.joinToString("")
        val hash = MessageDigest.getInstance("MD5").digest("salt=$DS_SALT&t=$t&r=$r".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$t,$r,$hash"
    }

    /** Raw request returning the whole response (retcode, message, data) without judging the retcode. */
    suspend fun request(
        method: String,
        url: String,
        cookie: String?,
        ds: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("User-Agent", DESKTOP_UA)
            conn.setRequestProperty("Referer", "https://act.hoyolab.com/")
            conn.setRequestProperty("Origin", "https://act.hoyolab.com")
            conn.setRequestProperty("x-rpc-app_version", "1.5.0")
            conn.setRequestProperty("x-rpc-client_type", "5")
            conn.setRequestProperty("x-rpc-language", "en-us")
            if (ds) conn.setRequestProperty("DS", ds())
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    fun retcode(root: JsonObject) = root["retcode"]!!.jsonPrimitive.int
    fun message(root: JsonObject) = root["message"]?.jsonPrimitive?.content ?: ""

    fun data(root: JsonObject): JsonObject {
        val rc = retcode(root)
        if (rc != 0) throw HoyoException(rc, message(root))
        return root["data"]!!.jsonObject
    }

    /** Numbers sometimes come back as strings. */
    fun JsonObject.num(name: String): Int = this[name]!!.jsonPrimitive.content.toInt()

    /** All accounts for a game_biz, highest level first. */
    suspend fun getAccounts(cookie: String, biz: String): List<GameAccount> {
        val d = data(request("GET",
            "https://api-account-os.hoyolab.com/account/binding/api/getUserGameRolesByCookie?game_biz=$biz", cookie))
        return d["list"]!!.jsonArray.map {
            val o = it.jsonObject
            GameAccount(
                o["game_uid"]!!.jsonPrimitive.content,
                o["region"]!!.jsonPrimitive.content,
                o["nickname"]?.jsonPrimitive?.content ?: "",
                o.num("level"),
            )
        }.sortedByDescending { it.level }
    }

    fun regionName(region: String) = when {
        "usa" in region || region.endsWith("_us") -> "America"
        "eur" in region || region.endsWith("_eu") -> "Europe"
        "asia" in region || region.endsWith("_jp") -> "Asia"
        "cht" in region || region.endsWith("_sg") -> "TW/HK/MO"
        else -> region
    }

    suspend fun fetchNote(cookie: String, g: Game, a: GameAccount): Note = when (g) {
        Game.GENSHIN -> {
            val d = data(request("GET",
                "https://bbs-api-os.hoyolab.com/game_record/genshin/api/dailyNote?server=${a.region}&role_id=${a.uid}",
                cookie, ds = true))
            val claimed = d["is_extra_task_reward_received"]!!.jsonPrimitive.boolean
            Note(
                Stamina(d.num("current_resin"), d.num("max_resin"), d.num("resin_recovery_time"), 480, System.currentTimeMillis()),
                "Commissions ${d.num("finished_task_num")}/${d.num("total_task_num")}" + if (claimed) " ✓" else "",
                claimed,
            )
        }
        Game.HSR -> {
            val d = data(request("GET",
                "https://bbs-api-os.hoyolab.com/game_record/hkrpg/api/note?server=${a.region}&role_id=${a.uid}",
                cookie, ds = true))
            val score = d.num("current_train_score")
            val max = d.num("max_train_score")
            Note(
                Stamina(d.num("current_stamina"), d.num("max_stamina"), d.num("stamina_recover_time"), 360, System.currentTimeMillis()),
                "Daily Training $score/$max" + if (score >= max) " ✓" else "",
                score >= max,
            )
        }
        Game.ZZZ -> {
            val d = data(request("GET",
                "https://sg-act-nap-api.hoyolab.com/event/game_record_zzz/api/zzz/note?server=${a.region}&role_id=${a.uid}",
                cookie, ds = true))
            val energy = d["energy"]!!.jsonObject
            val progress = energy["progress"]!!.jsonObject
            val vitality = d["vitality"]!!.jsonObject
            val cur = vitality.num("current")
            val max = vitality.num("max")
            Note(
                Stamina(progress.num("current"), progress.num("max"), energy.num("restore"), 360, System.currentTimeMillis()),
                "Engagement $cur/$max" + if (cur >= max) " ✓" else "",
                cur >= max,
            )
        }
    }
}
