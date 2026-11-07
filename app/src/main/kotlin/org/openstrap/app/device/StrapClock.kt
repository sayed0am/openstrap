package org.openstrap.app.device

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.StrapUuids
import org.openstrap.app.data.catching
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.TimeSync
import org.openstrap.protocol.toHex
import org.openstrap.protocol.u8
import java.time.ZonedDateTime

/**
 * The strap's clock, written when Zepp 10.10.5 writes it (codemap zepp-connected-traffic-10.10.5 §1.4): on connect
 * without the DST hint, then with it on every other ping, after each finished sync and on the phone's time broadcasts
 * as [ClockWrites] lets through.
 */
internal class StrapClock(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: LiveLink,
    private val log: (String) -> Unit,
) {
    /** Why the clock is written, for the log. */
    enum class Why { PING, AFTER_SYNC, PHONE_TIME }

    /** The time module's version; the next-DST hint needs 2 or later (03 §3.8). */
    @Volatile private var timeProtocol: Int? = null

    private val writing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val writes = ClockWrites()

    /** Zepp's channel init asks the time module's version (`zf6.i`). */
    suspend fun queryVersion(s: ChannelSession) {
        timeProtocol = null
        if (!s.services.has(Endpoints.TIME)) return
        timeProtocol = catching {
            TimeSync.parseVersion(
                s.request(Endpoints.TIME, TimeSync.versionRequest, VERSION_TIMEOUT_MS) {
                    it.isNotEmpty() && it.u8(0) == TimeSync.VERSION + 1
                },
            )
        }.onFailure { log("time version query failed: $it") }.getOrNull()
        log("time protocol $timeProtocol")
    }

    /** The clock on connect, inside setup and without the DST hint (Zepp's T1). A failure drops the link, as in Zepp. */
    suspend fun setOnConnect(s: ChannelSession, client: GattClient) {
        val now = ZonedDateTime.now()
        val ok = catching {
            if (s.services.has(Endpoints.TIME)) {
                val r = s.request(Endpoints.TIME, TimeSync.setRequest(now)) { it.u8(0) == SET_REPLY }
                log("time set: ${r.toHex()}")
                r.size >= 2 && r.u8(1) == SUCCESS
            } else {
                client.write(StrapUuids.MAIN_SERVICE, StrapUuids.CURRENT_TIME, TimeSync.struct(now), withResponse = true)
                log("time set via 2A2B")
                true
            }
        }.onFailure { log("time set failed: $it") }.getOrDefault(false)
        if (!ok) throw StrapCommandException("the strap didn't take the time")
    }

    /** Counts a strap ping; true on the ones that write the clock. */
    fun onPing(): Boolean = writes.onPing()

    /** The clock, then the next DST change (Zepp's `hz90.c`). One at a time, and only while [s] is connected. */
    suspend fun write(s: ChannelSession, reason: Why) {
        if (!writing.compareAndSet(false, true)) return
        val why = reason.name.lowercase()
        try {
            if (link.activeSession !== s) return
            val now = ZonedDateTime.now()
            catching {
                if (s.services.has(Endpoints.TIME)) {
                    val r = s.request(Endpoints.TIME, TimeSync.setRequest(now)) { it.u8(0) == SET_REPLY }
                    log("time set ($why): ${r.toHex()}")
                } else {
                    link.gatt?.write(StrapUuids.MAIN_SERVICE, StrapUuids.CURRENT_TIME, TimeSync.struct(now), withResponse = true)
                    log("time set via 2A2B ($why)")
                }
            }.onFailure { log("time sync ($why) failed: $it") }
            if ((timeProtocol ?: 0) >= 2) {
                catching {
                    val request = TimeSync.nextDstRequest(now)
                    val r = s.request(Endpoints.TIME, request) { it.isNotEmpty() && it.u8(0) == TimeSync.NEXT_DST + 1 }
                    log("next dst ${request.toHex()}: ${r.toHex()}")
                }.onFailure { log("dst hint failed: $it") }
            }
        } finally {
            writing.set(false)
        }
    }

    /** The phone's clock, date or time zone changed: Zepp's `hz90.a` decides whether the strap hears of it. */
    private val timeChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) {
            val s = link.activeSession ?: return
            val action = intent.action.orEmpty()
            val pass = writes.onBroadcast(action, System.currentTimeMillis(), java.util.TimeZone.getDefault().rawOffset)
            log("phone time changed (${action.substringAfterLast('.')}), ${if (pass) "updating the strap" else "not passed on"}")
            if (pass) writeSoon(s)
        }
    }

    private fun writeSoon(s: ChannelSession) = scope.launch { write(s, Why.PHONE_TIME) }

    fun register() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, timeChanged, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private companion object {
        const val VERSION_TIMEOUT_MS = 5_000L
        const val SET_REPLY = 0x06
        const val SUCCESS = 0x01
    }
}
