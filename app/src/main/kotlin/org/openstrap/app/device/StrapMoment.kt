package org.openstrap.app.device

import org.openstrap.app.data.MomentLead

/**
 * The moment of the day for the strap's notification, as the Moment widget reads it: its [name] as Today's header
 * says it ("Evening"), what it [lead]s with, and Today's [sentence] for it.
 */
data class StrapMoment(val name: String, val lead: MomentLead, val sentence: String?)
