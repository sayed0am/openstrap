package org.openstrap.app.device

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.openstrap.app.MainActivity
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.Workouts
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * The Quick Settings tile. Idle it reads "Start workout" with the last sport and opens the ready screen, like every
 * entry point (location is only granted to a service started while the app is visible). During a workout it is lit
 * with the sport and opens the live screen.
 */
class WorkoutTileService : TileService() {
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val container = (application as OpenStrapApp).container
        watching = CoroutineScope(Dispatchers.Main).launch {
            combine(container.workout.state, container.database.healthDao().appWorkouts()) { s, done -> s to done.firstOrNull() }
                .collect { (state, last) ->
                    val tile = qsTile ?: return@collect
                    val r = state as? WorkoutState.Recording
                    tile.state = if (r != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    tile.label = if (r != null) Workouts.sportName(r.workout.sportType) else tr(R.string.common_start_workout)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = when {
                            r?.paused == true -> tr(R.string.common_paused)
                            r != null -> tr(R.string.common_recording)
                            else -> Workouts.sportName(last?.sportType ?: Workouts.DEFAULT_SPORTS.first())
                        }
                    }
                    tile.updateTile()
                }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val container = (application as OpenStrapApp).container
        val recording = container.workout.state.value is WorkoutState.Recording
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (recording) {
            intent.putExtra(MainActivity.EXTRA_SHOW_WORKOUT, true)
        } else {
            val last = (qsTile?.subtitle?.toString())?.let { name -> Workouts.sports.firstOrNull { it.second == name }?.first }
            intent.putExtra(MainActivity.EXTRA_READY_SPORT, last ?: Workouts.DEFAULT_SPORTS.first())
        }
        val pending = PendingIntent.getActivity(this, REQUEST_TILE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending)
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }

    private companion object {
        const val REQUEST_TILE = 3
    }
}
