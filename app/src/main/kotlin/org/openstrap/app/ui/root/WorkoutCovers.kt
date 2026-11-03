package org.openstrap.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.openstrap.app.device.WorkoutState

/**
 * Whether the workout layer hides everything under it: the live screen (not when tucked into its capsule), the summary,
 * or Choose and Ready. The tabs can't be seen then, so their glows hold still like a tab off screen.
 */
internal fun workoutCovers(state: WorkoutState, minimized: Boolean, overlay: WorkoutOverlay): Boolean = when (state) {
    is WorkoutState.Recording -> !minimized
    is WorkoutState.Ended -> true
    WorkoutState.Idle -> overlay != WorkoutOverlay.None
}

/**
 * [workoutCovers] as a lambda that reads snapshot state, so a glow waiting for it to turn false wakes when the layer
 * goes. The lambda stays the same one, so handing it to a static local never recomposes what's under it.
 */
@Composable
internal fun rememberWorkoutCovers(state: StateFlow<WorkoutState>, minimized: StateFlow<Boolean>, nav: AppNavState): () -> Boolean {
    val s = state.collectAsStateWithLifecycle()
    val m = minimized.collectAsStateWithLifecycle()
    return remember(s, m, nav) {
        val covers = derivedStateOf { workoutCovers(s.value, m.value, nav.workout) }
        val read: () -> Boolean = { covers.value }
        read
    }
}
