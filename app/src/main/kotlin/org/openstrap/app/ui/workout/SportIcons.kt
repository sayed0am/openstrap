package org.openstrap.app.ui.workout

import androidx.compose.ui.graphics.vector.ImageVector
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.R
import org.openstrap.app.tr

/** Each sport's icon, and the few the workout screens use, all from [AppIcons] (Hugeicons); Icon() tints them. */
internal object SportIcons {
    val Run: ImageVector get() = AppIcons.WorkoutRun
    val Walk: ImageVector get() = AppIcons.Walking
    val Strength: ImageVector get() = AppIcons.Dumbbell01
    /** A pennant, for marking a lap. */
    val Lap: ImageVector get() = AppIcons.Flag03

    private val RUN = setOf(1, 2, 11)
    private val WALK = setOf(3, 15, 40, 215, 224, 225)
    private val STRENGTH = setOf(24, 50, 52, 222, 226)

    fun of(sportType: Int): ImageVector = when (sportType) {
        11 -> AppIcons.Mountains
        in RUN -> Run
        15, 224 -> AppIcons.PersonSimpleHike
        215 -> AppIcons.Dog
        225 -> AppIcons.Backpack
        in WALK -> Walk
        108, 57, 58 -> AppIcons.Stairs
        9 -> AppIcons.Steps
        4 -> AppIcons.PersonSimpleBike
        8, 143 -> AppIcons.Bicycle
        6 -> AppIcons.PersonSimpleSwim
        7 -> AppIcons.Waves
        23 -> AppIcons.Boat
        49 -> AppIcons.Timer
        130, 51, 109 -> AppIcons.Lightning
        222 -> AppIcons.FlagCheckered
        50 -> AppIcons.Barbell
        in STRENGTH -> Strength
        60 -> AppIcons.FlowerLotus
        61 -> AppIcons.PersonSimpleTaiChi
        53, 55 -> AppIcons.PersonArmsSpread
        76 -> AppIcons.MusicNotes
        17, 205 -> AppIcons.TennisBall
        89, 204 -> AppIcons.PingPong
        92 -> AppIcons.PersonSimpleThrow
        18 -> AppIcons.SoccerBall
        85 -> AppIcons.Basketball
        88 -> AppIcons.Volleyball
        83 -> AppIcons.Golf
        97 -> AppIcons.BoxingGlove
        12 -> AppIcons.PersonSimpleSki
        70, 10 -> AppIcons.Mountains
        else -> AppIcons.Heartbeat
    }

    /** The sport picker's search field. */
    val Search: ImageVector get() = AppIcons.Search01

    /** The GPS row, and the mark on a sport done only outdoors. */
    val Location: ImageVector get() = AppIcons.Location01

}
