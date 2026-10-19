package app.hoyotray.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.hoyotray.data.HoyoApi.num
import app.hoyotray.model.CodeOffer
import app.hoyotray.model.Game
import app.hoyotray.model.GameAccount
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class CheckInResult { CLAIMED, ALREADY_CLAIMED, NEEDS_YOU }

enum class RedeemResult { REDEEMED, ALREADY_USED, EXPIRED, INVALID, LEVEL_TOO_LOW, COOLDOWN, LOGIN_NEEDED, NEEDS_YOU }

/** Daily check-in and redemption-code endpoints, plus the official pages to fall back to. */
object Rewards {
    private class Endpoints(
        val checkInBase: String, val actId: String, val signGame: String?, val hoyolabGameId: Int,
        val redeemUrl: String, val checkInPage: String, val redeemPage: String,
    )

    private val byGame = mapOf(
        Game.GENSHIN to Endpoints("https://sg-hk4e-api.hoyolab.com/event/sol", "e202102251931481", null, 2,
            "https://sg-hk4e-api.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "https://act.hoyolab.com/ys/event/signin-sea-v3/index.html?act_id=e202102251931481",
            "https://genshin.hoyoverse.com/en/gift?code="),
        Game.HSR to Endpoints("https://sg-public-api.hoyolab.com/event/luna/os", "e202303301540311", "hkrpg", 6,
            "https://sg-hkrpg-api.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "https://act.hoyolab.com/bbs/event/signin/hkrpg/e202303301540311.html?act_id=e202303301540311",
            "https://hsr.hoyoverse.com/gift?code="),
        Game.ZZZ to Endpoints("https://sg-act-nap-api.hoyolab.com/event/luna/zzz/os", "e202406031448091", "zzz", 8,
            "https://public-operation-nap.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "https://act.hoyolab.com/bbs/event/signin/zzz/e202406031448091.html?act_id=e202406031448091",
            "https://zenless.hoyoverse.com/redemption?code="),
    )

    /** HoYoLAB's day rolls over at midnight UTC+8. */
    val today: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT+8") }.format(Date())

    private fun signHeaders(e: Endpoints) = e.signGame?.let { mapOf("x-rpc-signgame" to it) } ?: emptyMap()

    /** (already claimed today, days claimed this month). */
    suspend fun getCheckIn(cookie: String, g: Game): Pair<Boolean, Int> {
        val e = byGame.getValue(g)
        val d = HoyoApi.data(HoyoApi.request("GET", "${e.checkInBase}/info?lang=en-us&act_id=${e.actId}", cookie, headers = signHeaders(e)))
        return (d["is_sign"]!!.jsonPrimitive.booleanOrNull == true) to d.num("total_sign_day")
    }

    suspend fun checkIn(cookie: String, g: Game): CheckInResult {
        val e = byGame.getValue(g)
        val root = HoyoApi.request("POST", "${e.checkInBase}/sign?lang=en-us", cookie,
            headers = signHeaders(e), body = """{"act_id":"${e.actId}"}""")
        if (HoyoApi.retcode(root) == -5003) return CheckInResult.ALREADY_CLAIMED
        val d = HoyoApi.data(root)
        // A captcha challenge comes back as success with a risk flag (Genshin nests it in gt_result).
        val risk = (d["gt_result"] as? JsonObject) ?: d
        val isRisk = (risk["is_risk"] as? JsonPrimitive)?.booleanOrNull == true
        val riskCode = (risk["risk_code"] as? JsonPrimitive)?.intOrNull ?: 0
        return if (isRisk || riskCode != 0) CheckInResult.NEEDS_YOU else CheckInResult.CLAIMED
    }

    /** Name of the reward for the given day index this month, e.g. "20× Primogem". */
    suspend fun checkInReward(cookie: String, g: Game, dayIndex: Int): String? {
        val e = byGame.getValue(g)
        val d = HoyoApi.data(HoyoApi.request("GET", "${e.checkInBase}/home?lang=en-us&act_id=${e.actId}", cookie, headers = signHeaders(e)))
        val awards = d["awards"]!!.jsonArray
        if (dayIndex !in awards.indices) return null
        val a = awards[dayIndex].jsonObject
        return "${a.num("cnt")}× ${a["name"]!!.jsonPrimitive.content}"
    }

    /** Codes currently shown in the redemption-code widget on the game's HoYoLAB page. */
    suspend fun getCodes(g: Game): List<CodeOffer> {
        val e = byGame.getValue(g)
        val d = HoyoApi.data(HoyoApi.request("GET",
            "https://bbs-api-os.hoyolab.com/community/painter/wapi/circle/channel/guide/material?game_id=${e.hoyolabGameId}",
            cookie = null, headers = mapOf("x-rpc-client_type" to "4", "x-rpc-app_version" to "2.42.0")))
        val offers = mutableListOf<CodeOffer>()
        for (module in d["modules"]!!.jsonArray) {
            val group = module.jsonObject["exchange_group"] as? JsonObject ?: continue
            val bonuses = runCatching { group["bonuses"]!!.jsonArray }.getOrNull() ?: continue
            for (b in bonuses) {
                val o = b.jsonObject
                val code = (o["exchange_code"] as? JsonPrimitive)?.content?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
                val status = (o["code_status"] as? JsonPrimitive)?.content
                if (status != null && status != "ON") continue
                val amounts = runCatching {
                    o["icon_bonuses"]!!.jsonArray.mapNotNull { (it.jsonObject["bonus_num"] as? JsonPrimitive)?.content }.joinToString(" + ")
                }.getOrDefault("")
                if (offers.none { it.code == code }) offers += CodeOffer(code, amounts)
            }
        }
        return offers
    }

    suspend fun redeem(cookie: String, g: Game, a: GameAccount, code: String): Pair<RedeemResult, String> {
        val e = byGame.getValue(g)
        val c = URLEncoder.encode(code, "UTF-8")
        val root = HoyoApi.request("GET",
            "${e.redeemUrl}?uid=${a.uid}&region=${a.region}&lang=en&cdkey=$c&game_biz=${g.biz}&sLangKey=en-us", cookie)
        val rc = HoyoApi.retcode(root)
        val result = when (rc) {
            0 -> RedeemResult.REDEEMED
            -2017, -2018 -> RedeemResult.ALREADY_USED
            -2001 -> RedeemResult.EXPIRED
            -2003, -2004 -> RedeemResult.INVALID
            -2011 -> RedeemResult.LEVEL_TOO_LOW
            -2016 -> RedeemResult.COOLDOWN
            -1071 -> RedeemResult.LOGIN_NEEDED
            else -> RedeemResult.NEEDS_YOU
        }
        return result to "retcode $rc ${HoyoApi.message(root)}"
    }

    fun checkInPage(g: Game) = byGame.getValue(g).checkInPage
    fun redeemPage(g: Game, code: String) = byGame.getValue(g).redeemPage + URLEncoder.encode(code, "UTF-8")

    fun open(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
