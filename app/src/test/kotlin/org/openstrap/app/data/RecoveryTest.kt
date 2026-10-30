package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Recovery.Factor
import org.openstrap.app.data.Recovery.Minute
import org.openstrap.app.data.Recovery.Night
import org.openstrap.app.data.Recovery.State
import org.openstrap.app.data.db.SleepStage
import kotlin.random.Random

class RecoveryTest {
    private val day = 86_400L

    private fun night(i: Int, hrv: Float? = 50f, rhr: Float? = 55f, temp: Float? = 33.5f, breath: Float? = 14f, asleep: Int = 480) =
        Night(
            day = i * day, start = i * day - 7 * 3600, end = i * day + 30 * 60, asleepMinutes = asleep, awakeMinutes = 20,
            deepMinutes = 80, remMinutes = 95, hrv = hrv, restingHr = rhr, rhrMeanSleep = rhr?.plus(4f), nadirTime = i * day - 4 * 3600,
            skinTemperature = temp, breathRate = breath, hrvSamples = 20, hrMinutes = 420,
        )

    /** 30 ordinary nights with realistic night-to-night noise. */
    private fun history(rnd: Random = Random(1)) = (1..30).map {
        night(it, hrv = 50f * (1 + 0.08f * (rnd.nextFloat() - 0.5f) * 2), rhr = 55f + rnd.nextInt(-2, 3), temp = 33.5f + 0.1f * (rnd.nextFloat() - 0.5f))
    }

    @Test
    fun zScoreAnchors() {
        assertEquals(80, Recovery.scoreFromZ(0f))
        assertEquals(93, Recovery.scoreFromZ(1f))
        assertEquals(55, Recovery.scoreFromZ(-1f))
        assertEquals(27, Recovery.scoreFromZ(-2f))
    }

    @Test
    fun ordinaryNightIsReady() {
        val r = Recovery.readiness(night(31), history())
        assertNotNull(r.score)
        assertTrue("score ${r.score}", r.score!! in 75..95)
        assertFalse(r.strainAlert)
        assertFalse(r.capped)
        assertFalse(r.calibrating)
        assertEquals(Recovery.Phase.Calibrated, r.phase)
        val factors = r.contributors.map { it.factor }.toSet()
        assertTrue(factors.containsAll(listOf(Factor.HrvBalance, Factor.RestingHr, Factor.Sleep, Factor.SleepBalance, Factor.Temperature, Factor.RecoveryIndex)))
    }

    @Test
    fun concordantSignalsRaiseAnAlertAndCapTheScore() {
        // Low HRV, resting HR up 9 bpm and skin temperature up together: several signals off baseline at once.
        val sick = night(31, hrv = 30f, rhr = 64f, temp = 34.4f)
        val r = Recovery.readiness(sick, history())
        assertTrue(r.strainAlert)
        assertTrue(r.alertSignals.size >= 2)
        assertTrue("score ${r.score}", r.score!! <= Recovery.ALERT_CAP)
    }

    @Test
    fun oneRedFlagCannotBeAveragedAway() {
        // Everything is great except HRV, which collapsed.
        val r = Recovery.readiness(night(31, hrv = 20f), history())
        val hrv = r.contributors.first { it.factor == Factor.HrvBalance }.score
        assertTrue(hrv < Recovery.RED_FLAG)
        assertTrue(r.score!! <= hrv + Recovery.RED_FLAG_HEADROOM)
        assertTrue(r.capped)
    }

    @Test
    fun withoutHrvOrRestingHrReadinessIsWithheld() {
        // Before 7 valid nights there's no HRV or resting HR contributor, so no score: sleep alone isn't readiness.
        val r = Recovery.readiness(night(7), (1..6).map { night(it) })
        assertTrue(r.calibrating)
        assertEquals(Recovery.Phase.Withheld, r.phase)
        assertTrue(r.contributors.none { it.factor == Factor.HrvBalance || it.factor == Factor.RestingHr })
        assertTrue(r.contributors.any { it.factor == Factor.Sleep })
        assertNull(r.score)
        assertTrue(r.withheld)
        assertTrue(r.showStrapEstimate)
        // At 7 valid nights it's scored, labelled learning.
        val learning = Recovery.readiness(night(8), (1..7).map { night(it) })
        assertEquals(Recovery.Phase.Learning, learning.phase)
        assertNotNull(learning.score)
        assertFalse(learning.withheld)
    }

    @Test
    fun phasesFollowValidNights() {
        assertEquals(Recovery.Phase.Withheld, Recovery.phase(6))
        assertEquals(Recovery.Phase.Learning, Recovery.phase(7))
        assertEquals(Recovery.Phase.Learning, Recovery.phase(13))
        assertEquals(Recovery.Phase.Scored, Recovery.phase(14))
        assertEquals(Recovery.Phase.Scored, Recovery.phase(27))
        assertEquals(Recovery.Phase.Calibrated, Recovery.phase(28))
    }

    @Test
    fun deadZoneIgnoresChangesUnderHalfAnSd() {
        assertEquals(0f, Recovery.deadZone(0.3f), 1e-6f)
        assertEquals(0f, Recovery.deadZone(-0.5f), 1e-6f)
        assertEquals(0.5f, Recovery.deadZone(1f), 1e-6f)
        assertEquals(-0.7f, Recovery.deadZone(-1.2f), 1e-6f)
        // A night a little off the usual scores as normal.
        val past = (1..30).map { night(it, hrv = 50f + (it % 5 - 2) * 2f) }
        val r = Recovery.readiness(night(31, hrv = 49f), past)
        assertEquals(80, r.contributors.first { it.factor == Factor.HrvBalance }.score)
    }

    @Test
    fun robustBaselineUsesMedianAndMadWithFloors() {
        val b = Recovery.baseline(listOf(10f, 11f, 12f, 13f, 14f, 15f, 16f), floor = 0.1f)!!
        assertEquals(13f, b.centre, 1e-5f)
        assertEquals(1.4826f * 2f, b.scale, 1e-4f) // MAD of 3,2,1,0,1,2,3 is 2
        // One wild night barely moves it.
        val withOutlier = Recovery.baseline(listOf(10f, 11f, 12f, 13f, 14f, 15f, 90f), floor = 0.1f)!!
        assertEquals(13f, withOutlier.centre, 1e-5f)
        assertEquals(1.4826f * 2f, withOutlier.scale, 1e-4f)
        // A perfectly steady baseline uses the floor.
        assertEquals(Recovery.RHR_SD_FLOOR, Recovery.baseline(List(10) { 55f }, Recovery.RHR_SD_FLOOR)!!.scale, 1e-6f)
        assertNull(Recovery.baseline(List(6) { 55f }, Recovery.RHR_SD_FLOOR))
    }

    @Test
    fun hrvBlendsTonightAndTheWeek() {
        // A steady baseline of 50 ms, then six low nights: tonight is back to usual but the 7-night mean still drags.
        val base = (1..24).map { night(it, hrv = 50f * (1 + 0.04f * ((it % 5) - 2))) }
        val low = (25..30).map { night(it, hrv = 38f) }
        val r = Recovery.readiness(night(31, hrv = 50f), base + low)
        val hrv = r.contributors.first { it.factor == Factor.HrvBalance }.score
        assertTrue("hrv $hrv", hrv < 70)
        assertEquals(Recovery.HrvRange.Below, r.hrvRange)
        assertEquals(7, r.hrvWeekNights)
        assertFalse(r.hrvWeekThin)
    }

    @Test
    fun invalidNightsStayOutOfTheBaselineAndTheStrapFillsIn() {
        // Too few RMSSD samples: our HRV and resting HR don't count tonight; the strap's values show as a fallback.
        val thin = night(31, hrv = 20f).copy(hrvSamples = 3, strapHrv = 48f, strapRestingHr = 56f)
        val r = Recovery.readiness(thin, history())
        assertNull(r.contributors.firstOrNull { it.factor == Factor.HrvBalance })
        assertEquals(48f, r.fallbackHrv)
        assertEquals(56f, r.fallbackRestingHr)
        assertTrue(r.withheld)
        // Invalid nights in history are ignored, even with wild values.
        val junk = (1..30).map { night(it, hrv = 500f, rhr = 99f).copy(hrMinutes = 30) }
        val r2 = Recovery.readiness(night(31), junk)
        assertEquals(Recovery.Phase.Withheld, r2.phase)
        assertEquals(0, r2.baselineNights)
    }

    @Test
    fun ownAndStrapDisagreeingRaisesAQualityFlag() {
        val r = Recovery.readiness(night(31, hrv = 50f, rhr = 55f).copy(strapHrv = 62f, strapRestingHr = 59f), history())
        assertEquals(2, r.qualityFlags.size)
        val close = Recovery.readiness(night(31, hrv = 50f, rhr = 55f).copy(strapHrv = 54f, strapRestingHr = 57f), history())
        assertTrue(close.qualityFlags.isEmpty())
    }

    @Test
    fun noSleepNoScore() {
        assertNull(Recovery.readiness(null, history()).score)
    }

    @Test
    fun sleepDebtLowersSleepBalance() {
        val short = (1..13).map { night(it, asleep = 330) }
        val rested = (1..13).map { night(it, asleep = 480) }
        val debt = Recovery.readiness(night(14, asleep = 330), short).contributors.first { it.factor == Factor.SleepBalance }.score
        val none = Recovery.readiness(night(14, asleep = 480), rested).contributors.first { it.factor == Factor.SleepBalance }.score
        assertEquals(100, none)
        assertEquals(0, debt) // 2.5 h short for 14 nights is far more than 4 h of debt
    }

    @Test
    fun sleepNeedFollowsAgeAndOverride() {
        assertEquals(480, Recovery.sleepNeed(30))
        assertEquals(480, Recovery.sleepNeed(null))
        assertEquals(450, Recovery.sleepNeed(65))
        assertEquals(510, Recovery.sleepNeed(70, override = 510))
        assertEquals(480, Recovery.sleepNeed(30, override = 600)) // out of 420-540: ignored
    }

    @Test
    fun sleepScoreIsDurationAndContinuity() {
        assertEquals(76.56f, Recovery.durationScore(420), 0.01f) // 7 of 8 h
        assertEquals(56.25f, Recovery.durationScore(360), 0.01f)
        assertEquals(100f, Recovery.durationScore(540), 0.01f)
        assertEquals(100f, Recovery.continuityScore(30), 0.01f)
        assertEquals(50f, Recovery.continuityScore(75), 0.01f)
        assertEquals(0f, Recovery.continuityScore(120), 0.01f)
        val n = night(1, asleep = 420).copy(wasoMinutes = 30, deepMinutes = 0, remMinutes = 0)
        assertEquals(84, Recovery.sleepScore(n)) // 0.7·76.6 + 0.3·100; no stage target
        assertEquals(77, Recovery.sleepScore(n.copy(wasoMinutes = null)))
    }

    @Test
    fun wasoCountsWakeBetweenFirstAndLastSleep() {
        val stages = listOf(
            Triple(0L, 1200L, SleepStage.AWAKE), // before sleep onset: not WASO
            Triple(1200L, 4800L, SleepStage.LIGHT),
            Triple(4800L, 6600L, SleepStage.AWAKE), // 30 min
            Triple(6600L, 9000L, SleepStage.DEEP),
            Triple(9000L, 9600L, SleepStage.AWAKE), // after the final wake: not WASO
        )
        assertEquals(30, Recovery.wasoMinutes(stages))
        assertNull(Recovery.wasoMinutes(listOf(Triple(0L, 600L, SleepStage.AWAKE))))
    }

    @Test
    fun sleepDebtFollowsTheRecursion() {
        val l = Recovery.SLEEP_DEBT_DECAY
        assertEquals(0.906f, l, 0.001f)
        fun nights(vararg asleep: Int) = asleep.mapIndexed { i, a -> night(i + 1, asleep = a) }
        // Five ordinary nights, then two nights 2 h short: debt = 120·λ + 120.
        assertEquals(120 * l + 120, Recovery.sleepDebtMinutes(nights(480, 480, 480, 480, 480, 360, 360), 7 * day)!!, 0.01f)
        // A 10 h night repays half its 2 h surplus, capped at an hour.
        assertEquals(max0(120 * l * l + 120 * l - 60), Recovery.sleepDebtMinutes(nights(480, 480, 480, 480, 360, 360, 600), 7 * day)!!, 0.01f)
        // Naps count, missing nights are skipped, and fewer than 7 nights withholds it.
        val napped = nights(480, 480, 480, 480, 480, 480, 360).mapIndexed { i, n -> if (i == 6) n.copy(napMinutes = 120) else n }
        assertEquals(0f, Recovery.sleepDebtMinutes(napped, 7 * day)!!, 0.01f)
        val gappy = nights(480, 480, 480, 480, 480, 480, 480, 360).filterIndexed { i, _ -> i != 3 }
        assertEquals(120f, Recovery.sleepDebtMinutes(gappy, 8 * day)!!, 0.01f)
        assertNull(Recovery.sleepDebtMinutes(nights(480, 480, 480, 480, 480, 360), 6 * day))
        // Only the last 14 nights count.
        val old = (1..20).map { night(it, asleep = if (it <= 6) 0 else 480) }
        assertEquals(0f, Recovery.sleepDebtMinutes(old, 20 * day)!!, 0.01f)
    }

    private fun max0(v: Float) = if (v < 0) 0f else v

    @Test
    fun lateHeartRateNadirLowersRecoveryIndex() {
        val n = night(31)
        val early = Recovery.readiness(n.copy(nadirTime = n.start + 2 * 3600), history()).contributors.first { it.factor == Factor.RecoveryIndex }
        val late = Recovery.readiness(n.copy(nadirTime = n.end - 30 * 60), history()).contributors.first { it.factor == Factor.RecoveryIndex }
        assertEquals(100, early.score)
        assertTrue(late.score < 60)
    }

    @Test
    fun trimpMatchesBanisterWorkedExample() {
        // HRmax 200, resting 40, 30 min at 130 bpm: Banister TRIMP ≈ 32.
        val x = Recovery.hrr(130, 40f, 200f)
        assertEquals(32f, 30 * Recovery.trimpMinute(x), 1.5f)
    }

    @Test
    fun ewmaOfConstantLoadIsBalanced() {
        val (a, c) = Recovery.ewmaLoads(List(28) { 60f })
        assertEquals(60f, a, 0.01f)
        assertEquals(60f, c, 0.01f)
    }

    @Test
    fun loadSpikeIsPenalised() {
        val loads = List(27) { 40f } + 200f
        val r = Recovery.readiness(night(31), history(), loads)
        assertTrue(r.contributors.first { it.factor == Factor.PreviousDayLoad }.score < 100)
        // ACWR is descriptive now, on the Load screen, never a Readiness contributor.
        assertTrue(Recovery.acwr(loads)!! > 1.3f)
        assertNull(Recovery.acwr(loads.takeLast(13)))
    }

    @Test
    fun weightsMatchTheEvidenceReview() {
        val w = Factor.entries.associate { it to it.weight }
        assertEquals(0.52f, w.getValue(Factor.HrvBalance) + w.getValue(Factor.RestingHr), 1e-5f)
        assertEquals(0.28f, w.getValue(Factor.Sleep) + w.getValue(Factor.SleepBalance), 1e-5f)
        assertEquals(0.13f, w.getValue(Factor.Temperature) + w.getValue(Factor.Breathing) + w.getValue(Factor.BloodOxygen), 1e-5f)
        assertEquals(1f, w.values.sum(), 1e-5f)
    }

    // --- Charge ------------------------------------------------------------------------------------

    private val params = Recovery.ChargeParams(restingHr = 58f, maxHr = 188f, nightRestingHr = 52f)

    private fun run(n: Int, f: (Int) -> Minute) = List(n) { f(it) }

    @Test
    fun aGoodNightRechargesFromLowToHigh() {
        val stages = listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM)
        val night = run(450) { Minute(it * 60L, 54, 0, null, stages[(it / 25) % 4]) }
        val r = Recovery.charge(night, params, 0L..450 * 60L, start = 30f)
        assertTrue("charge ${r.latest}", r.latest!! in 85f..96f)
    }

    @Test
    fun restlessNightRechargesLess() {
        val good = run(450) { Minute(it * 60L, 52, 0, null, SleepStage.LIGHT) }
        val poor = run(450) { Minute(it * 60L, 68, 0, null, SleepStage.LIGHT) }
        val a = Recovery.charge(good, params, 0L..450 * 60L, start = 30f).latest!!
        val b = Recovery.charge(poor, params.copy(hrvScore = 30), 0L..450 * 60L, start = 30f).latest!!
        assertTrue("good $a poor $b", a - b > 8)
    }

    @Test
    fun quietWakingDrainsSlowly() {
        val day = run(600) { Minute(it * 60L, 64, 0, 30, null) }
        val r = Recovery.charge(day, params, 0L..600 * 60L, start = 80f, requireAnchor = false)
        // Rest drains in proportion to charge: 80 → 80·(1 − REST_DRAIN)^600 ≈ 59, slower as it falls.
        assertEquals(80f * Math.pow(1.0 - Recovery.REST_DRAIN, 600.0).toFloat(), r.latest!!, 0.5f)
        assertEquals(0f, r.drainedExercise, 0.01f)
    }

    @Test
    fun aCalmDeskHourDrainsLessThanTheActiveBaseDrain() {
        // An hour at a desk at resting-ish HR, no steps, Zepp-"relaxed" stress 35: Rest, which drains well under the
        // BASE_DRAIN (2.4 an hour) an active minute pays.
        val desk = Recovery.charge(run(60) { Minute(it * 60L, 64, 0, 35, null) }, params, 0L..3_600L, start = 60f, requireAnchor = false)
        val drop = 60f - desk.latest!!
        assertTrue("drop $drop", drop > 0f && drop < 60 * Recovery.BASE_DRAIN * 0.8f)
        assertTrue(desk.points.all { it.state == State.Rest })
        // Calm enough to Relax (stress under 25): no drain at all, only the slow recharge.
        val calm = Recovery.charge(run(60) { Minute(it * 60L, 64, 0, 20, null) }, params, 0L..3_600L, start = 60f, requireAnchor = false)
        assertTrue("calm ${calm.latest}", calm.latest!! > 60f)
    }

    /** 30 % of reserve for [params] (58-188): Exercise intensity by heart rate alone. */
    private val raisedHr = 98
    /** 25 % of reserve: Activity intensity, below the sustained-effort line ([Recovery.SUSTAINED_EFFORT_MINUTES]). */
    private val sittingHr = 90

    @Test
    fun sittingWithARaisedHeartRateDoesNotDrainAsActivity() {
        // Heat or coffee: HR at 25 % of reserve, no steps, stress in the normal band. Rest, not Exercise or Activity.
        val sitting = Recovery.charge(run(60) { Minute(it * 60L, sittingHr, 0, 45, null) }, params, 0L..3_600L, start = 70f, requireAnchor = false)
        assertTrue(sitting.points.all { it.state == State.Rest })
        assertEquals(0f, sitting.drainedExercise, 1e-4f)
        val rest = Recovery.charge(run(60) { Minute(it * 60L, 64, 0, 45, null) }, params, 0L..3_600L, start = 70f, requireAnchor = false)
        assertEquals(rest.latest!!, sitting.latest!!, 0.01f)
        // With a low stress score it still can't Relax: the raised heart rate takes the recharge away.
        val calmScore = Recovery.charge(run(60) { Minute(it * 60L, sittingHr, 0, 15, null) }, params, 0L..3_600L, start = 70f, requireAnchor = false)
        val relaxed = Recovery.charge(run(60) { Minute(it * 60L, 64, 0, 15, null) }, params, 0L..3_600L, start = 70f, requireAnchor = false)
        assertTrue(calmScore.points.none { it.state == State.Relax })
        assertTrue(calmScore.latest!! < relaxed.latest!!)
        // Sitting stress still drains as stress.
        val tense = Recovery.charge(run(60) { Minute(it * 60L, sittingHr, 0, 80, null) }, params, 0L..3_600L, start = 70f, requireAnchor = false)
        assertTrue(tense.drainedStress > 0f)
        assertEquals(0f, tense.drainedExercise, 1e-4f)
    }

    @Test
    fun aWalkStillDrains() {
        // The same heart rate while walking (100 steps a minute) is effort.
        val walk = Recovery.charge(run(60) { Minute(it * 60L, raisedHr, 100, 45, null) }, params, 0L..3_600L, start = 70f, requireAnchor = false)
        val sitting = Recovery.charge(run(60) { Minute(it * 60L, sittingHr, 0, 45, null) }, params, 0L..3_600L, start = 70f, requireAnchor = false)
        assertTrue(walk.points.all { it.state == State.Exercise || it.state == State.Activity })
        assertTrue(walk.drainedExercise > 3f)
        assertTrue(walk.latest!! < sitting.latest!! - 3f)
        // A slower walk (a few steps a minute) at activity intensity still counts too.
        val stroll = Recovery.charge(run(30) { Minute(it * 60L, 88, 20, 45, null) }, params, 0L..1_800L, start = 70f, requireAnchor = false)
        assertTrue(stroll.points.all { it.state == State.Activity })
    }

    @Test
    fun aWorkoutIsUnchanged() {
        // A recorded workout without steps (cycling, weights): 30 minutes at 40 % of reserve, then 20 still minutes
        // after it at 15 %. Inside the workout and its stress exclusion heart rate counts as before: Exercise with
        // BASE_DRAIN plus TRIMP, then BASE_DRAIN.
        val hr = 110
        val x = Recovery.hrr(hr, params.restingHr, params.maxHr)
        val active = run(30) { Minute(it * 60L, hr, 0, null, null, Recovery.WorkoutPhase.Active) }
        val after = run(20) { Minute((30 + it) * 60L, 78, 0, null, null, Recovery.WorkoutPhase.After) }
        val r = Recovery.charge(active + after, params, 0L..3_000L, start = 80f, requireAnchor = false)
        val expected = 80f - 30 * (Recovery.BASE_DRAIN + Recovery.EXERCISE_DRAIN_PER_TRIMP * Recovery.trimpMinute(x)) - 20 * Recovery.BASE_DRAIN
        assertEquals(expected, r.latest!!, 0.01f)
        assertTrue(r.points.filter { it.t < 30 * 60L }.all { it.state == State.Exercise })
    }

    /** Half of reserve for [params]: hard enough that a sustained run without steps is unrecorded exercise. */
    private val hardHr = 123

    @Test
    fun halfAnHourHardWithoutStepsIsExercise() {
        // Cycling or rowing that wasn't recorded: 30 minutes at HRR 0.5, no steps, stress high from the effort.
        val r = Recovery.charge(run(30) { Minute(it * 60L, hardHr, 0, 70, null) }, params, 0L..1_800L, start = 80f, sampleSeconds = 60, requireAnchor = false)
        assertTrue(r.points.all { it.state == State.Exercise })
        assertTrue("exercise ${r.drainedExercise}", r.drainedExercise > 5f)
        assertEquals(0f, r.drainedStress, 1e-4f)
    }

    @Test
    fun fiveHardMinutesWithoutStepsAreNotExercise() {
        // A short spike sitting down (a tense call, a coffee): Rest or Stress, never Exercise or Activity.
        val spike = run(5) { Minute(it * 60L, hardHr, 0, 70, null) } + run(30) { Minute((5 + it) * 60L, 64, 0, 40, null) }
        val r = Recovery.charge(spike, params, 0L..2_100L, start = 80f, sampleSeconds = 60, requireAnchor = false)
        assertTrue(r.points.none { it.state == State.Exercise || it.state == State.Activity })
        assertEquals(0f, r.drainedExercise, 1e-4f)
        // Nine minutes is still short of the run.
        assertTrue(Recovery.sustainedEffort(run(9) { Minute(it * 60L, hardHr, 0, 70, null) }, params).none { it })
        assertTrue(Recovery.sustainedEffort(run(10) { Minute(it * 60L, hardHr, 0, 70, null) }, params).all { it })
    }

    @Test
    fun aCalmAwakeDayHolds() {
        // Eight calm, still hours (stress 20, resting HR) from 85: Zepp's toy replay ends at 86 (native 03 §11).
        val r = Recovery.charge(run(480) { Minute(it * 60L, 60, 0, 20, null) }, params, 0L..28_800L, start = 85f, requireAnchor = false)
        assertTrue("calm ${r.latest}", r.latest!! in 85f..87f)
        assertTrue(r.points.all { it.state == State.Relax })
    }

    /**
     * Two nights of 7.5 h light sleep, then a day worn until 20:00 equivalent, the strap off for 11 hours over the
     * third night (marked off-wrist), and a worn morning. Minute 2880 is the third day's midnight.
     */
    private fun strapOffNight(): Pair<List<Minute>, Recovery.ChargeParams> {
        val sleep = { from: Int -> run(450) { Minute((from + it) * 60L, 54, 0, null, SleepStage.LIGHT) } }
        val awake = { from: Int, n: Int -> run(n) { Minute((from + it) * 60L, 66, if (it % 30 == 0) 80 else 0, 45, null) } }
        val off = run(660) { Minute((2640 + it) * 60L, null, 0, null, null, offWrist = true) }
        val minutes = sleep(0) + awake(450, 990) + sleep(1440) + awake(1890, 750) + off + awake(3300, 900)
        val p = params.copy(
            mainSleepEnds = listOf(450 * 60L, 1890 * 60L),
            usualSleep = listOf(0L..450 * 60L, 1440 * 60L..1890 * 60L, 2880 * 60L..3330 * 60L),
        )
        return minutes to p
    }

    @Test
    fun aNightWithTheStrapOffIsNotASleeplessNight() {
        val (minutes, p) = strapOffNight()
        val window = 2880 * 60L until 4320 * 60L
        val r = Recovery.charge(minutes, p, window, start = 50f)
        assertTrue(r.strapOffOvernight)
        // Restarted from the median wake value: the second night's (the first carries the starting guess).
        val wake = Recovery.charge(minutes, p, 1890 * 60L..1890 * 60L, start = 50f).points.single().value
        assertEquals(wake, r.atWake!!, 0.5f)
        // No number while the strap was off, and a sensible day after it.
        assertTrue(r.points.none { it.t < 3300 * 60L })
        assertTrue("lowest ${r.lowest}", r.lowest!! > 45f)
        // Treated as awake (the old rule) the same night would leave the day far lower.
        val asAwake = minutes.map { if (it.offWrist) it.copy(hr = 66, offWrist = false) else it }
        assertTrue(Recovery.charge(asAwake, p, window, start = 50f).lowest!! < r.lowest - 15f)
        // The day before isn't flagged: its evening just stops when the strap comes off.
        val before = Recovery.charge(minutes, p, 1440 * 60L until 2880 * 60L, start = 50f)
        assertTrue(!before.strapOffOvernight)
        assertTrue(before.points.none { it.t >= 2640 * 60L })
    }

    @Test
    fun shortTimeOffTheWristDriftsDown() {
        // Two hours off the wrist mid-morning, from about 80: Zepp's drift, about 2.9 an hour, not a hold.
        val minutes = run(60) { Minute(it * 60L, 64, 0, 20, null) } +
            run(120) { Minute((60 + it) * 60L, null, 0, null, null, offWrist = true) } +
            run(60) { Minute((180 + it) * 60L, 64, 0, 20, null) }
        val r = Recovery.charge(minutes, params, 0L until 240 * 60L, start = 80f, sampleSeconds = 60, requireAnchor = false)
        val atOn = r.points.first { it.t == 180 * 60L }.value
        val atOff = r.points.first { it.t == 59 * 60L }.value
        assertEquals(120 * Recovery.NO_WEAR_DRIFT_HIGH, atOn - atOff, 0.3f)
        assertTrue(!r.strapOffOvernight)
        // A heart-rate dropout under ten minutes holds instead.
        val dropout = run(30) { Minute(it * 60L, if (it in 10 until 15) null else 64, 0, 40, null) }
        assertEquals(0, Recovery.notWorn(dropout).count { it })
    }

    @Test
    fun aRecordedOffBodyIntervalIsNotWorn() {
        // The strap pushed off-body at minute 10 and on body at minute 15, while connected: those five minutes without
        // heart rate are not worn at once, though the gap rule alone would read them as a dropout.
        val wear = listOf(10 * 60L to false, 15 * 60L to true)
        val minutes = Recovery.minutes(0, 30 * 60L, (0 until 30).filter { it !in 10 until 15 }.map { it * 60L to 64 }, emptyMap(), emptyList(), emptyList(), wear = wear)
        assertEquals((10 until 15).toList(), Recovery.notWorn(minutes).withIndex().filter { it.value }.map { it.index })
        assertEquals(0, Recovery.notWorn(minutes.map { it.copy(worn = null) }).count { it })
        // Unknown again (the link went down): the gap rule is back.
        val minutesAfterLoss = Recovery.minutes(0, 60 * 60L, (0 until 60).filter { it !in 10 until 40 }.map { it * 60L to 64 }, emptyMap(), emptyList(), emptyList(), wear = listOf(0L to true, 5 * 60L to null))
        assertEquals(30, Recovery.notWorn(minutesAfterLoss).count { it })
        // And Charge drifts over the recorded off-body minutes instead of holding.
        val r = Recovery.charge(minutes, params, 0L until 30 * 60L, start = 80f, sampleSeconds = 60, requireAnchor = false)
        val held = Recovery.charge(minutes.map { it.copy(worn = null) }, params, 0L until 30 * 60L, start = 80f, sampleSeconds = 60, requireAnchor = false)
        assertTrue(r.points.first { it.t == 15 * 60L }.value < held.points.first { it.t == 15 * 60L }.value)
    }

    @Test
    fun aRecordedOnBodyStateDoesNotOverrideOffWristOrTheGapRule() {
        // On body may be one stale read at connect, so the strap's off-wrist mark still counts...
        val offWrist = run(30) { Minute(it * 60L, if (it in 10 until 15) null else 64, 0, 40, null, offWrist = it in 10 until 15, worn = true) }
        assertEquals((10 until 15).toList(), Recovery.notWorn(offWrist).withIndex().filter { it.value }.map { it.index })
        // ...and so does a closed gap of 10+ minutes without heart rate.
        val gap = run(60) { Minute(it * 60L, if (it in 10 until 40) null else 64, 0, 40, null, worn = true) }
        assertEquals(30, Recovery.notWorn(gap).count { it })
        // A short dropout recorded on body still holds.
        val dropout = run(30) { Minute(it * 60L, if (it in 10 until 15) null else 64, 0, 40, null, worn = true) }
        assertEquals(0, Recovery.notWorn(dropout).count { it })
    }

    @Test
    fun aLongDaytimeStretchOffIsAGapUntilTheNextSleep() {
        // Seven hours off in the day (no usual sleep there): drift for 384 minutes, then nothing until the next sleep.
        val minutes = run(60) { Minute(it * 60L, 64, 0, 40, null) } +
            run(420) { Minute((60 + it) * 60L, null, 0, null, null, offWrist = true) } +
            run(120) { Minute((480 + it) * 60L, 64, 0, 40, null) } +
            run(60) { Minute((600 + it) * 60L, 54, 0, null, SleepStage.LIGHT) }
        val r = Recovery.charge(minutes, params.copy(usualSleep = listOf(1000 * 60L..1500 * 60L)), 0L until 660 * 60L, start = 80f, sampleSeconds = 60, requireAnchor = false)
        assertTrue(r.points.none { it.t in (60 + Recovery.NO_WEAR_LIMIT_MINUTES) * 60L until 600 * 60L })
        assertTrue(r.points.any { it.t == 600 * 60L && it.state == State.Sleep })
        assertTrue(!r.strapOffOvernight)
    }

    private fun withSignal(signal: BodySignals.State) = params.copy(bodySignals = listOf(-60L to signal))

    @Test
    fun anAlertNightRechargesLessAndTheNextDayDrainsMore() {
        // A 7.5-hour light-sleep night from 30.
        val night = run(450) { Minute(it * 60L, 54, 0, null, SleepStage.LIGHT) }
        val normalNight = Recovery.charge(night, withSignal(BodySignals.State.Normal), 0L..27_000L, start = 30f).latest!!
        val alertNight = Recovery.charge(night, withSignal(BodySignals.State.Alert), 0L..27_000L, start = 30f).latest!!
        assertTrue("normal $normalNight alert $alertNight", alertNight < normalNight - 8f)
        assertTrue(alertNight > 30f)
        // The day after, still under the alert (it lasts until the next sleep): a 10-hour desk day with a 30-minute
        // walk in it, from the same 80, drains about half as much again.
        val day = run(600) { Minute(it * 60L, if (it in 200 until 230) raisedHr else 66, if (it in 200 until 230) 110 else 0, 40, null) }
        val normalDrop = 80f - Recovery.charge(day, withSignal(BodySignals.State.Normal), 0L..36_000L, start = 80f, requireAnchor = false).latest!!
        val alert = Recovery.charge(day, withSignal(BodySignals.State.Alert), 0L..36_000L, start = 80f, requireAnchor = false)
        val alertDrop = 80f - alert.latest!!
        assertTrue("normal $normalDrop alert $alertDrop", alertDrop in normalDrop * 1.3f..normalDrop * 1.5f)
        assertTrue(alert.drainedExercise > 0f)
        // Watch leaves Charge alone.
        assertEquals(normalNight, Recovery.charge(night, withSignal(BodySignals.State.Watch), 0L..27_000L, start = 30f).latest!!, 1e-4f)
    }

    @Test
    fun anAlertLastsUntilTheNextSleep() {
        val night = { from: Int -> run(300) { Minute((from + it) * 60L, 54, 0, null, SleepStage.LIGHT) } }
        val minutes = night(0) + night(300)
        val both = Recovery.charge(minutes, params.copy(bodySignals = listOf(0L to BodySignals.State.Alert)), 0L..36_000L, start = 30f, requireAnchor = false)
        val first = Recovery.charge(
            minutes, params.copy(bodySignals = listOf(0L to BodySignals.State.Alert, 300 * 60L to BodySignals.State.Normal)), 0L..36_000L, start = 30f, requireAnchor = false,
        )
        assertTrue(first.latest!! > both.latest!! + 2f)
    }

    @Test
    fun anHourOfModerateExerciseDrainsAboutAQuarter() {
        val hr = (58 + 0.6f * (188 - 58)).toInt() // 60% of reserve
        val r = Recovery.charge(run(60) { Minute(it * 60L, hr, 150, 70, null) }, params, 0L..3600L, start = 80f, requireAnchor = false)
        val drop = 80f - r.latest!!
        assertTrue("drop $drop", drop in 20f..32f)
        assertTrue(r.drainedExercise > r.drainedLiving)
        assertEquals(0f, r.drainedStress, 0.01f)
    }

    @Test
    fun elevatedHeartRateAfterExerciseIsNotStress() {
        val workout = run(40) { Minute(it * 60L, 150, 160, 80, null) }
        // Cool-down: HR easing down, strap stress still high from the workout.
        val after = run(20) { Minute((40 + it) * 60L, 95 - it, 0, 75, null) }
        val r = Recovery.charge(workout + after, params, 0L..3600L, start = 90f, requireAnchor = false)
        assertTrue(r.points.filter { it.t >= 40 * 60L }.none { it.state == State.Stress })
        assertEquals(0f, r.drainedStress, 0.01f)
    }

    @Test
    fun sustainedStressDrainsFasterThanCalm() {
        val calm = Recovery.charge(run(240) { Minute(it * 60L, 66, 0, 20, null) }, params, 0L..14_400L, start = 70f, requireAnchor = false).latest!!
        val tense = Recovery.charge(run(240) { Minute(it * 60L, 74, 0, 80, null) }, params, 0L..14_400L, start = 70f, requireAnchor = false)
        assertTrue(calm > tense.latest!! + 5)
        assertTrue(tense.drainedStress > 0)
    }

    @Test
    fun stressNeedsThreeMinutesInARow() {
        // Alternating 80 and 40 never builds a run: no stress drain at all.
        val blips = Recovery.charge(run(120) { Minute(it * 60L, 70, 0, if (it % 2 == 0) 80 else 40, null) }, params, 0L..7_200L, start = 70f, requireAnchor = false)
        assertEquals(0f, blips.drainedStress, 1e-4f)
        assertTrue(blips.points.none { it.state == State.Stress })
        // A sustained stretch drains from its third minute.
        val run3 = Recovery.charge(run(3) { Minute(it * 60L, 70, 0, 80, null) }, params, 0L..180L, start = 70f, sampleSeconds = 60, requireAnchor = false)
        assertEquals(listOf(State.Rest, State.Rest, State.Stress), run3.points.map { it.state })
    }

    @Test
    fun stressEitherSideOfANotWornMinuteDoesNotAddUp() {
        // Two stressed minutes, one off the wrist, two more: no run of three, so no Stress.
        val minutes = run(5) { if (it == 2) Minute(it * 60L, null, 0, null, null, offWrist = true) else Minute(it * 60L, 70, 0, 80, null) }
        val r = Recovery.charge(minutes, params, 0L..300L, start = 70f, sampleSeconds = 60, requireAnchor = false)
        assertTrue(r.points.none { it.state == State.Stress })
        assertEquals(0f, r.drainedStress, 1e-4f)
    }

    @Test
    fun anHourOfStressDrainsAtMostAThirdOfAnHourOfExercise() {
        // Calibration target: an hour at stress 80 with T = 60 stays at or below a third of an hour at 60 % of reserve.
        val stress = Recovery.charge(run(60) { Minute(it * 60L, 70, 0, 80, null) }, params, 0L..3_600L, start = 80f, requireAnchor = false)
        val hr = (58 + 0.6f * (188 - 58)).toInt()
        val exercise = Recovery.charge(run(60) { Minute(it * 60L, hr, 150, 70, null) }, params, 0L..3_600L, start = 80f, requireAnchor = false)
        val stressDrop = 80f - stress.latest!!
        val exerciseDrop = 80f - exercise.latest!!
        assertTrue("stress $stressDrop", stressDrop <= 32f / 3)
        assertTrue("stress part ${stress.drainedStress} vs exercise $exerciseDrop", stress.drainedStress <= exerciseDrop / 3)
    }

    private val dayOf = { t: Long -> t / 86_400 }

    @Test
    fun stressThresholdIsTenAboveCalmLevel() {
        // Four days of calm readings around 54 (seen on hardware while seated): stress counts from 64.
        val calm = (0 until 4).flatMap { d -> (0 until 30).map { i -> d * 86_400L + i * 300 to (50 + i % 9) } }
        val t = Recovery.stressThreshold(calm, dayOf)
        assertEquals(54, t.calmLevel)
        assertEquals(64, t.value)
        assertEquals(4, t.days)
    }

    @Test
    fun stressThresholdStaysInZeppsBandsAndNeedsThreeDays() {
        val low = (0 until 3).flatMap { d -> (0 until 30).map { i -> d * 86_400L + i * 300 to 20 } }
        assertEquals(Recovery.STRESS_FROM_MIN, Recovery.stressThreshold(low, dayOf).value)
        val high = low.map { it.first to 75 }
        assertEquals(Recovery.STRESS_FROM_MAX, Recovery.stressThreshold(high, dayOf).value)
        val twoDays = low.filter { it.first < 2 * 86_400L }.map { it.first to 54 }
        val learning = Recovery.stressThreshold(twoDays, dayOf)
        assertEquals(Recovery.STRESS_FROM, learning.value)
        assertNull(learning.calmLevel)
        assertEquals(2, learning.days)
    }

    @Test
    fun chargeUsesThePersonalThreshold() {
        val day62 = run(240) { Minute(it * 60L, 70, 0, 62, null) }
        val fixed = Recovery.charge(day62, params, 0L..14_400L, start = 70f, requireAnchor = false)
        val personal = Recovery.charge(day62, params.copy(stressFrom = 64), 0L..14_400L, start = 70f, requireAnchor = false)
        assertTrue(fixed.points.any { it.state == State.Stress })
        assertTrue(personal.points.none { it.state == State.Stress })
    }

    @Test
    fun zeppNormalStressIsNotStress() {
        // Readings of 40-59 are Zepp's "normal" band: a seated day there drains like rest.
        val r = Recovery.charge(run(240) { Minute(it * 60L, 70, 0, 55, null) }, params, 0L..14_400L, start = 70f, requireAnchor = false)
        assertTrue(r.points.none { it.state == State.Stress })
        assertEquals(0f, r.drainedStress, 0.01f)
    }

    @Test
    fun chargeStaysInRange() {
        val r = Recovery.charge(run(2000) { Minute(it * 60L, 175, 170, 90, null) }, params, 0L..120_000L, start = 50f, requireAnchor = false)
        assertTrue(r.points.all { it.value in 0f..100f })
        assertEquals(0f, r.lowest!!, 0.01f)
    }

    @Test
    fun minutesFillsStagesAndHoldsStress() {
        val m = Recovery.minutes(
            from = 0, to = 600,
            hr = listOf(60L to 70), steps = mapOf(120L to 30),
            stress = listOf(0L to 50),
            stages = listOf(Triple(240L, 360L, SleepStage.DEEP)),
            stressHoldSeconds = 180,
        )
        assertEquals(10, m.size)
        assertEquals(70, m[1].hr)
        assertEquals(30, m[2].steps)
        assertEquals(50, m[3].stress)
        assertNull(m[4].stress)
        assertEquals(SleepStage.DEEP, m[4].sleepStage)
        assertEquals(SleepStage.DEEP, m[5].sleepStage)
        assertNull(m[6].sleepStage)
    }

    @Test
    fun lowestAverageFindsTheNadir() {
        val series = (0 until 480).map { it * 60L to (if (it in 100..140) 48 else 60) }
        val (t, v) = Recovery.lowestAverage(series, 30)!!
        assertEquals(48f, v, 0.01f)
        assertTrue(t in 100 * 60L..140 * 60L)
    }

    @Test
    fun maxHeartRateUsesAgeThenStrapThenObserved() {
        assertEquals(187f, Recovery.maxHeartRate(30), 0.01f) // Tanaka: 208 - 0.7 * 30
        assertEquals(192f, Recovery.maxHeartRate(null, strapMax = 192), 0.01f)
        assertEquals(190f, Recovery.maxHeartRate(null), 0.01f)
        assertEquals(195f, Recovery.maxHeartRate(30, observedPeak = 195), 0.01f)
        // A single artefact can't move it more than 15 bpm.
        assertEquals(202f, Recovery.maxHeartRate(30, observedPeak = 240), 0.01f)
    }

    @Test
    fun strapDailyMaxNeedsTheMinutesToBackItUp() {
        val minuteMax = mapOf(1 to 170, 2 to 168, 3 to null, 4 to 150)
        val strap = listOf(1 to 176, 2 to 199, 3 to 205, 4 to 240, 5 to 190)
        // Day 1 within 10 bpm is taken; day 2 a spike, day 3 no minutes, day 4 out of range, day 5 no minutes: dropped.
        assertEquals(listOf(176, 168, 150), Recovery.dailyMaxima(minuteMax, strap))
        // So two lone strap spikes no longer agree their way into the observed peak.
        assertEquals(166, Recovery.observedPeak(Recovery.dailyMaxima(mapOf(1 to 166, 2 to 168), listOf(1 to 199, 2 to 199))))
    }

    @Test
    fun pastTrendDayGetsItsOwnLoad() {
        val loadDays = (1..30).map { it * day to if (it == 20) 200f else 40f }
        // The morning of day 21 saw days 1-20, ending with the hard day.
        val before = RecoveryEngine.loadsBefore(loadDays, 21 * day)
        assertEquals(20, before.size)
        assertEquals(200f, before.last(), 0f)
        val past = Recovery.readiness(night(21), history().filter { it.day < 21 * day }, before)
        assertTrue(past.contributors.first { it.factor == Factor.PreviousDayLoad }.score < 100)
        assertEquals(loadDays.map { it.second }, RecoveryEngine.loadsBefore(loadDays, 31 * day))
    }

    @Test
    fun femaleTrimpCurveUsesItsOwnConstants() {
        // 0.86·e^(1.67x) sits above 0.64·e^(1.92x) everywhere below 118% of reserve.
        assertEquals(0.4f * 0.86f * kotlin.math.exp(1.67f * 0.4f), Recovery.trimpMinute(0.4f, female = true), 1e-4f)
        assertTrue(Recovery.trimpMinute(0.4f, female = true) > Recovery.trimpMinute(0.4f))
        assertTrue(Recovery.trimpMinute(0.95f, female = true) > Recovery.trimpMinute(0.95f))
    }

    @Test
    fun noSleepMeansNoCharge() {
        // A new user's first evening: plenty of heart rate, no night yet. A number here would be a guess.
        val r = Recovery.charge(run(300) { Minute(it * 60L, 70, 0, 40, null) }, params, 0L..18_000L)
        assertFalse(r.anchored)
        assertNull(r.latest)
        assertTrue(r.points.isEmpty())
    }

    @Test
    fun chargeHoldsWhileTheStrapIsOff() {
        val night = run(60) { Minute(it * 60L, 52, 0, null, SleepStage.DEEP) }
        val off = run(240) { Minute((60 + it) * 60L, null, 0, null, null) }
        val r = Recovery.charge(night + off, params, 0L..18_000L, start = 60f, sampleSeconds = 60)
        val atWake = r.points.last { it.t < 60 * 60L }.value
        assertTrue(r.anchored)
        assertEquals(atWake, r.points.last().value, 0.5f)
    }

    @Test
    fun overnightOxygenDropLowersReadinessAndAlerts() {
        val past = history().map { it.copy(spo2 = 96f) }
        val normal = Recovery.readiness(night(31).copy(spo2 = 95.8f), past)
        assertEquals(100, normal.contributors.first { it.factor == Factor.BloodOxygen }.score)

        val low = Recovery.readiness(night(31).copy(spo2 = 93f), past)
        val c = low.contributors.first { it.factor == Factor.BloodOxygen }
        assertEquals(50, c.score) // 3 % below usual: 2 % past the dead band, 25 points each
        // SpO2 has no early-warning evidence: it lowers the score but never raises a body-signal alert.
        assertTrue(low.alertSignals.isEmpty())
        assertFalse(low.strainAlert)
        assertTrue(low.score!! < normal.score!!)
    }

    @Test
    fun higherOxygenThanUsualIsNotABonus() {
        val past = history().map { it.copy(spo2 = 95f) }
        val r = Recovery.readiness(night(31).copy(spo2 = 98f), past)
        assertEquals(100, r.contributors.first { it.factor == Factor.BloodOxygen }.score)
        assertFalse(r.alertSignals.any { "blood oxygen" in it })
    }

    @Test
    fun oxygenNeedsABaseline() {
        val r = Recovery.readiness(night(31).copy(spo2 = 90f), history())
        assertNull(r.contributors.firstOrNull { it.factor == Factor.BloodOxygen })
    }

    @Test
    fun breathingDisturbancesAreTheirOwnCardAgainstYourHistory() {
        val past = history().map { it.copy(odi = 4f) }
        val b = Recovery.breathingDisturbances(night(31).copy(odi = 40f), past)!!
        assertEquals(40f, b.perHour, 1e-5f)
        assertEquals(4f, b.usual!!, 1e-5f)
        assertNull(Recovery.breathingDisturbances(night(31).copy(odi = 40f), past.take(6))!!.usual)
        assertNull(Recovery.breathingDisturbances(night(31), past))
    }

    @Test
    fun bodySignalsPushFollowsTheWellnessCopy() {
        assertEquals(
            "Several of your overnight signals (resting heart rate, skin temperature) are outside your usual range for the second night. " +
                "This can happen with heavy training, alcohol, travel, poor sleep, or when your body is fighting something off. " +
                "An easier day may help. If you feel unwell, checking in with a healthcare professional may be helpful. " +
                "This is a wellness estimate, not a medical assessment.",
            BodySignalsCopy.pushText(listOf("resting heart rate", "skin temperature"), nights = 2),
        )
        val banned = listOf("illness", "infection", "fever", "covid", "abnormal", "diagnos")
        val texts = listOf(BodySignalsCopy.TITLE, BodySignalsCopy.SHORT, BodySignalsCopy.pushText(listOf("skin temperature"), 1))
        texts.forEach { t -> banned.forEach { assertFalse("$it in $t", it in t.lowercase()) } }
    }

    @Test
    fun breathingDisturbancesDontMoveReadiness() {
        // A stable trait, so inside Readiness it would only act as a fixed offset: it's left out.
        val with = Recovery.readiness(night(31).copy(odi = 40f), history())
        val without = Recovery.readiness(night(31), history())
        assertEquals(without.score, with.score)
        assertFalse(with.capped)
    }
}
