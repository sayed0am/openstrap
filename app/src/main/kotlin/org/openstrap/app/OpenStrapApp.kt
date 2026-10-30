package org.openstrap.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.openstrap.app.ble.BleScanner
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.CompanionLink
import org.openstrap.app.device.PhoneAlerts
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.StrapManager
import org.openstrap.app.export.CsvExporter
import org.openstrap.app.health.HealthConnectExporter

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppContainer(val app: Application) {
    val database = OpenStrapDatabase.create(app)
    val settings = SettingsStore(app)
    val health = HealthRepository(database.healthDao())
    val scanner = BleScanner(app)
    val csv = CsvExporter(database.healthDao())
    val healthConnect = HealthConnectExporter(app, database.healthDao())
    val strap: StrapController = StrapManager(app, settings, database.healthDao())
    val companion = CompanionLink(app, settings)
    val phoneAlerts = PhoneAlerts(app, settings, strap)
    val autoHealthConnect = org.openstrap.app.health.HealthConnectAutoSync(app, settings, healthConnect, strap)
    val workout = org.openstrap.app.device.WorkoutSession(
        app, database.healthDao(), settings, strap, callRinging = { phoneAlerts.callRinging }, onSaved = autoHealthConnect::workoutSaved,
    )
    val updater = org.openstrap.app.update.AppUpdater(app, settings, recording = { workout.state.value is org.openstrap.app.device.WorkoutState.Recording })
}

class OpenStrapApp : Application(), androidx.work.Configuration.Provider {
    lateinit var container: AppContainer
        private set

    /** WorkManager as Glance's widget sessions need it: rendering on their own thread ([WidgetWork]). */
    override fun getWorkManagerConfiguration(): androidx.work.Configuration =
        androidx.work.Configuration.Builder().setWorkerFactory(org.openstrap.app.widget.WidgetWork).build()

    /** The words before anything else runs: content providers start before [onCreate]. */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        Texts.install(base)
    }

    override fun onCreate() {
        super.onCreate()
        Texts.install(this)
        container = AppContainer(this)
        container.companion.refresh()
        // A workout still recording when the process died carries on (the service restarts it too).
        container.workout.restore()
        // Android restarts the process on its own (companion presence, a reboot): reconnect without the screen.
        // A no-op when nothing is paired, a connection is already up, or the user tapped Disconnect.
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) container.strap.backgroundConnect()
        // Widgets turn the day over at midnight; alarms don't survive a reboot or an update, so set it on each start.
        org.openstrap.app.device.WidgetUpdater.scheduleMidnight(this)
        // Android 15 and later: the widget picker's previews, from the real layouts (spec 6.4).
        org.openstrap.app.device.WidgetUpdater.publishPreviews(this)
        // The daily check for a new OpenStrap, unless it was turned off on the Updates page.
        container.updater.start()
    }
}
