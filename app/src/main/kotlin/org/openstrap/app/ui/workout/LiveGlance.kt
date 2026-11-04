package org.openstrap.app.ui.workout

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.openstrap.app.R
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.i18n.Strings
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.presentation.Pace
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.WorkoutWords
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.openstrap.app.presentation.WorkoutCopy
import org.openstrap.app.data.HeartZones

/** What a [LiveGlance] is lit in: the zone's colour, work red, rest green, yoga purple, paused grey, asking amber. */
enum class GlanceTint { Zone, Work, Rest, Mind, Paused, Asking }

/** The next thing to do from the Today hero or the floating bar, or the second one beside it. */
enum class GlanceAction { Pause, Resume, KeepGoing, End, Open, SetDone, StartSet, AddRest, Skip }

/**
 * Where an interval workout is ([GlanceInput.interval]): its [phase], round [round] of [rounds] (from 1), the seconds
 * [left] in a timed phase or the metres [metersLeft] in a run rep, the rest after this work ([nextRest]), and the
 * heart rate's [drop] since the work ended. [everyMinute] work ends on Done.
 */
data class IntervalMoment(
    val phase: org.openstrap.app.data.Phase,
    val round: Int,
    val rounds: Int,
    val left: Long? = null,
    val metersLeft: Float? = null,
    val nextRest: Long? = null,
    val drop: Int? = null,
    val everyMinute: Boolean = false,
    val reps: Boolean = false,
    /** A game's periods: each one's length, for the time in it. */
    val periodLength: Long? = null,
)

/**
 * Where a strength workout is ([GlanceInput.strength]): in set [setNumber] (or before it), its [setSeconds], the rest
 * [restLeft] of [restTotal] and the level that ends it sooner ([restBelow]), the [drop] in heart rate since the set
 * ended, and what's next ([nextLoad], "10 × 60 kg").
 */
data class StrengthMoment(
    val inSet: Boolean,
    val setNumber: Int,
    val setsTarget: Int?,
    val done: Int,
    val setSeconds: Long = 0,
    val restLeft: Long? = null,
    val restTotal: Long? = null,
    val restBelow: Int? = null,
    val drop: Int? = null,
    val exercise: String? = null,
    val nextLoad: String? = null,
)

/** The bar under the figure: how far toward the goal ([drain] false, it fills), or rest running out (it empties). */
data class GlanceProgress(val fraction: Float, val drain: Boolean = false)

/**
 * A workout at a glance while its live screen is tucked away (workouts spec 2.10): the one source for the Today hero
 * and the floating bar, so the two never disagree.
 *
 * [figure] is the one number that matters now (km, minutes, bpm) with its [unit]; [line1] and [line2] the bar's two
 * lines; [progress] the goal's bar; [tint] and [zone] its colour; [action] the next thing you'd do and [second] the one
 * beside it. The hero also shows [goal] and [toGo] under its bar and three [figures] (value, label).
 */
data class LiveGlance(
    val figure: String?,
    val unit: String?,
    val line1: String,
    val line2: String?,
    val progress: GlanceProgress?,
    val tint: GlanceTint,
    val zone: Int?,
    val action: GlanceAction,
    val actionLabel: String,
    val second: GlanceAction? = null,
    val secondLabel: String? = null,
    /** The action's name where the bar has less room ("Set 4" for "Start set 4"). */
    val shortLabel: String? = null,
    /** The hero's Next box ("Set 4, bench press 10 × 60 kg"). */
    val next: String? = null,
    val goal: String? = null,
    val toGo: String? = null,
    val figures: List<Pair<String?, String>> = emptyList(),
    /** The total time so far, "24:51". */
    val clock: String = "",
    /** A GPS workout's last whole kilometre for the hero's strip: "Km 4 in 5:55" and "Avg 146 bpm. Fastest so far was km 3." */
    val split: Pair<String, String?>? = null,
    /** The bar shows the live bpm in dots beside its action (only where the action is Pause). */
    val bpm: Int? = null,
)

/**
 * The workout as it is now, for [LiveGlance.of]: time and pauses, the strap, the distance and pace for a GPS workout,
 * the goal, and the stop question while it's asked ([stoppedSince], when the wearer last moved, epoch seconds).
 */
data class GlanceInput(
    val sportType: Int,
    val now: Long,
    val activeSeconds: Long,
    val pausedAt: Long?,
    val bpm: Int?,
    val connected: Boolean,
    val maxHr: Int,
    /** Your heart rate zones as stored, for the zone shown. */
    val zones: HeartZones,
    val gps: Boolean,
    val distanceMeters: Float?,
    val distanceFromSteps: Boolean = false,
    /** Seconds per kilometre now and over the whole workout; null with no pace yet. */
    val pace: Double? = null,
    val averagePace: Double? = null,
    val target: WorkoutTarget? = null,
    val activeKcal: Float? = null,
    val stoppedSince: Long? = null,
    val inCar: Boolean = false,
    /** The last whole kilometre, its average heart rate, and the fastest so far by index. */
    val lastSplit: Route.Split? = null,
    val lastSplitBpm: Int? = null,
    val fastestSplit: Int? = null,
    val strength: StrengthMoment? = null,
    val interval: IntervalMoment? = null,
    /** A game's effort so far, 1 to 5 ([org.openstrap.app.data.Games.effort]). */
    val effort: Int? = null,
    /** Average heart rate so far, for an indoor workout's figures. */
    val averageBpm: Int? = null,
)

/**
 * The glance of the workout [session] records, once a second and whenever what it shows changes; null while nothing
 * records. Worked out off the screen, so the Today hero and the floating bar each read it without the app around them
 * recomposing every second, and on [work], since each second's figures walk the whole route (audit C7).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun liveGlance(
    session: WorkoutSession,
    liveHeartRate: Flow<Int?>,
    connection: Flow<ConnectionState>,
    /** Your heart rate zones as stored. */
    zones: Flow<HeartZones>,
    work: CoroutineDispatcher = Dispatchers.Default,
): Flow<LiveGlance?> {
    val ticks = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    val strap = combine(liveHeartRate, connection, zones) { bpm, c, z -> Triple(bpm, c is ConnectionState.Connected, z) }
    val figures = combine(session.route, session.kcal, session.stats) { route, kcal, stats -> Triple(route, kcal, stats) }
    // Nothing ticks while nothing records.
    val recording = session.state.map { it is WorkoutState.Recording }.distinctUntilChanged()
    return recording.flatMapLatest { on -> if (!on) flowOf(null) else glanceTicks(session, strap, figures, ticks) }.flowOn(work)
}

/** [liveGlance] while a workout records. */
private fun glanceTicks(
    session: WorkoutSession,
    strap: Flow<Triple<Int?, Boolean, HeartZones>>,
    figures: Flow<Triple<List<Route.Point>, WorkoutRecorder.Kcal?, WorkoutRecorder.Stats?>>,
    ticks: Flow<Long>,
): Flow<LiveGlance?> {
    // The splits are worked out again only when the route grows, not every second.
    var splitsOf = -1 to emptyList<Route.Split>()
    return combine(session.state, strap, figures, ticks) { st, (bpm, connected, zones), (route, kcal, stats), now ->
        val r = st as? WorkoutState.Recording ?: return@combine null
        val w = r.workout
        val active = WorkoutRecorder.activeSeconds(w, now)
        val distance = w.distanceMeters
        if (w.useGps && splitsOf.first != route.size) splitsOf = route.size to Route.splits(route, w.pauseRanges)
        val whole = splitsOf.second.filter { it.meters >= 1000 }
        val last = whole.lastOrNull()
        val lastBpm = last?.let { s ->
            val from = whole.getOrNull(whole.size - 2)?.endT ?: route.firstOrNull()?.t ?: s.endT
            WorkoutCopy.averageOver(session.history.value, from, s.endT)
        }
        Glance.of(
            GlanceInput(
                w.sportType, now, active, w.pausedAt, bpm, connected, r.maxHeartRate, zones, w.useGps, distance, w.distanceFromSteps,
                pace = if (w.useGps) Route.currentPace(route) else null,
                averagePace = Pace.of(distance, active),
                target = WorkoutTarget.parse(w.target), activeKcal = kcal?.active,
                stoppedSince = r.endPrompt, inCar = r.inCar, averageBpm = stats?.average,
                lastSplit = last, lastSplitBpm = lastBpm, fastestSplit = whole.minByOrNull { it.pace }?.index,
                strength = if (r.strength) strengthMoment(r, now, bpm, session.history.value) else null,
                interval = intervalMoment(r, now, bpm, WorkoutCopy.readings(session.history.value, session.recent.value), route),
                effort = org.openstrap.app.data.Games.effort(session.zoneSeconds.value.toIntArray()),
            ),
        )
    }.distinctUntilChanged()
}


/** Where strength workout [r] is at [now], for its glance; [history] is its live readings, for the drop since the set. */
internal fun strengthMoment(
    r: WorkoutState.Recording, now: Long, bpm: Int?, history: List<Pair<Long, Int>>, strings: Strings = Strings.app,
): StrengthMoment {
    val last = r.sets.lastOrNull()
    val done = r.sets.count { it.end != null }
    val ended = last?.end
    val atEnd = ended?.let { Workouts.heartRateAt(history, it) ?: org.openstrap.app.data.Strength.peak(history, it - 5, it) }
    val load = r.exercise?.let { e ->
        r.sets.lastOrNull { it.reps != null && it.exercise.equals(e, ignoreCase = true) }
            ?.let { s ->
                strings.format(R.string.workout_glance_load, s.reps ?: 0, org.openstrap.app.data.Strength.kgText(s.weightKg ?: 0f))
            }
    }
    return StrengthMoment(
        inSet = r.inSet, setNumber = if (r.inSet) r.sets.size else r.sets.size + 1, setsTarget = r.workout.setsTarget, done = done,
        setSeconds = if (r.inSet && last != null) (now - last.start).coerceAtLeast(0) else 0,
        restLeft = r.restUntil?.let { it - now }, restTotal = r.restUntil?.let { u -> ended?.let { u - it } },
        restBelow = org.openstrap.app.data.Strength.Rest.parse(r.workout.rest)?.belowBpm,
        drop = if (atEnd != null && bpm != null && r.resting) atEnd - bpm else null,
        exercise = r.exercise, nextLoad = load,
    )
}

/**
 * Where interval workout [r] is at [now], for its glance and live screen; null without rounds. [readings] are its
 * live readings (time, bpm), for the drop since the work ended; [route] its GPS fixes, for a run rep's metres to go.
 */
internal fun intervalMoment(r: WorkoutState.Recording, now: Long, bpm: Int?, readings: List<Pair<Long, Int>>, route: List<Route.Point>): IntervalMoment? {
    val s = r.structure ?: return null
    val p = r.phase(now) ?: return null
    val active = WorkoutRecorder.activeSeconds(r.workout, now)
    val rest = when (s) {
        is org.openstrap.app.data.Structure.Timed -> s.rest
        is org.openstrap.app.data.Structure.Reps -> s.rest
        is org.openstrap.app.data.Structure.EveryMinute -> null
        is org.openstrap.app.data.Structure.Periods -> s.rest
    }
    return when (p) {
        is org.openstrap.app.data.Phase.Work -> {
            val meters = (s as? org.openstrap.app.data.Structure.Reps)?.meters?.let { goal ->
                val ran = route.filter { it.t >= p.start }.takeIf { it.size > 1 }?.let { Route.distance(it, r.workout.pauseRanges).toFloat() } ?: 0f
                (goal - ran).coerceAtLeast(0f)
            }
            val minuteLeft = (s as? org.openstrap.app.data.Structure.EveryMinute)?.let {
                WorkoutRecorder.activeSeconds(r.workout, p.start) + it.period - active
            }
            IntervalMoment(
                p, p.round + 1, s.rounds, left = (p.endsAt?.let { it - active } ?: minuteLeft)?.coerceAtLeast(0), metersLeft = meters,
                nextRest = rest.takeIf { p.round + 1 < s.rounds }, everyMinute = s is org.openstrap.app.data.Structure.EveryMinute,
                periodLength = (s as? org.openstrap.app.data.Structure.Periods)?.length,
                reps = s is org.openstrap.app.data.Structure.Reps,
            )
        }
        is org.openstrap.app.data.Phase.Rest -> {
            val atEnd = Workouts.heartRateAt(readings, p.start)
            IntervalMoment(
                p, p.round + 2, s.rounds, left = (p.endsAt - active).coerceAtLeast(0),
                drop = if (atEnd != null && bpm != null) atEnd - bpm else null, reps = s is org.openstrap.app.data.Structure.Reps,
            )
        }
        org.openstrap.app.data.Phase.WarmUp -> IntervalMoment(p, 1, s.rounds)
        org.openstrap.app.data.Phase.Done -> IntervalMoment(p, s.rounds, s.rounds)
    }
}

/** The glance's words are in res/values/strings_workout_glance.xml, kept short: they're read at a glance mid workout. */
object Glance {
    /** [i] worked out (workouts spec 2.10's table): asking first, then paused, then the sport's own moment. */
    fun of(i: GlanceInput, strings: Strings = Strings.app): LiveGlance {
        val sport = Workouts.sportName(i.sportType)
        val bpm = i.bpm.takeIf { i.connected && i.pausedAt == null }
        val zone = WorkoutRecorder.liveZone(bpm, i.maxHr, i.zones)
        val distance = Workouts.family(i.sportType) == Workouts.Family.Distance && i.gps
        val clock = Units.workoutClock(i.activeSeconds)
        val figure = if (distance) i.distanceMeters?.let { kmFigure(it) } ?: "0.00" else bpm?.toString()
        val unit = when {
            distance -> strings.get(R.string.workout_glance_unit_km)
            figure != null -> strings.get(R.string.workout_glance_unit_bpm)
            else -> null
        }
        val progress = progress(i)
        val (goal, toGo) = goalLine(i, strings)
        val activeKcal = i.activeKcal?.toInt()?.toString() to strings.get(R.string.workout_glance_figure_active_kcal)
        val figures = if (distance) listOf(
            Pace.clock(i.pace) to strings.get(R.string.workout_glance_figure_pace),
            bpm?.toString() to bpmLabel(zone, strings),
            activeKcal,
        ) else listOfNotNull(
            i.distanceMeters?.takeIf { it >= Units.SHOWN_FROM_METERS }?.let {
                Units.km(it, Units.Precision.Workout) to strings.get(
                    if (i.distanceFromSteps) R.string.workout_glance_figure_km_from_steps else R.string.workout_glance_figure_km,
                )
            },
            i.averageBpm?.toString() to strings.get(R.string.workout_glance_figure_average_bpm),
            activeKcal,
        )
        val base = LiveGlance(
            figure, unit, "", null, progress, GlanceTint.Zone, zone, GlanceAction.Pause, strings.get(R.string.workout_glance_pause),
            goal = goal, toGo = toGo, figures = figures, split = splitLine(i, strings), clock = clock,
        )
        i.stoppedSince?.let { since ->
            val minutes = ((i.now - since) / 60).coerceAtLeast(1)
            return base.copy(
                line1 = strings.get(R.string.workout_glance_still_going),
                line2 = if (i.inCar) {
                    strings.get(R.string.workout_glance_in_car)
                } else {
                    strings.format(R.string.workout_glance_no_movement, minutes)
                },
                tint = GlanceTint.Asking, action = GlanceAction.KeepGoing, actionLabel = strings.get(R.string.workout_glance_keep_going),
                second = GlanceAction.End, secondLabel = strings.get(R.string.workout_glance_end),
            )
        }
        i.pausedAt?.let { at ->
            val done = strings.format(
                R.string.workout_glance_paused_so_far, sport,
                if (distance && i.distanceMeters != null) kmWithUnit(i.distanceMeters, strings = strings) else clock,
            )
            return base.copy(
                line1 = strings.format(R.string.workout_glance_paused_for, Units.workoutClock((i.now - at).coerceAtLeast(0))), line2 = done,
                tint = GlanceTint.Paused, zone = null, action = GlanceAction.Resume,
                actionLabel = strings.get(R.string.workout_glance_resume),
            )
        }
        val state = when {
            !i.connected -> WorkoutWords.STRAP_GONE
            bpm == null -> strings.get(R.string.workout_glance_waiting)
            else -> null
        }
        i.strength?.let { s -> return strength(base, s, bpm, i, strings) }
        i.interval?.let { m -> return interval(base, m, bpm, i, strings) }
        if (Workouts.family(i.sportType) == Workouts.Family.MindBody) return mind(base, i, strings)
        if (Workouts.family(i.sportType) == Workouts.Family.Games) return game(base, null, bpm, i, strings)
        if (distance) {
            val first = listOfNotNull(kmWithUnit(i.distanceMeters ?: 0f, strings = strings), Pace.text(i.pace)).joinToString(", ")
            val second = listOfNotNull(
                clock, toGoShort(i, strings) ?: state ?: zone?.let { strings.format(R.string.workout_glance_zone_lower, it + 1) },
            ).joinToString(", ")
            return base.copy(line1 = first, line2 = second, bpm = bpm, actionLabel = strings.get(R.string.workout_glance_pause))
        }
        val zoneLine = zone?.let { WorkoutCopy.zoneLine(it) }
        return base.copy(
            line1 = "$sport, $clock", line2 = listOfNotNull(state ?: zoneLine, toGoShort(i, strings)).joinToString(", ").ifEmpty { null },
            bpm = bpm,
        )
    }

    /** A bpm figure's label: "bpm, zone 3", or "bpm" with no zone. */
    private fun bpmLabel(zone: Int?, strings: Strings): String =
        zone?.let { strings.format(R.string.workout_glance_figure_bpm_zone, it + 1) } ?: strings.get(R.string.workout_glance_figure_bpm)

    /** "142 bpm", and "142 bpm, down 20" once the heart rate has come down since the work ended. */
    private fun heart(bpm: Int, drop: Int?, strings: Strings): String =
        drop?.takeIf { it > 0 }?.let { strings.format(R.string.workout_glance_bpm_down, bpm, it) }
            ?: strings.format(R.string.workout_glance_bpm, bpm)

    /**
     * Strength's moments (spec 2.10's table): in a set, the set's clock with Set done; resting, the rest left draining
     * in green with Start set N and +30 s, the drop in heart rate and what's next; between sets, Start set N.
     */
    private fun strength(base: LiveGlance, s: StrengthMoment, bpm: Int?, i: GlanceInput, strings: Strings): LiveGlance {
        val figures = listOf(
            bpm?.toString() to bpmLabel(base.zone, strings),
            s.done.toString() to strings.plural(R.plurals.workout_glance_figure_sets_done, s.done),
            i.activeKcal?.toInt()?.toString() to strings.get(R.string.workout_glance_figure_active_kcal),
        )
        val what = s.exercise?.lowercase()?.let { e -> listOfNotNull(e, s.nextLoad).joinToString(" ") }
        val next = what?.let { strings.format(R.string.workout_glance_next_set_what, s.setNumber, it) }
            ?: strings.format(R.string.workout_glance_set, s.setNumber)
        val bpmText = bpm?.let { strings.format(R.string.workout_glance_bpm, it) }
        val start = base.copy(
            action = GlanceAction.StartSet, actionLabel = strings.format(R.string.workout_glance_start_set, s.setNumber),
            shortLabel = strings.format(R.string.workout_glance_set, s.setNumber),
            figures = figures, progress = null, goal = null, toGo = null,
        )
        return when {
            s.inSet -> {
                val time = Units.workoutClock(s.setSeconds)
                base.copy(
                    figure = Units.workoutClock(s.setSeconds, Units.DOT_LOCALE), unit = strings.get(R.string.workout_glance_unit_in_set),
                    line1 = s.setsTarget?.let { strings.format(R.string.workout_glance_set_of_time, s.setNumber, it, time) }
                        ?: strings.format(R.string.workout_glance_set_time, s.setNumber, time),
                    line2 = listOfNotNull(s.exercise, bpmText).joinToString(", ").ifEmpty { null },
                    action = GlanceAction.SetDone, actionLabel = strings.get(R.string.workout_glance_set_done),
                    figures = figures, progress = null, goal = null, toGo = null,
                )
            }
            s.restLeft != null -> {
                val left = Units.workoutClock(s.restLeft.coerceAtLeast(0))
                val heart = bpm?.let { heart(it, s.drop, strings) }
                start.copy(
                    figure = Units.workoutClock(s.restLeft.coerceAtLeast(0), Units.DOT_LOCALE),
                    unit = strings.get(R.string.workout_glance_unit_left),
                    tint = GlanceTint.Rest,
                    line1 = strings.format(R.string.workout_glance_rest_left, left),
                    line2 = listOfNotNull(heart, strings.format(R.string.workout_glance_set_next_lower, s.setNumber)).joinToString(", "),
                    progress = s.restTotal?.takeIf { it > 0 }?.let { GlanceProgress((s.restLeft.toFloat() / it).coerceIn(0f, 1f), drain = true) },
                    goal = restGoal(s, bpm, strings),
                    second = GlanceAction.AddRest, secondLabel = strings.get(R.string.workout_glance_add_rest_30), next = next,
                )
            }
            else -> start.copy(
                line1 = when {
                    s.done == 0 -> strings.get(R.string.workout_glance_ready_for_set_1)
                    s.setsTarget != null -> strings.format(R.string.workout_glance_rest_over_of, s.setNumber, s.setsTarget)
                    else -> strings.format(R.string.workout_glance_rest_over, s.setNumber)
                },
                line2 = listOfNotNull(s.exercise, bpmText).joinToString(", ").ifEmpty { null },
                next = next,
            )
        }
    }

    /** The hero's line under the rest's bar: "122 bpm, down 20. Ends sooner once you stay under 110."; null with neither. */
    private fun restGoal(s: StrengthMoment, bpm: Int?, strings: Strings): String? {
        val heart = bpm?.let { b ->
            s.drop?.takeIf { it > 0 }?.let { strings.format(R.string.workout_glance_rest_bpm_down_sentence, b, it) }
                ?: strings.format(R.string.workout_glance_rest_bpm_sentence, b)
        }
        val sooner = s.restBelow?.let { strings.format(R.string.workout_glance_rest_ends_sooner, it) }
        return if (heart != null && sooner != null) strings.format(R.string.workout_sentences, heart, sooner) else heart ?: sooner
    }

    /**
     * Mind and body (spec 2.10's table): the minutes of the length in violet, one line ("Yoga, 23:40 of 45 min"), a
     * quieter Pause and no heart rate in dots.
     */
    private fun mind(base: LiveGlance, i: GlanceInput, strings: Strings): LiveGlance {
        val length = (i.target as? WorkoutTarget.Time)?.let { it.seconds / 60 }
        val sport = Workouts.sportName(i.sportType)
        return base.copy(
            figure = "${i.activeSeconds / 60}",
            unit = length?.let { strings.format(R.string.workout_glance_unit_min_of, it) } ?: strings.get(R.string.workout_glance_unit_min),
            line1 = length?.let { strings.format(R.string.workout_glance_mind_of, sport, base.clock, it) } ?: "$sport, ${base.clock}",
            line2 = null, tint = GlanceTint.Mind, zone = null, bpm = null, goal = null, toGo = null, figures = emptyList(),
        )
    }

    /**
     * Games (spec 2.10's table): the period and the time in it, the zone and the effort, and Half time; in the break, its
     * time left and Start the next period. Free play has no periods: the sport and its time.
     */
    private fun game(base: LiveGlance, m: IntervalMoment?, bpm: Int?, i: GlanceInput, strings: Strings): LiveGlance {
        val zone = base.zone?.let { strings.format(R.string.workout_glance_zone, it + 1) }
        val effort = i.effort?.let { strings.format(R.string.workout_glance_effort_lower, it) }
        val figures = listOf(
            bpm?.toString() to bpmLabel(base.zone, strings),
            i.effort?.let { strings.format(R.string.workout_glance_effort_of_5, it) } to strings.get(R.string.workout_glance_figure_effort),
            i.activeKcal?.toInt()?.toString() to strings.get(R.string.workout_glance_figure_active_kcal),
        )
        val heart = listOfNotNull(zone, effort).joinToString(", ").ifEmpty { null }
        val game = base.copy(figures = figures, goal = null, toGo = null, progress = null, bpm = bpm, line2 = heart)
        if (m == null || m.periodLength == null) return game.copy(line1 = "${Workouts.sportName(i.sportType)}, ${base.clock}")
        val period = StartCopy.periodName(m.round - 1, m.rounds, strings)
        return when (m.phase) {
            is org.openstrap.app.data.Phase.Work -> {
                val inPeriodS = (m.periodLength - (m.left ?: 0)).coerceAtLeast(0)
                val inPeriod = Units.workoutClock(inPeriodS)
                game.copy(
                    figure = Units.workoutClock(inPeriodS, Units.DOT_LOCALE),
                    unit = period.lowercase(),
                    line1 = "$period, $inPeriod",
                    action = GlanceAction.Skip, actionLabel = StartCopy.breakName(m.rounds, strings),
                )
            }
            is org.openstrap.app.data.Phase.Rest -> {
                val left = Units.workoutClock(m.left ?: 0)
                game.copy(
                    figure = Units.workoutClock(m.left ?: 0, Units.DOT_LOCALE), unit = strings.get(R.string.workout_glance_unit_left),
                    tint = GlanceTint.Rest,
                    line1 = strings.format(R.string.workout_glance_break_left, StartCopy.breakName(m.rounds, strings), left),
                    action = GlanceAction.Skip, actionLabel = strings.format(R.string.workout_glance_start_period, period.lowercase()),
                    shortLabel = strings.get(R.string.workout_glance_start),
                )
            }
            else -> game.copy(line1 = strings.format(R.string.workout_glance_full_time, base.clock))
        }
    }

    /**
     * Intervals' moments (spec 2.10's table): work in work red with the seconds left (or the metres of a run rep) and
     * Pause; rest in rest green with Skip rest and +10 s; warming up with Start rounds; all rounds done.
     */
    private fun interval(base: LiveGlance, m: IntervalMoment, bpm: Int?, i: GlanceInput, strings: Strings): LiveGlance {
        if (m.periodLength != null) return game(base, m, bpm, i, strings)
        val start = base.copy(goal = null, toGo = null, progress = null)
        val bpmText = bpm?.let { strings.format(R.string.workout_glance_bpm, it) }
        return when (m.phase) {
            is org.openstrap.app.data.Phase.Work -> {
                val figure = m.metersLeft?.let { "${it.toInt()}" } ?: m.left?.toString()
                start.copy(
                    figure = figure,
                    unit = strings.get(
                        when {
                            m.metersLeft != null -> R.string.workout_glance_unit_m_to_go
                            m.everyMinute -> R.string.workout_glance_unit_s_left_minute
                            else -> R.string.workout_glance_unit_s_left
                        },
                    ),
                    tint = GlanceTint.Work,
                    line1 = strings.format(
                        if (m.reps) R.string.workout_glance_work_rep else R.string.workout_glance_work_round, m.round, m.rounds,
                    ),
                    line2 = listOfNotNull(
                        m.nextRest?.let { strings.format(R.string.workout_glance_then_rest, StartCopy.restText(it, strings)) }
                            ?: strings.get(R.string.workout_glance_last_one).takeIf { m.round == m.rounds },
                        bpmText,
                    ).joinToString(", ").ifEmpty { null },
                    bpm = bpm,
                    action = if (m.everyMinute) GlanceAction.Skip else GlanceAction.Pause,
                    actionLabel = strings.get(if (m.everyMinute) R.string.workout_glance_done else R.string.workout_glance_pause),
                )
            }
            is org.openstrap.app.data.Phase.Rest -> start.copy(
                figure = m.left?.toString(), unit = strings.get(R.string.workout_glance_unit_s_rest), tint = GlanceTint.Rest,
                line1 = strings.format(
                    if (m.reps) R.string.workout_glance_rest_rep_next else R.string.workout_glance_rest_round_next, m.round,
                ),
                line2 = bpm?.let { heart(it, m.drop, strings) },
                action = GlanceAction.Skip, actionLabel = strings.get(R.string.workout_glance_skip_rest),
                second = GlanceAction.AddRest, secondLabel = strings.get(R.string.workout_glance_add_rest_10),
            )
            org.openstrap.app.data.Phase.WarmUp -> start.copy(
                line1 = strings.format(R.string.workout_glance_warming_up, base.clock), line2 = bpmText, action = GlanceAction.Skip,
                actionLabel = strings.get(R.string.workout_glance_start_rounds), bpm = null,
            )
            org.openstrap.app.data.Phase.Done -> start.copy(
                line1 = strings.format(
                    if (m.reps) R.string.workout_glance_all_reps_done else R.string.workout_glance_all_rounds_done, m.rounds,
                ),
                line2 = listOfNotNull(base.clock, bpmText).joinToString(", "), bpm = bpm,
            )
        }
    }

    /** The hero's split strip: "Km 4 in 5:55", then its average and which was fastest; null before a whole kilometre. */
    fun splitLine(i: GlanceInput, strings: Strings = Strings.app): Pair<String, String?>? {
        val s = i.lastSplit ?: return null
        val fastest = i.fastestSplit?.takeIf { it != s.index }?.let { strings.format(R.string.workout_glance_fastest_was, it + 1) }
            ?: strings.get(R.string.workout_glance_fastest_this).takeIf { s.index > 0 }
        val avg = i.lastSplitBpm?.let { strings.format(R.string.workout_glance_split_average, it) }
        val line = if (avg != null && fastest != null) strings.format(R.string.workout_sentences, avg, fastest) else avg ?: fastest
        return WorkoutWords.split(s.index, s.seconds) to line
    }

    /** The goal's bar: how far toward a distance, time or kcal goal. A zone goal has none. */
    fun progress(i: GlanceInput): GlanceProgress? = when (val t = i.target) {
        is WorkoutTarget.Distance -> GlanceProgress(((i.distanceMeters ?: 0f) / t.meters).coerceIn(0f, 1f))
        is WorkoutTarget.Time -> GlanceProgress((i.activeSeconds.toFloat() / t.seconds).coerceIn(0f, 1f))
        is WorkoutTarget.Kcal -> GlanceProgress(((i.activeKcal ?: 0f) / t.kcal).coerceIn(0f, 1f))
        is WorkoutTarget.Zones, null -> null
    }

    /** The hero's line under its bar: "Goal 5 km" and "About 4 min to go", or the goal met. */
    fun goalLine(i: GlanceInput, strings: Strings = Strings.app): Pair<String?, String?> {
        val t = i.target ?: return null to null
        val label = when (t) {
            is WorkoutTarget.Distance -> t.label
            is WorkoutTarget.Time -> t.label
            is WorkoutTarget.Kcal -> t.label
            is WorkoutTarget.Zones ->
                return strings.format(R.string.workout_glance_target, t.label.lowercase()) to null
        }
        val goal = strings.format(R.string.workout_glance_goal, label)
        if ((progress(i)?.fraction ?: 0f) >= 1f) return goal to strings.get(R.string.workout_glance_reached)
        val left = when (t) {
            is WorkoutTarget.Distance -> {
                val pace = i.pace ?: i.averagePace
                val meters = t.meters - (i.distanceMeters ?: 0f)
                pace?.let { p ->
                    val seconds = meters / 1000.0 * p
                    if (seconds < 60) {
                        strings.get(R.string.workout_glance_about_under_a_minute)
                    } else {
                        strings.format(R.string.workout_glance_about_minutes_to_go, ceil(seconds / 60).toInt())
                    }
                }
            }
            is WorkoutTarget.Time -> minutesToGo((t.seconds - i.activeSeconds).toDouble(), strings)
            is WorkoutTarget.Kcal -> kcalToGo(t, i, strings)
        }
        return goal to left
    }

    /** The bar's short "to go": "0.68 km to go", "5 min to go", "120 kcal to go"; null without a goal or once met. */
    private fun toGoShort(i: GlanceInput, strings: Strings): String? {
        if ((progress(i)?.fraction ?: 0f) >= 1f) return null
        return when (val t = i.target) {
            is WorkoutTarget.Distance ->
                strings.format(R.string.workout_glance_to_go, kmWithUnit(t.meters - (i.distanceMeters ?: 0f), strings = strings))
            is WorkoutTarget.Time -> minutesToGo((t.seconds - i.activeSeconds).toDouble(), strings)
            is WorkoutTarget.Kcal -> kcalToGo(t, i, strings)
            else -> null
        }
    }

    /** "120 kcal to go". */
    private fun kcalToGo(t: WorkoutTarget.Kcal, i: GlanceInput, strings: Strings): String =
        strings.format(R.string.workout_glance_kcal_to_go, (t.kcal - (i.activeKcal ?: 0f)).toInt().coerceAtLeast(0))

    /** "4 min to go", rounded up; "Under a minute to go". */
    private fun minutesToGo(seconds: Double, strings: Strings): String =
        if (seconds < 60) {
            strings.get(R.string.workout_glance_under_a_minute)
        } else {
            strings.format(R.string.workout_glance_minutes_to_go, ceil(seconds / 60).toInt())
        }

    /** "4.32" kilometres, as the figure in the dot font, so always with a dot and ASCII digits. */
    fun kmFigure(meters: Float): String = Units.km(meters, Units.Precision.Workout, Units.DOT_LOCALE)

    /** "4.32 km", or "680 m" under one, down to "0 m" where a line needs a figure. */
    fun kmWithUnit(meters: Float, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String =
        Units.distance(meters, Units.Precision.Workout, locale = locale)
            ?: strings.format(R.string.workout_glance_meters, meters.coerceAtLeast(0f).toInt())

    /** A pace as a clock in the dot font, "5:42" ([Pace.clock]); null with none, or one too slow to be moving. */
    fun paceClock(secondsPerKm: Double?): String? = Pace.clock(secondsPerKm, Units.DOT_LOCALE)
}
