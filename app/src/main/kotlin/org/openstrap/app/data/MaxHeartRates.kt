package org.openstrap.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.UserProfile

/**
 * The one max heart rate the zones go by, for the workouts list and Ready, a recording workout and the Heart rate
 * zones page alike: [RecoveryEngine.maxHeartRateExplained]'s rule, so a tested value wins, else your age (or the
 * strap's value, or 190) raised by peaks over the 90-day load window. [current] is worked out again on each profile
 * change while anything reads it, so the 90-day read runs once for all of them. [now] reads it afresh where the old
 * readers did (a workout starting, the zones page opening) and hands what it found on to [current].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaxHeartRates(
    private val dao: HealthDao,
    private val profile: Flow<UserProfile>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // No replay: a fresh read from before a profile change mustn't come back over the newer one.
    private val fresh = MutableSharedFlow<MaxHeartRate>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Max heart rate as of now, null until it's first worked out. */
    val current: StateFlow<MaxHeartRate?> = merge(profile.mapLatest { work(it) }, fresh)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Max heart rate worked out now from the stored profile, for a suspend reader that needs it once. */
    suspend fun now(): MaxHeartRate = work(profile.first()).also { fresh.tryEmit(it) }

    private suspend fun work(p: UserProfile): MaxHeartRate = withContext(Dispatchers.Default) { RecoveryEngine(dao).maxHeartRateExplained(p) }
}
