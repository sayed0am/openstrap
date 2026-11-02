package org.openstrap.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.LocalDate

/**
 * Something outside the screens asks the app to show: a notification, a widget, the tile or a launcher shortcut. The
 * activity reads it from the intent's extras and the app root applies it (audit A7).
 */
sealed interface NavRequest {
    /** The ready screen for [sport]: a launcher shortcut, the Quick Settings tile, the Start widget, the service. */
    data class OpenReady(val sport: Int) : NavRequest

    /** Choose with every sport: the Start widget's All sports. */
    data object OpenChoose : NavRequest

    /** The strap workout starting at [start] on the Activity tab: an auto-detected workout's notification. */
    data class OpenWorkout(val start: Long) : NavRequest

    /** The screen [route] names ([org.openstrap.app.ui.dashboard.Detail.fromRoute]), for [day] when it is set. */
    data class OpenDetail(val route: String, val day: LocalDate?) : NavRequest

    /** Today at its root, on today: the Moment widget. */
    data object OpenToday : NavRequest

    /** The Strap page over the tab showing: a widget's strap status line. */
    data object OpenStrap : NavRequest

    /** Last week's recap: the weekly recap notification. */
    data object OpenRecap : NavRequest

    /** The Updates page: an update notification. */
    data object OpenUpdates : NavRequest

    /** The live workout screen, even when it was tucked away: the workout notification, the tile, the Start widget. */
    data object ShowWorkout : NavRequest
}

/**
 * The requests on their way to the app root. It is the activity's, so a request sent before the first frame waits for
 * it, and one sent just before a fold or a rotation outlives the old activity. Each is received once.
 */
class NavigationRequests : ViewModel() {
    private val channel = Channel<NavRequest>(Channel.UNLIMITED)

    /** Every request in the order sent, each received once. */
    val requests: Flow<NavRequest> = channel.receiveAsFlow()

    fun send(request: NavRequest) {
        channel.trySend(request)
    }
}
