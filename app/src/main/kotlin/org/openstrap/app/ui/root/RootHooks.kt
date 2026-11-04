package org.openstrap.app.ui.root

import org.openstrap.app.AppContainer
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.device.DeviceViewModel
import org.openstrap.app.ui.firmware.FirmwareViewModel
import org.openstrap.app.ui.workout.WorkoutsViewModel
import java.time.LocalDate

/** What applying a request asks of the app beyond where it is. */
internal class NavRequestHooks(
    /** A workout records now: requests for Choose and Ready wait. */
    val recording: () -> Boolean,
    val firmwareInstalling: () -> Boolean,
    /** Firmware left the pages, so it starts again from the top next time. */
    val firmwareClosed: () -> Unit,
    /** A notification's metric is for this day. */
    val selectDay: (LocalDate) -> Unit,
    /** The Moment widget: Today on today. */
    val today: () -> Unit,
    /** The Strap page opened over the tab: it grows as the header's strap chip opens it. */
    val strapOpened: () -> Unit,
    /** The live workout screen, even when it was tucked away. */
    val showWorkout: () -> Unit,
)

/** Everything the pages over the tab act with: the view models they show and the actions that need the activity. */
internal class PageHooks(
    val container: AppContainer,
    val dayVm: DayViewModel,
    val deviceVm: DeviceViewModel,
    val firmwareVm: FirmwareViewModel,
    val workoutsVm: WorkoutsViewModel,
    val rootVm: AppRootViewModel,
    val healthConnect: HealthConnectActions,
    val backup: BackupActions,
)
