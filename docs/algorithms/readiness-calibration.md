# Readiness calibration against the strap

**Status: collecting data.** Started 2026-10-16. Revisit after about two weeks of daily data, around **2026-10-30**. unstrap's Readiness formula stays as it is until then.

## Why

The strap computes its own Readiness (channel module `0x0049`). unstrap computes its own as well (see [recovery.md](recovery.md)). The two can disagree a lot. On the first day:

| | Strap | unstrap |
|---|---|---|
| Readiness | 91 | 52 |
| HRV part | 89 (62 ms overnight) | none yet: no HRV baseline ("learning 0/14 nights") |
| Resting HR part | 86 (66 bpm) | none yet |
| Skin temperature part | 100 | none yet |
| Breathing (AHI) part | 100 | none yet |
| Sleep | not a part of the strap's score | 3h 30m asleep, scored low; carries most of the 52 |

A single day can't support a fit, so we collect first. Possible changes once there's data:

* Scale unstrap's HRV and resting-HR parts to match the strap's (HRV 30 % and RHR 22 % of our weight).
* Use the strap's parts while unstrap's own baselines are still learning.
* Keep unstrap's sleep parts either way. The strap leaves sleep out, so its score reads high after a short night.

## What is collected

* **When:** at the end of every sync (`HistorySync.syncReadiness`), unstrap asks the strap for its score (`08 <since>`).
* **What comes back:** the strap holds only its latest score and returns it whatever `since` is. A history therefore exists only for days the phone synced.
* **Stored in:** table `strap_readiness`, one row per local day (`day` = local midnight, epoch seconds).
  * `score`
  * `hrvScore`, `restingHrScore`, `skinTemperatureScore`, `breathingScore`
  * `sleepHrv` (ms) and `sleepRestingHr` (bpm)
  * `algorithmVersion` (the strap reported `4.3`)
* **unstrap's own score is not stored.** It is computed from the synced data (`RecoveryEngine`), so the score for any past day can be recomputed during the comparison.

**Gaps:** a day the phone doesn't sync before the next morning's score replaces it is lost. Open the app at least once a day while collecting.

## Pulling the data

On a debug build connected over adb:

```bash
adb shell run-as org.openstrap.app cat databases/openstrap.db > openstrap.db
adb shell run-as org.openstrap.app cat databases/openstrap.db-wal > openstrap.db-wal
sqlite3 openstrap.db "SELECT date(day, 'unixepoch', 'localtime'), score, hrvScore, restingHrScore, skinTemperatureScore, breathingScore, sleepHrv, sleepRestingHr FROM strap_readiness ORDER BY day"
```

## Check-in and nightly inputs

Agreement with the strap is agreement with another unvalidated score, so the review also needs a within-user test ([evidence-review.md](evidence-review.md), implementation step 1):

* **Morning check-in** (Today screen, before noon): how you feel, 1 (poor) to 5 (great). Table `check_in`, one row per local day.
* **Tags** were removed from the app: they changed no score. Old rows in table `day_tag` are still exported, imported and written to the backtest CSV.
* **unstrap's own nightly inputs**, logged each time the day's score is computed. Table `night_metrics`: `hrvLn` (`hrv_n`, ln of the mean gated RMSSD, 5–200 ms), `rhrLowest30` (`rhr_n`), `rhrMeanSleep`, `rhrP10`, the sample counts, and the strap's `sleepHrv` / `sleepRestingHr` for the same night.

All of these are in the CSV backup (`check_in.csv`, `day_tag.csv`, `night_metrics.csv`).

## Backtest harness

`Backtest` (`app/src/main/kotlin/org/openstrap/app/data/Backtest.kt`) replays `RecoveryEngine` one day at a time and writes one CSV line per day: our Readiness and every contributor, the nightly inputs, the strap's score and parts, the check-in and the tags. Each day only sees data up to its own end, as the phone would have.

Run it on a pulled database (with its `-wal` beside it) or on a CSV backup zip:

```bash
./gradlew testDebugUnitTest --tests '*BacktestTest*replay*' \
  -Pbacktest=/path/to/openstrap.db -PbacktestProfile=1990,Male -PbacktestOut=/tmp/backtest.csv
```

The database is copied first, so Room's migration never touches the original. Without `-PbacktestOut` the CSV goes to `app/build/backtest.csv`. The days use the machine's time zone, so run it in the zone the data was recorded in.

## Soft-minimum fusion

Zepp combines its parts with a soft minimum (analysis `native/06-readiness.md` §4.8): each part weighs e^(−(s/100)²), so a low part counts up to e ≈ 2.7× a perfect one. unstrap uses a weighted mean, and caps the score at worst + 30 when a physiological part is below 40.

`Recovery.softMinimum` keeps unstrap's importance weights and adds Zepp's: w = weight · e^(−(s/100)²), score = Σ w·s / Σ w. It drops the red-flag cap; the Alert cap stays. The app still shows the weighted mean (`SOFT_MIN_FUSION` is off). The backtest writes both scores side by side for each day: `readiness` (shown), `readiness_soft_min`, then `strap_score`.

At the review (about 2026-11-06, once there are 14+ scored nights), run the backtest and compare:

* `readiness` against `readiness_soft_min`: how often they differ by more than 5, and on which days. Expect the soft minimum lower on days with one weak part and close to the mean on even days. Check that it still catches the red-flag days (`capped` true, no alert) without the hard cap.
* Each against `strap_score`: mean difference and spread. The strap leaves sleep out, so also compare with the sleep columns (`c_sleep`, `c_sleep_balance`) set aside.
* The check-in: which of the two tracks `check_in` better. Agreement with the strap alone isn't evidence either way.

Switch `SOFT_MIN_FUSION` on only if the soft minimum is at least as good on both, and record the numbers here.

**Also test whether the strap scores against population curves only** (`native/06` §9, idea 1). For each `strap_readiness` row, compute Zepp's population-curve HRV and RHR parts from the row's own `strap_sleep_hrv` / `strap_sleep_resting_hr` and the user's age (§4.3, §4.4), fuse them with the soft minimum (equal base weights) and compare with `strap_score`. If they keep matching after 7+ nights, the strap has no personal history and unstrap could reproduce its score exactly; if they drift apart, the strap keeps its own baselines and the z-curves in §4.3–4.5 are the model to fit.

## When revisiting

1. Check that there are at least 10–14 days of rows and that unstrap's baselines have finished learning.
2. For each day, recompute unstrap's Readiness and its contributors. Put them next to the strap's score and parts.
3. Compare part by part:
   * our HRV balance against the strap's `hrvScore`
   * our resting HR against `restingHrScore`
   * our temperature against `skinTemperatureScore`

   Then compare the overall score with the sleep parts left out.
4. Decide between rescaling our parts, falling back to the strap's parts while learning, or leaving the formula alone. Record the decision and the numbers here, and update [recovery.md](recovery.md).
