package org.openstrap.app.ui.root

import androidx.compose.ui.graphics.Color
import org.openstrap.app.ui.components.NavItem
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.MetricColors

/** Each tab's place in the bar: its label, icons and the capsule's colour while it shows (spec 3.2). */
fun tabItems(): List<NavItem> = Tab.entries.map { tab ->
    when (tab) {
        Tab.Today -> NavItem(tab.label, AppIcons.HouseSimple, AppIcons.HouseSimpleFill, Color.White)
        Tab.Recovery -> NavItem(tab.label, AppIcons.Plant, AppIcons.PlantFill, MetricColors.readiness)
        Tab.Sleep -> NavItem(tab.label, AppIcons.Moon, AppIcons.MoonFill, MetricColors.sleepDeep)
        Tab.Activity -> NavItem(tab.label, AppIcons.SneakerMove, AppIcons.SneakerMoveFill, MetricColors.steps)
    }
}
