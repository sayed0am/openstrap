# Rebrand: OpenStrap to unstrap

Branch `rebrand/unstrap`, from `develop` at 397ca4f (2026-11-06).

## Why

The OpenStrap org on GitHub (github.com/OpenStrap, made 2026-04-02) ships an app called edge for the same idea:
recovery, strain and sleep from a strap without a subscription. It has 879 stars, 163 followers and a site at
openstrap.github.io. This repo has 31. Anyone searching "OpenStrap" lands on theirs, and the two get mixed up.

## The new name

- Logo: lowercase "unstrap" in Funnel Display Bold, tracking -3.7%, with "un" in red: the closed app, cancelled. The
  red glows on dark grounds only (text shadow 0.25 em at 35%), and loses the glow below about 24 px. On light grounds
  the red alone carries it. Source: claude.ai/artifact/2bMB9knUooYRe5o1sxhb1e (Main, Icons, Splash, Banner).
  Tagline: "Private, open-source app for the Amazfit Helio Strap".
- Red is #FF5A5F, the same as `MetricColors.heart` (`Theme.kt:22`), on purpose: one red in the app, not two near
  matches.
- Font: Funnel Display is already bundled (`Theme.kt:101`), so the wordmark is drawn in the app with no new font.
- Launcher icon: red "un" on #0B0D10. Themed icon (Android 13+): "un" in the system's one colour. Notification and
  status bar: a white "u" (see step 7).
- Writing the name: lowercase "unstrap" everywhere, even at the start of a sentence, in file names and in docs. The
  one exception is the app name (`app_name`, the label under the launcher icon and the dev and demo labels), which is
  "Unstrap". Never "UnStrap".

## The rule: rename what people see, keep what's stored

About 25,900 mentions in 718 of 872 files. Almost all of them are the Kotlin package `org.openstrap.*`. Nobody sees
those, and changing what's stored would cost current users their updates or data. So:

### Keep as they are

| What | Where | Why |
|---|---|---|
| Application ID `org.openstrap.app` (and `.dev`, `.demo`) | `app/build.gradle.kts` | A new ID is a new app to Android. The in-app update couldn't install over it, and data would stay in the old app. |
| Kotlin packages `org.openstrap.app.*`, `org.openstrap.protocol.*` | all sources | Invisible to users. Renaming would touch every file and every open branch for nothing. |
| `OpenStrapDatabase`, `openstrap.db`, `app/schemas/org.openstrap...` | `data/db` | Room's schema folder follows the class name. Migrations and their tests depend on it. |
| Health Connect ids `openstrap:` and `openstrap:sleep:` | `HealthConnectExporter.kt:135`, `SleepImport.kt:37` | Records already written carry them. A new prefix would duplicate everything on the next sync and break sleep import. |
| Intent actions `org.openstrap.app.*` | `StrapService.kt:599-627` | Internal, and pending intents from the old build may still be live after an update. |
| Signing secrets `OPENSTRAP_*` | `.github/workflows/android.yml`, `app/build.gradle.kts` | Renaming means re-adding secrets in GitHub. No gain. |
| Settings DataStore name `settings` | `SettingsStore.kt:21` | Already neutral. |

### Change

1. **App name.** `app_name` in `strings.xml`, and the dev and demo label in `app/build.gradle.kts:59,77`.
2. **Text in the app.** 103 mentions over 21 `values/*.xml` files, plus translator comments that say "OpenStrap is
   the app's name". All text, no layout change. No translations exist yet, so English only.
3. **Brand on share cards.** Done: every share picture (run, lift, indoor, rounds, calm, game and the week) opens
   with `Wordmark` (ui/glow/Wordmark.kt) at the top left, with the date or time on the right where the card has one:
   "un" in the heart red, "strap" in white, Funnel Display Bold 16 sp, no glow at this size. It moved off the foot
   because the card light rises from there and the red faded on warm light (about 2 to 1). The top is dark on every
   card (6.5 to 1), and over a photo the top fade is now 85% black, so the red holds about 4 to 1 even on a near
   white photo. The ring before the name on the calm and game cards is gone; there "strap" stays dimmed to 80%.
4. **Shared file names.** `OpenStrap_week_<date>.png` in `GoalsAndBests.kt:293` becomes `unstrap_week_<date>.png`.
5. **Backup files.** `BackupFiles.kt:16-23` writes `OpenStrap-backup-<date>.zip`. New backups are `unstrap-backup-<date>.zip`. The
   regexes must accept both names, so pruning and restore still find old backups. Add a test with both.
6. **Import error.** "This isn't an OpenStrap export." in `CsvImporter.kt:135`. Old exports must still import. Check
   the importer doesn't look for the name anywhere else.
7. **Notification icon.** Done: `ic_stat_unstrap`, a white "u" in Funnel Display Bold filling the 22 dp space,
   used by every notification and the manifest. Decided on the Fold 2026-11-06 against "un": Android fits the icon
   into a square, so two short letters came out half the height of the icons beside it, as "os." did. "u" matches
   them.
8. **Launcher icon.** Done: red "un" (#FF5A5F, with the soft glow) on #0B0D10, 52 dp wide in the 108 dp canvas,
   the same width as the old "os." and inside the 66 dp circle every mask keeps. The monochrome layer is the same
   "un" for themed icons. Red over white, chosen 2026-11-06: the red "un" is the brand, and white read as a plain
   label. Samsung's shade shows this icon on our notifications. Play Store 512 px still to export.
9. **Splash.** Android's own splash (12 and up) draws the launcher icon, so it shows the red "un" with no work. The
   theme sets its background to the app's black and the icon's circle to #0B0D10. A wordmark in the splash, as in
   the logo sheet, would need the branding image slot, which Google advises against, so it isn't used.
10. **Updates.** `Releases.kt:34` reads `sayed0am/openstrap`. GitHub forwards a renamed repo, including the API, so
    old builds keep working after the rename. New builds point at `sayed0am/unstrap`. The APK is picked by `.apk`
    suffix (`Releases.kt:89`), so its file name can change freely.
11. **Release workflow.** `android.yml:71,88,99`: artifact name, `OpenStrap-<tag>.apk`, release title.
12. **Docs.** README, `docs/privacy.md`, `docs/algorithms`, `docs/protocol`, `docs/testing`, `CLAUDE.md` heading.
    Past release notes in `docs/releases` stay as written.
13. **Manifest comments.** 9 comments in `AndroidManifest.xml`. `.OpenStrapApp` and `Theme.OpenStrap` can stay
    (code names) or move with step 14.
14. **Optional code names.** `OpenStrapApp`, `Theme.OpenStrap`. Only if cheap. Skip `OpenStrapDatabase` (see above).
15. **Log and wake lock tags.** Done: the log tag is `"unstrap"` and the wake locks `"unstrap:firmware"` and
    `"unstrap:workout"`, so phone logs read with `adb logcat -s unstrap`.

### Outside the code

- Rename the GitHub repo `sayed0am/openstrap` to `sayed0am/unstrap` after develop is merged to main and before the
  release, so the 3.0 release is made at the new name. GitHub forwards the old name, the API included, so installed
  OpenStrap builds keep finding updates. Never make a new repo at the old name, or the forwarding stops. Point the
  local clone's remote at the new address right after.
- Release notes for 3.0: one line saying OpenStrap is now unstrap, same app, data kept, then the features.
- 3.0 must install over the last OpenStrap release: same ID, same key, and build 30000 is above 2.2.2 and the 2.3.0
  dev builds. Test on the Fold by installing it over the current release and checking data, Health Connect sync
  and a backup restore.

## Order

1. Finish the changes on `rebrand/unstrap`: updates (`Releases.kt` to `sayed0am/unstrap`), release workflow, docs,
   Play Store icon, and the share card layout if it lands in time.
2. Check: lint, detekt, unit tests, then install over the current release on the Fold.
3. Merge `rebrand/unstrap` into develop, then develop into main.
4. Rename the GitHub repo to `unstrap`.
5. Release 3.0 (tag `v3.0.0`).

## Decided

- **Version:** 3.0 (decided 2026-11-06). The rename is the moment people need to notice, and it makes a clean cut:
  OpenStrap is 2.x, unstrap is 3.x. It also carries develop's unreleased 2.3 work, so no 2.3 ships with the old name.
- **Log tag:** `"unstrap"` (step 15).
