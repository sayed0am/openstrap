package org.openstrap.app.device

import android.content.Context
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.WidgetKind

/**
 * The old way in, kept until the wave 4 merge: the widgets now watch what they show
 * ([org.openstrap.app.widget.WidgetUpdater.start]), so nobody needs to tell them. Forwards to the app's updater.
 */
@Deprecated("The widgets watch the data now: container.widgets")
object WidgetUpdater {
    fun changed(context: Context, kinds: Set<WidgetKind>) {
        (context.applicationContext as? OpenStrapApp)?.container?.widgets?.changed(kinds)
    }

    fun changedAll(context: Context) {
        (context.applicationContext as? OpenStrapApp)?.container?.widgets?.changedAll()
    }
}
