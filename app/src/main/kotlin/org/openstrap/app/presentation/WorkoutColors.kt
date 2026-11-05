package org.openstrap.app.presentation

/**
 * A workout's colours as ARGB ints, so Compose, the notification and the widgets share them: Compose wraps one in
 * `Color(int)`, a notification takes it as it is.
 */
object WorkoutColors {
    /** Zone 1, easy: a calm blue, the cool end of one scale that warms with effort. */
    const val ZONE_1 = 0xFF5B8CFF.toInt()

    /** Zone 2, aerobic: teal. Also the rest colour between sets and intervals. */
    const val ZONE_2 = 0xFF2EC5D3.toInt()

    /** Zone 3, tempo: lime. */
    const val ZONE_3 = 0xFFA6E05A.toInt()

    /** Zone 4, threshold: amber. */
    const val ZONE_4 = 0xFFFFB020.toInt()

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
