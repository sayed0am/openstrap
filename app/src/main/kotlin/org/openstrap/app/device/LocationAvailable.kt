package org.openstrap.app.device

import android.content.Context
import android.location.LocationManager

/** Whether the phone's location can be used now: allowed, and turned on. */
internal fun locationAvailable(context: Context): Boolean =
    WorkoutLocation.granted(context) && context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
