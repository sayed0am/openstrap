package org.openstrap.app.ui.alarms

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.device.StrapCommandException
import org.openstrap.app.device.StrapController
import org.openstrap.app.testing.endMain
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** A failed read from the strap says plain words on the Alarms page, never an exception's own text (audit E6). */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmsViewModelTest {
    private val models = mutableListOf<AlarmsViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        endMain(models.map { it.viewModelScope.coroutineContext[Job] })
    }

    @Test fun `an unexpected failure says it couldn't read, not its own text`() {
        val error = errorAfterRead(IllegalStateException("Room cannot verify the data integrity"))
        assertEquals("Couldn't read from the strap", error)
    }

    @Test fun `the strap's own reason and a timeout keep their words`() {
        assertEquals("The strap isn't connected", errorAfterRead(StrapCommandException("The strap isn't connected")))
        assertEquals("The strap didn't answer. Try again.", errorAfterRead(GattTimeoutException("op 0x12 timed out")))
    }

    private fun errorAfterRead(failure: Throwable): String? {
        val vm = AlarmsViewModel(failingStrap(failure)).also { models += it }
        val state = vm.state.value
        assertFalse(state.loading)
        return state.error
    }

    /** A strap whose alarm read fails with [failure]. Everything else answers empty. */
    private fun failingStrap(failure: Throwable): StrapController {
        val flows = mutableMapOf<String, Any>()
        return Proxy.newProxyInstance(StrapController::class.java.classLoader, arrayOf(StrapController::class.java)) { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "failing strap"
                // A suspend call fails through its continuation, so a checked exception isn't wrapped by the proxy.
                method.name == "readAlarms" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args.last() as Continuation<Any?>).resumeWith(Result.failure(failure))
                    COROUTINE_SUSPENDED
                }
                StateFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableStateFlow<Any?>(null) }
                SharedFlow::class.java.isAssignableFrom(method.returnType) -> flows.getOrPut(method.name) { MutableSharedFlow<Any?>() }
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> Unit
            }
        } as StrapController
    }
}
