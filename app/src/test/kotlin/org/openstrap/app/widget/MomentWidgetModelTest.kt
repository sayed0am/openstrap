package org.openstrap.app.widget

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Moment
import org.openstrap.app.data.MomentInputs
import org.openstrap.app.data.MomentLead
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.device.WidgetUpdater
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.glow.AFTER_RIM
import org.openstrap.app.ui.glow.EVENING_BODY
import org.openstrap.app.ui.glow.GlowMood
import org.openstrap.app.ui.glow.LightAngle
import org.openstrap.app.ui.glow.WINDOW_RIM
import org.openstrap.app.ui.theme.MetricColors

/**
 * Widgets spec 3.1: what the Moment widget shows at each moment, from Today's own resolver, copy and sums; the
 * duplicate rule; the refresh at each moment's end and while it counts minutes; and what fits each size, one line a
 * text, no gap over 32 dp on One UI's cells.
 */
class MomentWidgetModelTest {
    private val zone: ZoneId = ZoneOffset.UTC

    /** A Saturday. */
    private val today = LocalDate.of(2026, 10, 24)

    private fun at(h: Int, m: Int, date: LocalDate = today): Instant = date.atTime(h, m).atZone(zone).toInstant()
    private fun epoch(h: Int, m: Int, date: LocalDate = today) = at(h, m, date).epochSecond

    /* ---------------------------------------------- Sample day ---------------------------------------------- */

    /** The Sleep widget's night: 22:58 to 06:56, score 82, woke 3 times, 7 h 40 asleep. */
    private val sleepStart = epoch(22, 58, today.minusDays(1))
    private val sleepEnd = epoch(6, 56)
    private val session = SleepSession(sleepStart, sleepEnd, score = 82, wakeCount = 3, deepMinutes = 92, remMinutes = 101, lightMinutes = 267, awakeMinutes = 18)

    private fun stages(): List<SleepStage> {
        val plan = listOf(
            SleepStage.LIGHT to 60, SleepStage.DEEP to 60, SleepStage.LIGHT to 90, SleepStage.REM to 50, SleepStage.AWAKE to 10,
            SleepStage.LIGHT to 117, SleepStage.DEEP to 32, SleepStage.REM to 51, SleepStage.AWAKE to 8,
        )
        var t = sleepStart
        return plan.map { (stage, min) -> SleepStage(t, t + min * 60L, sleepStart, stage).also { t += min * 60L } }
    }

    /** Earlier nights this week, shorter: 6 h 10, 7 h, 7 h 20. */
    private val week: Map<LocalDate, SleepSession> = listOf(1 to 370, 2 to 420, 3 to 440).associate { (ago, min) ->
        val d = today.minusDays(ago.toLong())
        val e = d.atTime(7, 0).atZone(zone).toEpochSecond()
        d to SleepSession(e - min * 60L, e)
    }

    private val night = SleepWidgetNight.of(today, session, stages(), week + (today to session))

    /** Energy from 84 at waking down to 62 at [now], a reading every 15 minutes. */
    private fun energy(now: Instant, value: Int = 62): MomentEnergy {
        val n = ((now.epochSecond - sleepEnd) / 900).toInt().coerceAtLeast(1)
        val points = (0..n).map { i -> Recovery.ChargePoint(sleepEnd + i * 900L, 84f + (value - 84f) * i / n, Recovery.State.Activity) }
        return MomentEnergy(value, 84, 38, 60f, true, points)
    }

    /** Heart rate every five minutes since midnight, 60 to 110 and back. */
    private fun heart(now: Instant): List<ChartPoint> {
        val start = epoch(0, 0)
        return (0..((now.epochSecond - start) / 300).toInt()).map { i -> ChartPoint(start + i * 300L, 85f + 25f * kotlin.math.sin(i / 12.0).toFloat()) }
    }

    private val bedtime = LocalTime.of(22, 40)

    /** Three runs at 17:30 on Saturdays: the workout window runs 16:45 to 18:15. */
    private val runs = (1..3).map { epoch(17, 30, today.minusWeeks(it.toLong())) }

    private val recovery = TodayCopy.recoveryShown(78, false, null, null, TabCopy.RECOVERY_WAITING)

    private fun input(
        now: Instant,
        placed: Set<WidgetKind> = emptySet(),
        recording: MomentRecordingInput? = null,
        last: MomentWorkout? = null,
        starts: List<Long> = emptyList(),
        night: SleepWidgetNight? = this.night,
        recovery: TodayCopy.RecoveryShown? = this.recovery,
        energy: MomentEnergy? = energy(now),
        bed: LocalTime? = bedtime,
        firstNights: Boolean = false,
        day: LocalDate = today,
    ) = MomentWidgetInput(
        inputs = MomentInputs(day, recording != null, last?.end, night?.session?.end, bed, starts),
        placed = placed,
        recovery = recovery,
        score = recovery?.value,
        alert = false,
        noScore = null,
        firstNights = firstNights,
        energy = energy,
        load = 42.3f,
        loadStatus = TodayCopy.LoadStatus.SoFar,
        night = night,
        usualSport = 1,
        lastWorkout = last,
        recording = recording,
        dayWorkouts = listOfNotNull(last?.sportType),
        heartRate = heart(now),
        restingHr = 52,
        steps = 8432,
        stepGoal = 10_000,
    )

    private fun model(now: Instant, input: MomentWidgetInput = input(now)) = MomentWidgetModel.from(input, now, zone)

    // The moments, as the resolver gives them for the sample day.
    private val waking = at(8, 0)
    private val midday = at(13, 0)
    private val window = at(17, 20)
    private val during = at(18, 0)
    private val after = at(18, 20)
    private val evening = at(20, 30)
    private val nightBefore = at(22, 10)
    private val nightAfter = at(23, 30)
    private val run = MomentWorkout(1, epoch(17, 42))
    private val recording = MomentRecordingInput(1, epoch(17, 42))

    private fun wakingModel(placed: Set<WidgetKind> = emptySet()) = model(waking, input(waking, placed))
    private fun middayModel() = model(midday)
    private fun windowModel() = model(window, input(window, starts = runs))
    private fun duringModel() = model(during, input(during, recording = recording, starts = runs))
    private fun afterModel() = model(after, input(after, last = run, starts = runs))
    private fun eveningModel() = model(evening, input(evening, last = run))
    private fun nightBeforeModel() = model(nightBefore, input(nightBefore, last = run))
    private fun nightAfterModel() = model(nightAfter, input(nightAfter, last = run))

    /* ---------------------------------------------- Each moment ---------------------------------------------- */

    @Test fun `waking up leads with Recovery, the waking sentence, Energy at waking and the night's stages`() {
        val m = wakingModel()
        assertEquals(Moment.Waking, m.moment)
        assertEquals(MomentLead.Recovery, m.lead)
        assertEquals("Waking up", m.name)
        assertEquals("78", m.number)
        assertEquals(listOf("Ready"), m.words)
        assertEquals(listOf("Energy at waking 84"), m.figures)
        assertEquals(TodayCopy.waking(night.asleepSeconds, 78, false, null, false, isToday = true), m.sentences.first())
        assertEquals("Slept 7 h 40. Recovered enough for your usual training.", m.sentences.first())
        assertTrue("a shorter wording keeps what Recovery says", "Recovered enough for your usual training." in m.sentences)
        val chart = m.chart!!
        assertEquals(MomentChartKind.Stages, chart.kind)
        assertEquals(night.stages, chart.stages)
        assertEquals("22:58" to "06:56", chart.from to chart.to)
        assertEquals(MomentLight(MetricColors.readiness, LightAngle.LowerLeft), m.light)
        assertEquals(GlowMood.Day, m.mood)
        assertEquals("Recovery 78, Ready", m.numberDescription)
        assertFalse(m.opensLive)
        assertNull(m.tick)
    }

    @Test fun `midday leads with Energy, the Charge hero's sentence, drained and recharged, the line since waking`() {
        val m = middayModel()
        assertEquals(Moment.Midday, m.moment)
        assertEquals(MomentLead.Energy, m.lead)
        assertEquals("Midday", m.name)
        assertEquals("62", m.number)
        assertEquals(listOf("Energy left"), m.words)
        assertEquals(listOf("left"), m.shortWords)
        assertEquals("Drained 60, recharged 38", m.figures.first())
        assertEquals(TodayCopy.charge(62, 60, true, 13), m.sentences.first())
        assertEquals("The day so far drained 60. Plenty left for this evening.", m.sentences.first())
        // Lit from below up to the value, as Today's Charge hero.
        assertEquals(0.62f, m.light.level!!, 1e-4f)
        val chart = m.chart!!
        assertEquals(MomentChartKind.Line, chart.kind)
        assertEquals("06:56" to "22:40", chart.from to chart.to)
        // The line runs from waking to now and stops there: the rest of the day is still to come.
        val lastIndex = chart.values.indexOfLast { it != null }
        assertEquals(((epoch(13, 0) - sleepEnd) / 900).toInt(), lastIndex)
        assertTrue(chart.values.drop(lastIndex + 1).all { it == null })
        assertTrue(chart.shares.filterNotNull().all { it in 0f..1f })
        assertEquals(0.62f, chart.shares[lastIndex]!!, 0.01f)
        assertEquals("Energy 62 left", m.numberDescription)
        assertNull(m.tick)
    }

    @Test fun `the workout window leads with Start, the window's sentence and Energy left`() {
        val m = windowModel()
        assertEquals(Moment.WorkoutWindow, m.moment)
        assertEquals(MomentLead.Start, m.lead)
        assertEquals("Workout time", m.name)
        assertEquals("62", m.number)
        assertEquals("Ready for effort. You usually run around now.", m.sentences.first())
        assertEquals(1, m.start)
        assertEquals("Start run", m.startLabel)
        assertTrue("no figure: the Start button stands there", m.figures.isEmpty())
        assertEquals(MomentLight(MetricColors.heart, LightAngle.UpperRight, rim = WINDOW_RIM), m.light)
        assertEquals(MomentChartKind.Line, m.chart?.kind)
    }

    @Test fun `during a workout, the sport and since when, nothing live, opening the live screen`() {
        val m = duringModel()
        assertEquals(Moment.During, m.moment)
        assertEquals(MomentLead.Workout, m.lead)
        assertEquals("Running", m.name)
        assertNull(m.number)
        assertTrue(m.sentences.isEmpty() && m.figures.isEmpty())
        assertNull(m.chart)
        val r = m.recording!!
        assertEquals("Outdoor run", r.sport.name)
        assertEquals("Recording since 17:42", r.since)
        assertEquals("Since 17:42", r.sinceShort)
        assertTrue(m.opensLive)
        assertNull("the recording's end refreshes it", m.tick)
    }

    @Test fun `after a workout, minutes since it, the heart coming down, Load today, the drop since the end`() {
        val m = afterModel()
        assertEquals(Moment.After, m.moment)
        assertEquals(MomentLead.AfterWorkout, m.lead)
        assertEquals("After your run", m.name)
        assertEquals("38", m.number)
        assertEquals("min since your run", m.words.first())
        assertEquals("Load today 42", m.figures.first())
        val lastHr = heart(after).last { it.x <= after.epochSecond }.y.toInt()
        assertEquals(TodayCopy.after(lastHr, 52), m.sentences.single())
        assertEquals(MomentLight(MetricColors.heart, LightAngle.Below, rim = AFTER_RIM), m.light)
        val chart = m.chart!!
        assertEquals("17:42", chart.from)
        // The count moves on: the next five minute mark.
        assertEquals(at(18, 25), m.tick)
    }

    @Test fun `the evening leads with Energy left, the day in a sentence and how long to bedtime`() {
        val m = eveningModel()
        assertEquals(Moment.Evening, m.moment)
        assertEquals(MomentLead.Sentence, m.lead)
        assertEquals("Evening", m.name)
        assertEquals("62", m.number)
        assertEquals(listOf("Bedtime in 2 h 10"), m.figures)
        assertEquals(TodayCopy.evening(listOf("run"), 8432, 10_000, TodayCopy.LoadStatus.SoFar), m.sentences.first())
        assertEquals(GlowMood.Evening, m.mood)
        assertEquals(MomentLight(MetricColors.evening, LightAngle.LowerRight, bodyLightness = EVENING_BODY), m.light)
        assertEquals("the day's heart rate", MomentChartKind.Line, m.chart?.kind)
        assertEquals(at(20, 35), m.tick)
    }

    @Test fun `the night counts the minutes to bedtime, then shows Energy left once it has passed`() {
        val m = nightBeforeModel()
        assertEquals(Moment.Night, m.moment)
        assertEquals("Night", m.name)
        assertEquals("30", m.number)
        assertEquals(listOf("min to bedtime", "min"), m.words)
        assertEquals(listOf("Bedtime 22:40"), m.figures)
        assertEquals(GlowMood.Night, m.mood)
        assertEquals(MomentLight(MetricColors.sleepDeep, LightAngle.Below), m.light)
        assertEquals(at(22, 15), m.tick)
        // The last count before bedtime stops at it, not at the five minute mark past it.
        val late = model(at(22, 36), input(at(22, 36), bed = LocalTime.of(22, 38)))
        assertEquals("2", late.number)
        assertEquals(at(22, 38), late.tick)

        val past = nightAfterModel()
        assertEquals(Moment.Night, past.moment)
        assertEquals("62", past.number)
        assertEquals(listOf("Energy left"), past.words)
        assertEquals(listOf("Bedtime 22:40"), past.figures)
        assertNull(past.tick)
    }

    @Test fun `without a known bedtime the night guesses none`() {
        val m = model(at(22, 30), input(at(22, 30), bed = null))
        assertEquals(Moment.Night, m.moment)
        assertEquals("62", m.number)
        assertTrue(m.figures.isEmpty())
        assertNull(m.tick)
    }

    @Test fun `before the first night, Energy waits with After your first night`() {
        val noEnergy = MomentEnergy(null, null, 0, 0f, false, emptyList())
        val m = model(midday, input(midday, energy = noEnergy, recovery = null, night = null, firstNights = true))
        assertEquals(MomentLead.Energy, m.lead)
        assertNull(m.number)
        assertEquals(AFTER_FIRST_NIGHT, m.waiting)
        assertEquals("Energy starts after your first night. Sleep is what fills it.", m.sentences.first())
        assertNull(m.chart)
        assertTrue(m.figures.isEmpty())
    }

    /* ---------------------------------------------- The duplicate rule ---------------------------------------------- */

    @Test fun `waking with a Recovery widget placed leads with last night, lit from above in Sleep blue`() {
        val m = wakingModel(setOf(WidgetKind.Recovery))
        assertEquals(MomentLead.LastNight, m.lead)
        assertEquals("82", m.number)
        assertEquals(listOf("Sleep score, good", "Good"), m.words)
        // The sentence already says how long you slept, so the figure says how often you woke.
        assertEquals(TabCopy.night(night.asleepSeconds, night.others), m.sentences.first())
        assertEquals(listOf("7 h 40, your longest this week.", "7 h 40 asleep."), m.sentences)
        assertEquals(listOf("Woke 3 times"), m.figures)
        assertEquals(MomentLight(MetricColors.sleepDeep, LightAngle.Above), m.light)
        assertEquals(MomentChartKind.Stages, m.chart?.kind)
        assertEquals("Sleep score 82, good", m.numberDescription)
    }

    @Test fun `waking with Recovery and Sleep both placed leads with Energy, as at midday`() {
        val m = wakingModel(setOf(WidgetKind.Recovery, WidgetKind.Sleep))
        assertEquals(Moment.Waking, m.moment)
        assertEquals(MomentLead.Energy, m.lead)
        assertEquals("Waking up", m.name)
        assertEquals("62", m.number)
        assertEquals("Drained 60, recharged 38", m.figures.first())
        assertEquals(TodayCopy.charge(62, 60, true, 8), m.sentences.first())
        assertNotNull(m.light.level)
    }

    @Test fun `a Sleep widget alone changes nothing at waking, nor do the others`() {
        for (placed in listOf(setOf(WidgetKind.Sleep), setOf(WidgetKind.Steps, WidgetKind.Start, WidgetKind.Moment))) {
            assertEquals(placed.toString(), MomentLead.Recovery, wakingModel(placed).lead)
        }
    }

    @Test fun `the other moments lead the same whatever is placed`() {
        val all = WidgetKind.entries.toSet()
        for (placed in listOf(emptySet(), all)) {
            assertEquals(MomentLead.Energy, model(midday, input(midday, placed)).lead)
            assertEquals(MomentLead.Start, model(window, input(window, placed, starts = runs)).lead)
            assertEquals(MomentLead.Workout, model(during, input(during, placed, recording = recording)).lead)
            assertEquals(MomentLead.AfterWorkout, model(after, input(after, placed, last = run)).lead)
            assertEquals(MomentLead.Sentence, model(evening, input(evening, placed)).lead)
            assertEquals(MomentLead.Sentence, model(nightBefore, input(nightBefore, placed)).lead)
        }
    }

    @Test fun `no sleep last night leads with Energy from waking, saying so, as Today does`() {
        // Without a night, the morning starts at 05:00 and lasts three hours.
        val m = model(at(7, 30), input(at(7, 30), night = null))
        assertEquals(Moment.Waking, m.moment)
        assertEquals(MomentLead.Energy, m.lead)
        assertEquals(TodayCopy.NO_SLEEP, m.sentences.first())
        assertEquals("62", m.number)
    }

    @Test fun `waking without a Recovery value gives way to last night, then to Energy`() {
        val none = TodayCopy.recoveryShown(null, false, TodayCopy.NoScore.Learning(5, 7), null, TabCopy.RECOVERY_WAITING)
        assertEquals(MomentLead.LastNight, model(waking, input(waking, recovery = none)).lead)
        assertEquals(MomentLead.Energy, model(waking, input(waking, setOf(WidgetKind.Sleep), recovery = none)).lead)
        // The strap's estimate stands in, labelled as its own.
        val estimate = TodayCopy.recoveryShown(null, false, TodayCopy.NoScore.Learning(5, 7), 71, TabCopy.RECOVERY_WAITING)
        val m = model(waking, input(waking, recovery = estimate))
        assertEquals(MomentLead.Recovery, m.lead)
        assertEquals("71", m.number)
        assertEquals(listOf("Strap's estimate"), m.words)
    }

    @Test fun `after with no workout to tell, or during with none recording, it's midday's`() {
        val r = MomentWidgetModel.lead(Moment.During, input(during))
        assertEquals(MomentLead.Energy, r)
        assertEquals(MomentLead.Energy, MomentWidgetModel.lead(Moment.After, input(after)))
    }

    /* ---------------------------------------------- Refresh ---------------------------------------------- */

    @Test fun `the alarm is the moment's end or the next count, whichever comes first, only while placed`() {
        val a = at(19, 40)
        val b = at(18, 25)
        assertNull(WidgetUpdater.momentAlarm(false, a, b))
        assertEquals(a, WidgetUpdater.momentAlarm(true, a, null))
        assertEquals(b, WidgetUpdater.momentAlarm(true, a, b))
        assertEquals(b, WidgetUpdater.momentAlarm(true, null, b))
        assertNull(WidgetUpdater.momentAlarm(true, null, null))
        // Only the moment's change wakes the phone; a tick before it waits for the screen.
        assertTrue(WidgetUpdater.momentWakes(a, null))
        assertFalse(WidgetUpdater.momentWakes(a, b))
        assertFalse(WidgetUpdater.momentWakes(null, b))
        assertTrue(WidgetUpdater.momentWakes(b, a))
    }

    @Test fun `midday's alarm is the evening's start, from the inputs the widget rendered with`() {
        val i = input(midday)
        val m = model(midday, i)
        val next = i.inputs.nextChange(midday, zone)
        assertEquals(at(19, 40), next)
        assertEquals(next, WidgetUpdater.momentAlarm(true, next, m.tick))
        assertEquals(Moment.Evening, i.inputs.resolve(next!!, zone))
    }

    @Test fun `five minute marks on the clock`() {
        assertEquals(at(18, 25), MomentWidgetModel.nextTick(at(18, 20)))
        assertEquals(at(18, 25), MomentWidgetModel.nextTick(at(18, 21).plusSeconds(30)))
        assertEquals(at(0, 0, today.plusDays(1)), MomentWidgetModel.nextTick(at(23, 58)))
    }

    /**
     * A whole day on its alarms alone, 06:00 to 04:00: each alarm lands where the moment changes or the count moves
     * on, the counts never wait more than five minutes, and every moment of the day is reached.
     */
    @Test fun `a day on the widget's alarms reaches every moment and keeps its counts fresh`() {
        val last = run
        val started = epoch(17, 0)
        // A run recorded from 17:00 to 17:42: its start and end refresh the widget on their own (spec 7).
        val events = listOf(started, last.end).map(Instant::ofEpochSecond)
        fun inputAt(now: Instant): MomentWidgetInput {
            val t = now.epochSecond
            val rec = if (t in started until last.end) MomentRecordingInput(1, started) else null
            // Past midnight the widget reads the new day, before its night has synced.
            val date = now.atZone(zone).toLocalDate()
            val later = date > today
            return input(now, recording = rec, last = last.takeIf { t >= it.end }, starts = runs, night = if (later) null else night, day = date)
        }
        var now = at(6, 0)
        val end = at(4, 0, today.plusDays(1))
        val seen = mutableListOf<Moment>()
        var steps = 0
        while (now < end && steps < 500) {
            val i = inputAt(now)
            val m = model(now, i)
            if (seen.lastOrNull() != m.moment) seen += m.moment
            // A recording has no known end: its end refreshes the widget. Step to it as the recorder would.
            // A recording has no known end: its end refreshes the widget.
            val alarm = WidgetUpdater.momentAlarm(true, i.inputs.nextChange(now, zone), m.tick)
            if (i.inputs.recording) assertNull(i.inputs.nextChange(now, zone))
            val event = events.firstOrNull { it > now }
            val next = listOfNotNull(alarm, event).minOrNull() ?: break
            assertTrue("$now: the alarm $next is after now", next > now)
            if (m.tick != null) assertTrue("$now: a count waits at most five minutes", next.epochSecond - now.epochSecond <= 300)
            // Just before the alarm the moment is still the one shown.
            val before = next.minusSeconds(1)
            assertEquals("$now: moment before $next", m.moment, MomentWidgetModel.from(inputAt(before), before, zone).moment)
            now = next
            steps++
        }
        assertTrue("stopped early at $now", now >= end)
        assertEquals(
            listOf(Moment.Midday, Moment.Waking, Moment.Midday, Moment.WorkoutWindow, Moment.During, Moment.After, Moment.Evening, Moment.Night),
            seen.dropLastWhile { it != Moment.Night },
        )
    }

    /* ---------------------------------------------- Layout ---------------------------------------------- */

    private val scales = listOf(1f, 1.08f, 1.3f)

    /** One UI's cells inside the padding (spec 2.1), and the three sizes' minimums. */
    private val oneUi = Triple(140f to 180f, 313f to 180f, 313f to 290f)
    private val minimum = Triple(110f to 110f, 260f to 120f, 260f to 220f)

    private fun everyMoment(): List<Pair<String, MomentWidgetModel>> = listOf(
        "waking" to wakingModel(),
        "last night" to wakingModel(setOf(WidgetKind.Recovery)),
        "waking energy" to wakingModel(setOf(WidgetKind.Recovery, WidgetKind.Sleep)),
        "midday" to middayModel(),
        "window" to windowModel(),
        "during" to duringModel(),
        "after" to afterModel(),
        "evening" to eveningModel(),
        "night" to nightBeforeModel(),
        "night past bedtime" to nightAfterModel(),
    )

    /** Every text in [fit] on one line within [width], the sentence in two lines at most, the number row within it. */
    private fun assertOneLine(what: String, fit: MomentLayout.Fit, width: Float, fontScale: Float) {
        listOfNotNull(fit.name, fit.word, fit.waiting, fit.sport).forEach { l ->
            assertTrue("$what: \"${l.text}\" at ${l.sp} is ${l.width(fontScale)} in $width", l.width(fontScale) <= width + 0.01f)
        }
        fit.since?.let { l ->
            val w = MomentLayout.REC_DOT_DP + MomentLayout.REC_DOT_GAP_DP + l.width(fontScale)
            assertTrue("$what: \"${l.text}\" is $w in $width", w <= width + 0.01f)
        }
        fit.sentence?.let { s ->
            assertTrue("$what: \"${s.text}\" takes ${s.lines} lines", s.lines <= MomentLayout.SENTENCE_LINES)
            assertEquals(s.lines, WidgetText.wrappedLines(s.text, s.sp, width, fontScale))
        }
        fit.row?.let { r ->
            val dots = WidgetDots.width(dotsText(fit), r.dot)
            val w = dots + (r.word?.let { MomentLayout.WORD_GAP_DP + it.width(fontScale) } ?: 0f) +
                (if (r.play) MomentLayout.FIGURE_GAP_DP + MomentLayout.PLAY_DP else 0f) +
                (r.figure?.let { MomentLayout.FIGURE_GAP_DP + it.width(fontScale) } ?: 0f)
            assertTrue("$what: the number row is $w in $width", w <= width + 0.01f)
        }
    }

    private var current: MomentWidgetModel? = null
    private fun dotsText(@Suppress("UNUSED_PARAMETER") fit: MomentLayout.Fit) = current?.number.orEmpty()

    private fun fits(m: MomentWidgetModel, cells: Triple<Pair<Float, Float>, Pair<Float, Float>, Pair<Float, Float>>, s: Float) = listOf(
        "Small" to cells.first to MomentLayout.small(m, cells.first.first, cells.first.second, s),
        "Wide" to cells.second to MomentLayout.wide(m, cells.second.first, cells.second.second, s),
        "Large" to cells.third to MomentLayout.large(m, cells.third.first, cells.third.second, s),
    )

    @Test fun `on One UI's cells no gap is over 32 dp, at every moment and font`() {
        for ((what, m) in everyMoment()) for (s in scales) for ((key, fit) in fits(m, oneUi, s)) {
            val (size, cell) = key
            val gap = fit.gap(cell.second)
            assertTrue("$what $size at $s: gap $gap (content ${fit.content}, ${fit.inner} between)", gap <= MomentLayout.TARGET_GAP_DP)
            assertTrue("$what $size at $s: doesn't fit ${cell.second}", fit.fits(cell.second))
        }
    }

    @Test fun `every moment fits every size at its minimum and on One UI, one line a text`() {
        for ((what, m) in everyMoment()) for (s in scales) for (cells in listOf(minimum, oneUi)) for ((key, fit) in fits(m, cells, s)) {
            val (size, cell) = key
            current = m
            assertTrue("$what $size ${cell} at $s: content ${fit.content} + gaps", fit.fits(cell.second))
            assertOneLine("$what $size $cell at $s", fit, cell.first, s)
        }
    }

    @Test fun `the essentials stay at the minimums at the fonts phones use`() {
        for ((what, m) in everyMoment()) for (s in listOf(1f, 1.08f)) {
            val small = MomentLayout.small(m, 110f, 110f, s)
            val wide = MomentLayout.wide(m, 260f, 120f, s)
            val large = MomentLayout.large(m, 260f, 220f, s)
            if (m.number != null) {
                assertTrue("$what Small at $s keeps the number", small.dot > 0f)
                assertNotNull("$what Small at $s keeps the word", small.word)
                assertNotNull("$what Wide at $s keeps the number row", wide.row)
                assertNotNull("$what Large at $s keeps the number row", large.row)
            }
            if (m.sentences.isNotEmpty()) {
                assertNotNull("$what Wide at $s keeps a sentence", wide.sentence)
                assertNotNull("$what Large at $s keeps a sentence", large.sentence)
            }
            if (m.chart != null) assertTrue("$what Large at $s keeps the chart", large.chart > 0f)
            m.recording?.let { assertNotNull("$what Small at $s keeps the sport", small.sport) }
        }
    }

    @Test fun `the workout window keeps its Start button at every size on One UI and at the minimum`() {
        val m = windowModel()
        for (s in listOf(1f, 1.08f)) {
            assertTrue(MomentLayout.small(m, 110f, 110f, s).play)
            assertTrue(MomentLayout.wide(m, 260f, 120f, s).row!!.play)
            assertTrue(MomentLayout.large(m, 313f, 290f, s).row!!.play)
        }
    }

    @Test fun `on One UI the Wide shows the mockup's whole number row and sentence at midday`() {
        val fit = MomentLayout.wide(middayModel(), 313f, 180f, 1f)
        assertEquals("left", fit.row!!.word!!.text)
        assertEquals("Drained 60, recharged 38", fit.row.figure!!.text)
        assertEquals("The day so far drained 60. Plenty left for this evening.", fit.sentence!!.text)
    }

    @Test fun `a tall cell grows the parts before the gaps, a short one shrinks them`() {
        val m = middayModel()
        val tall = MomentLayout.large(m, 313f, 290f, 1f)
        val short = MomentLayout.large(m, 260f, 220f, 1.3f)
        assertTrue("grown ${tall.chart} over ${MomentLayout.CHART_DP}", tall.chart >= MomentLayout.CHART_DP)
        assertTrue(short.chart <= MomentLayout.CHART_DP)
        val small = MomentLayout.small(m, 140f, 180f, 1f)
        assertTrue("the Small's dots grow past the mockup's on One UI", small.dot > MomentLayout.SMALL_DOT)
    }

    @Test fun `a sentence too long for two lines gives way to a shorter wording, never a third line`() {
        val long = middayModel().copy(sentences = MomentWidgetModel.shorter("A very long sentence that goes on and on about the day and what it drained and what it left. Short one."))
        for (s in scales) {
            val fit = MomentLayout.wide(long, 260f, 120f, s)
            fit.sentence?.let { assertTrue(it.lines <= 2) }
        }
        assertEquals(listOf("A b. C d e.", "C d e.", "A b."), MomentWidgetModel.shorter("A b. C d e."))
    }

    @Test fun `one dotted number at every size`() {
        for ((what, m) in everyMoment()) for ((key, fit) in fits(m, oneUi, 1f)) {
            val numbers = listOf(fit.dot > 0f && fit.row == null, fit.row != null).count { it }
            assertTrue("$what ${key.first}: $numbers", numbers <= 1)
            if (m.number == null) assertEquals(0, numbers)
        }
    }

    @Test fun `every string keeps to the copy rules`() {
        for ((what, m) in everyMoment()) {
            val texts = listOfNotNull(m.name, m.number, m.waiting, m.startLabel, m.numberDescription, m.chart?.description, m.chart?.from, m.chart?.to,
                m.recording?.since, m.recording?.sinceShort) + m.words + m.shortWords + m.figures + m.sentences
            texts.forEach { assertFalse("$what: $it", it.any { c -> c in "·;—" }) }
        }
    }

    @Test fun `the hero's light is Today's, at the moment's mood`() {
        val style = middayModel().light.style(GlowMood.Day)
        assertEquals(0.62f, style.lightLevel!!, 1e-4f)
        val ev = eveningModel()
        val evStyle = ev.light.style(ev.mood)
        assertEquals(GlowMood.Evening, evStyle.mood)
        assertEquals(EVENING_BODY, evStyle.bodyLightness, 1e-4f)
        assertEquals(LightAngle.LowerRight, evStyle.lightAngle)
        assertEquals(WINDOW_RIM, windowModel().light.style(GlowMood.Day).glowStrength, 1e-4f)
    }

    @Test fun `with the strap status the name stays and the row counts the button`() {
        val m = WidgetSamples.moment(LocalDate.of(2026, 10, 24), zone)
        val l = StatusLine(StrapStatus.StaleSync(0), listOf("Synced Wednesday", "Synced Wed", "Wednesday", "Wed"))
        val wide = MomentLayout.wide(m, 313f, 180f, 1f, l)
        assertEquals(m.name, wide.name.text)
        assertEquals(StatusButton.SIZE_DP, wide.top!!.height, 0f)
        assertNotNull(wide.top.words)
        assertTrue(wide.fits(180f))
        assertNull(MomentLayout.small(m, 140f, 180f, 1f, l).top!!.words)
        assertNull(MomentLayout.wide(m, 313f, 180f, 1f).top)
    }
}
