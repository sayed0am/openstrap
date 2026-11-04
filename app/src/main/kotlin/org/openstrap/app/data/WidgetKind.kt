package org.openstrap.app.data

/**
 * The five home screen widgets (widgets spec 2). It lives in data, not widget, so the Moment lead rule ([momentLead])
 * can name which widgets are placed without data depending on the widget package.
 */
enum class WidgetKind { Moment, Sleep, Steps, Recovery, Start }
