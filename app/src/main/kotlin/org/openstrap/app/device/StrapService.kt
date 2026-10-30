package org.openstrap.app.device

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.openstrap.app.ui.glow.chargeShown
import org.openstrap.app.MainActivity
import org.openstrap.app.BuildConfig
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.R
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.workout.NotificationCopy
import org.openstrap.app.ui.workout.ZONE_COLORS
import androidx.compose.ui.graphics.toArgb
import org.openstrap.app.tr

/**
 * Keeps the process alive while connected so syncing and live HR survive the app being backgrounded, and for the
 * whole of a workout, connected or not. During a workout its notification is the workout's: sport, a running clock,
 * heart rate and zone, and Pause or Resume.
 */
class StrapService : LifecycleService() {

    private var observing = false
    /** What the notification shows now; each start command must put it back, not a placeholder over a workout. */
    private var shown: Notification? = null
    /** The notification's End asks first: until this time (elapsed realtime) it shows "Yes, end" and "Cancel". */
    private val confirmEnd = MutableStateFlow(0L)
    /** Today's scores for the everyday notification, refreshed after each sync. */
    private val figures = MutableStateFlow<Figures?>(null)
    /**
     * Set when the everyday notification is swiped away (Android 14+ allows it for a foreground service): it stays
     * away, the strap still connected, until a workout or a new problem needs it. Holds the problem shown then, if any.
     */
    private val hidden = MutableStateFlow<Hidden?>(null)
    /** What the everyday notification last said, for the problem it showed when swiped away. */
    private var idleShown: IdleContent? = null
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val container = (application as OpenStrapApp).container
        val strap = container.strap
        val workout = container.workout
        // Started before the permission was revoked (a notification button, say): startForeground would throw.
        if (!allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val first = shown ?: notification(tr(R.string.common_helio_strap), tr(R.string.missed_connecting))
        if (intent?.action == ACTION_HIDE) hidden.value = Hidden(idleShown?.problem)
        // A GPS workout adds the location type, allowed because its start comes from the visible ready screen. After a
        // restart in the background Android refuses it; the workout carries on without GPS.
        val gps = (workout.state.value as? WorkoutState.Recording)?.workout?.useGps == true && WorkoutLocation.granted(this)
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val withGps = if (gps && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base
        // Posting it again would undo a swipe; once in the foreground, the service stays there without it.
        if (!foreground || hidden.value == null || workout.state.value is WorkoutState.Recording) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, first, withGps)
            } catch (e: SecurityException) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, first, base)
            }
            foreground = true
        }
        when (intent?.action) {
            // Debug: the workout card with sample figures as this service's notification, or ("clear") its own back.
            ACTION_SAMPLE -> if (BuildConfig.DEBUG && workout.state.value !is WorkoutState.Recording) {
                val kind = intent.getStringExtra(EXTRA_SAMPLE) ?: "running"
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, if (kind == "clear") first else WorkoutNotification.sample(this, kind))
            }
            ACTION_PAUSE -> workout.pause()
            ACTION_RESUME -> workout.resume()
            ACTION_END_ASK -> confirmEnd.value = SystemClock.elapsedRealtime() + CONFIRM_MS
            ACTION_END_CANCEL -> confirmEnd.value = 0
            ACTION_END -> { confirmEnd.value = 0; workout.end() }
            ACTION_SYNC -> strap.syncNow()
            ACTION_RETRY -> strap.autoConnect()
            ACTION_UNDO -> intent.getLongExtra(EXTRA_START, 0).takeIf { it > 0 }?.let {
                workout.undoTrim(it)
                getSystemService(NotificationManager::class.java).cancel(SUMMARY_ID)
            }
            ACTION_END_AT -> workout.endAtPrompt()
            ACTION_KEEP -> workout.keepGoing()
            ACTION_LAP -> workout.lap()
            ACTION_START_SET -> workout.startSet()
            ACTION_SET_DONE -> workout.setDone()
            ACTION_EXTEND -> workout.extendRest()
            ACTION_RECORD -> workout.recordOffer()
            ACTION_DECLINE -> workout.declineOffer()
        }
        val recording = workout.state.value is WorkoutState.Recording
        if (observing) return if (recording) START_STICKY else START_NOT_STICKY
        observing = true
        lifecycleScope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            workout.offer.collect { o -> if (o == null) nm.cancel(OFFER_ID) else nm.notify(OFFER_ID, offerNotification(o)) }
        }
        // "End?" falls back to the usual actions if nobody answers.
        lifecycleScope.launch { confirmEnd.collectLatest { until -> if (until > 0) { delay(CONFIRM_MS); confirmEnd.value = 0 } } }
        lifecycleScope.launch(Dispatchers.Default) {
            strap.sync.distinctUntilChangedBy { it is SyncState.Done }.collectLatest {
                while (true) {
                    figures.value = runCatching { loadFigures(container) }.getOrNull() ?: figures.value
                    delay(FIGURES_REFRESH_MS)
                }
            }
        }
        lifecycleScope.launch { watchEnded(container) }
        lifecycleScope.launch {
            // Live HR changes about once a second, and every post wakes the CPU and redraws the system UI.
            // Connection, battery, charging and workout changes post at once; a bpm-only change waits for the refresh.
            var status: String? = null
            var lastPost = 0L
            val more = combine(strap.sync, figures, hidden, confirmEnd) { sync, f, h, end -> More(sync, f, h, end) }
            // Until the session has checked for a workout left recording (a restart after the process died), stay up.
            combine(strap.connection, strap.liveHeartRate, workout.state, workout.restored, more) { c, hr, w, restored, m ->
                Status(c, hr, w, restored, m)
            }.collect { (c, hr, w, restored, m) ->
                if (c is ConnectionState.Disconnected || c is ConnectionState.NotPaired) {
                    if (w !is WorkoutState.Recording && restored) { stopSelf(); return@collect }
                }
                val confirming = m.confirmEnd > SystemClock.elapsedRealtime()
                val now = SystemClock.elapsedRealtime()
                val n: Notification
                if (w is WorkoutState.Recording) {
                    if (m.hidden != null) hidden.value = null
                    val key = "${c::class.simpleName}${(c as? ConnectionState.Connected)?.batteryPercent}|${hr != null}|$w|$confirming"
                    if (key == status && now - lastPost < WORKOUT_REFRESH_MS) return@collect
                    status = key
                    n = workoutNotification(w, hr.takeIf { c is ConnectionState.Connected }, c is ConnectionState.Connected, confirming, workout)
                } else {
                    val idle = idleContent(c, m.sync, m.figures)
                    // Swiped away: back only for a problem it wasn't already showing.
                    if (m.hidden != null) {
                        if (idle.problem == null || idle.problem == m.hidden.problem) { status = null; return@collect }
                        hidden.value = null
                    }
                    val key = "$idle"
                    if (key == status) return@collect
                    status = key
                    idleShown = idle
                    n = idleNotification(idle)
                }
                lastPost = now
                shown = n
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            }
        }
        return if (recording) START_STICKY else START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.strap_service_strap_connection), NotificationManager.IMPORTANCE_LOW))
        // Left by the old Quiet switch: Android raises a foreground service's MIN channel to LOW anyway.
        nm.deleteNotificationChannel("strap_quiet")
        nm.createNotificationChannel(NotificationChannel(SAVED_CHANNEL, tr(R.string.common_workout_saved), NotificationManager.IMPORTANCE_LOW))
        WorkoutNotification.createChannel(this)
        // Seen, not heard: the strap's buzz is the alert.
        nm.createNotificationChannel(NotificationChannel(OFFER_CHANNEL, tr(R.string.strap_service_workout_noticed), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        })
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    // Its own request code: a pending intent keeps the flags it was first made with, and an older one without
    // SINGLE_TOP would only bring the app forward, never delivering the extra that opens the live screen.
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, REQUEST_OPEN,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_WORKOUT, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(title: String, text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_openstrap)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    /** What the everyday notification says; equal content isn't posted again. */
    private data class IdleContent(
        val title: String, val text: String, val syncedAt: Long?, val progress: Float?, val syncing: Boolean,
        val retry: Boolean, val connected: Boolean, val sport: Int, val problem: String?,
    )

    /**
     * Your day first, the link second: "Recovery 72, Energy 58" over "Strap connected, battery 80%" (the strap's battery named, so it's never taken for Energy), with the last sync as the
     * notification's time. Problems take the text: a failed connection (with Retry), a low battery, a sync under way.
     */
    private fun idleContent(c: ConnectionState, sync: SyncState, f: Figures?): IdleContent {
        val title = f?.let { listOfNotNull(it.recovery?.let { r -> tr(R.string.strap_service_recovery, r) }, it.charge?.let { ch -> tr(R.string.strap_service_energy, ch) }).joinToString(", ") }
            ?.ifEmpty { null } ?: tr(R.string.common_helio_strap)
        val connected = c as? ConnectionState.Connected
        val battery = connected?.batteryPercent
        val low = connected != null && !connected.charging && battery != null && battery <= LOW_BATTERY
        val text = when {
            c is ConnectionState.Failed -> tr(R.string.strap_service_couldn_t_reach_the)
            c is ConnectionState.Connecting -> tr(R.string.missed_connecting)
            c is ConnectionState.Authenticating -> tr(R.string.missed_authenticating)
            connected == null -> tr(R.string.common_strap_not_connected)
            sync is SyncState.Running -> tr(R.string.missed_syncing_step, sync.step)
            low -> tr(R.string.strap_service_strap_battery_charge_it, battery)
            else -> listOfNotNull(if (connected.charging) tr(R.string.strap_service_strap_charging) else tr(R.string.common_strap_connected), battery?.let { tr(R.string.strap_service_battery, it) }).joinToString(", ")
        }
        return IdleContent(
            title, text, (sync as? SyncState.Done)?.atEpochSeconds ?: f?.syncedAt, (sync as? SyncState.Running)?.progress,
            sync is SyncState.Running, c is ConnectionState.Failed, connected != null, f?.sport ?: Workouts.DEFAULT_SPORTS.first(),
            when { c is ConnectionState.Failed -> "failed"; low -> "low"; else -> null },
        )
    }

    private fun idleNotification(i: IdleContent): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(i.title)
            .setContentText(i.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setDeleteIntent(service(ACTION_HIDE))
        // The header shows how long ago it synced, and keeps counting on its own.
        i.syncedAt?.let { b.setShowWhen(true).setWhen(it * 1000).setSubText(tr(R.string.strap_service_synced)) } ?: b.setShowWhen(false)
        if (i.syncing) b.setProgress(100, ((i.progress ?: 0f) * 100).roundToInt(), i.progress == null)
        if (i.retry) b.addAction(NotificationCompat.Action(0, tr(R.string.strap_service_retry), service(ACTION_RETRY)))
        if (i.connected) {
            b.addAction(NotificationCompat.Action(0, tr(R.string.common_start_workout), startWorkout(i.sport)))
            if (!i.syncing) b.addAction(NotificationCompat.Action(0, tr(R.string.common_sync_now), service(ACTION_SYNC)))
        }
        return b.build()
    }

    private fun startWorkout(sport: Int): PendingIntent = PendingIntent.getActivity(
        this, REQUEST_START,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_READY_SPORT, sport).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Today's Recovery and Charge, the last sync and your usual sport; the model runs in a few hundred ms. */
    private suspend fun loadFigures(container: org.openstrap.app.AppContainer): Figures {
        val dao = container.database.healthDao()
        val r = org.openstrap.app.data.RecoveryEngine(dao).compute(java.time.LocalDate.now(), container.settings.profile.first())
        val sport = dao.appWorkoutsAll().maxByOrNull { it.start }?.sportType ?: Workouts.DEFAULT_SPORTS.first()
        return Figures(r.readiness.score, r.charge.latest?.let(::chargeShown), sport, (container.strap.sync.value as? SyncState.Done)?.atEpochSeconds ?: figures.value?.syncedAt)
    }

    /**
     * Whether this phone shows the workout as a Live Update. A colorized notification with custom views can't be one, so
     * the black card shows only where the chip wouldn't show anyway: before Android 16's Live Updates, when they're
     * turned off for the app, and on Samsung, whose One UI keeps the chip for apps it lists.
     */
    private fun liveUpdatesShown(): Boolean =
        Build.VERSION.SDK_INT >= 36 && !Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            runCatching { getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }.getOrDefault(false)

    /**
     * The workout notification. Where it shows as a Live Update (Android 16, not Samsung), it's promoted: a chip by the
     * status-bar clock with the running time, and on the lock screen. The clock runs on its own (a chronometer), so it
     * needs no posts to tick. The five zones are the progress bar's segments with a dot where the heart rate sits; a
     * time or kcal target fills the bar toward the goal instead. Everywhere else it's the black card of [WorkoutNotification],
     * custom views a Live Update can't have. Both say the same: [NotificationCopy.card].
     */
    private fun workoutNotification(r: WorkoutState.Recording, bpm: Int?, connected: Boolean, confirming: Boolean, session: WorkoutSession): Notification {
        r.endPrompt?.let { return stoppedNotification(r, it) }
        if (r.strength && !r.paused) return strengthNotification(r, bpm, connected, confirming)
        val w = r.workout
        val now = System.currentTimeMillis()
        val active = WorkoutRecorder.activeSeconds(w, now / 1000)
        val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate)
        val target = WorkoutTarget.parse(w.target)
        val card = NotificationCopy.card(
            Workouts.sportName(w.sportType), active, r.paused, connected, bpm, r.maxHeartRate, w.useGps, w.distanceMeters,
            w.distanceFromSteps, target, session.zoneSeconds.value.toList(), confirming, session.kcal.value?.active,
        )
        val actions = WorkoutNotification.actions(r.paused, confirming, ::service)
        if (!liveUpdatesShown()) return WorkoutNotification.build(this, card, actions, openApp()).build()
        val color = when {
            r.paused || !connected -> PAUSED_GREY
            zone != null -> ZONE_COLORS[zone].toArgb()
            else -> PAUSED_GREY
        }
        val bar = NotificationCompat.ProgressStyle()
            .setStyledByProgress(false)
            // `also`, not `apply`: ProgressStyle has an apply() of its own. The tracker, the gauge marker in the zone colour as on the live
            // screen's zone bar, marks where the heart rate sits; a progress point would draw as a square on some phones.
            .also { style ->
                if (target is WorkoutTarget.Time) {
                    style.setStyledByProgress(true)
                    style.setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(target.seconds.toInt().coerceAtLeast(1)).setColor(color)))
                    style.setProgress(active.toInt().coerceAtMost(target.seconds.toInt()))
                } else if (target is WorkoutTarget.Kcal) {
                    // A kcal target fills toward its kcal the same way.
                    style.setStyledByProgress(true)
                    style.setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(target.kcal.coerceAtLeast(1)).setColor(color)))
                    style.setProgress((session.kcal.value?.active?.toInt() ?: 0).coerceIn(0, target.kcal))
                } else {
                    // Bright up to the heart rate, faded after, like the app's zone bar and its gauges.
                    style.setStyledByProgress(true)
                    style.setProgressSegments(ZONE_COLORS.map { NotificationCompat.ProgressStyle.Segment(ZONE_SPAN).setColor(it.toArgb()) })
                    if (bpm != null && !r.paused) {
                        style.setProgress((WorkoutRecorder.zonePosition(bpm, r.maxHeartRate) * ZONE_SPAN * ZONE_COLORS.size).toInt())
                        WorkoutRecorder.liveZone(bpm, r.maxHeartRate)?.let { z -> style.setProgressTrackerIcon(tracker(z)) }
                    }
                }
            }
        val b = NotificationCompat.Builder(this, WorkoutNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(card.sport)
            .setContentText(card.text)
            .setStyle(bar)
            .setColor(BRAND_DOT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
            .apply { if (r.paused) setShortCriticalText(tr(R.string.common_paused)) }
            // In the header, after the running time.
            .setSubText(card.kcal)
            .setContentIntent(openApp())
            .setShowWhen(true)
            .setUsesChronometer(!r.paused)
            .setWhen(if (r.paused) now else now - active * 1000)
        actions.forEach(b::addAction)
        return b.build()
    }

    /** "End workout?": a stray tap in a pocket can't end it. It falls back after [CONFIRM_MS]. */
    private fun NotificationCompat.Builder.endConfirmation(): NotificationCompat.Builder =
        setContentText(NotificationCopy.END_QUESTION).apply { WorkoutNotification.actions(paused = false, confirming = true, ::service).forEach(::addAction) }

    /**
     * Once a workout is saved, a quiet summary with View, and Undo when it ended on its own (the stop or car question)
     * and the time after was kept. It goes when the summary in the app is closed.
     */
    private suspend fun watchEnded(container: org.openstrap.app.AppContainer) {
        val nm = getSystemService(NotificationManager::class.java)
        var previous: WorkoutState? = null
        container.workout.state.collect { st ->
            val was = previous
            previous = st
            when {
                st is WorkoutState.Ended && was is WorkoutState.Recording -> {
                    val w = container.database.healthDao().appWorkoutOnce(st.start) ?: return@collect
                    nm.notify(SUMMARY_ID, savedNotification(w))
                }
                st !is WorkoutState.Ended -> nm.cancel(SUMMARY_ID)
            }
        }
    }

    private fun savedNotification(w: org.openstrap.app.data.db.AppWorkout): Notification {
        val end = w.end ?: System.currentTimeMillis() / 1000
        val duration = WorkoutRecorder.activeSeconds(w.copy(end = null, pausedAt = null), end)
        val facts = listOfNotNull(
            clock(duration),
            w.distanceMeters?.takeIf { it >= 10 }?.let { (if (w.distanceFromSteps) "≈ " else "") + org.openstrap.app.ui.glow.km(it) + if (it >= 1000) " km" else "" },
            w.averageHeartRate?.let { tr(R.string.strap_service_bpm_avg, it) },
        ).joinToString(", ")
        val auto = w.endReason == org.openstrap.app.data.db.AppWorkout.END_AUTO || w.endReason == org.openstrap.app.data.db.AppWorkout.END_CAR
        val cut = w.recordedEnd?.let { it - end }?.takeIf { auto && it >= 60 }
        val time = java.time.Instant.ofEpochSecond(end).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val text = if (cut != null) tr(R.string.strap_service_ended_at_when_you, facts, time, cut / 60) else facts
        val b = NotificationCompat.Builder(this, SAVED_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(tr(R.string.missed_sport_saved, Workouts.sportName(w.sportType)))
            .setContentText(facts)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(NotificationCompat.Action(0, tr(R.string.strap_service_view), openApp()))
        if (cut != null) b.addAction(
            NotificationCompat.Action(
                0, tr(R.string.common_undo),
                PendingIntent.getService(
                    this, REQUEST_UNDO, Intent(this, StrapService::class.java).setAction(ACTION_UNDO).putExtra(EXTRA_START, w.start),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ),
        )
        return b.build()
    }

    /** Strength on the lock screen: the set or the rest counting down, its progress, and the next step. */
    private fun strengthNotification(r: WorkoutState.Recording, bpm: Int?, connected: Boolean, confirming: Boolean): Notification {
        val now = System.currentTimeMillis()
        val done = r.sets.count { it.end != null }
        val until = r.restUntil
        val title = when {
            r.inSet -> tr(R.string.strap_service_set, r.sets.size)
            until != null -> tr(R.string.strap_service_resting_after_set, done)
            done == 0 -> tr(R.string.strap_service_ready_for_set, Workouts.sportName(r.workout.sportType))
            else -> tr(R.string.strap_service_rest_over_set_next, done + 1)
        }
        val b = NotificationCompat.Builder(this, WorkoutNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(title)
            .setContentText(
                if (!connected) tr(R.string.strap_service_still_recording_reconnecting)
                else listOfNotNull(bpm?.let { "$it bpm" }, r.workout.setsTarget?.let { tr(R.string.strap_service_of_sets, done, it) }).joinToString(", "),
            )
            .setColor(if (connected) (WorkoutRecorder.liveZone(bpm, r.maxHeartRate)?.let { ZONE_COLORS[it].toArgb() } ?: PAUSED_GREY) else PAUSED_GREY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
            .setContentIntent(openApp())
        if (confirming) return b.endConfirmation().build()
        if (until != null) {
            val start = r.sets.lastOrNull()?.end ?: until
            // The rest counts down on its own; the bar fills toward its end.
            b.setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true).setWhen(until * 1000)
                .setProgress((until - start).toInt().coerceAtLeast(1), (now / 1000 - start).toInt().coerceIn(0, (until - start).toInt()), false)
            b.addAction(NotificationCompat.Action(0, tr(R.string.common_start_set, r.sets.size + 1), service(ACTION_START_SET)))
            b.addAction(NotificationCompat.Action(0, tr(R.string.missed_plus_30_s), service(ACTION_EXTEND)))
        } else if (r.inSet) {
            b.setUsesChronometer(true).setShowWhen(true).setWhen(r.sets.last().start * 1000)
            b.addAction(NotificationCompat.Action(0, tr(R.string.common_set_done), service(ACTION_SET_DONE)))
        } else {
            b.addAction(NotificationCompat.Action(0, tr(R.string.common_start_set, r.sets.size + 1), service(ACTION_START_SET)))
        }
        b.addAction(NotificationCompat.Action(0, tr(R.string.common_end), service(ACTION_END_ASK)))
        return b.build()
    }

    /** "Looks like you've stopped": the same notification, asking whether to end where the wearer stopped. */
    private fun stoppedNotification(r: WorkoutState.Recording, at: Long): Notification {
        val time = java.time.Instant.ofEpochSecond(at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val sport = Workouts.sportName(r.workout.sportType).lowercase()
        val text = if (r.inCar) tr(R.string.strap_service_you_last_moved_at, time, sport)
        else tr(R.string.strap_service_your_heart_rate_has, time, sport, time)
        return NotificationCompat.Builder(this, WorkoutNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(if (r.inCar) tr(R.string.common_looks_like_you_re) else tr(R.string.common_looks_like_you_ve))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(tr(R.string.strap_service_end))
            .setContentIntent(openApp())
            .addAction(NotificationCompat.Action(0, tr(R.string.strap_service_end_at, time), service(ACTION_END_AT)))
            .addAction(NotificationCompat.Action(0, tr(R.string.common_keep_going), service(ACTION_KEEP)))
            .build()
    }

    /** "Looks like a workout": record it from where the effort began, or not. Swiping it away is "Not now". */
    private fun offerNotification(o: org.openstrap.app.data.AutoStart.Offer): Notification {
        val time = java.time.Instant.ofEpochSecond(o.start).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val sport = Workouts.sportName(o.sportType).lowercase()
        val text = tr(R.string.strap_service_your_heart_rate_has_2, time, o.averageHeartRate, sport, time)
        return NotificationCompat.Builder(this, OFFER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(tr(R.string.common_looks_like_a_workout))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .setDeleteIntent(service(ACTION_DECLINE))
            .addAction(NotificationCompat.Action(0, tr(R.string.common_record), service(ACTION_RECORD)))
            .addAction(NotificationCompat.Action(0, tr(R.string.common_not_now), service(ACTION_DECLINE)))
            .build()
    }

    /** The app's gauge marker in each zone's colour: a solid dot in a soft halo of itself, drawn once per zone. */
    private val trackers = arrayOfNulls<androidx.core.graphics.drawable.IconCompat>(ZONE_COLORS.size)

    private fun tracker(zone: Int): androidx.core.graphics.drawable.IconCompat = trackers[zone] ?: run {
        val d = resources.displayMetrics.density
        val size = (18 * d).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val color = ZONE_COLORS[zone].toArgb()
        paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(color, 77)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, 5 * d, paint)
        androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap).also { trackers[zone] = it }
    }

    private fun service(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, StrapService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private data class Status(val connection: ConnectionState, val heartRate: Int?, val workout: WorkoutState, val restored: Boolean, val more: More)
    private data class More(val sync: SyncState, val figures: Figures?, val hidden: Hidden?, val confirmEnd: Long)
    private data class Hidden(val problem: String?)
    private data class Figures(val recovery: Int?, val charge: Int?, val sport: Int, val syncedAt: Long?)

    companion object {
        private const val CHANNEL = "strap"
        private const val NOTIFICATION_ID = 1
        private const val OFFER_ID = 3
        private const val OFFER_CHANNEL = "workout_offer"
        internal const val ACTION_END_ASK = "org.openstrap.app.workout.END_ASK"
        internal const val ACTION_END_CANCEL = "org.openstrap.app.workout.END_CANCEL"
        private const val ACTION_SYNC = "org.openstrap.app.strap.SYNC"
        private const val ACTION_RETRY = "org.openstrap.app.strap.RETRY"
        private const val ACTION_UNDO = "org.openstrap.app.workout.UNDO"
        private const val ACTION_HIDE = "org.openstrap.app.strap.HIDE"
        private const val EXTRA_START = "start"
        private const val SUMMARY_ID = 4
        private const val SAVED_CHANNEL = "workout_saved"
        private const val REQUEST_START = 5
        private const val REQUEST_UNDO = 6
        private const val CONFIRM_MS = 6_000L
        private const val FIGURES_REFRESH_MS = 30 * 60_000L
        /** Battery percent at which the everyday notification says to charge. */
        private const val LOW_BATTERY = 15
        /** The live screen's paused grey, for a paused or disconnected workout. */
        private const val PAUSED_GREY = 0xFF5A5F6A.toInt()
        /** The red dot in the "OS." logo, the app's heart colour. */
        private val BRAND_DOT = org.openstrap.app.ui.theme.MetricColors.heart.toArgb()
        private const val ACTION_RECORD = "org.openstrap.app.workout.RECORD"
        private const val ACTION_DECLINE = "org.openstrap.app.workout.DECLINE"
        private const val WORKOUT_REFRESH_MS = 10_000L
        internal const val ACTION_PAUSE = "org.openstrap.app.workout.PAUSE"
        internal const val ACTION_SAMPLE = "org.openstrap.app.workout.SAMPLE"
        internal const val EXTRA_SAMPLE = "kind"
        internal const val ACTION_RESUME = "org.openstrap.app.workout.RESUME"
        internal const val ACTION_END = "org.openstrap.app.workout.END"
        private const val ACTION_END_AT = "org.openstrap.app.workout.END_AT"
        private const val ACTION_KEEP = "org.openstrap.app.workout.KEEP"
        internal const val ACTION_LAP = "org.openstrap.app.workout.LAP"
        private const val ACTION_START_SET = "org.openstrap.app.workout.START_SET"
        private const val ACTION_SET_DONE = "org.openstrap.app.workout.SET_DONE"
        private const val ACTION_EXTEND = "org.openstrap.app.workout.EXTEND"
        /** Each zone's length on the notification's progress bar. */
        private const val ZONE_SPAN = 20
        private const val REQUEST_OPEN = 2

        private fun zoneRange(zone: Int, maxHr: Int) =
            if (zone == 0) tr(R.string.common_under_bpm, 60 * maxHr / 100) else tr(R.string.strap_service_bpm, (50 + zone * 10) * maxHr / 100, if (zone == 4) maxHr else (60 + zone * 10) * maxHr / 100 - 1)

        /** Whether the service is up; a start then needn't (and, once swiped away, mustn't) post its notification. */
        @Volatile private var running = false

        /**
         * Android 14 on refuses a connected-device service to an app holding no Bluetooth permission (never granted, or
         * revoked in Settings), and the refusal throws inside the service, where no caller can catch it.
         */
        fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        fun start(context: Context) {
            if (!allowed(context)) return
            val intent = Intent(context, StrapService::class.java)
            if (running && runCatching { context.startService(intent) }.isSuccess) return
            context.startForegroundService(intent)
        }

        private fun clock(seconds: Long) = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
