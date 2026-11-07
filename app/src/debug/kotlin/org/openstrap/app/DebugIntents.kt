package org.openstrap.app

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openstrap.app.MainActivity.Companion.EXTRA_DATA_STATE
import org.openstrap.app.MainActivity.Companion.EXTRA_MOMENT
import org.openstrap.app.MainActivity.Companion.EXTRA_SHOW_SUMMARY
import org.openstrap.app.MainActivity.Companion.EXTRA_STRAP_STATE
import org.openstrap.app.MainActivity.Companion.EXTRA_WORKOUT_NOTIFICATION
import org.openstrap.app.MainActivity.Companion.MOMENT_NOW
import org.openstrap.app.data.Cue
import org.openstrap.app.data.Moment
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.PairedDevice
import org.openstrap.app.device.StrapManager
import org.openstrap.app.device.StrapService
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.ui.dashboard.DataPreview
import org.openstrap.app.ui.dashboard.StrapPreview
import org.openstrap.app.ui.glow.debugRecoveryScoreState
import org.openstrap.app.ui.root.DebugPreviews

private const val TAG = "unstrap"
private const val HEX = 16
private const val SIMULATED_BATTERY = 80
private const val SCORE_MAX = 100

private val MainActivity.container: AppContainer get() = (application as OpenStrapApp).container

/**
 * The debug extras, each acted on once and removed from the intent. A release build has a twin that ignores them.
 * `am start -n org.openstrap.app/.MainActivity --ei dump_raw_days 7` dumps raw history to files/raw.
 */
internal fun MainActivity.handleDebugIntent(previews: DebugPreviews) {
    demoData()
    strapCommands()
    shownStates(previews)
    workoutSamples()
    dumpRaw()
}

/**
 * `--ez demo_data true` fills an empty database with sample data (for the emulator), and stands in a strap when none
 * is paired, so the screens show a paired phone. Pair with `simulate_hr` to have it connected.
 */
private fun MainActivity.demoData() {
    if (!intent.getBooleanExtra("demo_data", false)) return
    intent.removeExtra("demo_data")
    val container = container
    lifecycleScope.launch {
        DemoData.fill(container.database.healthDao())
        if (container.settings.pairedDevice.first() == null) {
            container.settings.savePairedDevice(PairedDevice("00:00:00:00:00:00", "00", "Helio Strap"))
        }
    }
}

/** Commands to the strap: raw bytes, strap sessions on or off, a cue's buzz, and simulated heart rate. */
private fun MainActivity.strapCommands() {
    val manager = container.strap as? StrapManager
    // `--ei probe_endpoint 73 --es probe_hex 00` sends raw bytes to a channel endpoint and logs replies.
    val probeEndpoint = intent.getIntExtra("probe_endpoint", -1)
    if (probeEndpoint >= 0) {
        val hex = intent.getStringExtra("probe_hex").orEmpty()
        intent.removeExtra("probe_endpoint")
        manager?.probe(probeEndpoint, hex.chunked(2).map { it.toInt(HEX).toByte() }.toByteArray())
    }
    // `--ez strap_sessions false` stops phone workouts running a session on the strap (0x0019), `true` brings it back.
    if (intent.hasExtra("strap_sessions")) {
        val on = intent.getBooleanExtra("strap_sessions", false)
        intent.removeExtra("strap_sessions")
        val settings = container.settings
        lifecycleScope.launch { settings.setStrapSessions(on) }
        Toast.makeText(this, "Strap sessions ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
    }
    buzz()
    // `--es simulate_hr 140x120,80x600 --ei simulate_steps 0 --ei simulate_battery 80` plays heart rate as a connected
    // strap (emulator).
    val simulate = intent.getStringExtra("simulate_hr") ?: return
    intent.removeExtra("simulate_hr")
    val script = simulate.split(',').mapNotNull { p ->
        p.split('x').takeIf { it.size == 2 }?.let { (b, s) -> b.toIntOrNull()?.let { bpm -> s.toIntOrNull()?.let { bpm to it } } }
    }
    manager?.simulate(script, intent.getIntExtra("simulate_steps", 0), intent.getIntExtra("simulate_battery", SIMULATED_BATTERY))
}

/** `--es buzz AboveTarget` plays a cue's pattern on the strap (a Cue name or Stop), to feel it. */
private fun MainActivity.buzz() {
    val buzz = intent.getStringExtra("buzz") ?: return
    intent.removeExtra("buzz")
    val pattern = when (buzz) {
        "Stop" -> WorkoutSession.STOP_BUZZ
        else -> Cue.entries.firstOrNull { it.name == buzz }?.pattern
    } ?: return
    val strap = container.strap
    lifecycleScope.launch {
        catching { strap.buzz(pattern) }.onFailure { Toast.makeText(this@buzz, "Buzz failed: ${it.message}", Toast.LENGTH_SHORT).show() }
    }
}

/**
 * What the screens are shown in place of the real state, so each can be checked on a phone, and "auto" goes back to
 * the real one. Only what the screens are shown changes: no Bluetooth, no scan, no sync, nothing written.
 */
private fun MainActivity.shownStates(previews: DebugPreviews) {
    // `--es moment Evening` forces Today's moment (a Moment name).
    val moment = intent.getStringExtra(EXTRA_MOMENT)
    if (moment != null && moment != MOMENT_NOW) {
        intent.removeExtra(EXTRA_MOMENT)
        if (moment.equals("auto", ignoreCase = true)) {
            previews.moment.value = null
        } else {
            Moment.entries.firstOrNull { it.name.equals(moment, ignoreCase = true) }?.let { previews.moment.value = it }
                ?: Log.w(TAG, "moment: no moment called $moment")
        }
    }
    // `--ei recovery_score 78` shows that score on the Recovery detail (its arc gauge) while Recovery is still learning,
    // and `--ei recovery_score -1` goes back to the real one.
    if (intent.hasExtra("recovery_score")) {
        val score = intent.getIntExtra("recovery_score", -1)
        intent.removeExtra("recovery_score")
        debugRecoveryScoreState.value = score.takeIf { it in 0..SCORE_MAX }
    }
    // `--es strap_state stuck` shows a strap state on a phone whose strap is fine.
    intent.getStringExtra(EXTRA_STRAP_STATE)?.let { state ->
        intent.removeExtra(EXTRA_STRAP_STATE)
        if (state.equals("auto", ignoreCase = true)) {
            previews.strap.value = null
        } else {
            StrapPreview.of(state)?.let { previews.strap.value = it } ?: Log.w(TAG, "strap_state: no state called $state")
        }
        Log.i(TAG, "strap_state: showing ${previews.strap.value ?: "the real state"}")
    }
    // `--es data_state nosleep` shows a data state (the pairing stage's steps, first nights, no sleep, a partial day,
    // the first score) on a phone whose data is fine.
    intent.getStringExtra(EXTRA_DATA_STATE)?.let { state ->
        intent.removeExtra(EXTRA_DATA_STATE)
        if (state.equals("auto", ignoreCase = true)) {
            previews.data.value = null
        } else {
            DataPreview.of(state)?.let { previews.data.value = it } ?: Log.w(TAG, "data_state: no state called $state")
        }
        Log.i(TAG, "data_state: showing ${previews.data.value ?: "the real state"}")
    }
}

/** The workout screens and notification with nothing recording and nothing written. */
private fun MainActivity.workoutSamples() {
    // `--ez show_summary true` opens the summary right after a workout for the latest stored phone workout.
    if (intent.getBooleanExtra(EXTRA_SHOW_SUMMARY, false)) {
        intent.removeExtra(EXTRA_SHOW_SUMMARY)
        val container = container
        lifecycleScope.launch {
            val latest = container.database.healthDao().appWorkouts().first().filter { it.end != null }.maxByOrNull { it.start }
            if (latest != null) {
                container.workout.showSummary(latest.start)
            } else {
                Toast.makeText(this@workoutSamples, "No phone workout to show", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // `--es workout_notification running` posts the workout notification with sample figures, built as a real
    // workout's is: running, paused, indoor, end or clear.
    val sample = intent.getStringExtra(EXTRA_WORKOUT_NOTIFICATION) ?: return
    intent.removeExtra(EXTRA_WORKOUT_NOTIFICATION)
    startService(Intent(this, StrapService::class.java).setAction(StrapService.ACTION_SAMPLE).putExtra(StrapService.EXTRA_SAMPLE, sample))
}

/** `--ei dump_raw_days 7` dumps raw history to files/raw, and `--es dump_types 5,6,70` limits it to these GDSP codes. */
private fun MainActivity.dumpRaw() {
    val days = intent.getIntExtra("dump_raw_days", 0)
    if (days <= 0) return
    intent.removeExtra("dump_raw_days")
    // Even codes the strap doesn't advertise.
    val types = intent.getStringExtra("dump_types")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
    intent.removeExtra("dump_types")
    Log.i(TAG, "dump: requested $days days, types ${types ?: "all"}")
    (container.strap as? StrapManager)?.dumpRaw(days, types)
}
