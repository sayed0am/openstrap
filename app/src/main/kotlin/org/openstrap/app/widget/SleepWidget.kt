package org.openstrap.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.unit.ColorProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.SLEEP_NIGHTS
import org.openstrap.app.ui.glow.EdgeKind
import org.openstrap.app.ui.glow.EdgeStatus
import org.openstrap.app.ui.glow.GlowMood
import org.openstrap.app.ui.glow.draw.drawStageBar
import org.openstrap.app.ui.glow.draw.renderToBitmap
import org.openstrap.app.ui.glow.edgeLight
import org.openstrap.app.ui.glow.stageColor
import org.openstrap.app.ui.theme.MetricColors

/**
 * The Sleep widget (widgets spec 3.2): last night's score, time asleep and stages, as the Sleep tab's hero tells them.
 * The Wide adds bedtime to waking and the stage legend, the Large the tab's sentence, how often you woke and the
 * time in each stage. An edge card lit from the top like a night sky, in Sleep's ink and always the night mood,
 * as the Sleep tab. The whole widget opens the Sleep score's detail for that night.
 */
class SleepWidget : LoadingWidget<SleepWidgetModel>(WidgetKind.Sleep) {
    override suspend fun loadShown(context: Context): SleepWidgetModel = load(context)

    override fun sample(): SleepWidgetModel = ZoneId.systemDefault().let { WidgetSamples.sleep(LocalDate.now(it), it) }

    @Composable
    override fun Content(data: SleepWidgetModel) = SleepContent(data)

    companion object {
        /**
         * This morning's night as the Sleep tab reads it ([SleepHistory.mainSleep] over the night ending today, its
         * stages, the week's other nights from [SleepHistory.mainSleeps] over the tab's [SLEEP_NIGHTS]), and
         * yesterday's while this morning's isn't synced. "After your first night" until the strap has stored a sleep.
         */
        internal suspend fun load(context: Context, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): SleepWidgetModel {
            val container = (context.applicationContext as OpenStrapApp).container
            val dao = container.database.healthDao()
            val today = now.atZone(zone).toLocalDate()
            return try {
                val span = DayWindow(DayWindow.nightEndingOn(today.minusDays(SLEEP_NIGHTS - 1L), zone).from, DayWindow.nightEndingOn(today, zone).to)
                val sessions = org.openstrap.app.data.SleepSource(dao).sessionsOnce(span.from, span.to)
                val nights = SleepHistory.mainSleeps(sessions, zone)
                suspend fun night(date: LocalDate): SleepWidgetNight? = SleepHistory.mainSleep(sessions, DayWindow.nightEndingOn(date, zone))?.let { main ->
                    SleepWidgetNight.of(date, main, org.openstrap.app.data.SleepSource(dao).stagesOnce(main), nights)
                }
                val tonight = night(today)
                // A night the user deleted is this morning's answer: yesterday's doesn't stand in for it.
                val deleted = tonight == null && DayWindow.nightEndingOn(today, zone).let { w -> org.openstrap.app.data.SleepSource(dao).nightDeleted(w.from, w.to) }
                val yesterday = if (tonight == null && !deleted) night(today.minusDays(1)) else null
                val first = tonight == null && yesterday == null && !deleted && nights.isEmpty() && dao.latestSleepEnd().first() == null
                val day = DayWindow.of(today, zone)
                val naps = sessions.any { it.nap && it.start in day.from..day.to }
                SleepWidgetModel.from(tonight, yesterday, first, naps, zone, deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(WIDGET_TAG, "widgets: sleep for $today not read: $e")
                SleepWidgetModel.from(null, null, firstNight = false, napsToday = false, zone = zone)
            }
        }
    }
}

/** The Sleep widget's receiver. The first one placed stops Moment leading with last night (spec 3.1). */
class SleepWidgetReceiver : PlacedWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepWidget()
}

/* ------------------------------------------------------------------------------------------------ */
/* Layout                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/** Sleep's ink, as the Sleep tab and Today's Last night card. */
private val Ink = MetricColors.sleepDeep

/** The legend's labels: Caption size and weight, at Secondary's 72% (the mockup's). */
private val LegendStyle = WidgetType.caption.copy(color = ColorProvider(Color.White.copy(alpha = 0.72f)))

/**
 * The widget at the size Glance lays out, lit from the top in the night mood whatever the moment ([EdgeKind.Sleep],
 * spec 2.2). A night opens its Sleep score; while waiting, the Sleep screen.
 */
@Composable
private fun SleepContent(m: SleepWidgetModel) {
    val context = LocalContext.current
    val open = if (m.date != null) openMetric(context, Detail.SLEEP_SCORE_ROUTE, m.date) else openMetric(context, SLEEP_ROUTE, null)
    EdgeWidget(edgeLight(EdgeStatus.Usual, EdgeKind.Sleep), Ink, GlowMood.Night, open, BITMAP_EXTRA) { frame ->
        when {
            m.waiting != null -> WaitingContent(m.title, m.waiting, frame)
            frame.bucket == WidgetSizes.Small -> SmallContent(m, frame)
            frame.bucket == WidgetSizes.Wide -> WideContent(m, frame)
            else -> LargeContent(m, frame)
        }
    }
}

/** A gap between blocks: they share the spare height alike, with half a gap above and below. */
@Composable
private fun ColumnScope.Gap() = Spacer(GlanceModifier.defaultWeight())

/** The half gap above the first block or below the last, at the cell's real height. */
@Composable
private fun Edge(spread: Spread, f: WidgetFrame) = Spacer(GlanceModifier.height(spread.edge(f.drawContentHeight).dp))

/** Small: the top row, the score and its word, then time asleep and the stage bar. */
@Composable
private fun ColumnScope.SmallContent(m: SleepWidgetModel, f: WidgetFrame) {
    val fit = SleepLayout.small(m, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    Edge(fit.spread, f)
    TopRowView(m.title, null, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    ScoreRow(m, fit.row, SleepLayout.SMALL_WORD_GAP_DP, f.density)
    if (fit.lines.isNotEmpty() || fit.bar) {
        Gap()
        // One child of the column, its rows inside: Glance drops a column's children past ten.
        Column(GlanceModifier.fillMaxWidth()) {
            fit.lines.forEachIndexed { i, line ->
                if (i > 0) Spacer(GlanceModifier.height(SleepLayout.SMALL_GAP_DP.dp))
                Text(line.text, style = WidgetType.secondary, maxLines = line.lines)
            }
            if (fit.bar) {
                if (fit.lines.isNotEmpty()) Spacer(GlanceModifier.height(SleepLayout.SMALL_GAP_DP.dp))
                StageBar(m, SleepLayout.SMALL_BAR_DP, f)
            }
        }
    }
    Edge(fit.spread, f)
}

/** Wide: the top row with bedtime to waking, the score with time asleep, the stage bar with its legend. */
@Composable
private fun ColumnScope.WideContent(m: SleepWidgetModel, f: WidgetFrame) {
    val fit = SleepLayout.wide(m, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    Edge(fit.spread, f)
    TopRowView(m.title, m.note, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    ScoreRow(m, fit.row, SleepLayout.WORD_GAP_DP, f.density)
    if (fit.bar) {
        Gap()
        Stages(m, fit.legendRows, f)
    }
    Edge(fit.spread, f)
}

/** Large: the top row, the tab's sentence, the score with how often you woke, then the time in each stage. */
@Composable
private fun ColumnScope.LargeContent(m: SleepWidgetModel, f: WidgetFrame) {
    val fit = SleepLayout.large(m, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    val spread = SleepLayout.largeSpread(fit, m.stages.size, f.fontScale)
    Edge(spread, f)
    TopRowView(m.title, m.note, fit.top, f, spread.edge(f.drawContentHeight))
    if (fit.sentenceLines > 0 && m.sentence != null) {
        Gap()
        Text(m.sentence, style = WidgetType.hero, maxLines = fit.sentenceLines)
    }
    Gap()
    ScoreRow(m, fit.row, SleepLayout.WORD_GAP_DP, f.density)
    when {
        fit.stages -> { Gap(); StageRows(m.stages, f) }
        fit.bar -> { Gap(); StageBar(m, SleepLayout.BAR_DP, f) }
    }
    Edge(spread, f)
}

/**
 * The score in dots with its word beside it and [SleepLayout.ScoreRow.right] on the right; without a score, the
 * words saying so in its place.
 */
@Composable
private fun ScoreRow(m: SleepWidgetModel, row: SleepLayout.ScoreRow, wordGap: Float, density: Float) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        val score = m.score
        if (row.dot > 0f && score != null) {
            DotNumber(score, row.dot, density, m.scoreDescription)
            row.word?.let {
                Spacer(GlanceModifier.width(wordGap.dp))
                Text(it, style = WidgetType.word, maxLines = 1)
            }
            Spacer(GlanceModifier.defaultWeight())
        } else row.word?.let {
            Text(it, style = WidgetType.word, maxLines = row.wordLines, modifier = GlanceModifier.defaultWeight())
        }
        row.right?.let {
            Spacer(GlanceModifier.width(SleepLayout.SIDE_GAP_DP.dp))
            Text(it, style = WidgetType.secondary, maxLines = 1)
        }
    }
}

/** The stage bar, 8 dp, with its legend under it on [legendRows] rows (none at 0). */
@Composable
private fun Stages(m: SleepWidgetModel, legendRows: Int, f: WidgetFrame) {
    // One child of the widget's column: Glance drops a column's children past ten.
    Column(GlanceModifier.fillMaxWidth()) {
        StageBar(m, SleepLayout.BAR_DP, f, described = legendRows == 0)
        if (legendRows > 0) {
            Spacer(GlanceModifier.height(SleepLayout.BAR_GAP_DP.dp))
            Legend(m.legend, legendRows)
        }
    }
}

/**
 * The legend: one row of every stage spread across the width, or two rows of two, each item in half the width, so a
 * label never wraps ([SleepLayout.legendRows] decides).
 */
@Composable
private fun Legend(items: List<SleepLegendItem>, rows: Int) {
    if (rows == 1) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, item ->
                if (i > 0) Spacer(GlanceModifier.defaultWeight())
                LegendItem(item)
            }
        }
        return
    }
    Column(GlanceModifier.fillMaxWidth()) {
        items.chunked(2).forEachIndexed { r, pair ->
            if (r > 0) Spacer(GlanceModifier.height(SleepLayout.LEGEND_ROW_GAP_DP.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                pair.forEach { item -> Box(GlanceModifier.defaultWeight()) { LegendItem(item) } }
                if (pair.size == 1) Box(GlanceModifier.defaultWeight()) {}
            }
        }
    }
}

/** An 8 dp colour mark and the stage's total, "Deep 1 h 32". */
@Composable
private fun LegendItem(item: SleepLegendItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            GlanceModifier.size(SleepLayout.MARK_DP.dp).cornerRadius(2.dp).background(ColorProvider(stageColor(item.stage))),
        ) {}
        Spacer(GlanceModifier.width(SleepLayout.MARK_GAP_DP.dp))
        Text(item.label, style = LegendStyle, maxLines = 1)
    }
}

/** The stage bar [heightDp] high across the content: the Sleep tab's [drawStageBar]. Read out when there's no legend. */
@Composable
private fun StageBar(m: SleepWidgetModel, heightDp: Float, f: WidgetFrame, described: Boolean = true) {
    val w = f.drawContentWidth * f.density
    val bitmap = renderToBitmap(w, heightDp * f.density) { c -> drawStageBar(c, m.totals, 0f, 0f, w, f.density, heightDp) }
    Image(
        ImageProvider(bitmap),
        contentDescription = if (described) m.legend.joinToString(", ") { it.label } else null,
        modifier = GlanceModifier.fillMaxWidth().height(heightDp.dp),
        contentScale = ContentScale.FillBounds,
    )
}

/**
 * The time in each stage, as the Sleep score detail lists it: the name, a bar for its share of the night in the stage's
 * colour on a white 10% track, the time on the right. One child of the column, a row a stage.
 */
@Composable
private fun StageRows(rows: List<SleepStageRow>, f: WidgetFrame) {
    val track = f.drawContentWidth - SleepLayout.STAGE_NAME_DP - SleepLayout.STAGE_TIME_DP - 2 * SleepLayout.STAGE_GAP_DP
    Column(GlanceModifier.fillMaxWidth()) {
        rows.forEachIndexed { i, r ->
            if (i > 0) Spacer(GlanceModifier.height(SleepLayout.STAGE_ROW_GAP_DP.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, style = WidgetType.secondary, maxLines = 1, modifier = GlanceModifier.width(SleepLayout.STAGE_NAME_DP.dp))
                Spacer(GlanceModifier.width(SleepLayout.STAGE_GAP_DP.dp))
                Image(
                    ImageProvider(stageRowBar(r, track, f.density)),
                    contentDescription = "${r.name} ${r.time}",
                    modifier = GlanceModifier.defaultWeight().height(SleepLayout.STAGE_BAR_DP.dp),
                    contentScale = ContentScale.FillBounds,
                )
                Spacer(GlanceModifier.width(SleepLayout.STAGE_GAP_DP.dp))
                Text(
                    r.time,
                    style = WidgetType.secondaryShown.copy(textAlign = TextAlign.End),
                    maxLines = 1,
                    modifier = GlanceModifier.width(SleepLayout.STAGE_TIME_DP.dp),
                )
            }
        }
    }
}

/** A stage row's bar [widthDp] wide: the white 10% track, the stage's share filled in its colour, both fully round. */
private fun stageRowBar(r: SleepStageRow, widthDp: Float, density: Float): Bitmap {
    val w = (widthDp * density).coerceAtLeast(1f)
    val h = SleepLayout.STAGE_BAR_DP * density
    return renderToBitmap(w, h) { c ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.argb(26, 255, 255, 255)
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
        paint.color = stageColor(r.stage).toArgb()
        val fill = (w * r.share).coerceIn(h, w)
        c.drawRoundRect(0f, 0f, fill, h, h / 2, h / 2, paint)
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Bitmaps                                                                                           */
/* ------------------------------------------------------------------------------------------------ */

/** The route `open_metric` takes for the Sleep screen, while there's no night to open the score of. */
internal const val SLEEP_ROUTE = "Sleep"

/** Room for the dots, the stage bar and the stage rows' bars beside the backgrounds, in the bitmap budget. */
private const val BITMAP_EXTRA = 1.35
