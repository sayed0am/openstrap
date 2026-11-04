package org.openstrap.app.data

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings

/** The morning check-in scale: 1 (poor) to 5 (great). */
object CheckIn {
    const val MIN = 1
    const val MAX = 5
    private val LABEL_IDS = listOf(
        R.string.core_checkin_poor, R.string.core_checkin_low, R.string.core_checkin_ok,
        R.string.core_checkin_good, R.string.core_checkin_great,
    )
    val LABELS: List<String> get() = LABEL_IDS.map(Strings.app::get)
    fun label(feeling: Int, strings: Strings = Strings.app): String =
        strings.get(LABEL_IDS[(feeling - MIN).coerceIn(0, LABEL_IDS.lastIndex)])
}
