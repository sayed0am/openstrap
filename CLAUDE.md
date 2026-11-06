# unstrap

## Widget gallery

A debug-only screen that hosts each home screen widget at its three sizes, with forced moods, moments and strap
states. Its code stays in `app/src/debug/kotlin/org/openstrap/app/widget/debug/WidgetGalleryActivity.kt` (it still
compiles), but its activity is commented out in `app/src/debug/AndroidManifest.xml`, so it isn't installed: while
open, its widgets count as placed and refresh along with the real ones.

To test widgets again:

1. Uncomment the `WidgetGalleryActivity` `<activity>` in `app/src/debug/AndroidManifest.xml`.
2. Build and install the debug app (check no workout is recording first: a `workout` notification channel in
   `adb shell dumpsys notification --noredact | grep "pkg=org.openstrap.app"`).
3. `adb shell appwidget grantbind --package org.openstrap.app --user 0`
4. `adb shell am start -f 0x00020000 -n org.openstrap.app/.widget.debug.WidgetGalleryActivity --es widget moment`
   Extras: `widget` recovery|steps|sleep|start|moment, `mood` day|evening|night|auto, `cells` oneui|min,
   `moment` a moment name or auto, `placed` kinds or none|auto, `strap` notpaired|low|stale|yesterday|week|syncing|none|auto,
   `pin` a kind. A render takes 15 to 20 s.
5. When done, close it with Back (so it unbinds its widgets), then comment the activity out again.
