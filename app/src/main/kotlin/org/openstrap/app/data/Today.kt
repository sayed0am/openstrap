package org.openstrap.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.Clock
import java.time.LocalDate

/**
 * Today's date as it moves, for screens left open past midnight (audit C10). A date read once when a view model is
 * made stays on the day it was made, so the day shown and today's live steps never roll over.
 */
object Today {
    private const val MINUTE_MS = 60_000L

    /** The date by [clock] now. */
    fun on(clock: Clock): LocalDate = LocalDate.now(clock)

    /**
     * Today by [clock], at once and again at each new minute and on each of [changes], only when the date moved.
     * [clock] is asked afresh every time, so the default follows a change of time zone too.
     */
    fun dates(clock: () -> Clock = { Clock.systemDefaultZone() }, changes: Flow<Unit> = emptyFlow()): Flow<LocalDate> {
        val minutes = flow {
            while (true) {
                emit(Unit)
                delay(MINUTE_MS - Math.floorMod(clock().millis(), MINUTE_MS))
            }
        }
        return merge(minutes, changes).map { on(clock()) }.distinctUntilChanged()
    }

    /** The phone's clock, time zone or date was changed, so today may be another day without waiting for the minute. */
    fun changes(context: Context): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiver: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        // System broadcasts only, so nothing outside the system can send it.
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.applicationContext.unregisterReceiver(receiver) }
    }
}
