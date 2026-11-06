package org.openstrap.app.export

import org.json.JSONObject
import org.openstrap.app.data.settings.SettingsKeys

/**
 * The settings a backup carries, as settings.json beside the CSV files: what the user chose about themselves and the
 * app. Left out on purpose: the strap's address and auth key, the Zepp sign-in, the companion association, the strap's
 * battery and sync cursors (a restored cursor would skip history), call and notification buzzing and Health Connect
 * auto sync (each needs a permission granted on this phone), the experimental strap sessions, the backup folder itself
 * (its permission belongs to one phone), and what the app keeps for itself, such as the weeks a recap or goal moment
 * last showed and the update checks. Keys are [SettingsKeys], as [org.openstrap.app.data.settings.SettingsStore]
 * stores them.
 */
object SettingsBackup {
    const val FILE = "settings.json"
    private const val VERSION = 1

    enum class Type { INT, LONG, FLOAT, BOOLEAN, STRING }

    val KEYS: Map<String, Type> = with(SettingsKeys) {
        linkedMapOf(
            PROFILE_BIRTH_YEAR to Type.INT,
            PROFILE_SEX to Type.STRING,
            PROFILE_WEIGHT_KG to Type.FLOAT,
            PROFILE_HEIGHT_CM to Type.INT,
            PROFILE_IMPERIAL to Type.BOOLEAN,
            PROFILE_SLEEP_NEED_MINUTES to Type.INT,
            PROFILE_MAX_HR to Type.INT,
            PROFILE_HR_ZONES to Type.STRING,
            PROFILE_ACTIVITY_LEVEL to Type.INT,
            PROFILE_ACTIVITY_ANSWERED_AT to Type.LONG,
            PROFILE_BODY_MEASURED_AT to Type.LONG,
            PROFILE_BETA_BLOCKER to Type.BOOLEAN,
            PROFILE_PREGNANT to Type.BOOLEAN,
            GOAL_STEPS to Type.INT,
            GOAL_SLEEP_MINUTES to Type.INT,
            GOAL_ACTIVE_HOURS to Type.INT,
            GOAL_CALORIES to Type.INT,
            GOAL_WORKOUTS_WEEK to Type.INT,
            TODAY_HIDDEN to Type.STRING,
            TODAY_PINNED to Type.STRING,
            CELEBRATE_GOAL to Type.BOOLEAN,
            WEEKLY_RECAP to Type.BOOLEAN,
            FIRST_SCORE_DAY to Type.LONG,
            AUTO_START_WORKOUTS to Type.BOOLEAN,
            WEAR_REMINDER to Type.BOOLEAN,
            WORKOUT_LIVE_VIEW to Type.INT,
            WORKOUT_SIDES to Type.STRING,
            BUZZ_PATTERN to Type.STRING,
            CALL_BUZZ to Type.STRING,
            CALL_BUZZ_LENGTH to Type.STRING,
            UPDATE_AUTO to Type.BOOLEAN,
            QUIET_HOURS to Type.BOOLEAN,
            QUIET_FROM_MINUTES to Type.INT,
            QUIET_TO_MINUTES to Type.INT,
            QUIET_CALLS to Type.BOOLEAN,
        )
    }

    /** Keys made of a prefix and a sport's id, one per sport: what Ready was last set to for it. */
    private val prefixes: Map<String, Type> = mapOf(SettingsKeys.SPORT_SETTINGS_PREFIX to Type.STRING)

    /** The type [key] is backed up as, or null when it isn't backed up. */
    fun typeOf(key: String): Type? = KEYS[key] ?: prefixes.entries.firstOrNull { (prefix, _) ->
        key.startsWith(prefix) && key.removePrefix(prefix).toIntOrNull() != null
    }?.value

    /** Whether a backup carries [key]. */
    fun carries(key: String): Boolean = typeOf(key) != null

    /** The backed-up keys of [values] as JSON; anything else in it is left out. */
    fun toJson(values: Map<String, Any>): String {
        val settings = JSONObject()
        for ((key, v) in values) {
            val type = typeOf(key) ?: continue
            if (convert(v, type) != null) settings.put(key, v)
        }
        return JSONObject().put("version", VERSION).put("settings", settings).toString(2)
    }

    /**
     * The settings in [json], each as its key's type. Keys this version doesn't back up, and values of the wrong
     * type, are skipped, so a newer or damaged file restores what it can.
     */
    fun fromJson(json: String): Map<String, Any> {
        val settings = JSONObject(json).optJSONObject("settings") ?: return emptyMap()
        return buildMap {
            for (key in settings.keys()) {
                val type = typeOf(key) ?: continue
                convert(settings.get(key), type)?.let { put(key, it) }
            }
        }
    }

    /** What a restore writes: the settings this phone has never set ([present] are the keys it has). */
    fun missing(backup: Map<String, Any>, present: Set<String>): Map<String, Any> = backup.filterKeys { it !in present }

    private fun convert(v: Any, type: Type): Any? = when (type) {
        Type.INT -> (v as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()
        Type.LONG -> (v as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toLong()
        Type.FLOAT -> (v as? Number)?.toFloat()
        Type.BOOLEAN -> v as? Boolean
        Type.STRING -> v as? String
    }
}
