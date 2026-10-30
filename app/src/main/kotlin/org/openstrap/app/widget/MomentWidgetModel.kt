package org.openstrap.app.widget

import androidx.compose.ui.graphics.Color
import org.openstrap.app.data.Moment
import org.openstrap.app.data.MomentInputs
import org.openstrap.app.data.MomentLead
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.momentLead
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.components.StageSpan
import org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.WakingLead
import org.openstrap.app.ui.dashboard.clockText
import org.openstrap.app.ui.dashboard.momentMood
import org.openstrap.app.ui.dashboard.sportNoun
import org.openstrap.app.ui.dashboard.todayTitle
import org.openstrap.app.ui.dashboard.wakingLead
import org.openstrap.app.ui.glow.AFTER_RIM
import org.openstrap.app.ui.glow.EVENING_BODY
import org.openstrap.app.ui.glow.GlowMood
import org.openstrap.app.ui.glow.GlowStyle
import org.openstrap.app.ui.glow.LightAngle
import org.openstrap.app.ui.glow.WINDOW_RIM
import org.openstrap.app.ui.glow.chargeShown
import org.openstrap.app.ui.glow.heroStyle
import org.openstrap.app.ui.theme.MetricColors
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/*
 * The Moment widget's content (widgets spec 3.1): which moment it is, what it leads with once the duplicate rule has
 * had its say, and every line, number, figure and chart in it, from the same resolver, copy and sums Today's hero
 * uses. Then MomentLayout: what fits each size, one line a text (the hero sentence two at most), the spare height
 * shared out. Plain functions, so every moment is tested without a phone.
 */

/** Energy as the widget reads it, from the day's [Recovery.ChargeDay]. */
data class MomentEnergy(
    /** Energy now, whole, as the Charge detail's headline ([chargeShown]); null before the first night. */
    val now: Int?,
    val atWake: Int?,
    val recharged: Int,
    /** What the day drained, gross: movement, stress and living. */
    val drained: Float,
    val anchored: Boolean,
    val points: List<Recovery.ChargePoint>,
) {
    companion object {
        fun of(c: Recovery.ChargeDay): MomentEnergy {
            val anchored = c.anchored
            return MomentEnergy(
                now = c.latest?.takeIf { anchored }?.let(::chargeShown),
                atWake = c.atWake?.takeIf { anchored }?.toInt(),
                recharged = c.recharged.toInt(),
                drained = c.drainedExercise + c.drainedStress + c.drainedLiving,
                anchored = anchored,
                points = c.points,
            )
        }
    }
}

/** The last finished workout: its sport and when it ended (epoch seconds). */
data class MomentWorkout(val sportType: Int, val end: Long)

/** The workout recording now: its sport and when it started (epoch seconds). */
data class MomentRecordingInput(val sportType: Int, val start: Long)

/**
 * Everything the Moment widget reads, gathered by [MomentWidget.load] from what the app stores and works out, so the
 * model is a plain function of it.
 */
data class MomentWidgetInput(
    /** What [org.openstrap.app.data.MomentResolver] reads, as Today gathers it ([widgetMomentInputs]). */
    val inputs: MomentInputs,
    /** The widgets on the home screen, for the duplicate rule. */
    val placed: Set<WidgetKind>,
    /** Recovery as Today shows it ([TodayCopy.recoveryShown]); null when it couldn't be worked out. */
    val recovery: TodayCopy.RecoveryShown?,
    val score: Int?,
    val alert: Boolean,
    val noScore: TodayCopy.NoScore?,
    /** Before the strap has seen a night. */
    val firstNights: Boolean,
    val energy: MomentEnergy?,
    /** Load so far today, and against your usual. */
    val load: Float?,
    val loadStatus: TodayCopy.LoadStatus?,
    /** Today's main sleep, as the Sleep tab's hero reads it; null without one. */
    val night: SleepWidgetNight?,
    /** Your usual sport, as Today's Start picks it. */
    val usualSport: Int,
    val lastWorkout: MomentWorkout?,
    val recording: MomentRecordingInput?,
    /** The sports of today's workouts, oldest first, as the evening sentence counts them. */
    val dayWorkouts: List<Int>,
    /** Today's heart rate, five minute averages (epoch seconds, bpm). */
    val heartRate: List<ChartPoint>,
    val restingHr: Int?,
    val steps: Int,
    val stepGoal: Int,
)

/** How the Large's chart is drawn. */
enum class MomentChartKind {
    /** The night's stages laid flat, as the nap strip ([org.openstrap.app.ui.glow.StageStrip]). */
    Stages,

    /** A solid white line ending in its dot, as the Steps widget's ([org.openstrap.app.ui.glow.draw.ghostLineGeometry]). */
    Line,
}

/**
 * The Large's chart: [stages] for [MomentChartKind.Stages], else [values] evenly spaced (nulls the stretch still to
 * come) on an axis from [low] to [high], with the times under it from [from] to [to] and what TalkBack reads.
 */
data class MomentChart(
    val kind: MomentChartKind,
    val stages: List<StageSpan> = emptyList(),
    val values: List<Float?> = emptyList(),
    val low: Float = 0f,
    val high: Float = 100f,
    val from: String,
    val to: String,
    val description: String,
) {
    /** [values] as shares of the axis, 0..1, for a line whose top is [high]. */
    val shares: List<Float?> get() = values.map { v -> v?.let { ((it - low) / (high - low).coerceAtLeast(1f)).coerceIn(0f, 1f) } }
}

/**
 * The hero's light for the moment (v2 spec 4.2, widgets spec 2.2): its [color] from [angle], the rim at [rim], or a
 * level from below to [level] for Energy, and the evening's lighter body.
 */
data class MomentLight(val color: Color, val angle: Float?, val rim: Float = 1f, val level: Float? = null, val bodyLightness: Float? = null) {
    /** The glow card's style in [mood], as Today's hero draws it ([heroStyle], Energy's level as its Charge hero). */
    fun style(mood: GlowMood): GlowStyle {
        val base = if (level != null) GlowStyle.Ember.copy(lightLevel = level) else heroStyle(color, angle, rim)
        return (bodyLightness?.let { base.copy(bodyLightness = it) } ?: base).copy(mood = mood)
    }
}

/** During a workout: the sport, its tile and "Recording since 17:42". */
data class MomentRecording(val sport: StartSport, val since: String, val sinceShort: String)

/**
 * What the Moment widget shows (widgets spec 3.1). Every one-line text comes as options, longest first, and the
 * layout takes the first that fits ([MomentLayout]).
 */
data class MomentWidgetModel(
    val moment: Moment,
    val lead: MomentLead,
    /** The moment's name, as Today's header says it ("Midday", "After your run"). */
    val name: String,
    /** The number in dots; null when there's none to show. */
    val number: String?,
    /** Under the number on the Small: "Energy left". */
    val words: List<String>,
    /** Beside the number on the Wide and Large: "left". */
    val shortWords: List<String>,
    /** The Secondary figure on the right of the number row: "Drained 60, recharged 38". */
    val figures: List<String>,
    /** The hero sentence and shorter wordings of it. */
    val sentences: List<String>,
    /** In place of the number when there's none: "After your first night". */
    val waiting: String?,
    /** The Start button's sport (the workout window), and what TalkBack reads for it. */
    val start: Int?,
    val startLabel: String?,
    val recording: MomentRecording?,
    val chart: MomentChart?,
    val light: MomentLight,
    val mood: GlowMood,
    /** What TalkBack reads for the dotted number: "62, Energy left". */
    val numberDescription: String?,
    /**
     * When something shown moves on by itself within the moment (a count of minutes), so the widget refreshes then
     * as well as at the moment's end; null when nothing does.
     */
    val tick: Instant?,
) {
    /** The whole widget opens the live screen while a workout records, else Today. */
    val opensLive: Boolean get() = recording != null

    companion object {
        /** How often a count of minutes on the widget moves on: every [TICK_MINUTES] on the clock. */
        const val TICK_MINUTES = 5L

        val ENERGY_LEFT: String get() = tr(R.string.moment_widget_model_energy_left)
        const val LEFT = "left"

        /**
         * The widget at [now]: the moment [org.openstrap.app.data.MomentResolver] gives for the [input] (or a
         * [forced] one, the debug gallery's), what it leads with ([momentLead], then the moment's next lead where the
         * lead has nothing to show), and everything in it.
         */
        fun from(input: MomentWidgetInput, now: Instant, zone: ZoneId, forced: Moment? = null): MomentWidgetModel {
            val moment = forced ?: input.inputs.resolve(now, zone)
            val lead = lead(moment, input)
            val noSleep = moment == Moment.Waking &&
                wakingLead(input.firstNights, input.night?.asleepSeconds, isToday = true) == WakingLead.Charge
            val local = now.atZone(zone)
            val sport = when (moment) {
                Moment.During -> input.recording?.sportType
                Moment.After -> input.lastWorkout?.sportType
                else -> null
            }
            val name = todayTitle(moment, local.toLocalDate(), sport, today = local.toLocalDate())
            val mood = momentMood(moment)
            val c = Content(input, moment, now, zone)
            return when (lead) {
                MomentLead.Recovery -> c.recovery(lead, name, mood)
                MomentLead.LastNight -> c.lastNight(lead, name, mood)
                MomentLead.Energy -> c.energy(lead, name, mood, noSleep)
                MomentLead.Start -> c.window(lead, name, mood)
                MomentLead.Workout -> c.during(lead, name, mood)
                MomentLead.AfterWorkout -> c.after(lead, name, mood)
                MomentLead.Sentence -> if (moment == Moment.Night) c.night(lead, name, mood) else c.evening(lead, name, mood)
            }
        }

        /**
         * What the widget leads with: [momentLead]'s, unless that has nothing to show. No sleep last night leads
         * with Energy from waking, as Today's hero does (spec 4.3); waking with no Recovery value gives way to last
         * night, then Energy; during a workout with nothing recording, or after one with none to tell, it's midday's.
         */
        fun lead(moment: Moment, input: MomentWidgetInput): MomentLead {
            val lead = momentLead(moment, input.placed)
            if (moment == Moment.Waking) {
                if (wakingLead(input.firstNights, input.night?.asleepSeconds, isToday = true) == WakingLead.Charge) return MomentLead.Energy
                val sleepScore = input.night?.session?.score
                var l = lead
                if (l == MomentLead.Recovery && input.recovery?.value == null) {
                    l = if (WidgetKind.Sleep !in input.placed && sleepScore != null) MomentLead.LastNight else MomentLead.Energy
                }
                if (l == MomentLead.LastNight && sleepScore == null) l = MomentLead.Energy
                return l
            }
            if (lead == MomentLead.Workout && input.recording == null) return MomentLead.Energy
            if (lead == MomentLead.AfterWorkout && input.lastWorkout == null) return MomentLead.Energy
            return lead
        }

        /** The next [TICK_MINUTES] mark on the clock after [now]. */
        fun nextTick(now: Instant): Instant {
            val minute = now.truncatedTo(ChronoUnit.MINUTES)
            val m = minute.epochSecond / 60
            return minute.plusSeconds((TICK_MINUTES - Math.floorMod(m, TICK_MINUTES)) * 60)
        }

        /** Minutes on a clock that starts at noon, so the night runs on past midnight (as [TodayCopy.bedtimeIn]). */
        private fun afterNoon(t: LocalTime): Int = Math.floorMod(t.toSecondOfDay() / 60 - 720, 1440)

        /** "Slept 7 h 40." split into its sentences: the whole, then each alone, longest first. */
        internal fun shorter(sentence: String): List<String> {
            val parts = sentence.split(Regex("(?<=\\.) ")).filter { it.isNotBlank() }
            return (listOf(sentence) + parts.sortedByDescending { it.length }).distinct()
        }
    }

    /** Builds each lead's model from the [input] at [now]. */
    private class Content(val input: MomentWidgetInput, val moment: Moment, val now: Instant, val zone: ZoneId) {
        val t = now.epochSecond
        val energy = input.energy

        fun clock(epoch: Long) = clockText(epoch, zone)

        /** Waking: Recovery's score and word, the waking sentence, Energy at waking, the night's stages. */
        fun recovery(lead: MomentLead, name: String, mood: GlowMood): MomentWidgetModel {
            val shown = input.recovery
            val word = shown?.word
            return MomentWidgetModel(
                moment, lead, name,
                number = shown?.value?.toString(),
                words = listOfNotNull(word),
                shortWords = listOfNotNull(word),
                figures = listOfNotNull(energy?.atWake?.let { tr(R.string.common_energy_at_waking, it) }),
                sentences = shorter(TodayCopy.waking(input.night?.asleepSeconds, input.score, input.alert, input.noScore, input.firstNights, isToday = true)),
                waiting = null, start = null, startLabel = null, recording = null,
                chart = stagesChart(),
                light = MomentLight(MetricColors.readiness, LightAngle.LowerLeft),
                mood = mood,
                numberDescription = shown?.value?.let { v -> tr(R.string.moment_widget_model_recovery, v) + (word?.let { ", $it" } ?: "") },
                tick = null,
            )
        }

        /**
         * Waking with a Recovery widget placed: last night's score and word, the Sleep tab's sentence, how often you
         * woke (the sentence already says how long you slept), Sleep blue lit from above.
         */
        fun lastNight(lead: MomentLead, name: String, mood: GlowMood): MomentWidgetModel {
            val n = input.night!!
            val score = n.session.score!!
            val word = TabCopy.sleepScoreWord(score)
            val line = TabCopy.sleepScoreLine(score)
            return MomentWidgetModel(
                moment, lead, name,
                number = score.toString(),
                words = listOf(line, word),
                shortWords = listOf(line, word),
                figures = listOfNotNull(TabCopy.woke(n.session.wakeCount)),
                // Shorter: the same sentence without the week's comparison, "7 h 40 asleep."
                sentences = listOf(TabCopy.night(n.asleepSeconds, n.others), TabCopy.night(n.asleepSeconds, emptyList())).distinct(),
                waiting = null, start = null, startLabel = null, recording = null,
                chart = stagesChart(),
                light = MomentLight(MetricColors.sleepDeep, LightAngle.Above),
                mood = mood,
                numberDescription = tr(R.string.moment_widget_model_sleep_score, score, word.lowercase()),
                tick = null,
            )
        }

        /**
         * Midday, and waking when Recovery and Sleep both have their widgets (or there was no sleep): Energy left,
         * the Charge hero's sentence, drained and recharged, the line since waking, lit from below to the level.
         */
        fun energy(lead: MomentLead, name: String, mood: GlowMood, noSleep: Boolean = false): MomentWidgetModel {
            val e = energy
            val left = e?.now
            val drained = e?.let { TabCopy.drainedAmount(it.drained) }
            val sentence = if (noSleep) TodayCopy.NO_SLEEP
            else TodayCopy.charge(left, e?.let { TabCopy.drainedAmount(it.drained).toInt() }, e?.anchored == true, now.atZone(zone).hour)
            return energyModel(
                lead, name, mood,
                figures = if (e != null && left != null && drained != null) listOf(tr(R.string.moment_widget_model_drained_recharged, drained, e.recharged), tr(R.string.moment_widget_model_drained, drained)) else emptyList(),
                sentences = shorter(sentence),
            )
        }

        /** The workout window: Energy left, "Ready for effort. You usually run around now.", the Start button. */
        fun window(lead: MomentLead, name: String, mood: GlowMood): MomentWidgetModel {
            val sport = input.usualSport
            val noun = sportNoun(sport)
            val title = TodayCopy.windowTitle(input.score)
            val usual = TodayCopy.window(noun, null)
            val e = energy?.now
            return MomentWidgetModel(
                moment, lead, name,
                number = e?.toString(),
                words = listOf(ENERGY_LEFT),
                shortWords = listOf(LEFT),
                figures = emptyList(),
                sentences = listOf("$title. $usual", usual, "$title."),
                waiting = if (e == null) title else null,
                start = sport,
                startLabel = tr(R.string.moment_widget_model_start, noun ?: org.openstrap.app.data.Workouts.sportName(sport).lowercase()),
                recording = null,
                chart = energyChart(),
                light = MomentLight(MetricColors.heart, LightAngle.UpperRight, rim = WINDOW_RIM),
                mood = mood,
                numberDescription = e?.let { tr(R.string.common_energy_left, it) },
                tick = null,
            )
        }

        /** During a workout: the sport and since when, no number, nothing live. */
        fun during(lead: MomentLead, name: String, mood: GlowMood): MomentWidgetModel {
            val r = input.recording!!
            val since = clock(r.start)
            return MomentWidgetModel(
                moment, lead, name,
                number = null, words = emptyList(), shortWords = emptyList(), figures = emptyList(), sentences = emptyList(),
                waiting = null, start = null, startLabel = null,
                recording = MomentRecording(StartSport.recorded(r.sportType), tr(R.string.moment_widget_model_recording_since, since), tr(R.string.moment_widget_model_since, since)),
                chart = null,
                light = MomentLight(MetricColors.heart, LightAngle.Below),
                mood = mood,
                numberDescription = null,
                tick = null,
            )
        }

        /** After a workout: minutes since it, how the heart is coming down, Load today, the drop since the end. */
        fun after(lead: MomentLead, name: String, mood: GlowMood): MomentWidgetModel {
            val w = input.lastWorkout!!
            val minutes = ((t - w.end).coerceAtLeast(0) / 60).toInt()
            val noun = sportNoun(w.sportType) ?: tr(R.string.missed_unit_workout)
            val since = input.heartRate.filter { it.x >= w.end && it.x <= t }
            val load = input.load?.let { Math.round(it) }
            return MomentWidgetModel(
                moment, lead, name,
                number = minutes.toString(),
                words = listOf(tr(R.string.moment_widget_model_min_since_your, noun), tr(R.string.moment_widget_model_min_since), tr(R.string.missed_unit_min)),
                shortWords = listOf(tr(R.string.moment_widget_model_min_since_your, noun), tr(R.string.moment_widget_model_min_since), tr(R.string.missed_unit_min)),
                figures = listOfNotNull(load?.let { tr(R.string.moment_widget_model_load_today, it) }, load?.let { tr(R.string.common_load_2, it) }),
                sentences = listOf(TodayCopy.after(since.lastOrNull()?.y?.toInt(), input.restingHr)),
                waiting = null, start = null, startLabel = null, recording = null,
                chart = heartChart(since, w.end, tr(R.string.moment_widget_model_heart_rate_since_your, noun)),
                light = MomentLight(MetricColors.heart, LightAngle.Below, rim = AFTER_RIM),
                mood = mood,
                numberDescription = tr(R.string.moment_widget_model_minutes_since_your, minutes, noun),
                tick = nextTick(now),
            )
        }

        /** The evening: Energy left, the day in a sentence, how long to bedtime, the day's heart rate. */
        fun evening(lead: MomentLead, name: String, mood: GlowMood): MomentWidgetModel {
            val bed = input.inputs.usualBedtime
            val bedIn = TodayCopy.bedtimeIn(now.atZone(zone).toLocalTime(), bed)
            return energyModel(
                lead, name, mood,
                figures = listOfNotNull(bedIn),
                sentences = shorter(daySentence()),
                light = MomentLight(MetricColors.evening, LightAngle.LowerRight, bodyLightness = EVENING_BODY),
                chart = dayHeartChart(),
                // "Bedtime in 1 h 10" counts down.
                tick = if (bedIn != null) nextTick(now) else null,
            )
        }

        /**
         * The night: minutes to your usual bedtime until it comes, then Energy left; the day in a sentence, the
         * bedtime, the day's heart rate. Sleep blue from below.
         */
        fun night(lead: MomentLead, name: String, mood: GlowMood): MomentWidgetModel {
            val bed = input.inputs.usualBedtime
            val light = MomentLight(MetricColors.sleepDeep, LightAngle.Below)
            val bedtime = bed?.let { trRaw(R.string.moment_widget_model_bedtime_d_d).format(it.hour, it.minute) }
            val clock = now.atZone(zone).toLocalTime()
            val left = bed?.let { afterNoon(it) - afterNoon(clock) }?.takeIf { it > 0 }
            if (left == null) {
                return energyModel(lead, name, mood, listOfNotNull(bedtime), shorter(daySentence()), light, dayHeartChart(), tick = null)
            }
            val tick = nextTick(now).let { tk -> minOf(tk, now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(left * 60L)) }
            return MomentWidgetModel(
                moment, lead, name,
                number = left.toString(),
                words = listOf(tr(R.string.moment_widget_model_min_to_bedtime), "min"),
                shortWords = listOf(tr(R.string.moment_widget_model_min_to_bedtime), "min"),
                figures = listOfNotNull(bedtime),
                sentences = shorter(daySentence()),
                waiting = null, start = null, startLabel = null, recording = null,
                chart = dayHeartChart(),
                light = light,
                mood = mood,
                numberDescription = tr(R.string.moment_widget_model_minutes_to_bedtime, left),
                tick = tick,
            )
        }

        /** Energy left as the number, with [figures] and [sentences] of the moment's own. */
        fun energyModel(
            lead: MomentLead, name: String, mood: GlowMood, figures: List<String>, sentences: List<String>,
            light: MomentLight? = null, chart: MomentChart? = energyChart(), tick: Instant? = null,
        ): MomentWidgetModel {
            val now = energy?.now
            return MomentWidgetModel(
                moment, lead, name,
                number = now?.toString(),
                words = listOf(ENERGY_LEFT),
                shortWords = listOf(LEFT),
                figures = if (now == null) emptyList() else figures,
                sentences = sentences,
                waiting = if (now == null) AFTER_FIRST_NIGHT else null,
                start = null, startLabel = null, recording = null,
                chart = if (now == null) null else chart,
                // Energy is lit to its level from below (spec 2.5), as Today's Charge hero.
                light = light ?: MomentLight(MetricColors.charge, LightAngle.Below, level = (now ?: 0) / 100f),
                mood = mood,
                numberDescription = now?.let { tr(R.string.common_energy_left, it) },
                tick = tick,
            )
        }

        /** The evening hero's sentence: what kind of day it was, then what made it. */
        fun daySentence(): String {
            val nouns = input.dayWorkouts.map(::sportNoun)
            return TodayCopy.evening(nouns, input.steps, input.stepGoal, input.loadStatus)
        }

        /** The night's stages from bedtime to waking; null without stages. */
        fun stagesChart(): MomentChart? {
            val n = input.night ?: return null
            if (n.stages.isEmpty()) return null
            val from = clock(n.session.start)
            val to = clock(n.session.end)
            return MomentChart(MomentChartKind.Stages, stages = n.stages, from = from, to = to, description = tr(R.string.moment_widget_model_last_night_s_stages, from, to))
        }

        /**
         * Energy's line since waking, in quarter hours up to now, across the day from waking to your usual bedtime
         * (or now, when that's later). Null before the first night or with fewer than two readings.
         */
        fun energyChart(): MomentChart? {
            val e = energy?.takeIf { it.anchored } ?: return null
            val today = now.atZone(zone).toLocalDate()
            val wake = input.inputs.sleepEnd ?: today.atTime(org.openstrap.app.data.MomentResolver.WAKE_FALLBACK).atZone(zone).toEpochSecond()
            if (t <= wake) return null
            // A bedtime after midnight is tomorrow's.
            val bed = (input.inputs.usualBedtime ?: org.openstrap.app.data.MomentResolver.BEDTIME_FALLBACK).let { b ->
                today.atTime(b).atZone(zone).toEpochSecond() + if (b.hour < 12) 86_400 else 0
            }
            val end = maxOf(bed, t)
            val step = 15 * 60L
            val n = ((end - wake) / step).toInt().coerceAtLeast(2)
            val sums = FloatArray(n + 1)
            val counts = IntArray(n + 1)
            e.points.forEach { p -> if (p.t in wake..t) { val i = ((p.t - wake) / step).toInt().coerceAtMost(n); sums[i] += p.value; counts[i]++ } }
            val values = List(n + 1) { if (counts[it] == 0) null else sums[it] / counts[it] }
            if (values.count { it != null } < 2) return null
            val from = clock(wake)
            val to = clock(end)
            val nowValue = e.now
            return MomentChart(
                MomentChartKind.Line, values = values, low = 0f, high = 100f, from = from, to = to,
                description = tr(R.string.moment_widget_model_energy_since, from) + (nowValue?.let { tr(R.string.moment_widget_model_now, it) } ?: ""),
            )
        }

        /** The heart rate in [points] from [start] to now, its axis fitted to the readings and your resting rate. */
        fun heartChart(points: List<ChartPoint>, start: Long, description: String): MomentChart? {
            if (points.size < 2) return null
            val step = maxOf(60L, (t - start) / 40)
            val n = ((t - start) / step).toInt().coerceAtLeast(2)
            val sums = FloatArray(n + 1)
            val counts = IntArray(n + 1)
            points.forEach { p -> val i = ((p.x - start) / step).toInt(); if (i in 0..n) { sums[i] += p.y; counts[i]++ } }
            val values = List(n + 1) { if (counts[it] == 0) null else sums[it] / counts[it] }
            val known = values.filterNotNull()
            if (known.size < 2) return null
            val low = minOf(known.min(), input.restingHr?.toFloat() ?: Float.MAX_VALUE) - 5f
            val high = known.max() + 5f
            return MomentChart(MomentChartKind.Line, values = values, low = low, high = high, from = clock(start), to = clock(points.last().x), description = description)
        }

        /** The day's heart rate since midnight. */
        fun dayHeartChart(): MomentChart? {
            val start = now.atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
            val points = input.heartRate.filter { it.x in start..t }
            return heartChart(points, points.firstOrNull()?.x ?: start, tr(R.string.moment_widget_model_your_heart_rate_today))
        }

    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Layout                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/**
 * What of the Moment widget fits each size, and the spare height shared out (spec 2.1, the lessons of Start in 3.5):
 * every text one line (the hero sentence two at most), each at the first wording and size that fits with
 * [WidgetText.FIT_MARGIN], else left out. Spare height grows the parts first (the number's dots, the sentence, the
 * chart, the recording's tile) until no gap is over [TARGET_GAP_DP], then is shared evenly between the blocks, half a
 * gap above the first and below the last, as the padding already frames them.
 */
object MomentLayout {
    /** The moment's name: Sans 500 13 at 60% (spec 3.1), Caption size when it won't fit. */
    val NAME_SIZES = listOf(WidgetText.SECONDARY_SP, WidgetText.CAPTION_SP)

    /** The word under or beside the number: Body, then Secondary. */
    val WORD_SIZES = listOf(WidgetText.BODY_SP, WidgetText.SECONDARY_SP)

    /** The figure on the right of the number row, Secondary, then Caption size. */
    val FIGURE_SIZES = listOf(WidgetText.SECONDARY_SP, WidgetText.CAPTION_SP)

    /** The hero sentence (Display 300 20, spec 3.1), a touch smaller before a shorter wording. */
    val SENTENCE_SIZES = listOf(WidgetText.HERO_SP, 18f)

    /** Sizes the sentence grows to on a tall cell, and the most lines it takes. */
    val SENTENCE_GROWTH = listOf(22f, 24f)
    const val SENTENCE_LINES = 2

    /** The number's dots: the Small's from the mockup's 3.6 up, the Wide's and Large's 4.5 (spec 3). */
    const val SMALL_DOT = 3.6f
    const val SMALL_MIN_DOT = 3f
    val SMALL_DOT_GROWTH = listOf(4.5f, 5f, 5.5f, 6f, 6.5f, 7f)
    const val DOT = 4.5f
    val DOT_GROWTH = listOf(5f, 5.5f, 6f, 6.5f, 7f)

    /** Between the dots and their word, and the word and the figure. */
    const val WORD_GAP_DP = 8f
    const val FIGURE_GAP_DP = 12f

    /** The Start button (44 dp, white) and the room before it. */
    const val PLAY_DP = 44f

    /** The Large's line chart: the mockup's 44 dp, growing on a tall cell, down to 32 when short. */
    const val CHART_DP = 44f
    const val CHART_MIN_DP = 32f
    val CHART_GROWTH = listOf(56f, 64f, 72f)

    /** The stage strip, as the Sleep widget's bar, and as far as it grows. */
    const val STRIP_DP = 8f
    const val STRIP_MIN_DP = 6f
    val STRIP_GROWTH = listOf(10f, 12f, 14f, 16f, 18f, 20f)

    /** Between a chart and its times. */
    const val CHART_LABEL_GAP_DP = 6f

    /** During a workout: the sport's tile, and the sport's name in the hero's light type on the Wide and Large. */
    const val TILE_DP = 44f
    val TILE_GROWTH = listOf(56f, 64f, 72f)
    val SPORT_SIZES = listOf(WidgetText.HERO_SP, 18f)
    val SPORT_GROWTH = listOf(24f, 28f)

    /** The red recording dot before "Recording since 17:42", and the room after it, as Start's. */
    const val REC_DOT_DP = 8f
    const val REC_DOT_GAP_DP = 6f

    /** Open, as Start's while recording. */
    const val OPEN_PAD_DP = 14f
    val OPEN: String get() = tr(R.string.common_open)

    /** The least gap between blocks, and the most a tall cell should show. */
    const val MIN_GAP_DP = 4f
    const val TARGET_GAP_DP = 32f

    /** A text and the size it fits at. */
    data class Line(val text: String, val sp: Float) {
        fun height(fontScale: Float) = WidgetText.line(sp, fontScale)
        fun width(fontScale: Float) = WidgetText.fitWidth(text, sp, fontScale)
    }

    /** The first of [options] that fits [room] on one line, at the first of [sizes] that does; null when none does. */
    fun line(options: List<String>, sizes: List<Float>, room: Float, fontScale: Float): Line? {
        for (o in options) for (sp in sizes) if (WidgetText.fits(o, sp, room, fontScale)) return Line(o, sp)
        return null
    }

    /** The sentence at [sp] and how many lines it wraps to in [width]. */
    data class Sentence(val text: String, val sp: Float, val lines: Int) {
        fun height(fontScale: Float) = lines * WidgetText.line(sp, fontScale)
    }

    fun sentence(text: String, sp: Float, width: Float, fontScale: Float): Sentence? {
        val lines = WidgetText.wrappedLines(text, sp, width, fontScale)
        return if (lines <= SENTENCE_LINES) Sentence(text, sp, lines) else null
    }

    /**
     * The number row on the Wide and Large: the dots at [dot], the first word beside them and the first figure (or
     * the Start button) on the right that fit [width]. The button stays before the word, the word before the figure.
     */
    data class NumberRow(val dot: Float, val word: Line?, val figure: Line?, val play: Boolean, val height: Float)

    fun numberRow(m: MomentWidgetModel, dot: Float, width: Float, fontScale: Float): NumberRow? {
        val number = m.number ?: return null
        val dots = WidgetDots.width(number, dot)
        if (dots > width) return null
        val play = m.start != null && dots + FIGURE_GAP_DP + PLAY_DP <= width
        val right = if (play) FIGURE_GAP_DP + PLAY_DP else 0f
        val word = line(m.shortWords, WORD_SIZES, width - dots - WORD_GAP_DP - right, fontScale)
        val used = dots + (word?.let { WORD_GAP_DP + it.width(fontScale) } ?: 0f)
        val figure = if (play) null else line(m.figures, FIGURE_SIZES, width - used - FIGURE_GAP_DP, fontScale)
        val h = maxOf(WidgetDots.height(dot), word?.height(fontScale) ?: 0f, figure?.height(fontScale) ?: 0f, if (play) PLAY_DP else 0f)
        return NumberRow(dot, word, figure, play, h)
    }

    /**
     * A size laid out: the [name], the number row or the Small's number and [word], the [sentence], the [chart]'s
     * height (0 for none), the [waiting] line, or the recording's parts; [content] is the height of it all without
     * gaps and [inner] the gaps between the blocks, which with the half gap above and below share the spare height.
     */
    data class Fit(
        val name: Line,
        val dot: Float = 0f,
        val word: Line? = null,
        val row: NumberRow? = null,
        val play: Boolean = false,
        val sentence: Sentence? = null,
        val chart: Float = 0f,
        val waiting: Line? = null,
        val tile: Float = 0f,
        val sport: Line? = null,
        val since: Line? = null,
        val open: Boolean = false,
        val content: Float,
        val inner: Int,
        /** The top row with the strap status button at its right (spec 5), null for the name alone. */
        val top: TopRow? = null,
    ) {
        /** The gap between blocks in a cell [height] dp high (inside the padding). */
        fun gap(height: Float): Float = if (inner + 1 <= 0) 0f else ((height - content) / (inner + 1)).coerceAtLeast(0f)

        /** The half gap above the first block and below the last. */
        fun edgeGap(height: Float): Float = gap(height) / 2

        /** Whether it fits [height] with at least [MIN_GAP_DP] between blocks. */
        fun fits(height: Float): Boolean = content + (inner + 1) * (if (inner > 0) MIN_GAP_DP else 0f) <= height + 0.01f
    }

    /**
     * The first of [ladders] whose first (least grown) fit fits [height], grown along it to the first that brings the
     * gap to [TARGET_GAP_DP] or under, else as far as it still fits. The last ladder's first when none fits.
     */
    fun pick(ladders: List<List<Fit>>, height: Float): Fit {
        for (ladder in ladders.filter { it.isNotEmpty() }) {
            if (!ladder.first().fits(height)) continue
            val fitting = ladder.filter { it.fits(height) }
            return fitting.firstOrNull { it.gap(height) <= TARGET_GAP_DP } ?: fitting.last()
        }
        return ladders.last { it.isNotEmpty() }.first()
    }

    private fun nameLine(m: MomentWidgetModel, width: Float, fontScale: Float, beside: Float = 0f): Line =
        line(listOf(m.name), NAME_SIZES, width - beside, fontScale) ?: Line(m.name, NAME_SIZES.last())

    /** The top row's height: the name's line, or the status button's while it shows ([top]). */
    private fun topHeight(name: Line, top: TopRow?, fontScale: Float): Float = top?.height ?: name.height(fontScale)

    /**
     * The top row with [status]'s button at its right (and on the Wide and Large, [withWords], its words before it),
     * null without a status; the name then at the size the row gives it.
     */
    private fun statusRow(m: MomentWidgetModel, status: StatusLine?, width: Float, fontScale: Float, withWords: Boolean): TopRow? =
        status?.let { TopRow.of(m.name, null, it, width, fontScale, withWords, titleSizes = NAME_SIZES) }

    /** The name as the top row shows it: at [row]'s size beside the button, else the first of [NAME_SIZES] that fits. */
    private fun nameIn(m: MomentWidgetModel, row: TopRow?, width: Float, fontScale: Float): Line =
        row?.let { Line(m.name, it.titleSp) } ?: nameLine(m, width, fontScale)

    /** Open's width at [fontScale]: its word in Body 500 with [OPEN_PAD_DP] each side. */
    fun openWidth(fontScale: Float): Float = WidgetText.fitWidth(OPEN, WidgetText.BODY_SP, fontScale) + 2 * OPEN_PAD_DP

    /* ---------------------------------------------- Small --------------------------------------------- */

    /**
     * The Small in [width] by [height] dp: the name, the number (with the Start button beside it in the workout
     * window), its word under it. Three blocks, the dots growing from the mockup's 3.6 until no gap is over the
     * target. Without a number, the name and the waiting line; during a workout, [recordingSmall].
     */
    fun small(m: MomentWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Fit {
        if (m.recording != null) return recordingSmall(m, m.recording, width, height, fontScale)
        val statusTop = statusRow(m, status, width, fontScale, withWords = false)
        val name = nameIn(m, statusTop, width, fontScale)
        val top = topHeight(name, statusTop, fontScale)
        val number = m.number
        if (number == null) {
            val waiting = m.waiting?.let { line(listOf(it), FIGURE_SIZES, width, fontScale) }
            val play = m.start != null
            val content = top + (waiting?.height(fontScale) ?: 0f) + (if (play) PLAY_DP else 0f)
            return Fit(name, waiting = waiting, play = play, content = content, inner = (if (waiting != null) 1 else 0) + (if (play) 1 else 0), top = statusTop)
        }
        fun at(dot: Float): Fit? {
            val dots = WidgetDots.width(number, dot)
            if (dots > width) return null
            val play = m.start != null && dots + WORD_GAP_DP + PLAY_DP <= width
            val word = line(m.words, WORD_SIZES, width, fontScale)
            val row = maxOf(WidgetDots.height(dot), if (play) PLAY_DP else 0f)
            val content = top + row + (word?.height(fontScale) ?: 0f)
            return Fit(name, dot = dot, word = word, play = play, content = content, inner = if (word != null) 2 else 1, top = statusTop)
        }
        // Growing never costs the Small its word or its Start button.
        val ladder = (listOf(SMALL_DOT) + SMALL_DOT_GROWTH).mapNotNull(::at).let { l ->
            l.filter { it.word == l.firstOrNull()?.word && it.play == l.firstOrNull()?.play }
        }
        return pick(listOf(ladder, listOfNotNull(at(SMALL_MIN_DOT))), height)
    }

    /** The Small during a workout: the name, the sport's tile, its name, "Recording since 17:42". */
    fun recordingSmall(m: MomentWidgetModel, r: MomentRecording, width: Float, height: Float, fontScale: Float): Fit {
        val name = nameLine(m, width, fontScale)
        val sport = line(listOf(r.sport.name, r.sport.shortName), WORD_SIZES, width, fontScale)
        val sinceOptions = listOf(listOf(r.since, r.sinceShort), emptyList())
        val ladders = sinceOptions.flatMap { opts ->
            val since = line(opts, FIGURE_SIZES, width - REC_DOT_DP - REC_DOT_GAP_DP, fontScale)
            fun at(tile: Float): Fit {
                val parts = listOfNotNull(name.height(fontScale), tile.takeIf { it > 0f }, sport?.height(fontScale), since?.height(fontScale))
                return Fit(name, tile = tile, sport = sport, since = since, content = parts.sum(), inner = parts.size - 1)
            }
            listOf((listOf(TILE_DP) + TILE_GROWTH.take(1)).map(::at), listOf(at(0f)))
        }
        return pick(ladders, height)
    }

    /* ---------------------------------------------- Wide ---------------------------------------------- */

    /**
     * The Wide in [width] by [height] dp: the name, the hero sentence (two lines at most), the number row. The
     * sentence at 20 sp, then 18, then a shorter wording, then left out; grown to 22 and 24 sp and the dots to 6 dp
     * on a tall cell.
     */
    fun wide(m: MomentWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Fit =
        if (m.recording != null) recordingWide(m, m.recording, width, height, fontScale, large = false)
        else pick(ladders(m, width, fontScale, large = false, status), height)

    /** The Large: the Wide plus the moment's chart, with its times under it. */
    fun large(m: MomentWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Fit =
        if (m.recording != null) recordingWide(m, m.recording, width, height, fontScale, large = true)
        else pick(ladders(m, width, fontScale, large = true, status), height)

    /** A chart's block: the chart [h] dp high and its times under it. */
    fun chartBlock(h: Float, fontScale: Float): Float = if (h <= 0f) 0f else h + CHART_LABEL_GAP_DP + WidgetText.line(WidgetText.CAPTION_SP, fontScale)

    private fun ladders(m: MomentWidgetModel, width: Float, fontScale: Float, large: Boolean, status: StatusLine?): List<List<Fit>> {
        val statusTop = statusRow(m, status, width, fontScale, withWords = true)
        val name = nameIn(m, statusTop, width, fontScale)
        val top = topHeight(name, statusTop, fontScale)
        val stages = m.chart?.kind == MomentChartKind.Stages
        val chartBase = when {
            !large || m.chart == null -> emptyList()
            stages -> listOf(STRIP_DP, STRIP_MIN_DP)
            else -> listOf(CHART_DP, CHART_MIN_DP)
        }
        val chartGrowth = if (stages) STRIP_GROWTH else CHART_GROWTH
        fun fit(sentence: Sentence?, dot: Float, chart: Float): Fit? {
            val row = if (m.number != null) numberRow(m, dot, width, fontScale) ?: return null else null
            val waiting = if (m.number == null && sentence == null) m.waiting?.let { line(listOf(it), FIGURE_SIZES, width, fontScale) } else null
            val blocks = listOfNotNull(
                top, sentence?.height(fontScale), row?.height, waiting?.height(fontScale),
                chartBlock(chart, fontScale).takeIf { it > 0f },
            )
            return Fit(name, dot = dot, row = row, sentence = sentence, chart = chart, waiting = waiting, content = blocks.sum(), inner = blocks.size - 1, top = statusTop)
        }
        /**
         * The fit with [text] at [sp], the dots at [dot] and the chart [chart] high, then the fits it grows to on a
         * tall cell: step by step a taller chart and (from 20 sp) a larger sentence, kept to two lines, then larger
         * dots. Shrunk dots or the chart's own step down don't grow: they're already short of room.
         */
        fun ladder(text: String?, sp: Float, chart: Float, dot: Float): List<Fit> {
            val s = text?.let { sentence(it, sp, width, fontScale) ?: return emptyList() }
            val base = fit(s, dot, chart) ?: return emptyList()
            if (dot != DOT || (chart > 0f && chart != chartBase.first())) return listOf(base)
            val chartSeq = if (chart > 0f) listOf(chart) + chartGrowth else listOf(0f)
            val spSeq = if (text != null && sp == SENTENCE_SIZES.first()) listOf(sp) + SENTENCE_GROWTH else listOf(sp)
            val grown = mutableListOf(base)
            var shown = s
            for (i in 1 until maxOf(chartSeq.size, spSeq.size)) {
                shown = text?.let { sentence(it, spSeq[minOf(i, spSeq.lastIndex)], width, fontScale) } ?: shown
                fit(shown, DOT, chartSeq[minOf(i, chartSeq.lastIndex)])?.let { grown += it }
            }
            val last = grown.last()
            for (d in DOT_GROWTH) fit(last.sentence, d, last.chart)?.let { grown += it }
            // Growing never costs the number row its word, figure or Start: those stay as the base has them.
            return grown.filter { it.row?.copy(dot = 0f, height = 0f) == base.row?.copy(dot = 0f, height = 0f) }.distinct()
        }
        val charts = if (chartBase.isEmpty()) listOf(0f) else chartBase
        val options = m.sentences
        // The sentence's size and the dots step down before the sentence gives way to a shorter wording.
        val steps = listOf(SENTENCE_SIZES[0] to DOT, SENTENCE_SIZES[1] to DOT, SENTENCE_SIZES[0] to SMALL_DOT, SENTENCE_SIZES[1] to SMALL_DOT)
        val out = mutableListOf<List<Fit>>()
        // The whole sentence before a shorter one, the chart (stepping down) before a smaller size; then without
        // the chart; then without the sentence.
        for (text in options) for ((sp, dot) in steps) for (ch in charts) out += ladder(text, sp, ch, dot)
        if (chartBase.isNotEmpty()) for (text in options) for ((sp, dot) in steps) out += ladder(text, sp, 0f, dot)
        for (dot in listOf(DOT, SMALL_DOT)) {
            for (ch in charts) out += ladder(null, SENTENCE_SIZES.first(), ch, dot)
            out += ladder(null, SENTENCE_SIZES.first(), 0f, dot)
        }
        return out
    }

    /**
     * The Wide or Large during a workout: the name with Open on the right, the sport's tile, its name in the hero's
     * light type, "Recording since 17:42". Nothing live. The tile and the name grow on a tall cell; the line shortens,
     * then the tile goes, then the line.
     */
    fun recordingWide(m: MomentWidgetModel, r: MomentRecording, width: Float, height: Float, fontScale: Float, large: Boolean): Fit {
        val open = openWidth(fontScale)
        val name = nameLine(m, width, fontScale, beside = open + FIGURE_GAP_DP)
        val top = maxOf(name.height(fontScale), PLAY_DP)
        val ladders = mutableListOf<List<Fit>>()
        for (opts in listOf(listOf(r.since), listOf(r.sinceShort), emptyList())) {
            val since = line(opts, FIGURE_SIZES, width - REC_DOT_DP - REC_DOT_GAP_DP, fontScale)
            if (opts.isNotEmpty() && since == null) continue
            fun at(tile: Float, sp: Float): Fit? {
                val sport = line(listOf(r.sport.name, r.sport.shortName), listOf(sp), width, fontScale)
                    ?: line(listOf(r.sport.name, r.sport.shortName), SPORT_SIZES, width, fontScale) ?: return null
                val parts = listOfNotNull(top, tile.takeIf { it > 0f }, sport.height(fontScale), since?.height(fontScale))
                return Fit(name, tile = tile, sport = sport, since = since, open = true, content = parts.sum(), inner = parts.size - 1)
            }
            val growth = (listOf(TILE_DP to SPORT_SIZES.first()) + TILE_GROWTH.mapIndexed { i, tl -> tl to (SPORT_GROWTH.getOrNull(i - 1) ?: SPORT_SIZES.first()) })
                .let { if (large) it else it.take(2) }
            ladders += growth.mapNotNull { (tl, sp) -> at(tl, sp) }.distinct()
            ladders += listOfNotNull(at(0f, SPORT_SIZES.first()))
        }
        return pick(ladders, height)
    }
}
