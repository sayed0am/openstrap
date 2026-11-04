package org.openstrap.app.presentation

/**
 * A workout's colours as ARGB ints, so Compose, the notification and the widgets share them: Compose wraps one in
 * `Color(int)`, a notification takes it as it is.
 */
object WorkoutColors {
    /** Zone 1, easy: the cool blue of light sleep. */
    const val ZONE_1 = 0xFF8FA8FF.toInt()

    /** Zone 2, aerobic. Also the rest green between sets and intervals. */
    const val ZONE_2 = 0xFF3DDC97.toInt()
    const val ZONE_3 = 0xFFFFD166.toInt()
    const val ZONE_4 = 0xFFFF9F43.toInt()

    /** Zone 5, max: the heart red. */
    const val ZONE_5 = 0xFFFF5A5F.toInt()

    /** Zone 1 (easy) to 5 (max), cool to hot, by zone index 0 to 4. */
    val ZONES: List<Int> = listOf(ZONE_1, ZONE_2, ZONE_3, ZONE_4, ZONE_5)

    /** Rest between sets or intervals. */
    const val REST_GREEN = ZONE_2

    /** A paused workout, a lost strap or no reading yet: a cool grey rather than a zone, in the app and the notification. */
    const val PAUSED_GREY = 0xFF9AA0B4.toInt()

    /** Zone [index] (0 to 4)'s colour, or [PAUSED_GREY] with no zone. */
    fun zone(index: Int?): Int = index?.let(ZONES::getOrNull) ?: PAUSED_GREY
}
