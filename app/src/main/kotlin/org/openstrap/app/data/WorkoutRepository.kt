package org.openstrap.app.data

import kotlinx.coroutines.flow.Flow
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutRound
import org.openstrap.app.data.db.AppWorkoutSet
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.WorkoutHeartRate
import java.time.LocalDate

/** The strap's workouts and the phone's, analysed, and what their pages and summaries read. */
interface WorkoutRepository {
    /**
     * The strap's workouts and the phone's, newest first, each with its zones from its whole heart rate stream. Zones
     * come in after the list, so the list shows before they're worked out.
     */
    fun workouts(): Flow<List<WorkoutAnalysis>>

    /** The strap's sport-effect records, newest last. */
    fun effects(): Flow<List<SportEffectRow>>

    /** The max heart rate zones are shares of ([MaxHeartRates]). */
    fun maxHeartRate(): Flow<Int>

    /** The workout starting at [start], the phone's or the strap's, as it changes, or null when there's none. */
    fun open(start: Long): Flow<WorkoutAnalysis?>

    /** The phone workout [start]'s summary. Reading it never writes: [backfillEffect] does that on its own. */
    fun phoneSummary(start: Long): Flow<WorkoutSummaryData?>

    /** Workouts nobody started ([WorkoutSuggestions]) since [from], with [now] read afresh each time. */
    fun suggestions(from: Long, now: () -> Long): Flow<List<WorkoutSuggestions.Suggestion>>

    /** [date]'s heart rate a minute at a time, for adding a workout after the fact. */
    suspend fun dayMinuteHeartRate(date: LocalDate): List<Pair<Long, Int>>

    /**
     * The heart rate drop in the first minute after each of the latest [count] strap workouts before [before], newest
     * first, from those ended from a real effort ([WorkoutAnalysis.recoveryIsMeaningful]).
     */
    suspend fun recoveryDrops(before: Long, count: Int): List<Int>

    /** Works out and saves the effect of phone workout [start] when it ended before effects were worked out. */
    suspend fun backfillEffect(start: Long)

    /** Deletes a strap workout and keeps it from coming back with a later sync. */
    suspend fun deleteStrapWorkout(start: Long, now: Long)
}

/**
 * What a phone workout's summary shows: [analysis] over its shown range, [recorded] every sample from
 * [recordedStart] to [recordedEnd] (for the trim chart).
 */
data class WorkoutSummaryData(
    val analysis: WorkoutAnalysis,
    val recorded: List<WorkoutHeartRate>,
    val recordedStart: Long,
    val recordedEnd: Long,
    val endReason: Int?,
    /** The route, when it was kept, else empty. */
    val route: List<Route.Point> = emptyList(),
    /** Every live reading in the shown range, for colouring the route. */
    val history: List<Pair<Long, Int>> = emptyList(),
    val distanceMeters: Float? = null,
    val distanceFromSteps: Boolean = false,
    /** Strength: each set and the rest after it. */
    val sets: List<AppWorkoutSet> = emptyList(),
    /**
     * The strap's own records of the same workout, by start, when it kept any (a strap session, or its
     * auto-detection), more than one when a long workout held several.
     */
    val strapWorkouts: List<WorkoutAnalysis> = emptyList(),
    /** Laps, when any were marked. */
    val laps: List<WorkoutRecorder.Lap> = emptyList(),
    /** Finished pauses, which no split counts. */
    val pauses: List<LongRange> = emptyList(),
    /** Its effect figures are the strap's own ([AppWorkout.effectFromStrap]). */
    val effectFromStrap: Boolean = false,
    /** Its rounds or periods ([AppWorkout.structure]) and each one recorded. */
    val structure: Structure? = null,
    val rounds: List<AppWorkoutRound> = emptyList(),
    /** Strength: the heart rate rest ended under, when rest ended on heart rate ([Strength.Rest]). */
    val restBelow: Int? = null,
    /** Strength: how long the heart rate took to fall under [restBelow] in the last session before ([restRecovery]). */
    val restBefore: Long? = null,
) {
    companion object {
        /**
         * How long the heart rate took to fall under [below] after each set, on average over the rests where it did:
         * from the set's end to the first reading under the level before the rest ended. Null without a level or such
         * a rest.
         */
        fun restRecovery(sets: List<AppWorkoutSet>, readings: List<Pair<Long, Int>>, below: Int?): Long? {
            below ?: return null
            val times = sets.mapNotNull { s ->
                val end = s.end ?: return@mapNotNull null
                val until = s.restEnd ?: return@mapNotNull null
                readings.firstOrNull { it.first in end..until && it.second < below }?.let { it.first - end }
            }
            return if (times.isEmpty()) null else times.sum() / times.size
        }
    }
}
