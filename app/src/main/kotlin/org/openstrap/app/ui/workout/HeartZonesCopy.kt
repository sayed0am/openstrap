package org.openstrap.app.ui.workout

import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.MaxHeartRate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Heart rate zones page says (board SettingsHeartZones). Pure, so it's tested without the page. */
object HeartZonesCopy {
    const val TITLE = "Heart rate zones"
    const val MAX_TITLE = "Your max heart rate"
    const val SET_IT = "Set it myself"
    const val LEARN_IT = "Learn it again"

    /** Under the list: one set, what reads them, and how to change them. */
    const val NOTE = "The same for every sport. Zone goals and time in zones use them, and Load uses your max. Tap a zone to move its edges."

    /** The You page's row: "Max 192 bpm, the same zones for every sport". */
    fun youLine(maxHr: Int?): String = if (maxHr != null) "Max $maxHr bpm, the same zones for every sport" else "The same zones for every sport"

    /** The Profile page's row for your max, which opens the Heart rate zones page. */
    const val MAX_ROW = "Max heart rate"
    fun profileLine(set: Int?): String = if (set != null) "$set bpm, set by you" else "Worked out from your age and workouts"

    /** Where your max comes from, under it: "Learned from your hardest efforts, 3 Oct". */
    fun source(m: MaxHeartRate, locale: Locale = Locale.getDefault()): String = when (m.source) {
        MaxHeartRate.Source.Set -> "Set by you"
        MaxHeartRate.Source.Learned -> "Learned from your hardest efforts" + (m.learnedOn?.let { ", " + it.format(DateTimeFormatter.ofPattern("d MMM", locale)) } ?: "")
        MaxHeartRate.Source.Age -> "From your age, until a hard effort shows more"
        MaxHeartRate.Source.Strap -> "From your Zepp profile, until a hard effort shows more"
        MaxHeartRate.Source.Guess -> "A first guess. Add your age, or set it yourself."
    }

    /** A zone's name on the list: "Zone 2". */
    fun zoneName(zone: Int): String = "Zone ${zone + 1}"

    /** A zone's heart rates on the list: "115 to 133", and "172 and up" for zone 5. */
    fun zoneBpm(zones: HeartZones, zone: Int, maxHr: Int): String {
        val from = zones.startBpm(zone, maxHr)
        return zones.endBpm(zone, maxHr)?.let { "$from to $it" } ?: "$from and up"
    }

    /** The sheet for moving a zone's edges: its steppers' names and what they do. */
    const val STARTS_AT = "Starts at"
    const val ENDS_AT = "Ends at"
    fun edgesNote(zone: Int): String = when (zone) {
        0 -> "Moving its end moves where zone 2 starts."
        HeartZones.STANDARD.starts.lastIndex -> "Zone 5 runs on up to your max. Moving its start moves where zone 4 ends."
        else -> "Moving an edge moves the zone beside it too."
    }

    const val RESET = "Back to the usual zones"

    /** The sheet for setting your max yourself. */
    const val SET_TITLE = "Your max heart rate"
    const val SET_NOTE = "From a test or a race you ran flat out. Your zones follow it."
    const val SET_FIELD = "Max heart rate"
    const val SAVE = "Save"

    /** A typed max: 120 to 230 bpm, as the profile has always taken it. */
    fun parseMax(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in org.openstrap.app.data.Recovery.TESTED_MAX_RANGE }
}
