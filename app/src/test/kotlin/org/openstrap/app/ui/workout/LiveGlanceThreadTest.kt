package org.openstrap.app.ui.workout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.MaxHeartRates
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.StrapController
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.testing.FixedClock
import org.openstrap.app.testing.inMemoryDb
import org.openstrap.app.testing.profile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections
import org.openstrap.app.data.HeartZones

/** Each second's glance, which walks the whole route, is worked out off the main thread (audit C7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveGlanceThreadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FixedClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: OpenStrapDatabase

    @Before fun setUp() {
        db = inMemoryDb()
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** A strap that is never connected and does nothing: each flow stays empty, each command returns at once. */
    private fun idleStrap(): StrapController {
        val flows = mutableMapOf<String, Any>()
        return Proxy.newProxyInstance(StrapController::class.java.classLoader, arrayOf(StrapController::class.java)) { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "idle strap"
                method.returnType == java.lang.Boolean.TYPE -> false
                method.name == "getConnection" -> flows.getOrPut(method.name) { MutableStateFlow<ConnectionState>(ConnectionState.Disconnected) }
                StateFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableStateFlow(emptyValue(method.genericReturnType)) }
                SharedFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableSharedFlow<Any?>() }
                else -> Unit
            }
        } as StrapController
    }

    /** What a flow of [type] starts at: false, an empty list or null. */
    private fun emptyValue(type: java.lang.reflect.Type): Any? =
        when ((type as? java.lang.reflect.ParameterizedType)?.actualTypeArguments?.firstOrNull()?.typeName?.substringBefore('<')) {
            "java.lang.Boolean" -> false
            "java.util.List" -> emptyList<Any>()
            else -> null
        }

    @Test fun `the glance is worked out off the main thread`() {
        val dao = db.healthDao()
        val session = WorkoutSession(context, dao, SettingsStore(context), idleStrap(), MaxHeartRates(dao, flowOf(profile())), clock = { clock.now }, scope = scope)
        session.start(sportType = 1)
        runBlocking { withTimeout(WAIT_MS) { session.state.first { it is WorkoutState.Recording } } }
        val threads = Collections.synchronizedList(mutableListOf<String>())
        // The live heart rate is read inside the glance's flow, so where it's read is where the glance is worked out.
        val bpm = flow<Int?> {
            threads += Thread.currentThread().name
            emit(120)
        }
        runBlocking { withTimeout(WAIT_MS) { liveGlance(session, bpm, flowOf(ConnectionState.Disconnected), flowOf(HeartZones.STANDARD)).first { it != null } } }
        assertTrue("read on $threads", threads.isNotEmpty() && threads.all { it.startsWith("DefaultDispatcher") })
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
