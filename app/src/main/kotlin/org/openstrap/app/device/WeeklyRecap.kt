package org.openstrap.app.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import org.openstrap.app.MainActivity
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.R
import org.openstrap.app.i18n.Strings
import org.openstrap.app.data.Rewards
import org.openstrap.app.data.catching
import org.openstrap.app.presentation.RewardsCopy

/**
 * The weekly recap (workouts spec 3.4): one notification on the first morning of the week about the week before, from
 * the weeks stored ([org.openstrap.app.data.RewardsKeeper]), opening the recap page. Off with its switch, and nothing
 * for a week without a workout: no nudges.
 */
object WeeklyRecap {
    private const val WORK = "weekly-recap"
    private const val CHANNEL = "weekly_recap"
    private const val ID = 7401

    /** The morning hours it may come in. */
    private val MORNING = LocalTime.of(8, 0)..LocalTime.of(12, 0)

    /** Looks every few hours, so one look falls in the morning; a no-op when already scheduled. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecapWorker>(3, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Posts last week's recap when it's the first morning of the week, the switch is on and it hasn't been sent. */
    suspend fun check(context: Context) {
        val container = (context.applicationContext as OpenStrapApp).container
        val settings = container.settings
        val now = java.time.LocalDateTime.now()
        val firstDay = container.rewards.firstDay
        val thisWeek = Rewards.weekStart(now.toLocalDate(), firstDay)
        if (now.toLocalDate() != thisWeek || now.toLocalTime() !in MORNING) return
        val lastWeek = thisWeek.minusWeeks(1)
        if (!settings.weeklyRecap.first() || settings.recapWeek.first() == lastWeek.toEpochDay()) return
        // The weeks are only worked out again while the app is open: workouts synced since would be missed, and the
        // week is never sent again. Brought up to date first; a refresh that fails still sends what's stored.
        val recordingStart = (container.workout.state.value as? WorkoutState.Recording)?.workout?.start
        catching { container.rewards.refreshNow(recordingStart) }.onFailure { Log.w("OpenStrap", "weekly recap: weeks not refreshed: $it") }
        val weeks = container.database.healthDao().weekResultsOnce()
        val week = weeks.firstOrNull { it.weekStart == lastWeek.toEpochDay() }
        settings.setRecapWeek(lastWeek.toEpochDay())
        if (week == null || week.count == 0) return
        // Android 13 brought the notification permission. Before it, the check would always read as denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        show(context, RewardsCopy.weekRange(lastWeek), RewardsCopy.recapText(week, Rewards.inARow(weeks, lastWeek)))
    }

    private fun show(context: Context, range: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, Strings.app.get(R.string.system_channel_recap), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_RECAP, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_unstrap)
                .setContentTitle(Strings.app.format(R.string.system_recap_title, range))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}

/** The recap's look every few hours ([WeeklyRecap.schedule]). A failed look is logged, and the next one tries again. */
class RecapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        catching { WeeklyRecap.check(applicationContext) }.onFailure { Log.w("OpenStrap", "weekly recap: look failed: $it") }
        return Result.success()
    }
}
