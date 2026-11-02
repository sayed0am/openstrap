package org.openstrap.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.first
import org.openstrap.app.MainActivity
import org.openstrap.app.R
import org.openstrap.app.data.WidgetKind
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.theme.ControlSurface
import org.openstrap.app.ui.glow.draw.EdgeKind
import org.openstrap.app.ui.glow.draw.EdgeStatus
import org.openstrap.app.ui.glow.draw.GlowMood
import org.openstrap.app.ui.glow.draw.drawIcon
import org.openstrap.app.ui.glow.draw.renderToBitmap
import org.openstrap.app.ui.glow.draw.edgeLight
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.SportIcons
import java.time.Instant
import java.time.ZoneId

/**
 * The Start a workout widget (widgets spec 3.5), the one action widget: home screen to Ready in one tap. The usual
 * sport with its play button at every size, the next four as tiles on the Wide, this week and the usual's last on the
 * Large; while a workout records, that workout with Open. An edge card lit from the right, toward Start, in heart ink
 * and the moment's mood. Play, a tile or the widget opens Ready with that sport, All sports the sport picker.
 */
class StartWidget : LoadingWidget<StartWidget.Shown>(WidgetKind.Start) {
    override fun shown(data: WidgetData, context: Context): Shown = load(data)

    override fun sample(): Shown = Shown(WidgetSamples.start(Instant.now(), ZoneId.systemDefault()), GlowMood.Day)

    @Composable
    override fun Content(data: Shown) = StartContent(data)

    /** What the widget shows and the light it's lit in. */
    data class Shown(val model: StartWidgetModel, val mood: GlowMood)

    companion object {
        /**
         * Every workout as the workout lists read them ([Workouts.listed]), the Outdoor or Indoor side last chosen per
         * sport and whether location is on, as Ready reads them, and the workout recording then. The mood from the
         * moment then ([widgetMood]).
         */
        internal fun load(data: WidgetData): Shown {
            val read = checkNotNull(data.start) { "not read for Start" }
            val recording = (read.state as? WorkoutState.Recording)?.let { r ->
                val w = r.workout
                StartRecordingInput(w.sportType, w.start, WorkoutRecorder.activeSeconds(w, data.now.epochSecond), r.paused)
            }
            val model = StartWidgetModel.from(read.workouts.orEmpty(), read.sides, read.location, recording, data.now, data.zone)
            return Shown(model, widgetMood(data, data.usualBedtime))
        }
    }
}

/** The Start widget's receiver. */
class StartWidgetReceiver : PlacedWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StartWidget()
}

/* ------------------------------------------------------------------------------------------------ */
/* Taps                                                                                              */
/* ------------------------------------------------------------------------------------------------ */

/** Ready with [sport] (spec 4), as a launcher shortcut opens it. */
internal fun openReady(context: Context, sport: Int): Intent =
    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_READY_SPORT, sport).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Ready on the sport picker: All sports. */
internal fun openSportPicker(context: Context): Intent =
    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_READY_PICK, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** The live screen of the workout recording, as its notification opens it. */
internal fun openLiveWorkout(context: Context): Intent =
    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_WORKOUT, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

/* ------------------------------------------------------------------------------------------------ */
/* Layout                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/** Heart ink, as Ready's hero: the light toward Start. */
private val Ink = MetricColors.heart

/** The name in the usual row: Body 500. */
private val NameStyle = WidgetType.body.copy(fontWeight = FontWeight.Medium)

/** Open's word, black on its white pill. */
private val OpenStyle = WidgetType.body.copy(color = ColorProvider(Color.Black), fontWeight = FontWeight.Medium)

/** Back to the card's 20 dp side padding, for every row but the tile captions, which may run into it. */
private val Inset = GlanceModifier.padding(horizontal = StartLayout.CAPTION_BLEED_DP.dp)

/** The Large's hairline: white at 8%, at full strength in every mood (spec 2.2). */
private const val HAIRLINE_ALPHA = 0.08f

/**
 * The widget at the size Glance lays out, lit from the right ([EdgeKind.Live]'s edge). The whole widget opens Ready
 * with the usual, or the live screen while a workout records.
 */
@Composable
private fun StartContent(data: StartWidget.Shown) {
    val m = data.model
    val context = LocalContext.current
    val open = if (m.recording != null) openLiveWorkout(context) else openReady(context, m.usual.sportType)
    // The side padding is half the card's, so the tile captions can run into it; everything else pads back ([Inset]).
    // While a workout records, the recording is the news and the live screen shows the strap: no status line.
    EdgeWidget(
        edgeLight(EdgeStatus.Usual, EdgeKind.Live), Ink, data.mood, open, BITMAP_EXTRA,
        sidePadding = WidgetSizes.PADDING_DP - StartLayout.CAPTION_BLEED_DP, showStatus = m.recording == null,
    ) { frame ->
        val rec = m.recording
        // Start has no title row, so with the strap status (spec 5) the Wide and Large get "Start a workout" above the
        // usual row, the status button at its right; the Small gets a title row too ([StatusSmallContent]).
        val top = if (rec != null || frame.bucket == WidgetSizes.Small) null
        else StartLayout.statusTop(m, frame.status, frame.bucket, frame.contentWidth, frame.contentHeight, frame.fontScale)
        when {
            rec != null && frame.bucket == WidgetSizes.Small -> Column(GlanceModifier.fillMaxWidth().defaultWeight().then(Inset)) { SmallRecording(m, rec, frame) }
            rec != null && frame.bucket == WidgetSizes.Wide -> WideRecording(m, rec, frame)
            rec != null -> LargeRecording(m, rec, frame)
            frame.bucket == WidgetSizes.Small -> Column(GlanceModifier.fillMaxWidth().defaultWeight().then(Inset)) { SmallContent(m, frame) }
            frame.bucket == WidgetSizes.Wide -> WideContent(m, frame, top)
            else -> LargeContent(m, frame, top)
        }
    }
}

/**
 * Small: the usual's tile, then its name and "Your usual" with play at the foot, as the mockup has it. When the name
 * won't fit beside play, play moves up beside a bigger tile. One line each, never wrapped. The spare height is shared above, between and below, so nothing leaves a hole.
 */
@Composable
private fun ColumnScope.SmallContent(m: StartWidgetModel, f: WidgetFrame) {
    val sport = m.usual
    f.status?.let { return StatusSmallContent(m, it, f) }
    val fit = StartLayout.small(sport, listOf(StartWidgetModel.USUAL), false, f.contentWidth, f.contentHeight, f.fontScale)
    val sz = fit.sizes
    val words: @Composable () -> Unit = {
        Text(fit.words.name, style = NameStyle, maxLines = 1)
        fit.words.line?.let { Text(it, style = WidgetType.secondary, maxLines = 1) }
    }
    Spacer(GlanceModifier.defaultWeight())
    if (fit.actionTop) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (fit.tile) UsualTile(sport, false, sz, f.density)
            Spacer(GlanceModifier.defaultWeight())
            Action(m, sz, f)
        }
        Spacer(GlanceModifier.defaultWeight())
        Column(GlanceModifier.fillMaxWidth()) { words() }
    } else {
        UsualTile(sport, false, sz, f.density)
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) { words() }
            Spacer(GlanceModifier.width(StartLayout.SMALL_GAP_DP.dp))
            Action(m, sz, f)
        }
    }
    Spacer(GlanceModifier.defaultWeight())
}

/**
 * Small with the strap status (spec 5), as the other widgets' Smalls: "Start a workout" (or "Start") with the status
 * button at the top right, then the usual's tile with play right beside it, then the name over "Your usual". Half a
 * gap above and below, a gap between.
 */
@Composable
private fun ColumnScope.StatusSmallContent(m: StartWidgetModel, line: StatusLine, f: WidgetFrame) {
    val fit = StartLayout.statusSmall(m.usual, line, f.contentWidth, f.contentHeight, f.fontScale)
    val edge = fit.spread.edge(f.drawContentHeight)
    Spacer(GlanceModifier.height(edge.dp))
    TopRowView(fit.title.orEmpty(), null, fit.top, f, edge)
    Spacer(GlanceModifier.defaultWeight())
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        UsualTile(m.usual, false, fit.sizes, f.density)
        Spacer(GlanceModifier.width(StartLayout.STATUS_PLAY_GAP_DP.dp))
        Action(m, fit.sizes, f)
    }
    Spacer(GlanceModifier.defaultWeight())
    Column(GlanceModifier.fillMaxWidth()) {
        Text(fit.words.name, style = NameStyle, maxLines = 1)
        fit.words.line?.let { Text(it, style = WidgetType.secondary, maxLines = 1) }
    }
    Spacer(GlanceModifier.height(edge.dp))
}

/**
 * Wide: the usual row over the tiles, at the largest sizes that keep four sports, the spare height shared above,
 * between and below.
 */
@Composable
private fun ColumnScope.WideContent(m: StartWidgetModel, f: WidgetFrame, top: TopRow?) {
    val b = StartLayout.wide(m, f.contentWidth, f.contentHeight, f.fontScale, top?.height ?: 0f)
    if (top != null) {
        // The status title row (spec 5); the gap above it fixed at its share so the card knows where the button is.
        val gap = b.gap(f.drawContentHeight)
        Spacer(GlanceModifier.height(gap.dp))
        StatusTitle(top, f, gap)
    }
    Spacer(GlanceModifier.defaultWeight())
    UsualRow(m, b, f)
    b.tiles?.let {
        Spacer(GlanceModifier.defaultWeight())
        TileRow(it, f)
    }
    Spacer(GlanceModifier.defaultWeight())
}

/**
 * Large: the Wide, a hairline, then This week and the usual sport's last, the spare height shared between them all.
 */
@Composable
private fun ColumnScope.LargeContent(m: StartWidgetModel, f: WidgetFrame, top: TopRow?) {
    val b = StartLayout.large(m, f.contentWidth, f.contentHeight, f.fontScale, top?.height ?: 0f)
    // Half a gap above and below, at the cell's real height: the padding already frames the block.
    val edge = b.edgeGap(f.drawHeight - 2 * WidgetSizes.PADDING_DP)
    Spacer(GlanceModifier.height(edge.dp))
    if (top != null) {
        StatusTitle(top, f, edge)
        Spacer(GlanceModifier.defaultWeight())
    }
    UsualRow(m, b, f)
    Spacer(GlanceModifier.defaultWeight())
    b.tiles?.let {
        TileRow(it, f)
        Spacer(GlanceModifier.defaultWeight())
    }
    Hairline()
    Spacer(GlanceModifier.defaultWeight())
    b.figures?.let { Figures(m, it) }
    Spacer(GlanceModifier.height(edge.dp))
}

/** "Start a workout" with the strap status button and its words at the right (spec 5), [above] dp under the content's top. */
@Composable
private fun StatusTitle(top: TopRow, f: WidgetFrame, above: Float) {
    Column(GlanceModifier.fillMaxWidth().then(Inset)) { TopRowView(StartWidgetModel.TITLE, null, top, f, above) }
}

/** The usual's tile, its name over "Your usual, GPS on" with the location mark, and play; or the workout recording. */
@Composable
private fun UsualRow(m: StartWidgetModel, b: StartLayout.Block, f: WidgetFrame) {
    val sport = m.usual
    val words = b.row.words
    Row(GlanceModifier.fillMaxWidth().then(Inset), verticalAlignment = Alignment.CenterVertically) {
        UsualTile(sport, false, b.sizes, f.density)
        Spacer(GlanceModifier.width(StartLayout.ROW_GAP_DP.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(words.name, style = NameStyle, maxLines = 1)
            words.line?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line, style = WidgetType.secondary, maxLines = 1)
                    if (words.mark) {
                        Spacer(GlanceModifier.width(StartLayout.MARK_GAP_DP.dp))
                        Image(
                            ImageProvider(iconBitmap(SportIcons.Location, Color.White.copy(alpha = 0.72f), StartLayout.MARK_DP, f.density)),
                            contentDescription = null,
                            modifier = GlanceModifier.size(StartLayout.MARK_DP.dp),
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                }
            }
        }
        Spacer(GlanceModifier.width(StartLayout.ROW_GAP_DP.dp))
        Action(m, b.sizes, f)
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Recording                                                                                         */
/* ------------------------------------------------------------------------------------------------ */

/**
 * Small while recording: the sport's tile and Open in the top row, then the live timer, the sport's name and, room
 * allowing, "Since 11:58" ("Paused" while paused, which always keeps its line). The spare height shared above, between and below.
 */
@Composable
private fun ColumnScope.SmallRecording(m: StartWidgetModel, r: StartRecording, f: WidgetFrame) {
    val fit = StartLayout.smallRecording(r, f.contentWidth, f.contentHeight, f.fontScale)
    Spacer(GlanceModifier.defaultWeight())
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (fit.tile >= StartLayout.MIN_TILE_DP) UsualTile(r.sport, true, StartLayout.Sizes(fit.tile, fit.open, 0f), f.density)
        Spacer(GlanceModifier.defaultWeight())
        Action(m, fit.sizes, f)
    }
    Spacer(GlanceModifier.defaultWeight())
    Column(GlanceModifier.fillMaxWidth()) {
        Timer(r, fit.timerSp)
        Text(fit.name, style = NameStyle, maxLines = 1)
        if (fit.since == StartLayout.SincePlace.Under) Text(if (r.paused) r.status else r.since, style = WidgetType.secondary, maxLines = 1)
    }
    Spacer(GlanceModifier.defaultWeight())
}

/** Wide while recording: the sport's row with "Recording" and Open, then the timer as the hero. */
@Composable
private fun ColumnScope.WideRecording(m: StartWidgetModel, r: StartRecording, f: WidgetFrame) {
    val fit = StartLayout.wideRecording(r, f.contentWidth, f.contentHeight, f.fontScale)
    Spacer(GlanceModifier.defaultWeight())
    RecordingRow(m, r, fit, f)
    Spacer(GlanceModifier.defaultWeight())
    TimerRow(r, fit)
    Spacer(GlanceModifier.defaultWeight())
}

/** Large while recording: the Wide's, then the hairline and the week's figures, secondary to the timer. */
@Composable
private fun ColumnScope.LargeRecording(m: StartWidgetModel, r: StartRecording, f: WidgetFrame) {
    val fit = StartLayout.largeRecording(r, m, f.contentWidth, f.contentHeight, f.fontScale)
    val edge = fit.edgeGap(f.drawHeight - 2 * WidgetSizes.PADDING_DP)
    Spacer(GlanceModifier.height(edge.dp))
    RecordingRow(m, r, fit, f)
    Spacer(GlanceModifier.defaultWeight())
    TimerRow(r, fit)
    Spacer(GlanceModifier.defaultWeight())
    Hairline()
    Spacer(GlanceModifier.defaultWeight())
    fit.figures?.let { Figures(m, it) }
    Spacer(GlanceModifier.height(edge.dp))
}

/** The sport's tile, its name over the red dot and "Recording" (white and "Paused" while paused), and Open. */
@Composable
private fun RecordingRow(m: StartWidgetModel, r: StartRecording, fit: StartLayout.Recording, f: WidgetFrame) {
    Row(GlanceModifier.fillMaxWidth().then(Inset), verticalAlignment = Alignment.CenterVertically) {
        UsualTile(r.sport, true, fit.sizes, f.density)
        Spacer(GlanceModifier.width(StartLayout.ROW_GAP_DP.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(fit.name, style = NameStyle, maxLines = 1)
            if (fit.status) Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(dotBitmap(if (r.paused) Color.White.copy(alpha = 0.5f) else Ink, f.density)),
                    contentDescription = null,
                    modifier = GlanceModifier.size(StartLayout.DOT_DP.dp),
                    contentScale = ContentScale.FillBounds,
                )
                Spacer(GlanceModifier.width(StartLayout.DOT_GAP_DP.dp))
                Text(r.status, style = WidgetType.secondary, maxLines = 1)
            }
        }
        Spacer(GlanceModifier.width(StartLayout.ROW_GAP_DP.dp))
        Action(m, fit.sizes, f)
    }
}

/** The timer, with "Since 11:58" beside it (on its baseline side) or under it, where the layout found room. */
@Composable
private fun TimerRow(r: StartRecording, fit: StartLayout.Recording) {
    when (fit.since) {
        StartLayout.SincePlace.Beside -> Row(GlanceModifier.fillMaxWidth().then(Inset), verticalAlignment = Alignment.Bottom) {
            Timer(r, fit.timerSp)
            Spacer(GlanceModifier.width(StartLayout.SINCE_GAP_DP.dp))
            Text(r.since, style = WidgetType.secondary, maxLines = 1)
        }
        StartLayout.SincePlace.Under -> Column(GlanceModifier.fillMaxWidth().then(Inset)) {
            Timer(r, fit.timerSp)
            Text(r.since, style = WidgetType.secondary, maxLines = 1)
        }
        StartLayout.SincePlace.None -> Box(GlanceModifier.fillMaxWidth().then(Inset)) { Timer(r, fit.timerSp) }
    }
}

/**
 * The live timer: a RemoteViews Chronometer, which ticks on its own with no widget updates, h:mm:ss or mm:ss, at
 * [sp] (so the system font scales it). Its base puts the workout's active time at now; paused, it stands still.
 */
@Composable
private fun Timer(r: StartRecording, sp: Float) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, R.layout.widget_chronometer).apply {
        val base = StartRecording.chronometerBase(SystemClock.elapsedRealtime(), System.currentTimeMillis(), r)
        setChronometer(R.id.widget_chronometer, base, null, !r.paused)
        setTextViewTextSize(R.id.widget_chronometer, TypedValue.COMPLEX_UNIT_SP, sp)
    }
    AndroidRemoteViews(views)
}

/** The Large's hairline, padded back to 20 dp outside the line: a background would fill a padding of its own. */
@Composable
private fun Hairline() {
    Box(GlanceModifier.fillMaxWidth().then(Inset)) {
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(ColorProvider(Color.White.copy(alpha = HAIRLINE_ALPHA)))) {}
    }
}

/** Play, white with a black triangle, opening Ready with the usual; or Open while a workout records. */
@Composable
private fun Action(m: StartWidgetModel, sz: StartLayout.Sizes, f: WidgetFrame) {
    val context = LocalContext.current
    if (m.recording != null) {
        val w = StartLayout.openWidth(f.fontScale)
        Box(
            GlanceModifier.size(w.dp, sz.play.dp)
                .background(ImageProvider(pillBitmap(w, sz.play, f.density)), ContentScale.FillBounds)
                .clickable(actionStartActivity(openLiveWorkout(context))),
            contentAlignment = Alignment.Center,
        ) { Text(StartWidgetModel.OPEN, style = OpenStyle, maxLines = 1) }
        return
    }
    Image(
        ImageProvider(playBitmap(sz, f.density)),
        contentDescription = m.usual.action,
        modifier = GlanceModifier.size(sz.play.dp).clickable(actionStartActivity(openReady(context, m.usual.sportType))),
        contentScale = ContentScale.FillBounds,
    )
}

/** The usual's tile at [sz]. A tap starts it too, or opens the workout recording. */
@Composable
private fun UsualTile(sport: StartSport, recording: Boolean, sz: StartLayout.Sizes, density: Float) {
    val context = LocalContext.current
    val tap = if (recording) openLiveWorkout(context) else openReady(context, sport.sportType)
    Image(
        ImageProvider(tileBitmap(SportIcons.of(sport.shownType), sz.tile, sz.tileCorner, sz.icon, density)),
        contentDescription = null,
        modifier = GlanceModifier.size(sz.tile.dp).clickable(actionStartActivity(tap)),
        contentScale = ContentScale.FillBounds,
    )
}

/**
 * The next sports and All sports: the tiles spread edge to edge with equal room between (the first under the usual's
 * tile, the last under play), then their captions on one line, each in a box as wide as its neighbours leave
 * ([StartLayout.captionBoxes]), each centred under its tile, the first and last running into the side padding when
 * they're wider than their tile. Each row is at most ten children (Glance drops a row's children past ten). A tile
 * and its caption open the same.
 */
@Composable
private fun TileRow(tiles: StartLayout.Tiles, f: WidgetFrame) {
    val context = LocalContext.current
    val opens = tiles.sports.map { openReady(context, it.sportType) } + openSportPicker(context)
    val actions = tiles.sports.map { it.action } + StartWidgetModel.ALL_SPORTS_ACTION
    val icons = tiles.sports.map { SportIcons.of(it.shownType) } + AppIcons.MoreHorizontalCircle01
    val captions = tiles.captions
    val n = captions.size
    val icon = (tiles.tile * StartLayout.ICON_DP / StartLayout.SMALL_TILE_DP).coerceIn(16f, 24f)
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth().then(Inset)) {
            for (i in 0 until n) {
                if (i > 0) Spacer(GlanceModifier.defaultWeight())
                Image(
                    ImageProvider(tileBitmap(icons[i], tiles.tile, tiles.corner, icon, f.density)),
                    contentDescription = actions[i],
                    modifier = GlanceModifier.size(tiles.tile.dp).clickable(actionStartActivity(opens[i])),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        if (!tiles.captioned) return@Column
        Spacer(GlanceModifier.height(StartLayout.CAPTION_GAP_DP.dp))
        // Placed at the real width, so the captions sit under tiles spread across it.
        val boxes = StartLayout.captionBoxes(captions, tiles.tile, tiles.captionSp, f.drawContentWidth, f.fontScale)
        val style = WidgetType.caption.copy(fontSize = tiles.captionSp.sp)
        // This row alone spans into the padding: x runs from the bleed's edge. At most ten children: a spacer before
        // each of the five captions.
        Row(GlanceModifier.fillMaxWidth()) {
            var x = -StartLayout.CAPTION_BLEED_DP
            boxes.forEachIndexed { i, (left, w) ->
                val gap = left - x
                if (gap > 0.5f) Spacer(GlanceModifier.width(gap.dp))
                Text(
                    captions[i], style = style.copy(textAlign = TextAlign.Center), maxLines = 1,
                    modifier = GlanceModifier.width(w.dp).clickable(actionStartActivity(opens[i])),
                )
                x = left + w
            }
        }
    }
}

/** This week and the usual's last: side by side, Caption over Body, or a row each when a value won't fit half. */
@Composable
private fun Figures(m: StartWidgetModel, fit: StartLayout.Figures) {
    when (fit.layout) {
        StartLayout.FigureLayout.Side -> Row(GlanceModifier.fillMaxWidth().then(Inset)) {
            Figure(StartWidgetModel.THIS_WEEK, m.weekLine, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(StartLayout.FIGURE_GAP_DP.dp))
            Figure(m.lastLabel, m.lastLine, GlanceModifier.defaultWeight())
        }
        StartLayout.FigureLayout.Rows -> Column(GlanceModifier.fillMaxWidth().then(Inset)) {
            FigureRow(StartWidgetModel.THIS_WEEK, m.weekLine)
            Spacer(GlanceModifier.height(StartLayout.FIGURE_ROW_GAP_DP.dp))
            FigureRow(m.lastLabel, m.lastLine)
        }
        StartLayout.FigureLayout.Stacked -> Column(GlanceModifier.fillMaxWidth().then(Inset)) {
            Figure(StartWidgetModel.THIS_WEEK, m.weekLine, GlanceModifier.fillMaxWidth())
            Spacer(GlanceModifier.height(StartLayout.FIGURE_ROW_GAP_DP.dp))
            Figure(m.lastLabel, m.lastLine, GlanceModifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: GlanceModifier) {
    Column(modifier) {
        Text(label, style = WidgetType.caption, maxLines = 1)
        Spacer(GlanceModifier.height(StartLayout.FIGURE_LINE_GAP_DP.dp))
        Text(value, style = WidgetType.body, maxLines = 1)
    }
}

/** A figure as a row: its Caption on the left, the value on the right. */
@Composable
private fun FigureRow(label: String, value: String) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = WidgetType.caption, maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Text(value, style = WidgetType.body, maxLines = 1)
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Bitmaps                                                                                           */
/* ------------------------------------------------------------------------------------------------ */

/** Room for the tiles, play and the mark beside the backgrounds, in the bitmap budget. */
private const val BITMAP_EXTRA = 1.3

/** A sport's tile [sizeDp] square: the Control surface with [cornerDp] corners, the icon white at [iconDp] in the middle. */
internal fun tileBitmap(icon: ImageVector, sizeDp: Float, cornerDp: Float, iconDp: Float, density: Float): Bitmap {
    val s = sizeDp * density
    val r = cornerDp * density
    val i = iconDp * density
    return renderToBitmap(s, s) { c ->
        c.drawRoundRect(0f, 0f, s, s, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ControlSurface.toArgb() })
        drawIcon(c, icon, Color.White, (s - i) / 2, (s - i) / 2, i)
    }
}

/** The play button at [sz]: a white circle with the black play triangle, as Ready's Start. */
private fun playBitmap(sz: StartLayout.Sizes, density: Float): Bitmap = playBitmap(sz.play, sz.playIcon, density)

/** The play button [sizeDp] across with its triangle [iconDp]: a white circle, the black play icon in the middle. */
internal fun playBitmap(sizeDp: Float, iconDp: Float, density: Float): Bitmap {
    val s = sizeDp * density
    val i = iconDp * density
    return renderToBitmap(s, s) { c ->
        c.drawCircle(s / 2, s / 2, s / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb() })
        drawIcon(c, AppIcons.PlayFill, Color.Black, (s - i) / 2, (s - i) / 2, i)
    }
}

/** Open's white pill, [w] by [h] dp, fully round. */
internal fun pillBitmap(w: Float, h: Float, density: Float): Bitmap {
    val pw = w * density
    val ph = h * density
    return renderToBitmap(pw, ph) { c ->
        c.drawRoundRect(0f, 0f, pw, ph, ph / 2, ph / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb() })
    }
}

/** The recording dot, [color], [StartLayout.DOT_DP] across. */
internal fun dotBitmap(color: Color, density: Float): Bitmap {
    val s = StartLayout.DOT_DP * density
    return renderToBitmap(s, s) { c -> c.drawCircle(s / 2, s / 2, s / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }) }
}
