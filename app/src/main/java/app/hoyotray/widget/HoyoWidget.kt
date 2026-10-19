package app.hoyotray.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.hoyotray.data.Repo
import app.hoyotray.data.shownAccounts
import app.hoyotray.model.Game
import app.hoyotray.model.Stamina
import app.hoyotray.model.clock
import app.hoyotray.model.noteKey
import app.hoyotray.ui.MainActivity

private data class WidgetRow(val game: Game, val name: String, val stamina: Stamina?)

/**
 * Home-screen widget. It never waits for the network: it draws the cached values and recomputes them
 * from the regen timer on every update, and shows the clock time each resource caps (which can't go stale).
 */
class HoyoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Repo.init(context)
        val s = Repo.state.value
        val shown = shownAccounts(s.settings, s.cache)
        val rows = shown.take(4).map { (g, a) ->
            val several = shown.count { it.first == g } > 1
            WidgetRow(g, if (several) "${g.shortName} · ${a.nickname}" else g.shortName, s.cache.notes[noteKey(g, a)]?.stamina)
        }
        val loggedIn = s.loggedIn
        provideContent { Content(rows, loggedIn) }
    }

    @Composable
    private fun Content(rows: List<WidgetRow>, loggedIn: Boolean) {
        val white = ColorProvider(Color(0xFFF5F5F5))
        val dim = ColorProvider(Color(0xFFA8A8A8))
        val now = System.currentTimeMillis()
        Column(
            modifier = GlanceModifier.fillMaxSize().background(Color(0xE6202127)).cornerRadius(18.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!loggedIn || rows.isEmpty()) {
                Text(if (!loggedIn) "Tap to log in to HoYoLAB" else "Nothing selected", style = TextStyle(color = dim, fontSize = 13.sp))
                return@Column
            }
            // Glance allows at most 10 children per container, so each game gets its own Column.
            rows.forEachIndexed { i, row ->
                if (i > 0) Spacer(GlanceModifier.height(10.dp))
                GameRow(row, now, white, dim)
            }
        }
    }
}

@Composable
private fun GameRow(row: WidgetRow, now: Long, white: ColorProvider, dim: ColorProvider) {
    val accent = ColorProvider(Color(row.game.accent))
    val st = row.stamina
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("●", style = TextStyle(color = accent, fontSize = 12.sp))
            Spacer(GlanceModifier.width(6.dp))
            Text(row.name, style = TextStyle(color = dim, fontSize = 14.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            val full = st?.isFull(now) == true
            Text(st?.let { "${it.estimate(now)}/${it.max}" } ?: "—",
                style = TextStyle(color = if (full) accent else white, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.height(4.dp))
        LinearProgressIndicator(
            progress = st?.let { (it.estimate(now).toFloat() / it.max).coerceIn(0f, 1f) } ?: 0f,
            modifier = GlanceModifier.fillMaxWidth().height(5.dp),
            color = accent,
            backgroundColor = ColorProvider(Color(0x33FFFFFF)),
        )
        if (st != null) {
            Spacer(GlanceModifier.height(3.dp))
            Text(if (st.isFull(now)) "Full!" else "Full at ${clock(st.fullAt)}",
                style = TextStyle(color = if (st.isFull(now)) accent else dim, fontSize = 12.sp))
        }
    }
}

class HoyoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HoyoWidget()
}
