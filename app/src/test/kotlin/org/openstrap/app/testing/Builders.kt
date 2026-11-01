package org.openstrap.app.testing

import org.openstrap.app.data.Recovery
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.settings.UserProfile

/** A strap workout with only what the test cares about named. Everything the strap may leave out is null. */
fun workout(
    start: Long = 0,
    minutes: Int = 30,
    end: Long = start + minutes * 60L,
    sportType: Int = 1,
    exerciseSeconds: Int? = (end - start).toInt(),
    calories: Int? = null,
    averageHeartRate: Int? = null,
    maxHeartRate: Int? = null,
    minHeartRate: Int? = null,
    aerobicTrainingEffect: Float? = null,
    anaerobicTrainingEffect: Float? = null,
    trainingLoad: Int? = null,
    vo2max: Int? = null,
    steps: Int? = null,
    subtype: Int? = null,
) = Workout(
    start, end, sportType, exerciseSeconds, calories, averageHeartRate, maxHeartRate, minHeartRate,
    aerobicTrainingEffect, anaerobicTrainingEffect, trainingLoad, vo2max, steps, subtype,
)

/** A finished phone workout. The rest of [AppWorkout] keeps its defaults, so set it with `copy`. */
fun appWorkout(
    start: Long = 0,
    minutes: Int = 30,
    end: Long? = start + minutes * 60L,
    sportType: Int = 1,
    pauses: String = "",
    averageHeartRate: Int? = null,
    maxHeartRate: Int? = null,
    target: String? = null,
    activeKcal: Float? = null,
    totalKcal: Float? = null,
) = AppWorkout(
    start = start, end = end, sportType = sportType, pauses = pauses, averageHeartRate = averageHeartRate,
    maxHeartRate = maxHeartRate, target = target, activeKcal = activeKcal, totalKcal = totalKcal,
)

private const val DAY = 86_400L

/**
 * An ordinary valid night for day [i] (epoch day [i]): 8 h asleep from 7 h before midnight to half past, enough HRV
 * samples and heart rate minutes to count, and the given vitals.
 */
fun night(i: Int, hrv: Float? = 50f, rhr: Float? = 55f, temp: Float? = 33.5f, breath: Float? = 14f, asleep: Int = 480) =
    Recovery.Night(
        day = i * DAY, start = i * DAY - 7 * 3600, end = i * DAY + 30 * 60, asleepMinutes = asleep, awakeMinutes = 20,
        deepMinutes = 80, remMinutes = 95, hrv = hrv, restingHr = rhr, rhrMeanSleep = rhr?.plus(4f), nadirTime = i * DAY - 4 * 3600,
        skinTemperature = temp, breathRate = breath, hrvSamples = 20, hrMinutes = 420,
    )

/** A filled-in profile, a man born in 1990 at 70 kg and 175 cm, where [UserProfile]'s own defaults are all unknown. */
fun profile(
    birthYear: Int? = 1990,
    sex: UserProfile.Sex? = UserProfile.Sex.Male,
    weightKg: Float? = 70f,
    heightCm: Int? = 175,
    maxHeartRate: Int? = null,
) = UserProfile(birthYear = birthYear, sex = sex, weightKg = weightKg, heightCm = heightCm, maxHeartRate = maxHeartRate)
