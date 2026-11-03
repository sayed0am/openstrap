package org.openstrap.app.ui.root

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.HealthRepository
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.MaxHeartRates
import org.openstrap.app.data.RewardsKeeper
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.health.HealthConnectExporter
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.openstrap.app.update.AppUpdater
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.LocalDate

/** The app root's reads and writes, out of the composition (audit A1). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppRootViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private lateinit var db: OpenStrapDatabase
    private lateinit var settings: SettingsStore
    private lateinit var vm: AppRootViewModel

    /** A strap whose connection the test moves. Every other flow stays empty and every command returns at once. */
    private fun strap(): StrapController {
        val flows = mutableMapOf<String, Any>()
        return Proxy.newProxyInstance(StrapController::class.java.classLoader, arrayOf(StrapController::class.java)) { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "test strap"
                method.name == "getConnection" -> connection
                method.name == "getSync" -> flows.getOrPut(method.name) { MutableStateFlow(org.openstrap.app.device.SyncState.Idle) }
                method.name == "getSyncRecord" -> flows.getOrPut(method.name) { MutableStateFlow(org.openstrap.app.device.SyncRecord()) }
                method.returnType == java.lang.Boolean.TYPE -> false
                StateFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableStateFlow(emptyValue(method.genericReturnType)) }
                SharedFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableSharedFlow<Any?>() }
                else -> Unit
            }
        } as StrapController
    }

    private fun emptyValue(type: java.lang.reflect.Type): Any? =
        when ((type as? java.lang.reflect.ParameterizedType)?.actualTypeArguments?.firstOrNull()?.typeName?.substringBefore('<')) {
            "java.lang.Boolean" -> false
            "java.util.List" -> emptyList<Any>()
            else -> null
        }

    @Before fun setUp() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        db = inMemoryDb()
        settings = SettingsStore(app)
        val dao = db.healthDao()
        val strap = strap()
        val maxHeartRates = MaxHeartRates(dao, flowOf(profile()))
        val clock = FixedClock()
        vm = AppRootViewModel(
            settings, HealthRepository(dao), dao, strap,
            WorkoutSession(app, dao, settings, strap, maxHeartRates, clock = { clock.now }, scope = scope),
            RewardsKeeper(dao, settings, maxHeartRates), HealthConnectExporter(app, dao),
            AppUpdater(app, settings, recording = { false }, scope = scope),
            MutableStateFlow(emptyList<WorkoutAnalysis>()),
            // Its work on the test's own dispatcher: nothing left on another thread finishes into the next test.
            work = main,
        )
    }

    @After fun tearDown() {
        runBlocking {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
        db.close()
        Dispatchers.resetMain()
    }

    private fun <T> await(flow: kotlinx.coroutines.flow.Flow<T>, what: (T) -> Boolean): T =
        runBlocking { withTimeout(WAIT_MS) { flow.first(what) } }

    @Test fun `the first score's day waits for the store, then keeps the day marked`() {
        assertEquals(FirstScoreDay.Loading, vm.firstScoreDay.value)
        val day = LocalDate.of(2026, 10, 20)
        vm.markFirstScore(day)
        assertEquals(FirstScoreDay.Day(day), await(vm.firstScoreDay) { it is FirstScoreDay.Day })
    }

    @Test fun `the day's check-in follows the day shown`() {
        val day = LocalDate.of(2026, 10, 20)
        vm.saveCheckIn(day, 4)
        vm.dayShown(day)
        assertEquals(4, await(vm.dayExtras) { it.checkIn != null }.checkIn)
        val next = day.plusDays(1)
        vm.dayShown(next)
        assertEquals(DayExtras(), await(vm.dayExtras) { it.checkIn == null })
    }

    @Test fun `the strap facts follow the connection`() {
        val connected = ConnectionState.Connected(80, "3.25", false)
        connection.value = connected
        assertEquals(connected, await(vm.strap) { it.connection == connected }.connection)
    }

    @Test fun `saved goals reach the store`() {
        val goals = Goals(steps = 12_345)
        vm.saveGoals(goals)
        assertEquals(12_345, await(settings.goals) { it.steps == 12_345 }.steps)
        assertEquals(12_345, await(vm.goals) { it.steps == 12_345 }.steps)
    }

    @Test fun `moved heart zones reach the screens from the store`() {
        val zones = HeartZones(listOf(50f, 61f, 71f, 81f, 91f))
        vm.setHeartZones(zones)
        assertEquals(zones, await(settings.heartZones) { it == zones })
        assertEquals(zones, await(vm.heartZones) { it == zones })
    }

    @Test fun `no history badges while none wait`() {
        runBlocking { settings.historyBadgesShown() }
        assertEquals(null, await(vm.historyBadges) { it == null })
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
