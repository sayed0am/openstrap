// The pages over the tab read the device, firmware, day and workouts view models they show, as they did inside AppRoot.
// Their keyed effects run once per key after the frame commits, as they did there. A SideEffect would run on every
// recomposition.
@file:Suppress("ViewModelForwarding", "ComposeViewModelForwarding", "UnnecessaryLaunchedEffect")

package org.openstrap.app.ui.root

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.openstrap.app.AppContainer
import org.openstrap.app.PendingAction
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SampleData
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.data.sleepNeed
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.alarms.AlarmsScreen
import org.openstrap.app.ui.alarms.AlarmsViewModel
import org.openstrap.app.ui.alarms.HealthAlertsScreen
import org.openstrap.app.ui.alarms.PhoneAlertsScreen
import org.openstrap.app.ui.components.GrowFromButton
import org.openstrap.app.ui.components.GrowProgress
import org.openstrap.app.ui.components.ListDetail
import org.openstrap.app.ui.components.NavRailWidth
import org.openstrap.app.ui.components.OledSurfaces
import org.openstrap.app.ui.components.PushContent
import org.openstrap.app.ui.components.SidePaneWidth
import org.openstrap.app.ui.dashboard.StrapView
import org.openstrap.app.ui.device.BackupPage
import org.openstrap.app.ui.device.DeletedSleepPage
import org.openstrap.app.ui.device.DeviceScreen
import org.openstrap.app.ui.device.DiagnosticsScreen
import org.openstrap.app.ui.device.HealthConnectPage
import org.openstrap.app.ui.device.ProfileScreen
import org.openstrap.app.ui.device.UpdatesPage
import org.openstrap.app.ui.device.YouPage
import org.openstrap.app.ui.device.openHealthConnect
import org.openstrap.app.ui.firmware.FirmwareScreen
import org.openstrap.app.ui.firmware.ZeppAccountScreen
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.update.Releases

/**
 * The pages over the tab: the You page and the Strap page, and what they open. They grow out of the button that
 * opened them and shrink back into it, drawn as they were while they go, and meanwhile back and taps belong to the tab
 * again.
 */
@Composable
internal fun PagesOverTab(
    nav: AppNavState,
    hooks: PageHooks,
    requestBluetooth: (() -> Unit) -> Unit,
    strap: StrapHeader,
    previewing: Boolean,
    padding: PaddingValues,
    rail: Boolean,
    growFrom: Rect?,
    pageGrow: GrowProgress,
    navGlass: HazeState,
    idleBack: NavigationEventDispatcherOwner,
    openPage: (Page) -> Unit,
    closePages: () -> Boolean,
    firmwareInstalling: () -> Boolean,
) {
    val keptPages = remember { arrayOf(nav.pages) }
    if (nav.pages.isNotEmpty()) keptPages[0] = nav.pages
    val tabBack = LocalNavigationEventDispatcherOwner.current
    val profile by hooks.deviceVm.profile.collectAsStateWithLifecycle()
    GrowFromButton(
        nav.pages.isNotEmpty(), growFrom,
        Modifier.fillMaxSize().padding(start = if (rail) NavRailWidth else 0.dp),
        progress = pageGrow,
    ) { closing ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).hazeSource(navGlass, zIndex = 1f)) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides (if (closing || tabBack == null) idleBack else tabBack)) {
                OledSurfaces {
                    OverPages(
                        nav.pages.ifEmpty { keptPages[0] }, hooks, requestBluetooth, profile, padding, strap.view.lastSeen,
                        strap.view.takeIf { previewing },
                        open = openPage, close = nav::closePage,
                        // A row on a wide screen's list: its page beside the list, in place of the one there, unless
                        // an update is being installed there.
                        openBeside = { p -> nav.openBeside(p, firmwareInstalling()) },
                        closeAll = { closePages() },
                        onGoals = { nav.setEditGoals(true) },
                    )
                }
            }
        }
    }
}

/**
 * The top page over the tab, sliding in and out like a stack, each closing back to the one under it. On a wide screen
 * (spec 2.4 Unfolded) the You and Strap pages are a list with the page a row opens beside it, as a tab with its
 * detail: the list on the left with that row marked, the page in a pane on the right ([openBeside] puts it there, in
 * place of the one open). Back closes the pane, or goes back within it, and the list's own back closes it all
 * ([closeAll]). A page opened on its own (Profile from a card) is one centred column.
 */
// The stack of pages is a new list on each change and is read whole, so its stability changes nothing here.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
private fun OverPages(
    pages: List<Page>,
    hooks: PageHooks,
    requestBluetooth: (() -> Unit) -> Unit,
    profile: UserProfile,
    padding: PaddingValues,
    lastSeen: Long?,
    /** Debug: a previewed strap state for the Strap page's card. */
    preview: StrapView?,
    open: (Page) -> Unit,
    close: () -> Unit,
    openBeside: (Page) -> Unit,
    closeAll: () -> Unit,
    onGoals: () -> Unit,
) {
    val container = hooks.container
    // Read from the strap when first needed (the Strap page's rows show what it holds), and again on each page that edits it.
    val needsAlarms = pages.any { it == Page.Strap || it == Page.Alarms || it == Page.HealthAlerts }
    val alarmsVm: AlarmsViewModel? = if (needsAlarms) viewModel(key = "alarms", factory = ViewModelFactories.alarms(container)) else null
    // One page. [list] is set for the You or Strap page as the list beside a pane: its rows open there, the row whose
    // page is open is marked, and its back closes everything.
    val page: @Composable (Page?, List<Page>?) -> Unit = page@{ shown, list ->
        val rowOpens: (Page) -> Unit = if (list != null) openBeside else open
        val selected = list?.getOrNull(1)
        val back = if (list != null) closeAll else close
        when (shown) {
            null -> Unit
            Page.You -> YouPageHost(hooks, profile, padding, rowOpens, selected, onGoals, back)
            Page.Strap, Page.Alarms, Page.HealthAlerts, Page.PhoneAlerts, Page.Firmware, Page.ZeppAccount -> {
                StrapPage(shown, hooks, alarmsVm, requestBluetooth, padding, lastSeen, preview, rowOpens, selected, back, open, close)
            }
            else -> DataPage(shown, hooks, profile, padding, open, close)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val root = pages.firstOrNull()
        if (maxWidth >= SidePaneWidth && (root == Page.You || root == Page.Strap)) {
            ListDetail(
                pages.drop(1), isOpen = { it.isNotEmpty() }, keys = { s -> s.map { it.name } },
                minWidth = SidePaneWidth,
                closedMaxWidth = GlowLayout.PageMaxWidth,
                detail = { stack -> page(stack.lastOrNull(), null) },
            ) { page(root, pages) }
        } else {
            PushContent(pages, depth = { it.size }) { stack -> page(stack.lastOrNull(), null) }
        }
    }
}

/** The Strap page and what it opens: alarms, health alerts, phone alerts, firmware and the Zepp account. */
@Composable
private fun StrapPage(
    shown: Page,
    hooks: PageHooks,
    alarmsVm: AlarmsViewModel?,
    requestBluetooth: (() -> Unit) -> Unit,
    padding: PaddingValues,
    lastSeen: Long?,
    preview: StrapView?,
    rowOpens: (Page) -> Unit,
    selected: Page?,
    back: () -> Unit,
    open: (Page) -> Unit,
    close: () -> Unit,
) {
    val container = hooks.container
    when (shown) {
        Page.Strap -> alarmsVm?.let { StrapPageHost(hooks, it, requestBluetooth, padding, lastSeen, preview, rowOpens, selected, back) }
        Page.Alarms, Page.HealthAlerts -> alarmsVm?.let { AlarmsPage(it, health = shown == Page.HealthAlerts, padding, close) }
        Page.PhoneAlerts -> PhoneAlertsScreen(
            viewModel(key = "phoneAlerts", factory = ViewModelFactories.phoneAlerts(container)), padding, onBack = close,
        )
        Page.Firmware -> FirmwareScreen(hooks.firmwareVm, padding, onBack = close, onZeppAccount = { open(Page.ZeppAccount) })
        Page.ZeppAccount -> ZeppAccountScreen(hooks.firmwareVm, padding, onBack = close)
        else -> Unit
    }
}

/** The You page's other pages: your data, the app's updates, goals and badges, profile, zones and diagnostics. */
@Composable
private fun DataPage(
    shown: Page,
    hooks: PageHooks,
    profile: UserProfile,
    padding: PaddingValues,
    open: (Page) -> Unit,
    close: () -> Unit,
) {
    val container = hooks.container
    when (shown) {
        Page.HealthConnect -> HealthConnectPageHost(hooks, padding, close)
        Page.Backup -> BackupPageHost(hooks, padding, close)
        Page.Updates -> UpdatesPageHost(container, hooks.rootVm, padding, close)
        Page.DeletedSleep -> {
            val deleted by hooks.dayVm.deletedSleep.collectAsStateWithLifecycle()
            DeletedSleepPage(deleted, padding, onRestore = hooks.dayVm::restoreSleep, onBack = close)
        }
        Page.GoalsAndBests, Page.AllBadges, Page.WeeklyRecap ->
            RewardsPage(shown, hooks.rootVm, container.rewards.firstDay, padding, open, close)
        Page.Profile -> ProfileScreen(
            profile, padding, onSave = { hooks.deviceVm.saveProfile(it); close() }, onBack = close,
            onHeartZones = { open(Page.HeartZones) },
        )
        Page.HeartZones -> HeartZonesPage(container, padding, hooks.rootVm::setMaxHeartRate, hooks.rootVm::setHeartZones, close)
        Page.Diagnostics -> {
            val log by container.strap.log.collectAsStateWithLifecycle()
            DiagnosticsScreen(log, padding, onBack = close)
        }
        else -> Unit
    }
}

/** The alarms, or the health alerts, read from the strap afresh as the page opens. */
@Composable
private fun AlarmsPage(alarms: AlarmsViewModel, health: Boolean, padding: PaddingValues, close: () -> Unit) {
    LaunchedEffect(Unit) { alarms.refresh() }
    val alarmsState by alarms.state.collectAsStateWithLifecycle()
    if (health) {
        HealthAlertsScreen(alarms, alarmsState, padding, onBack = close)
    } else {
        AlarmsScreen(alarms, alarmsState, padding, onBack = close)
    }
}

/** The You page: who you are, your goals, your data and the app. */
@Composable
private fun YouPageHost(
    hooks: PageHooks,
    profile: UserProfile,
    padding: PaddingValues,
    open: (Page) -> Unit,
    selected: Page?,
    onGoals: () -> Unit,
    onBack: () -> Unit,
) {
    val container = hooks.container
    val deviceVm = hooks.deviceVm
    val goals by hooks.rootVm.goals.collectAsStateWithLifecycle()
    val log by container.strap.log.collectAsStateWithLifecycle()
    val hcAuto by deviceVm.healthConnectAuto.collectAsStateWithLifecycle()
    val backup by deviceVm.backup.collectAsStateWithLifecycle()
    val updates by container.updater.state.collectAsStateWithLifecycle()
    val updateWaiting by hooks.rootVm.updateWaiting.collectAsStateWithLifecycle()
    val deletedSleep by hooks.dayVm.deletedSleep.collectAsStateWithLifecycle()
    val youMaxHr by hooks.workoutsVm.maxHeartRate.collectAsStateWithLifecycle()
    // Permissions can be taken back in Health Connect while away: the row says so on return.
    LifecycleResumeEffect(Unit) {
        deviceVm.checkAutoHealthConnect()
        onPauseOrDispose { }
    }
    YouPage(
        profile, goals, Recovery.sleepNeed(profile.age(), profile.sleepNeedMinutes), log.size, padding,
        onProfile = { open(Page.Profile) },
        maxHeartRate = youMaxHr,
        onHeartZones = { open(Page.HeartZones) },
        onGoals = onGoals,
        onGoalsAndBests = { open(Page.GoalsAndBests) },
        healthConnectAuto = hcAuto,
        onHealthConnect = { open(Page.HealthConnect) },
        backup = backup,
        onBackup = { open(Page.Backup) },
        deletedSleep = deletedSleep.size,
        onDeletedSleep = { open(Page.DeletedSleep) },
        updates = updates,
        installedVersion = container.updater.installed,
        updateWaiting = updateWaiting,
        onUpdates = { open(Page.Updates) },
        onDiagnostics = { open(Page.Diagnostics) },
        onSampleData = if (SampleData.AVAILABLE) ({ deviceVm.loadSampleData() }) else null,
        onBack = onBack,
        selected = selected?.row,
    )
}

/** The Strap page, with what the strap holds read once it's connected. */
@Composable
private fun StrapPageHost(
    hooks: PageHooks,
    alarms: AlarmsViewModel,
    requestBluetooth: (() -> Unit) -> Unit,
    padding: PaddingValues,
    lastSeen: Long?,
    preview: StrapView?,
    open: (Page) -> Unit,
    selected: Page?,
    onBack: () -> Unit,
) {
    val container = hooks.container
    val alarmsState by alarms.state.collectAsStateWithLifecycle()
    val account by hooks.firmwareVm.account.collectAsStateWithLifecycle()
    val connection by container.strap.connection.collectAsStateWithLifecycle()
    // Nothing read yet (the strap wasn't there when it was asked): ask again once it's connected.
    LaunchedEffect(connection is ConnectionState.Connected) {
        if (connection is ConnectionState.Connected && alarmsState.caps == null && !alarmsState.busy) alarms.refresh()
    }
    DeviceScreen(
        hooks.deviceVm, padding, requestBluetooth, alarmsState, account != null, lastSeen,
        onAlarms = { open(Page.Alarms) },
        onPhoneAlerts = { open(Page.PhoneAlerts) },
        onHealthAlerts = { open(Page.HealthAlerts) },
        onFirmware = { open(Page.Firmware) },
        onZeppAccount = { open(Page.ZeppAccount) },
        onBack = onBack,
        preview = preview,
        selected = selected?.row,
    )
}

@Composable
private fun HealthConnectPageHost(hooks: PageHooks, padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val deviceVm = hooks.deviceVm
    val hcAuto by deviceVm.healthConnectAuto.collectAsStateWithLifecycle()
    // As on the You page: the switch says so when permissions were taken back meanwhile.
    LifecycleResumeEffect(Unit) {
        deviceVm.checkAutoHealthConnect()
        onPauseOrDispose { }
    }
    HealthConnectPage(
        hcAuto, padding,
        onAuto = hooks.healthConnect.auto,
        onCopyNow = hooks.healthConnect.export,
        onRestore = hooks.healthConnect.import,
        onOpen = {
            if (!openHealthConnect(context)) Toast.makeText(context, HC_UNAVAILABLE, Toast.LENGTH_LONG).show()
        },
        onBack = onBack,
    )
}

@Composable
private fun BackupPageHost(hooks: PageHooks, padding: PaddingValues, onBack: () -> Unit) {
    val deviceVm = hooks.deviceVm
    val backup by deviceVm.backup.collectAsStateWithLifecycle()
    val backingUp by deviceVm.backingUp.collectAsStateWithLifecycle()
    val folder = backup.folder?.let(Uri::parse)
    BackupPage(
        backup, backingUp, padding,
        onTurnOn = hooks.backup.turnOn,
        onTurnOff = { deviceVm.turnOffBackup() },
        onFolder = { hooks.backup.chooseFolder(folder) },
        onBackUpNow = { deviceVm.backUpNow() },
        onRestore = { hooks.backup.restore(folder) },
        onSaveCopy = hooks.backup.saveCopy,
        onBack = onBack,
    )
}

/** The Updates page, with Android's "Install unknown apps" switch asked for when an install needs it. */
@Composable
private fun UpdatesPageHost(container: AppContainer, rootVm: AppRootViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val updater = container.updater
    val updates by updater.state.collectAsStateWithLifecycle()
    val auto by rootVm.updateAuto.collectAsStateWithLifecycle()
    val workoutNow by container.workout.state.collectAsStateWithLifecycle()
    // Seen: the dots on the avatar and the Updates row go, and again for a release found while it's open.
    LaunchedEffect(Releases.newer(updates.releases, updater.installed).firstOrNull()?.tag) { updater.markSeen() }
    // Back from Android's "Install unknown apps" switch: on, the install goes ahead. Kept by its tag, so it still
    // installs after the activity was recreated in Settings.
    var waiting by rememberSaveable(stateSaver = PendingAction.Saver) { mutableStateOf<PendingAction?>(null) }
    val allowLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        (waiting as? PendingAction.Install)?.let { install -> waiting = install.copy(answered = true) }
    }
    LaunchedEffect(waiting, updates.releases) {
        val install = (waiting as? PendingAction.Install)?.takeIf { it.answered } ?: return@LaunchedEffect
        val release = updates.releases.firstOrNull { it.tag == install.tag } ?: return@LaunchedEffect
        waiting = null
        if (updater.canInstall()) updater.install(release)
    }
    val uri = LocalUriHandler.current
    UpdatesPage(
        updates, updater.installed, auto, workoutNow is WorkoutState.Recording, padding,
        onInstall = { r ->
            when {
                r.apk == null -> uri.openUri(r.page)
                updater.canInstall() -> updater.install(r)
                else -> {
                    waiting = PendingAction.Install(r.tag)
                    allowLauncher.launch(updater.allowInstalls)
                }
            }
        },
        onCheck = updater::checkNow,
        onAuto = updater::setAuto,
        onGitHub = { uri.openUri(Releases.PAGE) },
        onBack = {
            updater.dismissFailure()
            onBack()
        },
    )
}
