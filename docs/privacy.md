# Privacy, network and permissions

What OpenStrap sends, what it asks Android for, and why. Your health data never leaves the phone.

## Network use

OpenStrap goes online for your Zepp account, for firmware and for workout maps only when you ask it to, and once a day to look for a new version of itself, which you can turn off. It never uploads health data.

- **Sign in with Zepp** opens Zepp's own sign-in page (watchface.zepp.com) in an in-app browser. OpenStrap never sees your password. After sign-in it reads the app token and user ID from the page's cookies. It then makes one request to Zepp's device list (`GET users/{userId}/devices` on `api-mifit*.zepp.com`) and shows you the strap's Bluetooth address and auth key to confirm. It saves the token and user ID for firmware checks and clears the Zepp cookies.
- **Check for update** sends your Zepp token, user ID, and the strap's firmware version, hardware version and serial number to the update server you set. **Download** fetches the image from the URL that server returns.
- **Workout maps** load map tiles from [OpenFreeMap](https://openfreemap.org) (`tiles.openfreemap.org`), only when you open the map of a GPS workout. No account, API key or cookies. Like any tile server it sees roughly where the map is and your IP address. **Route only** on the map page loads no tiles at all. Your route itself never leaves the phone.

- **New versions of OpenStrap.** Once a day, when the phone has a network, OpenStrap reads the list of its releases from GitHub (`GET api.github.com/repos/sayed0am/openstrap/releases`), and again when you tap **Check now**. The request carries no account, token or ID, only OpenStrap's version in the user agent. Like any server, GitHub sees your IP address. When a newer release is out, a notification says so once. Turn it off with **Check every day** in **You → Updates**.
- **Installing an update** downloads that release's APK from GitHub, only when you tap it. OpenStrap checks it against the SHA-256 GitHub lists for it, and that it's OpenStrap signed with the same key, before handing it to Android to install.

You can skip the sign-in and paste the key and token by hand instead.

## Permissions

- **Nearby devices (Bluetooth)** to find and connect to the strap.
- **Location** only if you turn on GPS for an outdoor workout, and only while that workout records. Never in the background otherwise. The route is used for distance, pace and the map, then discarded unless you choose **Keep route on this phone**. Without location, distance comes from the strap's steps (shown with ≈).
- **Notifications** for the "Helio Strap" connection notification, health alerts, the off-body reminder and new versions of OpenStrap.
- **Health Connect** read and write, only if you export to or restore from it.
- **Phone state and notification access** only if you turn on phone alerts (see [Phone access](#phone-access)).
- **Internet** only for the Zepp sign-in, firmware, workout maps and OpenStrap updates (see [Network use](#network-use)).
- **Install unknown apps** only to install an OpenStrap update you tapped. Android asks you to allow it the first time.
- **Keep strap connected in the background.** At the end of pairing, or from that row on the strap page, OpenStrap asks Android to link the strap to it as a companion device. Android shows its own prompt for that strap. It's a plain link, not Android's watch profile, so OpenStrap doesn't get access to calls, contacts or calendar. Once it's linked, Android leaves OpenStrap out of battery optimization and lets it reconnect from the background. On Android 12 and newer, Android also wakes OpenStrap when the strap comes into range, so it reconnects after the app is stopped or the phone restarts, without opening the app. Forget this strap removes the link. On phones without companion device support, or if you turn the prompt down, the row asks to turn off battery optimization for OpenStrap instead.

## Phone access

Both are off until you switch them on in **You → Alarms & alerts → Phone alerts**.

- **Incoming calls** needs the phone-state permission. OpenStrap uses it only to know when a call is ringing, answered or over. It never reads numbers, contacts or the call log. During Do Not Disturb the strap buzzes only if Do Not Disturb lets calls from anyone through. Calls let through only as starred contacts or repeat callers ring the phone but not the strap, because telling them apart would need the caller's number.
- **Notifications** needs notification access. OpenStrap looks only at whether a notification would make a sound (Do Not Disturb and silent apps are followed) and at its category. It never reads the title or text, stores nothing, and sends the strap only a buzz. It asks Android only for notifications that alert, plus conversations. You can narrow that further, or leave out apps, on the notification-access page.

## Backup and restore

- **You → Export CSV** saves a zip with one CSV per table. **Import CSV** restores it on this phone or a new one. Data the strap synced always wins. The backup only fills in the days before the phone's earliest record. Import after the strap's first sync on a new phone. A failed import changes nothing.
- **Restore from Health Connect** brings back what OpenStrap wrote there, even after OpenStrap was uninstalled. Like the CSV import, it only fills gaps.
