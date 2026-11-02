package org.openstrap.app.ui.root

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.NavRequest
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.app.ui.dashboard.Reselect
import org.openstrap.app.ui.dashboard.Score
import org.openstrap.app.ui.dashboard.TabNav
import java.time.LocalDate

/** Where the app is, and what back, the tabs and the requests do to it (audit A1 and A7). */
class AppNavStateTest {
    private val day = LocalDate.of(2026, 10, 23)

    /**
     * Back as the screens' own handlers answer it before the split: each enabled handler in composition order, the
     * last one composed wins. The root handler, then the tab's top detail, the pages, the goals and All metrics
     * sheets, adding a workout, picking a stretch's sport, Choose, Ready and Your zones over it.
     */
    private fun handlersBack(s: AppNavState.Snapshot, firmwareInstalling: Boolean, recording: Boolean): Pair<AppNavState.Snapshot, BackResult> {
        val nav = AppNavState(s)
        val current = nav.current
        val ready = s.workout as? WorkoutOverlay.Ready
        val closed: (() -> Unit) -> Pair<AppNavState.Snapshot, BackResult> = { close -> close(); nav.snapshot() to BackResult.Handled }
        val handlers = listOf<Pair<Boolean, () -> Pair<AppNavState.Snapshot, BackResult>>>(
            true to { s.tab.back()?.let { s.copy(tab = it) to BackResult.Handled } ?: (s to BackResult.AtHome) },
            nav.stack(current).isNotEmpty() to { closed { nav.pop(current) } },
            s.pages.isNotEmpty() to { closed { if (!(s.pages.last() == Page.Firmware && firmwareInstalling)) nav.closePage() } },
            s.editGoals to { closed { nav.setEditGoals(false) } },
            s.allMetricsOpen to { closed { nav.setAllMetrics(false) } },
            (s.addingOn != null) to { closed { nav.setAddingOn(null) } },
            (s.savingStretch != null) to { closed { nav.setSavingStretch(null) } },
            (!recording && s.workout == WorkoutOverlay.Choose) to { closed(nav::closeChoose) },
            (!recording && ready != null) to { closed(nav::closeReady) },
            (!recording && ready?.zones == true) to { closed(nav::closeZones) },
        )
        return handlers.last { it.first }.second()
    }

    private val overlays = listOf(
        WorkoutOverlay.None,
        WorkoutOverlay.Choose,
        WorkoutOverlay.Ready(3),
        WorkoutOverlay.Ready(3, overChoose = true),
        WorkoutOverlay.Ready(3, picking = true, structure = "i:4x60", overChoose = true, zones = true),
    )
    private val pageSets = listOf(emptyList(), listOf(Page.You), listOf(Page.Strap, Page.Firmware), listOf(Page.You, Page.Updates))
    private val stacks = listOf(emptyList(), listOf(Detail.Recovery), listOf(Detail.OfMetric(Metric.Hrv), Detail.OfScore(Score.Sleep)))

    @Test
    fun `back gives the same answer as the handlers from every configuration`() {
        var checked = 0
        for (current in Tab.entries) for (previous in listOf(null, Tab.Sleep.ordinal)) for (pages in pageSets)
            for (installing in listOf(false, true)) for (stack in stacks) for (overlay in overlays) for (recording in listOf(false, true))
                for (flags in 0 until 16) {
                    val s = AppNavState.Snapshot(
                        tab = TabNav(current.ordinal, previous),
                        pages = pages,
                        stacks = Tab.entries.associateWith { if (it == current) stack else emptyList() },
                        allMetricsOpen = flags and 1 != 0,
                        editGoals = flags and 2 != 0,
                        addingOn = day.takeIf { flags and 4 != 0 },
                        savingStretch = (100L..200L).takeIf { flags and 8 != 0 },
                        workout = overlay,
                    )
                    val (expected, expectedResult) = handlersBack(s, installing, recording)
                    val nav = AppNavState(s)
                    val result = nav.back(installing, recording)
                    assertEquals("result from $s", expectedResult, result)
                    assertEquals("state from $s", expected, nav.snapshot())
                    checked++
                }
        assertEquals(30_720, checked)
    }

    @Test
    fun `back from a tab goes to the tab before, then Today, and Today's root is home`() {
        val nav = AppNavState(AppNavState.Snapshot(tab = TabNav(Tab.Activity.ordinal, Tab.Sleep.ordinal)))
        assertEquals(BackResult.Handled, nav.back(false))
        assertEquals(Tab.Sleep, nav.current)
        assertEquals(BackResult.Handled, nav.back(false))
        assertEquals(Tab.Today, nav.current)
        assertEquals(BackResult.AtHome, nav.back(false))
    }

    @Test
    fun `Ready closes back to Choose when Choose was under it, and Your zones closes back to Ready`() {
        val nav = AppNavState()
        nav.openChoose()
        nav.openReady(5)
        nav.openZones()
        assertEquals(WorkoutOverlay.Ready(5, overChoose = true, zones = true), nav.workout)
        nav.back(false)
        assertEquals(WorkoutOverlay.Ready(5, overChoose = true), nav.workout)
        nav.back(false)
        assertEquals(WorkoutOverlay.Choose, nav.workout)
        nav.back(false)
        assertEquals(WorkoutOverlay.None, nav.workout)
    }

    @Test
    fun `Choose asked for while Ready shows stays under it`() {
        val nav = AppNavState()
        nav.openReady(5)
        nav.openChoose()
        assertEquals(WorkoutOverlay.Ready(5, overChoose = true), nav.workout)
        nav.closeReady()
        assertEquals(WorkoutOverlay.Choose, nav.workout)
    }

    @Test
    fun `Ready's own sport list changes only the sport`() {
        val nav = AppNavState()
        nav.openReady(5, picking = true, structure = "s")
        nav.readySport(7)
        assertEquals(WorkoutOverlay.Ready(7, picking = true, structure = "s"), nav.workout)
    }

    @Test
    fun `a starting workout closes Choose and Ready`() {
        val nav = AppNavState()
        nav.openChoose()
        nav.openReady(5)
        nav.workoutStarted()
        assertEquals(WorkoutOverlay.None, nav.workout)
    }

    @Test
    fun `an update installing keeps its pages open`() {
        val nav = AppNavState(AppNavState.Snapshot(pages = listOf(Page.Strap, Page.Firmware)))
        assertFalse(nav.closePages(firmwareInstalling = true))
        nav.openBeside(Page.Alarms, firmwareInstalling = true)
        assertEquals(listOf(Page.Strap, Page.Firmware), nav.pages)
        assertEquals(BackResult.Handled, nav.back(firmwareInstalling = true))
        assertEquals(listOf(Page.Strap, Page.Firmware), nav.pages)
        assertTrue(nav.closePages(firmwareInstalling = false))
        assertEquals(emptyList<Page>(), nav.pages)
    }

    @Test
    fun `a tap on the tab showing scrolls, then pops to the root`() {
        val nav = AppNavState(AppNavState.Snapshot(stacks = mapOf(Tab.Today to listOf(Detail.Recovery))))
        assertEquals(Reselect.ScrollToTop, nav.reselect(Tab.Today))
        assertEquals(Reselect.PopToRoot, nav.reselect(Tab.Today))
        assertEquals(emptyList<Detail>(), nav.stack(Tab.Today))
    }

    @Test
    fun `a detail already on top isn't opened twice, and a switcher pick takes its place`() {
        val nav = AppNavState()
        nav.push(Tab.Sleep, Detail.Sleep)
        nav.push(Tab.Sleep, Detail.Sleep)
        assertEquals(listOf<Detail>(Detail.Sleep), nav.stack(Tab.Sleep))
        nav.replaceTop(Tab.Sleep, Detail.OfMetric(Metric.Hrv))
        assertEquals(listOf<Detail>(Detail.OfMetric(Metric.Hrv)), nav.stack(Tab.Sleep))
    }

    @Test
    fun `a request for Ready or Choose waits while a workout records`() {
        val nav = AppNavState()
        nav.apply(NavRequest.OpenReady(4), recording = true, firmwareInstalling = false)
        nav.apply(NavRequest.OpenChoose, recording = true, firmwareInstalling = false)
        assertEquals(WorkoutOverlay.None, nav.workout)
        nav.apply(NavRequest.OpenChoose, recording = false, firmwareInstalling = false)
        nav.apply(NavRequest.OpenReady(4), recording = false, firmwareInstalling = false)
        assertEquals(WorkoutOverlay.Ready(4, overChoose = true), nav.workout)
    }

    @Test
    fun `the Moment widget opens Today at its root over whatever showed`() {
        val nav = AppNavState(
            AppNavState.Snapshot(
                tab = TabNav(Tab.Sleep.ordinal, 0), pages = listOf(Page.You), allMetricsOpen = true,
                stacks = mapOf(Tab.Today to listOf(Detail.Week)), workout = WorkoutOverlay.Choose,
            ),
        )
        nav.apply(NavRequest.OpenToday, recording = false, firmwareInstalling = false)
        assertEquals(Tab.Today, nav.current)
        assertEquals(emptyList<Page>(), nav.pages)
        assertEquals(emptyList<Detail>(), nav.stack(Tab.Today))
        assertFalse(nav.allMetricsOpen)
        assertEquals(WorkoutOverlay.None, nav.workout)
    }

    @Test
    fun `a notification opens its workout on the Activity tab and its metric on the tab showing`() {
        val nav = AppNavState(AppNavState.Snapshot(tab = TabNav(Tab.Recovery.ordinal, 0), pages = listOf(Page.Strap)))
        nav.apply(NavRequest.OpenDetail("Hrv", day), recording = false, firmwareInstalling = false)
        assertEquals(listOf<Detail>(Detail.OfMetric(Metric.Hrv)), nav.stack(Tab.Recovery))
        assertEquals(emptyList<Page>(), nav.pages)
        nav.apply(NavRequest.OpenDetail("nothing", day), recording = false, firmwareInstalling = false)
        assertEquals(listOf<Detail>(Detail.OfMetric(Metric.Hrv)), nav.stack(Tab.Recovery))
        nav.apply(NavRequest.OpenWorkout(42), recording = false, firmwareInstalling = false)
        assertEquals(Tab.Activity, nav.current)
        assertEquals(listOf<Detail>(Detail.Workout(42)), nav.stack(Tab.Activity))
    }

    @Test
    fun `the strap, recap and updates requests open their pages unless an update installs`() {
        val nav = AppNavState(AppNavState.Snapshot(pages = listOf(Page.Strap, Page.Firmware)))
        nav.apply(NavRequest.OpenRecap, recording = false, firmwareInstalling = true)
        assertEquals(listOf(Page.Strap, Page.Firmware), nav.pages)
        nav.apply(NavRequest.OpenRecap, recording = false, firmwareInstalling = false)
        assertEquals(listOf(Page.You, Page.GoalsAndBests, Page.WeeklyRecap), nav.pages)
        nav.apply(NavRequest.OpenUpdates, recording = false, firmwareInstalling = false)
        assertEquals(listOf(Page.You, Page.Updates), nav.pages)
        nav.apply(NavRequest.OpenStrap, recording = false, firmwareInstalling = false)
        assertEquals(listOf(Page.Strap), nav.pages)
    }

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun `the place survives process death`() {
        val s = AppNavState.Snapshot(
            tab = TabNav(Tab.Sleep.ordinal, Tab.Activity.ordinal),
            pages = listOf(Page.Strap, Page.Alarms),
            stacks = Tab.entries.associateWith { if (it == Tab.Sleep) listOf(Detail.OfMetric(Metric.Hrv, night = true), Detail.Workout(9)) else emptyList() },
            allMetricsOpen = true,
            editGoals = true,
            addingOn = day,
            savingStretch = 100L..900L,
            workout = WorkoutOverlay.Ready(12, picking = true, structure = "i:4x60", overChoose = true, zones = true),
        )
        val saved = with(AppNavState.Saver) { scope.save(AppNavState(s)) }!!
        assertEquals(s, AppNavState.Saver.restore(saved)!!.snapshot())
        for (w in overlays) {
            val again = with(AppNavState.Saver) { scope.save(AppNavState(AppNavState.Snapshot(workout = w))) }!!
            assertEquals(w, AppNavState.Saver.restore(again)!!.workout)
        }
    }

    @Test
    fun `nothing saved restores as the start`() {
        val empty = with(AppNavState.Saver) { scope.save(AppNavState()) }!!
        assertEquals(AppNavState().snapshot(), AppNavState.Saver.restore(empty)!!.snapshot())
    }
}
