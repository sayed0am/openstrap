package org.openstrap.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** All timestamps are epoch seconds (UTC). */

@Entity(tableName = "heart_rate")
data class HeartRateSample(
    @PrimaryKey val timestamp: Long,
    val bpm: Int,
    val source: Int = SOURCE_HISTORY,
) {
    companion object {
        const val SOURCE_HISTORY = 0
        const val SOURCE_LIVE = 1
    }
}

@Entity(tableName = "activity_minute")
data class ActivityMinute(
    @PrimaryKey val timestamp: Long,
    val steps: Int,
    val intensity: Int,
    val kind: Int,
    val heartRate: Int?,
)

/** A main sleep or a nap. The strap's own totals and score are kept as it reported them. */
@Entity(tableName = "sleep_session", indices = [Index("day")])
data class SleepSession(
    @PrimaryKey val start: Long,
    val end: Long,
    /** Local midnight of the day the strap assigned this sleep to; 0 for rows from before v2. */
    @ColumnInfo(defaultValue = "0") val day: Long = 0,
    @ColumnInfo(defaultValue = "0") val nap: Boolean = false,
    val score: Int? = null,
    val averageHeartRate: Int? = null,
    val deepMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val remMinutes: Int? = null,
    val awakeMinutes: Int? = null,
    val wakeCount: Int? = null,
)

/**
 * The user's own times for a sleep or nap the strap detected, or its removal, kept apart from [SleepSession] because a
 * sync rewrites the day's sessions. Keyed by the strap's start for the session, which stays the same across syncs or
 * moves a few minutes ([org.openstrap.app.data.SleepEdits.resolve] allows for that). The strap's rows are never
 * changed by an edit, so Undo and Restore bring back exactly what was there.
 */
@Entity(tableName = "sleep_edit")
data class SleepEdit(
    @PrimaryKey val strapStart: Long,
    /** Which kind the strap's session was, so an edit never moves or removes a session of the other kind. */
    @ColumnInfo(defaultValue = "0") val nap: Boolean,
    /** The user's times; both null when not moved. */
    val start: Long? = null,
    val end: Long? = null,
    /** Removed: left out of everything. Its session goes for good after [org.openstrap.app.data.SleepEdits.KEEP_DAYS]. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** When it was last changed, epoch seconds: for the Deleted sleep list and the clean up. */
    @ColumnInfo(defaultValue = "0") val editedAt: Long = 0,
) {
    /** True when the user moved the session's times. */
    val moved: Boolean get() = start != null && end != null
}

@Entity(tableName = "sleep_stage", indices = [Index("sessionStart")])
data class SleepStage(
    @PrimaryKey val start: Long,
    val end: Long,
    val sessionStart: Long,
    val stage: Int,
) {
    companion object {
        const val AWAKE = 0
        const val LIGHT = 1
        const val DEEP = 2
        const val REM = 3
    }
}

@Entity(tableName = "spo2")
data class Spo2Sample(
    @PrimaryKey val timestamp: Long,
    val percent: Int,
)

@Entity(tableName = "stress")
data class StressSample(
    @PrimaryKey val timestamp: Long,
    val value: Int,
)

@Entity(tableName = "hrv")
data class HrvSample(
    @PrimaryKey val timestamp: Long,
    val milliseconds: Int,
)

@Entity(tableName = "resting_hr")
data class RestingHeartRate(
    /** Start of the local day, epoch seconds. */
    @PrimaryKey val day: Long,
    val bpm: Int,
)

/** Skin temperature from the strap's touch sensor, one per minute (type 46). */
@Entity(tableName = "skin_temperature")
data class SkinTemperature(
    @PrimaryKey val timestamp: Long,
    val celsius: Float,
)

/** Respiratory rate during sleep, breaths per minute (type 56). */
@Entity(tableName = "breath_rate")
data class BreathRate(
    @PrimaryKey val timestamp: Long,
    val perMinute: Int,
)

/** The strap's PAI state at [timestamp] (type 13); the latest row of a day is that day's value. */
@Entity(tableName = "pai")
data class PaiSample(
    @PrimaryKey val timestamp: Long,
    val daily: Float,
    val total: Float,
    val restingHeartRate: Int,
    val maxHeartRate: Int,
    /**
     * The strap's projected total for the next day with no new activity (`nextActivityScores[1]`, record v2+), so
     * `total − tomorrowTotal` is exactly what drops off (native 08 §3.7). Null for older records and older rows.
     */
    val tomorrowTotal: Float? = null,
)

/** HRV as SDNN (type 78). The strap sends one every hour or two through the day, not just at night. */
@Entity(tableName = "hrv_sdnn")
data class HrvSdnnSample(
    @PrimaryKey val timestamp: Long,
    val milliseconds: Float,
)

/**
 * A workout recorded on the strap (type 5). [start] is the strap's trackId. [end] is the start plus the
 * exercise time; the real end (pauses included) comes from the sport effect or the HR stream when present.
 */
@Entity(tableName = "workout")
data class Workout(
    @PrimaryKey val start: Long,
    val end: Long,
    val sportType: Int,
    val exerciseSeconds: Int?,
    val calories: Int?,
    val averageHeartRate: Int?,
    val maxHeartRate: Int?,
    val minHeartRate: Int?,
    val aerobicTrainingEffect: Float?,
    val anaerobicTrainingEffect: Float?,
    val trainingLoad: Int?,
    val vo2max: Int?,
    val steps: Int?,
    /** The summary's Type.subtype: 1 auto-detected, 6 a phone workout mirrored on the strap, 0 normal (codemap 05 §8). Null before version 17. */
    val subtype: Int? = null,
)

/** Per-second HR during a workout and the strap's 3-minute recovery recording after it (type 6). */
@Entity(tableName = "workout_hr", primaryKeys = ["workoutStart", "timestamp"])
data class WorkoutHeartRate(
    val workoutStart: Long,
    val timestamp: Long,
    val bpm: Int,
)

/**
 * A workout recorded on the phone from the strap's live heart rate, apart from the strap's own [Workout] so a later
 * merge with strap-side sessions stays explicit. [end] is null while it is recording.
 */
@Entity(tableName = "app_workout")
data class AppWorkout(
    @PrimaryKey val start: Long,
    val end: Long?,
    val sportType: Int,
    val source: Int = SOURCE_APP,
    /** Finished pauses as "from-to" pairs of epoch seconds, comma separated; see [pauseRanges]. */
    val pauses: String = "",
    /** When the pause under way began; null while recording. */
    val pausedAt: Long? = null,
    val endReason: Int? = null,
    /** Worked out when the workout ends, and again when gap filling adds minutes. */
    val averageHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    /** "Ask to end when I stop", chosen on the ready screen. */
    @ColumnInfo(defaultValue = "1") val askToEnd: Boolean = true,
    /**
     * Where the workout really ended before the app or a trim moved [end] earlier; null when it ends where it was
     * recorded. Nothing recorded is deleted, so undo puts it back.
     */
    val recordedEnd: Long? = null,
    /** The start as trimmed; null for [start]. [start] stays the key. */
    val shownStart: Long? = null,
    /** What it aims for ([org.openstrap.app.data.WorkoutTarget.code]); null for none. */
    val target: String? = null,
    /** GPS on for this workout (outdoor sports only, off by default). */
    @ColumnInfo(defaultValue = "0") val useGps: Boolean = false,
    /** The route is saved in [AppWorkoutRoutePoint]; otherwise it is used live, then discarded, and only totals stay. */
    @ColumnInfo(defaultValue = "0") val keepRoute: Boolean = false,
    /** Metres covered: from GPS, or estimated from the strap's steps ([distanceFromSteps]) without it. */
    val distanceMeters: Float? = null,
    @ColumnInfo(defaultValue = "0") val distanceFromSteps: Boolean = false,
    /** Strength: how rest ends ([org.openstrap.app.data.Strength.Rest.code]) and how many sets are planned. */
    val rest: String? = null,
    val setsTarget: Int? = null,
    /** Lap marks, epoch seconds, comma separated; see [lapMarks]. */
    @ColumnInfo(defaultValue = "") val laps: String = "",
    /**
     * What the workout did ([org.openstrap.app.data.WorkoutEffect]): peak EPOC in ml/kg, aerobic training effect 0–5,
     * recovery time and, for outdoor runs, VO2max in ml/kg/min. Worked out when it ends and again on a trim or a gap
     * fill; null on workouts from before version 14 until they are opened.
     */
    val epoc: Float? = null,
    val aerobicTrainingEffect: Float? = null,
    val recoveryMinutes: Int? = null,
    val vo2max: Float? = null,
    /** Anaerobic training effect 0–5: only from the strap ([effectFromStrap]), as no open model makes one. */
    val anaerobicTrainingEffect: Float? = null,
    /**
     * The effect figures and [averageHeartRate] and [maxHeartRate] are the strap's own, from a record it kept of nearly
     * all of this workout ([org.openstrap.app.data.WorkoutEffectEngine.strapEffect]), rather than OpenStrap's model and
     * live readings.
     */
    @ColumnInfo(defaultValue = "0") val effectFromStrap: Boolean = false,
    /**
     * This workout's own recovery time, before what earlier workouts left: [recoveryMinutes] is never less than that
     * leftover, so after a long workout a short one shows the long one's. Null for the strap's figures and before
     * version 23.
     */
    val ownRecoveryMinutes: Int? = null,
    /**
     * kcal over the shown range less pauses, above rest and in all ([org.openstrap.app.data.EnergyExpenditure.workoutMinutes]).
     * Worked out when it ends and again on a trim or a gap fill; null without a profile, and on workouts from before
     * version 20 until the daily energy history fills them in.
     */
    val activeKcal: Float? = null,
    val totalKcal: Float? = null,
    /** When the live active kcal first reached a kcal [target], epoch seconds; null until then and for other targets. */
    val targetReachedAt: Long? = null,
    /**
     * Rounds or periods ([org.openstrap.app.data.Phases]): intervals ("t:20/10x8"), run reps ("r:400m/90x6") or a
     * game's periods ("p:2x2700/900"); null for an open workout. Added in version 25.
     */
    val structure: String? = null,
    /**
     * A GPS run's fastest stretches ([org.openstrap.app.data.Rewards.Stretch.code]), worked out from the route when it
     * ends, kept or not, so its bests can be worked out again later. Null without GPS. Added in version 25.
     */
    val stretches: String? = null,
) {
    val lapMarks: List<Long> get() = laps.split(',').mapNotNull { it.toLongOrNull() }

    /** The start the workout is shown and measured from. */
    val from: Long get() = shownStart ?: start

    val pauseRanges: List<LongRange>
        get() = pauses.split(',').mapNotNull { p ->
            val (a, b) = p.split('-').takeIf { it.size == 2 } ?: return@mapNotNull null
            (a.toLongOrNull() ?: return@mapNotNull null)..(b.toLongOrNull() ?: return@mapNotNull null)
        }

    /** Seconds spent paused between [from] and [now], the pause under way included. */
    fun pausedSeconds(now: Long): Long =
        pauseRanges.sumOf { (minOf(it.last, now) - maxOf(it.first, from)).coerceAtLeast(0) } + (pausedAt?.let { (now - maxOf(it, from)).coerceAtLeast(0) } ?: 0)

    fun withPause(from: Long, to: Long) = copy(pauses = (pauseRanges.map { "${it.first}-${it.last}" } + "$from-$to").joinToString(","), pausedAt = null)

    companion object {
        const val SOURCE_APP = 0
        const val SOURCE_STRAP = 1
        const val SOURCE_SUGGESTED = 2
        const val END_USER = 0
        const val END_AUTO = 1
        const val END_CAR = 2
    }
}

/**
 * One set of a strength workout: when it began and ended, and when the rest after it ended (null while resting). Its
 * [exercise], [reps] and [weightKg] are logged on the card after it when logging is on (version 25), else null.
 */
@Entity(tableName = "app_workout_set", primaryKeys = ["workoutStart", "index"])
data class AppWorkoutSet(
    val workoutStart: Long,
    val index: Int,
    val start: Long,
    val end: Long? = null,
    val restEnd: Long? = null,
    val exercise: String? = null,
    val reps: Int? = null,
    val weightKg: Float? = null,
)

/**
 * One round of an interval workout or one rep of run intervals (version 25): when its work began and ended, when the
 * rest after it ended, and its peak heart rate. [workEnd] and [restEnd] are null while under way.
 */
@Entity(tableName = "app_workout_round", primaryKeys = ["workoutStart", "index"])
data class AppWorkoutRound(
    val workoutStart: Long,
    val index: Int,
    val workStart: Long,
    val workEnd: Long? = null,
    val restEnd: Long? = null,
    val peakBpm: Int? = null,
)

/** An interval workout saved by name ("Tuesday intervals"), offered on Choose and in More (version 25). */
@Entity(tableName = "saved_workout")
data class SavedWorkout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sport: Int,
    /** As [AppWorkout.structure]. */
    val structure: String,
    /** A goal as [AppWorkout.target], or null. */
    val goal: String? = null,
    val createdAt: Long,
)

/** An exercise named in a strength log, with its last reps and weight to fill the next log card (version 25). */
@Entity(tableName = "exercise")
data class ExerciseRow(
    @PrimaryKey val name: String,
    val lastReps: Int? = null,
    val lastKg: Float? = null,
    /** When it was last logged, epoch seconds, for the list's order. */
    val usedAt: Long,
)

/**
 * A personal best ([org.openstrap.app.data.Rewards]) by its [kind] ("run_5k", "lift:Bench press"): the [value] in its
 * kind's unit, the [date] it was set (epoch day) and the workout that set it, if one did (version 25).
 */
@Entity(tableName = "personal_best")
data class PersonalBest(
    @PrimaryKey val kind: String,
    val value: Double,
    val date: Long,
    val workoutStart: Long? = null,
)

/** A badge earned (requests spec 7.2, workouts spec 3.3): when, and the workout or day that earned it (version 25). */
@Entity(tableName = "badge")
data class BadgeRow(
    @PrimaryKey val id: String,
    val earnedAt: Long,
    val workoutStart: Long? = null,
    /** The epoch day, for a badge a day earned. */
    val day: Long? = null,
)

/**
 * A week's workouts against the weekly goal it had (workouts spec 3.1): [weekStart] its first day (epoch day), the
 * [goal] then, how many workouts [count]ed and whether it was [met] (version 25).
 */
@Entity(tableName = "week_result")
data class WeekResult(
    @PrimaryKey val weekStart: Long,
    val goal: Int,
    val count: Int,
    val met: Boolean,
)

/**
 * One GPS fix of a workout whose route the wearer chose to keep on the phone. Speed, bearing, altitude and their
 * accuracies came in version 13 and are null on older rows ([org.openstrap.app.data.Route.Point]).
 */
@Entity(tableName = "app_workout_route", primaryKeys = ["workoutStart", "timestamp"])
data class AppWorkoutRoutePoint(
    val workoutStart: Long,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float? = null,
    val bearing: Float? = null,
    val altitude: Double? = null,
    val speedAccuracy: Float? = null,
    val bearingAccuracy: Float? = null,
) {
    val point get() = org.openstrap.app.data.Route.Point(timestamp, lat, lon, accuracy, speed, bearing, altitude, speedAccuracy, bearingAccuracy)

    companion object {
        fun of(workoutStart: Long, p: org.openstrap.app.data.Route.Point) =
            AppWorkoutRoutePoint(workoutStart, p.t, p.lat, p.lon, p.accuracy, p.speed, p.bearing, p.altitude, p.speedAccuracy, p.bearingAccuracy)
    }
}

/**
 * A strap workout deleted in OpenStrap, so a later sync doesn't bring it back: the strap still holds it and re-sends
 * it whenever its workout cursor goes back (a first sync, a cursor repair). [effectEnd] is the end of the sport effect
 * deleted with it, if one was, which arrives on its own and is skipped the same way.
 */
@Entity(tableName = "workout_deleted")
data class DeletedWorkout(
    @PrimaryKey val start: Long,
    val effectEnd: Long?,
    val deletedAt: Long,
)

/** A stretch answered "Not a workout", so it isn't offered again ([org.openstrap.app.data.WorkoutSuggestions]). */
@Entity(tableName = "app_workout_dismissed")
data class DismissedWorkout(
    @PrimaryKey val start: Long,
    val end: Long,
)

/** Live heart rate during an [AppWorkout], one per second; [fromHistory] rows fill minutes the phone missed, one per minute. */
@Entity(tableName = "app_workout_hr", primaryKeys = ["workoutStart", "timestamp"])
data class AppWorkoutHeartRate(
    val workoutStart: Long,
    val timestamp: Long,
    val bpm: Int,
    val fromHistory: Boolean = false,
)

/** The strap's post-workout result (type 59), keyed by the workout's end. */
@Entity(tableName = "sport_effect")
data class SportEffectRow(
    @PrimaryKey val end: Long,
    val recoveryMinutes: Int?,
    val vo2max: Float?,
    val epoc: Float?,
    val weeklyLoad: Float?,
    val monthlyLoad: Float?,
    val optimalLoadMin: Int?,
    val optimalLoadMax: Int?,
    val maximalHeartRate: Int?,
)

/**
 * The strap's own readiness for a day (module 0x0049), kept next to OpenStrap's so the two can be compared
 * and ours fitted later. The strap keeps only its latest score, so each sync saves it.
 */
@Entity(tableName = "strap_readiness")
data class StrapReadinessRow(
    /** Local midnight of the scored day. */
    @PrimaryKey val day: Long,
    val updatedAt: Long,
    val score: Int?,
    val hrvScore: Int?,
    val restingHrScore: Int?,
    val skinTemperatureScore: Int?,
    val breathingScore: Int?,
    val sleepHrv: Int?,
    val sleepRestingHr: Int?,
    val algorithmVersion: String,
)

/** Blood oxygen measured during sleep (type 38). Separate from daytime spot readings ([Spo2Sample]). */
@Entity(tableName = "sleep_spo2")
data class SleepSpo2Sample(
    @PrimaryKey val timestamp: Long,
    val percent: Int,
)

/**
 * One oxygen-desaturation event during sleep (type 39): the size of the drop, and the 240 SpO2 and 240 heart-rate
 * samples the strap recorded around it, one unsigned byte each. The samples are null for the short record form.
 */
@Entity(tableName = "osa_event")
class OsaEventRow(
    @PrimaryKey val timestamp: Long,
    val drop: Int,
    val spo2: ByteArray?,
    val heartRate: ByteArray?,
)

/**
 * The strap's nightly sleep-apnea summary (type 74), keyed by the night's belongTime (the same day rule as the
 * sleep result). Units of [validTime], [odiRaw] and [odiScore] are unverified, so they're kept as sent; readiness
 * uses only [eventCount].
 */
@Entity(tableName = "osa_result")
data class OsaResultRow(
    @PrimaryKey val belongTime: Long,
    val validTime: Long,
    val odiRaw: Long,
    val odiScore: Int,
    val dispCode: Int,
    val eventCount: Int,
    val version: Int,
)

/** The day's highest exercise heart rate (type 61). */
@Entity(tableName = "daily_max_hr")
data class DailyMaxHr(
    /** Start of the local day, epoch seconds. */
    @PrimaryKey val day: Long,
    val bpm: Int,
)

/** Heart rate after a workout ended (type 70): about one bpm per second, one unsigned byte each. */
@Entity(tableName = "recovery_hr")
class RecoveryHeartRateRow(
    @PrimaryKey val workoutStart: Long,
    val samples: ByteArray,
)

/**
 * Extra per-minute activity (type 88). On a real strap [distanceCm] is centimetres (about 75 a step), and
 * [sit], [stand], [walk] and [run] are 0/1 flags for the minute's posture rather than durations. [calories] looks like
 * active kcal for the minute and [floors] has stayed 0; both are kept as sent until confirmed.
 */
@Entity(tableName = "activity_extended")
data class ActivityExtendedMinute(
    @PrimaryKey val timestamp: Long,
    val floors: Int,
    val run: Int,
    val fatBurn: Int,
    val stand: Int,
    val walk: Int,
    val sit: Int,
    val steps: Int,
    val calories: Int,
    val distanceCm: Int,
)

/** A record whose format isn't decoded yet (type 57), kept whole so it can be parsed later. */
@Entity(tableName = "raw_record", primaryKeys = ["type", "timestamp"])
class RawRecord(
    val type: Int,
    val timestamp: Long,
    val payload: ByteArray,
)

/** An alert the strap pushed (0x001D `0D`): high or low heart rate, high stress, low SpO2, or an irregular rhythm. */
@Entity(tableName = "health_alert", primaryKeys = ["timestamp", "type"])
data class HealthAlertRow(
    val timestamp: Long,
    /** The strap's alert code, `HealthAlert.Type.code`. */
    val type: Int,
    /** bpm, stress level or SpO2 %; 0 for an irregular rhythm. */
    val value: Long,
)

/**
 * A change in whether the strap is worn, from its wear-status push (Settings DEVICE_INFORMATION key 16, codemap 07
 * §4 N5) or the read on connect. The state holds until the next row. [worn] null marks the link going down: from then
 * on the app doesn't know, so Charge falls back to its heart-rate gap rule.
 */
@Entity(tableName = "wear_event")
data class WearEventRow(
    @PrimaryKey val timestamp: Long,
    val worn: Boolean?,
)

/** A phone workout's finished sets, counted for the workout lists ([HealthDao.setCounts]). */
data class SetCount(val workoutStart: Long, val sets: Int)

/**
 * What a workout's HR stream looks like from outside: its last sample, how many there are and their bpm summed. The
 * workout lists work zones out again only when it changes ([HealthDao.workoutHeartRateMarks]).
 */
data class HrStreamMark(val workoutStart: Long, val lastSample: Long, val samples: Int, val bpmSum: Long)

/** One local day of a sampled metric: its average (or total, for steps), lowest, highest and sample count. */
data class DailyAggregate(
    /** The local date as SQLite prints it, `yyyy-MM-dd`. */
    val day: String,
    val value: Double,
    val min: Double,
    val max: Double,
    val count: Int,
)

/** One local hour's steps: the local date as SQLite prints it, the hour 0 to 23, and the steps in it. */
data class HourSteps(val day: String, val hour: Int, val steps: Int)

/** The day's last PAI reading: what the strap reported for that day and the 7-day total. */
data class DailyPai(val day: String, val daily: Float, val total: Float, val latest: Long)

/**
 * The morning check-in: how the user feels, 1 (poor) to 5 (great), for the local day [day]. The only within-user
 * ground truth the Readiness score can be tested against (docs/algorithms/evidence-review.md, "Validity").
 */
@Entity(tableName = "check_in")
data class CheckInRow(
    /** Local midnight of the day, epoch seconds. */
    @PrimaryKey val day: Long,
    val feeling: Int,
    val updatedAt: Long,
)

/** Something the user said happened on [day] that can move body signals: see `DayTag` for the keys. */
@Entity(tableName = "day_tag", primaryKeys = ["day", "tag"])
data class DayTagRow(
    /** Local midnight of the day, epoch seconds. */
    val day: Long,
    val tag: String,
)

/**
 * OpenStrap's own nightly inputs, logged next to the strap's for the same night, so the strap calibration review can
 * compare definitions (evidence-review.md, "The strap's own nightly HRV and resting HR"). Recomputable from the synced
 * data, but kept so a later change of definition doesn't rewrite what was seen at the time.
 */
@Entity(tableName = "night_metrics")
data class NightMetricsRow(
    /** Local midnight of the morning the sleep ended. */
    @PrimaryKey val day: Long,
    val updatedAt: Long,
    /** `hrv_n`: ln(mean of the gated RMSSD samples during sleep). */
    val hrvLn: Float?,
    val hrvSamples: Int,
    /** `rhr_n`: lowest 30-minute window mean during sleep. */
    val rhrLowest30: Float?,
    /** Mean of per-minute sleep heart rate. */
    val rhrMeanSleep: Float?,
    /** 10th percentile of per-minute sleep heart rate. */
    val rhrP10: Float?,
    val hrMinutes: Int,
    /** The strap's own values for the same night (strap_readiness), when it reported them. */
    val strapHrv: Int?,
    val strapRestingHr: Int?,
)

/**
 * Each Sunday's Habit Age estimate as it was shown, so the next week can say "1 year younger than last week". Kept
 * rather than worked out again, since a later sync can change the data behind a past estimate.
 */
@Entity(tableName = "habit_age_week")
data class HabitAgeWeekRow(
    /** Local midnight of the Sunday the estimate is for. */
    @PrimaryKey val asOf: Long,
    val estimate: Float,
    val low: Int,
    val high: Int,
    val updatedAt: Long,
)

/**
 * One local day's energy as the engine last worked it out ([org.openstrap.app.data.EnergyExpenditure.Day]), so week,
 * month and year views don't run the model for every day. Rewritten whenever the day is worked out again.
 */
@Entity(tableName = "daily_energy")
data class DailyEnergyRow(
    /** Local midnight of the day, epoch seconds. */
    @PrimaryKey val day: Long,
    val updatedAt: Long,
    val totalKcal: Float,
    val activeKcal: Float,
    val bmrKcal: Float,
    val minutesWithData: Int,
    val partial: Boolean,
    /** Active kcal in workout minutes, and in walking minutes outside them. */
    val workoutKcal: Float,
    val walkingKcal: Float,
    /** Active kcal for each local hour, 0 to 23, comma separated; see [hourlyKcal]. */
    val hourly: String,
) {
    val hourlyKcal: List<Float> get() = hourly.split(',').map { it.toFloatOrNull() ?: 0f }
}
