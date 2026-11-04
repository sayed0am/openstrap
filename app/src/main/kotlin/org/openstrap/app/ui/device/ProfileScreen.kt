package org.openstrap.app.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import org.openstrap.app.ui.glow.LocalPageColumn
import org.openstrap.app.ui.components.PinnedFade
import org.openstrap.app.ui.components.pinnedFade
import org.openstrap.app.ui.dashboard.ScreenList
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.settings.Units
import org.openstrap.app.data.settings.UserProfile
import androidx.compose.foundation.layout.height
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageConfirm
import org.openstrap.app.ui.glow.PageNumberField
import org.openstrap.app.ui.glow.PageHeader
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PagePanel
import org.openstrap.app.ui.glow.PageRow
import org.openstrap.app.ui.glow.PageSwitchRow
import java.time.LocalDate

/** One line for the settings row: "34 years, male, 72 kg, 178 cm", or a prompt when empty. */
fun UserProfile.summary(): String =
    if (isEmpty) "Add age and sex for accurate effort and recovery"
    else listOfNotNull(
        age()?.let { "$it years" },
        sex?.name?.lowercase(),
        weightKg?.let { Units.formatWeight(it, usesImperial) },
        heightCm?.let { Units.formatHeight(it, usesImperial) },
    ).joinToString(", ")

/**
 * Age, sex, weight and height. Age sets the maximum heart rate (and so every effort calculation);
 * sex picks the training-load curve. Kept on this phone only. A full page over the You list (or beside it, when
 * wide); back leaves without saving, asking first if anything was changed.
 */
@Composable
fun ProfileScreen(profile: UserProfile, contentPadding: PaddingValues, onSave: (UserProfile) -> Unit, onBack: () -> Unit, onHeartZones: () -> Unit = {}) {
    var age by rememberSaveable { mutableStateOf(profile.age()?.toString().orEmpty()) }
    var sex by rememberSaveable { mutableIntStateOf(when (profile.sex) { UserProfile.Sex.Female -> 0; UserProfile.Sex.Male -> 1; null -> 2 }) }
    var imperial by rememberSaveable { mutableStateOf(profile.usesImperial) }
    // Entry text in the chosen units; converting on a unit switch keeps what the user typed.
    var weight by rememberSaveable { mutableStateOf(profile.weightKg?.let { formatNumber(if (imperial) Units.kgToLb(it) else it) }.orEmpty()) }
    var heightCm by rememberSaveable { mutableStateOf(profile.heightCm?.toString().orEmpty()) }
    val (ft0, in0) = profile.heightCm?.let { Units.cmToFeetInches(it) } ?: (null to null)
    var feet by rememberSaveable { mutableStateOf(ft0?.toString().orEmpty()) }
    var inches by rememberSaveable { mutableStateOf(in0?.toString().orEmpty()) }
    var activity by rememberSaveable { mutableIntStateOf(profile.activityLevel ?: -1) }
    var betaBlocker by rememberSaveable { mutableStateOf(profile.betaBlocker) }
    var pregnant by rememberSaveable { mutableStateOf(profile.pregnant) }
    var sleepNeed by rememberSaveable { mutableStateOf(profile.sleepNeedMinutes?.let { formatNumber(it / 60f) }.orEmpty()) }

    val ageValue = age.trim().toIntOrNull()?.takeIf { it in 10..100 }
    val weightNumber = weight.trim().replace(',', '.').toFloatOrNull()
    val weightValue = weightNumber?.let { if (imperial) Units.lbToKg(it) else it }?.takeIf { it in 25f..300f }
    val heightValue = if (imperial) {
        val f = feet.trim().toIntOrNull()
        val i = inches.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..11 }
        if (f != null && i != null) Units.feetInchesToCm(f, i).takeIf { it in 100..250 } else null
    } else heightCm.trim().toIntOrNull()?.takeIf { it in 100..250 }
    val heightBlank = if (imperial) feet.isBlank() && inches.isBlank() else heightCm.isBlank()
    // Hours, so "7.5" is 450 min; only 7-9 h is accepted (Recovery.SLEEP_NEED_MIN..MAX).
    val sleepNeedValue = sleepNeed.trim().replace(',', '.').toFloatOrNull()?.let { Math.round(it * 60) }
        ?.takeIf { it in org.openstrap.app.data.Recovery.SLEEP_NEED_MIN..org.openstrap.app.data.Recovery.SLEEP_NEED_MAX }
    val valid = (age.isBlank() || ageValue != null) && (weight.isBlank() || weightValue != null) && (heightBlank || heightValue != null) &&
        (sleepNeed.isBlank() || sleepNeedValue != null)

    fun switchUnits(toImperial: Boolean) {
        if (toImperial == imperial) return
        // Carry valid entries across so switching never loses what was typed.
        weightValue?.let { weight = formatNumber(if (toImperial) Units.kgToLb(it) else it) }
        heightValue?.let { cm ->
            heightCm = cm.toString()
            Units.cmToFeetInches(cm).let { (f, i) -> feet = f.toString(); inches = i.toString() }
        }
        imperial = toImperial
    }

    // What the fields held when the page opened, so back knows whether there is anything to lose.
    val initial = rememberSaveable { listOf(age, sex, imperial, weight, heightCm, feet, inches, activity, betaBlocker, pregnant, sleepNeed).joinToString("\u0000") }
    val dirty = listOf(age, sex, imperial, weight, heightCm, feet, inches, activity, betaBlocker, pregnant, sleepNeed).joinToString("\u0000") != initial
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val back: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(onBack = back)
    if (confirmDiscard) {
        PageConfirm(
            "Discard changes?", "Your profile changes haven't been saved.",
            confirm = "Discard", onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
            dismiss = "Keep editing",
        )
    }
    val save: () -> Unit = {
        onSave(
            // A copy, so fields this page doesn't edit are kept.
            profile.copy(
                birthYear = ageValue?.let { LocalDate.now().year - it },
                sex = when (sex) { 0 -> UserProfile.Sex.Female; 1 -> UserProfile.Sex.Male; else -> null },
                weightKg = weightValue,
                heightCm = heightValue,
                imperial = imperial,
                sleepNeedMinutes = sleepNeedValue,
                activityLevel = activity.takeIf { it in 0..4 },
                // Saving confirms the answers, so the fitness estimate's freshness checks restart.
                activityAnsweredAt = if (activity in 0..4) System.currentTimeMillis() / 1000 else null,
                bodyMeasuredAt = if (weightValue != null && heightValue != null) System.currentTimeMillis() / 1000 else null,
                betaBlocker = betaBlocker,
                pregnant = pregnant,
            ),
        )
    }

    // The keyboard shrinks the page rather than covering it, so a focused field scrolls into view above it;
    // while it's up the nav bar is hidden behind it and needs no room. Save is pinned at the bottom, over the
    // keyboard when it's up, like Start and Done on the workout pages (spec 6.3), and as wide as the fields: on a
    // wide screen both keep to the page's centred column (spec 2.4 Unfolded). Whether the keyboard takes room is
    // read from its inset, so a keyboard that floats (as it can on an unfolded screen) leaves Save above the tab bar.
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val bottom = if (keyboard) 0.dp else contentPadding.calculateBottomPadding()
        val side = GlowLayout.pageSide(maxWidth, LocalPageColumn.current, GlowLayout.Gutter)
        // The list ends clear of Save and the fade behind it (ScreenList adds its own 24 dp).
        ScreenList(PaddingValues(bottom = PinnedFade.listClearance(bottom + 12.dp + SaveHeight, listEnd = 24.dp)), gutter = GlowLayout.Gutter) {
            item { PageHeader("Your profile", back) }
            item {
                PagePanel {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PageNote(
                            "Age sets your maximum heart rate, which every effort and recovery number depends on. " +
                                "All fields are optional and stay on this phone.",
                        )
                        PageNumberField(age, { age = it }, "Age", "years", age.isNotBlank() && ageValue == null)
                        // Sex as labelled segments like Units, not two bare symbols beside Age. Tapping the chosen one
                        // again clears it.
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PageNote("Sex, for energy, Load, fitness and Habit Age")
                            org.openstrap.app.ui.glow.GlowSegments(listOf("Female", "Male"), if (sex in 0..1) sex else -1, { i -> sex = if (sex == i) 2 else i })
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PageNote("Units")
                            org.openstrap.app.ui.glow.GlowSegments(listOf("Metric", "Imperial"), if (imperial) 1 else 0, { switchUnits(it == 1) })
                        }
                        PageNumberField(
                            weight, { weight = it }, "Weight", if (imperial) "lb" else "kg",
                            weight.isNotBlank() && weightValue == null, decimal = true,
                        )
                        if (imperial) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val wrong = !heightBlank && heightValue == null
                                PageNumberField(feet, { feet = it.take(1) }, "Feet", "ft", wrong, Modifier.weight(1f))
                                PageNumberField(inches, { inches = it.take(2) }, "Inches", "in", wrong, Modifier.weight(1f))
                            }
                        } else {
                            PageNumberField(heightCm, { heightCm = it }, "Height", "cm", !heightBlank && heightValue == null)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PageNote("Usual activity, for the fitness estimate")
                            // Tapping the chosen level again clears it.
                            org.openstrap.app.ui.glow.GlowSegments((1..5).map { "$it" }, activity, { i -> activity = if (activity == i) -1 else i })
                            PageNote(if (activity in 0..4) org.openstrap.app.data.Vo2max.ACTIVITY_LEVELS[activity] else "Pick the level that best matches a usual week.")
                        }
                        PageSwitchRow("Taking beta-blockers", null, betaBlocker, { betaBlocker = it }, divider = false)
                        PageSwitchRow("Pregnant", null, pregnant, { pregnant = it }, divider = false)
                        // Your max is set on the Heart rate zones page, with the zones that follow it.
                        PageRow(
                            org.openstrap.app.ui.workout.HeartZonesCopy.MAX_ROW, org.openstrap.app.ui.workout.HeartZonesCopy.profileLine(profile.maxHeartRate),
                            onClick = onHeartZones, below = true, divider = false,
                        )
                        PageNumberField(
                            sleepNeed, { sleepNeed = it }, "Sleep need (7 to 9, blank for your age's default)", "h",
                            sleepNeed.isNotBlank() && sleepNeedValue == null, decimal = true,
                        )
                    }
                }
            }
        }
        // Black from the bottom of the screen up to Save, fading out above it, so the page scrolls away under it.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().pinnedFade().padding(start = side, end = side, bottom = bottom + 12.dp)) {
            PageButton("Save", save, Modifier.fillMaxWidth().height(SaveHeight), enabled = valid, primary = true)
        }
    }
}

/** The pinned Save button's height, as Start on Ready. */
private val SaveHeight = 56.dp

/** 72.0 -> "72", 158.7 -> "158.7". */
private fun formatNumber(v: Float): String = "%.1f".format(java.util.Locale.ROOT, v).removeSuffix(".0")
