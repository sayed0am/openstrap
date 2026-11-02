package org.openstrap.app.data

/**
 * The heart rates the app believes (decision 9 in [org.openstrap.app.presentation.Units]): 30 to 230 bpm, in every
 * chart, summary, export, engine and workout. Outside it a reading is a sensor artefact, and one range means a reading
 * counts everywhere or nowhere.
 */
object HeartRate {
    const val MIN_BPM = 30
    const val MAX_BPM = 230

    val VALID: IntRange = MIN_BPM..MAX_BPM

    /**
     * [VALID] as SQL, to follow a column in a Room query: `"... AND bpm " + HeartRate.SQL_VALID`. A constant, so
     * annotations can use it.
     */
    const val SQL_VALID = "BETWEEN $MIN_BPM AND $MAX_BPM"

    fun isValid(bpm: Int): Boolean = bpm in VALID
}
