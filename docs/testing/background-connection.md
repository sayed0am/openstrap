# Background connection: what's untested

The companion device association (sayed0am/openstrap#7) was tested on one phone: a Galaxy Z Fold (SM-F966B) on Android 16, over adb, on 2026-10-21. This page lists what wasn't checked on hardware, and how to check each item.

## Tested

- Associating from the strap page while the strap is connected. After that, `adb shell dumpsys deviceidle whitelist` lists `org.openstrap.app`.
- `adb shell am kill org.openstrap.app` leaves the app running. With a foreground service bound by the companion manager, Android treats the process as not safe to kill.
- After a SIGKILL (`adb shell run-as org.openstrap.app kill -9 <pid>`), a reinstall or a reboot, Android restarts OpenStrap on its own. The strap is connected and authenticated about 3 s later, with no screen open.
- The row comes back when the association is removed (`adb shell cmd companiondevice disassociate 0 org.openstrap.app <MAC>`).
- Declining Android's prompt switches the row to the battery-optimization prompt.
- An association made outside the app is picked up on resume: new id, presence watching on.
- A notification arriving while the strap is disconnected reconnects it.
- Disconnect holds through presence events, notifications and a process kill. Opening the app reconnects.

## Not tested

| What | Why not | How to test |
|---|---|---|
| **Forget this strap** removes the association | It deletes the strap's auth key from the phone, so you'd have to pair again | Forget the strap, then check `adb shell cmd companiondevice list 0` no longer shows `org.openstrap.app`. Pair again with the key. |
| Android's prompt right after a fresh pairing | Needs a new pairing. The row on the strap page runs the same code. | Forget the strap (above), pair again, and check Android's "Allow OpenStrap to access…" prompt appears once the strap page shows the strap. |
| A ringing call reconnects the strap | It only applies when the strap dropped without a Disconnect tap (e.g. out of range). It uses the same `PhoneAlerts.reconnectStrap()` path as notifications, which was tested. | Turn on Incoming calls in Phone alerts. Take the strap out of range until OpenStrap shows it's not connected, bring it back, then call the phone. Look for `connecting to` in the log after `call state 1`. |
| The strap leaving range and coming back | Needs the strap moved away from the phone | Walk away with the phone for a few minutes, come back, and check the strap reconnects without opening the app. |
| Android 13–15 (`onDeviceAppeared(AssociationInfo)`, presence by address) | No phone on those versions | Associate, SIGKILL the process, and check `companion: strap appeared` and `connecting to` in `adb logcat -s OpenStrap`. |
| Android 12 (`onDeviceAppeared(String)`, no association ids) | No phone on that version | As above. Also check that Forget removes the association by address. |
| Phones without companion device support | No such phone | The row should offer the battery-optimization prompt straight away. |
| A day of normal use on battery | Not run | Leave the strap connected all day with the app closed. Check that syncs, call buzzes and notification buzzes keep working, and look at OpenStrap's battery use in Settings. |

## Known behaviour

- On Android 16, one presence event can reach the app through two or three callbacks. `autoConnect()` ignores repeats, so this only shows as repeated `companion: strap appeared` lines in the log.
- While the user's Disconnect holds, the strap still advertises, so the companion manager keeps binding the presence service every few seconds. Each event is skipped and not logged.
- The call or notification that wakes the app is lost: the strap is still connecting when it arrives. Later ones reach the strap.
