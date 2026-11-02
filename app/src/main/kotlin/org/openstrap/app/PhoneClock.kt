package org.openstrap.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import org.openstrap.app.device.WidgetUpdater
import org.openstrap.app.presentation.ClockSetting

/**
 * Keeps [ClockSetting] on the phone's 12 or 24 hour setting, so every time the app writes follows it. Android tells a
 * running app when the setting changes with [Intent.ACTION_TIME_CHANGED], the broadcast a clock change sends too, and
 * the widgets are drawn again then, since their times were written in the old form.
 */
internal object PhoneClock {
    /** Reads the setting now. */
    fun read(context: Context) {
        ClockSetting.use24Hour = DateFormat.is24HourFormat(context)
    }

    /** Reads the setting and follows it while the process lives. Called once, from the app's start. */
    fun follow(context: Context) {
        read(context)
        ContextCompat.registerReceiver(context, changed, IntentFilter(Intent.ACTION_TIME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            read(context)
            WidgetUpdater.changedAll(context)
        }
    }
}
