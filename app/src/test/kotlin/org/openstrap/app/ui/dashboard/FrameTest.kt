package org.openstrap.app.ui.dashboard

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.SyncState
import org.openstrap.app.presentation.Metric
import org.openstrap.app.presentation.dayTitle
import org.openstrap.app.presentation.todayTitle
import org.openstrap.app.ui.glow.draw.momentMood

/** The header's day labels, the strap chip's words and the tab rules (spec 3.1 to 3.3). */
class FrameTest {
    private val today = LocalDate.of(2026, 10, 23) // a Friday
    private val uk = Locale.UK

    @Test fun `title says today, yesterday, the weekday within the week, then the date`() {
        assertEquals("Today", dayTitle(today, today, uk))
        assertEquals("Yesterday", dayTitle(today.minusDays(1), today, uk))
        assertEquals("Wednesday", dayTitle(today.minusDays(2), today, uk))
        assertEquals("Saturday", dayTitle(today.minusDays(6), today, uk))
        // A week back would read as today's weekday, so it takes its date, with the year from an earlier one.
        assertEquals("Fri 16 Oct", dayTitle(today.minusDays(7), today, uk))
        assertEquals("Wed 31 Dec 2025", dayTitle(LocalDate.of(2025, 12, 31), today, uk))
        assertEquals("Fri 16 Oct", dateLine(today.minusDays(7), uk))
    }

    @Test fun `the day under the title is short and never has an interpunct`() {
        assertEquals("Fri 23 Oct", dateLine(today, uk))
        assertEquals("Thu", peekDay(today.minusDays(1), uk))
    }

    private val now = 1_800_000_000L
    private fun connected(battery: Int? = 86, charging: Boolean = false) = ConnectionState.Connected(battery, "3.25", charging)

    @Test fun `all well is the battery and a dot`() {
        val chip = strapChip(true, connected(), SyncState.Done(now - 60), now - 10, now)
        assertEquals("86%", chip.text)
        assertEquals(StrapChip.Dot.Fine, chip.dot)
    }

    @Test fun `when several apply, the chip says the one that explains the rest`() {
        // Charging explains a failed sync (fast-charge mode stops syncs), so it wins.
        assertEquals("Charging 64%", strapChip(true, connected(64, charging = true), SyncState.Failed("fast charge"), now, now).text)
        // Gone for a while: how long, even with a failed sync behind it.
        assertEquals("18 min ago", strapChip(true, ConnectionState.Disconnected, SyncState.Failed("timeout"), now - 18 * 60, now).text)
        // Not paired outranks everything.
        assertEquals("Not paired", strapChip(false, connected(64, charging = true), SyncState.Failed("x"), now, now).text)
        // A low battery is quieter than a failed sync.
        assertEquals("Not synced", strapChip(true, connected(10), SyncState.Failed("x"), now, now).text)
    }

    @Test fun `words only when they matter`() {
        assertEquals("Charging 64%", strapChip(true, connected(64, charging = true), SyncState.Idle, null, now).text)
        assertEquals(StrapChip.Dot.Charging, strapChip(true, connected(64, charging = true), SyncState.Idle, null, now).dot)
        val failed = strapChip(true, connected(), SyncState.Failed("timeout"), now, now)
        assertEquals("Not synced", failed.text)
        assertEquals(StrapChip.Dot.Warn, failed.dot)
        assertEquals("Not paired", strapChip(false, ConnectionState.NotPaired, SyncState.Idle, null, now).text)
        assertEquals("Not paired", strapChip(true, ConnectionState.NotPaired, SyncState.Idle, null, now).text)
    }

    @Test fun `a low battery keeps the number and changes the dot`() {
        val chip = strapChip(true, connected(12), SyncState.Idle, now, now)
        assertEquals("12%", chip.text)
        assertEquals(StrapChip.Dot.Low, chip.dot)
    }

    @Test fun `a strap gone away says when it was last seen, in amber`() {
        val chip = strapChip(true, ConnectionState.Disconnected, SyncState.Idle, now - 18 * 60, now)
        assertEquals("18 min ago", chip.text)
        assertEquals(StrapChip.Dot.Warn, chip.dot)
        assertEquals("Not connected", strapChip(true, ConnectionState.Disconnected, SyncState.Idle, null, now).text)
        assertEquals("18 min ago", strapChip(true, ConnectionState.Failed("gone"), SyncState.Idle, now - 18 * 60, now).text)
    }

    @Test fun `a moment's connecting stays quiet, a long one shows the time`() {
        val brief = strapChip(true, ConnectionState.Connecting, SyncState.Idle, now - 30, now)
        assertEquals("", brief.text)
        assertEquals(StrapChip.Dot.Quiet, brief.dot)
        assertEquals("", strapChip(true, ConnectionState.Authenticating, SyncState.Idle, null, now).text)
        assertEquals("3 h ago", strapChip(true, ConnectionState.Connecting, SyncState.Idle, now - 3 * 3600, now).text)
    }

    @Test fun `right after the app starts, a strap not back yet is a quiet dot`() {
        val starting = strapChip(true, ConnectionState.Disconnected, SyncState.Idle, now - 29 * 60, now, sinceStart = 10)
        assertEquals("", starting.text)
        assertEquals(StrapChip.Dot.Quiet, starting.dot)
        assertEquals("", strapChip(true, ConnectionState.Connecting, SyncState.Idle, now - 29 * 60, now, sinceStart = 119).text)
        // After the grace, or once connected, as usual; an unpaired strap says so at once.
        assertEquals("29 min ago", strapChip(true, ConnectionState.Disconnected, SyncState.Idle, now - 29 * 60, now, sinceStart = STARTUP_GRACE_S).text)
        assertEquals("45%", strapChip(true, connected(45), SyncState.Idle, now, now, sinceStart = 5).text)
        assertEquals("Not paired", strapChip(false, ConnectionState.NotPaired, SyncState.Idle, null, now, sinceStart = 5).text)
    }

    @Test fun `connected without a battery reading is just the dot`() {
        val chip = strapChip(true, connected(null), SyncState.Idle, now, now)
        assertEquals("", chip.text)
        assertEquals(StrapChip.Dot.Fine, chip.dot)
    }

    @Test fun `ago reads like a person would say it`() {
        assertEquals("Just now", agoText(20))
        assertEquals("1 min ago", agoText(60))
        assertEquals("59 min ago", agoText(59 * 60 + 59))
        assertEquals("1 h ago", agoText(3600))
        assertEquals("1 day ago", agoText(30 * 3600))
        assertEquals("3 days ago", agoText(3 * 86_400))
        assertEquals("Just now", agoText(-5))
    }

    private val recovery = 1
    private val sleep = 2
    private val activity = 3

    @Test fun `back goes to the tab before, then Today, then leaves`() {
        val nav = TabNav().select(recovery).select(sleep).select(activity)
        val first = nav.back()!!
        assertEquals(sleep, first.current)
        val second = first.back()!!
        assertEquals(TabNav.HOME, second.current)
        assertNull(second.back())
    }

    @Test fun `back from a tab opened from Today goes to Today`() {
        val nav = TabNav().select(sleep)
        assertEquals(TabNav.HOME, nav.back()!!.current)
        assertNull(TabNav().back())
    }

    @Test fun `selecting the tab showing changes nothing`() {
        val nav = TabNav().select(sleep)
        assertEquals(nav, nav.select(sleep))
    }

    @Test fun `tapping the tab showing scrolls, then pops`() {
        val (once, first) = TabNav().select(sleep).reselect(depth = 2)
        assertEquals(Reselect.ScrollToTop, first)
        val (twice, second) = once.reselect(depth = 2)
        assertEquals(Reselect.PopToRoot, second)
        assertFalse(twice.reselected)
        // At the root there's nothing to pop: it scrolls again.
        assertEquals(Reselect.ScrollToTop, twice.reselect(depth = 0).second)
        assertEquals(Reselect.ScrollToTop, once.reselect(depth = 0).second)
    }

    @Test fun `only a swipe moves the tab when the pager settles`() {
        val nav = TabNav().select(sleep)
        // The pager settling on an old page, from an animation cut short or not started yet, leaves the tap's tab.
        assertEquals(nav, nav.settled(TabNav.HOME, swiped = false))
        assertEquals(nav, nav.settled(recovery, swiped = false))
        // A swipe that lands on another tab picks it, and back returns to where the swipe began.
        val swiped = nav.settled(activity, swiped = true)
        assertEquals(activity, swiped.current)
        assertEquals(sleep, swiped.previous)
        // A swipe that springs back changes nothing.
        assertEquals(nav, nav.settled(sleep, swiped = true))
    }

    @Test fun `opening a screen in between makes the next tap scroll first`() {
        val (once, _) = TabNav().reselect(depth = 1)
        assertTrue(once.reselected)
        assertEquals(Reselect.ScrollToTop, once.moved().reselect(depth = 1).second)
        // Moving to another tab starts over too.
        assertFalse(once.select(sleep).reselected)
    }

    @Test fun `open_metric routes name a metric, Recovery or Sleep`() {
        assertEquals(Detail.OfMetric(Metric.Hrv), Detail.fromRoute("Hrv"))
        assertEquals(Detail.Recovery, Detail.fromRoute("Recovery"))
        assertEquals(Detail.Sleep, Detail.fromRoute("Sleep"))
        assertEquals(Detail.OfScore(Score.Sleep), Detail.fromRoute(Detail.SLEEP_SCORE_ROUTE))
        assertNull(Detail.fromRoute("Readiness"))
    }

    @Test fun `stacks with the new screens survive process death`() {
        val stack = listOf(Detail.Recovery, Detail.OfMetric(Metric.Spo2), Detail.Sleep, Detail.Workout(1792227300))
        val saved = with(Detail.StackSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stack) }!!
        assertEquals(stack, Detail.StackSaver.restore(saved))
    }

    @Test fun `Recovery and Charge details survive process death`() {
        // Every route there is, the score details among them, deepest last.
        val stack = listOf(
            Detail.OfScore(Score.Recovery), Detail.OfScore(Score.Energy), Detail.OfMetric(Metric.HeartRate), Detail.AllWorkouts,
            Detail.Healthspan, Detail.Recovery, Detail.Sleep, Detail.Workout(1792227300),
        ) + Metric.entries.map(Detail::OfMetric) + Score.entries.map(Detail::OfScore)
        val saved = with(Detail.StackSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stack) }!!
        assertEquals(stack, Detail.StackSaver.restore(saved))
        // A score this build doesn't know is dropped, the rest kept.
        assertEquals(listOf(Detail.OfScore(Score.Energy)), Detail.StackSaver.restore("c:Sleepscore|c:Charge"))
    }

    @Test fun `stacks saved before Energy and Calories were renamed open the same details`() {
        // Before the rename, Energy (the Charge model) was Score.Charge and Calories was Score.Energy, saved by name.
        assertEquals(listOf(Detail.OfScore(Score.Calories)), Detail.StackSaver.restore("c:Energy"))
        assertEquals(listOf(Detail.OfScore(Score.Energy)), Detail.StackSaver.restore("c:Charge"))
        assertEquals(
            listOf(Detail.Recovery, Detail.OfScore(Score.Energy), Detail.OfScore(Score.Calories)),
            Detail.StackSaver.restore("r:|c:Charge|c:Energy"),
        )
        // They are still saved that way, so a saved "Energy" only ever means Calories, and card motion keys match.
        assertEquals("c:Charge", Detail.keyOf(Detail.OfScore(Score.Energy)))
        assertEquals("c:Energy", Detail.keyOf(Detail.OfScore(Score.Calories)))
        assertEquals(Score.entries.size, Score.entries.map { it.key }.toSet().size)
        Score.entries.filter { it != Score.Energy && it != Score.Calories }.forEach { assertEquals(it.name, it.key) }
    }

    @Test fun `every detail switches within one group of siblings`() {
        val all = Metric.entries.map(Detail::OfMetric) + Score.entries.map(Detail::OfScore)
        all.forEach { d -> assertEquals(d.toString(), 1, DETAIL_GROUPS.count { d in it }) }
        assertEquals(listOf(Detail.OfScore(Score.Recovery), Detail.OfScore(Score.Energy)), siblingsOf(Detail.OfScore(Score.Energy)))
        assertEquals(listOf(Detail.Healthspan), siblingsOf(Detail.Healthspan))
    }

    @Test fun `the sleep details and night-scoped vitals survive process death`() {
        val stack = listOf(Detail.Sleep, Detail.OfScore(Score.Sleep), Detail.OfScore(Score.Regularity), Detail.OfScore(Score.Naps)) +
            NIGHT_VITALS.map { Detail.OfMetric(it, night = true) } + Detail.OfMetric(Metric.Hrv)
        val saved = with(Detail.StackSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stack) }!!
        assertEquals(stack, Detail.StackSaver.restore(saved))
        // The night and the day are different details of one metric.
        assertTrue(saved.contains("n:Hrv") && saved.contains("m:Hrv"))
        assertEquals(listOf(Detail.OfMetric(Metric.Hrv, night = true), Detail.OfMetric(Metric.Hrv)), Detail.StackSaver.restore("n:Hrv|m:Hrv"))
        // Only a night vital can be scoped to the night; a stray one is dropped, the rest kept.
        assertEquals(listOf(Detail.OfScore(Score.Naps)), Detail.StackSaver.restore("n:Steps|c:Naps"))
        // A link by name opens the day's detail, never the night's.
        assertEquals(Detail.OfMetric(Metric.Spo2), Detail.fromRoute("Spo2"))
    }

    @Test fun `the Activity details and the week story survive process death`() {
        val stack = ACTIVITY_GROUP + Detail.Week + Detail.OfScore(Score.Load)
        val saved = with(Detail.StackSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(stack) }!!
        assertEquals(stack, Detail.StackSaver.restore(saved))
        assertTrue(saved.contains("k:") && saved.contains("c:HeartPoints"))
    }

    @Test fun `the Activity details switch among themselves in the tab's order, the week story alone`() {
        assertEquals(
            listOf(
                Detail.OfMetric(Metric.Steps), Detail.OfScore(Score.ActiveMinutes), Detail.OfScore(Score.HeartPoints), Detail.OfScore(Score.Load),
                Detail.OfMetric(Metric.HeartRate), Detail.OfScore(Score.Vo2Max), Detail.OfScore(Score.Recover), Detail.OfScore(Score.Calories),
                Detail.OfScore(Score.Distance),
            ),
            ACTIVITY_GROUP,
        )
        ACTIVITY_GROUP.forEach { assertEquals(ACTIVITY_GROUP, siblingsOf(it)) }
        assertEquals(listOf(Detail.Week), siblingsOf(Detail.Week))
        assertFalse(DETAIL_GROUPS.any { Detail.Week in it })
    }

    @Test fun `the sleep details switch among themselves, the night vitals only within the night`() {
        val sleep = listOf(Score.Sleep, Score.Regularity, Score.Naps).map(Detail::OfScore)
        sleep.forEach { assertEquals(sleep, siblingsOf(it)) }
        val night = NIGHT_VITALS.map { Detail.OfMetric(it, night = true) }
        assertEquals(listOf(Metric.HeartRate, Metric.Hrv, Metric.Breathing, Metric.Spo2, Metric.SkinTemperature), NIGHT_VITALS)
        night.forEach { d ->
            assertEquals(night, siblingsOf(d))
            assertTrue(siblingsOf(d).all { it is Detail.OfMetric && it.night })
        }
        // The same metric by day stays with the day's group.
        assertFalse(siblingsOf(Detail.OfMetric(Metric.Hrv)).any { it is Detail.OfMetric && it.night })
        // Every row of the Sleep tab's night vitals opens a night detail in that group.
        nightVitals(DayUiState()).forEach { v -> assertTrue(v.label, Detail.OfMetric(v.metric, night = true) in night) }
        (night + sleep).forEach { d -> assertEquals(d.toString(), 1, DETAIL_GROUPS.count { d in it }) }
    }

    @Test fun `a stack saved with All metrics on it comes back without it`() {
        // All metrics is a sheet now, not a screen on a stack; a stack saved before keeps the rest.
        assertEquals(listOf(Detail.OfMetric(Metric.Hrv)), Detail.StackSaver.restore("x:|m:Hrv"))
    }

    @Test fun `today's title is the moment, a past day keeps its name`() {
        val titles = org.openstrap.app.data.Moment.entries.associateWith { todayTitle(it, today, today = today, locale = uk) }
        assertEquals(
            mapOf(
                org.openstrap.app.data.Moment.During to "Workout", org.openstrap.app.data.Moment.After to "After your workout",
                org.openstrap.app.data.Moment.WorkoutWindow to "Workout time", org.openstrap.app.data.Moment.Waking to "Waking up",
                org.openstrap.app.data.Moment.Night to "Night", org.openstrap.app.data.Moment.Evening to "Evening",
                org.openstrap.app.data.Moment.Midday to "Midday",
            ),
            titles,
        )
        assertEquals("After your run", todayTitle(org.openstrap.app.data.Moment.After, today, 1, today, uk))
        // During a workout the header is the sport under way (spec 4.2).
        assertEquals("Running", todayTitle(org.openstrap.app.data.Moment.During, today, 1, today, uk))
        assertEquals("Cycling", todayTitle(org.openstrap.app.data.Moment.During, today, 4, today, uk))
        assertEquals(org.openstrap.app.data.Workouts.sportName(52), todayTitle(org.openstrap.app.data.Moment.During, today, 52, today, uk))
        assertEquals("After your ride", todayTitle(org.openstrap.app.data.Moment.After, today, 4, today, uk))
        assertEquals("After your workout", todayTitle(org.openstrap.app.data.Moment.After, today, 52, today, uk))
        assertEquals("Yesterday", todayTitle(org.openstrap.app.data.Moment.Waking, today.minusDays(1), today = today, locale = uk))
        assertEquals("Wednesday", todayTitle(org.openstrap.app.data.Moment.Waking, today.minusDays(2), today = today, locale = uk))
    }

    @Test fun `evening dims, night is almost unlit, the rest is day`() {
        for (m in org.openstrap.app.data.Moment.entries) {
            val want = when (m) {
                org.openstrap.app.data.Moment.Evening -> org.openstrap.app.ui.glow.draw.GlowMood.Evening
                org.openstrap.app.data.Moment.Night -> org.openstrap.app.ui.glow.draw.GlowMood.Night
                else -> org.openstrap.app.ui.glow.draw.GlowMood.Day
            }
            assertEquals(m.name, want, momentMood(m))
        }
    }

    @Test fun `the hero's light follows the moment`() {
        assertEquals(225f, heroLightAngle(org.openstrap.app.data.Moment.Waking))
        // Midday's Charge rises from below to its level; during a workout the zone's light is from below too.
        assertEquals(270f, heroLightAngle(org.openstrap.app.data.Moment.Midday))
        assertEquals(45f, heroLightAngle(org.openstrap.app.data.Moment.WorkoutWindow))
        assertEquals(270f, heroLightAngle(org.openstrap.app.data.Moment.After))
        assertEquals(315f, heroLightAngle(org.openstrap.app.data.Moment.Evening))
        assertEquals(270f, heroLightAngle(org.openstrap.app.data.Moment.Night))
        assertEquals(270f, heroLightAngle(org.openstrap.app.data.Moment.During))
    }
}
