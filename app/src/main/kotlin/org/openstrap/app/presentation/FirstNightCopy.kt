package org.openstrap.app.presentation

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings

/*
 * The first nights (spec 4.3): what a card says before there's a night to score, and what leads the waking hero.
 */

/** What a card waiting for the first night says in place of its value (spec 4.3). */
val AFTER_FIRST_NIGHT: String get() = Strings.app.get(R.string.core_first_night_after)

/** What leads the waking hero: Recovery, or Charge when there was no sleep to score (spec 4.3). */
enum class WakingLead { Recovery, Charge }

/**
 * The waking hero's lead: Charge on today when no sleep was recorded last night ([asleepSeconds] null), since there's
 * no night for Recovery to score. Before the first night, and on past days, Recovery keeps the hero and says why.
 */
fun wakingLead(firstNights: Boolean, asleepSeconds: Long?, isToday: Boolean): WakingLead =
    if (!firstNights && isToday && asleepSeconds == null) WakingLead.Charge else WakingLead.Recovery
