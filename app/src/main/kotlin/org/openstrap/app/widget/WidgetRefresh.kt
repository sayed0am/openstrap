package org.openstrap.app.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openstrap.app.BuildConfig
import org.openstrap.app.ui.glow.GlowMood

/**
 * Tells a running widget its data changed. Glance calls a widget's `provideGlance` once per session and recomposes
 * the running session on an update, so a widget loads in `provideGlance` and loads again each time its kind's
 * version moves on. [org.openstrap.app.device.WidgetUpdater] bumps it before every update.
 */
object WidgetRefresh {
    private val versions = WidgetKind.entries.associateWith { MutableStateFlow(0) }

    fun version(kind: WidgetKind): StateFlow<Int> = versions.getValue(kind)

    fun bump(kind: WidgetKind) {
        versions.getValue(kind).value++
    }

    private val resizes = WidgetKind.entries.associateWith { MutableStateFlow(0) }

    /** Moves on each time the launcher gives a [kind] widget a new size, so its sessions lay out again. */
    fun resized(kind: WidgetKind): StateFlow<Int> = resizes.getValue(kind)

    fun resize(kind: WidgetKind) {
        resizes.getValue(kind).value++
    }

    /**
     * Debug builds: the mood to light widgets in instead of the moment's, for the widget gallery; null follows the
     * moment. Release builds never read it.
     */
    @Volatile
    var debugMood: GlowMood? = null

    /** [debugMood] where it applies. */
    val moodOverride: GlowMood? get() = if (BuildConfig.DEBUG) debugMood else null

    /** Debug builds: the moment the Moment widget shows instead of the clock's, for the gallery; null follows it. */
    @Volatile
    var debugMoment: org.openstrap.app.data.Moment? = null

    /**
     * Debug builds: the widgets the Moment widget takes as placed for the duplicate rule (spec 3.1), for the gallery;
     * null reads the launcher's.
     */
    @Volatile
    var debugPlaced: Set<WidgetKind>? = null

    /**
     * Debug builds: the strap status line every widget shows instead of the real one, for the gallery: "notpaired",
     * "low", "stale" (4 hours ago), "yesterday", "week" (3 days ago), "syncing", or "none" for no line; null reads
     * the strap.
     */
    @Volatile
    var debugStrap: String? = null

    /** [debugStrap] where it applies. */
    val statusOverride: String? get() = if (BuildConfig.DEBUG) debugStrap else null

    /** [statusOverride]'s status at [now] (epoch seconds); null for "none" or a name it doesn't know. */
    fun debugStatus(now: Long): StrapStatus? = when (statusOverride?.lowercase()) {
        "notpaired" -> StrapStatus.NotPaired
        "low" -> StrapStatus.LowBattery(12)
        "stale" -> StrapStatus.StaleSync(now - 4 * 3600)
        "yesterday" -> StrapStatus.StaleSync(now - 86_400)
        "week" -> StrapStatus.StaleSync(now - 3 * 86_400)
        "syncing" -> StrapStatus.Syncing
        else -> null
    }

    /** [debugMoment] and [debugPlaced] where they apply. */
    val momentOverride: org.openstrap.app.data.Moment? get() = if (BuildConfig.DEBUG) debugMoment else null
    val placedOverride: Set<WidgetKind>? get() = if (BuildConfig.DEBUG) debugPlaced else null
}
