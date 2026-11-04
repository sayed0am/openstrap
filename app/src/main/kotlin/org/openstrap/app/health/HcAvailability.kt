package org.openstrap.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient

/**
 * Whether Health Connect can be used, from the SDK's status. On Android 12 and 13 it's an app from the Play Store, and
 * the SDK says the provider needs an update both when that app is missing and when it's too old or turned off: the
 * Play Store page fixes all three, so the app offers to install it. SDK_UNAVAILABLE (a work profile on Android 14 and
 * up, say) has nothing to install.
 */
enum class HcAvailability {
    AVAILABLE,
    INSTALLABLE,
    UNAVAILABLE,
    ;

    companion object {
        fun of(sdkStatus: Int): HcAvailability = when (sdkStatus) {
            HealthConnectClient.SDK_AVAILABLE -> AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> INSTALLABLE
            else -> UNAVAILABLE
        }

        fun of(context: Context): HcAvailability = of(HealthConnectClient.getSdkStatus(context))
    }
}
