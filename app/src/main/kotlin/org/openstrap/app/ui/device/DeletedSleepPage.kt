package org.openstrap.app.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.dashboard.SleepCopy
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageRows
import org.openstrap.app.ui.theme.AppIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Deleted sleep (edit spec 4.4), from the You page's Your data while there's something in it: each night or nap deleted
 * in the last 7 days, its day, its times and the days left, with Restore. After 7 days they go for good.
 */
@Composable
fun DeletedSleepPage(
    deleted: List<SleepEdits.Resolved>,
    contentPadding: PaddingValues,
    onRestore: (SleepSession) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis() / 1000
    fun clock(t: Long) = Instant.ofEpochSecond(t).atZone(zone).format(CLOCK)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Deleted sleep", onBack) }
        item { Text(SleepCopy.DELETED_INTRO, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.padding(horizontal = 4.dp)) }
        item {
            PageRows {
                deleted.forEachIndexed { i, r ->
                    // A night is named by the evening it began, a nap by its own day.
                    val day = if (r.strap.nap) Instant.ofEpochSecond(r.strap.start).atZone(zone).toLocalDate()
                    else SleepHistory.nightOf(r.strap.end, zone).minusDays(1)
                    PageRow(
                        SleepCopy.deletedName(r.strap.nap, day.format(DAY)),
                        SleepCopy.deletedLine(clock(r.start), clock(r.end), r.edit?.let { SleepEdits.daysLeft(it, now) } ?: SleepEdits.KEEP_DAYS),
                        below = true, divider = i > 0, icon = AppIcons.Moon,
                    ) { PageButton("Restore", { onRestore(r.strap) }, icon = AppIcons.ArrowCounterClockwise) }
                }
            }
        }
        item { PageNote(SleepCopy.DELETED_FOOT) }
    }
}
