package org.openstrap.app.device

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.data.catching
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.FindDevice
import org.openstrap.protocol.services.MotorVibrate
import org.openstrap.protocol.toHex
import java.util.Locale

/**
 * Every buzz goes through [buzzLock], one command at a time, and a ringing call wins: while [callHold] is set,
 * notification and workout buzzes are dropped. Zepp has no such guard on the phone; its watches sort calls from
 * notifications in firmware (both arrive on 0x001E), and the strap has no 0x001E. Find strap is the same `03` … `06`.
 */
// One small function per step of a buzz, a call or Find strap, each forwarded from StrapManager.
@Suppress("TooManyFunctions")
internal class BuzzCoordinator(
    private val scope: CoroutineScope,
    private val link: LiveLink,
    private val firmwareUpdating: StateFlow<Boolean>,
    private val commands: StrapCommands,
    private val log: (String) -> Unit,
) {
    private val buzzLock = Mutex()
    @Volatile private var callHold = false
    /** When the last motor pattern ends: one still playing then cuts off a `03` sent meanwhile (seen 2026-10-20). */
    @Volatile private var motorBusyUntil = 0L
    private var pulseJob: Job? = null

    // Each return is a buzz given way to a call.
    @Suppress("ReturnCount")
    suspend fun buzz(pattern: List<Int>) {
        if (callHold) return
        buzzLock.withLock {
            if (callHold) return
            if (link.activeSession?.services?.has(Endpoints.MOTOR_VIBRATE) == true) {
                playMotor(pattern)
                return
            }
            // No motor module: find-device's `03` keeps buzzing until stopped (fw 3.25, 2026-10-20), so time it by hand.
            findBuzz()
            delay(FindDevice.SHORT_BUZZ_MS)
            sendFindDevice(FindDevice.STOP)
        }
    }

    private suspend fun playMotor(pattern: List<Int>) {
        if (!MotorVibrate.isOk(commands.command(Endpoints.MOTOR_VIBRATE, MotorVibrate.play(pattern)))) {
            throw StrapCommandException("The strap didn't buzz")
        }
        motorBusyUntil = SystemClock.elapsedRealtime() + pattern.sum()
    }

    /**
     * Steady is find-device's `03` until [stopCallBuzz]. Pulsed plays [CALL_PULSE] on the motor once a second instead,
     * so it ends within one pulse of the stop; a strap without the motor module gets the steady buzz.
     */
    // Each return is the call ended meanwhile, or the pulsed way taken.
    @Suppress("ReturnCount")
    suspend fun startCallBuzz(pulsed: Boolean) {
        callHold = true
        buzzLock.withLock {
            if (!callHold) return
            val wait = motorBusyUntil - SystemClock.elapsedRealtime()
            if (wait > 0) {
                log("call buzz: waiting $wait ms for a motor pattern to end")
                delay(wait + MOTOR_SETTLE_MS)
            }
            // Checked again right before sending, as Zepp does: the call may have been answered meanwhile.
            if (!callHold) return
            if (pulsed && link.activeSession?.services?.has(Endpoints.MOTOR_VIBRATE) == true) {
                // A steady buzz still on (a preview switching over) is ended first: pulses cutting it off leave the
                // strap ignoring the next `03`.
                if (findBuzzOn) {
                    sendFindDevice(FindDevice.STOP)
                    delay(BUZZ_RESET_MS)
                }
                log("call buzz: pulsing")
                pulseJob?.cancel()
                pulseJob = scope.launch { pulse() }
                return
            }
            // A pulsed buzz still running (a preview just switched to steady) would cut the steady one off.
            pulseJob?.cancel()
            pulseJob = null
            // Logged before the send too, so a slow reply shows apart from a late send.
            log("call buzz: sending 03")
            findBuzz()
        }
    }

    /** True from an answered `03` until a `06` goes out or the strap reports `07`. */
    @Volatile private var findBuzzOn = false

    /**
     * `03`, tried again once after a `06`: after a motor pattern cut a `03` off, the strap sometimes ignored the next
     * `03` until it got a `06` (fw 3.25, 2026-10-28). Waiting the usual 8 s for it held up every buzz behind it.
     */
    private suspend fun findBuzz() {
        val reply = catching { commands.command(Endpoints.FIND_DEVICE, FindDevice.BUZZ, BUZZ_REPLY_MS) }.getOrElse {
            log("buzz: no answer to 03, sending 06 and trying again")
            sendFindDevice(FindDevice.STOP)
            delay(BUZZ_RESET_MS)
            commands.command(Endpoints.FIND_DEVICE, FindDevice.BUZZ, BUZZ_REPLY_MS)
        }
        if (!FindDevice.isBuzzOk(reply)) throw StrapCommandException("The strap didn't buzz")
        findBuzzOn = true
    }

    private suspend fun pulse() {
        val start = SystemClock.elapsedRealtime()
        var n = 0L
        while (callHold) {
            buzzLock.withLock {
                if (callHold) catching { playMotor(CALL_PULSE) }.onFailure { if (callHold) log("call pulse failed: $it") }
            }
            n++
            delay((start + n * CALL_PULSE_PERIOD_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    /** Ends the call buzz either way, and Find strap with it. Queued behind a start still in flight, so it can't be overtaken. */
    suspend fun stopCallBuzz() {
        callHold = false
        pulseJob?.cancel()
        pulseJob = null
        stopBuzzing()
    }

    /**
     * `03`, not the `05 01` Zepp uses for its continuous find: on fw 3.25 `03` buzzes until `06`, while `05 01` doesn't
     * vibrate at all (2026-10-21).
     */
    private suspend fun startBuzzing() = buzzLock.withLock { findBuzz() }

    /** `06`. Any stop ends Find strap too (a ringing call ending, Stop in the app), so it can't wait out the timeout. */
    suspend fun stopBuzzing() {
        endFind()
        buzzLock.withLock { sendFindDevice(FindDevice.STOP) }
    }

    val findingStrap = MutableStateFlow(false)
    private var findJob: Job? = null

    /** The same `03` … `06` as a ringing call, with Zepp's 60 s limit (03 §3.16). */
    suspend fun findStrap() {
        startBuzzing()
        log("find strap: buzzing")
        findingStrap.value = true
        findJob?.cancel()
        findJob = scope.launch {
            delay(FindDevice.FIND_TIMEOUT_MS)
            log("find strap: timed out")
            // Cleared first, so stopBuzzing's endFind doesn't cancel this job before the STOP goes out.
            findJob = null
            catching { stopBuzzing() }
        }
    }

    fun endFind() {
        findJob?.cancel()
        findJob = null
        findingStrap.value = false
    }

    /** The strap's `07`: buzzing stopped on its side, which also ends Find strap. */
    fun stoppedOnStrap() {
        findBuzzOn = false
        endFind()
    }

    /** STOP gets no reply (03 §3.16), so it is only sent. */
    // Each throw is a reason the user is shown.
    @Suppress("ThrowsCount")
    private suspend fun sendFindDevice(request: ByteArray) {
        val s = link.activeSession ?: throw StrapCommandException("The strap isn't connected")
        if (firmwareUpdating.value) throw StrapCommandException("Wait for the firmware update to finish")
        log("cmd %04x > %s".format(Locale.ROOT, Endpoints.FIND_DEVICE, request.toHex()))
        if (request.contentEquals(FindDevice.STOP)) findBuzzOn = false
        catching { s.send(Endpoints.FIND_DEVICE, request) }.getOrElse { throw StrapCommandException("The strap didn't answer") }
    }

    private companion object {
        /** The pulsed call buzz: 600 ms on the motor, once a second. */
        val CALL_PULSE = listOf(600)
        const val CALL_PULSE_PERIOD_MS = 1_000L
        /** Margin after a motor pattern's own length before a call's `03`. */
        const val MOTOR_SETTLE_MS = 150L
        /** How long a `03` waits for its answer before the `06` and second try; answers took 0.1 to 3 s. */
        const val BUZZ_REPLY_MS = 3_000L
        /** Pause after a `06` before the next buzz. */
        const val BUZZ_RESET_MS = 300L
    }
}
