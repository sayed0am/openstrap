package org.openstrap.app.widget

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Summaries
import org.openstrap.app.ui.glow.draw.WeekBarsLook
import org.openstrap.app.ui.glow.draw.weekBars
import org.openstrap.app.ui.glow.draw.weekStub

/** Widgets spec 3.3: what the Steps widget shows in each state, from the app's own sums and copy, and how it fits. */
class StepsWidgetModelTest {
    private val today = LocalDate.of(2026, 10, 24) // a Saturday
    private val monday = LocalDate.of(2026, 10, 19)

    /** Mon 7,800, Tue 11,200, Wed 5,900, Thu 10,400, Fri 6,300: the mockup's week, two met of five. */
    private val past = listOf(7_800, 11_200, 5_900, 10_400, 6_300).mapIndexed { i, s -> monday.plusDays(i.toLong()) to s }.toMap()

    /** 8,432 steps by 14:xx, spread over the day's hours. */
    private val hourly = List(24) { h -> if (h in 7..14) 1_054f else 0f }

    private fun model(steps: Int = 8_432, goal: Int = 10_000, days: Map<LocalDate, Int> = past, hour: Int = 14, h: List<Float> = hourly) =
        StepsWidgetModel.from(today, steps, h, hour, goal, days, Locale.UK)

    @Test fun `today's steps against the goal`() {
        val m = model()
        assertEquals("Steps", m.title)
        assertEquals("Goal 10,000", m.goalNote)
        assertEquals("8,432", m.stepsText)
        assertEquals("today", m.word)
        assertEquals("1,568 to go", m.toGo)
        assertEquals("84% of your goal", m.share)
        assertEquals(0.8432f, m.progress, 1e-4f)
        assertFalse(m.goalMet)
        assertEquals("8,432 steps today, 84% of your goal", m.description)
        assertEquals(today, m.date)
    }

    @Test fun `past the goal, Goal met replaces what's to go and the bar stays full`() {
        val m = model(steps = 12_345)
        assertTrue(m.goalMet)
        assertEquals("Goal met", m.toGo)
        assertEquals("Goal met", m.share)
        assertEquals(1f, m.progress, 0f)
        assertEquals("12,345 steps today, goal met", m.description)
        assertEquals("Goal met", StepsWidgetModel.toGo(10_000, 10_000))
        assertEquals("1 to go", StepsWidgetModel.toGo(9_999, 10_000))
    }

    @Test fun `before any steps today, 0 against the goal and no line`() {
        val m = model(steps = 0, h = List(24) { 0f }, days = emptyMap(), hour = 7)
        assertEquals("0", m.stepsText)
        assertEquals("10,000 to go", m.toGo)
        assertEquals("0% of your goal", m.share)
        assertEquals(0f, m.progress, 0f)
        assertFalse(m.showLine)
        assertEquals("Goal met 0 of 1 day", m.weekLine)
    }

    @Test fun `the line is the Steps card's, the running total up to the hour now`() {
        val m = model()
        assertTrue(m.showLine)
        assertEquals(Summaries.runningSteps(hourly, 14), m.line)
        assertEquals(24, m.line.size)
        assertNull(m.line[15])
        assertEquals(8_432f, m.line[14]!!, 0f)
        assertEquals(10_000f, m.lineMax, 0f)
        assertEquals(12_345f, model(steps = 12_345).lineMax, 0f)
    }

    @Test fun `the week, Monday to Sunday, met, missed, today and to come`() {
        val m = model()
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), m.week.map { it.letter })
        assertEquals(
            listOf(StepsDay.Missed, StepsDay.Met, StepsDay.Missed, StepsDay.Met, StepsDay.Missed, StepsDay.Today, StepsDay.ToCome),
            m.week.map { it.state },
        )
        assertEquals(listOf(7_800, 11_200, 5_900, 10_400, 6_300, 8_432, null), m.week.map { it.steps })
        assertEquals("Goal met 2 of 6 days", m.weekLine)
    }

    @Test fun `today counts once it meets the goal, and a day without a record doesn't count`() {
        val days = past - monday.plusDays(2)
        val m = model(steps = 10_500, days = days)
        assertEquals(StepsDay.NoRecord, m.week[2].state)
        assertEquals(StepsDay.Today, m.week[5].state)
        assertEquals("Goal met 3 of 5 days", m.weekLine)
    }

    @Test fun `on a Monday the week is today and six to come`() {
        val m = StepsWidgetModel.from(monday, 3_000, hourly, 9, 10_000, emptyMap(), Locale.UK)
        assertEquals(StepsDay.Today, m.week.first().state)
        assertTrue(m.week.drop(1).all { it.state == StepsDay.ToCome })
        assertEquals("Goal met 0 of 1 day", m.weekLine)
    }

    @Test fun `bars are each day's share of the week's top, none for days to come or not recorded`() {
        val m = model(days = past - monday.plusDays(2))
        val values = StepsWidgetModel.barValues(m.week, m.goal)
        assertEquals(1f, values[1]!!, 1e-4f) // Tuesday's 11,200 is the top, over the goal
        assertEquals(7_800f / 11_200f, values[0]!!, 1e-4f)
        assertEquals(8_432f / 11_200f, values[5]!!, 1e-4f)
        assertNull(values[2])
        assertNull(values[6])
        // Under the goal all week, the goal is the top.
        val quiet = model(steps = 5_000, days = mapOf(monday to 4_000))
        assertEquals(0.4f, StepsWidgetModel.barValues(quiet.week, quiet.goal)[0]!!, 1e-4f)

        val density = 2f
        val bars = weekBars(values, density)
        val full = WeekBarsLook.HEIGHT_DP * density
        assertEquals(full, bars[1]!!.size.height, 1e-3f)
        assertTrue(bars[0]!!.size.height < bars[1]!!.size.height)
        val stub = weekStub(6, density)
        assertEquals(WeekBarsLook.STUB_DP * density, stub.size.height, 1e-3f)
        assertEquals(full - stub.size.height, stub.topLeft.y, 1e-3f)
        assertEquals(bars[1]!!.size.width, stub.size.width, 1e-3f)
        assertEquals(bars[0]!!.topLeft.x + 6 * (WeekBarsLook.COLUMN_DP + WeekBarsLook.GAP_DP) * density, stub.topLeft.x, 1e-3f)
    }

    @Test fun `every string keeps to the copy rules`() {
        val m = model()
        (listOf(m.title, m.goalNote, m.word, m.toGo, m.share, m.weekLine, m.description)).forEach { s ->
            assertFalse(s, s.any { it in "·;—" })
        }
    }

    /* ------------------------------------------- Fit at the minimum ------------------------------------------- */

    private val small = WidgetSizes.Small.width.value - 2 * WidgetSizes.PADDING_DP to WidgetSizes.Small.height.value - 2 * WidgetSizes.PADDING_DP
    private val wide = WidgetSizes.Wide.width.value - 2 * WidgetSizes.PADDING_DP to WidgetSizes.Wide.height.value - 2 * WidgetSizes.PADDING_DP
    private val large = WidgetSizes.Large.width.value - 2 * WidgetSizes.PADDING_DP to WidgetSizes.Large.height.value - 2 * WidgetSizes.PADDING_DP

    /** The Small's column, as SmallContent stacks it. */
    private fun smallHeight(fit: StepsLayout.Small, fontScale: Float): Float =
        WidgetText.line(WidgetText.TITLE_SP, fontScale) + StepsLayout.dotsHeight(fit.dot) +
            (if (fit.word) StepsLayout.SMALL_WORD_GAP_DP + WidgetText.line(WidgetText.BODY_SP, fontScale) else 0f) +
            StepsLayout.SMALL_BAR_GAP_DP + StepsLayout.BAR_DP +
            (if (fit.share) StepsLayout.SMALL_BAR_GAP_DP + WidgetText.line(WidgetText.SECONDARY_SP, fontScale) else 0f)

    @Test fun `the Small keeps everything at the default font and fits at 1_3`() {
        val m = model()
        val normal = StepsLayout.small(m.stepsText, m.share, small.first, small.second, 1f)
        assertEquals(Triple(StepsLayout.SMALL_DOT, true, true), Triple(normal.dot, normal.word, normal.share))
        assertTrue(smallHeight(normal, 1f) <= small.second)
        val big = StepsLayout.small(m.stepsText, m.share, small.first, small.second, 1.3f)
        assertTrue(smallHeight(big, 1.3f) <= small.second)
        assertTrue(big.word || big.share)
        // Five figures shrink the dots to the width rather than clip.
        val many = StepsLayout.small("12,345", "Goal met", small.first, small.second, 1f)
        assertTrue(many.dot < StepsLayout.SMALL_DOT)
        assertTrue(StepsLayout.dotsWidth("12,345", many.dot) <= small.first + 1e-3f)
    }

    @Test fun `the Wide's number row fits its width at both font sizes`() {
        for (scale in listOf(1f, 1.3f)) for ((steps, toGo) in listOf("8,432" to "1,568 to go", "12,345" to "2,655 to go", "12,345" to "Goal met")) {
            val row = StepsLayout.numberRow(steps, toGo, wide.first, scale)
            val need = StepsLayout.dotsWidth(steps, row.dot) +
                (if (row.word) StepsLayout.WORD_GAP_DP + WidgetText.width("today", WidgetText.BODY_SP, scale) else 0f) +
                StepsLayout.SIDE_GAP_DP + WidgetText.width(toGo, WidgetText.SECONDARY_SP, scale)
            assertTrue("$steps $toGo at $scale: $need", need <= wide.first + 1e-3f)
            assertTrue(row.dot >= StepsLayout.SMALL_DOT || !row.word)
        }
        assertEquals(StepsLayout.NumberRow(StepsLayout.WIDE_DOT, word = true), StepsLayout.numberRow("8,432", "1,568 to go", 353f - 40f, 1f))
    }

    @Test fun `the Wide's line fits its height at both font sizes`() {
        for (scale in listOf(1f, 1.3f)) {
            val row = StepsLayout.numberRow("8,432", "1,568 to go", wide.first, scale)
            val line = assertNotNullLine(StepsLayout.wideLine(wide.second, row, scale))
            val total = WidgetText.line(WidgetText.TITLE_SP, scale) + row.height(scale) + line + 2 * StepsLayout.MIN_GAP_DP
            assertTrue("at $scale: $total", total <= wide.second + 1e-3f)
        }
        assertEquals(StepsLayout.LINE_DP, StepsLayout.wideLine(wide.second, StepsLayout.numberRow("8,432", "1,568 to go", wide.first, 1f), 1f)!!, 0f)
        // A font too large for the line drops it rather than clip.
        assertNull(StepsLayout.wideLine(wide.second, StepsLayout.numberRow("8,432", "1,568 to go", wide.first, 2.4f), 2.4f))
    }

    @Test fun `the Large fits its height and the week its width at both font sizes`() {
        for (scale in listOf(1f, 1.3f)) {
            val row = StepsLayout.numberRow("8,432", "1,568 to go", large.first, scale)
            val week = StepsLayout.week("Goal met 2 of 6 days", large.first, scale)
            val line = assertNotNullLine(StepsLayout.largeLine(large.second, row, week, scale))
            val total = WidgetText.line(WidgetText.TITLE_SP, scale) + row.height(scale) + line +
                week.height + 3 * StepsLayout.MIN_GAP_DP
            assertTrue("at $scale: $total", total <= large.second + 1e-3f)
            assertTrue(week.column >= WeekBarsLook.MIN_COLUMN_DP)
            assertTrue(WeekBarsLook.width(7, week.column) <= large.first)
            // "This week" beside the count at the minimum Large at the default font. At 1.3 the estimate leaves no
            // room, so "This week" drops and the count keeps its one line.
            if (scale == 1f) assertTrue(week.title)
        }
        // With room, the week keeps the mockup's columns.
        assertEquals(WeekBarsLook.COLUMN_DP, StepsLayout.week("Goal met 2 of 6 days", 420f, 1f).column, 0f)
        // A font too large for both drops "This week", never wraps the count.
        assertFalse(StepsLayout.week("Goal met 2 of 6 days", large.first, 2f).title)
    }

    private fun assertNotNullLine(v: Float?): Float {
        assertNotNull(v)
        return v!!
    }

    @Test fun `with the strap status the title row counts the button and the words replace the goal`() {
        val m = WidgetSamples.steps(today, Locale.UK)
        val l = StatusLine(StrapStatus.StaleSync(0), listOf("Synced Wednesday", "Synced Wed", "Wednesday", "Wed"))
        val shown = StepsLayout.wide(m, 313f, 180f, 1f, l)
        assertEquals(StatusButton.SIZE_DP, shown.top.height, 0f)
        assertNotNull(shown.top.words)
        assertFalse(shown.top.note)
        assertTrue(StepsLayout.wide(m, 313f, 180f, 1f).top.note)
        val small = StepsLayout.small(m.stepsText, m.share, 140f, 180f, 1f, l)
        assertNull(small.top.words)
        assertEquals(StatusButton.SIZE_DP, small.top.height, 0f)
    }
}
