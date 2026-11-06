package org.openstrap.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import org.openstrap.app.R
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.DetailRoutes
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.WidgetKind
import org.openstrap.app.ui.glow.draw.GlowMood
import org.openstrap.app.ui.glow.draw.WeekBarsLook
import org.openstrap.app.ui.glow.draw.drawPillBar
import org.openstrap.app.ui.glow.draw.drawWeekRings
import org.openstrap.app.ui.glow.draw.ChartInk
import org.openstrap.app.ui.glow.draw.hourPills
import org.openstrap.app.ui.glow.draw.renderToBitmap
import org.openstrap.app.ui.theme.MetricColors

/**
 * The Steps widget (widgets spec 3.3): today's steps against the goal, glanceable all day. The Wide adds what's to go
 * and the day so far as the Steps card's line, the Large this week day by day. An edge card lit from the bottom, a
 * value, in Steps' lime and the moment's mood, as Today's Steps card. The whole widget opens the Steps detail.
 */
class StepsWidget : LoadingWidget<StepsWidget.Shown>(WidgetKind.Steps) {
    override fun shown(data: WidgetData, context: Context): Shown = load(data)

    override fun sample(): Shown = Shown(WidgetSamples.steps(java.time.LocalDate.now()), GlowMood.Day)

    @Composable
    override fun Content(data: Shown) = StepsContent(data)

    /** What the widget shows and the light it's lit in. */
    data class Shown(val model: StepsWidgetModel, val mood: GlowMood)

    companion object {
        /**
         * Today's steps as Today's Steps card counts them: the synced minutes with the strap's live total on top
         * ([org.openstrap.app.data.LiveSteps]), the day's line from the minutes hour by hour, the goal from settings,
         * and this week's earlier days from the daily totals the app's week shows. The mood from the moment now
         * ([widgetMood]).
         */
        internal fun load(data: WidgetData): Shown {
            val read = checkNotNull(data.steps) { "not read for Steps" }
            val at = data.now.atZone(data.zone)
            val today = at.toLocalDate()
            val goal = read.goal.getOrThrow()
            val model = read.day?.let { d ->
                val hourly = Summaries.hourlySteps(d.minutes, DayWindow.of(today, data.zone).from)
                StepsWidgetModel.from(today, d.steps, hourly, at.hour, goal, d.past)
            } ?: StepsWidgetModel.from(today, 0, List(24) { 0f }, at.hour, goal, emptyMap())
            return Shown(model, widgetMood(data, data.usualBedtime))
        }
    }
}

/** The Steps widget's receiver. */
class StepsWidgetReceiver : PlacedWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()
}

/* ------------------------------------------------------------------------------------------------ */
/* Layout                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/** Steps' lime, as Today's Steps card. */
private val Ink = MetricColors.steps

/** The goal bar's track (the mockup's): white at 10%. */
private const val TRACK_ALPHA = 0.10f

/** The widget at the size Glance lays out: a dark picture card, as Today's Steps card. */
@Composable
private fun StepsContent(data: StepsWidget.Shown) {
    val m = data.model
    val open = openMetric(LocalContext.current, DetailRoutes.STEPS, m.date)
    PictureWidget(Ink, null, data.mood, open, BITMAP_EXTRA) { frame ->
        when (frame.bucket) {
            WidgetSizes.Small -> SmallContent(m, frame)
            WidgetSizes.Wide -> WideContent(m, frame)
            else -> LargeContent(m, frame)
        }
    }
}

/** Small: the top row, then the steps with "today", then the hours as pills (the goal bar before a step) and the share of the goal. */
@Composable
private fun ColumnScope.SmallContent(m: StepsWidgetModel, f: WidgetFrame) {
    val fit = StepsLayout.small(m.smallText, m.share, f.contentWidth, f.contentHeight, f.fontScale, f.status, pills = m.showLine)
    Edge(fit.spread, f)
    TopRowView(m.title, null, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    // Each block one child of the column: Glance drops a column's children past ten.
    Column(GlanceModifier.fillMaxWidth()) {
        DotNumber(m.smallText, fit.dot, f.density, m.description)
        if (fit.word) {
            Spacer(GlanceModifier.height(StepsLayout.SMALL_WORD_GAP_DP.dp))
            Text(m.word, style = WidgetType.word, maxLines = 1)
        }
    }
    Gap()
    Column(GlanceModifier.fillMaxWidth()) {
        StepsLine(m, StepsLayout.SMALL_PILLS_DP, f)
        if (fit.share) {
            Spacer(GlanceModifier.height(StepsLayout.SMALL_BAR_GAP_DP.dp))
            Text(m.share, style = WidgetType.secondary, maxLines = 1)
        }
    }
    Edge(fit.spread, f)
}

/** Wide: the top row with the goal, the steps with what's to go, the day so far as a line (or the goal bar). */
@Composable
private fun ColumnScope.WideContent(m: StepsWidgetModel, f: WidgetFrame) {
    val fit = StepsLayout.wide(m, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    Edge(fit.spread, f)
    TopRowView(m.title, m.goalNote, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    NumberRow(m, fit.row, f)
    Gap()
    StepsLine(m, fit.line, f)
    Edge(fit.spread, f)
}

/** Large: the Wide plus this week, day by day against the goal. */
@Composable
private fun ColumnScope.LargeContent(m: StepsWidgetModel, f: WidgetFrame) {
    val fit = StepsLayout.large(m, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    Edge(fit.spread, f)
    TopRowView(m.title, m.goalNote, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    NumberRow(m, fit.row, f)
    Gap()
    StepsLine(m, fit.line, f)
    Gap()
    fit.week?.let { WeekRow(m, it, f.density) }
    Edge(fit.spread, f)
}

/** The steps in dots with "today" beside them when it fits, what's to go (or "Goal met") on the right. */
@Composable
private fun NumberRow(m: StepsWidgetModel, row: StepsLayout.NumberRow, f: WidgetFrame) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        DotNumber(m.stepsText, row.dot, f.density, m.description)
        if (row.word) {
            Spacer(GlanceModifier.width(StepsLayout.WORD_GAP_DP.dp))
            Text(m.word, style = WidgetType.word, maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(m.toGo, style = WidgetType.secondary, maxLines = 1)
    }
}

/**
 * The day so far as Today's Steps card draws it, [height] dp high: the hours as lime pill bars, the latest full, the
 * rest dimmer, on a hairline baseline. The goal bar instead when there's no room or no step yet.
 */
@Composable
private fun StepsLine(m: StepsWidgetModel, height: Float?, f: WidgetFrame) {
    if (!m.showLine || height == null) {
        // No line (a large font, or none to draw): the goal bar shows the progress instead.
        GoalBar(m.progress, f)
        return
    }
    val w = f.drawContentWidth * f.density
    val h = height * f.density
    val bitmap = renderToBitmap(w, h) { c ->
        hourPills(m.hourly, null, w, h, f.density)?.let { g ->
            c.drawLine(0f, g.baselineY, w, g.baselineY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ChartInk.Grid.toArgb()
                strokeWidth = ChartInk.HAIRLINE_DP * f.density
            })
            g.bars.forEach { (bar, latest) -> drawPillBar(c, bar, if (latest) Ink else Ink.copy(alpha = HOUR_BAR_ALPHA)) }
        }
    }
    Image(
        ImageProvider(bitmap),
        contentDescription = LocalContext.current.getString(R.string.system_widget_steps_line_description, m.share),
        modifier = GlanceModifier.fillMaxWidth().height(height.dp),
        contentScale = ContentScale.FillBounds,
    )
}

/** The goal bar: 4 dp, lime on white at 10%, as far as the goal, full past it. */
@Composable
private fun GoalBar(progress: Float, f: WidgetFrame) {
    Image(
        ImageProvider(goalBar(progress, f.drawContentWidth, f.density)),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxWidth().height(StepsLayout.BAR_DP.dp),
        contentScale = ContentScale.FillBounds,
    )
}

/** "This week" and how many days met the goal on one line, then a ring a day under it, from the week's first day. */
@Composable
private fun WeekRow(m: StepsWidgetModel, week: StepsLayout.Week, density: Float) {
    // One child of the Large's column: Glance drops a column's children past ten.
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (week.title) {
                Text(StepsWidgetModel.THIS_WEEK, style = WidgetType.secondaryShown, maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
            }
            Text(m.weekLine, style = WidgetType.secondary, maxLines = 1)
        }
        Spacer(GlanceModifier.height(StepsLayout.WEEK_LINE_GAP_DP.dp))
        val description = LocalContext.current.getString(R.string.system_widget_steps_week_description)
        val today = m.week.map { it.letter to (it.state == StepsDay.Today) }
        val rings = ImageProvider(weekBitmap(m, week.column, density))
        WeekChart(rings, description, today, week.column, height = StepsLayout.ringHeight(week.column))
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Bitmaps                                                                                           */
/* ------------------------------------------------------------------------------------------------ */

/** The hours before the latest, a little dimmer than it, as on Today's Steps card. */
private const val HOUR_BAR_ALPHA = 0.6f

/** Room for the dots, the line, the bar and the week beside the backgrounds, in the bitmap budget. */
private const val BITMAP_EXTRA = 1.35

/** The goal bar [width] dp wide at [density]: the track, then the fill as far as [progress]. */
private fun goalBar(progress: Float, width: Float, density: Float): Bitmap {
    val w = width * density
    val h = StepsLayout.BAR_DP * density
    val r = h / 2
    return renderToBitmap(w, h) { c ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(0f, 0f, w, h, r, r, paint.apply { color = Color.White.copy(alpha = TRACK_ALPHA).toArgb() })
        val fill = w * progress.coerceIn(0f, 1f)
        if (fill > 0f) c.drawRoundRect(0f, 0f, fill.coerceAtLeast(h), h, r, r, paint.apply { color = Ink.toArgb() })
    }
}

/** This week as rings, as Today's Your week card draws them ([drawWeekRings]), a ring per column over its letter. */
private fun weekBitmap(m: StepsWidgetModel, column: Float, density: Float): Bitmap {
    val height = StepsLayout.ringHeight(column) * density
    return renderToBitmap(WeekBarsLook.width(m.week.size, column) * density, height) { c ->
        drawWeekRings(c, StepsWidgetModel.ringShares(m.week, m.goal), Ink, column * density, WeekBarsLook.GAP_DP * density, height, density)
    }
}
