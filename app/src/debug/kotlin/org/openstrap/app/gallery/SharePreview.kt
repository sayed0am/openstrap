package org.openstrap.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import org.openstrap.app.data.Route
import org.openstrap.app.ui.glow.StoryCard
import org.openstrap.app.ui.glow.StoryCardView
import org.openstrap.app.ui.glow.StoryChip
import org.openstrap.app.ui.glow.StoryStat

/**
 * Every share card kind with fixed figures, at half size two to a row (`--es section share`), to check the cards'
 * shared layout (mockups/share-cards-system.html) on an emulator, which has no workouts to share.
 */
// Sample figures, as the mockup's.
@Suppress("MagicNumber")
@Composable
internal fun SharePreview() {
    val best = StoryChip("Fastest 5 km, 26:41", best = true, met = false)
    val goal = StoryChip("Weekly goal, 3 of 4", best = false, met = false)
    val met = StoryChip("Weekly goal, 4 of 4", best = false, met = true)
    val cards: List<Pair<StoryCard, List<Route.Point>>> = listOf(
        StoryCard.Run(
            "Nov 6", "Run", "5.21", "km",
            listOf(StoryStat("27:48", null, "Time"), StoryStat("5:20", "/km", "Pace"), StoryStat("152", null, "Avg bpm")), listOf(best),
        ) to loop(),
        StoryCard.Run(
            "Nov 3", "Walk", "4.80", "km",
            listOf(StoryStat("48:24", null, "Time"), StoryStat("10:05", "/km", "Pace"), StoryStat("112", null, "Avg bpm")), emptyList(),
        ) to emptyList(),
        StoryCard.Lift(
            "Nov 5", "Strength", "4,820", "kg",
            listOf(
                "Bench press" to "5 × 5, top 80 kg", "Overhead press" to "4 × 8, top 40 kg", "Barbell row" to "4 × 10, top 60 kg",
                "Dips" to "3 sets",
            ),
            listOf(StoryStat("52", "min", "Time"), StoryStat("16", null, "Sets"), StoryStat("128", null, "Avg bpm")), listOf(goal),
        ) to emptyList(),
        StoryCard.Indoor(
            "Nov 6", "Spin bike", "42:18", listOf(180, 540, 1080, 620, 118),
            listOf(StoryStat("141", null, "Avg bpm"), StoryStat("386", null, "Active kcal"), StoryStat("168", null, "Peak bpm")),
            listOf(StoryChip("Longest spin, 42 min", best = true, met = false)),
        ) to emptyList(),
        StoryCard.Rounds(
            "Nov 6", "HIIT", "8", "of 8 rounds", listOf(163, 168, 171, 173, 176, 178, 175, 177), 190,
            listOf(StoryStat("178", null, "Peak bpm"), StoryStat("12", "min", "Work"), StoryStat("24:10", null, "Time")), listOf(met),
        ) to emptyList(),
        StoryCard.Calm(
            "Nov 6", "Yoga", "20", "min", List(40) { i -> 66f + 18f * kotlin.math.exp(-i / 11f) + sin(i * 1.7f) * 1.6f },
            listOf(StoryStat("84", null, "Bpm at start"), StoryStat("66", null, "Bpm at end")), emptyList(),
        ) to emptyList(),
        StoryCard.Game(
            "Nov 4", "Football", "64", "min",
            listOf(StoryStat("132", null, "Load"), StoryStat("181", null, "Peak bpm"), StoryStat("148", null, "Avg bpm")), emptyList(),
        ) to emptyList(),
        StoryCard.Week(
            "Oct 27 to Nov 2", "My week", "5", "workouts, goal 4",
            listOf("M" to true, "T" to true, "W" to false, "T" to true, "F" to true, "S" to false, "S" to true),
            listOf(StoryStat("4:12", null, "Hours"), StoryStat("18.4", "km", "Distance"), StoryStat("2,310", null, "Active kcal")),
            listOf(StoryChip("6 weeks in a row", best = false, met = true), best),
        ) to emptyList(),
    )
    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())
            .padding(WindowInsets.systemBars.asPaddingValues()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (card, route) ->
                    Box(Modifier.size(180.dp, 320.dp).clipToBounds()) {
                        StoryCardView(
                            card, null, route,
                            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).graphicsLayer {
                                scaleX = 0.5f
                                scaleY = 0.5f
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A park loop with a lead in, about 5 km, for the run's route. */
@Suppress("MagicNumber")
private fun loop(): List<Route.Point> = List(120) { i ->
    val t = i / 119.0 * 2 * Math.PI
    val r = 1 + 0.18 * sin(3 * t + 0.6) + 0.08 * sin(7 * t)
    Route.Point(1_792_000_000L + i * 14L, 51.5 + 0.006 * r * sin(t), -0.1 + 0.01 * r * cos(t), 5f)
}
