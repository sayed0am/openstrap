package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.protocol.services.SportSession.State

class WorkoutRecorderTest {
    /** On the minute, so minute arithmetic reads plainly. */
    private val start = 1_800_000_000L / 60 * 60

    @Test
    fun missingMinutesSkipCoveredCutAndPausedMinutes() {
        // 10 minutes from 30 s past the start minute; live for minutes 1–3, nothing after; paused across minutes 6–7.
        val from = start + 30
        val to = start + 30 + 600
        val live = (60L until 240L).map { start + it }
        val pause = (start + 6 * 60 + 10)..(start + 7 * 60 + 50)
        val missing = WorkoutRecorder.missingMinutes(from, to, listOf(pause), live)
        // Minute 0 is cut by the start, 10 by the end; 1–3 are live; 6 and 7 overlap the pause.
        assertEquals(listOf(4, 5, 8, 9).map { start + it * 60L }, missing)
    }

    @Test
    fun oneLiveSampleCoversItsMinute() {
        assertEquals(emptyList<Long>(), WorkoutRecorder.missingMinutes(start, start + 60, emptyList(), listOf(start + 59)))
    }

    @Test
    fun fillPrefersPerSecondHistoryThenMinuteRecords() {
        val missing = listOf(start, start + 60, start + 120)
        val rows = WorkoutRecorder.fillFromHistory(
            start, missing,
            secondAverages = mapOf(start to 131),
            minuteRecords = mapOf(start to 120, start + 60 to 125, start + 120 to 255),
        )
        // The third minute's 255 is not a heart rate, so it stays missing.
        assertEquals(listOf(AppWorkoutHeartRate(start, start, 131, true), AppWorkoutHeartRate(start, start + 60, 125, true)), rows)
    }

    @Test
    fun historyMinuteCountsAsAWholeMinuteOfZoneTime() {
        val w = AppWorkout(start, start + 120, 1)
        val hr = (0L until 60L).map { AppWorkoutHeartRate(start, start + it, 140) } + AppWorkoutHeartRate(start, start + 60, 140, fromHistory = true)
        val a = WorkoutRecorder.analyse(w, hr, maxHeartRate = 190, zones = HeartZones.STANDARD)
        // 140/190 = 73 %: zone 3. Live samples give 59 s between them, the history minute 55 s of 5-second steps plus the join.
        assertTrue(a.zoneSeconds[2] in 115..120)
        assertEquals(1, a.historyMinutes)
        assertTrue(a.fromPhone)
        assertNull(a.recovery60)
        assertEquals(start + 120, a.end)
    }

    @Test
    fun statsWeighHistoryMinutesByTime() {
        val hr = (0L until 60L).map { AppWorkoutHeartRate(start, start + it, 100) } + AppWorkoutHeartRate(start, start + 60, 160, fromHistory = true)
        val s = WorkoutRecorder.stats(hr)!!
        assertEquals(130, s.average)
        assertEquals(160, s.max)
        assertEquals(100, s.min)
        assertNull(WorkoutRecorder.stats(emptyList()))
    }

    @Test
    fun activeTimeLeavesOutPauses() {
        val w = AppWorkout(start, null, 1).withPause(start + 100, start + 160).copy(pausedAt = start + 400)
        assertEquals(listOf((start + 100)..(start + 160)), w.pauseRanges)
        // 500 s in: 60 s of finished pause and 100 s of the one under way.
        assertEquals(340, WorkoutRecorder.activeSeconds(w, start + 500))
        val ended = w.withPause(start + 400, start + 500).copy(end = start + 600)
        assertEquals(440, WorkoutRecorder.activeSeconds(ended, start + 9_999))
    }

    @Test
    fun validRange() {
        assertTrue(WorkoutRecorder.isValid(30))
        assertTrue(!WorkoutRecorder.isValid(0))
        assertTrue(!WorkoutRecorder.isValid(255))
    }

    // Resting 60, max 190: calm is up to 60 + 0.25 × 130 = 92.5 bpm.
    private fun hrRun(from: Long, to: Long, bpm: Int) = (from until to).map { it to bpm }

    @Test
    fun stoppedAfterEightCalmMinutesEndsAtTheLastActiveMoment() {
        val hr = hrRun(start, start + 600, 140) + hrRun(start + 600, start + 1200, 80)
        val now = start + 600 + WorkoutRecorder.STOPPED_S
        assertEquals(start + 599, WorkoutRecorder.stoppedSince(hr, emptyList(), 60, 190, since = start, now = now))
        // A minute earlier it is still too soon.
        assertNull(WorkoutRecorder.stoppedSince(hr, emptyList(), 60, 190, since = start, now = now - 60))
    }

    @Test
    fun stepsKeepAWorkoutGoingAtACalmHeartRate() {
        val hr = hrRun(start, start + 1200, 80)
        // A first active spell at the start, then walking slowly: steps every minute.
        val active = hrRun(start, start + 60, 120)
        val steps = (0..20).map { start + it * 60L to 1000 + it * 50 }
        assertNull(WorkoutRecorder.stoppedSince(active + hr, steps, 60, 190, since = start, now = start + 1200))
    }

    @Test
    fun neverAsksWithoutAnActiveMomentOrAfterKeepGoing() {
        val calm = hrRun(start, start + 1200, 70)
        assertNull(WorkoutRecorder.stoppedSince(calm, emptyList(), 60, 190, since = start, now = start + 1200))
        val hr = hrRun(start, start + 60, 130) + hrRun(start + 60, start + 1200, 70)
        // "Keep going" at start + 700: nothing active since, so no second prompt.
        assertNull(WorkoutRecorder.stoppedSince(hr, emptyList(), 60, 190, since = start + 700, now = start + 1200))
    }

    @Test
    fun aDroppedStrapNeverEndsAWorkout() {
        // Active, then the strap went quiet: no heart rate at all after.
        val hr = hrRun(start, start + 60, 130)
        assertNull(WorkoutRecorder.stoppedSince(hr, emptyList(), 60, 190, since = start, now = start + 60 + 600))
    }

    @Test
    fun anEarlyEndLeavesTheRestRecordedButOutOfTheFigures() {
        val hr = (0L until 600L).map { AppWorkoutHeartRate(start, start + it, if (it < 300) 150 else 80) }
        val w = AppWorkout(start, start + 300, 1, recordedEnd = start + 600)
        val a = WorkoutRecorder.analyse(w, hr, 190, HeartZones.STANDARD)
        assertEquals(300, a.durationSeconds)
        assertEquals(150, a.workout.averageHeartRate)
        assertEquals(start + 600, a.recordedEnd)
        // Trimming the start too.
        val t = WorkoutRecorder.analyse(w.copy(shownStart = start + 100), hr, 190, HeartZones.STANDARD)
        assertEquals(200, t.durationSeconds)
        assertEquals(200L, t.workout.exerciseSeconds!!.toLong())
        assertEquals(start + 100, t.shownStart)
    }

    @Test
    fun usualAveragesTheLastFiveSimilarWorkouts() {
        fun workout(at: Long, sport: Int, length: Long, bpm: Int) =
            WorkoutRecorder.analyse(AppWorkout(at, at + length, sport), (0L until length).map { AppWorkoutHeartRate(at, at + it, bpm) }, 190, HeartZones.STANDARD)
        val now = workout(start + 100_000, 1, 1800, 140)
        val others = listOf(
            workout(start, 1, 1800, 130),
            workout(start + 10_000, 1, 1500, 150),
            workout(start + 20_000, 3, 1800, 100), // another sport
            workout(start + 30_000, 1, 300, 170), // far shorter
            workout(start + 200_000, 1, 1800, 160), // after it
        )
        val similar = WorkoutRecorder.similar(now, others)
        assertEquals(listOf(start + 10_000, start), similar.map { it.workout.start })
        val u = WorkoutRecorder.usual(similar, HeartZones.STANDARD)!!
        assertEquals(2, u.count)
        assertEquals(140, u.averageHeartRate)
        assertEquals(1650L, u.activeSeconds)
        // 130/190 = 68 %: zone 2; 150/190 = 79 %: zone 3; half each.
        assertEquals(0.5f, u.zoneShares[1], 0.01f)
        assertEquals(0.5f, u.zoneShares[2], 0.01f)
        assertNull(WorkoutRecorder.usual(emptyList(), HeartZones.STANDARD))
    }

    @Test
    fun lapsSplitAtTheirMarks() {
        val hr = (0L until 300L).map { (start + it) to if (it < 100) 120 else 150 }
        val laps = WorkoutRecorder.laps(start, start + 300, listOf(start + 100, start + 999), hr, emptyList(), emptyList())
        // The mark past the end is ignored: two laps.
        assertEquals(2, laps.size)
        assertEquals(100L, laps[0].end - laps[0].start)
        assertEquals(120, laps[0].averageHeartRate)
        assertEquals(150, laps[1].averageHeartRate)
        assertNull(laps[1].meters)
    }

    @Test
    fun aListRowCarriesThePhoneWorkoutsDistance() {
        val start = 1_000_000L
        val a = WorkoutRecorder.analyse(AppWorkout(start, start + 1800, 3, distanceMeters = 3400f, distanceFromSteps = true), emptyList(), 190, HeartZones.STANDARD)
        assertEquals(3400f, a.distanceMeters!!, 0.01f)
        assertTrue(a.distanceFromSteps)
        assertNull(a.sets)
    }

    /* ---------------------------------------------- Energy --------------------------------------------- */

    private val man = org.openstrap.app.data.settings.UserProfile(sex = org.openstrap.app.data.settings.UserProfile.Sex.Male, weightKg = 70f, heightCm = 175)
    private val age = 30
    private val b = EnergyExpenditure.bmr(70f, 175, 30, org.openstrap.app.data.settings.UserProfile.Sex.Male) / 1440f
    private fun live(from: Long, until: Long, bpm: (Long) -> Int) = (from until until).map { AppWorkoutHeartRate(start, it, bpm(it)) }
    private fun minutes(w: AppWorkout, hr: List<AppWorkoutHeartRate>, steps: Map<Long, Int> = emptyMap(), now: Long = w.end ?: start) =
        WorkoutRecorder.kcalMinutes(w, hr, steps, restingHr = 60f, maxHr = 190f, profile = man, age = age, vo2max = 42f, now = now)!!

    @Test
    fun workoutKcalLeavesOutPausedMinutesAndCountsHistoryOnes() {
        // 10 minutes, paused from 2:00 to 3:59; live at 150 bpm except minute 1, which a history row fills.
        val w = AppWorkout(start, start + 600, 1, pauses = "${start + 120}-${start + 239}")
        val hr = live(start, start + 600) { 150 }.filter { it.timestamp !in start + 60 until start + 120 } +
            AppWorkoutHeartRate(start, start + 60, 150, fromHistory = true)
        val m = minutes(w, hr.sortedBy { it.timestamp })
        assertEquals(listOf(0, 1, 4, 5, 6, 7, 8, 9).map { start + it * 60L }, m.map { it.t })
        // x = 90/130 at VO2max 42 (12 METs): 1 + x·11 ≈ 8.6 METs, under the 10.8 cap; every minute alike.
        val met = 1f + 90f / 130f * 11f
        m.forEach { assertEquals(b + EnergyExpenditure.net(met, 70f), it.kcal, 1e-3f) }
        val kcal = WorkoutRecorder.kcal(m)
        assertEquals(8 * EnergyExpenditure.net(met, 70f), kcal.active, 1e-2f)
        assertEquals(kcal.active + 8 * b, kcal.total, 1e-2f)
    }

    @Test
    fun liveKcalNeverGoesDown() {
        val w = AppWorkout(start, null, 1)
        // 30 s in: one reading at 190 bpm (flat out), half a minute gone.
        val first = WorkoutRecorder.liveKcal(minutes(w, live(start, start + 1) { 190 }, now = start + 30), start + 30)
        assertEquals(EnergyExpenditure.net(10.8f, 70f) / 2, first.active, 1e-3f)
        // 59 s in: the rest of the minute at resting heart rate drags the minute's mean down; the figure holds.
        val calm = live(start, start + 59) { if (it == start) 190 else 60 }
        val raw = WorkoutRecorder.liveKcal(minutes(w, calm, now = start + 59), start + 59)
        assertTrue(raw.active < first.active)
        val second = WorkoutRecorder.liveKcal(minutes(w, calm, now = start + 59), start + 59, previous = first)
        assertEquals(first, second)
        // Ten minutes on at 150 bpm it climbs again.
        val later = calm + live(start + 59, start + 600) { 150 }
        val third = WorkoutRecorder.liveKcal(minutes(w, later, now = start + 600), start + 600, previous = second)
        assertTrue(third.active > second.active && third.total > second.total)
    }

    @Test
    fun liveKcalHoldsWhilePaused() {
        // Paused 30 s in, flat out until then: the minute it began in counts for its 30 active seconds only.
        val w = AppWorkout(start, null, 1, pausedAt = start + 30)
        val hr = live(start, start + 30) { 190 }
        fun at(now: Long) = WorkoutRecorder.liveKcal(minutes(w, hr, now = now), now, active = Recovery.activeRanges(w, now))
        val atPause = at(start + 30)
        assertEquals(EnergyExpenditure.net(10.8f, 70f) / 2, atPause.active, 1e-3f)
        // Four minutes later, still paused: the same, not a whole minute's worth.
        assertEquals(atPause, at(start + 270))
    }

    @Test
    fun aDayCountsItsPhoneWorkoutExactly() {
        // 30 minutes: 150 bpm for 15, then 120; the last ten minutes walked at 120 spm.
        val w = AppWorkout(start, start + 1800, 1)
        val steps = (20 until 30).associate { start + it * 60L to 120 }
        val m = minutes(w, live(start, start + 1800) { if (it < start + 900) 150 else 120 }, steps)
        val kcal = WorkoutRecorder.kcal(m)
        fun day(saved: Float?): EnergyExpenditure.Day {
            val inWorkout = EnergyExpenditure.fitted(m, saved, b).map { EnergyExpenditure.Minute(it.t, steps[it.t], null, false, it.kcal) }
            val before = (60 downTo 1).map { EnergyExpenditure.Minute(start - it * 60L, 0, null, false) }
            return EnergyExpenditure.day(before + inWorkout, man, age, vo2max = 42f)!!
        }
        val d = day(kcal.total)
        assertEquals(kcal.active, d.workoutKcal, 1e-2f)
        assertEquals(kcal.total + 60 * 1.15f * b, d.totalKcal, 1e-2f)
        // The walked minutes are the workout's, not walking.
        assertEquals(0f, d.walkingKcal, 0f)
        // A saved total that differs from today's working (older inputs) is still what the day counts.
        val other = day(kcal.total + 50f)
        assertEquals(kcal.active + 50f, other.workoutKcal, 1e-2f)
        assertEquals(kcal.total + 50f + 60 * 1.15f * b, other.totalKcal, 1e-2f)
        // And the workout as listed carries the same total.
        assertEquals(Math.round(kcal.total), WorkoutRecorder.toWorkout(WorkoutRecorder.withKcal(w, m), start + 1800).calories)
    }

    @Test
    fun minuteStepsSpreadEachRiseOverItsMinutes() {
        // 90 steps between 0:30 and 1:30: half in minute 0, half in minute 1. Then 120 over minute 2 exactly.
        val totals = listOf(start + 30 to 1000, start + 90 to 1090, start + 120 to 1090, start + 180 to 1210)
        assertEquals(mapOf(start to 45, start + 60 to 45, start + 120 to 120), WorkoutRecorder.minuteSteps(totals))
        // A fall (a new day) and a gap of 20 minutes give nothing; a burst is held to 250 a minute.
        assertEquals(emptyMap<Long, Int>(), WorkoutRecorder.minuteSteps(listOf(start to 5000, start + 60 to 20)))
        assertEquals(emptyMap<Long, Int>(), WorkoutRecorder.minuteSteps(listOf(start to 0, start + 1200 to 2400)))
        assertEquals(mapOf(start to 250), WorkoutRecorder.minuteSteps(listOf(start to 0, start + 60 to 900)))
    }

    private fun tick(w: AppWorkout, live: List<Pair<Long, Int>>, now: Long, previous: WorkoutRecorder.Kcal? = null, totals: List<Pair<Long, Int>> = emptyList()) =
        WorkoutRecorder.liveKcalTick(w, live, emptyList(), totals, emptyMap(), 60f, 190f, man, age, 42f, now, previous)

    @Test
    fun liveKcalTickLeavesOutThePauseAndOnlyClimbs() {
        // Five minutes at 150 bpm, then paused from 5:00 with nothing recorded.
        val readings = (start until start + 300).map { it to 150 }
        val running = AppWorkout(start, null, 1)
        val atPause = tick(running, readings, start + 300)!!
        val paused = running.copy(pausedAt = start + 300)
        // Ten minutes later, still paused: the figure hasn't moved.
        val later = tick(paused, readings, start + 900, atPause)!!
        assertEquals(atPause, later)
        // A tick that would come out lower (the readings gone, say) holds the figure.
        assertEquals(atPause, tick(running, emptyList(), start + 300, atPause))
        // Resumed at 15:00, two more minutes count; the paused ten don't.
        val resumed = running.copy(pauses = "${start + 300}-${start + 899}")
        val more = readings + (start + 900 until start + 1020).map { it to 150 }
        val after = tick(resumed, more, start + 1020, later)!!
        assertEquals(atPause.active / 5 * 7, after.active, 1e-2f)
        // Without a profile there's no figure.
        assertNull(WorkoutRecorder.liveKcalTick(running, readings, emptyList(), emptyList(), emptyMap(), 60f, 190f,
            org.openstrap.app.data.settings.UserProfile(), age, 42f, start + 300, null))
    }

    @Test
    fun liveKcalTickTakesStepsFromTheStrapsTotals() {
        // A brisk walk at a low heart rate: steps lead. Totals each minute, 130 steps apart.
        val w = AppWorkout(start, null, 3)
        val readings = (start until start + 600).map { it to 80 }
        val totals = (0..10).map { start + it * 60L to 1000 + it * 130 }
        val hrOnly = tick(w, readings, start + 600)!!
        val walked = tick(w, readings, start + 600, totals = totals)!!
        assertTrue(walked.active > hrOnly.active * 1.5f)
    }

    @Test
    fun kcalTargetReachedTimeLeavesPausesOutAndTrimsHold() {
        val start = 1_700_000_000L
        val w = AppWorkout(start, start + 3000, 1, target = "k:300", targetReachedAt = start + 1700).withPause(start + 600, start + 900)
        // 1700 s in, 300 of them paused.
        assertEquals(1400L, WorkoutRecorder.reachedSeconds(w))
        assertEquals(1400L, WorkoutRecorder.analyse(w, emptyList(), 190, HeartZones.STANDARD).targetReachedSeconds)
        assertEquals("k:300", WorkoutRecorder.analyse(w, emptyList(), 190, HeartZones.STANDARD).target)
        // Trimmed so it starts later: counted from the new start.
        assertEquals(700L, WorkoutRecorder.reachedSeconds(w.copy(shownStart = start + 1000)))
        // Trimmed to end before it was reached, or never reached: no time.
        assertNull(WorkoutRecorder.reachedSeconds(w.copy(end = start + 1500)))
        assertNull(WorkoutRecorder.reachedSeconds(w.copy(targetReachedAt = null)))
    }
    /* ------------------------------------- The strap's own record and session ------------------------------------- */

    /** The strap's record of a HIIT session: it read 152 bpm on average where the live stream read 96. */
    private val strapRecord = org.openstrap.app.data.db.Workout(
        start, start + 1800, 49, 1800, 300, 152, 181, 88, 3.4f, 1.2f, 40, null, null, subtype = 6,
    )

    @Test
    fun theStrapsRecordTakesOverEffectAndHeartRate() {
        val phone = AppWorkout(start = start, end = start + 1800, sportType = 49, averageHeartRate = 96, maxHeartRate = 120, vo2max = 44f)
        val effect = org.openstrap.app.data.db.SportEffectRow(start + 1800, 600, null, 52f, null, null, null, null, 191)
        val w = WorkoutRecorder.withStrapRecord(phone, strapRecord, effect)!!
        assertEquals(152, w.averageHeartRate)
        assertEquals(181, w.maxHeartRate)
        assertEquals(52f, w.epoc)
        assertEquals(3.4f, w.aerobicTrainingEffect)
        assertEquals(600, w.recoveryMinutes)
        assertEquals(44f, w.vo2max)
        assertTrue(w.effectFromStrap)
        // The figures shown are the strap's, though the live readings say otherwise.
        val live = (0 until 1800L step 1).map { AppWorkoutHeartRate(start, start + it, 96) }
        val shown = WorkoutRecorder.toWorkout(w, start + 1800, WorkoutRecorder.stats(live))
        assertEquals(152, shown.averageHeartRate)
        assertEquals(181, shown.maxHeartRate)
        // Without the strap's record, the live readings stand.
        assertEquals(96, WorkoutRecorder.toWorkout(phone.copy(averageHeartRate = 140), start + 1800, WorkoutRecorder.stats(live)).averageHeartRate)
    }

    @Test
    fun aStrapRecordWithoutHeartRateKeepsThePhones() {
        val phone = AppWorkout(start = start, end = start + 1800, sportType = 49, averageHeartRate = 96, maxHeartRate = 120)
        val w = WorkoutRecorder.withStrapRecord(phone, strapRecord.copy(averageHeartRate = null, maxHeartRate = null), null)!!
        assertEquals(96, w.averageHeartRate)
        assertEquals(120, w.maxHeartRate)
        // EPOC from the summary's load when there's no sport effect; none at all leaves the model.
        assertEquals(40f, w.epoc)
        assertNull(WorkoutRecorder.withStrapRecord(phone, strapRecord.copy(trainingLoad = null), null))
    }

    @Test
    fun theWorkoutFollowsTheStrapsOwnStateChanges() {
        assertEquals(WorkoutRecorder.StrapAction.PAUSE, WorkoutRecorder.strapAction(State.PAUSE, paused = false))
        assertNull(WorkoutRecorder.strapAction(State.PAUSE, paused = true))
        assertEquals(WorkoutRecorder.StrapAction.RESUME, WorkoutRecorder.strapAction(State.RESUME, paused = true))
        assertNull(WorkoutRecorder.strapAction(State.RESUME, paused = false))
        assertEquals(WorkoutRecorder.StrapAction.END, WorkoutRecorder.strapAction(State.STOP, paused = false))
        assertEquals(WorkoutRecorder.StrapAction.END, WorkoutRecorder.strapAction(State.STOP, paused = true))
        // Zepp leaves these alone.
        assertNull(WorkoutRecorder.strapAction(State.START, paused = false))
        assertNull(WorkoutRecorder.strapAction(State.ABANDON, paused = false))
    }

    @Test
    fun aWorkoutThatIsntKeptAbandonsTheStrapsSession() {
        assertEquals(State.STOP, WorkoutRecorder.closingState(kept = true))
        assertEquals(State.ABANDON, WorkoutRecorder.closingState(kept = false))
    }
}
