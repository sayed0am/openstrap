package org.openstrap.app.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.openstrap.app.R
import org.openstrap.app.data.settings.CalorieGoal
import org.openstrap.app.data.settings.Goals
import org.openstrap.app.presentation.GoalCopy
import org.openstrap.app.presentation.Units
import org.openstrap.app.ui.theme.ControlSurface
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PageNumberField
import org.openstrap.app.ui.glow.PageSheet
import org.openstrap.app.ui.glow.RoundIconButton
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors

/**
 * Steps, calories, sleep and active hours to aim for each day, and workouts a week, in a sheet over the You page (spec 3.4, calories spec
 * 4.6). Every goal ring and "goal met" uses these, and the calorie goal goes to the strap. [suggestedCalories] is the
 * calorie goal your last four weeks suggest ([CalorieGoal.suggested]), null while it loads or under a week of full days. Cancel, or pulling the
 * sheet down, leaves them as they were.
 */
@Composable
fun GoalsSheet(goals: Goals, onSave: (Goals) -> Unit, onDismiss: () -> Unit, suggestedCalories: Int? = null) {
    var steps by rememberSaveable { mutableStateOf(goals.steps.toString()) }
    var calories by rememberSaveable { mutableIntStateOf(goals.calories) }
    var sleepH by rememberSaveable { mutableStateOf((goals.sleepMinutes / 60).toString()) }
    var sleepM by rememberSaveable { mutableStateOf((goals.sleepMinutes % 60).toString()) }
    var active by rememberSaveable { mutableStateOf(goals.activeHours.toString()) }
    var workouts by rememberSaveable { mutableIntStateOf(goals.workoutsPerWeek) }

    val stepsValue = steps.toIntOrNull()?.takeIf { it in Goals.STEPS }
    val sleepValue = sleepH.toIntOrNull()?.let { h -> sleepM.ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..59 }?.let { h * 60 + it } }
        ?.takeIf { it in Goals.SLEEP_MINUTES }
    val activeValue = active.toIntOrNull()?.takeIf { it in Goals.ACTIVE_HOURS }

    PageSheet(onDismiss, stringResource(R.string.you_goals_title), note = stringResource(R.string.you_goals_note)) {
        PageNumberField(
            steps, { steps = it }, stringResource(R.string.you_goals_steps_label), stringResource(R.string.you_goals_steps_unit),
            stepsValue == null,
        )
        CalorieGoalField(calories, { calories = it }, suggestedCalories)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.you_goals_sleep_label), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                style = GlowType.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PageNumberField(
                    sleepH, { sleepH = it.take(2) }, "", stringResource(R.string.you_unit_h), sleepValue == null, Modifier.weight(1f),
                )
                PageNumberField(
                    sleepM, { sleepM = it.take(2) }, "", stringResource(R.string.you_unit_min), sleepValue == null, Modifier.weight(1f),
                )
            }
        }
        PageNumberField(
            active, { active = it.take(2) }, stringResource(R.string.you_goals_active_label),
            stringResource(R.string.you_goals_active_unit),
            activeValue == null,
        )
        PageNote(stringResource(R.string.you_goals_active_note))
        WorkoutGoalField(workouts) { workouts = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(stringResource(R.string.you_action_cancel), onDismiss, Modifier.weight(1f))
            PageButton(
                stringResource(R.string.you_action_save),
                {
                    onSave(
                        goals.copy(
                            steps = stepsValue!!, sleepMinutes = sleepValue!!, activeHours = activeValue!!, calories = calories,
                            workoutsPerWeek = workouts,
                        ),
                    )
                },
                Modifier.weight(1f),
                enabled = stepsValue != null && sleepValue != null && activeValue != null, primary = true,
            )
        }
    }
}

/** Workouts a week, between minus and plus (workouts spec 3.1). */
@Composable
internal fun WorkoutGoalField(value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.you_goals_workouts_label), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
            style = GlowType.secondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIconButton(
                AppIcons.Minus, stringResource(R.string.you_goals_workouts_fewer),
                { onChange((value - 1).coerceIn(Goals.WORKOUTS_PER_WEEK)) }, size = 44.dp,
            )
            Box(Modifier.weight(1f).height(52.dp).clip(GlowLayout.TileShape).background(ControlSurface), contentAlignment = Alignment.Center) {
                Text(
                    pluralStringResource(R.plurals.you_goals_workouts_value, value, value), color = Color.White, style = GlowType.body,
                    maxLines = 1,
                )
            }
            RoundIconButton(
                AppIcons.Plus, stringResource(R.string.you_goals_workouts_more),
                { onChange((value + 1).coerceIn(Goals.WORKOUTS_PER_WEEK)) }, size = 44.dp,
            )
        }
        PageNote(stringResource(R.string.you_goals_workouts_note))
    }
}

/**
 * The calorie goal: active kcal between minus and plus, which step it by [Goals.CALORIES_STEP]. Tapping the number
 * opens it for typing; Enter or leaving the field keeps a value in range, anything else keeps the goal as it was and
 * says the range. Under it the [suggested] goal, with Use while it differs.
 */
@Composable
private fun CalorieGoalField(value: Int, onChange: (Int) -> Unit, suggested: Int?) {
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // The field reports "not focused" as soon as it's placed; only losing the focus it had commits.
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        if (!typing) return
        typing = false
        focused = false
        val typed = CalorieGoal.parse(text)
        refused = typed == null
        if (typed != null) onChange(typed)
    }
    val typeLabel = stringResource(R.string.you_goals_calories_type)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.you_goals_calories_label), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
            style = GlowType.secondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIconButton(
                AppIcons.Minus, stringResource(R.string.you_goals_calories_lower),
                { typing = false; refused = false; onChange(CalorieGoal.step(value, -Goals.CALORIES_STEP)) },
                size = 44.dp,
            )
            Box(
                Modifier.weight(1f).height(52.dp).clip(GlowLayout.TileShape).background(ControlSurface)
                    .let {
                        if (typing) it
                        else it.clickable(onClickLabel = typeLabel, role = Role.Button) {
                            text = value.toString(); refused = false; typing = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (typing) {
                    BasicTextField(
                        text, { t -> text = t.filter { it.isDigit() }.take(4) },
                        Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { if (it.isFocused) focused = true else if (focused) commit() },
                        textStyle = GlowType.body.copy(color = Color.White, textAlign = TextAlign.Center), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        cursorBrush = SolidColor(Color.White),
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                } else {
                    Text(
                        stringResource(R.string.you_goals_active_kcal, Units.count(value.toLong())), color = Color.White,
                        style = GlowType.body, maxLines = 1,
                    )
                }
            }
            RoundIconButton(
                AppIcons.Plus, stringResource(R.string.you_goals_calories_raise),
                { typing = false; refused = false; onChange(CalorieGoal.step(value, Goals.CALORIES_STEP)) },
                size = 44.dp,
            )
        }
        if (refused) PageNote(GoalCopy.outOfRange(Goals.CALORIES), MetricColors.warn)
        if (suggested != null) {
            val count = Units.count(suggested.toLong())
            if (suggested == value) PageNote(stringResource(R.string.you_goals_suggested_matched, count))
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { PageNote(stringResource(R.string.you_goals_suggested, count)) }
                PageButton(stringResource(R.string.you_goals_use), { typing = false; refused = false; onChange(suggested) })
            }
        }
    }
}
