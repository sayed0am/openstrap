package org.openstrap.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class PairedDevice(val address: String, val authKeyHex: String, val name: String)

/**
 * The user's own Zepp credentials, used only for firmware update checks. Stored on this phone only.
 * [loginToken] is the long-lived token that buys a new app token (A3, codemap 08 §1.4); null when the sign-in didn't
 * give one or the token was typed in. [appTokenAtMs] is when [appToken] was obtained (Zepp's `lu_app_ttl`) and
 * [appTtlSeconds] its lifetime, when the server said.
 */
data class ZeppAccount(
    val appToken: String,
    val userId: String,
    val apiBase: String,
    val china: Boolean,
    val loginToken: String? = null,
    val appTokenAtMs: Long? = null,
    val appTtlSeconds: Long? = null,
) {
    override fun toString() =
        "ZeppAccount(userId=$userId, apiBase=$apiBase, china=$china, appToken=…, loginToken=${if (loginToken == null) "none" else "…"}, " +
            "appTokenAtMs=$appTokenAtMs, appTtlSeconds=$appTtlSeconds)"

    companion object {
        const val DEFAULT_API_BASE = "https://api-mifit.zepp.com/"
    }
}

/** What the user told us about themselves; every field is optional. Used by on-phone calculations and sent to the strap. */
data class UserProfile(
    val birthYear: Int? = null,
    val sex: Sex? = null,
    val weightKg: Float? = null,
    val heightCm: Int? = null,
    /** Show pounds and feet/inches; null follows the phone's region. Values are always stored metric. */
    val imperial: Boolean? = null,
    /** The user's own sleep need, 420-540 min; null uses the default for their age. */
    val sleepNeedMinutes: Int? = null,
    /** A maximum heart rate from a test; overrides the age estimate. */
    val maxHeartRate: Int? = null,
    /** Usual physical activity, level 1-5 as index 0-4 (Jurca 2005's question), and when it was last answered. */
    val activityLevel: Int? = null,
    val activityAnsweredAt: Long? = null,
    /** When weight and height were last confirmed (epoch seconds); the VO2max estimate wants them under 90 days old. */
    val bodyMeasuredAt: Long? = null,
    /** Resting heart rate doesn't reflect fitness on beta-blockers or in pregnancy, so VO2max is withheld. */
    val betaBlocker: Boolean = false,
    val pregnant: Boolean = false,
) {
    enum class Sex { Female, Male }

    val usesImperial: Boolean
        get() = imperial ?: (java.util.Locale.getDefault().country in IMPERIAL_REGIONS)

    companion object {
        /** Regions that use pounds and feet day to day. */
        val IMPERIAL_REGIONS = setOf("US", "LR", "MM")
        const val KG_PER_LB = 0.45359237f
        const val CM_PER_INCH = 2.54f
    }

    fun age(today: java.time.LocalDate = java.time.LocalDate.now()): Int? = birthYear?.let { today.year - it }
    val isEmpty get() = birthYear == null && sex == null && weightKg == null && heightCm == null
}

/**
 * The strap as last seen, kept for widgets: [battery] and [charging] from the last connection (null battery before
 * one), [lastSyncDone] when a history sync last finished (epoch seconds), null before one.
 */
/**
 * Automatic backup ([org.openstrap.app.export.AutoBackup]): the [folder] chosen (a tree URI) and its [folderName], or
 * null while off. [lastDone] is when a backup last finished (epoch seconds); [failed] the last run's failure and
 * [failedAt] when, null once a backup succeeds.
 */
data class BackupState(
    val folder: String?,
    val folderName: String?,
    val lastDone: Long?,
    val failed: org.openstrap.app.export.BackupFiles.Failure?,
    val failedAt: Long?,
) {
    val on get() = folder != null
}

data class StrapSnapshot(val paired: Boolean, val battery: Int?, val charging: Boolean, val lastSyncDone: Long?)

/**
 * Auto sync to Health Connect: [on] or off (off by default), when OpenStrap last wrote there ([lastSynced], epoch
 * seconds, auto or by hand), and why auto sync turned itself off ([stopped]), until the user turns it on again.
 */
data class HealthConnectAuto(val on: Boolean = false, val lastSynced: Long? = null, val stopped: Stopped? = null) {
    enum class Stopped { PERMISSIONS, UNAVAILABLE }
}

/** Notification buzz patterns: alternating vibrate and pause milliseconds for the strap's motor. */
enum class BuzzPattern(val label: String, val millis: List<Int>) {
    ONE_SHORT("Single", listOf(200)),
    TWO_SHORT("Double", listOf(150, 150, 150)),
    THREE_SHORT("Triple", listOf(120, 120, 120, 120, 120)),
    ONE_LONG("Long", listOf(600)),
}

/** How the strap buzzes while a call rings: the find-device buzz without a break, or 600 ms motor pulses once a second. */
enum class CallBuzz(val label: String) { STEADY("Steady"), PULSED("Pulsed") }

/**
 * Hours the strap stays still for notifications, such as the night: from [from] to [to] in minutes after midnight,
 * running past midnight when [to] comes first. Calls still ring unless [calls] mutes them too.
 */
data class QuietHours(val on: Boolean = false, val from: Int = 22 * 60, val to: Int = 7 * 60, val calls: Boolean = false) {
    /** True when [minute] (after midnight) falls inside the hours. A window whose ends match is empty. */
    fun covers(minute: Int): Boolean = on && when {
        from == to -> false
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }
}

class SettingsStore(private val context: Context) {
    private val address = stringPreferencesKey("device_address")
    private val authKey = stringPreferencesKey("device_auth_key")
    private val name = stringPreferencesKey("device_name")

    val pairedDevice: Flow<PairedDevice?> = context.dataStore.data.map { prefs ->
        val a = prefs[address] ?: return@map null
        val k = prefs[authKey] ?: return@map null
        PairedDevice(a, k, prefs[name] ?: "Helio Strap")
    }

    suspend fun savePairedDevice(device: PairedDevice) {
        context.dataStore.edit {
            it[address] = device.address
            it[authKey] = device.authKeyHex
            it[name] = device.name
        }
    }

    private val zeppToken = stringPreferencesKey("zepp_app_token")
    private val zeppUser = stringPreferencesKey("zepp_user_id")
    private val zeppApi = stringPreferencesKey("zepp_api_base")
    private val zeppChina = androidx.datastore.preferences.core.booleanPreferencesKey("zepp_china")
    private val zeppLoginToken = stringPreferencesKey("zepp_login_token")
    private val zeppAppTokenAt = androidx.datastore.preferences.core.longPreferencesKey("zepp_app_token_at")
    private val zeppAppTtl = androidx.datastore.preferences.core.longPreferencesKey("zepp_app_ttl")

    val zeppAccount: Flow<ZeppAccount?> = context.dataStore.data.map { prefs ->
        val t = prefs[zeppToken] ?: return@map null
        ZeppAccount(
            t, prefs[zeppUser].orEmpty(), prefs[zeppApi] ?: ZeppAccount.DEFAULT_API_BASE, prefs[zeppChina] == true,
            prefs[zeppLoginToken], prefs[zeppAppTokenAt], prefs[zeppAppTtl],
        )
    }

    /** Replaces the whole sign-in, so a typed-in token drops the login token of an earlier sign-in. */
    suspend fun saveZeppAccount(account: ZeppAccount) {
        context.dataStore.edit {
            it[zeppToken] = account.appToken
            it[zeppUser] = account.userId
            it[zeppApi] = account.apiBase
            it[zeppChina] = account.china
            account.loginToken?.let { v -> it[zeppLoginToken] = v } ?: it.remove(zeppLoginToken)
            account.appTokenAtMs?.let { v -> it[zeppAppTokenAt] = v } ?: it.remove(zeppAppTokenAt)
            account.appTtlSeconds?.let { v -> it[zeppAppTtl] = v } ?: it.remove(zeppAppTtl)
        }
    }

    suspend fun clearZeppAccount() {
        context.dataStore.edit {
            it.remove(zeppToken); it.remove(zeppUser); it.remove(zeppApi); it.remove(zeppChina)
            it.remove(zeppLoginToken); it.remove(zeppAppTokenAt); it.remove(zeppAppTtl)
        }
    }

    private val liveHr = androidx.datastore.preferences.core.booleanPreferencesKey("live_hr")

    /** Whether live heart rate starts automatically on connect. On by default. */
    val liveHeartRate: Flow<Boolean> = context.dataStore.data.map { it[liveHr] ?: true }

    suspend fun setLiveHeartRate(enabled: Boolean) {
        context.dataStore.edit { it[liveHr] = enabled }
    }

    private val autoStartKey = androidx.datastore.preferences.core.booleanPreferencesKey("auto_start_workouts")

    /** Notice a workout as it starts and offer to record it from where it began ([org.openstrap.app.data.AutoStart]). On by default. */
    val autoStartWorkouts: Flow<Boolean> = context.dataStore.data.map { it[autoStartKey] ?: true }

    suspend fun setAutoStartWorkouts(enabled: Boolean) {
        context.dataStore.edit { it[autoStartKey] = enabled }
    }

    private val wearReminderKey = androidx.datastore.preferences.core.booleanPreferencesKey("wear_reminder")

    /**
     * Off-body reminder, as Zepp has for the strap (codemap 07 §4 N5): turns on the strap's wear reminder (GENERAL
     * key 44) on connect and shows a notification while the strap reports it is off-body. On by default.
     */
    val wearReminder: Flow<Boolean> = context.dataStore.data.map { it[wearReminderKey] ?: true }

    suspend fun setWearReminder(enabled: Boolean) {
        context.dataStore.edit { it[wearReminderKey] = enabled }
    }

    private val strapSessionsKey = androidx.datastore.preferences.core.booleanPreferencesKey("strap_sessions")

    /**
     * On by default: a phone workout also runs a session on the strap (0x0019), so the strap keeps its own record of
     * it, whose heart rate and effect the workout then takes (verified on the strap 2026-10-30, codemap 05 §5.11). A
     * debug intent turns it off, to compare the live stream without one.
     */
    val strapSessions: Flow<Boolean> = context.dataStore.data.map { it[strapSessionsKey] ?: true }

    suspend fun setStrapSessions(enabled: Boolean) {
        context.dataStore.edit { it[strapSessionsKey] = enabled }
    }

    private val workoutLiveViewKey = androidx.datastore.preferences.core.intPreferencesKey("workout_live_view")

    /** The live view a GPS workout was left on, Heart (0) or Map (1); the next one opens on it. Heart by default. */
    val workoutLiveView: Flow<Int> = context.dataStore.data.map { it[workoutLiveViewKey] ?: 0 }

    suspend fun setWorkoutLiveView(view: Int) {
        context.dataStore.edit { it[workoutLiveViewKey] = view }
    }

    private val workoutSidesKey = stringPreferencesKey("workout_sides")

    /**
     * The side of Ready's Outdoor or Indoor switch you last chose, per sport (spec 6.3), by the sport's key
     * ([org.openstrap.app.data.Workouts.Sides.outdoor]), true for indoor. Sports never switched aren't in it.
     */
    val workoutSides: Flow<Map<Int, Boolean>> = context.dataStore.data.map { org.openstrap.app.data.Workouts.parseSides(it[workoutSidesKey]) }

    suspend fun setWorkoutSide(sport: Int, indoor: Boolean) {
        context.dataStore.edit {
            val sides = org.openstrap.app.data.Workouts.parseSides(it[workoutSidesKey]) + (sport to indoor)
            it[workoutSidesKey] = org.openstrap.app.data.Workouts.formatSides(sides)
        }
    }

    /**
     * What Ready was set to the last time each sport started (workouts spec 4), by the sport's tile
     * ([org.openstrap.app.data.Workouts.tileType]). Sports never started aren't in it.
     */
    val sportSettings: Flow<Map<Int, org.openstrap.app.data.SportSettings>> = context.dataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val sport = key.name.removePrefix(SPORT_SETTINGS_PREFIX).takeIf { key.name.startsWith(SPORT_SETTINGS_PREFIX) }?.toIntOrNull()
            if (sport == null || value !is String) null else sport to org.openstrap.app.data.SportSettings.parse(value)
        }.toMap()
    }

    suspend fun setSportSettings(sport: Int, settings: org.openstrap.app.data.SportSettings) {
        val key = stringPreferencesKey(SPORT_SETTINGS_PREFIX + org.openstrap.app.data.Workouts.tileType(sport))
        context.dataStore.edit { it[key] = settings.encode() }
    }

    private val birthYear = androidx.datastore.preferences.core.intPreferencesKey("profile_birth_year")
    private val sex = stringPreferencesKey("profile_sex")
    private val weight = androidx.datastore.preferences.core.floatPreferencesKey("profile_weight_kg")
    private val height = androidx.datastore.preferences.core.intPreferencesKey("profile_height_cm")
    private val imperial = androidx.datastore.preferences.core.booleanPreferencesKey("profile_imperial")
    private val sleepNeed = androidx.datastore.preferences.core.intPreferencesKey("profile_sleep_need_minutes")
    private val maxHr = androidx.datastore.preferences.core.intPreferencesKey("profile_max_hr")
    private val activityLevel = androidx.datastore.preferences.core.intPreferencesKey("profile_activity_level")
    private val activityAt = longPreferencesKey("profile_activity_answered_at")
    private val bodyAt = longPreferencesKey("profile_body_measured_at")
    private val betaBlocker = androidx.datastore.preferences.core.booleanPreferencesKey("profile_beta_blocker")
    private val pregnant = androidx.datastore.preferences.core.booleanPreferencesKey("profile_pregnant")

    val profile: Flow<UserProfile> = context.dataStore.data.map { prefs ->
        UserProfile(
            prefs[birthYear],
            prefs[sex]?.let { v -> UserProfile.Sex.entries.firstOrNull { it.name == v } },
            prefs[weight],
            prefs[height],
            prefs[imperial],
            prefs[sleepNeed],
            prefs[maxHr],
            prefs[activityLevel],
            prefs[activityAt],
            prefs[bodyAt],
            prefs[betaBlocker] == true,
            prefs[pregnant] == true,
        )
    }

    suspend fun saveProfile(p: UserProfile) {
        context.dataStore.edit {
            if (p.birthYear != null) it[birthYear] = p.birthYear else it.remove(birthYear)
            if (p.sex != null) it[sex] = p.sex.name else it.remove(sex)
            if (p.weightKg != null) it[weight] = p.weightKg else it.remove(weight)
            if (p.heightCm != null) it[height] = p.heightCm else it.remove(height)
            if (p.imperial != null) it[imperial] = p.imperial else it.remove(imperial)
            if (p.sleepNeedMinutes != null) it[sleepNeed] = p.sleepNeedMinutes else it.remove(sleepNeed)
            if (p.maxHeartRate != null) it[maxHr] = p.maxHeartRate else it.remove(maxHr)
            if (p.activityLevel != null) it[activityLevel] = p.activityLevel else it.remove(activityLevel)
            if (p.activityAnsweredAt != null) it[activityAt] = p.activityAnsweredAt else it.remove(activityAt)
            if (p.bodyMeasuredAt != null) it[bodyAt] = p.bodyMeasuredAt else it.remove(bodyAt)
            it[betaBlocker] = p.betaBlocker
            it[pregnant] = p.pregnant
        }
    }

    private val stepGoal = androidx.datastore.preferences.core.intPreferencesKey("goal_steps")
    private val sleepGoal = androidx.datastore.preferences.core.intPreferencesKey("goal_sleep_minutes")
    private val activeGoal = androidx.datastore.preferences.core.intPreferencesKey("goal_active_hours")
    private val calorieGoal = androidx.datastore.preferences.core.intPreferencesKey("goal_calories")
    private val workoutGoal = androidx.datastore.preferences.core.intPreferencesKey("goal_workouts_week")

    val goals: Flow<Goals> = context.dataStore.data.map { prefs ->
        Goals(
            prefs[stepGoal] ?: Goals.DEFAULT_STEPS,
            prefs[sleepGoal] ?: Goals.DEFAULT_SLEEP_MINUTES,
            prefs[activeGoal] ?: Goals.DEFAULT_ACTIVE_HOURS,
            prefs[calorieGoal] ?: Goals.DEFAULT_CALORIES,
            prefs[workoutGoal] ?: org.openstrap.app.data.Rewards.DEFAULT_GOAL,
        )
    }

    suspend fun saveGoals(g: Goals) {
        context.dataStore.edit {
            it[stepGoal] = g.steps
            it[sleepGoal] = g.sleepMinutes
            it[activeGoal] = g.activeHours
            it[calorieGoal] = g.calories
            it[workoutGoal] = g.workoutsPerWeek
        }
    }

    private val todayHidden = stringPreferencesKey("today_hidden")
    private val todayPinned = stringPreferencesKey("today_pinned")

    /** Edit Today's choices: the cards hidden and the ones pinned under the hero, by name. Null until first edited. */
    val todayHiddenNames: Flow<List<String>?> = context.dataStore.data.map { prefs -> prefs[todayHidden]?.split(',')?.filter { it.isNotEmpty() } }
    val todayPinnedNames: Flow<List<String>?> = context.dataStore.data.map { prefs -> prefs[todayPinned]?.split(',')?.filter { it.isNotEmpty() } }

    private val goalMomentKey = androidx.datastore.preferences.core.booleanPreferencesKey("celebrate_goal")
    private val weeklyRecapKey = androidx.datastore.preferences.core.booleanPreferencesKey("weekly_recap")
    private val goalMomentWeekKey = longPreferencesKey("goal_moment_week")
    private val recapWeekKey = longPreferencesKey("recap_week")
    private val rewardsStartedKey = androidx.datastore.preferences.core.booleanPreferencesKey("rewards_started")
    private val historyBadgesKey = androidx.datastore.preferences.core.booleanPreferencesKey("history_badges_waiting")

    /** Celebrations (workouts spec 3.4): the full screen goal moment, once a week. On by default. */
    val goalMoment: Flow<Boolean> = context.dataStore.data.map { it[goalMomentKey] ?: true }

    suspend fun setGoalMoment(on: Boolean) {
        context.dataStore.edit { it[goalMomentKey] = on }
    }

    /** Celebrations: the Monday morning recap notification. On by default. */
    val weeklyRecap: Flow<Boolean> = context.dataStore.data.map { it[weeklyRecapKey] ?: true }

    suspend fun setWeeklyRecap(on: Boolean) {
        context.dataStore.edit { it[weeklyRecapKey] = on }
    }

    /** The week (epoch day of its first day) the goal moment last showed for, so it shows once a week; null before. */
    val goalMomentWeek: Flow<Long?> = context.dataStore.data.map { it[goalMomentWeekKey] }

    suspend fun setGoalMomentWeek(week: Long) {
        context.dataStore.edit { it[goalMomentWeekKey] = week }
    }

    /** The week (epoch day of its first day) the last recap was about, so each week gets one; null before. */
    val recapWeek: Flow<Long?> = context.dataStore.data.map { it[recapWeekKey] }

    suspend fun setRecapWeek(week: Long) {
        context.dataStore.edit { it[recapWeekKey] = week }
    }

    /** Rewards were worked out once already: later badges are new, not past history. */
    val rewardsStarted: Flow<Boolean> = context.dataStore.data.map { it[rewardsStartedKey] ?: false }

    /** The badges earned for past history on first launch wait to be shown once, all on one screen. */
    val historyBadgesWaiting: Flow<Boolean> = context.dataStore.data.map { it[historyBadgesKey] ?: false }

    suspend fun startRewards(historyBadges: Boolean) {
        context.dataStore.edit {
            it[rewardsStartedKey] = true
            it[historyBadgesKey] = historyBadges
        }
    }

    suspend fun historyBadgesShown() {
        context.dataStore.edit { it[historyBadgesKey] = false }
    }

    private val habitAgeSeen = longPreferencesKey("habit_age_seen")

    /** The Sunday (local midnight) of the last Habit Age estimate opened, so Today stops offering it; null before any. */
    val habitAgeSeenAsOf: Flow<Long?> = context.dataStore.data.map { it[habitAgeSeen] }

    suspend fun setHabitAgeSeen(asOf: Long) {
        context.dataStore.edit { it[habitAgeSeen] = asOf }
    }

    private val firstScore = longPreferencesKey("first_score_day")

    /**
     * The day (epoch day) of the first real Recovery score, so it's marked on that day only; null before one. Scores
     * from before this was kept are stored as a day long past ([org.openstrap.app.ui.dashboard.BEFORE_FIRST_SCORE]).
     */
    val firstScoreDay: Flow<Long?> = context.dataStore.data.map { it[firstScore] }

    suspend fun setFirstScoreDay(epochDay: Long) {
        context.dataStore.edit { it[firstScore] = epochDay }
    }

    suspend fun saveTodayEdits(hidden: List<String>, pinned: List<String>) {
        context.dataStore.edit {
            it[todayHidden] = hidden.joinToString(",")
            it[todayPinned] = pinned.joinToString(",")
            // What changed's tile choice, which Edit Today replaced.
            it.remove(stringPreferencesKey("today_tiles"))
        }
    }

    private val companionId = androidx.datastore.preferences.core.intPreferencesKey("companion_association_id")
    private val companionNo = androidx.datastore.preferences.core.booleanPreferencesKey("companion_declined")

    /** The strap's companion association id ([org.openstrap.app.device.CompanionLink.LEGACY_ID] before Android 13), or null. */
    val companionAssociation: Flow<Int?> = context.dataStore.data.map { it[companionId] }

    suspend fun setCompanionAssociation(id: Int) {
        context.dataStore.edit { it[companionId] = id }
    }

    /** True once the user turned down Android's association prompt; the strap page then offers battery settings instead. */
    val companionDeclined: Flow<Boolean> = context.dataStore.data.map { it[companionNo] == true }

    suspend fun setCompanionDeclined(declined: Boolean) {
        context.dataStore.edit { it[companionNo] = declined }
    }

    private val heldOff = androidx.datastore.preferences.core.booleanPreferencesKey("held_disconnected")

    /** True after the user tapped Disconnect, until they connect again; background reconnects leave the strap alone. */
    val heldDisconnected: Flow<Boolean> = context.dataStore.data.map { it[heldOff] == true }

    suspend fun setHeldDisconnected(held: Boolean) {
        context.dataStore.edit { it[heldOff] = held }
    }

    /** Forgets the strap and the Zepp sign-in; the user's profile and goals stay. */
    suspend fun forgetDevice() {
        context.dataStore.edit { prefs ->
            val keep = setOf(birthYear, sex, weight, height, imperial, sleepNeed, maxHr, activityLevel, activityAt, bodyAt, betaBlocker, pregnant, stepGoal, sleepGoal, activeGoal, calorieGoal, todayHidden, todayPinned, hcAuto, hcSynced, hcStopped, buzzCalls, buzzNotifications, buzzPattern, callBuzz, quietOn, quietFrom, quietTo, quietCalls, backupFolder, backupFolderName, backupDone, backupFailed, backupFailedAt)
            prefs.asMap().keys.filter { it !in keep }.forEach { prefs.remove(it) }
        }
    }

    private val hcAuto = androidx.datastore.preferences.core.booleanPreferencesKey("health_connect_auto")
    private val hcSynced = longPreferencesKey("health_connect_synced")
    private val hcStopped = stringPreferencesKey("health_connect_auto_stopped")

    /** Auto sync to Health Connect, kept when the strap is forgotten: it's about the data, not the strap. */
    val healthConnectAuto: Flow<HealthConnectAuto> = context.dataStore.data.map { prefs ->
        HealthConnectAuto(
            prefs[hcAuto] == true,
            prefs[hcSynced],
            prefs[hcStopped]?.let { v -> HealthConnectAuto.Stopped.entries.firstOrNull { it.name == v } },
        )
    }

    /** The user's switch; either way clears why it last stopped. */
    suspend fun setHealthConnectAuto(enabled: Boolean) {
        context.dataStore.edit {
            it[hcAuto] = enabled
            it.remove(hcStopped)
        }
    }

    /** Auto sync turns itself off, and the You page says [why]. */
    suspend fun stopHealthConnectAuto(why: HealthConnectAuto.Stopped) {
        context.dataStore.edit {
            it[hcAuto] = false
            it[hcStopped] = why.name
        }
    }

    /** OpenStrap wrote to Health Connect at [epochSeconds]. */
    suspend fun setHealthConnectSynced(epochSeconds: Long) {
        context.dataStore.edit { it[hcSynced] = epochSeconds }
    }

    private val buzzCalls = androidx.datastore.preferences.core.booleanPreferencesKey("buzz_calls")
    private val buzzNotifications = androidx.datastore.preferences.core.booleanPreferencesKey("buzz_notifications")

    /** Buzz the strap while a phone call rings. Off by default; it needs the phone-state permission. */
    val buzzOnCalls: Flow<Boolean> = context.dataStore.data.map { it[buzzCalls] == true }

    /** Buzz the strap once for each app notification that would make a sound. Off by default; it needs notification access. */
    val buzzOnNotifications: Flow<Boolean> = context.dataStore.data.map { it[buzzNotifications] == true }

    private val buzzPattern = stringPreferencesKey("buzz_pattern")

    /** How the strap buzzes for a notification; two short buzzes by default. */
    val notificationPattern: Flow<BuzzPattern> = context.dataStore.data.map { prefs ->
        BuzzPattern.entries.firstOrNull { it.name == prefs[buzzPattern] } ?: BuzzPattern.TWO_SHORT
    }

    suspend fun setNotificationPattern(pattern: BuzzPattern) {
        context.dataStore.edit { it[buzzPattern] = pattern.name }
    }

    private val callBuzz = stringPreferencesKey("call_buzz")

    /** How the strap buzzes for a call; steady by default. */
    val callBuzzStyle: Flow<CallBuzz> = context.dataStore.data.map { prefs ->
        CallBuzz.entries.firstOrNull { it.name == prefs[callBuzz] } ?: CallBuzz.STEADY
    }

    suspend fun setCallBuzzStyle(style: CallBuzz) {
        context.dataStore.edit { it[callBuzz] = style.name }
    }

    suspend fun setBuzzOnCalls(enabled: Boolean) {
        context.dataStore.edit { it[buzzCalls] = enabled }
    }

    suspend fun setBuzzOnNotifications(enabled: Boolean) {
        context.dataStore.edit { it[buzzNotifications] = enabled }
    }

    private val quietOn = androidx.datastore.preferences.core.booleanPreferencesKey("quiet_hours")
    private val quietFrom = androidx.datastore.preferences.core.intPreferencesKey("quiet_from_minutes")
    private val quietTo = androidx.datastore.preferences.core.intPreferencesKey("quiet_to_minutes")
    private val quietCalls = androidx.datastore.preferences.core.booleanPreferencesKey("quiet_calls")

    /** When the strap doesn't buzz for notifications; off by default, 22:00 to 07:00 once turned on. */
    val quietHours: Flow<QuietHours> = context.dataStore.data.map { prefs ->
        val d = QuietHours()
        QuietHours(prefs[quietOn] == true, prefs[quietFrom] ?: d.from, prefs[quietTo] ?: d.to, prefs[quietCalls] == true)
    }

    suspend fun setQuietHours(q: QuietHours) {
        context.dataStore.edit {
            it[quietOn] = q.on
            it[quietFrom] = q.from.mod(24 * 60)
            it[quietTo] = q.to.mod(24 * 60)
            it[quietCalls] = q.calls
        }
    }

    private val strapBattery = androidx.datastore.preferences.core.intPreferencesKey("strap_battery")
    private val strapCharging = androidx.datastore.preferences.core.booleanPreferencesKey("strap_charging")
    private val syncDone = longPreferencesKey("strap_last_sync_done")

    /**
     * What a widget's strap status line reads with the strap away (widgets spec 5): pairing, the battery and charging
     * as last connected, and when a sync last finished.
     */
    val strapSnapshot: Flow<StrapSnapshot> = context.dataStore.data.map { prefs ->
        StrapSnapshot(prefs[address] != null && prefs[authKey] != null, prefs[strapBattery], prefs[strapCharging] == true, prefs[syncDone])
    }

    /** The connected strap's battery and charging, written when either changes. */
    suspend fun setStrapBattery(percent: Int, charging: Boolean) {
        context.dataStore.edit {
            it[strapBattery] = percent
            it[strapCharging] = charging
        }
    }

    /** When a history sync last finished (epoch seconds). Per type cursors are [lastSync]. */
    suspend fun setLastSyncDone(epochSeconds: Long) {
        context.dataStore.edit { it[syncDone] = epochSeconds }
    }

    /** Last synced timestamp (epoch seconds) for a history data type. */
    suspend fun lastSync(type: String): Long? =
        context.dataStore.data.first()[longPreferencesKey("last_sync_$type")]

    suspend fun setLastSync(type: String, epochSeconds: Long) {
        context.dataStore.edit { it[longPreferencesKey("last_sync_$type")] = epochSeconds }
    }

    private val backupFolder = stringPreferencesKey("backup_folder")
    private val backupFolderName = stringPreferencesKey("backup_folder_name")
    private val backupDone = longPreferencesKey("backup_last_done")
    private val backupFailed = stringPreferencesKey("backup_failed")
    private val backupFailedAt = longPreferencesKey("backup_failed_at")

    val backup: Flow<BackupState> = context.dataStore.data.map { prefs ->
        BackupState(
            prefs[backupFolder], prefs[backupFolderName], prefs[backupDone],
            prefs[backupFailed]?.let { v -> org.openstrap.app.export.BackupFiles.Failure.entries.firstOrNull { it.name == v } },
            prefs[backupFailedAt],
        )
    }

    /** Turns automatic backup on, or moves it to another folder; the last run's result goes with the old folder. */
    suspend fun setBackupFolder(uri: String, name: String?) {
        context.dataStore.edit {
            it[backupFolder] = uri
            if (name != null) it[backupFolderName] = name else it.remove(backupFolderName)
            it.remove(backupDone); it.remove(backupFailed); it.remove(backupFailedAt)
        }
    }

    suspend fun clearBackup() {
        context.dataStore.edit {
            it.remove(backupFolder); it.remove(backupFolderName); it.remove(backupDone); it.remove(backupFailed); it.remove(backupFailedAt)
        }
    }

    suspend fun setBackupDone(epochSeconds: Long) {
        context.dataStore.edit {
            it[backupDone] = epochSeconds
            it.remove(backupFailed); it.remove(backupFailedAt)
        }
    }

    suspend fun setBackupFailed(failure: org.openstrap.app.export.BackupFiles.Failure, epochSeconds: Long) {
        context.dataStore.edit {
            it[backupFailed] = failure.name
            it[backupFailedAt] = epochSeconds
        }
    }

    /** The settings a backup carries ([org.openstrap.app.export.SettingsBackup.KEYS]), by key name, as stored. */
    private val updateAutoKey = androidx.datastore.preferences.core.booleanPreferencesKey("update_auto")
    private val updateCheckedKey = longPreferencesKey("update_checked_at")
    private val updateNotifiedKey = stringPreferencesKey("update_notified_tag")
    private val updateInstallingKey = stringPreferencesKey("update_installing_tag")
    private val updateSeenKey = stringPreferencesKey("update_seen_tag")

    /** The daily check for a new OpenStrap release on GitHub ([org.openstrap.app.update.AppUpdater]). On unless turned off. */
    val updateAuto: Flow<Boolean> = context.dataStore.data.map { it[updateAutoKey] ?: true }

    suspend fun setUpdateAuto(on: Boolean) {
        context.dataStore.edit { it[updateAutoKey] = on }
    }

    /** When the release list was last read from GitHub, epoch seconds. */
    val updateChecked: Flow<Long?> = context.dataStore.data.map { it[updateCheckedKey] }

    suspend fun setUpdateChecked(epochSeconds: Long) {
        context.dataStore.edit { it[updateCheckedKey] = epochSeconds }
    }

    /** The tag of the last release the daily check notified about, so each one is said once. */
    suspend fun updateNotified(): String? = context.dataStore.data.first()[updateNotifiedKey]

    suspend fun setUpdateNotified(tag: String) {
        context.dataStore.edit { it[updateNotifiedKey] = tag }
    }

    /** The tag of the newest release the Updates page has shown, so its dot on the avatar goes once it's been seen. */
    val updateSeen: Flow<String?> = context.dataStore.data.map { it[updateSeenKey] }

    suspend fun setUpdateSeen(tag: String) {
        context.dataStore.edit { it[updateSeenKey] = tag }
    }

    /** The tag of the release handed to Android to install, until the new version starts; null when none. */
    suspend fun updateInstalling(): String? = context.dataStore.data.first()[updateInstallingKey]

    suspend fun setUpdateInstalling(tag: String?) {
        context.dataStore.edit { if (tag == null) it.remove(updateInstallingKey) else it[updateInstallingKey] = tag }
    }

    suspend fun backupValues(): Map<String, Any> =
        context.dataStore.data.first().asMap().mapKeys { it.key.name }.filterKeys { it in org.openstrap.app.export.SettingsBackup.KEYS }

    /**
     * Restores [values] from a backup, only those this phone has never set, so restoring twice or onto a phone already
     * set up changes nothing the user chose here. Returns how many were written.
     */
    suspend fun restoreValues(values: Map<String, Any>): Int {
        var written = 0
        context.dataStore.edit { prefs ->
            val present = prefs.asMap().keys.mapTo(HashSet()) { it.name }
            for ((name, v) in org.openstrap.app.export.SettingsBackup.missing(values, present)) {
                when (v) {
                    is Int -> prefs[androidx.datastore.preferences.core.intPreferencesKey(name)] = v
                    is Long -> prefs[longPreferencesKey(name)] = v
                    is Float -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(name)] = v
                    is Boolean -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = v
                    is String -> prefs[stringPreferencesKey(name)] = v
                    else -> continue
                }
                written++
            }
        }
        return written
    }
}

/** The key each sport's [SportSettings][org.openstrap.app.data.SportSettings] is stored under, before the sport's id. */
private const val SPORT_SETTINGS_PREFIX = "sport_settings_"
