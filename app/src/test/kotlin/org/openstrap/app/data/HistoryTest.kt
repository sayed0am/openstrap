package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HistoryTest {
    private val end = LocalDate.of(2026, 10, 17)
    private fun day(daysAgo: Long, v: Float) = DayStat(end.minusDays(daysAgo), v, v, v, 1)

    @Test
    fun normalNeedsAWeekOfData() {
        assertNull(History.normal((0L..5L).map { day(it, 60f) }, end))
        assertNotNull(History.normal((0L..6L).map { day(it, 60f) }, end))
    }

    @Test
    fun normalIsMeanPlusMinusOneSd() {
        // 7 days: 56, 58, 60, 60, 60, 62, 64 -> mean 60, sample sd 2.58
        val stats = listOf(56f, 58f, 60f, 60f, 60f, 62f, 64f).mapIndexed { i, v -> day(i.toLong(), v) }
        val n = History.normal(stats, end)!!
        assertEquals(60f, n.mean, 0.01f)
        assertEquals(57.42f, n.low, 0.01f)
        assertEquals(62.58f, n.high, 0.01f)
        assertEquals(7, n.days)
    }

    @Test
    fun normalIgnoresDaysOutsideTheWindow() {
        val stats = (0L..6L).map { day(it, 60f) } + day(40, 200f) + DayStat(end.plusDays(1), 200f, 200f, 200f, 1)
        assertEquals(60f, History.normal(stats, end)!!.mean, 0.001f)
    }

    @Test
    fun statusComparesWithTheRange() {
        val n = History.Normal(60f, 57f, 63f, 30)
        assertEquals(1, History.status(64f, n))
        assertEquals(0, History.status(60f, n))
        assertEquals(-1, History.status(56f, n))
        assertNull(History.status(null, n))
        assertNull(History.status(60f, null))
    }

    @Test
    fun weeklyGroupsFromMonday() {
        // 17 Oct 2026 is a Saturday: 12–17 Oct are one week, 11 Oct belongs to the week before.
        val stats = listOf(day(6, 10f), day(5, 20f), day(0, 30f))
        val weeks = History.weekly(stats, java.time.DayOfWeek.MONDAY)
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)), weeks.map { it.date })
        assertEquals(25f, weeks[1].value, 0.001f)
        assertEquals(20f, weeks[1].min, 0.001f)
        assertEquals(30f, weeks[1].max, 0.001f)
    }

    @Test
    fun summaryCoversOnlyTheRange() {
        val stats = listOf(day(8, 100f), day(2, 10f), day(1, 30f), day(0, 20f))
        val s = History.summary(stats, end.minusDays(6), end)!!
        assertEquals(20f, s.average, 0.001f)
        assertEquals(10f, s.lowest.value, 0f)
        assertEquals(30f, s.highest.value, 0f)
        assertEquals(60f, s.total, 0.001f)
        assertEquals(3, s.days)
        assertNull(History.summary(stats, end.plusDays(1), end.plusDays(2)))
    }
}
