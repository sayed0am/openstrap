package org.openstrap.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.R
import org.openstrap.app.tr

val SLEEP_NAMES = mapOf(
    SleepStage.AWAKE to tr(R.string.screen_parts_awake),
    SleepStage.REM to tr(R.string.screen_parts_rem),
    SleepStage.LIGHT to tr(R.string.common_light),
    SleepStage.DEEP to tr(R.string.screen_parts_deep),
)

/** Counts taps on the tab that's showing; each new count scrolls the [ScreenList]s on that tab to the top. */
val LocalScrollToTop = androidx.compose.runtime.compositionLocalOf { 0 }

/** Shared scrolling list with consistent gutters for all dashboard tabs. */
@Composable
fun ScreenList(
    contentPadding: PaddingValues,
    state: LazyListState = rememberLazyListState(),
    /** Space between the screen edge and the cards. */
    gutter: Dp = 16.dp,
    content: LazyListScope.() -> Unit,
) {
    // A tap on the tab that's showing scrolls whatever screen is open on it back to the top (spec 3.3).
    val toTop = LocalScrollToTop.current
    var handled by remember { mutableIntStateOf(toTop) }
    LaunchedEffect(toTop) { if (toTop != handled) { handled = toTop; state.animateScrollToItem(0) } }
    // On a wide screen a page keeps to one centred column (spec 2.4 Unfolded); the whole width still scrolls it.
    val column = org.openstrap.app.ui.glow.LocalPageColumn.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = org.openstrap.app.ui.glow.GlowLayout.pageSide(maxWidth, column, gutter)
        // The status bar gets an opaque strip so cards don't scroll underneath the clock.
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
            state = state,
            contentPadding = PaddingValues(
                start = side, end = side,
                top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

fun List<ChartPoint>.avg(): Int? = if (isEmpty()) null else Math.round(map { it.y }.average()).toInt()
