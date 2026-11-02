package org.openstrap.app.presentation

import java.util.Locale

/**
 * The words a workout uses on the live screens and in its notification alike, kept here so both read them from one
 * place: the zones, a missing heart rate, the strap gone, a finished kilometre.
 */
object WorkoutWords {
    /** Zone 1 (easy) to 5 (max), by zone index 0 to 4. */
    val ZONE_NAMES: List<String> = listOf("Easy", "Aerobic", "Tempo", "Threshold", "Max")

    /** Where the heart rate goes while the strap is away, on every live screen and the notification. */
    const val STRAP_GONE = "Waiting for the strap"

    /** The live screen's note while the strap is away. */
    const val STRAP_GONE_NOTE =
        "The strap isn't connected. Recording carries on, and the minutes missed are filled from its own history after it syncs."

    private const val MINUTE = 60

    /** "Zone 3, aerobic" as its two parts, drawn as two text elements. */
    fun zoneTitle(zone: Int): Pair<String, String> = "Zone ${zone + 1}," to ZONE_NAMES[zone].lowercase(Locale.ROOT)

    /** [zoneTitle] as one line: "Zone 3, aerobic". */
    fun zoneLine(zone: Int): String = zoneTitle(zone).let { (name, word) -> "$name $word" }

    /** "9 min in this zone", or under a minute in words. */
    fun zoneTime(seconds: Int): String = if (seconds < MINUTE) "Under a minute in this zone" else "${seconds / MINUTE} min in this zone"

    /** What a live screen says where the heart rate goes and there's none: "Paused", [STRAP_GONE], or waiting for it. */
    fun noHeartRate(paused: Boolean, connected: Boolean = true): String = when {
        paused -> "Paused"
        !connected -> STRAP_GONE
        else -> "Waiting for heart rate"
    }

    /** "312 kcal" for the notification and the capsule, null before there's a figure. */
    fun kcalText(activeKcal: Float?): String? = activeKcal?.let { "${it.toInt()} kcal" }

    /** A finished kilometre, [index] from 0: "Km 3 in 5:42". The live screens and the summary all name one this way. */
    fun split(index: Int, seconds: Long, locale: Locale = Locale.getDefault()): String =
        "Km ${index + 1} in ${Units.workoutClock(seconds, locale)}"
}
