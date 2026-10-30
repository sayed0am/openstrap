package org.openstrap.app.ui.glow.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Route
import org.openstrap.app.ui.glow.*

/**
 * The live route in zone colours with the heart rate on a chip in the zone's colour, so it's never hidden (pattern
 * D5), then the GPS accuracy and "route only" (no tiles).
 */
@Composable
internal fun LiveMapPage(
    route: List<Route.Point>,
    history: List<Pair<Long, Int>>,
    maxHr: Int,
    routeOnly: Boolean,
    bpm: Int?,
    color: Color,
    paused: Boolean,
    modifier: Modifier,
    onRouteOnly: (Boolean) -> Unit,
) {
    val stretches = remember(route.size, history.size) { Route.byZone(route, history, maxHr) }
    // The whole page is the map; what its tiles reveal is said in the ready screen's (i).
    Box(modifier.fillMaxWidth().clip(GlowLayout.CardShape).background(Raised)) {
        // Where you are now, from the latest fix good enough to count, even while standing still.
        val here = route.lastOrNull { it.accuracy <= Route.MAX_ACCURACY_M }
        WorkoutMap(stretches, routeOnly, Modifier.fillMaxSize(), here)
        BpmChip(bpm, color, paused, Modifier.align(Alignment.TopStart).padding(16.dp))
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val acc = route.lastOrNull()?.accuracy
            Pill(if (acc == null || acc > 1000) "Finding GPS…" else "GPS ±${acc.toInt()} m", if (acc != null && acc <= Route.MAX_ACCURACY_M) TrendGood else TrendBad)
            Pill(if (routeOnly) "Route only" else "Map", null, onClick = { onRouteOnly(!routeOnly) })
        }
    }
}

/** The live heart rate on the map: a pill in the zone's [color], a dot beating at it, the number in dots. */
@Composable
internal fun BpmChip(bpm: Int?, color: Color, paused: Boolean, modifier: Modifier) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bpm != null) {
            PulseDot(Color.Black, bpm, 8.dp)
            Spacer(Modifier.width(10.dp))
            DotMatrixText("$bpm", dot = DotSize.Edge, color = Color.Black)
            Spacer(Modifier.width(6.dp))
            Text("bpm", color = Color.Black, style = MediumBody)
        } else Text(if (paused) "Paused" else "No heart rate yet", color = Color.Black, style = MediumBody)
    }
}
