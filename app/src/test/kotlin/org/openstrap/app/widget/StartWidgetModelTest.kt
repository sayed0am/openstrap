package org.openstrap.app.widget

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.Workout

/** Widgets spec 3.5: what the Start widget offers from your workouts, this week's line, recording, and how it fits. */
class StartWidgetModelTest {
    private val zone: ZoneId = ZoneOffset.UTC
    /** Saturday 24 October 2026, midday. */
    private val now: Instant = at(24, 12, 0)

    private fun at(day: Int, hour: Int, minute: Int): Instant = LocalDateTime.of(2026, 10, day, hour, minute).toInstant(ZoneOffset.UTC)

    private fun workout(day: Int, hour: Int, type: Int, minutes: Int, meters: Float? = null, sets: Int? = null): WorkoutAnalysis {
        val start = at(day, hour, 0).epochSecond
        val w = Workout(start, start + minutes * 60L, type, minutes * 60, null, null, null, null, null, null, null, null, null)
        return Workouts.analyse(w, emptyList(), emptyList(), 190).copy(distanceMeters = meters, sets = sets)
    }

    /**
     * Runs three times (Tuesday's 5.2 km this week), walks and rides twice, a treadmill run on Friday, strength once,
     * and the strap's own "Activity" this week, which isn't a workout.
     */
    private val history = listOf(
        workout(5, 7, 1, 45),
        workout(1, 7, 1, 45),
        workout(20, 18, 1, 60, meters = 5_200f),
        workout(16, 8, 3, 30),
        workout(12, 8, 3, 30),
        workout(15, 17, 4, 50),
        workout(9, 17, 4, 50),
        workout(23, 19, 2, 35),
        workout(18, 10, 52, 40, sets = 12),
        workout(22, 13, Workouts.GENERIC_ACTIVITY, 20),
    )

    private fun model(
        workouts: List<WorkoutAnalysis> = history, sides: Map<Int, Boolean> = emptyMap(), location: Boolean = true,
        recording: StartRecordingInput? = null,
    ) = StartWidgetModel.from(workouts, sides, location, recording, now, zone, Locale.UK)

    @Test fun `the usual is the sport done most, the next four by use`() {
        val m = model()
        assertEquals(1, m.usual.sportType)
        assertEquals("Outdoor run", m.usual.name)
        assertEquals("Start run", m.usual.action)
        // Walk and cycling twice, the walk more recently; treadmill and strength once, the treadmill more recently.
        assertEquals(listOf("Walk", "Cycling", "Treadmill", "Strength"), m.next.take(4).map { it.caption })
        assertEquals(listOf(3, 4, 2, 52), m.next.take(4).map { it.sportType })
        // Then the everyday sports, each once.
        assertEquals("Hike", m.next[4].caption)
        assertEquals(m.next.size, m.next.distinctBy { it.shownType }.size)
        assertTrue(m.next.none { it.shownType == Workouts.GENERIC_ACTIVITY })
        assertNull(m.recording)
    }

    @Test fun `before any workout, the default sports, then the everyday ones`() {
        val m = model(workouts = emptyList())
        assertEquals(Workouts.DEFAULT_SPORTS.first(), m.usual.sportType)
        assertEquals(listOf("Walk", "Strength", "Hike", "Cycling"), m.next.take(4).map { it.caption })
        assertEquals("No workouts yet", m.weekLine)
        assertEquals("Last run", m.lastLabel)
        assertEquals("None yet", m.lastLine)
    }

    @Test fun `GPS on or off as Ready would start the usual`() {
        val on = model()
        assertEquals("Your usual, GPS on", on.usualLine)
        assertTrue(on.gpsOn)
        val off = model(location = false)
        assertEquals("Your usual, GPS off", off.usualLine)
        assertFalse(off.gpsOn)
        // Strength has no GPS to speak of.
        val indoor = model(workouts = listOf(workout(20, 8, 52, 40), workout(21, 8, 52, 40)))
        assertEquals("Strength training", indoor.usual.name)
        assertEquals("Your usual", indoor.usualLine)
        assertFalse(indoor.gpsOn)
    }

    @Test fun `a sport with two sides shows the side Ready opens on, once`() {
        // Your runs, last done on the treadmill side: Ready opens on Treadmill, so that's what the widget says.
        val m = model(sides = mapOf(1 to true))
        assertEquals(1, m.usual.sportType)
        assertEquals(2, m.usual.shownType)
        assertEquals("Treadmill", m.usual.name)
        assertEquals("Your usual", m.usualLine)
        assertFalse(m.gpsOn)
        assertTrue(m.next.none { it.shownType == 2 })
    }

    @Test fun `this week counts workouts since Monday, not the strap's Activity`() {
        val m = model()
        // Tuesday's hour and Friday's 35 minutes.
        assertEquals("2 workouts, 1 h 35", m.weekLine)
        assertEquals("1 workout, 40 min", StartWidgetModel.weekLine(1, 40 * 60L))
        assertEquals("3 workouts, 2 h", StartWidgetModel.weekLine(3, 7_200L))
        assertEquals("No workouts yet", StartWidgetModel.weekLine(0, 0L))
    }

    @Test fun `the usual sport's last, by its tile, with its distance, sets or length`() {
        // The treadmill on Friday is a run too.
        assertEquals("Last run", model().lastLabel)
        assertEquals("Yesterday, 35 min", model().lastLine)
        val outdoors = model(workouts = history.filter { it.workout.sportType != 2 })
        assertEquals("Tuesday, 5.2 km", outdoors.lastLine)
        val strength = model(workouts = listOf(workout(18, 10, 52, 40, sets = 12), workout(10, 10, 52, 40)))
        assertEquals("Last strength", strength.lastLabel)
        assertEquals("Sunday, 12 sets", strength.lastLine)
        assertEquals("Last HIIT", StartWidgetModel.lastLabel(49))
        assertEquals("Last workout", StartWidgetModel.lastLabel(5))
        assertEquals("Last walk", StartWidgetModel.lastLabel(40))
        assertEquals("Today, 35 min", "${recentDay(now.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())}, 35 min")
        assertEquals("10 Oct", recentDay(java.time.LocalDate.of(2026, 10, 10), java.time.LocalDate.of(2026, 10, 24), Locale.UK))
    }

    @Test fun `while recording, the sport, since when and the active time`() {
        val m = model(recording = StartRecordingInput(4, at(24, 11, 58).epochSecond, 1_500L, paused = false))
        val rec = assertNotNullAnd(m.recording)
        assertEquals("Outdoor cycling", rec.sport.name)
        assertEquals("Since 11:58", rec.since)
        assertEquals("Recording", rec.status)
        assertEquals(4, rec.sport.shownType)
        assertEquals(1_500_000L, rec.activeMillis)
        assertEquals(now.toEpochMilli(), rec.atMillis)
        assertEquals("Paused", model(recording = StartRecordingInput(4, 0L, 60L, paused = true)).recording!!.status)
    }

    @Test fun `the timer's base puts the active time at now, and stands still paused`() {
        val r = model(recording = StartRecordingInput(4, at(24, 11, 58).epochSecond, 1_500L, paused = false)).recording!!
        val elapsed = 9_000_000L
        // Loaded now: 25 minutes back from the elapsed clock.
        assertEquals(elapsed - 1_500_000L, StartRecording.chronometerBase(elapsed, r.atMillis, r))
        // Drawn 10 s after loading: 10 s more have passed.
        assertEquals(elapsed - 1_510_000L, StartRecording.chronometerBase(elapsed, r.atMillis + 10_000L, r))
        // Paused, the time at the pause whenever it's drawn.
        val p = r.copy(paused = true)
        assertEquals(elapsed - 1_500_000L, StartRecording.chronometerBase(elapsed, r.atMillis + 60_000L, p))
    }

    @Test fun `the recording layouts fit every cell and font, one line each, the timer whole`() {
        val r = model(recording = StartRecordingInput(60, at(24, 11, 58).epochSecond, 1_500L, paused = false)).recording!!
        val walk = r.copy(sport = StartSport.recorded(40))
        val m = model().copy(weekLine = "5 workouts, 10 min", lastLine = "Yesterday, 1 min")
        for (s in scales) for (rec in listOf(r, walk)) {
            for ((w, h) in smallCells) {
                val fit = StartLayout.smallRecording(rec, w, h, s)
                val where = "Small $w x $h at $s"
                assertTrue("$where: timer too wide", StartLayout.timerWidth(fit.timerSp, s) <= w)
                assertTrue("$where: name wraps", one(fit.name, WidgetText.BODY_SP, w, s) || fit.name == rec.sport.shortName)
                assertTrue("$where: too tall ${fit.content}", fit.content <= h)
                if (fit.tile >= StartLayout.MIN_TILE_DP) assertTrue("$where: top row", fit.tile + StartLayout.SMALL_GAP_DP + StartLayout.openWidth(s) <= w)
            }
            for ((w, h) in wideCells) {
                val fit = StartLayout.wideRecording(rec, w, h, s)
                val where = "Wide $w x $h at $s"
                assertTrue("$where: timer too wide", StartLayout.timerWidth(fit.timerSp, s) <= w)
                if (fit.since == StartLayout.SincePlace.Beside) {
                    assertTrue(where, StartLayout.timerWidth(fit.timerSp, s) + StartLayout.SINCE_GAP_DP + WidgetText.width(rec.since, WidgetText.SECONDARY_SP, s) <= w)
                }
                assertTrue("$where: too tall ${fit.content}", fit.content + StartLayout.MIN_GAP_DP <= h)
            }
            for ((w, h) in largeCells) {
                val fit = StartLayout.largeRecording(rec, m, w, h, s)
                assertTrue("Large $w x $h at $s: too tall ${fit.content}", fit.content + 3 * StartLayout.MIN_GAP_DP <= h)
            }
        }
        // At the minimum Small the tile stays beside Open at the fonts phones use.
        for (s in listOf(1f, 1.08f)) assertTrue(StartLayout.smallRecording(walk, 110f, 110f, s).tile >= StartLayout.MIN_TILE_DP)
        // The timer is the hero: as large as the spec's sizes on One UI's 4 x 2 and 4 x 3.
        assertEquals(StartLayout.TIMER_WIDE_SP, StartLayout.wideRecording(r, 313f, 180f, 1f).timerSp, 0f)
        assertTrue(StartLayout.largeRecording(r, m, 313f, 290f, 1.08f).timerSp >= 48f)
    }

    @Test fun `the Small says Paused at every cell and the fonts phones use`() {
        val r = model(recording = StartRecordingInput(40, at(24, 11, 58).epochSecond, 1_500L, paused = true)).recording!!
        for (s in listOf(1f, 1.08f)) for ((w, h) in smallCells + listOf(110f to 110f)) {
            val fit = StartLayout.smallRecording(r, w, h, s)
            assertTrue("Small $w x $h at $s", fit.since == StartLayout.SincePlace.Under || fit.name == "Paused")
            assertTrue("Small $w x $h at $s: too tall ${fit.content}", fit.content <= h)
        }
    }

    @Test fun `recording on One UI's cells leaves no gap over 32 dp`() {
        val r = model(recording = StartRecordingInput(40, at(24, 11, 58).epochSecond, 1_500L, paused = false)).recording!!
        val m = model().copy(weekLine = "5 workouts, 10 min", lastLine = "Yesterday, 1 min")
        for (s in listOf(1f, 1.08f, 1.3f)) {
            val small = StartLayout.smallRecording(r, 140f, 180f, s)
            assertTrue("Small at $s: ${(180f - small.content) / small.gaps}", (180f - small.content) / small.gaps <= 32f)
            val wide = StartLayout.wideRecording(r, 313f, 180f, s)
            assertTrue("Wide at $s: ${(180f - wide.content) / wide.gaps}", (180f - wide.content) / wide.gaps <= 32f)
            val large = StartLayout.largeRecording(r, m, 313f, 290f, s)
            val edge = large.edgeGap(290f)
            val inner = (290f - large.content - 2 * edge) / (large.gaps - 2)
            assertTrue("Large at $s: $inner", inner <= 32f)
        }
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    @Test fun `captions are short names, the full one where the short one is already said`() {
        // Indoor walk alone reads "Walk", a treadmill beside your outdoor runs "Treadmill".
        val m = model(workouts = listOf(workout(20, 8, 1, 30), workout(21, 8, 1, 30), workout(22, 8, 40, 30), workout(23, 8, 2, 30)))
        assertEquals(listOf("Treadmill", "Walk"), m.next.take(2).map { it.caption })
        assertEquals("Indoor walk", m.next[1].name)
    }

    /* ------------------------------------------------------------------------------------------ */
    /* Fitting: nothing wraps, nothing cut, no holes                                              */
    /* ------------------------------------------------------------------------------------------ */

    /** Content sizes: the buckets' minimums and One UI's cells (spec 2.1), width to height. */
    private val smallCells = listOf(110f to 110f, 127f to 161f, 140f to 180f)
    private val wideCells = listOf(260f to 120f, 313f to 161f, 313f to 180f)
    private val largeCells = listOf(260f to 220f, 313f to 290f, 313f to 335f)
    private val scales = listOf(1f, 1.08f, 1.3f)

    private fun one(text: String, sp: Float, room: Float, s: Float) = WidgetText.width(text, sp, s) <= room

    @Test fun `the Small's words are one line each, in every cell and font`() {
        val sports = listOf(1, 52, 7, 23, 222, 2).map { StartSport.of(it, emptyMap()) }
        for ((w, h) in smallCells) for (s in scales) for (sp in sports) for (rec in listOf(false)) {
            val lines = listOf(StartWidgetModel.USUAL)
            val fit = StartLayout.small(sp, lines, rec, w, h, s)
            val action = fit.sizes.action(rec, s)
            val room = if (fit.actionTop) w else w - action - StartLayout.SMALL_GAP_DP
            val where = "${sp.name} at $w x $h, font $s, recording $rec"
            assertTrue("$where: name wraps", one(fit.words.name, WidgetText.BODY_SP, room, s) || fit.words.name == sp.shortName)
            fit.words.line?.let { assertTrue("$where: line wraps", one(it, WidgetText.SECONDARY_SP, room, s)) }
            if (fit.actionTop && fit.tile) assertTrue("$where: top row too wide", fit.sizes.tile + StartLayout.SMALL_GAP_DP + action <= w)
        }
        // On One UI's 2 x 2 the usual keeps its full name and "Your usual".
        val fit = StartLayout.small(StartSport.of(1, emptyMap()), listOf(StartWidgetModel.USUAL), false, 127f, 161f, 1.08f)
        assertEquals("Outdoor run", fit.words.name)
        assertEquals("Your usual", fit.words.line)
    }

    @Test fun `the usual row is one line each, the mark and GPS giving way first`() {
        val m = model()
        for ((w, h) in wideCells + largeCells) for (s in scales) {
            val b = StartLayout.wide(m, w, h, s)
            val words = b.row.words
            val room = w - b.sizes.tile - 2 * StartLayout.ROW_GAP_DP - b.sizes.play - if (words.mark) StartLayout.MARK_DP + StartLayout.MARK_GAP_DP else 0f
            words.line?.let { assertTrue("$w at $s: line wraps", one(it, WidgetText.SECONDARY_SP, room, s)) }
        }
        // At One UI's 4 columns the whole line and its mark fit; at the minimum the mark gives way first.
        assertEquals(StartLayout.Words("Outdoor run", "Your usual, GPS on", true), StartLayout.wide(m, 313f, 161f, 1.08f).row.words)
        assertEquals("Your usual, GPS on", StartLayout.wide(m, 260f, 120f, 1.08f).row.words.line)
    }

    /** Every caption shown fits its box on one line: Glance shows it whole, no "W...". */
    private fun assertCaptionsWhole(t: StartLayout.Tiles, w: Float, s: Float, where: String) {
        if (!t.captioned) return
        assertTrue("$where: captions run together or out", StartLayout.captionsFit(t.captions, t.tile, t.captionSp, w, s))
        val boxes = StartLayout.captionBoxes(t.captions, t.tile, t.captionSp, w, s)
        t.captions.zip(boxes).forEach { (c, box) ->
            // With the safety margin: the rough sum underrates short words ("Swi..." on the phone).
            val need = StartLayout.captionWidth(c, t.captionSp, s)
            assertTrue("$where: $c needs $need dp in a ${box.second} dp box", need <= box.second + 0.01f)
        }
        // Each box centred on its own tile, the first and last too, in order, within half the side padding.
        val n = t.captions.size
        boxes.forEachIndexed { i, (left, bw) -> assertEquals("$where: box $i off centre", StartLayout.centre(i, n, t.tile, w), left + bw / 2, 0.01f) }
        boxes.zipWithNext().forEach { (a, b) -> assertTrue("$where: boxes overlap", a.first + a.second <= b.first + 0.01f) }
        val bleed = StartLayout.CAPTION_BLEED_DP
        assertTrue("$where: past the bleed", boxes.first().first >= -bleed - 0.01f && boxes.last().first + boxes.last().second <= w + bleed + 0.01f)
    }

    @Test fun `short captions get their column's room, not their rough width`() {
        val captions = listOf("Walk", "Strength", "Hike", "Swim", "All sports")
        // One UI's 4 x 2, where the phone showed "W..." and "S...".
        run {
            val w = 313f
            val boxes = StartLayout.captionBoxes(captions, 38f, 10f, w, 1.08f)
            // "Walk" and "Swim" get far more than their 25 dp, so a sum that underrates them can't cut them.
            assertTrue(boxes[0].second >= 1.2f * StartLayout.captionWidth("Walk", 10f, 1.08f))
            assertTrue(boxes[3].second >= 1.2f * StartLayout.captionWidth("Swim", 10f, 1.08f))
        }
    }

    @Test fun `the Wide keeps the tile row, four sports on One UI, at least three at the minimum`() {
        val m = model()
        for (s in listOf(1f, 1.08f)) {
            assertEquals("One UI 4 x 2 at $s", 4, StartLayout.wide(m, 313f, 180f, s).tiles?.sports?.size)
            val b = StartLayout.wide(m, 260f, 120f, s)
            val tiles = b.tiles
            assertNotNull("Wide 300 x 160 at $s lost its tiles", tiles)
            // With the caption margin a 300 wide cell can't always give four captions room: a tight one gives way.
            assertTrue(tiles!!.sports.size >= 3)
            assertCaptionsWhole(tiles, 260f, s, "Wide at $s")
            assertTrue(b.content + (b.gaps - 2) * StartLayout.MIN_GAP_DP <= 120f)
        }
    }

    @Test fun `the Large holds four sports on One UI and at least three at 300 wide, captions on one line`() {
        val m = model(workouts = history + listOf(workout(24, 8, 40, 30), workout(17, 8, 40, 30), workout(10, 8, 40, 30)))
        for (s in listOf(1f, 1.08f)) {
            assertEquals("One UI 4 x 3 at $s", 4, StartLayout.large(m, 313f, 290f, s).tiles?.sports?.size)
            val b = StartLayout.large(m, 260f, 220f, s)
            assertNotNull("Large 300 x 260 at $s lost its tiles", b.tiles)
            assertTrue(b.tiles!!.sports.size >= 3)
            assertCaptionsWhole(b.tiles, 260f, s, "Large at $s")
            assertTrue(b.content + (b.gaps - 2) * StartLayout.MIN_GAP_DP <= 220f)
        }
    }

    @Test fun `tiles never cut or run captions together, at any cell or font`() {
        val long = listOf(14, 6, 8, 17, 18, 21).map { StartSport.of(it, emptyMap()) } + model().next
        val m = model().copy(next = long)
        for ((w, h) in wideCells) for (s in scales) {
            val b = StartLayout.wide(m, w, h, s)
            val t = b.tiles ?: continue
            assertCaptionsWhole(t, w, s, "Wide $w x $h at $s")
            assertTrue("Wide $w x $h at $s too tall", b.content + (b.gaps - 2) * StartLayout.MIN_GAP_DP <= h)
        }
        for ((w, h) in largeCells) for (s in scales) {
            val b = StartLayout.large(m, w, h, s)
            val t = b.tiles ?: continue
            assertCaptionsWhole(t, w, s, "Large $w x $h at $s")
            assertTrue("Large $w x $h at $s too tall", b.content + (b.gaps - 2) * StartLayout.MIN_GAP_DP <= h)
        }
        // At the largest font a short Wide still has its row, its captions the last resort to go.
        assertNotNull(StartLayout.wide(model(), 260f, 120f, 1.3f).tiles)
        assertTrue(StartLayout.wide(model(), 313f, 180f, 1.08f).tiles!!.captioned)
    }

    @Test fun `on One UI's cells the spare height is used, no gap bigger than 32 dp`() {
        val m = model().copy(weekLine = "5 workouts, 10 min", lastLine = "Yesterday, 1 min")
        for (s in listOf(1f, 1.08f)) {
            // 4 x 2 at 353 x 220 and 4 x 3 at 353 x 330, less the padding.
            val wide = StartLayout.wide(m, 313f, 180f, s)
            val large = StartLayout.large(m, 313f, 290f, s)
            assertEquals("Wide grows to the mockup's sizes", 48f, wide.sizes.tile, 0f)
            assertTrue("Wide at $s: gap ${(180f - wide.content) / wide.gaps}", (180f - wide.content) / wide.gaps <= 32f)
            assertTrue("Large at $s: gap ${(290f - large.content) / large.gaps}", (290f - large.content) / large.gaps <= 32f)
            // 2 x 2 at 180 x 220.
            val small = StartLayout.small(m.usual, listOf(StartWidgetModel.USUAL), false, 140f, 180f, s)
            val gap = (180f - StartLayout.smallHeight(small, s)) / small.gaps
            assertTrue("Small at $s: gap $gap", gap <= 32f)
        }
    }

    @Test fun `the Large's spare height goes between the blocks, half a gap above and below`() {
        val m = model().copy(weekLine = "5 workouts, 10 min", lastLine = "Yesterday, 1 min")
        for (s in listOf(1f, 1.08f)) {
            val b = StartLayout.large(m, 313f, 290f, s)
            val edge = b.edgeGap(290f)
            val inner = (290f - b.content - 2 * edge) / (b.gaps - 2)
            assertEquals("Large at $s", inner / 2, edge, 0.01f)
            // Under the figures: the padding and half a gap, no band.
            assertTrue("Large at $s: ${edge + WidgetSizes.PADDING_DP} dp under Last run", edge + WidgetSizes.PADDING_DP <= 40f)
        }
    }

    @Test fun `Swim's caption needs more than its rough width, so a tight one gives way`() {
        // By letter: the W and m make Swim wider than four average letters, then the margin on top.
        assertTrue(StartLayout.letterEms("Swim") > StartLayout.letterEms("Hike"))
        assertEquals(StartLayout.letterEms("Swim") * 11f * 1.08f * 1.15f, StartLayout.captionWidth("Swim", 11f, 1.08f), 1e-3f)
        assertTrue(StartLayout.captionWidth("Swim", 11f, 1.08f) > 1.15f * WidgetText.width("Swim", 11f, 1.08f))
    }

    @Test fun `the tile row is the same sports in the same order at every size`() {
        // The phone's history: walks, strength, hikes, rides, then swims.
        val sports = listOf(3, 52, 15, 4, 7, 49, 60).map { StartSport.of(it, emptyMap()) }
        val m = model().copy(next = sports)
        val rows = mutableMapOf<String, List<String>>()
        for (s in listOf(1f, 1.08f)) {
            rows["Wide min $s"] = StartLayout.wide(m, 260f, 120f, s).tiles!!.captions
            rows["Wide One UI $s"] = StartLayout.wide(m, 313f, 180f, s).tiles!!.captions
            rows["Large min $s"] = StartLayout.large(m, 260f, 220f, s).tiles!!.captions
            rows["Large One UI $s"] = StartLayout.large(m, 313f, 290f, s).tiles!!.captions
        }
        val order = listOf("Walk", "Strength", "Hike", "Cycling", "All sports")
        rows.forEach { (where, captions) -> assertEquals(where, order, captions) }
        // Every size's row is the order's start, whatever its room.
        for ((w, h) in wideCells) for (s in scales) {
            val t = StartLayout.wide(m, w, h, s).tiles ?: continue
            assertEquals("Wide $w x $h at $s", sports.take(t.sports.size), t.sports)
        }
    }

    @Test fun `the Wide at its minimum keeps its captions at font 1 and 1_08`() {
        for (s in listOf(1f, 1.08f)) assertTrue("Wide 300 x 160 at $s", StartLayout.wide(model(), 260f, 120f, s).tiles!!.captioned)
    }

    @Test fun `the figures never wrap a value`() {
        val m = model().copy(weekLine = "5 workouts, 10 min", lastLine = "Yesterday, 1 min")
        for ((w, _) in largeCells) for (s in scales) {
            val f = StartLayout.figures(m, w, s)
            val room = when (f.layout) {
                StartLayout.FigureLayout.Side -> (w - StartLayout.FIGURE_GAP_DP) / 2
                StartLayout.FigureLayout.Rows -> w - StartLayout.FIGURE_GAP_DP - WidgetText.width(m.lastLabel, WidgetText.CAPTION_SP, s)
                StartLayout.FigureLayout.Stacked -> w
            }
            assertTrue("$w at $s: ${f.layout}", one(m.lastLine, WidgetText.BODY_SP, room, s))
        }
        // The phone's case: at 300 wide and 1.08 the two don't fit half each, so they take a row each.
        assertEquals(StartLayout.FigureLayout.Rows, StartLayout.figures(m, 260f, 1.08f).layout)
        assertEquals(StartLayout.FigureLayout.Side, StartLayout.figures(model(), 313f, 1f).layout)
    }

    @Test fun `tiles spread edge to edge, evenly`() {
        assertEquals(19f, StartLayout.centre(0, 5, 38f, 260f), 1e-3f)
        assertEquals(241f, StartLayout.centre(4, 5, 38f, 260f), 1e-3f)
        assertEquals(StartLayout.centre(2, 5, 38f, 260f) - StartLayout.centre(1, 5, 38f, 260f), StartLayout.centre(1, 5, 38f, 260f) - StartLayout.centre(0, 5, 38f, 260f), 1e-3f)
        val boxes = StartLayout.captionBoxes(listOf("Walk", "Run", "Yoga", "Swim", "All sports"), 38f, 11f, 260f, 1f)
        // Every caption centred under its tile, Walk and All sports too.
        boxes.forEachIndexed { i, (l, w) -> assertEquals(StartLayout.centre(i, 5, 38f, 260f), l + w / 2, 1e-3f) }
        // All sports, wider than its tile, runs into the side padding by at most half of it.
        assertTrue(boxes.last().first + boxes.last().second > 260f)
        assertTrue(boxes.last().first + boxes.last().second <= 260f + StartLayout.CAPTION_BLEED_DP + 1e-3f)
    }

    @Test fun `with the strap status the Wide and the Small get a title row with the button`() {
        val m = WidgetSamples.start(now, zone, Locale.UK)
        val l = StatusLine(StrapStatus.StaleSync(0), listOf("Synced Wednesday", "Synced Wed", "Wednesday", "Wed"))
        val top = StartLayout.statusTop(m, l, WidgetSizes.Wide, 313f, 180f, 1f)!!
        assertEquals(StatusButton.SIZE_DP, top.height, 0f)
        assertNotNull(top.words)
        val b = StartLayout.wide(m, 313f, 180f, 1f, top.height)
        assertEquals(StartLayout.wide(m, 313f, 180f, 1f).gaps + 1, b.gaps)
        assertNotNull(b.tiles)
        assertNull(StartLayout.statusTop(m, null, WidgetSizes.Wide, 313f, 180f, 1f))
        // The Small: a title row with the button, the tile with play beside it, the name over "Your usual".
        val small = StartLayout.statusSmall(m.usual, l, 140f, 180f, 1f)
        // "Start a workout" won't fit beside the button on a 2 x 2 even at 13 sp, so "Start".
        assertEquals(StartLayout.SHORT_TITLE, small.title)
        assertEquals(StartWidgetModel.TITLE, StartLayout.statusSmall(m.usual, l, 200f, 180f, 1f).title)
        assertEquals(l, small.top.status)
        assertNull(small.top.words)
        assertEquals(StartWidgetModel.USUAL, small.words.line)
        assertTrue(small.sizes.tile + StartLayout.STATUS_PLAY_GAP_DP + small.sizes.play <= 140f)
        assertTrue(small.spread.gap(180f) <= Spread.TARGET_GAP_DP)
        // Short of room "Your usual" goes, then the title; the tile and play stay.
        val tight = StartLayout.statusSmall(m.usual, l, 110f, 110f, 1.3f)
        assertNull(tight.words.line)
        assertTrue(tight.spread.fits(110f, StartLayout.STATUS_MIN_GAP_DP))
    }
}
