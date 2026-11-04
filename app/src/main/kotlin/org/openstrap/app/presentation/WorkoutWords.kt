package org.openstrap.app.presentation

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import java.util.Locale

/**
 * The words a workout uses on the live screens and in its notification alike, kept here so both read them from one
 * place: the zones, a missing heart rate, the strap gone, a finished kilometre.
 */
object WorkoutWords {
    /** Zone 1 (easy) to 5 (max), by zone index 0 to 4. */
    val ZONE_NAMES: List<String> get() = ZONE_IDS.map(Strings.app::get)

    private val ZONE_IDS = listOf(
        R.string.core_workout_zone_easy, R.string.core_workout_zone_aerobic, R.string.core_workout_zone_tempo,
        R.string.core_workout_zone_threshold, R.string.core_workout_zone_max,
    )

    /** The zone names after other words, lower case in English: "aerobic". Their own strings, so a language keeps its case. */
    private val ZONE_IN_SENTENCE_IDS = listOf(
        R.string.core_workout_zone_easy_in_sentence, R.string.core_workout_zone_aerobic_in_sentence,
        R.string.core_workout_zone_tempo_in_sentence, R.string.core_workout_zone_threshold_in_sentence,
        R.string.core_workout_zone_max_in_sentence,
    )

    /** Where the heart rate goes while the strap is away, on every live screen and the notification. */
    val STRAP_GONE: String get() = Strings.app.get(R.string.core_workout_strap_gone)

    /** The live screen's note while the strap is away. */
    val STRAP_GONE_NOTE: String get() = Strings.app.get(R.string.core_workout_strap_gone_note)

    private const val MINUTE = 60

    /** "Zone 3, aerobic" as its two parts, drawn as two text elements. */
    fun zoneTitle(zone: Int, strings: Strings = Strings.app): Pair<String, String> =
        strings.format(R.string.core_workout_zone_number, zone + 1) to strings.get(ZONE_IN_SENTENCE_IDS[zone])

    /** [zoneTitle] as one line: "Zone 3, aerobic". */
    fun zoneLine(zone: Int, strings: Strings = Strings.app): String =
        strings.format(R.string.core_workout_zone_line, zone + 1, strings.get(ZONE_IN_SENTENCE_IDS[zone]))

    /** "9 min in this zone", or under a minute in words. */
    fun zoneTime(seconds: Int, strings: Strings = Strings.app): String =
        if (seconds < MINUTE) {
            strings.get(R.string.core_workout_zone_time_under)
        } else {
            strings.format(R.string.core_workout_zone_time, seconds / MINUTE)
        }

    /** What a live screen says where the heart rate goes and there's none: "Paused", [STRAP_GONE], or waiting for it. */
    fun noHeartRate(paused: Boolean, connected: Boolean = true, strings: Strings = Strings.app): String = when {
        paused -> strings.get(R.string.core_workout_paused)
        !connected -> strings.get(R.string.core_workout_strap_gone)
        else -> strings.get(R.string.core_workout_waiting_hr)
    }

    /** "312 kcal" for the notification and the capsule, null before there's a figure. */
    fun kcalText(activeKcal: Float?, strings: Strings = Strings.app): String? =
        activeKcal?.let { strings.format(R.string.core_unit_kcal, it.toInt().toString()) }

    /** A finished kilometre, [index] from 0: "Km 3 in 5:42". The live screens and the summary all name one this way. */
    fun split(index: Int, seconds: Long, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        strings.format(R.string.core_workout_split, index + 1, Units.workoutClock(seconds, locale))
}
