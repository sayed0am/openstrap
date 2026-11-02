package org.openstrap.app.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openstrap.app.data.HeartZones

/**
 * Your heart rate zones as stored, for the recording workout and its notification, which read a zone with every
 * reading and have no settings flow of their own yet. It only ever follows what's stored ([follow], from the app's
 * start), so it can't run ahead of the disk. The screens get theirs from the root and the repositories from the
 * settings flow, each handing them on to the pure zone functions in data.
 */
object HeartZonesInUse {
    private val live = MutableStateFlow(HeartZones.STANDARD)

    val current: StateFlow<HeartZones> = live

    /** Keeps [current] to [stored] for as long as [scope] lives. */
    fun follow(stored: Flow<HeartZones>, scope: CoroutineScope): Job = scope.launch { stored.collect { live.value = it } }
}
