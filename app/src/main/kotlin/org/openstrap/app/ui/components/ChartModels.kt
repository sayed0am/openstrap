package org.openstrap.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** A point on a time axis: x is epoch seconds, y is the value. */
data class ChartPoint(val x: Long, val y: Float)

/**
 * Scrubbing for every chart: drag sideways to read values straight away, or press and hold first (the
 * older gesture). Vertical drags still scroll the list and a plain tap still reaches the card.
 */
internal fun Modifier.scrub(key: Any?, onScrub: (Float?) -> Unit): Modifier = this
    .pointerInput(key) {
        detectHorizontalDragGestures(
            onDragStart = { onScrub(it.x) },
            onDragEnd = { onScrub(null) },
            onDragCancel = { onScrub(null) },
        ) { change, _ -> change.consume(); onScrub(change.position.x) }
    }
    .pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onScrub(it.x) },
            onDrag = { change, _ -> change.consume(); onScrub(change.position.x) },
            onDragEnd = { onScrub(null) },
            onDragCancel = { onScrub(null) },
        )
    }

/** A day on a weekly trend chart. */
data class TrendPoint(val label: String, val value: Float?)

/** A sleep stage interval for the hypnogram. */
data class StageSpan(val start: Long, val end: Long, val stage: Int)
