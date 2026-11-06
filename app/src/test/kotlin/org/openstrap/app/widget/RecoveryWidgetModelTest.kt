package org.openstrap.app.widget

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.presentation.AFTER_FIRST_NIGHT
import org.openstrap.app.presentation.TabCopy
import org.openstrap.app.presentation.TodayCopy
import org.openstrap.app.ui.glow.draw.WeekBarsLook
import org.openstrap.app.ui.glow.draw.weekBars

/** Widgets spec 3.4: what the Recovery widget shows in each state, from the app's own result and copy. */
class RecoveryWidgetModelTest {
    private val today = LocalDate.of(2026, 10, 24) // a Saturday
    private val week = (6 downTo 0).map { today.minusDays(it.toLong()) }.zip(listOf(65, 82, 55, 72, 88, 60, 78))

    private fun day(
        score: Int? = 78, date: LocalDate = today, firstNights: Boolean = false, alert: Boolean = false,
        days: List<Pair<LocalDate, Int?>> = week, strap: Int? = null,
    ): RecoveryDay {
        val ready = Recovery.Readiness(score, emptyList(), 30, alert, emptyList(), false)
        return RecoveryDay(
            date = date,
            shown = TodayCopy.recoveryShown(score, alert, null, strap, TabCopy.RECOVERY_WAITING),
            sentence = score?.let { TabCopy.recovery(it, alert, ready.contributors) },
            firstNights = firstNights,
            restingHr = 52f, hrv = 64.4f, sleep = 82,
            week = days,
        )
    }

    @Test fun `this morning's score`() {
        val m = RecoveryWidgetModel.from(day(), null, Locale.UK)
        assertEquals("Recovery", m.title)
        assertEquals("This morning", m.note)
        assertEquals("78", m.score)
        assertEquals("Ready", m.word)
        assertEquals("78, Ready", m.scoreDescription)
        assertEquals("Recovered enough for your usual training.", m.sentence)
        assertNull(m.waiting)
        assertEquals(today, m.date)
        assertEquals(
            listOf(RecoveryFigure("Resting", "52 bpm"), RecoveryFigure("HRV", "64 ms"), RecoveryFigure("Sleep", "82")),
            m.figures,
        )
    }

    @Test fun `the week, oldest first, the day shown last and lit`() {
        val m = RecoveryWidgetModel.from(day(), null, Locale.UK)
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), m.week.map { it.letter })
        assertEquals(listOf(65, 82, 55, 72, 88, 60, 78), m.week.map { it.score })
        assertEquals(listOf(false, false, false, false, false, false, true), m.week.map { it.shown })
    }

    @Test fun `fewer than two scored days leaves the week out, as the tab's trend`() {
        val lone = week.map { (d, _) -> d to null } .dropLast(1) + (today to 78)
        assertTrue(RecoveryWidgetModel.from(day(days = lone), null).week.isEmpty())
    }

    @Test fun `before this morning's score, yesterday's with Yesterday as the note`() {
        val y = day(score = 64, date = today.minusDays(1))
        val m = RecoveryWidgetModel.from(day(score = null), y)
        assertEquals("Yesterday", m.note)
        assertEquals("64", m.score)
        assertEquals("Moderate", m.word)
        assertEquals(today.minusDays(1), m.date)
    }

    @Test fun `the strap's estimate stands in as Today shows it`() {
        val m = RecoveryWidgetModel.from(day(score = null, strap = 91), null)
        assertEquals("91", m.score)
        assertEquals("Strap's estimate", m.word)
        assertEquals("This morning", m.note)
        assertEquals("unstrap's own score isn't ready yet.", m.sentence)
    }

    @Test fun `before the first night, the title and After your first night`() {
        val m = RecoveryWidgetModel.from(day(score = null, firstNights = true), null)
        assertEquals("Recovery", m.title)
        assertNull(m.score)
        assertNull(m.note)
        assertEquals(AFTER_FIRST_NIGHT, m.waiting)
        assertTrue(m.figures.isEmpty() && m.week.isEmpty())
    }

    @Test fun `no score today or yesterday after the first night says why`() {
        assertEquals(TabCopy.RECOVERY_WAITING, RecoveryWidgetModel.from(day(score = null), day(score = null)).waiting)
        assertEquals(TabCopy.RECOVERY_WAITING, RecoveryWidgetModel.from(null, null).waiting)
    }

    @Test fun `a figure the night didn't give says so`() {
        val m = RecoveryWidgetModel.from(day().copy(hrv = null, sleep = null), null)
        assertEquals(listOf("52 bpm", "No reading", "No reading"), m.figures.map { it.value })
    }

    @Test fun `from the app's result, the night's own values and the Sleep contributor`() {
        val night = Recovery.Night(
            day = 0, start = 0, end = 8 * 3600, asleepMinutes = 450, strapScore = 80, hrv = 61.6f, restingHr = 51.6f,
            hrvSamples = 40, hrMinutes = 400,
        )
        val ready = Recovery.Readiness(
            78, listOf(Recovery.Contributor(Recovery.Factor.Sleep, 82, Recovery.Detail.Asleep(450))), 30, false, emptyList(), false,
        )
        val r = RecoveryResult(
            today, ready, Recovery.ChargeDay(emptyList(), null, null, 0f, 0f, 0f, 0f, null, null, anchored = true), week,
            0f, null, 52f, 190f, true, Recovery.StressThreshold(60, null, 0), tonight = night,
        )
        val d = RecoveryDay.of(r, strapScore = 91)
        assertEquals("Ready", d.shown.word)
        assertEquals(78, d.shown.value)
        assertEquals(51.6f, d.restingHr)
        assertEquals(61.6f, d.hrv)
        assertEquals(82, d.sleep)
        assertEquals(false, d.firstNights)
        assertEquals(listOf("52 bpm", "62 ms", "82"), RecoveryWidgetModel.figures(d).map { it.value })
    }

    @Test fun `the strap's values stand in where the night's failed the validity rule`() {
        val night = Recovery.Night(day = 0, start = 0, end = 3600, asleepMinutes = 50, hrv = 30f, restingHr = 70f, strapScore = 40)
        val ready = Recovery.Readiness(null, emptyList(), 30, false, emptyList(), false, fallbackHrv = 45f, fallbackRestingHr = 55f)
        val r = RecoveryResult(
            today, ready, Recovery.ChargeDay(emptyList(), null, null, 0f, 0f, 0f, 0f, null, null, anchored = false), week,
            0f, null, 52f, 190f, true, Recovery.StressThreshold(60, null, 0), tonight = night,
        )
        val d = RecoveryDay.of(r, null)
        assertEquals(55f, d.restingHr)
        assertEquals(45f, d.hrv)
        assertEquals(40, d.sleep)
        assertTrue(d.firstNights)
    }

    @Test fun `week bars stand as high as the day's share of 100, none for a day without a score`() {
        val values = RecoveryLayout.barValues(listOf(RecoveryWeekDay("M", 100, false), RecoveryWeekDay("T", 50, false), RecoveryWeekDay("W", null, true)))
        assertEquals(listOf(1f, 0.5f, null), values)
        val bars = weekBars(values, density = 2f)
        assertEquals(WeekBarsLook.HEIGHT_DP * 2f, bars[0]!!.size.height, 0.01f)
        assertEquals(WeekBarsLook.HEIGHT_DP, bars[1]!!.size.height, 0.01f)
        assertNull(bars[2])
        // Solid pills at most 16 dp wide, standing on the box's bottom, one per 30 dp column with 2 dp gaps.
        assertEquals(WeekBarsLook.BAR_DP * 2f, bars[0]!!.size.width, 0.01f)
        bars.filterNotNull().forEach { assertEquals(WeekBarsLook.HEIGHT_DP * 2f, it.topLeft.y + it.size.height, 0.01f) }
        assertEquals((30f + 2f) * 2f + (30f - 16f), bars[1]!!.topLeft.x, 0.01f)
        assertEquals(7 * 30f + 6 * 2f, WeekBarsLook.width(7), 0.01f)
    }

    @Test fun `a low day's bar is still a bar, with soft corners`() {
        val bar = weekBars(listOf(0.05f), density = 1f).single()!!
        assertEquals(WeekBarsLook.CORNER_DP * 2, bar.size.height, 0.01f)
        assertEquals(WeekBarsLook.CORNER_DP, bar.corner, 0.01f)
    }

    @Test fun `the week of dots narrows beside a long word, never below its least`() {
        assertEquals(RecoveryLayout.DOTS_COLUMN_DP, RecoveryLayout.weekColumn(358f, 55f, "Ready"), 0.01f)
        val long = RecoveryLayout.weekColumn(260f, 55f, "Ready for more")
        assertTrue(long < RecoveryLayout.DOTS_COLUMN_DP && long >= RecoveryLayout.DOTS_MIN_COLUMN_DP)
        assertEquals(RecoveryLayout.DOTS_MIN_COLUMN_DP, RecoveryLayout.weekColumn(318f, 55f, "Ready for more", fontScale = 2f), 0.01f)
    }

    @Test fun `the Large keeps the week over the figures on the Fold's cover cell at its font size`() {
        val m = WidgetSamples.recovery(today)
        val large = RecoveryLayout.large(m, 240f, 220f, 1.15f)
        assertTrue(large.week)
        assertTrue(large.sentenceLines > 0)
        assertTrue(RecoveryLayout.wide(m, 240f, 120f, 1.15f).week)
    }

    @Test fun `with the strap status the title row counts the button and the words replace the note`() {
        val m = WidgetSamples.recovery(today, java.util.Locale.UK)
        val l = StatusLine(StrapStatus.StaleSync(0), listOf("Synced Wednesday", "Synced Wed", "Wednesday", "Wed"))
        val plain = RecoveryLayout.wide(m, 313f, 180f, 1f)
        val shown = RecoveryLayout.wide(m, 313f, 180f, 1f, l)
        assertTrue(plain.top.note)
        assertEquals(StatusButton.SIZE_DP, shown.top.height, 0f)
        assertEquals("Synced Wednesday", shown.top.words)
        assertTrue(!shown.top.note)
        assertTrue(shown.spread.fits(180f, RecoveryLayout.MIN_GAP_DP))
        // The Small: the button alone.
        assertNull(RecoveryLayout.small(m, 140f, 180f, 1f, l).top.words)
        assertEquals(StatusButton.SIZE_DP, RecoveryLayout.small(m, 140f, 180f, 1f, l).top.height, 0f)
    }
}
