package org.openstrap.app

import android.Manifest
import org.openstrap.app.ui.firmware.FirmwareScreen
import org.openstrap.app.ui.firmware.FirmwareViewModel
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.StrapService
import org.openstrap.app.ui.dashboard.DayPicker
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.dashboard.Detail
import org.openstrap.app.ui.components.FloatingNavBar
import dev.chrisbanes.haze.hazeSource
import org.openstrap.app.ui.components.NavItem
import org.openstrap.app.ui.device.DeviceScreen
import org.openstrap.app.ui.device.DeviceViewModel
import org.openstrap.app.ui.dashboard.MomentHeader
import org.openstrap.app.ui.dashboard.Reselect
import org.openstrap.app.ui.dashboard.TabNav
import org.openstrap.app.ui.dashboard.todayTitle
import org.openstrap.app.ui.dashboard.RecoveryFailure
import org.openstrap.app.ui.dashboard.StrapPreview
import org.openstrap.app.ui.dashboard.DataPreview
import org.openstrap.app.ui.dashboard.FirstScore
import org.openstrap.app.ui.dashboard.PAIRED_HOLD_MS
import org.openstrap.app.ui.dashboard.PairStep
import org.openstrap.app.ui.dashboard.earliestScore
import org.openstrap.app.ui.dashboard.firstScore
import org.openstrap.app.ui.dashboard.pairStep
import org.openstrap.app.ui.dashboard.wear
import org.openstrap.app.data.Recovery
import java.time.LocalDate
import org.openstrap.app.ui.dashboard.StrapView
import org.openstrap.app.ui.dashboard.previewed
import org.openstrap.app.ui.dashboard.strapChip
import org.openstrap.app.ui.dashboard.syncStopped
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.tween
import org.openstrap.app.ui.components.Motion
import org.openstrap.app.ui.components.ListDetail
import org.openstrap.app.ui.components.SideBySideWidth
import androidx.compose.foundation.pager.HorizontalPager
import org.openstrap.app.ui.components.sinkBehind
import androidx.compose.foundation.pager.rememberPagerState
import org.openstrap.app.ui.theme.OpenStrapTheme
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.glow.GlowWorkoutSummaryScreen

class MainActivity : ComponentActivity() {
    /** The latest `open_metric` link, until the app root has opened it. */
    private val metricRequests = kotlinx.coroutines.flow.MutableStateFlow<MetricRequest?>(null)

    /** The Moment widget's tap: Today, at its root, until the app root has opened it. */
    private val todayRequests = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** A widget's strap status line (not paired, battery low): the Strap page, until the app root has opened it. */
    private val strapRequests = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Debug: the moment forced by the `moment` extra, null to follow the clock. */
    private val forcedMoment = kotlinx.coroutines.flow.MutableStateFlow<org.openstrap.app.data.Moment?>(null)

    /** Debug: the strap state previewed by the `strap_state` extra, null for the real one. */
    private val strapPreview = kotlinx.coroutines.flow.MutableStateFlow<StrapPreview?>(null)

    /** Debug: the data state previewed by the `data_state` extra, null for the real one. */
    private val dataPreview = kotlinx.coroutines.flow.MutableStateFlow<DataPreview?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark bars (light icons) whatever the system mode, to match the always-dark theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as OpenStrapApp).container
        setContent { OpenStrapTheme { AppRoot(container, metricRequests, todayRequests, strapRequests, forcedMoment, strapPreview, dataPreview) } }
    }

    override fun onStart() {
        super.onStart()
        (application as OpenStrapApp).container.strap.setAppVisible(true)
    }

    override fun onStop() {
        (application as OpenStrapApp).container.strap.setAppVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Reconnect to a paired strap whenever the app comes to the front (sync follows on connect).
        val granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) (application as OpenStrapApp).container.strap.autoConnect()
        // The association or battery setting may have changed in system settings meanwhile.
        (application as OpenStrapApp).container.companion.refresh()
        handleDebugIntent()
        showWorkoutFromIntent()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent()
        showWorkoutFromIntent()
    }

    /**
     * The workout notification opens the live screen, even if it was tucked away; a shortcut or the tile, the ready
     * screen; an auto-detected workout's notification, that workout; a health alert or body-signal notification, its
     * metric's screen for its day.
     */
    private fun showWorkoutFromIntent() {
        // The Moment widget: Today as it is now, never a forced moment.
        if (intent.getStringExtra(EXTRA_MOMENT) == MOMENT_NOW) {
            intent.removeExtra(EXTRA_MOMENT)
            todayRequests.value = true
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_STRAP, false)) {
            intent.removeExtra(EXTRA_OPEN_STRAP)
            strapRequests.value = true
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)) {
            intent.removeExtra(EXTRA_OPEN_UPDATES)
            (application as OpenStrapApp).container.updater.openRequest.value = true
        }
        intent.getStringExtra(EXTRA_OPEN_METRIC)?.let { route ->
            val day = intent.getStringExtra(EXTRA_METRIC_DAY)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            intent.removeExtra(EXTRA_OPEN_METRIC)
            intent.removeExtra(EXTRA_METRIC_DAY)
            metricRequests.value = MetricRequest(route, day)
        }
        val open = intent.getLongExtra(EXTRA_OPEN_WORKOUT, -1)
        if (open >= 0) {
            intent.removeExtra(EXTRA_OPEN_WORKOUT)
            (application as OpenStrapApp).container.workout.openRequest.value = open
        }
        val ready = intent.getStringExtra(EXTRA_READY_SPORT)?.toIntOrNull() ?: intent.getIntExtra(EXTRA_READY_SPORT, -1)
        if (ready >= 0) {
            intent.removeExtra(EXTRA_READY_SPORT)
            (application as OpenStrapApp).container.workout.readyRequest.value = ready
        }
        if (intent.getBooleanExtra(EXTRA_READY_PICK, false)) {
            intent.removeExtra(EXTRA_READY_PICK)
            (application as OpenStrapApp).container.workout.readyPickRequest.value = true
        }
        if (!intent.getBooleanExtra(EXTRA_SHOW_WORKOUT, false)) return
        intent.removeExtra(EXTRA_SHOW_WORKOUT)
        (application as OpenStrapApp).container.workout.minimized.value = false
    }

    companion object {
        const val EXTRA_SHOW_WORKOUT = "show_workout"
        const val EXTRA_READY_SPORT = "ready_sport"

        /** True opens Ready on the sport picker, as All sports does: the Start widget's All sports. */
        const val EXTRA_READY_PICK = "ready_pick"
        const val EXTRA_OPEN_WORKOUT = "open_workout"

        /** A metric's detail by [org.openstrap.app.ui.dashboard.Metric] name ("Hrv"), or "Recovery" or "Sleep". */
        const val EXTRA_OPEN_METRIC = "open_metric"

        /** The day for [EXTRA_OPEN_METRIC], as an ISO date ("2026-10-23"); without it the day showing stays. */
        const val EXTRA_METRIC_DAY = "metric_day"

        /**
         * [MOMENT_NOW] opens Today at its root, as it is now: the Moment widget's tap (widgets spec 4). Debug: a
         * [org.openstrap.app.data.Moment] name to force on Today, or "auto" to follow the clock again.
         */
        const val EXTRA_MOMENT = "moment"
        const val MOMENT_NOW = "now"

        /** True opens the Strap page over the tab showing: a widget's status line while the strap isn't paired or is low. */
        const val EXTRA_OPEN_STRAP = "open_strap"

        /** True opens the Updates page over the tab showing: an update notification's tap. */
        const val EXTRA_OPEN_UPDATES = "open_updates"

        /** Debug: a [StrapPreview] name ("stale", "stuck", "disconnected", "charging", "notpaired", "recoveryfail") to show, or "auto". */
        const val EXTRA_STRAP_STATE = "strap_state"

        /**
         * Debug: a [DataPreview] name to show, or "auto": "pair", "pair_search", "pair_found", "pair_connecting",
         * "pair_connected", "pair_failed", "firstnights", "nosleep", "partial", "firstscore".
         */
        const val EXTRA_DATA_STATE = "data_state"

        /** Debug: true opens the summary right after a workout for the latest stored phone workout. */
        const val EXTRA_SHOW_SUMMARY = "show_summary"

        /** Debug: the workout notification with sample figures, "running", "paused", "indoor", "end" or "clear". */
        const val EXTRA_WORKOUT_NOTIFICATION = "workout_notification"
    }

    /** Debug: `am start -n org.openstrap.app/.MainActivity --ei dump_raw_days 7` dumps raw history to files/raw. */
    private fun handleDebugIntent() {
        // Debug: `--ez demo_data true` fills an empty database with sample data (for the emulator), and stands in a
        // strap when none is paired, so the screens show a paired phone. Pair with `simulate_hr` to have it connected.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demo_data", false)) {
            intent.removeExtra("demo_data")
            val container = (application as OpenStrapApp).container
            kotlinx.coroutines.MainScope().launch {
                DemoData.fill(container.database.healthDao())
                if (container.settings.pairedDevice.first() == null) {
                    container.settings.savePairedDevice(org.openstrap.app.data.settings.PairedDevice("00:00:00:00:00:00", "00", tr(R.string.common_helio_strap)))
                }
            }
        }
        // Debug: `--ei probe_endpoint 73 --es probe_hex 00` sends raw bytes to a channel endpoint and logs replies.
        val probeEndpoint = intent.getIntExtra("probe_endpoint", -1)
        if (BuildConfig.DEBUG && probeEndpoint >= 0) {
            val hex = intent.getStringExtra("probe_hex").orEmpty()
            intent.removeExtra("probe_endpoint")
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            ((application as OpenStrapApp).container.strap as? org.openstrap.app.device.StrapManager)?.probe(probeEndpoint, bytes)
        }
        // Debug: `--ez strap_sessions false` stops phone workouts running a session on the strap (0x0019), `true` brings it back.
        if (BuildConfig.DEBUG && intent.hasExtra("strap_sessions")) {
            val on = intent.getBooleanExtra("strap_sessions", false)
            intent.removeExtra("strap_sessions")
            kotlinx.coroutines.MainScope().launch { (application as OpenStrapApp).container.settings.setStrapSessions(on) }
            Toast.makeText(this, tr(R.string.main_activity_strap_sessions, if (on) tr(R.string.main_activity_strap_sessions__on) else tr(R.string.main_activity_strap_sessions__off)), Toast.LENGTH_SHORT).show()
        }
        // Debug: `--es buzz AboveTarget` plays a cue's pattern on the strap (a Cue name, Stop or Offer), to feel it.
        val buzz = intent.getStringExtra("buzz")
        if (BuildConfig.DEBUG && buzz != null) {
            intent.removeExtra("buzz")
            val pattern = when (buzz) {
                "Stop" -> org.openstrap.app.device.WorkoutSession.STOP_BUZZ
                "Offer" -> org.openstrap.app.device.WorkoutSession.OFFER_BUZZ
                else -> runCatching { org.openstrap.app.data.Cue.valueOf(buzz).pattern }.getOrNull()
            }
            val strap = (application as OpenStrapApp).container.strap
            if (pattern != null) kotlinx.coroutines.MainScope().launch {
                runCatching { strap.buzz(pattern) }.onFailure { Toast.makeText(this@MainActivity, tr(R.string.main_activity_buzz_failed, it.message), Toast.LENGTH_SHORT).show() }
            }
        }
        // Debug: `--es simulate_hr 140x120,80x600 --ei simulate_steps 0 --ei simulate_battery 80` plays heart rate as a connected strap (emulator).
        val simulate = intent.getStringExtra("simulate_hr")
        if (BuildConfig.DEBUG && simulate != null) {
            intent.removeExtra("simulate_hr")
            val script = simulate.split(',').mapNotNull { p -> p.split('x').takeIf { it.size == 2 }?.let { (b, s) -> b.toIntOrNull()?.let { bpm -> s.toIntOrNull()?.let { bpm to it } } } }
            ((application as OpenStrapApp).container.strap as? org.openstrap.app.device.StrapManager)?.simulate(script, intent.getIntExtra("simulate_steps", 0), intent.getIntExtra("simulate_battery", 80))
        }
        // Debug: `--es moment Evening` forces Today's moment (a Moment name), so each can be checked on a phone;
        // `--es moment auto` goes back to the clock.
        val moment = intent.getStringExtra(EXTRA_MOMENT)
        if (BuildConfig.DEBUG && moment != null && moment != MOMENT_NOW) {
            intent.removeExtra(EXTRA_MOMENT)
            if (moment.equals("auto", ignoreCase = true)) forcedMoment.value = null
            else org.openstrap.app.data.Moment.entries.firstOrNull { it.name.equals(moment, ignoreCase = true) }
                ?.let { forcedMoment.value = it }
                ?: android.util.Log.w("OpenStrap", "moment: no moment called $moment")
        }
        // Debug: `--ei recovery_score 78` shows that score on the Recovery detail (its arc gauge) while Recovery is still
        // learning; `--ei recovery_score -1` goes back to the real one. Nothing is written.
        if (BuildConfig.DEBUG && intent.hasExtra("recovery_score")) {
            val score = intent.getIntExtra("recovery_score", -1)
            intent.removeExtra("recovery_score")
            org.openstrap.app.ui.glow.debugRecoveryScore.value = score.takeIf { it in 0..100 }
        }
        // Debug: `--es strap_state stuck` shows a strap state on a phone whose strap is fine, so each can be checked;
        // `--es strap_state auto` goes back to the real one. Only what the screens are shown changes: no Bluetooth, no
        // sync, nothing written.
        val strapState = intent.getStringExtra(EXTRA_STRAP_STATE)
        if (BuildConfig.DEBUG && strapState != null) {
            intent.removeExtra(EXTRA_STRAP_STATE)
            if (strapState.equals("auto", ignoreCase = true)) strapPreview.value = null
            else StrapPreview.of(strapState)?.let { strapPreview.value = it }
                ?: android.util.Log.w("OpenStrap", "strap_state: no state called $strapState")
            android.util.Log.i("OpenStrap", "strap_state: showing ${strapPreview.value ?: "the real state"}")
        }
        // Debug: `--es data_state nosleep` shows a data state (the pairing stage's steps, first nights, no sleep, a
        // partial day, the first score) on a phone whose data is fine; `--es data_state auto` goes back to the real
        // one. Only what the screens are shown changes: no Bluetooth, no scan, no sync, nothing written.
        val dataState = intent.getStringExtra(EXTRA_DATA_STATE)
        if (BuildConfig.DEBUG && dataState != null) {
            intent.removeExtra(EXTRA_DATA_STATE)
            if (dataState.equals("auto", ignoreCase = true)) dataPreview.value = null
            else DataPreview.of(dataState)?.let { dataPreview.value = it }
                ?: android.util.Log.w("OpenStrap", "data_state: no state called $dataState")
            android.util.Log.i("OpenStrap", "data_state: showing ${dataPreview.value ?: "the real state"}")
        }
        // Debug: `--ez show_summary true` opens the summary right after a workout for the latest stored phone workout,
        // with nothing live and nothing written.
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_SHOW_SUMMARY, false)) {
            intent.removeExtra(EXTRA_SHOW_SUMMARY)
            val container = (application as OpenStrapApp).container
            kotlinx.coroutines.MainScope().launch {
                val latest = container.database.healthDao().appWorkouts().first().filter { it.end != null }.maxByOrNull { it.start }
                if (latest != null) container.workout.showSummary(latest.start)
                else Toast.makeText(this@MainActivity, tr(R.string.main_activity_no_phone_workout_to), Toast.LENGTH_SHORT).show()
            }
        }
        // Debug: `--es workout_notification running` posts the workout notification with sample figures, built as a
        // real workout's is: running, paused, indoor, end or clear. Nothing records and nothing is written.
        val sample = intent.getStringExtra(EXTRA_WORKOUT_NOTIFICATION)
        if (BuildConfig.DEBUG && sample != null) {
            intent.removeExtra(EXTRA_WORKOUT_NOTIFICATION)
            startService(Intent(this, StrapService::class.java).setAction(StrapService.ACTION_SAMPLE).putExtra(StrapService.EXTRA_SAMPLE, sample))
        }
        val days = intent.getIntExtra("dump_raw_days", 0)
        if (!BuildConfig.DEBUG || days <= 0) return
        intent.removeExtra("dump_raw_days")
        // Debug: `--es dump_types 5,6,70` limits the dump to these GDSP codes (even ones the strap doesn't advertise).
        val types = intent.getStringExtra("dump_types")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        intent.removeExtra("dump_types")
        android.util.Log.i("OpenStrap", "dump: requested $days days, types ${types ?: "all"}")
        ((application as OpenStrapApp).container.strap as? org.openstrap.app.device.StrapManager)?.dumpRaw(days, types)
    }
}

private enum class Tab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    /** The capsule's colour while this tab shows (spec 3.2). */
    val tint: androidx.compose.ui.graphics.Color,
) {
    Today(tr(R.string.common_today), AppIcons.HouseSimple, AppIcons.HouseSimpleFill, androidx.compose.ui.graphics.Color.White),
    Recovery(tr(R.string.common_recovery), AppIcons.Plant, AppIcons.PlantFill, org.openstrap.app.ui.theme.MetricColors.readiness),
    Sleep(tr(R.string.common_sleep), AppIcons.Moon, AppIcons.MoonFill, org.openstrap.app.ui.theme.MetricColors.sleepDeep),
    Activity(tr(R.string.common_activity), AppIcons.SneakerMove, AppIcons.SneakerMoveFill, org.openstrap.app.ui.theme.MetricColors.steps),
}

/**
 * The pages that open over whichever tab is showing and close back to it (spec 3.3): the You page and what it opens,
 * and the Strap page and what it opens. Shallowest first.
 */
private enum class Page(
    /** The row on the You or Strap page that opens this page, marked while it's open beside the list on a wide screen. */
    val row: String? = null,
) {
    You, Profile(tr(R.string.common_profile)), HealthConnect(tr(R.string.you_page_health_connect)), Backup(tr(R.string.main_activity_backup)), Updates(tr(R.string.firmware_screen_updates)), Diagnostics(tr(R.string.common_diagnostics)),
    Strap, Alarms(tr(R.string.common_alarms)), PhoneAlerts(tr(R.string.common_phone_alerts)), HealthAlerts(tr(R.string.common_health_alerts)), Firmware(tr(R.string.common_firmware)), ZeppAccount(tr(R.string.common_zepp_account)),
}

/** Keeps the open pages across process death, as their names ("Strap,Alarms"). */
private val PagesSaver: androidx.compose.runtime.saveable.Saver<List<Page>, String> = androidx.compose.runtime.saveable.Saver(
    save = { pages -> pages.joinToString(",") { it.name } },
    restore = { s -> s.split(',').mapNotNull { n -> Page.entries.firstOrNull { it.name == n } } },
)

/** What an `open_metric` link asked for: the screen and the day to show it for. */
internal data class MetricRequest(val route: String, val day: java.time.LocalDate?)

private val BLUETOOTH_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
)

private fun epochNow() = System.currentTimeMillis() / 1000

/** The first score's day before the settings store has answered: nothing is decided until it has. */
private const val FIRST_SCORE_LOADING = Long.MIN_VALUE

@Composable
private fun AppRoot(
    container: AppContainer,
    metricRequests: kotlinx.coroutines.flow.MutableStateFlow<MetricRequest?>,
    todayRequests: kotlinx.coroutines.flow.MutableStateFlow<Boolean>,
    strapRequests: kotlinx.coroutines.flow.MutableStateFlow<Boolean>,
    forcedMoment: kotlinx.coroutines.flow.StateFlow<org.openstrap.app.data.Moment?>,
    strapPreview: kotlinx.coroutines.flow.StateFlow<StrapPreview?>,
    dataPreviews: kotlinx.coroutines.flow.StateFlow<DataPreview?>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dayVm: DayViewModel = viewModel(factory = viewModelFactory { initializer { DayViewModel(container.health, container.strap.todaySteps, container.settings.profile) } })
    val deviceVm: DeviceViewModel = viewModel(factory = viewModelFactory { initializer { DeviceViewModel(container) } })

    // Which tab shows and the one before it, for back; every tab keeps its own stack of details.
    var nav by rememberSaveable(stateSaver = TabNav.Saver) { mutableStateOf(TabNav()) }
    val tab = nav.current
    val liveEnabled by container.strap.liveHeartRateEnabled.collectAsStateWithLifecycle()
    val firmwareVm: FirmwareViewModel = viewModel(factory = viewModelFactory { initializer { FirmwareViewModel(container) } })
    // Pages over the tab showing (the You page and the Strap page, and what they open), deepest last.
    var pages by rememberSaveable(stateSaver = PagesSaver) { mutableStateOf(emptyList<Page>()) }
    // The All metrics sheet, from the grid button in the header: over the tab, never a place.
    var allMetricsOpen by rememberSaveable { mutableStateOf(false) }
    val workoutsVm: org.openstrap.app.ui.workout.WorkoutsViewModel = viewModel(factory = viewModelFactory {
        initializer { org.openstrap.app.ui.workout.WorkoutsViewModel(container.database.healthDao(), container.settings.profile) }
    })
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    val openWorkout by workoutsVm.open.collectAsStateWithLifecycle()
    // Each tab's stack of details, deepest last; back pops one. Opening a detail never moves you to another tab.
    var todayStack by rememberSaveable(stateSaver = Detail.StackSaver) { mutableStateOf(emptyList<Detail>()) }
    var activityStack by rememberSaveable(stateSaver = Detail.StackSaver) { mutableStateOf(emptyList<Detail>()) }
    var recoveryStack by rememberSaveable(stateSaver = Detail.StackSaver) { mutableStateOf(emptyList<Detail>()) }
    var sleepStack by rememberSaveable(stateSaver = Detail.StackSaver) { mutableStateOf(emptyList<Detail>()) }
    fun stackOf(t: Int): List<Detail> = when (Tab.entries[t]) {
        Tab.Today -> todayStack; Tab.Recovery -> recoveryStack; Tab.Sleep -> sleepStack; Tab.Activity -> activityStack
    }
    fun setStack(t: Int, stack: List<Detail>) = when (Tab.entries[t]) {
        Tab.Today -> todayStack = stack; Tab.Recovery -> recoveryStack = stack; Tab.Sleep -> sleepStack = stack; Tab.Activity -> activityStack = stack
    }
    // The All metrics Charge row asks the Recovery tab to scroll to its Charge card, once.
    var showCharge by remember { mutableStateOf(false) }
    // Each tap on the tab showing bumps its count, and its lists scroll to the top.
    val scrollToTop = remember { androidx.compose.runtime.mutableStateListOf(*Array(Tab.entries.size) { 0 }) }
    val history: org.openstrap.app.ui.dashboard.DailyHistory = { m, from, to -> container.health.daily(m, from, to) }
    var editGoals by rememberSaveable { mutableStateOf(false) }
    val goals by container.settings.goals.collectAsStateWithLifecycle(org.openstrap.app.data.settings.Goals())
    val todayHidden by container.settings.todayHiddenNames.collectAsStateWithLifecycle(null)
    val todayPinned by container.settings.todayPinnedNames.collectAsStateWithLifecycle(null)
    // Remembered, like the picker below: a new object each time the root recomposes (on every tab change) would
    // recompose every tab.
    val todayEdits = remember(todayHidden, todayPinned) { org.openstrap.app.ui.dashboard.TodayEdits.parse(todayHidden, todayPinned) }
    val strapState by strapPreview.collectAsStateWithLifecycle()
    val dataPreview by dataPreviews.collectAsStateWithLifecycle()
    // The pairing steps before a strap is paired show with no strap, as `strap_state notpaired` does.
    val preview = strapState ?: StrapPreview.NotPaired.takeIf { dataPreview?.unpaired == true }
    val realRecovery by dayVm.recovery.collectAsStateWithLifecycle()
    val realRecoveryFailure by dayVm.recoveryFailure.collectAsStateWithLifecycle()
    // The debug preview of a failed Recovery shows the day as failed, with yesterday's score from the real result.
    val previewFailure = if (preview == StrapPreview.RecoveryFail) realRecovery?.let { r ->
        RecoveryFailure(r.date, r.readinessTrend.firstOrNull { it.first == r.date.minusDays(1) }?.second)
    } else null
    // The data previews show first nights or no sleep in the day and its Recovery; nothing else changes.
    val recovery = if (previewFailure != null) null else realRecovery?.previewed(dataPreview)
    val recoveryFailure = previewFailure ?: realRecoveryFailure
    val healthspan by dayVm.healthspan.collectAsStateWithLifecycle()
    val habitAgeSeen by container.settings.habitAgeSeenAsOf.collectAsStateWithLifecycle(null)
    // Opening Habit Age marks the estimate seen, so Today stops offering it.
    val seeHabitAge: () -> Unit = { healthspan?.result?.asOf?.let { a -> scope.launch { container.settings.setHabitAgeSeen(a) } } }
    val profile by deviceVm.profile.collectAsStateWithLifecycle()
    val realDay by dayVm.state.collectAsStateWithLifecycle()
    val day = remember(realDay, dataPreview) { realDay.previewed(dataPreview) }
    // Your usual steps by the hour, read once per day shown, off the main thread ([DayViewModel.usualSteps]).
    val usualSteps by dayVm.usualSteps.collectAsStateWithLifecycle()
    val paiHistory: org.openstrap.app.ui.glow.PaiHistory = remember(container) { { from, to -> container.health.dailyPai(from, to) } }
    val energyHistory: org.openstrap.app.ui.glow.EnergyHistory = remember(container) { { from, to -> container.health.dailyEnergy(from, to) } }
    val strapReadiness by remember(day.dayStart) { container.database.healthDao().strapReadiness(day.dayStart) }.collectAsStateWithLifecycle(null)
    val checkIn by remember(day.date) { container.health.checkIn(day.date) }.collectAsStateWithLifecycle(null)
    val daysWithData by dayVm.daysWithData.collectAsStateWithLifecycle()
    val picker = remember(daysWithData, dayVm) { DayPicker(daysWithData, dayVm::select) }
    val live by container.strap.liveHeartRate.collectAsStateWithLifecycle()
    val realConnection by container.strap.connection.collectAsStateWithLifecycle()
    val realSync by container.strap.sync.collectAsStateWithLifecycle()
    val syncRecord by container.strap.syncRecord.collectAsStateWithLifecycle()
    val message by deviceVm.message.collectAsStateWithLifecycle()
    val workoutState by container.workout.state.collectAsStateWithLifecycle()
    val workoutMinimized by container.workout.minimized.collectAsStateWithLifecycle()
    // The ready screen's sport while it is open, and whether it opened on the sport list ("All sports").
    var readySport by rememberSaveable { mutableStateOf<Int?>(null) }
    var readyPicking by rememberSaveable { mutableStateOf(false) }
    val suggestions by workoutsVm.suggestions.collectAsStateWithLifecycle()
    // Hidden while a workout records: the strap's history may show the one under way.
    val suggestion = suggestions.firstOrNull().takeIf { workoutState !is org.openstrap.app.device.WorkoutState.Recording }
    val offer by container.workout.offer.collectAsStateWithLifecycle()
    // A stretch being saved as a workout waits here while its sport is chosen.
    var savingSuggestion by remember { mutableStateOf<org.openstrap.app.data.WorkoutSuggestions.Suggestion?>(null) }
    val firmwareInstalling: () -> Boolean = { Page.Firmware in pages && firmwareVm.step.value is org.openstrap.app.ui.firmware.FirmwareStep.Installing }
    // Closes the pages over the tab, unless an update is being installed there; true if any closed.
    val closePages: () -> Boolean = {
        if (pages.isEmpty() || firmwareInstalling()) false
        else { if (Page.Firmware in pages) firmwareVm.reset(); pages = emptyList(); true }
    }
    // The first page over the tab grows out of the header button tapped for it, if one was (spec 3.5).
    val growOrigin = remember { org.openstrap.app.ui.components.GrowOrigin() }
    var growFrom by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // How far that page is open, for the tab behind it to sink as it comes forward.
    val pageGrow = org.openstrap.app.ui.components.rememberGrowProgress(pages.isNotEmpty())
    val openPage: (Page) -> Unit = { p ->
        val tapped = growOrigin.take()
        if (pages.isEmpty()) growFrom = tapped
        if (pages.lastOrNull() != p) pages = pages + p
    }
    val closePage: () -> Unit = { pages = pages.dropLast(1) }
    // A launcher shortcut or the Quick Settings tile asked for the ready screen.
    val readyRequest by container.workout.readyRequest.collectAsStateWithLifecycle()
    LaunchedEffect(readyRequest) {
        readyRequest?.let { sport ->
            if (workoutState !is org.openstrap.app.device.WorkoutState.Recording) { readySport = sport; readyPicking = false }
            container.workout.readyRequest.value = null
        }
    }
    // The Start widget's All sports: Ready on the sport picker, as the Activity tab's All sports opens it.
    val readyPickRequest by container.workout.readyPickRequest.collectAsStateWithLifecycle()
    LaunchedEffect(readyPickRequest) {
        if (readyPickRequest) {
            if (workoutState !is org.openstrap.app.device.WorkoutState.Recording) {
                readySport = readySport ?: org.openstrap.app.ui.glow.topSports(workouts).first().sportType
                readyPicking = true
            }
            container.workout.readyPickRequest.value = false
        }
    }
    // The Moment widget: Today at its root, on today, over whatever showed.
    val todayRequest by todayRequests.collectAsStateWithLifecycle()
    LaunchedEffect(todayRequest) {
        if (todayRequest) {
            closePages()
            allMetricsOpen = false
            if (workoutState !is org.openstrap.app.device.WorkoutState.Recording) readySport = null
            todayStack = emptyList()
            dayVm.select(java.time.LocalDate.now())
            nav = nav.select(Tab.Today.ordinal)
            todayRequests.value = false
        }
    }
    // A widget's strap status line: the Strap page, over the tab showing, as the header's strap chip opens it.
    val strapRequest by strapRequests.collectAsStateWithLifecycle()
    LaunchedEffect(strapRequest) {
        if (strapRequest) {
            allMetricsOpen = false
            if (pages.lastOrNull() != Page.Strap && (pages.isEmpty() || closePages())) openPage(Page.Strap)
            strapRequests.value = false
        }
    }
    // An update notification: the Updates page, over the You page as its row opens it.
    val updatesRequest by container.updater.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(updatesRequest) {
        if (updatesRequest) {
            allMetricsOpen = false
            if (pages.lastOrNull() != Page.Updates && (pages.isEmpty() || closePages())) pages = listOf(Page.You, Page.Updates)
            container.updater.openRequest.value = false
        }
    }
    // An auto-detected workout's notification: that workout, on the Activity tab, its home.
    val openRequest by container.workout.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        openRequest?.let { start ->
            closePages()
            activityStack = listOf(Detail.Workout(start))
            nav = nav.select(Tab.Activity.ordinal)
            container.workout.openRequest.value = null
        }
    }
    // A health alert or body-signal notification: its metric's screen, for the alert's day, over the tab showing.
    val metricRequest by metricRequests.collectAsStateWithLifecycle()
    LaunchedEffect(metricRequest) {
        metricRequest?.let { r ->
            Detail.fromRoute(r.route)?.let { detail ->
                closePages()
                allMetricsOpen = false
                r.day?.let(dayVm::select)
                if (stackOf(tab).lastOrNull() != detail) setStack(tab, stackOf(tab) + detail)
            }
            metricRequests.value = null
        }
    }
    // The day a workout is being added to after the fact.
    var addingOn by rememberSaveable { mutableStateOf<java.time.LocalDate?>(null) }

    // When the strap was last heard from, for the header chip: kept fresh while connected, then left where it stopped.
    // Without that in this run, the last synced reading or the last sync stand in.
    var seenConnected by rememberSaveable { mutableStateOf<Long?>(null) }
    val connected = realConnection is ConnectionState.Connected
    LaunchedEffect(connected) {
        if (connected) while (true) { seenConnected = epochNow(); kotlinx.coroutines.delay(30_000) }
    }
    val realLastSeen = listOfNotNull(
        seenConnected,
        (realSync as? org.openstrap.app.device.SyncState.Done)?.atEpochSeconds,
        day.heartRate.lastOrNull()?.x?.takeIf { day.date == java.time.LocalDate.now() },
    ).maxOrNull()
    // The chip's "18 min ago" moves on by itself.
    val now by androidx.compose.runtime.produceState(epochNow()) { while (true) { kotlinx.coroutines.delay(30_000); value = epochNow() } }
    // What the screens are shown of the strap: the real state, or a debug preview's (nothing else changes).
    // Paired until the store says otherwise, so a paired strap never flashes the pairing hero or "Not paired" on start.
    val realPairedKnown by remember { container.settings.pairedDevice.map { it != null } }.collectAsStateWithLifecycle(true)
    val strapView = StrapView(realPairedKnown, realConnection, realSync, syncRecord, realLastSeen).previewed(preview, now)
    val connection = strapView.connection
    val sync = strapView.sync
    val lastSeen = strapView.lastSeen
    // Right after the process starts the service is still reconnecting, so the chip waits before saying "18 min ago".
    val sinceStart = if (preview != null) Long.MAX_VALUE else (android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()) / 1000
    val chip = strapChip(strapView.paired, connection, sync, lastSeen, now, sinceStart)

    // The moment (spec 4.1), on a clock that ticks each minute only while the app is in view.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val clock by androidx.compose.runtime.produceState(java.time.Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                value = java.time.Instant.now()
                kotlinx.coroutines.delay(60_000 - System.currentTimeMillis() % 60_000)
            }
        }
    }
    val zone = java.time.ZoneId.systemDefault()
    val clockDate = clock.atZone(zone).toLocalDate()
    // Open across midnight on today, the day moves on with the clock, so night carries on past 00:00.
    var shownToday by remember { mutableStateOf(clockDate) }
    LaunchedEffect(clockDate) {
        if (clockDate != shownToday) {
            if (day.date == shownToday) dayVm.select(clockDate)
            shownToday = clockDate
        }
    }
    // Your usual workout time comes from workouts you started, not walks the strap noticed by itself.
    val workoutStarts = remember(workouts) { workouts.filter { it.startedByYou }.map { it.workout.start } }
    val lastWorkout = remember(workouts) { workouts.maxByOrNull { it.end } }
    val recording = workoutState is org.openstrap.app.device.WorkoutState.Recording
    // The moment is the clock's, whichever day is shown: browsing yesterday at night is still night (the mood follows
    // it), and only Today's layout goes back to waking for a past day. Today's wake and usual bedtime are kept from
    // when today was last loaded, so a past day doesn't lose them. Until today's recovery has loaded once, the
    // bedtime isn't known and the fallback would show Evening for a second before the real moment, so there's no
    // moment until then: Today's plain title, the day's mood.
    // A Recovery that failed counts as loaded: the moment shouldn't wait on it.
    val todayLoaded = day.date == clockDate && (recovery?.date == clockDate || recoveryFailure?.date == clockDate)
    val freshClockFacts = if (todayLoaded) day.sleep?.end to recovery?.usualBedtime else null
    var keptClockFacts by remember(clockDate) { mutableStateOf<Pair<Long?, java.time.LocalTime?>?>(null) }
    androidx.compose.runtime.SideEffect { if (freshClockFacts != null && freshClockFacts != keptClockFacts) keptClockFacts = freshClockFacts }
    val clockFacts = freshClockFacts ?: keptClockFacts
    val usualBedtime = clockFacts?.second
    val clockMoment = remember(clock.epochSecond / 60, clockDate, recording, lastWorkout?.end, clockFacts, workoutStarts) {
        clockFacts?.let { (sleepEnd, bedtime) ->
            org.openstrap.app.data.MomentResolver.resolve(clock, zone, clockDate, recording, lastWorkout?.end, sleepEnd, bedtime, workoutStarts)
        }
    }
    val forced by forcedMoment.collectAsStateWithLifecycle()
    val moment = forced ?: clockMoment
    // What Today is laid out for: the moment today, the waking layout for a past day.
    val todayMoment = org.openstrap.app.ui.dashboard.layoutMoment(moment, day.date == clockDate)
    val pairedKnown = realPairedKnown && preview != StrapPreview.NotPaired
    LaunchedEffect(forced, clockMoment) {
        forced?.let { android.util.Log.i("OpenStrap", "moment: forced $it, the clock says $clockMoment") }
    }

    // The pairing stage (E3) from the real pairing: the Strap page's scan before a strap is paired, then the
    // connection. Once paired it stays on Today until the strap first connects, and holds Connected a moment.
    val scan by deviceVm.scan.collectAsStateWithLifecycle()
    var firstConnect by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(realPairedKnown) { if (!realPairedKnown) firstConnect = true }
    val realStep = if (!realPairedKnown || firstConnect) pairStep(realPairedKnown, scan.scanning, scan.found.isNotEmpty(), realConnection) else null
    LaunchedEffect(realStep == PairStep.Connected) {
        if (realStep == PairStep.Connected) { kotlinx.coroutines.delay(PAIRED_HOLD_MS); firstConnect = false }
    }
    val pairing = dataPreview?.pair ?: realStep

    // How much of the day the strap was worn, for the Partial day tag on the cards it lowers (spec 4.3).
    val dayIsToday = day.date == clockDate
    val wear = day.wear(recovery, dayIsToday)
        .previewed(dataPreview, (((if (dayIsToday) now else day.dayEnd + 1) - day.dayStart) / 60).toInt())

    // The first real score is marked once (E4): its day is kept, and never written while a preview shows.
    val firstScoreDay by container.settings.firstScoreDay.collectAsStateWithLifecycle(FIRST_SCORE_LOADING)
    val dayRecovery = recovery?.takeIf { it.date == day.date }
    val first = if (firstScoreDay == FIRST_SCORE_LOADING) FirstScore(false) else firstScore(
        dayRecovery?.readiness?.score, day.date, dayIsToday, dayRecovery?.readiness?.phase == Recovery.Phase.Learning,
        dayRecovery?.let { earliestScore(it.readinessTrend, day.date) }, firstScoreDay?.let(LocalDate::ofEpochDay),
    )
    LaunchedEffect(first.remember, preview, dataPreview) {
        if (preview == null && dataPreview == null) first.remember?.let { container.settings.setFirstScoreDay(it.toEpochDay()) }
    }
    val showFirstScore = first.show || dataPreview == DataPreview.FirstScoreShown

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); deviceVm.clearMessage() }
    }

    // Bluetooth permission gate: remember what to run once granted.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = result[Manifest.permission.BLUETOOTH_SCAN] == true && result[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (ok) pending?.invoke() else Toast.makeText(context, tr(R.string.main_activity_bluetooth_permission_is_required), Toast.LENGTH_LONG).show()
        pending = null
    }
    val requestBluetooth: (() -> Unit) -> Unit = { then -> pending = then; btLauncher.launch(BLUETOOTH_PERMISSIONS) }
    // Location for a GPS workout: asked only when GPS is turned on; the workout starts either way (steps without it).
    var afterLocation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.none { it }) Toast.makeText(context, tr(R.string.main_activity_without_location_distance_comes), Toast.LENGTH_LONG).show()
        afterLocation?.invoke()
        afterLocation = null
    }
    val requestLocation: (() -> Unit) -> Unit = { then ->
        afterLocation = then
        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(deviceVm::exportCsv)
    }
    // The write permissions are asked for the manual copy and for turning auto sync on; this says which, and survives
    // the activity being recreated behind the permission screen.
    var hcForAuto by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val auto = hcForAuto
        hcForAuto = false
        scope.launch {
            // The needed ones may have been granted before, outside what this screen returns; the calorie ones may stay off.
            val ok = granted.containsAll(container.healthConnect.permissions) || runCatching { container.healthConnect.hasPermissions() }.getOrDefault(false)
            if (!ok) Toast.makeText(context, tr(R.string.main_activity_health_connect_permissions_were), Toast.LENGTH_LONG).show()
            else if (auto) deviceVm.setAutoHealthConnect(true) else deviceVm.exportHealthConnect()
        }
    }
    // Automatic backup's folder picker (a cancel leaves it as it was), and Restore, opening on that folder or Download.
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(deviceVm::chooseBackupFolder)
    }
    val backupRestoreLauncher = rememberLauncherForActivityResult(org.openstrap.app.ui.device.PickBackup()) { uri ->
        uri?.let(deviceVm::importCsv)
    }
    val hcReadLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.any { it in container.healthConnect.readPermissions }) deviceVm.importHealthConnect()
        else Toast.makeText(context, tr(R.string.main_activity_health_connect_permissions_were), Toast.LENGTH_LONG).show()
    }
    val importHealthConnect: () -> Unit = {
        if (!container.healthConnect.isAvailable()) {
            Toast.makeText(context, tr(R.string.main_activity_health_connect_isn_t), Toast.LENGTH_LONG).show()
        } else scope.launch {
            if (container.healthConnect.hasReadPermissions()) deviceVm.importHealthConnect()
            else hcReadLauncher.launch(container.healthConnect.readPermissions)
        }
    }
    // The manual copy, or turning auto sync on ([forAuto]): with the write permissions, asked for first when missing.
    val writeHealthConnect: (Boolean) -> Unit = { forAuto ->
        if (!container.healthConnect.isAvailable()) {
            Toast.makeText(context, tr(R.string.main_activity_health_connect_isn_t), Toast.LENGTH_LONG).show()
        } else scope.launch {
            // The calorie permissions are optional, but asked for again until granted: the launcher goes on with the
            // ones it needs.
            if (container.healthConnect.hasRequestedPermissions()) {
                if (forAuto) deviceVm.setAutoHealthConnect(true) else deviceVm.exportHealthConnect()
            } else {
                hcForAuto = forAuto
                hcLauncher.launch(container.healthConnect.requestedPermissions)
            }
        }
    }
    val exportHealthConnect: () -> Unit = { writeHealthConnect(false) }
    val autoHealthConnect: (Boolean) -> Unit = { on -> if (on) writeHealthConnect(true) else deviceVm.setAutoHealthConnect(false) }

    // Swiping left and right moves between tabs. [nav] owns the tab and the pager follows it: a tap animates the
    // pager there, and only a swipe the user made, once it settles, is another way to pick the tab. The pager
    // settling from its own animation (or one cut short by the next tap) never writes back.
    val pager = rememberPagerState(initialPage = tab) { Tab.entries.size }
    val swiped = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    LaunchedEffect(tab) {
        if (pager.currentPage != tab) {
            swiped.set(false)
            pager.animateScrollToPage(tab, animationSpec = tween(Motion.SWIFT, easing = Motion.easing))
        }
    }
    LaunchedEffect(pager) {
        launch { pager.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) swiped.set(true) } }
        snapshotFlow { pager.settledPage }.collect { nav = nav.settled(it, swiped.get()) }
    }
    // A detail screen open on this tab keeps the swipe for itself (and for its back gesture).
    val currentDetail = stackOf(tab).lastOrNull()
    LaunchedEffect(currentDetail) {
        workoutsVm.open((currentDetail as? Detail.Workout)?.start)
        // A screen opened or closed: the next tap on the tab scrolls before it pops.
        nav = nav.moved()
    }
    val subScreenOpen = currentDetail != null || pages.isNotEmpty()
    // Back closes what's open first (those screens handle it, registered later so they win). From a tab's root it
    // goes to the tab before, then to Today; on Today looking at a past day, back first returns to today. Only
    // Today's back leaves the app.
    val today = java.time.LocalDate.now()
    androidx.activity.compose.BackHandler(enabled = !subScreenOpen && (tab != Tab.Today.ordinal || day.date != today)) {
        if (tab == Tab.Today.ordinal) dayVm.select(today) else nav.back()?.let { nav = it }
    }
    // Back handlers on tabs that aren't showing (they stay composed, stacks and all) answer to a dispatcher nobody
    // presses, so back only ever closes what's on screen.
    val idleBack = remember {
        object : androidx.navigationevent.NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = androidx.navigationevent.NavigationEventDispatcher()
        }
    }
    // The pages are what the glass nav bar blurs as they scroll under it.
    val navGlass = dev.chrisbanes.haze.rememberHazeState()
    // Popups are drawn in the app's own window over a blur of the pages, like the nav bar.
    val glassHost = remember { org.openstrap.app.ui.components.GlassHost() }
    // Half-open on a table, the fold level: heart rate fills the standing top half, the app carries on below the fold.
    // During a workout the top half is the workout's heart card instead, and the controls stay below.
    val activity = androidx.activity.compose.LocalActivity.current
    val windowLayout by remember(activity) {
        activity?.let { androidx.window.layout.WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: kotlinx.coroutines.flow.emptyFlow()
    }.collectAsStateWithLifecycle(null)
    val halfOpen = windowLayout?.displayFeatures?.filterIsInstance<androidx.window.layout.FoldingFeature>()
        ?.filter { it.state == androidx.window.layout.FoldingFeature.State.HALF_OPENED }.orEmpty()
    val hinge = halfOpen.firstOrNull { it.orientation == androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL }
    // Stood up like a book, the fold upright: only the live workout splits, heart card left, controls right.
    val bookHinge = halfOpen.firstOrNull { it.orientation == androidx.window.layout.FoldingFeature.Orientation.VERTICAL }
    val workoutOpen = workoutState as? org.openstrap.app.device.WorkoutState.Recording
    val workoutOnTop = workoutOpen?.takeIf { hinge != null && !workoutMinimized }
    CompositionLocalProvider(
        org.openstrap.app.ui.dashboard.LocalGoals provides goals,
        org.openstrap.app.ui.components.LocalGlassHost provides glassHost,
        org.openstrap.app.ui.components.LocalGrowOrigin provides growOrigin,
    ) {
      androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      if (hinge != null) {
          val density = androidx.compose.ui.platform.LocalDensity.current
          val top = androidx.compose.ui.Modifier.height(with(density) { hinge.bounds.top.toDp() })
          if (workoutOnTop != null) {
              val recent by container.workout.recent.collectAsStateWithLifecycle()
              val zoneSeconds by container.workout.zoneSeconds.collectAsStateWithLifecycle()
              val kcal by container.workout.kcal.collectAsStateWithLifecycle()
              org.openstrap.app.ui.glow.GlowWorkoutGlance(workoutOnTop, live, recent, zoneSeconds, realConnection, top, kcal)
          } else org.openstrap.app.ui.glow.GlowTabletopHeart(
              day, live, liveEnabled.takeIf { realConnection is ConnectionState.Connected }, { container.strap.setLiveHeartRate(it) }, top,
          )
          androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(with(density) { hinge.bounds.height().toDp() }))
      }
      // Below the fold the status bar is far away, so the pages don't make room for it.
      androidx.compose.foundation.layout.BoxWithConstraints(
          androidx.compose.ui.Modifier.weight(1f).fillMaxWidth()
              .let { if (hinge != null) it.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars) else it },
      ) {
        // Wider than tall (a phone on its side, an unfolded foldable turned sideways): the tabs stand down the
        // left edge, so the pages keep their height.
        val rail = maxWidth > maxHeight && maxWidth >= 600.dp
        val navItems = Tab.entries.map { NavItem(it.label, it.icon, it.selectedIcon, it.tint) }
        // A detail covering the tab is a screen stepped into: no tab bar, only its back button (spec 3.2). Wide
        // enough for the detail to open beside the tab, the bar stays with the tab it belongs to.
        val showBar = !rail && !(currentDetail != null && maxWidth < SideBySideWidth)
        // Another tab keeps every stack. The tab showing scrolls to the top, then pops to its root (spec 3.3). A tap
        // that closes pages over the tab does only that.
        val selectTab: (Int) -> Unit = { t ->
            val closed = closePages()
            when {
                firmwareInstalling() -> Unit
                t != nav.current -> nav = nav.select(t)
                closed -> Unit
                else -> {
                    val (next, action) = nav.reselect(stackOf(t).size)
                    nav = next
                    when (action) {
                        Reselect.ScrollToTop -> scrollToTop[t] = scrollToTop[t] + 1
                        Reselect.PopToRoot -> setStack(t, emptyList())
                    }
                }
            }
        }
        Scaffold(
            bottomBar = {
                androidx.compose.animation.AnimatedVisibility(
                    showBar,
                    enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
                ) { FloatingNavBar(navItems, tab, selectTab, glass = navGlass) }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            if (editGoals) {
                // The calorie goal your last four weeks suggest, read as the sheet opens (calories spec 3.4).
                val suggestedCalories by produceState<Int?>(null) { value = runCatching { container.health.suggestedCalorieGoal() }.getOrNull() }
                org.openstrap.app.ui.device.GoalsSheet(goals, suggestedCalories = suggestedCalories, onSave = { g ->
                    scope.launch {
                        container.settings.saveGoals(g)
                        // The Steps widget's goal, and the evening sentence's on Moment (widgets spec 7).
                        org.openstrap.app.device.WidgetUpdater.changed(container.app, setOf(org.openstrap.app.widget.WidgetKind.Steps, org.openstrap.app.widget.WidgetKind.Moment))
                    }
                    editGoals = false
                }, onDismiss = { editGoals = false })
            }
            val updateWaiting by container.updater.waiting.collectAsStateWithLifecycle()
            // The header on every tab: the moment on Today (a past day's name for a past day), the tab's name elsewhere.
            val header: @Composable (Tab) -> Unit = { t ->
                MomentHeader(
                    if (t != Tab.Today) t.label else todayMoment?.let { m ->
                        // During a workout the header is the sport under way, after one the sport just done.
                        val sport = if (m == org.openstrap.app.data.Moment.During) (workoutState as? org.openstrap.app.device.WorkoutState.Recording)?.workout?.sportType else lastWorkout?.workout?.sportType
                        todayTitle(m, day.date, sport)
                    } ?: org.openstrap.app.ui.dashboard.dayTitle(day.date), day.date, chip, dayVm::previousDay, dayVm::nextDay, picker,
                    onOpenYou = { openPage(Page.You) }, onOpenStrap = { openPage(Page.Strap) },
                    // All metrics is hidden for now (spec 3.1). Passing { allMetricsOpen = true } brings the button
                    // back; the sheet opens over whatever shows, and no tab hears of it (spec 3.2).
                    onOpenAllMetrics = null,
                    updateWaiting = updateWaiting,
                )
            }
            // What a tab's stack shows on top: the same screens on every tab, each pushing onto the stack it's on.
            val detailPage: @Composable (Int, List<Detail>) -> Unit = { t, stack ->
                val pop: () -> Unit = { setStack(t, stack.dropLast(1)) }
                val push: (Detail) -> Unit = { setStack(t, stack + it) }
                when (val detail = stack.last()) {
                    // A switcher pick takes the detail's place; anything else it opens goes on top.
                    is Detail.OfMetric -> MetricDetail(
                        detail.metric, detail.night, day, history = history,
                        liveHeartRate = live, liveEnabled = liveEnabled, canUseLive = realConnection is ConnectionState.Connected,
                        onLiveToggle = { container.strap.setLiveHeartRate(it) },
                        onOpen = push, onSwitch = { setStack(t, stack.dropLast(1) + it) }, onSelectDay = dayVm::select,
                        contentPadding = padding, onBack = pop,
                    )
                    is Detail.OfScore -> org.openstrap.app.ui.glow.GlowScoreDetailScreen(
                        detail.score, day, recovery, strapReadiness?.takeIf { it.day == day.dayStart }?.score, history,
                        onOpen = push, onSwitch = { setStack(t, stack.dropLast(1) + it) }, onSelectDay = dayVm::select,
                        contentPadding = padding, onBack = pop,
                        workouts = workouts, paiHistory = paiHistory, onEditProfile = { openPage(Page.Profile) }, energyHistory = energyHistory,
                        onSetNapTimes = dayVm::setNapTimes,
                    )
                    Detail.Week -> org.openstrap.app.ui.glow.WeekDetail(
                        org.openstrap.app.ui.glow.ActivityDetailContext(
                            day, recovery?.takeIf { it.date == day.date }, workouts, paiHistory, history, onOpen = push, onSwitch = {},
                            onSelectDay = dayVm::select, onEditProfile = { openPage(Page.Profile) }, contentPadding = padding, onBack = pop,
                        ),
                    )
                    is Detail.Workout -> WorkoutDetail(
                        openWorkout?.takeIf { it.workout.start == detail.start }, workouts, padding, onBack = pop,
                        onDelete = { container.workout.discard(detail.start); pop() },
                        workoutsVm = workoutsVm, session = container.workout,
                        bmrPerMinute = org.openstrap.app.data.EnergyExpenditure.bmrPerMinute(profile, profile.age()),
                        recovery = recovery, energyHistory = energyHistory,
                        // Its day's Calories: the day shown moves to it, as a strip pick does.
                        onOpenDay = { d -> dayVm.select(d); push(Detail.OfScore(org.openstrap.app.ui.dashboard.Score.Energy)) },
                    )
                    Detail.AllWorkouts -> org.openstrap.app.ui.glow.GlowWorkoutsScreen(
                        workouts, padding, onOpen = { push(Detail.Workout(it)) }, onBack = pop,
                    )
                    Detail.Healthspan -> org.openstrap.app.ui.glow.GlowHealthspanScreen(healthspan, padding, onBack = pop)
                    // The Recovery and Sleep tabs' screens, opened over another tab from a card about them.
                    Detail.Recovery -> {
                        androidx.activity.compose.BackHandler(onBack = pop)
                        org.openstrap.app.ui.glow.GlowRecoveryScreen(
                            day, recovery, history,
                            header = { org.openstrap.app.ui.glow.PageHeader(tr(R.string.common_recovery), pop, subtitle = org.openstrap.app.ui.glow.dayName(day.date)) },
                            contentPadding = padding, onOpenMetric = { push(Detail.OfMetric(it)) },
                            onOpenScore = { push(Detail.OfScore(it)) }, strapScore = strapReadiness?.takeIf { it.day == day.dayStart }?.score,
                            healthspan = healthspan, onOpenHealthspan = { seeHabitAge(); push(Detail.Healthspan) }, onOpenProfile = { openPage(Page.Profile) },
                            recoveryFailure = recoveryFailure, onRetryRecovery = dayVm::retryRecovery,
                            wear = wear, firstScore = showFirstScore,
                        )
                    }
                    Detail.Sleep -> {
                        androidx.activity.compose.BackHandler(onBack = pop)
                        org.openstrap.app.ui.glow.GlowSleepScreen(
                            day, header = { org.openstrap.app.ui.glow.PageHeader(tr(R.string.common_sleep), pop, subtitle = org.openstrap.app.ui.glow.dayName(day.date)) },
                            contentPadding = padding, onOpen = push, recovery = recovery,
                        )
                    }
                }
            }
            // Every tab stays composed, so a swipe never builds a whole dashboard mid-gesture (with only the neighbours
            // kept, crossing halfway built the next one); glows on tabs off screen, or while the pager moves, hold still.
            HorizontalPager(
                pager, androidx.compose.ui.Modifier.padding(start = if (rail) org.openstrap.app.ui.components.NavRailWidth else 0.dp)
                    .sinkBehind(pageGrow).hazeSource(navGlass),
                userScrollEnabled = !subScreenOpen, key = { it }, beyondViewportPageCount = Tab.entries.size - 1,
            ) { page -> androidx.compose.runtime.CompositionLocalProvider(
                // Remembered: a new lambda each time would be a new static local, recomposing the whole tab under it.
                org.openstrap.app.ui.glow.LocalGlowPagePaused provides remember(pager, page) { { pager.isScrollInProgress || pager.settledPage != page } },
                org.openstrap.app.ui.dashboard.LocalScrollToTop provides scrollToTop[page],
                *(if (page == tab) emptyArray() else arrayOf(androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides idleBack)),
            ) {
                val pullToSync: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable () -> Unit) -> Unit = { screen ->
                    org.openstrap.app.ui.components.PullToSync(realSync, realConnection is ConnectionState.Connected, container.strap::syncNow) { screen() }
                }
                val t = Tab.entries[page]
                // A tab's root with its stack opening over it (or beside it, when wide).
                ListDetail(
                    stackOf(page), isOpen = { it.isNotEmpty() }, keys = { s -> s.map(Detail::keyOf) }, minWidth = SideBySideWidth,
                    detail = { stack -> if (stack.isNotEmpty()) detailPage(page, stack) },
                ) {
                    pullToSync {
                        when (t) {
                            // The moment's mood lights Today only (spec 2.1 Moods); the Sleep tab sets its own.
                            Tab.Today -> androidx.compose.runtime.CompositionLocalProvider(
                                org.openstrap.app.ui.glow.LocalGlowMood provides (todayMoment?.let { org.openstrap.app.ui.dashboard.momentMood(it) } ?: org.openstrap.app.ui.glow.GlowMood.Day),
                            ) { org.openstrap.app.ui.glow.GlowTodayScreen(
                                day, live, header = { header(Tab.Today) }, contentPadding = padding, moment = todayMoment,
                                // Cards about other tabs open those screens here, on Today; none switches tab.
                                onOpenMetric = { todayStack = listOf(Detail.OfMetric(it)) },
                                onOpen = { todayStack = listOf(it) }, usualSteps = usualSteps,
                                recovery = recovery,
                                strapReadiness = strapReadiness,
                                checkIn = checkIn?.feeling,
                                onCheckIn = { v -> scope.launch { container.health.saveCheckIn(day.date, v) } },
                                onOpenRecovery = { todayStack = listOf(Detail.Recovery) },
                                workouts = workouts,
                                onOpenWorkout = { todayStack = listOf(Detail.Workout(it)) },
                                onOpenHealthspan = { seeHabitAge(); todayStack = listOf(Detail.Healthspan) },
                                healthspan = healthspan,
                                habitAgeSeenAsOf = habitAgeSeen,
                                onOpenProfile = { openPage(Page.Profile) },
                                history = history,
                                edits = todayEdits,
                                onEditsChange = { e -> scope.launch { container.settings.saveTodayEdits(e.hidden.map { it.name }, e.pinned.map { it.name }) } },
                                suggestion = suggestion,
                                onSaveSuggestion = { savingSuggestion = suggestion },
                                onDismissSuggestion = { suggestion?.let { container.workout.dismissSuggestion(it.start, it.end) } },
                                // The same way in as Activity's start card: the ready screen for the sport, or its sport list.
                                onStartSport = { readySport = it; readyPicking = false },
                                onOtherSport = { readySport = readySport ?: org.openstrap.app.ui.glow.topSports(workouts).first().sportType; readyPicking = true },
                                offer = offer,
                                onRecordOffer = { container.workout.recordOffer() },
                                onDeclineOffer = { container.workout.declineOffer() },
                                running = workoutState as? org.openstrap.app.device.WorkoutState.Recording,
                                onOpenRunning = { container.workout.minimized.value = false },
                                onResumeRunning = container.workout::resume,
                                heroLightAngle = todayMoment?.let { org.openstrap.app.ui.dashboard.heroLightAngle(it) },
                                strap = org.openstrap.app.ui.glow.TodayStrap(
                                    paired = pairedKnown, pairing = pairing, stopped = syncStopped(pairedKnown, connection, sync), record = strapView.record,
                                    canSync = realConnection is ConnectionState.Connected,
                                    staleAfter = preview?.staleAfter ?: org.openstrap.app.ui.dashboard.STALE_AFTER_S,
                                ),
                                recoveryFailure = recoveryFailure,
                                onRetryRecovery = dayVm::retryRecovery,
                                onPair = { openPage(Page.Strap) },
                                onSyncNow = container.strap::syncNow,
                                usualBedtime = usualBedtime,
                                wear = wear, firstScore = showFirstScore,
                            ) }
                            Tab.Recovery -> org.openstrap.app.ui.glow.GlowRecoveryScreen(
                                day, recovery, history, header = { header(Tab.Recovery) }, contentPadding = padding,
                                onOpenMetric = { recoveryStack = listOf(Detail.OfMetric(it)) },
                                onOpenScore = { recoveryStack = listOf(Detail.OfScore(it)) },
                                strapScore = strapReadiness?.takeIf { it.day == day.dayStart }?.score,
                                healthspan = healthspan, onOpenHealthspan = { seeHabitAge(); recoveryStack = listOf(Detail.Healthspan) },
                                onOpenProfile = { openPage(Page.Profile) },
                                showCharge = showCharge, onChargeShown = { showCharge = false },
                                recoveryFailure = recoveryFailure, onRetryRecovery = dayVm::retryRecovery,
                                wear = wear, firstScore = showFirstScore,
                            )
                            Tab.Sleep -> org.openstrap.app.ui.glow.GlowSleepScreen(
                                day, header = { header(Tab.Sleep) }, contentPadding = padding,
                                onOpen = { sleepStack = listOf(it) },
                                recovery = recovery,
                            )
                            Tab.Activity -> org.openstrap.app.ui.glow.GlowActivityScreen(
                                day, recovery, workouts, paiHistory,
                                header = { header(Tab.Activity) }, contentPadding = padding,
                                onOpenMetric = { activityStack = listOf(Detail.OfMetric(it)) },
                                onOpen = { activityStack = listOf(it) }, usualSteps = usualSteps,
                                onOpenWorkout = { activityStack = listOf(Detail.Workout(it)) },
                                onAllWorkouts = { activityStack = listOf(Detail.AllWorkouts) },
                                onEditProfile = { openPage(Page.Profile) },
                                onStartWorkout = { readySport = it; readyPicking = false },
                                suggestion = suggestion,
                                onSaveSuggestion = { savingSuggestion = suggestion },
                                onDismissSuggestion = { suggestion?.let { container.workout.dismissSuggestion(it.start, it.end) } },
                                onAllSports = { readySport = readySport ?: org.openstrap.app.ui.glow.topSports(workouts).first().sportType; readyPicking = true },
                                onAddWorkout = { addingOn = day.date },
                                liveHeartRate = live, history = history, wear = wear,
                            )
                        }
                    }
                }
            } }
            // The pages over the tab: the You page and the Strap page, and what they open. They grow out of the
            // button that opened them and shrink back into it, drawn as they were while they go; meanwhile back and
            // taps belong to the tab again.
            val keptPages = remember { arrayOf(pages) }
            if (pages.isNotEmpty()) keptPages[0] = pages
            val tabBack = androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner.current
            org.openstrap.app.ui.components.GrowFromButton(
                pages.isNotEmpty(), growFrom,
                androidx.compose.ui.Modifier.fillMaxSize().padding(start = if (rail) org.openstrap.app.ui.components.NavRailWidth else 0.dp),
                progress = pageGrow,
            ) { closing -> androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).hazeSource(navGlass, zIndex = 1f),
            ) {
                CompositionLocalProvider(androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides (if (closing || tabBack == null) idleBack else tabBack)) {
                org.openstrap.app.ui.components.OledSurfaces {
                    OverPages(
                        pages.ifEmpty { keptPages[0] }, container, deviceVm, firmwareVm, profile, padding, requestBluetooth, lastSeen, strapView.takeIf { preview != null },
                        open = openPage, close = closePage,
                        // A row on a wide screen's list: its page beside the list, in place of the one there, unless
                        // an update is being installed there.
                        openBeside = { p -> if (!firmwareInstalling()) pages = org.openstrap.app.ui.dashboard.openBeside(pages, p) },
                        closeAll = { closePages() },
                        youPage = { open, selected, onBack ->
                            val log by container.strap.log.collectAsStateWithLifecycle()
                            val hcAuto by deviceVm.healthConnectAuto.collectAsStateWithLifecycle()
                            val backup by deviceVm.backup.collectAsStateWithLifecycle()
                            val updates by container.updater.state.collectAsStateWithLifecycle()
                            val updateWaiting by container.updater.waiting.collectAsStateWithLifecycle()
                            // Permissions can be taken back in Health Connect while away: the row says so on return.
                            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { deviceVm.checkAutoHealthConnect(); onPauseOrDispose { } }
                            org.openstrap.app.ui.device.YouPage(
                                profile, goals, org.openstrap.app.data.Recovery.sleepNeed(profile.age(), profile.sleepNeedMinutes), log.size, padding,
                                onProfile = { open(Page.Profile) },
                                onGoals = { editGoals = true },
                                healthConnectAuto = hcAuto,
                                onHealthConnect = { open(Page.HealthConnect) },
                                backup = backup,
                                onBackup = { open(Page.Backup) },
                                updates = updates,
                                installedVersion = container.updater.installed,
                                updateWaiting = updateWaiting,
                                onUpdates = { open(Page.Updates) },
                                onDiagnostics = { open(Page.Diagnostics) },
                                onSampleData = if (org.openstrap.app.data.SampleData.AVAILABLE) ({ deviceVm.loadSampleData() }) else null,
                                onBack = onBack,
                                selected = selected?.row,
                            )
                        },
                        dataPage = { shown, onBack ->
                            when (shown) {
                                Page.HealthConnect -> {
                                    val hcAuto by deviceVm.healthConnectAuto.collectAsStateWithLifecycle()
                                    // As on the You page: the switch says so when permissions were taken back meanwhile.
                                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { deviceVm.checkAutoHealthConnect(); onPauseOrDispose { } }
                                    org.openstrap.app.ui.device.HealthConnectPage(
                                        hcAuto, padding,
                                        onAuto = autoHealthConnect,
                                        onCopyNow = exportHealthConnect,
                                        onRestore = importHealthConnect,
                                        onOpen = {
                                            if (!org.openstrap.app.ui.device.openHealthConnect(context)) {
                                                Toast.makeText(context, tr(R.string.main_activity_health_connect_isn_t), Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        onBack = onBack,
                                    )
                                }
                                Page.Backup -> {
                                    val backup by deviceVm.backup.collectAsStateWithLifecycle()
                                    val backingUp by deviceVm.backingUp.collectAsStateWithLifecycle()
                                    val folder = backup.folder?.let(android.net.Uri::parse)
                                    org.openstrap.app.ui.device.BackupPage(
                                        backup, backingUp, padding,
                                        onTurnOn = { backupFolderLauncher.launch(org.openstrap.app.ui.device.BackupCopy.DOCUMENTS) },
                                        onTurnOff = { deviceVm.turnOffBackup() },
                                        onFolder = { backupFolderLauncher.launch(folder) },
                                        onBackUpNow = { deviceVm.backUpNow() },
                                        onRestore = { backupRestoreLauncher.launch(folder) },
                                        onSaveCopy = { csvLauncher.launch("openstrap-export.zip") },
                                        onBack = onBack,
                                    )
                                }
                                Page.Updates -> {
                                    val updater = container.updater
                                    val updates by updater.state.collectAsStateWithLifecycle()
                                    val auto by container.settings.updateAuto.collectAsStateWithLifecycle(true)
                                    val workoutNow by container.workout.state.collectAsStateWithLifecycle()
                                    // Seen: the dots on the avatar and the Updates row go, and again for a release found while it's open.
                                    LaunchedEffect(org.openstrap.app.update.Releases.newer(updates.releases, updater.installed).firstOrNull()?.tag) { updater.markSeen() }
                                    // Back from Android's "Install unknown apps" switch: on, the install goes ahead.
                                    var waiting by remember { mutableStateOf<org.openstrap.app.update.Release?>(null) }
                                    val allowLauncher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
                                        waiting?.let { r -> if (updater.canInstall()) updater.install(r) }
                                        waiting = null
                                    }
                                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                                    org.openstrap.app.ui.device.UpdatesPage(
                                        updates, updater.installed, auto, workoutNow is org.openstrap.app.device.WorkoutState.Recording, padding,
                                        onInstall = { r ->
                                            when {
                                                r.apk == null -> uri.openUri(r.page)
                                                updater.canInstall() -> updater.install(r)
                                                else -> { waiting = r; allowLauncher.launch(updater.allowInstalls) }
                                            }
                                        },
                                        onCheck = updater::checkNow,
                                        onAuto = updater::setAuto,
                                        onGitHub = { uri.openUri(org.openstrap.app.update.Releases.PAGE) },
                                        onBack = { updater.dismissFailure(); onBack() },
                                    )
                                }
                                else -> Unit
                            }
                        },
                    )
                }
                }
            } }
        }
        if (rail) org.openstrap.app.ui.components.FloatingNavRail(
            navItems, tab, selectTab, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.CenterStart),
            glass = navGlass,
        )
        // A map, not a card: rows whose home is a tab go there, at its root and top (Charge to its card); the rest open
        // their detail on the tab showing. The day is shared, so the tab shows the same one. The sheet closes first.
        if (allMetricsOpen) org.openstrap.app.ui.glow.AllMetricsSheet(
            org.openstrap.app.ui.glow.metricIndex(day, recovery, healthspan, workouts, strapScore = strapReadiness?.takeIf { it.day == day.dayStart }?.score), day.date,
            onDismiss = { allMetricsOpen = false },
            onOpen = { target ->
                allMetricsOpen = false
                closePages()
                when (target) {
                    is org.openstrap.app.ui.glow.IndexTarget.Open ->
                        if (stackOf(tab).lastOrNull() != target.detail) setStack(tab, stackOf(tab) + target.detail)
                    is org.openstrap.app.ui.glow.IndexTarget.Home -> {
                        val home = when (target.tab) {
                            org.openstrap.app.ui.glow.HomeTab.Recovery -> Tab.Recovery
                            org.openstrap.app.ui.glow.HomeTab.Sleep -> Tab.Sleep
                        }.ordinal
                        setStack(home, emptyList())
                        nav = nav.select(home)
                        if (target.charge) showCharge = true else scrollToTop[home] = scrollToTop[home] + 1
                    }
                }
            },
        )
        addingOn?.let { date ->
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val series by androidx.compose.runtime.produceState<List<Pair<Long, Int>>?>(null, date) { value = workoutsVm.dayMinuteHeartRate(date) }
                val maxHr by workoutsVm.maxHeartRate.collectAsStateWithLifecycle()
                val (recent, sides) = pickerSports(container, workoutsVm)
                org.openstrap.app.ui.glow.GlowAddWorkoutScreen(
                    date, series, maxHr ?: 190, androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues(),
                    onSave = { from, to, sport -> container.workout.saveFromHistory(from, to, sport); addingOn = null },
                    onBack = { addingOn = null },
                    recentSports = recent, sides = sides,
                )
            }
        }
        savingSuggestion?.let { s ->
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val (recent, sides) = pickerSports(container, workoutsVm)
                org.openstrap.app.ui.glow.GlowSportPickerScreen(
                    androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues(),
                    onPick = { sport -> container.workout.saveFromHistory(s.start, s.end, sport); savingSuggestion = null },
                    onBack = { savingSuggestion = null },
                    recentSports = recent, sides = sides,
                )
            }
        }
        WorkoutLayer(
            container, workoutState, workoutMinimized, readySport, readyPicking, live, realConnection, workoutsVm, requestBluetooth, requestLocation,
            onReadySport = { readySport = it },
            onCloseReady = { readySport = null; readyPicking = false },
            capsuleBottom = if (showBar) 104.dp else 16.dp,
            tabletop = hinge != null, bookHinge = bookHinge,
        )
        org.openstrap.app.ui.components.GlassOverlay(glassHost, navGlass)
      }
      }
    }
}

/**
 * The top page over the tab, sliding in and out like a stack; each closes back to the one under it. On a wide screen
 * (spec 2.4 Unfolded) the You and Strap pages are a list with the page a row opens beside it, as a tab with its
 * detail: the list on the left with that row marked, the page in a pane on the right ([openBeside] puts it there,
 * in place of the one open). Back closes the pane, or goes back within it; the list's own back closes it all
 * ([closeAll]). A page opened on its own (Profile from a card) is one centred column.
 */
@Composable
private fun OverPages(
    pages: List<Page>,
    container: AppContainer,
    deviceVm: DeviceViewModel,
    firmwareVm: FirmwareViewModel,
    profile: org.openstrap.app.data.settings.UserProfile,
    padding: androidx.compose.foundation.layout.PaddingValues,
    requestBluetooth: (() -> Unit) -> Unit,
    lastSeen: Long?,
    /** Debug: a previewed strap state for the Strap page's card. */
    preview: StrapView?,
    open: (Page) -> Unit,
    close: () -> Unit,
    openBeside: (Page) -> Unit,
    closeAll: () -> Unit,
    /** The You page, built where its launchers and the Goals editor live: how its rows open, the row marked, its back. */
    youPage: @Composable (open: (Page) -> Unit, selected: Page?, onBack: () -> Unit) -> Unit,
    /** The Health Connect and Backup pages, built there too for the same reason: the page shown and its back. */
    dataPage: @Composable (shown: Page, onBack: () -> Unit) -> Unit,
) {
    // Read from the strap when first needed (the Strap page's rows show what it holds), and again on each page that edits it.
    val needsAlarms = pages.any { it == Page.Strap || it == Page.Alarms || it == Page.HealthAlerts }
    val alarmsVm: org.openstrap.app.ui.alarms.AlarmsViewModel? = if (needsAlarms) viewModel(
        key = "alarms", factory = viewModelFactory { initializer { org.openstrap.app.ui.alarms.AlarmsViewModel(container.strap) } },
    ) else null
    // One page. [list] is set for the You or Strap page as the list beside a pane: its rows open there, the row whose
    // page is open is marked, and its back closes everything.
    val page: @Composable (Page?, List<Page>?) -> Unit = page@{ shown, list ->
        val rowOpens: (Page) -> Unit = if (list != null) openBeside else open
        val selected = list?.getOrNull(1)
        val back = if (list != null) closeAll else close
        when (shown) {
            null -> Unit
            Page.You -> youPage(rowOpens, selected, back)
            Page.Strap -> {
                val alarms = alarmsVm ?: return@page
                val alarmsState by alarms.state.collectAsStateWithLifecycle()
                val account by firmwareVm.account.collectAsStateWithLifecycle()
                val connection by container.strap.connection.collectAsStateWithLifecycle()
                // Nothing read yet (the strap wasn't there when it was asked): ask again once it's connected.
                LaunchedEffect(connection is ConnectionState.Connected) {
                    if (connection is ConnectionState.Connected && alarmsState.caps == null && !alarmsState.busy) alarms.refresh()
                }
                DeviceScreen(
                    deviceVm, padding, requestBluetooth, container.settings, alarmsState, account != null, lastSeen,
                    onAlarms = { rowOpens(Page.Alarms) },
                    onPhoneAlerts = { rowOpens(Page.PhoneAlerts) },
                    onHealthAlerts = { rowOpens(Page.HealthAlerts) },
                    onFirmware = { rowOpens(Page.Firmware) },
                    onZeppAccount = { rowOpens(Page.ZeppAccount) },
                    onBack = back,
                    preview = preview,
                    selected = selected?.row,
                )
            }
            Page.Alarms -> {
                val alarms = alarmsVm ?: return@page
                LaunchedEffect(Unit) { alarms.refresh() }
                val alarmsState by alarms.state.collectAsStateWithLifecycle()
                org.openstrap.app.ui.alarms.AlarmsScreen(alarms, alarmsState, padding, onBack = close)
            }
            Page.HealthAlerts -> {
                val alarms = alarmsVm ?: return@page
                LaunchedEffect(Unit) { alarms.refresh() }
                val alarmsState by alarms.state.collectAsStateWithLifecycle()
                org.openstrap.app.ui.alarms.HealthAlertsScreen(alarms, alarmsState, padding, onBack = close)
            }
            Page.PhoneAlerts -> org.openstrap.app.ui.alarms.PhoneAlertsScreen(container.phoneAlerts, container.settings, padding, onBack = close)
            Page.Firmware -> FirmwareScreen(firmwareVm, padding, onBack = close, onZeppAccount = { open(Page.ZeppAccount) })
            Page.ZeppAccount -> org.openstrap.app.ui.firmware.ZeppAccountScreen(firmwareVm, padding, onBack = close)
            Page.HealthConnect, Page.Backup, Page.Updates -> dataPage(shown, close)
            Page.Profile -> org.openstrap.app.ui.device.ProfileScreen(profile, padding, onSave = { deviceVm.saveProfile(it); close() }, onBack = close)
            Page.Diagnostics -> {
                val log by container.strap.log.collectAsStateWithLifecycle()
                org.openstrap.app.ui.device.DiagnosticsScreen(log, padding, onBack = close)
            }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
        val root = pages.firstOrNull()
        if (maxWidth >= org.openstrap.app.ui.components.SidePaneWidth && (root == Page.You || root == Page.Strap)) {
            ListDetail(
                pages.drop(1), isOpen = { it.isNotEmpty() }, keys = { s -> s.map { it.name } },
                minWidth = org.openstrap.app.ui.components.SidePaneWidth,
                closedMaxWidth = org.openstrap.app.ui.glow.GlowLayout.PageMaxWidth,
                detail = { stack -> page(stack.lastOrNull(), null) },
            ) { page(root, pages) }
        } else org.openstrap.app.ui.components.PushContent(pages, depth = { it.size }) { stack -> page(stack.lastOrNull(), null) }
    }
}

/**
 * What the sport picker needs (spec 6.3): your last four sports for its Recent row, newest first and only those you
 * started, and the Outdoor or Indoor side last chosen per sport on Ready.
 */
@Composable
private fun pickerSports(container: AppContainer, workoutsVm: org.openstrap.app.ui.workout.WorkoutsViewModel): Pair<List<Int>, Map<Int, Boolean>> {
    val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
    val sides by container.settings.workoutSides.collectAsStateWithLifecycle(emptyMap())
    val recent = remember(workouts) {
        org.openstrap.app.data.Workouts.recentSports(workouts.filter { it.startedByYou }.sortedByDescending { it.workout.start }.map { it.workout.sportType })
    }
    return recent to sides
}

/**
 * The workout screens over the whole app: the ready screen, the live screen (or, tucked away, a capsule above the tab
 * bar) and the summary once it ends.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.WorkoutLayer(
    container: AppContainer,
    state: org.openstrap.app.device.WorkoutState,
    minimized: Boolean,
    readySport: Int?,
    readyPicking: Boolean,
    live: Int?,
    connection: ConnectionState,
    workoutsVm: org.openstrap.app.ui.workout.WorkoutsViewModel,
    requestBluetooth: (() -> Unit) -> Unit,
    requestLocation: (() -> Unit) -> Unit,
    onReadySport: (Int) -> Unit,
    onCloseReady: () -> Unit,
    capsuleBottom: androidx.compose.ui.unit.Dp,
    tabletop: Boolean = false,
    bookHinge: androidx.window.layout.FoldingFeature? = null,
) {
    val session = container.workout
    // Read here, ahead of a workout, so the live screen usually opens on the right view at once.
    val liveView by container.settings.workoutLiveView.collectAsStateWithLifecycle(null)
    val kcal by session.kcal.collectAsStateWithLifecycle()
    val profile by container.settings.profile.collectAsStateWithLifecycle(null)
    val viewScope = rememberCoroutineScope()
    val padding = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues()
    val full = androidx.compose.ui.Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    when {
        // Minimizing morphs the live screen into the capsule above the tab bar, and tapping the capsule back.
        state is org.openstrap.app.device.WorkoutState.Recording -> org.openstrap.app.ui.glow.WorkoutMorph(
            minimized, capsuleBottom + padding.calculateBottomPadding(),
            full = {
                val recent by session.recent.collectAsStateWithLifecycle()
                val stats by session.stats.collectAsStateWithLifecycle()
                val zoneSeconds by session.zoneSeconds.collectAsStateWithLifecycle()
                val route by session.route.collectAsStateWithLifecycle()
                val history by session.history.collectAsStateWithLifecycle()
                // Folded, the heart card has the standing half: above the fold (drawn with the tabletop heart) or,
                // stood up like a book, the left half here.
                val liveScreen: @Composable (androidx.compose.ui.Modifier) -> Unit = { modifier ->
                    androidx.compose.foundation.layout.Box(modifier) {
                        org.openstrap.app.ui.glow.GlowWorkoutLiveScreen(
                            state, live, recent, stats, zoneSeconds, connection, padding,
                            onPause = session::pause, onResume = session::resume, onEnd = session::end,
                            onMinimize = { session.minimized.value = true },
                            onEndAtPrompt = session::endAtPrompt, onKeepGoing = session::keepGoing,
                            route = route, history = history,
                            onStartSet = session::startSet, onSetDone = session::setDone, onExtendRest = session::extendRest,
                            onLap = session::lap,
                            split = tabletop || bookHinge != null,
                            liveView = liveView, onLiveView = { v -> viewScope.launch { container.settings.setWorkoutLiveView(v) } },
                            kcal = kcal,
                        )
                    }
                }
                if (bookHinge != null) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
                        org.openstrap.app.ui.glow.GlowWorkoutGlance(
                            state, live, recent, zoneSeconds, connection,
                            androidx.compose.ui.Modifier.width(with(density) { bookHinge.bounds.left.toDp() }).fillMaxHeight(), kcal,
                        )
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(with(density) { bookHinge.bounds.width().toDp() }))
                        liveScreen(androidx.compose.ui.Modifier.weight(1f).fillMaxHeight())
                    }
                } else liveScreen(androidx.compose.ui.Modifier.fillMaxSize())
            },
            capsule = { place ->
                org.openstrap.app.ui.glow.WorkoutCapsule(
                    state, live, connection is ConnectionState.Connected, onClick = { session.minimized.value = false },
                    onPause = session::pause, onResume = session::resume, modifier = place, kcal = kcal,
                )
            },
        )
        state is org.openstrap.app.device.WorkoutState.Ended -> androidx.compose.foundation.layout.Box(full) {
            // A debug preview of a stored workout writes nothing just by opening, so no effect is worked out for it.
            val summary by remember(state) { workoutsVm.phoneSummary(state.start, backfill = !state.preview) }.collectAsStateWithLifecycle(null)
            val calmDown by session.calmDown.collectAsStateWithLifecycle()
            val route by session.route.collectAsStateWithLifecycle()
            val workouts by workoutsVm.workouts.collectAsStateWithLifecycle()
            val usualDrop by produceState<Int?>(null, state.start) { value = workoutsVm.usualDrop(state.start) }
            GlowWorkoutSummaryScreen(
                summary, calmDown.takeIf { !state.preview }, if (state.preview) emptyList() else route, workouts, usualDrop, padding,
                onDone = session::dismissSummary, onDelete = { session.discard(state.start) },
                onTrim = { from, to -> session.trim(state.start, from, to) }, onUndoTrim = { session.undoTrim(state.start) },
            )
        }
    }
    // The ready screen comes and goes like any other screen; a workout starting replaces it at once.
    val recording = state is org.openstrap.app.device.WorkoutState.Recording || state is org.openstrap.app.device.WorkoutState.Ended
    org.openstrap.app.ui.glow.PushOverlay(readySport.takeIf { !recording }, cut = recording) { sport ->
        androidx.compose.foundation.layout.Box(full) {
            val (recentSports, sides) = pickerSports(container, workoutsVm)
            org.openstrap.app.ui.glow.GlowWorkoutReadyScreen(
                sport, readyPicking, connection, live, padding,
                recentSports = recentSports, sides = sides,
                kcalTargets = profile?.let { org.openstrap.app.data.EnergyExpenditure.bmrPerMinute(it, it.age()) } != null,
                onSide = { s, indoor ->
                    viewScope.launch {
                        container.settings.setWorkoutSide(s, indoor)
                        // The Start widget names the side Ready opens on and says whether GPS is on (widgets spec 7).
                        org.openstrap.app.device.WidgetUpdater.changed(container.app, setOf(org.openstrap.app.widget.WidgetKind.Start))
                    }
                },
                onTurnOnLocation = requestLocation,
                onSport = onReadySport,
                onStart = { sport, askToEnd, target, useGps, keepRoute, rest, setsTarget ->
                    val start = { session.start(sport, askToEnd, target, useGps, keepRoute, rest, setsTarget); onCloseReady() }
                    val granted = container.app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val withBluetooth = { if (granted) start() else requestBluetooth(start) }
                    if (useGps && !org.openstrap.app.device.WorkoutLocation.granted(container.app)) requestLocation(withBluetooth) else withBluetooth()
                },
                onBack = onCloseReady,
            )
        }
    }
}

/** A metric's detail. */
@androidx.compose.runtime.Composable
private fun MetricDetail(
    metric: org.openstrap.app.ui.dashboard.Metric,
    night: Boolean,
    state: org.openstrap.app.ui.dashboard.DayUiState,
    history: org.openstrap.app.ui.dashboard.DailyHistory,
    liveHeartRate: Int?,
    liveEnabled: Boolean,
    canUseLive: Boolean,
    onLiveToggle: (Boolean) -> Unit,
    onOpen: (Detail) -> Unit,
    onSwitch: (Detail) -> Unit,
    onSelectDay: (java.time.LocalDate) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onBack: () -> Unit,
) = org.openstrap.app.ui.glow.GlowMetricDetailScreen(metric, night, state, history, liveHeartRate, liveEnabled, canUseLive, onLiveToggle, onOpen, onSwitch, onSelectDay, contentPadding, onBack)

/** A workout's detail: one page for the strap's and the phone's ([org.openstrap.app.ui.glow.GlowWorkoutDetailScreen]). */
@androidx.compose.runtime.Composable
private fun WorkoutDetail(
    a: org.openstrap.app.data.WorkoutAnalysis?,
    workouts: List<org.openstrap.app.data.WorkoutAnalysis>,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    workoutsVm: org.openstrap.app.ui.workout.WorkoutsViewModel,
    session: org.openstrap.app.device.WorkoutSession,
    bmrPerMinute: Float?,
    recovery: org.openstrap.app.data.RecoveryResult?,
    energyHistory: org.openstrap.app.ui.glow.EnergyHistory,
    onOpenDay: (java.time.LocalDate) -> Unit,
) {
    // OpenStrap's own recordings also get their summary (route, laps, sets, the recording for trim) and are deleted
    // through the session; the strap's are deleted and remembered, so the next sync doesn't bring them back.
    val start = a?.workout?.start
    val fromPhone = a?.fromPhone == true
    val summary by remember(start, fromPhone) {
        if (start != null && fromPhone) workoutsVm.phoneSummary(start) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val usualDrop by produceState<Int?>(null, start, fromPhone) { value = start?.takeIf { !fromPhone }?.let { workoutsVm.usualDrop(it) } }
    // The workout's day: its active kcal as just worked out when it's the day shown, else as stored.
    val date = a?.let { java.time.Instant.ofEpochSecond(it.shownStart).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
    val stored by remember(date, energyHistory) {
        date?.let { d -> energyHistory(d, d) } ?: kotlinx.coroutines.flow.flowOf(emptyMap())
    }.collectAsStateWithLifecycle(emptyMap())
    val dayActive = recovery?.takeIf { it.date == date }?.energy?.activeKcal ?: date?.let { stored[it]?.activeKcal }
    org.openstrap.app.ui.glow.GlowWorkoutDetailScreen(
        a, summary, workouts, usualDrop, contentPadding, onBack,
        onDelete = if (fromPhone) onDelete else ({ start?.let(workoutsVm::deleteStrapWorkout); onBack() }),
        onTrim = { from, to -> start?.let { session.trim(it, from, to) } }, onUndoTrim = { start?.let(session::undoTrim) },
        bmrPerMinute = bmrPerMinute, dayActiveKcal = dayActive, onOpenDay = date?.let { d -> { onOpenDay(d) } },
    )
}
