package org.openstrap.app.ui.root

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow
import org.openstrap.app.AppContainer
import org.openstrap.app.data.Today
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.ui.alarms.AlarmsViewModel
import org.openstrap.app.ui.dashboard.DayViewModel
import org.openstrap.app.ui.device.DeviceViewModel
import org.openstrap.app.ui.firmware.FirmwareViewModel
import org.openstrap.app.ui.workout.WorkoutsViewModel

/**
 * How each of the app's view models is made from the container, in one place, so the app root and the pages that
 * share a view model with it can't build it two ways.
 */
internal object ViewModelFactories {
    /** The day shown, which moves on to the new day as soon as the phone's date, time or time zone changes. */
    fun day(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            DayViewModel(
                c.health, c.strap.todaySteps, c.settings.profile, c.autoHealthConnect::sleepEdited, dateChanges = Today.changes(c.app),
            )
        }
    }

    fun workouts(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer { WorkoutsViewModel(c.database.healthDao(), c.settings.profile, c.maxHeartRates, dateChanges = Today.changes(c.app)) }
    }

    /** [workouts] is the workouts view model's list: both live as long as the activity, so the list is worked out once. */
    fun root(c: AppContainer, workouts: StateFlow<List<WorkoutAnalysis>>): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AppRootViewModel(
                c.settings, c.health, c.database.healthDao(), c.strap, c.workout, c.rewards, c.healthConnect, c.updater, workouts,
            )
        }
    }

    fun device(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { DeviceViewModel(c) } }

    fun firmware(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { FirmwareViewModel(c) } }

    fun alarms(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { AlarmsViewModel(c.strap) } }
}
