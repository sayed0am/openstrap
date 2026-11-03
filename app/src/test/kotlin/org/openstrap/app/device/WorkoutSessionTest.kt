package org.openstrap.app.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.MaxHeartRates
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.openstrap.protocol.services.Alarm
import org.openstrap.protocol.services.AlarmCapabilities
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsGroup
import org.openstrap.protocol.services.SportSession
import org.openstrap.protocol.services.StepTotals
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Collections

/**
 * The workout lifecycle on a clock the test moves (audit test 4), and the C4 race: a state write made while a locked
 * save is suspended survives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FixedClock()
    private val strap = FakeStrap()
    private lateinit var db: OpenStrapDatabase
    private lateinit var dao: SwitchableDao

    /** What would have crashed the app: an exception no coroutine caught. */
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var savedHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = SwitchableDao(db.healthDao())
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
        scope.cancel()
        db.close()
        assertTrue("uncaught: $uncaught", uncaught.isEmpty())
    }

    /** The app's scope as `AppContainer.appScope` builds it, but what it catches lands in [uncaught] for the test. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })

    private fun session() =
        WorkoutSession(context, dao, SettingsStore(context), strap, MaxHeartRates(dao, flowOf(profile())), clock = { clock.now }, scope = scope)

    private fun <T> StateFlow<T>.await(what: String = "state", predicate: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(WAIT_MS) { first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$what never came, last $value", e)
        }
    }

    private fun WorkoutSession.recording(predicate: (WorkoutState.Recording) -> Boolean = { true }) =
        state.await("recording") { it is WorkoutState.Recording && predicate(it) } as WorkoutState.Recording

    private fun stored(start: Long) = runBlocking { db.healthDao().appWorkoutOnce(start) }

    /** The stored workout once [predicate] holds: an edit to a saved one runs on the session's scope. */
    private fun storedOnce(start: Long, predicate: (AppWorkout?) -> Boolean): AppWorkout? = runBlocking {
        withTimeout(WAIT_MS) {
            var w = stored(start)
            while (!predicate(w)) {
                delay(POLL_MS)
                w = stored(start)
            }
            w
        }
    }

    @Test
    fun startPauseResumeAndEndSavesTheActiveTime() {
        val s = session()
        val t0 = clock.now
        s.start(sportType = 1)
        assertEquals(t0, s.recording().workout.start)
        assertNull(stored(t0)?.end)

        clock.advance(Duration.ofSeconds(120))
        s.pause()
        assertEquals(t0 + 120, s.recording { it.paused }.workout.pausedAt)
        clock.advance(Duration.ofSeconds(60))
        s.resume()
        val resumed = s.recording { !it.paused }.workout
        assertEquals(listOf(t0 + 120..t0 + 180), resumed.pauseRanges)
        assertEquals(120, WorkoutRecorder.activeSeconds(resumed, clock.now))

        clock.advance(Duration.ofSeconds(60))
        s.end()
        assertEquals(WorkoutState.Ended(t0), s.state.await { it is WorkoutState.Ended })
        val saved = stored(t0)!!
        assertEquals(t0 + 240, saved.end)
        assertEquals(180, WorkoutRecorder.activeSeconds(saved, saved.end!!))
    }

    @Test
    fun endedWhilePausedEndsWhereThePauseBegan() {
        val s = session()
        val t0 = clock.now
        s.start(sportType = 1)
        s.recording()
        clock.advance(Duration.ofSeconds(90))
        s.pause()
        s.recording { it.paused }
        clock.advance(Duration.ofSeconds(600))
        s.end()
        s.state.await { it is WorkoutState.Ended }
        assertEquals(t0 + 90, stored(t0)!!.end)
    }

    @Test
    fun underAMinuteIsNotKept() {
        val s = session()
        val t0 = clock.now
        s.start(sportType = 1)
        s.recording()
        clock.advance(Duration.ofSeconds(30))
        s.end()
        s.state.await { it is WorkoutState.Idle }
        assertNull(stored(t0))
        // Its time is remembered, so a strap session the strap kept over it isn't synced in as a workout.
        assertEquals(listOf(t0 to t0 + 30), runBlocking { db.healthDao().dismissedWorkoutsAll() }.map { it.start to it.end })
    }

    @Test
    fun discardDeletesTheWorkoutAndClosesItsSummary() {
        val s = session()
        val t0 = clock.now
        s.start(sportType = 1)
        s.recording()
        clock.advance(Duration.ofSeconds(300))
        s.end()
        s.state.await { it is WorkoutState.Ended }
        s.discard(t0)
        s.state.await { it is WorkoutState.Idle }
        assertNull(stored(t0))
        // Discarded before the strap's copy synced: its time is remembered, so the copy is left out when it arrives.
        assertEquals(listOf(t0 to t0 + 300), runBlocking { db.healthDao().dismissedWorkoutsAll() }.map { it.start to it.end })
    }

    @Test
    fun discardAfterAStartTrimTakesTheStrapCopysEffect() {
        val s = session()
        val t0 = clock.now
        s.start(sportType = 1)
        s.recording()
        clock.advance(Duration.ofSeconds(300))
        s.end()
        s.state.await { it is WorkoutState.Ended }
        runBlocking {
            db.healthDao().insertWorkouts(listOf(Workout(t0 + 3, t0 + 304, 1, 300, null, null, null, null, null, null, null, null, null, 6)))
            db.healthDao().insertSportEffects(listOf(SportEffectRow(t0 + 306, 45, null, null, null, null, null, null, null)))
        }
        // The shown start now lies inside the strap's copy.
        s.trim(t0, t0 + 60, t0 + 240)
        storedOnce(t0) { it?.shownStart == t0 + 60 }
        s.discard(t0)
        s.state.await { it is WorkoutState.Idle }
        assertNull(stored(t0))
        runBlocking {
            assertNull(db.healthDao().workoutOnce(t0 + 3))
            // Left, its 45 recovery minutes would carry into the next workout.
            assertEquals(emptyList<SportEffectRow>(), db.healthDao().sportEffectsOnce(t0, t0 + 3600))
            assertEquals(listOf(t0 + 306), db.healthDao().deletedWorkoutsAll().map { it.effectEnd })
        }
    }

    @Test
    fun aWorkoutLeftRecordingComesBackAfterProcessDeath() {
        val t0 = clock.now - 600
        runBlocking {
            db.healthDao().upsertAppWorkout(AppWorkout(start = t0, end = null, sportType = 1))
            db.healthDao().insertAppWorkoutHeartRate((1..9).map { AppWorkoutHeartRate(t0, t0 + it, 100 + it) })
        }
        val s = session()
        s.restore()
        s.restored.await { it }
        val r = s.recording()
        assertEquals(t0, r.workout.start)
        assertEquals(WorkoutRecorder.Stats(105, 109, 101), s.stats.value)
        assertEquals(600, WorkoutRecorder.activeSeconds(r.workout, clock.now))

        s.end()
        s.state.await { it is WorkoutState.Ended }
        assertEquals(clock.now, stored(t0)!!.end)
    }

    @Test
    fun aPauseBetweenTwoReadingsIsNotTimeInZone() {
        val s = session()
        s.start(sportType = 1)
        s.recording()
        reading(s, 150)
        clock.advance(Duration.ofSeconds(1))
        reading(s, 150)
        assertEquals(1, s.zoneSeconds.value.sum())

        s.pause()
        s.recording { it.paused }
        clock.advance(Duration.ofSeconds(100))
        s.resume()
        s.recording { !it.paused }
        clock.advance(Duration.ofSeconds(1))
        reading(s, 150)
        assertEquals(1, s.zoneSeconds.value.sum())
        clock.advance(Duration.ofSeconds(1))
        reading(s, 150)
        assertEquals(2, s.zoneSeconds.value.sum())
    }

    @Test
    fun aTrimShowsLessAndUndoPutsTheRecordingBack() {
        val s = session()
        val t0 = clock.now
        s.start(sportType = 1)
        s.recording()
        clock.advance(Duration.ofSeconds(600))
        s.end()
        s.state.await { it is WorkoutState.Ended }

        s.trim(t0, from = t0 + 60, to = t0 + 300)
        val trimmed = storedOnce(t0) { it?.end == t0 + 300 }!!
        assertEquals(t0 + 60, trimmed.shownStart)
        assertEquals(t0 + 600, trimmed.recordedEnd)

        s.undoTrim(t0)
        val undone = storedOnce(t0) { it?.end == t0 + 600 }!!
        assertNull(undone.shownStart)
        assertNull(undone.recordedEnd)
    }

    /** A live reading at the clock's second, once the session has taken it. */
    private fun reading(s: WorkoutSession, bpm: Int) {
        val t = clock.now
        strap.readings.tryEmit(bpm)
        s.recent.await("reading at $t") { it.lastOrNull()?.first == t }
    }

    @Test
    fun anEndPromptSetDuringASuspendedSaveSurvivesIt() {
        val s = session()
        // Auto-start's seed: a minute of effort, then nine calm minutes, so the wearer looks stopped.
        val t0 = clock.now
        val seed = (1L..60L).map { t0 + it to 170 } + (61L..600L).map { t0 + it to 70 }
        clock.advance(Duration.ofSeconds(600))
        s.start(sportType = 1, from = t0, seed = seed)
        s.recording()

        val gate = dao.hold()
        s.pause()
        runBlocking { withTimeout(WAIT_MS) { gate.entered.await() } }
        // The pause's save is under way, and meanwhile the 30 s check finds the stop.
        s.checkStopped()
        assertEquals(t0 + 60, (s.state.value as WorkoutState.Recording).endPrompt)
        gate.release.complete(Unit)

        val paused = s.recording { it.paused }
        assertEquals(t0 + 60, paused.endPrompt)
    }

    @Test
    fun aSaveThatFailsMidWorkoutKeepsItRecording() {
        val s = session()
        s.start(sportType = 1)
        s.recording()
        dao.failing = true
        clock.advance(Duration.ofSeconds(120))
        s.pause()
        // Paused in memory, and the notification can say it isn't saved.
        s.recording { it.paused && it.saveFailed }
        clock.advance(Duration.ofSeconds(10))
        s.resume()
        s.recording { !it.paused }
        clock.advance(Duration.ofSeconds(1))
        reading(s, 120)

        // The next save that works writes the whole workout, pause and all.
        dao.failing = false
        clock.advance(Duration.ofSeconds(60))
        s.pause()
        s.recording { it.paused && !it.saveFailed }
        val saved = stored(s.recording().workout.start)!!
        assertEquals(1, saved.pauseRanges.size)
        assertEquals(clock.now, saved.pausedAt)
    }

    @Test
    fun aWorkoutThatCantBeSavedAtTheEndKeepsRecording() {
        val s = session()
        val t0 = clock.now
        s.start(sportType = 1)
        s.recording()
        clock.advance(Duration.ofSeconds(300))
        dao.failing = true
        // Held, so the pause below queues behind the end and shows once the end has given up.
        val gate = dao.hold()
        s.end()
        runBlocking { withTimeout(WAIT_MS) { gate.entered.await() } }
        s.pause()
        gate.release.complete(Unit)
        // The end's save failed: still recording, so End can be tried again, and it shows.
        s.recording { it.paused && it.saveFailed }
        assertNull(stored(t0)?.end)

        dao.failing = false
        s.end()
        s.state.await { it is WorkoutState.Ended }
        assertNotNull(stored(t0)?.end)
    }

    @Test
    fun theSeedIsRecordedBeforeTheLiveReadings() {
        val t0 = clock.now
        clock.advance(Duration.ofSeconds(120))
        // Auto-start's readings up to now, and the strap already holding a live one for this same second.
        val seed = (1L..120L).map { t0 + it to 100 }
        strap.readings.tryEmit(190)
        val s = session()
        s.start(sportType = 1, from = t0, seed = seed)
        s.recording()
        s.recent.await { it.size == seed.size }
        // One a second: the live reading at the seed's last second is the same second, not a second reading.
        Thread.sleep(SETTLE_MS)
        assertEquals(seed, s.recent.value)
        assertEquals(WorkoutRecorder.Stats(100, 100, 100), s.stats.value)
    }

    /** [HealthDao] that can fail every workout save, or hold the next one until released. */
    private class SwitchableDao(private val real: HealthDao) : HealthDao by real {
        @Volatile var failing = false
        @Volatile private var gate: Gate? = null

        class Gate {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        fun hold() = Gate().also { gate = it }

        override suspend fun upsertAppWorkout(workout: AppWorkout) {
            gate?.let {
                gate = null
                it.entered.complete(Unit)
                it.release.await()
            }
            if (failing) throw android.database.sqlite.SQLiteException("disk I/O error")
            real.upsertAppWorkout(workout)
        }

        override suspend fun upsertSets(sets: List<org.openstrap.app.data.db.AppWorkoutSet>) {
            if (failing) throw android.database.sqlite.SQLiteException("disk I/O error")
            real.upsertSets(sets)
        }

        override suspend fun insertAppWorkoutHeartRate(samples: List<AppWorkoutHeartRate>) {
            if (failing) throw android.database.sqlite.SQLiteException("disk I/O error")
            real.insertAppWorkoutHeartRate(samples)
        }
    }

    /** A strap that is never connected, whose live heart rate is [readings]. */
    private class FakeStrap : StrapController {
        val readings = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 64)
        override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override val sync: StateFlow<SyncState> = MutableStateFlow(SyncState.Idle)
        override val syncRecord: StateFlow<SyncRecord> = MutableStateFlow(SyncRecord())
        override val syncEnded: SharedFlow<Unit> = MutableSharedFlow()
        override val liveHeartRate: StateFlow<Int?> = MutableStateFlow(null)
        override val heartRateReadings: SharedFlow<Int> = readings
        override val liveHeartRateEnabled: StateFlow<Boolean> = MutableStateFlow(false)
        override val todaySteps: StateFlow<StepTotals?> = MutableStateFlow(null)
        override val info: StateFlow<StrapInfo?> = MutableStateFlow(null)
        override val firmwareUpdating: StateFlow<Boolean> = MutableStateFlow(false)
        override val log: StateFlow<List<String>> = MutableStateFlow(emptyList())
        override val alarmsChanged: SharedFlow<Unit> = MutableSharedFlow()
        override val settingsChanged: SharedFlow<SettingsGroup> = MutableSharedFlow()
        override val sportSessionPushes: SharedFlow<SportSession.Push> = MutableSharedFlow()
        override val findingStrap: StateFlow<Boolean> = MutableStateFlow(false)
        override fun connect() = Unit
        override fun autoConnect() = Unit
        override fun backgroundConnect() = Unit
        override fun disconnect() = Unit
        override fun syncNow() = Unit
        override fun setLiveHeartRate(enabled: Boolean) = Unit
        override fun setAppVisible(visible: Boolean) = Unit
        override fun setWorkoutActive(active: Boolean) = Unit
        override fun setEffortWatch(active: Boolean) = Unit
        override suspend fun installFirmware(file: ByteArray, type: Int, onProgress: (org.openstrap.protocol.firmware.DfuProgress) -> Unit) = Unit
        override suspend fun readAlarms(): Pair<AlarmCapabilities, List<Alarm>> = throw StrapCommandException("not connected")
        override suspend fun saveAlarm(alarm: Alarm, isNew: Boolean) = Unit
        override suspend fun deleteAlarm(index: Int) = Unit
        override suspend fun clearAlarms() = Unit
        override suspend fun readHealthSettings(): SettingsGroup = throw StrapCommandException("not connected")
        override suspend fun writeHealthSettings(version: Int, settings: List<Setting>) = Unit
        override suspend fun restartStrap() = Unit
        override suspend fun buzz(pattern: List<Int>) = Unit
        override suspend fun sportSession(state: SportSession.State, sportType: Int, startTime: Long, elapsedSeconds: Long) = Unit
        override suspend fun sportSessionKeepAlive() = Unit
        override suspend fun startCallBuzz(pulsed: Boolean) = Unit
        override suspend fun stopCallBuzz() = Unit
        override suspend fun findStrap() = Unit
        override suspend fun stopFindingStrap() = Unit
    }

    private companion object {
        const val WAIT_MS = 10_000L
        /** Long enough for a reading the session wrongly took to show. */
        const val SETTLE_MS = 300L
        const val POLL_MS = 20L
    }
}
