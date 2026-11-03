package org.openstrap.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.openstrap.app.ble.BleScanner
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.BlePermissions
import org.openstrap.app.device.CompanionLink
import org.openstrap.app.device.PhoneAlerts
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.StrapManager
import org.openstrap.app.export.CsvExporter
import org.openstrap.app.health.HealthConnectExporter

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppContainer(val app: Application) {
    /**
     * The one scope for work that lives as long as the app. With a [SupervisorJob], an exception nobody caught goes to
     * the thread's default handler, which kills the process, so this handler logs it instead (audit C3). Classes get it
     * passed in rather than building their own.
     */
    // This is where the dispatcher is injected from, so there is nothing to inject it into.
    @Suppress("InjectDispatcher")
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("OpenStrap", "app scope: uncaught $e", e) },
    )
    val database = OpenStrapDatabase.create(app)
    val settings = SettingsStore(app)
    val health = HealthRepository(database.healthDao())
    /** The max heart rate every zone goes by: `maxHeartRates.current` to follow it, `maxHeartRates.now()` to read it once. */
    val maxHeartRates = org.openstrap.app.data.MaxHeartRates(database.healthDao(), settings.profile)
    val rewards = org.openstrap.app.data.RewardsKeeper(database.healthDao(), settings, maxHeartRates)
    val scanner = BleScanner(app)
    val csv = CsvExporter(database.healthDao())
    val healthConnect = HealthConnectExporter(app, database.healthDao())
    val strap: StrapController = StrapManager(app, settings, database.healthDao(), scope = appScope)
    val companion = CompanionLink(app, settings)
    val phoneAlerts = PhoneAlerts(app, settings, strap)
    val autoHealthConnect = org.openstrap.app.health.HealthConnectAutoSync(app, settings, healthConnect, strap, scope = appScope)
    val workout = org.openstrap.app.device.WorkoutSession(
        app, database.healthDao(), settings, strap, maxHeartRates, callRinging = { phoneAlerts.callRinging }, onSaved = autoHealthConnect::workoutSaved,
        scope = appScope,
    )
    val updater = org.openstrap.app.update.AppUpdater(
        app, settings, recording = { workout.state.value is org.openstrap.app.device.WorkoutState.Recording }, scope = appScope,
    )
    /** Save a copy, Restore and automatic backup's zip. What a restore couldn't do goes to Diagnostics. */
    val backups: org.openstrap.app.data.BackupRepository =
        org.openstrap.app.export.CsvBackups(app, database, settings, csv, log = { (strap as? StrapManager)?.log(it) })
    /**
     * Today's steps with the strap's live counter on top: one anchor, so Today and the widgets count alike (audit A5).
     * It follows the strap's readings to know the day each came on.
     */
    val liveSteps = org.openstrap.app.data.LiveSteps().also { it.follow(strap.todaySteps, appScope) }
    /** Each day as Today and the tabs show it, read and resolved. */
    val days: org.openstrap.app.data.DayRepository = org.openstrap.app.data.RoomDayRepository(health, liveSteps, strap.todaySteps)
    /** The workouts, analysed, for the lists, pages and summaries. */
    val workouts: org.openstrap.app.data.WorkoutRepository =
        org.openstrap.app.data.RoomWorkoutRepository(database.healthDao(), settings.profile, maxHeartRates, settings.heartZones)

    init {
        // The Recovery cache follows the data from here on.
        health.start(appScope, HealthRepository.recoveryInputs(database))
    }
    /** The home screen widgets' refreshes. Built last: it watches the strap, the workout and settings. */
    val widgets = org.openstrap.app.widget.WidgetUpdater(
        app, appScope, settings, strap, workout, health.sleepEdits(),
        org.openstrap.app.widget.WidgetSnapshot(
            health, database.healthDao(), settings, liveSteps,
            syncing = { strap.sync.value is org.openstrap.app.device.SyncState.Running },
            todaySteps = { strap.todaySteps.value },
            workoutState = { workout.state.value },
            location = { org.openstrap.app.device.locationAvailable(app) },
            placed = { org.openstrap.app.widget.placedWidgets(app) },
        ),
    )
}

class OpenStrapApp : Application(), androidx.work.Configuration.Provider {
    lateinit var container: AppContainer
        private set

    /** WorkManager as Glance's widget sessions need it: rendering on their own thread ([WidgetWork]). */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setWorkerFactory(org.openstrap.app.widget.WidgetWork).build()

    override fun onCreate() {
        super.onCreate()
        // The phone's 12 or 24 hour clock, before anything writes a time, and from then on as it changes.
        PhoneClock.follow(this)
        container = AppContainer(this)
        // Your heart rate zones as stored, for the workout and its notification, a restored backup's included.
        org.openstrap.app.device.HeartZonesInUse.follow(container.settings.heartZones, container.appScope)
        container.companion.refresh()
        // A workout still recording when the process died carries on (the service restarts it too).
        container.workout.restore()
        // Android restarts the process on its own (companion presence, a reboot): reconnect without the screen.
        // A no-op when nothing is paired, a connection is already up, or the user tapped Disconnect.
        if (BlePermissions.granted(this)) container.strap.backgroundConnect()
        // The widgets watch what they show from now on, turn the day over at midnight (alarms don't survive a reboot or
        // an update, so it's set on each start) and, on Android 15 and later, give the picker their previews (spec 6.4).
        container.widgets.start()
        // The daily check for a new OpenStrap, unless it was turned off on the Updates page.
        container.updater.start()
        // Looks for the first morning of the week, for the weekly recap (workouts spec 3.4).
        org.openstrap.app.device.WeeklyRecap.schedule(this)
    }
}
