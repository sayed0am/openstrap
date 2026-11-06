package org.openstrap.app.ui.root

import android.app.Activity
import android.app.KeyguardManager

/**
 * Runs [action] once the phone is unlocked: at once when it isn't locked, else after Android asks for the unlock
 * (the PIN, a fingerprint, or a swipe). Cancelled, [action] never runs. For leaving the live workout, the only screen
 * shown over the lock screen.
 */
internal fun Activity.unlockThen(action: () -> Unit) {
    val keyguard = getSystemService(KeyguardManager::class.java)
    if (keyguard?.isKeyguardLocked != true) {
        action()
        return
    }
    keyguard.requestDismissKeyguard(
        this,
        object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        },
    )
}
