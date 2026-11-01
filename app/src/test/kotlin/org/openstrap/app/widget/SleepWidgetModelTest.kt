package org.openstrap.app.widget

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.glow.draw.SleepStageOrder
import org.openstrap.app.ui.glow.draw.stageBarSegments

/** Widgets spec 3.2: what the Sleep widget shows in each state, from the Sleep tab's own sums and copy, and how it fits. */
class SleepWidgetModelTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 24)

    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(zone).toEpochSecond()

    /** The mockup's night: 22:58 to 06:56, Deep 1 h 32, REM 1 h 41, Light 4 h 27, Awake 18 min, woke 3 times, 82. */
    private val start = at(today.minusDays(1), 22, 58)
    private val end = at(today, 6, 56)
    private val session = SleepSession(start, end, score = 82, wakeCount = 3, deepMinutes = 92, remMinutes = 101, lightMinutes = 267, awakeMinutes = 18)

    /** The night's stages in chunks that add up to the mockup's totals: Light, Deep, Light, REM, Awake, Light, Deep, REM, Awake. */
    private fun stages(s: SleepSession = session): List<SleepStage> {
        val plan = listOf(
            SleepStage.LIGHT to 60, SleepStage.DEEP to 60, SleepStage.LIGHT to 90, SleepStage.REM to 50, SleepStage.AWAKE to 10,
            SleepStage.LIGHT to 117, SleepStage.DEEP to 32, SleepStage.REM to 51, SleepStage.AWAKE to 8,
        )
        var t = s.start
        return plan.map { (stage, min) -> SleepStage(t, t + min * 60L, s.start, stage).also { t += min * 60L } }
    }

    /** Earlier nights this week, shorter than this one: 6 h 10, 7 h, 7 h 20. */
    private val week: Map<LocalDate, SleepSession> = listOf(1 to 370, 2 to 420, 3 to 440).associate { (ago, min) ->
        val d = today.minusDays(ago.toLong())
        val e = at(d, 7, 0)
        d to SleepSession(e - min * 60L, e)
    }

    private fun night(s: SleepSession = session, st: List<SleepStage> = stages(s), date: LocalDate = today) =
        SleepWidgetNight.of(date, s, st, week + (date to s))

    private fun model() = SleepWidgetModel.from(night(), null, firstNight = false, napsToday = false, zone = zone)

    @Test fun `last night as the Sleep tab's hero tells it`() {
        val m = model()
        assertEquals("Last night", m.title)
        assertEquals("22:58–06:56", m.note)
        assertFalse(m.stale)
        assertEquals(today, m.date)
        assertEquals("82", m.score)
        assertEquals("Good", m.word)
        assertEquals("Sleep score, good", m.scoreLine)
        assertEquals("7 h 40 asleep", m.asleep)
        assertEquals("Woke 3 times", m.woke)
        assertEquals("Woke 3 times", m.largeRight)
        assertEquals("7 h 40, your longest this week.", m.sentence)
        assertEquals("Sleep score 82, good", m.scoreDescription)
        assertNull(m.waiting)
        // The hero's own sums and copy.
        assertEquals(TabCopy.night(27_600, SleepHistory.weekOthers(week, today)), m.sentence)
        assertEquals(TabCopy.sleepScoreLine(82), m.scoreLine)
    }

    @Test fun `the legend is the stage totals, deepest first, and adds up to time asleep`() {
        val m = model()
        assertEquals(listOf("Deep 1 h 32", "REM 1 h 41", "Light 4 h 27", "Awake 18 min"), m.legend.map { it.label })
        assertEquals(SleepStageOrder, m.totals.map { it.first })
        assertEquals(SleepStageOrder, m.legend.map { it.stage })
        val asleep = m.totals.filter { it.first != SleepStage.AWAKE }.sumOf { it.second }
        assertEquals(night().asleepSeconds, asleep)
        assertEquals("7 h 40", TodayCopy.duration(asleep))
        assertEquals(18 * 60L, m.totals.single { it.first == SleepStage.AWAKE }.second)
    }

    @Test fun `a stage under a minute leaves the bar and the legend`() {
        val st = stages().filter { it.stage != SleepStage.AWAKE } + SleepStage(end, end + 30, start, SleepStage.AWAKE)
        val m = SleepWidgetModel.from(night(st = st), null, false, false, zone)
        assertEquals(listOf("Deep", "REM", "Light"), m.legend.map { it.label.substringBefore(' ') })
    }

    @Test fun `the stage bar and the stage rows get the tab's inputs`() {
        val m = model()
        // The bar: segments in stage order, filling the width less the gaps.
        val segments = stageBarSegments(m.totals, 636, 4)
        assertEquals(SleepStageOrder, segments.map { it.stage })
        assertEquals(636 - 3 * 4, segments.sumOf { it.width })
        // The rows: a stage each in the same order, named and timed as the legend, shares adding up to the night.
        assertEquals(m.totals.map { it.first }, m.stages.map { it.stage })
        assertEquals(m.legend.map { it.label }, m.stages.map { "${it.name} ${it.time}" })
        assertEquals(1f, m.stages.sumOf { it.share.toDouble() }.toFloat(), 1e-4f)
    }

    @Test fun `before the first night, the title and After your first night`() {
        val m = SleepWidgetModel.from(null, null, firstNight = true, napsToday = false, zone = zone)
        assertEquals("Last night", m.title)
        assertEquals(AFTER_FIRST_NIGHT, m.waiting)
        assertNull(m.date)
        assertNull(m.score)
        assertNull(m.note)
    }

    @Test fun `with no night at all after the first, the Sleep tab's no-night line`() {
        assertEquals("No sleep recorded last night. Was the strap on?", SleepWidgetModel.from(null, null, false, false, zone).waiting)
        assertEquals("No night sleep recorded, only naps.", SleepWidgetModel.from(null, null, false, true, zone).waiting)
    }

    @Test fun `before this morning's night is synced, yesterday's with Last synced night`() {
        val y = today.minusDays(1)
        val ys = SleepSession(at(y.minusDays(1), 23, 10), at(y, 6, 40), score = 74, wakeCount = 1)
        val m = SleepWidgetModel.from(null, night(ys, stages(ys), y), firstNight = false, napsToday = false, zone = zone)
        assertTrue(m.stale)
        assertEquals("Sleep", m.title)
        assertEquals("Last synced night", m.note)
        assertEquals(y, m.date)
        assertEquals("74", m.score)
        assertEquals("Fair", m.word)
        assertEquals("Woke once", m.woke)
        // This morning's wins once it's in.
        assertEquals(today, SleepWidgetModel.from(night(), night(ys, stages(ys), y), false, false, zone).date)
    }

    @Test fun `a night without a score or stages says so and keeps time asleep`() {
        val bare = SleepSession(start, end)
        val m = SleepWidgetModel.from(night(bare, emptyList()), null, false, false, zone)
        assertNull(m.score)
        assertNull(m.word)
        assertEquals("No score from the strap", m.scoreLine)
        assertNull(m.scoreDescription)
        // No stages: the whole session, as the hero.
        assertEquals("7 h 58 asleep", m.asleep)
        assertTrue(m.totals.isEmpty())
        assertTrue(m.legend.isEmpty())
        assertTrue(m.stages.isEmpty())
        assertNull(m.woke)
        assertEquals("7 h 58 asleep", m.largeRight)
    }

    @Test fun `the main sleep and the week are the hero's`() {
        val nap = SleepSession(at(today, 14, 0), at(today, 14, 40), nap = true)
        val short = SleepSession(at(today, 3, 0), at(today, 4, 0))
        val main = SleepHistory.mainSleep(listOf(nap, short, session), DayWindow.nightEndingOn(today, zone))
        assertEquals(session, main)
        assertNull(SleepHistory.mainSleep(listOf(nap), DayWindow.nightEndingOn(today, zone)))
        // The six nights before, with a sleep: not this one.
        assertEquals(listOf(26_400L, 25_200L, 22_200L).sorted(), SleepHistory.weekOthers(week + (today to session), today).sorted())
    }

    @Test fun `every string keeps to the copy rules`() {
        val y = SleepWidgetModel.from(null, night(), false, false, zone)
        val all = listOf(model(), y, SleepWidgetModel.from(null, null, true, false, zone), SleepWidgetModel.from(null, null, false, true, zone))
        all.flatMap { m -> listOfNotNull(m.title, m.note, m.word, m.scoreLine, m.asleep, m.woke, m.sentence, m.waiting, m.scoreDescription) + m.legend.map { it.label } }
            .forEach { s -> assertFalse(s, s.any { it in "·;—" }) }
    }

    /* --------------------------------------------- The legend --------------------------------------------- */

    @Test fun `the legend is one row of four when it fits, else two of two, never a wrapped label`() {
        val items = model().legend
        val oneRow = items.sumOf { SleepLayout.legendItem(it.label, 1f).toDouble() }.toFloat() + 3 * SleepLayout.LEGEND_GAP_DP
        assertEquals(1, SleepLayout.legendRows(items, oneRow, 1f))
        assertEquals(1, SleepLayout.legendRows(items, 420f, 1f))
        assertEquals(2, SleepLayout.legendRows(items, oneRow - 1f, 1f))
        assertEquals(2, SleepLayout.legendRows(items, wide.first, 1f))
        assertEquals(2, SleepLayout.legendRows(items, wide.first, 1.3f))
        // So narrow that half the width can't hold "Light 4 h 27": no legend rather than a broken one.
        assertEquals(0, SleepLayout.legendRows(items, 150f, 1f))
        assertEquals(0, SleepLayout.legendRows(emptyList(), 400f, 1f))
        // Two rows: each label fits its half.
        val half = (wide.first - SleepLayout.LEGEND_GAP_DP) / 2
        assertTrue(items.all { SleepLayout.legendItem(it.label, 1.3f) <= half })
        assertEquals(2 * WidgetText.line(SleepLayout.LEGEND_SP) + SleepLayout.LEGEND_ROW_GAP_DP, SleepLayout.legendHeight(2, 1f), 1e-4f)
    }

    /* ------------------------------------------- Fit at the minimum ------------------------------------------- */

    private val small = WidgetSizes.Small.width.value - 2 * WidgetSizes.PADDING_DP to WidgetSizes.Small.height.value - 2 * WidgetSizes.PADDING_DP
    private val wide = WidgetSizes.Wide.width.value - 2 * WidgetSizes.PADDING_DP to WidgetSizes.Wide.height.value - 2 * WidgetSizes.PADDING_DP
    private val large = WidgetSizes.Large.width.value - 2 * WidgetSizes.PADDING_DP to WidgetSizes.Large.height.value - 2 * WidgetSizes.PADDING_DP

    /** The default font, the large one, and One UI's default (about 1.08). */
    private val scales = listOf(1f, 1.08f, 1.3f)

    private fun smallHeight(f: SleepLayout.Small, s: Float) = SleepLayout.smallHeight(f, s)

    @Test fun `the Small keeps everything at the default font and fits at every size`() {
        val m = model()
        val normal = SleepLayout.small(m, small.first, small.second, 1f)
        assertEquals(WidgetHero.dot(WidgetSizes.Small, small.second), normal.row.dot, 0f)
        assertEquals("Good", normal.row.word)
        assertEquals(listOf(SleepLayout.SmallLine("7 h 40 asleep")), normal.lines)
        assertTrue(normal.bar)
        for (s in scales) {
            for (mm in listOf(m, SleepWidgetModel.from(null, night(), false, false, zone), SleepWidgetModel.from(night(SleepSession(start, end), emptyList()), null, false, false, zone))) {
                val f = SleepLayout.small(mm, small.first, small.second, s)
                assertTrue("${mm.title} at $s: ${smallHeight(f, s)}", smallHeight(f, s) <= small.second + 1e-3f)
                assertTrue(f.row.width(mm.score, s, SleepLayout.SMALL_WORD_GAP_DP) <= small.first + 1e-3f || f.row.wordLines > 1)
                f.lines.forEach { assertTrue(WidgetText.width(it.text, WidgetText.SECONDARY_SP, s) <= small.first * it.lines) }
            }
        }
        // Yesterday's: "Last synced night" stays (on two lines at the minimum's width), time asleep and then the bar go.
        val stale = SleepLayout.small(SleepWidgetModel.from(null, night(), false, false, zone), small.first, small.second, 1f)
        assertEquals(listOf(SleepLayout.SmallLine("Last synced night", 2)), stale.lines)
        // On One UI's real 2 x 2 (167 by 201 dp), one line, then time asleep and the bar.
        val real = SleepLayout.small(SleepWidgetModel.from(null, night(), false, false, zone), 167.6f - 40f, 201.1f - 40f, 1f)
        assertTrue(real.bar)
        assertEquals(listOf(SleepLayout.SmallLine("Last synced night"), SleepLayout.SmallLine("7 h 40 asleep")), real.lines)
        // A score of 100 shrinks the dots to keep the word, and at 1.3 drops the word before the dots get too small.
        val hundred = SleepLayout.small(m.copy(score = "100", word = "Great"), small.first, small.second, 1f)
        assertEquals("Great", hundred.row.word)
        assertTrue(hundred.row.dot < SleepLayout.SMALL_DOT)
        assertTrue(hundred.row.width("100", 1f, SleepLayout.SMALL_WORD_GAP_DP) <= small.first + 1e-3f)
        val big = SleepLayout.small(m.copy(score = "100", word = "Great"), small.first, small.second, 1.3f)
        assertNull(big.row.word)
        assertEquals(WidgetHero.dot(WidgetSizes.Small, small.second), big.row.dot, 0f)
    }

    @Test fun `the Wide fits its width and height at every size, dropping the legend before clipping`() {
        val m = model()
        for (s in scales) {
            val f = SleepLayout.wide(m, wide.first, wide.second, s)
            assertTrue("width at $s: ${f.row.width(m.score, s)}", f.row.width(m.score, s) <= wide.first + 1e-3f)
            assertTrue("height at $s: ${SleepLayout.wideHeight(f, s)}", SleepLayout.wideHeight(f, s) <= wide.second + 1e-3f)
            assertTrue(f.row.dot >= SleepLayout.SMALL_DOT)
            assertEquals("7 h 40 asleep", f.row.right)
            // The minimum's width holds the note beside the title up to the phones' 1.08.
            assertTrue(f.note || s > 1.08f)
            assertTrue(f.bar)
        }
        // The default font at the minimum: the short word keeps time asleep, the legend on two rows.
        val normal = SleepLayout.wide(m, wide.first, wide.second, 1f)
        assertEquals("Good", normal.row.word)
        assertEquals(2, normal.legendRows)
        // A real 4 x 2 (One UI's, 353 dp) has room for the tab's words.
        assertEquals("Sleep score, good", SleepLayout.wide(m, 353f - 40f, 161f, 1.08f).row.word)
        assertEquals(WidgetHero.dot(WidgetSizes.Wide, 161f), SleepLayout.wide(m, 353f - 40f, 161f, 1f).row.dot, 0f)
        // At 1.3 on the minimum, no room for the legend: it goes, the bar stays.
        assertEquals(0, SleepLayout.wide(m, wide.first, wide.second, 1.3f).legendRows)
    }

    @Test fun `the Large fits its width and height at every size, the sentence going first, then the rows`() {
        val m = model()
        for (s in scales) {
            val f = SleepLayout.large(m, large.first, large.second, s)
            assertTrue("width at $s", f.row.width(m.score, s) <= large.first + 1e-3f)
            assertTrue("height at $s: ${SleepLayout.largeHeight(f, m.stages.size, s)}", SleepLayout.largeHeight(f, m.stages.size, s) <= large.second + 1e-3f)
            assertEquals("Woke 3 times", f.row.right)
            assertTrue("rows at $s", f.stages)
        }
        // At the default font the minimum Large keeps the sentence beside the rows.
        assertTrue(SleepLayout.large(m, large.first, large.second, 1f).sentenceLines > 0)
        // One UI's 4 x 3 (353 x 375, content 313 x 335) keeps it all at its own font size.
        val oneUi = SleepLayout.large(m, 313f, 335f, 1.08f)
        assertTrue(oneUi.stages && oneUi.sentenceLines > 0)
        // Too short for the rows: the stage bar.
        val short = SleepLayout.large(m, large.first, 140f, 1f)
        assertFalse(short.stages)
        assertTrue(short.bar)
        // Without stages: no rows or bar, and the rest fits.
        val bare = SleepWidgetModel.from(night(SleepSession(start, end), emptyList()), null, false, false, zone)
        val b = SleepLayout.large(bare, large.first, large.second, 1.3f)
        assertFalse(b.stages)
        assertFalse(b.bar)
        assertTrue(SleepLayout.largeHeight(b, 0, 1.3f) <= large.second)
    }

    @Test fun `with the strap status the title row counts the button and the words replace the times`() {
        val m = WidgetSamples.sleep(today, zone)
        val l = StatusLine(StrapStatus.StaleSync(0), listOf("Synced Wednesday", "Synced Wed", "Wednesday", "Wed"))
        val shown = SleepLayout.large(m, 313f, 290f, 1f, l)
        assertEquals(StatusButton.SIZE_DP, shown.top.height, 0f)
        assertNotNull(shown.top.words)
        assertFalse(shown.top.note)
        assertTrue(SleepLayout.largeHeight(shown, m.stages.size, 1f) <= 290.01f)
        assertNull(SleepLayout.small(m, 140f, 180f, 1f, l).top.words)
    }
}
