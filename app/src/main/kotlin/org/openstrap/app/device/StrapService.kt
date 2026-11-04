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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.openstrap.app.presentation.chargeShown
import org.openstrap.app.MainActivity
import org.openstrap.app.BuildConfig
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.R
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.catching
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.presentation.NotificationCopy
import org.openstrap.app.presentation.StrapBands
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.WorkoutColors
import org.openstrap.app.presentation.WorkoutWords

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
        val dao = container.database.healthDao()
        // Started before the permission was revoked (a notification button, say): startForeground would throw.
        if (!allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val first = shown ?: notification("Helio Strap", "Connecting…")
        if (intent?.action == ACTION_HIDE) hidden.value = Hidden(idleShown?.problem)
        // A GPS workout adds the location type, allowed because its start comes from the visible ready screen. After a
        // restart in the background Android refuses it; the workout carries on without GPS.
        val gps = (workout.state.value as? WorkoutState.Recording)?.workout?.useGps == true && WorkoutLocation.granted(this)
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val withGps = if (gps) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base
        // Posting it again would undo a swipe; once in the foreground, the service stays there without it.
        if (!foreground || hidden.value == null || workout.state.value is WorkoutState.Recording) {
            val log: (String) -> Unit = { (strap as? StrapManager)?.log(it) }
            // Refused for the location type, it tries again without. Refused outright (a start from the background
            // with no exemption), it stops rather than crash: a workout records on, but can be killed.
            if (!tryForeground(first, withGps, log) && (withGps == base || !tryForeground(first, base, log))) {
                (workout.state.value as? WorkoutState.Recording)?.let {
                    log("service: no foreground while recording, asking to keep it going")
                    KeepRecordingNotice.show(this, it.workout.sportType)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            foreground = true
            KeepRecordingNotice.clear(this)
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
                    // A failed read keeps the figures shown.
                    figures.value = catching { loadFigures(dao, container.settings, strap) }
                        .onFailure { e -> (strap as? StrapManager)?.log("service: notification figures not read: $e") }
                        .getOrNull() ?: figures.value
                    delay(FIGURES_REFRESH_MS)
                }
            }
        }
        lifecycleScope.launch { watchEnded(workout.state, dao) }
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

    /**
     * [ServiceCompat.startForeground] as [type], false when Android refuses it: a [SecurityException] for a type the app
     * may not use now, an [IllegalStateException] (`ForegroundServiceStartNotAllowedException`) for a start it doesn't allow.
     */
    private fun tryForeground(n: Notification, type: Int, log: (String) -> Unit): Boolean =
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            true
        } catch (e: SecurityException) {
            log("service: foreground as $type refused: $e")
            false
        } catch (e: IllegalStateException) {
            log("service: foreground start not allowed: $e")
            false
        }

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Strap connection", NotificationManager.IMPORTANCE_LOW))
        // Left by the old Quiet switch: Android raises a foreground service's MIN channel to LOW anyway.
        nm.deleteNotificationChannel("strap_quiet")
        nm.createNotificationChannel(NotificationChannel(SAVED_CHANNEL, "Workout saved", NotificationManager.IMPORTANCE_LOW))
        WorkoutNotification.createChannel(this)
        KeepRecordingNotice.createChannel(this)
        // Seen, not heard: the strap's buzz is the alert.
        nm.createNotificationChannel(NotificationChannel(OFFER_CHANNEL, "Workout noticed", NotificationManager.IMPORTANCE_DEFAULT).apply {
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
        val title = f?.let { listOfNotNull(it.recovery?.let { r -> "Recovery $r" }, it.charge?.let { ch -> "Energy $ch" }).joinToString(", ") }
            ?.ifEmpty { null } ?: "Helio Strap"
        val connected = c as? ConnectionState.Connected
        val battery = connected?.batteryPercent
        val low = connected != null && StrapBands.lowBattery(battery, connected.charging)
        val text = when {
            c is ConnectionState.Failed -> "Couldn't reach the strap"
            c is ConnectionState.Connecting -> "Connecting…"
            c is ConnectionState.Authenticating -> "Authenticating…"
            connected == null -> "Strap not connected"
            sync is SyncState.Running -> "Syncing, ${sync.step}"
            low -> "Strap battery $battery%. Charge it tonight."
            else -> listOfNotNull(if (connected.charging) "Strap charging" else "Strap connected", battery?.let { "battery $it%" }).joinToString(", ")
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
        i.syncedAt?.let { b.setShowWhen(true).setWhen(it * 1000).setSubText("Synced") } ?: b.setShowWhen(false)
        if (i.syncing) b.setProgress(100, ((i.progress ?: 0f) * 100).roundToInt(), i.progress == null)
        if (i.retry) b.addAction(NotificationCompat.Action(0, "Retry", service(ACTION_RETRY)))
        if (i.connected) {
            b.addAction(NotificationCompat.Action(0, "Start workout", startWorkout(i.sport)))
            if (!i.syncing) b.addAction(NotificationCompat.Action(0, "Sync now", service(ACTION_SYNC)))
        }
        return b.build()
    }

    private fun startWorkout(sport: Int): PendingIntent = PendingIntent.getActivity(
        this, REQUEST_START,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_READY_SPORT, sport).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Today's Recovery and Charge, the last sync and your usual sport; the model runs in a few hundred ms. */
    private suspend fun loadFigures(dao: HealthDao, settings: SettingsStore, strap: StrapController): Figures {
        val r = org.openstrap.app.data.RecoveryEngine(dao).compute(java.time.LocalDate.now(), settings.profile.first())
        val sport = dao.appWorkoutsAll().maxByOrNull { it.start }?.sportType ?: Workouts.DEFAULT_SPORTS.first()
        val syncedAt = (strap.sync.value as? SyncState.Done)?.atEpochSeconds ?: figures.value?.syncedAt
        return Figures(NotificationCopy.recovery(r), r.charge.latest?.let(::chargeShown), sport, syncedAt)
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
        val zones = session.heartZones.value
        if (r.strength && !r.paused) return strengthNotification(r, bpm, connected, confirming, zones)
        val w = r.workout
        val now = System.currentTimeMillis()
        val active = WorkoutRecorder.activeSeconds(w, now / 1000)
        val zone = WorkoutRecorder.liveZone(bpm, r.maxHeartRate, zones)
        val target = WorkoutTarget.parse(w.target)
        val card = NotificationCopy.card(
            Workouts.sportName(w.sportType), active, r.paused, connected, bpm, r.maxHeartRate, zones, w.useGps, w.distanceMeters,
            w.distanceFromSteps, target, session.zoneSeconds.value.toList(), confirming, session.kcal.value?.active,
        )
        val actions = WorkoutNotification.actions(r.paused, confirming, ::service)
        if (!liveUpdatesShown()) return WorkoutNotification.build(this, card, actions, openApp()).unsaved(r).build()
        val color = when {
            r.paused || !connected -> WorkoutColors.PAUSED_GREY
            zone != null -> WorkoutColors.zone(zone)
            else -> WorkoutColors.PAUSED_GREY
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
                    style.setProgressSegments(WorkoutColors.ZONES.map { NotificationCompat.ProgressStyle.Segment(ZONE_SPAN).setColor(it) })
                    if (bpm != null && !r.paused) {
                        val span = ZONE_SPAN * WorkoutColors.ZONES.size
                        style.setProgress((WorkoutRecorder.zonePosition(bpm, r.maxHeartRate, zones) * span).toInt())
                        WorkoutRecorder.liveZone(bpm, r.maxHeartRate, zones)?.let { z -> style.setProgressTrackerIcon(tracker(z)) }
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
            .apply { if (r.paused) setShortCriticalText("Paused") }
            // In the header, after the running time.
            .setSubText(card.kcal)
            .unsaved(r)
            .setContentIntent(openApp())
            .setShowWhen(true)
            .setUsesChronometer(!r.paused)
            .setWhen(if (r.paused) now else now - active * 1000)
        actions.forEach(b::addAction)
        return b.build()
    }

    /** In the header: the database refused the last save, so a process death now would lose some of the workout. */
    private fun NotificationCompat.Builder.unsaved(r: WorkoutState.Recording): NotificationCompat.Builder =
        apply { if (r.saveFailed) setSubText(NOT_SAVED) }

    /** "End workout?": a stray tap in a pocket can't end it. It falls back after [CONFIRM_MS]. */
    private fun NotificationCompat.Builder.endConfirmation(): NotificationCompat.Builder =
        setContentText(NotificationCopy.END_QUESTION).apply { WorkoutNotification.actions(paused = false, confirming = true, ::service).forEach(::addAction) }

    /**
     * Once a workout is saved, a quiet summary with View, and Undo when it ended on its own (the stop or car question)
     * and the time after was kept. It goes when the summary in the app is closed.
     */
    private suspend fun watchEnded(workoutState: StateFlow<WorkoutState>, dao: HealthDao) {
        val nm = getSystemService(NotificationManager::class.java)
        var previous: WorkoutState? = null
        workoutState.collect { st ->
            val was = previous
            previous = st
            when {
                st is WorkoutState.Ended && was is WorkoutState.Recording -> {
                    val w = dao.appWorkoutOnce(st.start) ?: return@collect
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
            Units.workoutClock(duration),
            w.distanceMeters?.let { Units.distance(it, Units.Precision.Workout, w.distanceFromSteps) },
            w.averageHeartRate?.let { "$it bpm avg" },
        ).joinToString(", ")
        val auto = w.endReason == org.openstrap.app.data.db.AppWorkout.END_AUTO || w.endReason == org.openstrap.app.data.db.AppWorkout.END_CAR
        val cut = w.recordedEnd?.let { it - end }?.takeIf { auto && it >= 60 }
        val time = TimeOfDay.format(end, java.time.ZoneId.systemDefault())
        val text = if (cut != null) "$facts\nEnded at $time, when you'd stopped. The ${cut / 60} min after were left out." else facts
        val b = NotificationCompat.Builder(this, SAVED_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle("${Workouts.sportName(w.sportType)} saved")
            .setContentText(facts)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(NotificationCompat.Action(0, "View", openApp()))
        if (cut != null) b.addAction(
            NotificationCompat.Action(
                0, "Undo",
                PendingIntent.getService(
                    this, REQUEST_UNDO, Intent(this, StrapService::class.java).setAction(ACTION_UNDO).putExtra(EXTRA_START, w.start),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ),
        )
        return b.build()
    }

    /** Strength on the lock screen: the set or the rest counting down, its progress, and the next step. */
    private fun strengthNotification(r: WorkoutState.Recording, bpm: Int?, connected: Boolean, confirming: Boolean, zones: HeartZones): Notification {
        val now = System.currentTimeMillis()
        val done = r.sets.count { it.end != null }
        val until = r.restUntil
        val title = when {
            r.inSet -> "Set ${r.sets.size}"
            until != null -> "Resting after set $done"
            done == 0 -> "${Workouts.sportName(r.workout.sportType)}, ready for set 1"
            else -> "Rest over, set ${done + 1} next"
        }
        val b = NotificationCompat.Builder(this, WorkoutNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(title)
            .setContentText(
                if (!connected) "${WorkoutWords.STRAP_GONE}. Still recording."
                else listOfNotNull(bpm?.let { "$it bpm" }, r.workout.setsTarget?.let { "$done of $it sets" }).joinToString(", "),
            )
            .setColor(if (connected) WorkoutColors.zone(WorkoutRecorder.liveZone(bpm, r.maxHeartRate, zones)) else WorkoutColors.PAUSED_GREY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
            .unsaved(r)
            .setContentIntent(openApp())
        if (confirming) return b.endConfirmation().build()
        if (until != null) {
            val start = r.sets.lastOrNull()?.end ?: until
            // The rest counts down on its own; the bar fills toward its end.
            b.setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true).setWhen(until * 1000)
                .setProgress((until - start).toInt().coerceAtLeast(1), (now / 1000 - start).toInt().coerceIn(0, (until - start).toInt()), false)
            b.addAction(NotificationCompat.Action(0, "Start set ${r.sets.size + 1}", service(ACTION_START_SET)))
            b.addAction(NotificationCompat.Action(0, "+30 s", service(ACTION_EXTEND)))
        } else if (r.inSet) {
            b.setUsesChronometer(true).setShowWhen(true).setWhen(r.sets.last().start * 1000)
            b.addAction(NotificationCompat.Action(0, "Set done", service(ACTION_SET_DONE)))
        } else {
            b.addAction(NotificationCompat.Action(0, "Start set ${r.sets.size + 1}", service(ACTION_START_SET)))
        }
        b.addAction(NotificationCompat.Action(0, "End", service(ACTION_END_ASK)))
        return b.build()
    }

    /** "Looks like you've stopped": the same notification, asking whether to end where the wearer stopped. */
    private fun stoppedNotification(r: WorkoutState.Recording, at: Long): Notification {
        val time = TimeOfDay.format(at, java.time.ZoneId.systemDefault())
        val sport = Workouts.sportName(r.workout.sportType).lowercase()
        val text = if (r.inCar) "You last moved at $time. End the $sport there, so the drive isn't counted?"
        else "Your heart rate has been back near resting since $time. End the $sport at $time?"
        return NotificationCompat.Builder(this, WorkoutNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(if (r.inCar) "Looks like you're in a car" else "Looks like you've stopped")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText("End?")
            .setContentIntent(openApp())
            .addAction(NotificationCompat.Action(0, "End at $time", service(ACTION_END_AT)))
            .addAction(NotificationCompat.Action(0, "Keep going", service(ACTION_KEEP)))
            .build()
    }

    /** "Looks like a workout": record it from where the effort began, or not. Swiping it away is "Not now". */
    private fun offerNotification(o: org.openstrap.app.data.AutoStart.Offer): Notification {
        val time = TimeOfDay.format(o.start, java.time.ZoneId.systemDefault())
        val sport = Workouts.sportName(o.sportType).lowercase()
        val text = "Your heart rate has been up since $time, ${o.averageHeartRate} bpm on average. Record a $sport from $time?"
        return NotificationCompat.Builder(this, OFFER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle("Looks like a workout")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .setDeleteIntent(service(ACTION_DECLINE))
            .addAction(NotificationCompat.Action(0, "Record", service(ACTION_RECORD)))
            .addAction(NotificationCompat.Action(0, "Not now", service(ACTION_DECLINE)))
            .build()
    }

    /** The app's gauge marker in each zone's colour: a solid dot in a soft halo of itself, drawn once per zone. */
    private val trackers = arrayOfNulls<androidx.core.graphics.drawable.IconCompat>(WorkoutColors.ZONES.size)

    private fun tracker(zone: Int): androidx.core.graphics.drawable.IconCompat = trackers[zone] ?: run {
        val d = resources.displayMetrics.density
        val size = (18 * d).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val color = WorkoutColors.ZONES[zone]
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
        private const val NOT_SAVED = "Not saved yet"
        private const val FIGURES_REFRESH_MS = 30 * 60_000L
        /** The red dot in the "OS." logo, the app's heart colour: zone 5's red. */
        private const val BRAND_DOT = WorkoutColors.ZONE_5
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

        /** Whether the service is up; a start then needn't (and, once swiped away, mustn't) post its notification. */
        @Volatile var running = false
            private set

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

    }
}
