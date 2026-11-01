package org.openstrap.app.data

import org.openstrap.app.data.db.SavedWorkout

/**
 * What Ready was set to the last time a sport was started (workouts spec 2.2, 4): Ready opens on it, and Start on
 * Choose's usual card starts with it, skipping Ready. Kept per sport by its tile ([Workouts.tileType]), so a run and
 * a treadmill share theirs; which side was last chosen is kept apart ([Workouts.Sides]).
 *
 * Stored as one line per sport ([encode]): "g=d:5000|a=1|p=1|k=0". Anything unreadable falls back to the default.
 */
data class SportSettings(
    /** The goal ([WorkoutTarget.code]); null for none ("Just run"). */
    val goal: String? = null,
    /** "Ask to end when I stop". */
    val askToEnd: Boolean = true,
    /**
     * GPS where it's offered. No longer turned off anywhere: Ready's where row picks an indoor side instead, so GPS is
     * on wherever it's offered ([WorkoutSetup.plan]). Kept so older lines still read.
     */
    val gps: Boolean = true,
    /** Saving the GPS route for the map: on unless turned off. */
    val keepRoute: Boolean = true,
    /** Strength: how rest ends ([Strength.Rest.code]) and the sets planned, null for open. */
    val rest: String? = null,
    val sets: Int? = null,
    /** Strength: a card after each set for its reps and weight. */
    val log: Boolean = false,
    /** Intervals and games: rounds or periods ([AppWorkout.structure][org.openstrap.app.data.db.AppWorkout]); null for open. */
    val structure: String? = null,
) {
    fun encode(): String = listOfNotNull(
        goal?.let { "g=$it" },
        "a=${bit(askToEnd)}",
        "p=${bit(gps)}",
        // "m", not the old "k": every saved line had k=0 from when the route was off unless turned on, so those read as on now.
        "m=${bit(keepRoute)}",
        rest?.let { "r=$it" },
        sets?.let { "s=$it" },
        "l=${bit(log)}",
        structure?.let { "x=$it" },
    ).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"
        private fun bit(b: Boolean) = if (b) "1" else "0"

        /** [text] as written by [encode]; the defaults for anything missing or unreadable, and for null. */
        fun parse(text: String?): SportSettings {
            val fields = text.orEmpty().split(SEPARATOR).mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
            }.toMap()
            val d = SportSettings()
            fun flag(key: String, default: Boolean) = when (fields[key]) { "1" -> true; "0" -> false; else -> default }
            return SportSettings(
                goal = fields["g"]?.takeIf { WorkoutTarget.parse(it) != null },
                askToEnd = flag("a", d.askToEnd),
                gps = flag("p", d.gps),
                keepRoute = flag("m", d.keepRoute),
                rest = fields["r"]?.takeIf { Strength.Rest.parse(it) != null },
                sets = fields["s"]?.toIntOrNull()?.takeIf { it in Strength.SETS },
                log = flag("l", d.log),
                structure = fields["x"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * A workout about to start, as Ready sets it up or as Choose's usual card takes it from last time: the sport type
 * saved (a side already picked), and what [org.openstrap.app.device.WorkoutSession.start] records with it.
 */
data class WorkoutPlan(
    val sportType: Int,
    val askToEnd: Boolean = true,
    val target: WorkoutTarget? = null,
    val useGps: Boolean = false,
    val keepRoute: Boolean = false,
    val rest: Strength.Rest? = null,
    val setsTarget: Int? = null,
    /** Strength: a log card after each set. */
    val logSets: Boolean = false,
    /** Rounds or periods, as [org.openstrap.app.data.db.AppWorkout.structure]. */
    val structure: String? = null,
)

/** Choose's sports (workouts spec 2.1): [usual] gets the big card with Start, [recent] the small ones, by tile. */
data class ChooseSports(val usual: Int?, val recent: List<Int>)

/** One of Choose's Recent chips: a sport by its tile, or a saved workout by its name. */
sealed interface RecentChip {
    data class Sport(val type: Int) : RecentChip

    /** A saved workout: Ready opens on its [structure]. */
    data class Saved(val name: String, val sport: Int, val structure: String) : RecentChip
}

/** Ready's choices worked out from what you did before (workouts spec 2.2). Pure, so it's tested without the screen. */
object WorkoutSetup {
    /** Your usual is the sport you started most in this many days. */
    const val USUAL_DAYS = 28

    /**
     * Choose's sports from the workouts you [started] (sport, start), newest first: your usual, the sport started most
     * in the last [USUAL_DAYS] (ties to the latest), and the next two sports you did. With no usual, up to three
     * recent ones, and a new user's Run, Walk and Strength. Sports count by tile, so a treadmill is a run.
     */
    fun choose(started: List<Pair<Int, Long>>, now: Long): ChooseSports {
        val mine = started.filter { it.first != Workouts.GENERIC_ACTIVITY }.map { Workouts.tileType(it.first) to it.second }
        val usual = mine.filter { it.second >= now - USUAL_DAYS * 86_400L }.groupBy { it.first }
            .maxWithOrNull(compareBy<Map.Entry<Int, List<Pair<Int, Long>>>> { it.value.size }.thenBy { e -> e.value.maxOf { it.second } })
            ?.key
        val recent = Workouts.recentSports(mine.map { it.first }, n = 4).filter { it != usual }
        if (usual != null) return ChooseSports(usual, recent.take(2))
        return ChooseSports(null, (recent + Workouts.DEFAULT_SPORTS.filter { it !in recent }).take(3))
    }

    /** How many saved workouts Choose's Recent row shows. */
    const val SAVED_CHIPS = 2

    /**
     * Choose's Recent row (workouts spec 2.1): the [recent] sports, then the newest [SAVED_CHIPS] of the [saved]
     * workouts (newest first) whose rounds still read.
     */
    fun recentChips(recent: List<Int>, saved: List<SavedWorkout>): List<RecentChip> =
        recent.map { RecentChip.Sport(it) } +
            saved.filter { Structure.parse(it.structure) != null }.take(SAVED_CHIPS).map { RecentChip.Saved(it.name, it.sport, it.structure) }

    /**
     * Choose's Start on your usual (workouts spec 2.1): a plan from last time's [settings] for [type], skipping Ready.
     * Its side is the one you last chose ([sides]); GPS is on where it's offered and the phone's location can be used
     * ([location]).
     */
    fun plan(type: Int, settings: SportSettings, sides: Map<Int, Boolean>, location: Boolean): WorkoutPlan {
        val indoor = Workouts.indoorSide(type, sides)
        val sport = Workouts.sidesOf(type)?.type(indoor == true) ?: type
        val gps = Workouts.gpsOffered(type, indoor) && location
        val strength = sport in Strength.SPORTS
        // Only a goal Ready would offer now: a distance where there is one, and no kcal goal (Ready has none to show).
        val goal = WorkoutTarget.parse(settings.goal)?.takeIf { offered(it, hasDistance = gps || sport in Workouts.ON_FOOT, kcal = false) }
        return WorkoutPlan(
            sportType = sport, askToEnd = settings.askToEnd, target = goal, useGps = gps, keepRoute = gps && settings.keepRoute,
            rest = (Strength.Rest.parse(settings.rest) ?: Strength.Rest.DEFAULT).takeIf { strength },
            setsTarget = settings.sets.takeIf { strength }, logSets = strength && settings.log,
            // HIIT's rounds, or a run's reps.
            structure = Structure.parse(settings.structure)?.takeIf { Structure.fits(it, sport) }?.code,
        )
    }

    /** Mind and body: the length chips after Open. */
    val LENGTHS = listOf(15, 30, 45, 60).map { WorkoutTarget.Time(it * 60L) }

    /**
     * Whether [goal] can be aimed for now: a distance only where the workout has one, from GPS or steps
     * ([hasDistance]); kcal only with a profile to work it out from ([kcal]).
     */
    fun offered(goal: WorkoutTarget?, hasDistance: Boolean, kcal: Boolean): Boolean = when (goal) {
        is WorkoutTarget.Distance -> hasDistance
        is WorkoutTarget.Kcal -> kcal
        else -> true
    }

    /** The kinds of goal Ready's goal card offers a distance or cardio sport (board ReadyFinal): its chips. */
    enum class GoalKind { None, Distance, Time, Zone, Intervals }

    /**
     * The goal kinds for [type]: Just go, a distance where the workout has one ([hasDistance]), a time, a zone, and
     * intervals where reps fit the sport ([Structure.fits], runs).
     */
    fun goalKinds(type: Int, hasDistance: Boolean): List<GoalKind> = listOfNotNull(
        GoalKind.None,
        GoalKind.Distance.takeIf { hasDistance },
        GoalKind.Time,
        GoalKind.Zone,
        GoalKind.Intervals.takeIf { Structure.fits(Structure.Reps(400, null, 90, 6), type) },
    )

    /** The kind of what Ready opens on: reps are Intervals, else the goal's kind; a kcal goal has no chip, so none. */
    fun goalKind(goal: WorkoutTarget?, reps: Structure.Reps?): GoalKind = when {
        reps != null -> GoalKind.Intervals
        goal is WorkoutTarget.Distance -> GoalKind.Distance
        goal is WorkoutTarget.Time -> GoalKind.Time
        goal is WorkoutTarget.Zones -> GoalKind.Zone
        else -> GoalKind.None
    }

    /**
     * A zone button tapped on Ready: the zone alone, unless one zone is chosen and another is tapped, which makes the
     * range between them. Tapping again after a range starts over from the zone tapped.
     */
    fun pickZone(current: WorkoutTarget.Zones?, tapped: Int): WorkoutTarget.Zones = when {
        current != null && current.low == current.high && current.low != tapped -> WorkoutTarget.Zones(minOf(current.low, tapped), maxOf(current.low, tapped))
        else -> WorkoutTarget.Zones(tapped, tapped)
    }

    /** What each kind opens on before it's been chosen for the sport: 5 km, 30 min, zone 2. */
    val DEFAULT_DISTANCE = WorkoutTarget.Distance(5000)
    val DEFAULT_TIME = WorkoutTarget.Time(30 * 60)
    val DEFAULT_ZONES = WorkoutTarget.Zones(1, 1)
}
