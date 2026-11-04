package org.openstrap.app.ui.root

import androidx.annotation.StringRes
import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import java.time.LocalDate
import org.openstrap.app.NavRequest
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.dashboard.Reselect
import org.openstrap.app.ui.dashboard.TabNav
import org.openstrap.app.ui.dashboard.openBeside

/** The four tabs, in the bar's order. Their icons and colours are in [tabItems]. */
enum class Tab(@StringRes private val labelRes: Int) {
    Today(R.string.day_tab_today),
    Recovery(R.string.day_tab_recovery),
    Sleep(R.string.day_tab_sleep),
    Activity(R.string.day_tab_activity),
    ;

    /** The tab's name in the bar, in the app's language. */
    val label: String get() = Strings.app.get(labelRes)
}

/**
 * The pages that open over whichever tab is showing and close back to it (spec 3.3): the You page and what it opens,
 * and the Strap page and what it opens. Shallowest first.
 */
enum class Page(
    /** The row on the You or Strap page that opens this page, marked while it's open beside the list on a wide screen. */
    val row: String? = null,
) {
    You,
    Profile("Profile"),
    HeartZones("Heart rate zones"),
    HealthConnect("Health Connect"),
    Backup("Backup"),
    DeletedSleep("Deleted sleep"),
    Updates("Updates"),
    Diagnostics("Diagnostics"),
    GoalsAndBests("Goals and bests"),
    AllBadges,
    WeeklyRecap,
    Strap,
    Alarms("Alarms"),
    PhoneAlerts("Phone alerts"),
    HealthAlerts("Health alerts"),
    Firmware("Firmware"),
    ZeppAccount("Zepp account"),
}

/** Keeps the open pages across process death, as their names ("Strap,Alarms"). */
val PagesSaver: Saver<List<Page>, String> = Saver(
    save = { pages -> pages.joinToString(",") { it.name } },
    restore = { s -> s.split(',').mapNotNull { n -> Page.entries.firstOrNull { it.name == n } } },
)

/** The workout screens over the app before one starts: Choose, and Ready over it or on its own. */
sealed interface WorkoutOverlay {
    data object None : WorkoutOverlay

    /** Choose, the first screen of a workout (workouts spec 2.1). */
    data object Choose : WorkoutOverlay

    /**
     * The ready screen for [sport], on its sport list when [picking] ("All sports"), with the rounds of a saved workout
     * picked on Choose ([structure], null for last time's). [overChoose]: Choose is under it, so closing comes back
     * there. [zones]: Your zones is open over it.
     */
    data class Ready(
        val sport: Int,
        val picking: Boolean = false,
        val structure: String? = null,
        val overChoose: Boolean = false,
        val zones: Boolean = false,
    ) : WorkoutOverlay
}

/** What [AppNavState.back] did. */
sealed interface BackResult {
    /** Something closed or the tab moved. */
    data object Handled : BackResult

    /** Today at its root: the caller returns to today's date, or lets the activity finish. */
    data object AtHome : BackResult
}

/**
 * Where the app is: the tab, the pages over it, each tab's details, the sheets and the workout screens before a
 * workout. Plain state with no Android in it, so the rules for opening, closing and back are unit tested. Each field
 * is its own state, so a change to one tab's stack recomposes only what reads that stack.
 */
@Stable
@Suppress("TooManyFunctions") // one function per way the app moves, so each screen's lambda names what it does
class AppNavState(initial: Snapshot = Snapshot()) {
    /** Everything [AppNavState] holds, as plain values: for the saver and the tests. */
    data class Snapshot(
        val tab: TabNav = TabNav(),
        val pages: List<Page> = emptyList(),
        val stacks: Map<Tab, List<Detail>> = emptyMap(),
        val allMetricsOpen: Boolean = false,
        val editGoals: Boolean = false,
        val addingOn: LocalDate? = null,
        val savingStretch: LongRange? = null,
        val workout: WorkoutOverlay = WorkoutOverlay.None,
    )

    /** Which tab shows and the one before it, for back. */
    var tab: TabNav by mutableStateOf(initial.tab)
        private set

    /** The pages over the tab, deepest last. */
    var pages: List<Page> by mutableStateOf(initial.pages)
        private set

    private val stackStates = Tab.entries.associateWith { mutableStateOf(initial.stacks[it].orEmpty()) }

    private val allMetricsState = mutableStateOf(initial.allMetricsOpen)
    private val editGoalsState = mutableStateOf(initial.editGoals)
    private val addingOnState = mutableStateOf(initial.addingOn)
    private val savingState = mutableStateOf(initial.savingStretch)

    /** The All metrics sheet, from the grid button in the header: over the tab, never a place. */
    val allMetricsOpen: Boolean get() = allMetricsState.value

    /** The goals sheet, from the You page. */
    val editGoals: Boolean get() = editGoalsState.value

    /** The day a workout is being added to after the fact. */
    val addingOn: LocalDate? get() = addingOnState.value

    /** A stretch the strap noticed, by its start and end, being saved as a workout while its sport is chosen. */
    val savingStretch: LongRange? get() = savingState.value

    /** Choose and Ready, before a workout starts. */
    var workout: WorkoutOverlay by mutableStateOf(initial.workout)
        private set

    /** The tab showing. */
    val current: Tab get() = Tab.entries[tab.current]

    /** Every tab's stack of details, deepest last. */
    val stacks: Map<Tab, List<Detail>> get() = Tab.entries.associateWith(::stack)

    /** [tab]'s stack of details, deepest last. Back pops one. Opening a detail never moves you to another tab. */
    fun stack(tab: Tab): List<Detail> = stackStates.getValue(tab).value

    /** Choose is open, on its own or under Ready. */
    /** Today at its root with nothing over it: where the Today hero shows the workout. */
    val atTodayRoot: Boolean get() = tab.current == Tab.Today.ordinal && stack(Tab.Today).isEmpty() && pages.isEmpty()

    val choosing: Boolean get() = workout.let { it == WorkoutOverlay.Choose || (it as? WorkoutOverlay.Ready)?.overChoose == true }

    /** Another tab: it becomes current, and back returns here. Every tab keeps its own stack. */
    fun select(tab: Tab) {
        this.tab = this.tab.select(tab.ordinal)
    }

    /** The pager came to rest on [page]: only a swipe the user made moves the tab ([TabNav.settled]). */
    fun settled(page: Int, swiped: Boolean) {
        tab = tab.settled(page, swiped)
    }

    /** A tap on the tab showing: the first scrolls to the top, the next pops to the root, which happens here. */
    fun reselect(tab: Tab): Reselect {
        val (next, action) = this.tab.reselect(stack(tab).size)
        this.tab = next
        if (action == Reselect.PopToRoot) setStack(tab, emptyList())
        return action
    }

    /** A screen opened or closed on the tab: the next tap on it scrolls before it pops. */
    fun moved() {
        tab = tab.moved()
    }

    /** Opens [page] over the tab, unless it's already on top. */
    fun openPage(page: Page) {
        if (pages.lastOrNull() != page) pages = pages + page
    }

    /** Closes the top page. */
    fun closePage() {
        pages = pages.dropLast(1)
    }

    /** Closes the pages over the tab, unless an update is being installed there. True if any closed. */
    fun closePages(firmwareInstalling: Boolean): Boolean {
        if (pages.isEmpty() || firmwareInstalling) return false
        pages = emptyList()
        return true
    }

    /** A row on a wide screen's list: its page beside the list, in place of the one there, unless an update installs. */
    fun openBeside(page: Page, firmwareInstalling: Boolean) {
        if (!firmwareInstalling) pages = openBeside(pages, page)
    }

    /** Opens [detail] on [tab]'s stack, unless it's already on top. */
    fun push(tab: Tab, detail: Detail) {
        val stack = stack(tab)
        if (stack.lastOrNull() != detail) setStack(tab, stack + detail)
    }

    /** Closes [tab]'s top detail. */
    fun pop(tab: Tab) {
        setStack(tab, stack(tab).dropLast(1))
    }

    /** A switcher pick: [detail] takes the top detail's place. */
    fun replaceTop(tab: Tab, detail: Detail) {
        setStack(tab, stack(tab).dropLast(1) + detail)
    }

    fun setStack(tab: Tab, stack: List<Detail>) {
        stackStates.getValue(tab).value = stack
    }

    /** Ready for [sport], over Choose when it's open. Your zones stays as it was. */
    fun openReady(sport: Int, picking: Boolean = false, structure: String? = null) {
        val zones = (workout as? WorkoutOverlay.Ready)?.zones == true
        workout = WorkoutOverlay.Ready(sport, picking, structure, overChoose = choosing, zones = zones)
    }

    /** Ready's own sport list picked another sport: only the sport changes. */
    fun readySport(sport: Int) {
        workout = (workout as? WorkoutOverlay.Ready)?.copy(sport = sport) ?: WorkoutOverlay.Ready(sport, overChoose = choosing)
    }

    /** Choose with every sport. Ready stays on top of it when it's open. */
    fun openChoose() {
        workout = when (val w = workout) {
            is WorkoutOverlay.Ready -> w.copy(overChoose = true)
            else -> WorkoutOverlay.Choose
        }
    }

    /** Closes Ready, back to Choose when it was under it. */
    fun closeReady() {
        workout = if (choosing) WorkoutOverlay.Choose else WorkoutOverlay.None
    }

    /** Closes Choose. */
    fun closeChoose() {
        workout = when (val w = workout) {
            is WorkoutOverlay.Ready -> w.copy(overChoose = false)
            else -> WorkoutOverlay.None
        }
    }

    /** A workout started: Ready and Choose close. */
    fun workoutStarted() {
        workout = WorkoutOverlay.None
    }

    /** Your zones, over Ready. */
    fun openZones() {
        (workout as? WorkoutOverlay.Ready)?.let { workout = it.copy(zones = true) }
    }

    fun closeZones() {
        (workout as? WorkoutOverlay.Ready)?.let { workout = it.copy(zones = false) }
    }

    fun setAllMetrics(open: Boolean) {
        allMetricsState.value = open
    }

    fun setEditGoals(open: Boolean) {
        editGoalsState.value = open
    }

    fun setAddingOn(date: LocalDate?) {
        addingOnState.value = date
    }

    fun setSavingStretch(stretch: LongRange?) {
        savingState.value = stretch
    }

    /**
     * Where [request] takes the app. [recording]: a workout records, so Choose and Ready stay as they are. The day a
     * request names, and the live screen [NavRequest.ShowWorkout] brings back, are the caller's.
     */
    fun apply(request: NavRequest, recording: Boolean, firmwareInstalling: Boolean) {
        when (request) {
            is NavRequest.OpenReady -> if (!recording) openReady(request.sport, picking = false, structure = keptStructure())
            NavRequest.OpenChoose -> if (!recording) openChoose()
            is NavRequest.OpenWorkout -> {
                closePages(firmwareInstalling)
                setStack(Tab.Activity, listOf(Detail.Workout(request.start)))
                select(Tab.Activity)
            }
            is NavRequest.OpenDetail -> Detail.fromRoute(request.route)?.let { detail ->
                closePages(firmwareInstalling)
                setAllMetrics(false)
                push(current, detail)
            }
            NavRequest.OpenToday -> {
                closePages(firmwareInstalling)
                setAllMetrics(false)
                if (!recording) workout = WorkoutOverlay.None
                setStack(Tab.Today, emptyList())
                select(Tab.Today)
            }
            NavRequest.OpenStrap -> over(Page.Strap, listOf(Page.Strap), firmwareInstalling)
            NavRequest.OpenRecap -> over(Page.WeeklyRecap, listOf(Page.You, Page.GoalsAndBests, Page.WeeklyRecap), firmwareInstalling)
            NavRequest.OpenUpdates -> over(Page.Updates, listOf(Page.You, Page.Updates), firmwareInstalling)
            NavRequest.ShowWorkout -> Unit
        }
    }

    /** A request for Ready while it's open keeps the rounds it shows, as the sport's own tap would. */
    private fun keptStructure(): String? = (workout as? WorkoutOverlay.Ready)?.structure

    /** [top] over the tab, as [stack], in place of the pages there: unless it's on top already or an update installs. */
    private fun over(top: Page, stack: List<Page>, firmwareInstalling: Boolean) {
        setAllMetrics(false)
        if (pages.lastOrNull() != top && (pages.isEmpty() || closePages(firmwareInstalling))) pages = stack
    }

    /**
     * What back does, highest first, as the screens' own back handlers do it: Your zones, Ready, then Choose (not while
     * a workout records, which hides them), the sport being picked for a stretch, the day a workout is being added to,
     * the All metrics and goals sheets, the top page (Firmware while an update installs keeps it), the tab's top
     * detail, then the tab before. Today at its root is [BackResult.AtHome].
     */
    fun back(firmwareInstalling: Boolean, recording: Boolean = false): BackResult {
        val w = workout
        when {
            !recording && w is WorkoutOverlay.Ready -> if (w.zones) closeZones() else closeReady()
            !recording && w == WorkoutOverlay.Choose -> closeChoose()
            savingStretch != null -> setSavingStretch(null)
            addingOn != null -> setAddingOn(null)
            allMetricsOpen -> setAllMetrics(false)
            editGoals -> setEditGoals(false)
            pages.isNotEmpty() -> if (!(pages.last() == Page.Firmware && firmwareInstalling)) closePage()
            stack(current).isNotEmpty() -> pop(current)
            else -> {
                tab = tab.back() ?: return BackResult.AtHome
            }
        }
        return BackResult.Handled
    }

    fun snapshot(): Snapshot = Snapshot(tab, pages, stacks, allMetricsOpen, editGoals, addingOn, savingStretch, workout)

    companion object {
        private const val TAB = "tab"
        private const val PAGES = "pages"
        private const val STACK = "stack."
        private const val ALL_METRICS = "allMetrics"
        private const val EDIT_GOALS = "editGoals"
        private const val ADDING_ON = "addingOn"
        private const val SAVING = "saving"
        private const val CHOOSE = "choose"
        private const val READY_SPORT = "readySport"
        private const val READY_PICKING = "readyPicking"
        private const val ZONES = "zones"
        private const val READY_STRUCTURE = "readyStructure"

        /**
         * Keeps the place across process death, with the tab, page and stack encoders that were there before, so what
         * survived then survives now. Anything unreadable restores as the default.
         */
        val Saver: Saver<AppNavState, Any> = mapSaver(
            save = { nav -> save(nav.snapshot()) },
            restore = { map -> AppNavState(restore(map)) },
        )

        private fun SaverScope.save(s: Snapshot): Map<String, Any?> = buildMap {
            put(TAB, with(TabNav.Saver) { save(s.tab) })
            put(PAGES, with(PagesSaver) { save(s.pages) })
            s.stacks.forEach { (tab, stack) -> put(STACK + tab.name, with(Detail.StackSaver) { save(stack) }) }
            put(ALL_METRICS, s.allMetricsOpen)
            put(EDIT_GOALS, s.editGoals)
            s.addingOn?.let { put(ADDING_ON, it.toEpochDay()) }
            s.savingStretch?.let { put(SAVING, longArrayOf(it.first, it.last)) }
            val w = s.workout
            put(CHOOSE, w == WorkoutOverlay.Choose || (w as? WorkoutOverlay.Ready)?.overChoose == true)
            if (w is WorkoutOverlay.Ready) {
                put(READY_SPORT, w.sport)
                put(READY_PICKING, w.picking)
                put(ZONES, w.zones)
                w.structure?.let { put(READY_STRUCTURE, it) }
            }
        }

        private fun restore(map: Map<String, Any?>): Snapshot {
            val choose = map[CHOOSE] as? Boolean == true
            val workout = (map[READY_SPORT] as? Int)?.let { sport ->
                WorkoutOverlay.Ready(
                    sport, map[READY_PICKING] as? Boolean == true, map[READY_STRUCTURE] as? String, choose, map[ZONES] as? Boolean == true,
                )
            } ?: if (choose) WorkoutOverlay.Choose else WorkoutOverlay.None
            return Snapshot(
                tab = (map[TAB] as? IntArray)?.let(TabNav.Saver::restore) ?: TabNav(),
                pages = (map[PAGES] as? String)?.let(PagesSaver::restore).orEmpty(),
                stacks = Tab.entries.associateWith { t -> (map[STACK + t.name] as? String)?.let(Detail.StackSaver::restore).orEmpty() },
                allMetricsOpen = map[ALL_METRICS] as? Boolean == true,
                editGoals = map[EDIT_GOALS] as? Boolean == true,
                addingOn = (map[ADDING_ON] as? Long)?.let(LocalDate::ofEpochDay),
                savingStretch = (map[SAVING] as? LongArray)?.takeIf { it.size == 2 }?.let { it[0]..it[1] },
                workout = workout,
            )
        }
    }
}
