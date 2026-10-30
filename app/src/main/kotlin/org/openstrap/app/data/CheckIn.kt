package org.openstrap.app.data

import org.openstrap.app.R
import org.openstrap.app.tr

/** The morning check-in scale: 1 (poor) to 5 (great). */
object CheckIn {
    const val MIN = 1
    const val MAX = 5
    val LABELS = listOf(tr(R.string.common_poor), tr(R.string.common_low), tr(R.string.common_ok), tr(R.string.common_good), tr(R.string.common_great))
    fun label(feeling: Int): String = LABELS[(feeling - MIN).coerceIn(0, LABELS.lastIndex)]
}
