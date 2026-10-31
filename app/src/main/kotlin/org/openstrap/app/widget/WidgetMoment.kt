package org.openstrap.app.widget

import org.openstrap.app.AppContainer
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.MomentInputs
import org.openstrap.app.data.MomentResolver
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.WorkoutState
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * What [MomentResolver] reads, from the database rather than Today's screen, so a widget follows the same moment as
 * Today (widgets spec 2.2: the mood follows the current moment). The same inputs Today gathers: whether a workout is
 * recording, the last workout's end, today's main sleep's end, the usual bedtime (Recovery's [usualBedtime]) and the
 * start of every workout you started over [MomentResolver.USUAL_WORKOUT_DAYS].
 */
suspend fun widgetMomentInputs(
    container: AppContainer,
    usualBedtime: LocalTime?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): MomentInputs {
    val dao = container.database.healthDao()
    val today = now.atZone(zone).toLocalDate()
    val t = now.epochSecond
    val from = t - MomentResolver.USUAL_WORKOUT_DAYS * 86_400L
    val phone = dao.appWorkoutsOnce(from, t)
    // The strap's record of a phone workout is that workout, as in the lists ([Workouts.sameWorkout]).
    val strap = dao.workoutsOnce(from, t).filter { Workouts.phoneTwin(it, phone, t) == null }
    // You started it: not one the strap noticed or filed as "Activity" by itself (WorkoutAnalysis.startedByYou).
    val starts = strap.filter { it.subtype != Workouts.SUBTYPE_AUTO_DETECTED && it.sportType != Workouts.GENERIC_ACTIVITY }.map { it.start } +
        phone.filter { it.sportType != Workouts.GENERIC_ACTIVITY }.map { it.start }
    val lastEnd = (strap.map { it.end } + phone.mapNotNull { it.end }).filter { it <= t }.maxOrNull()
    // Today's main sleep, as Today picks it: the longest non-nap session that ended in the night window.
    val night = DayWindow.nightEndingOn(today, zone)
    val sleepEnd = org.openstrap.app.data.SleepSource(dao).sessionsOnce(night.from, night.to).filter { !it.nap }.maxByOrNull { it.end - it.start }?.end
    return MomentInputs(
        day = today,
        recording = container.workout.state.value is WorkoutState.Recording,
        lastWorkoutEnd = lastEnd,
        sleepEnd = sleepEnd,
        usualBedtime = usualBedtime,
        workoutStarts = starts.sorted(),
    )
}
