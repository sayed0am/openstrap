# How OpenStrap calculates everything (v1.2.0)

Every number OpenStrap works out itself, explained in plain words. Written from the v1.2.0 code (tag `v1.2.0`, 2026-10-18).

* For research sources and the reasoning behind each choice, see [recovery.md](recovery.md) and [evidence-review.md](evidence-review.md).
* For the planned check against the strap's own Readiness, see [readiness-calibration.md](readiness-calibration.md).
* Code lives in `app/src/main/kotlin/org/openstrap/app/data/`. The rules are in one file per part: `ReadinessModel.kt` (Readiness, §5, and the sleep score, §9), `ChargeModel.kt` (Energy, §7), `LoadModel.kt` (training load, Strain and fitness, §8, and daytime resting HR, §4) and `MaxHrModel.kt` (maximum HR, §4). Their inputs, results and constants are in `Recovery.kt`, named `Recovery.X` as the code names them, and `RecoveryEngine.kt` feeds them from the database.

OpenStrap is a wellness app. None of these numbers is a medical measurement.

## Contents

1. [What comes from the strap, and what we calculate](#1-what-comes-from-the-strap-and-what-we-calculate)
2. [Shared building blocks](#2-shared-building-blocks)
3. [The night: turning sleep into numbers](#3-the-night-turning-sleep-into-numbers)
4. [Your heart-rate settings: resting and maximum](#4-your-heart-rate-settings-resting-and-maximum)
5. [Readiness (0–100, each morning)](#5-readiness-0100-each-morning)
6. [Body signals off baseline](#6-body-signals-off-baseline)
7. [Energy (0–100, minute by minute)](#7-charge-0100-minute-by-minute)
8. [Training load, Strain and fitness/fatigue](#8-training-load-strain-and-fitnessfatigue)
9. [Sleep screen](#9-sleep-screen)
10. [Activity: steps, distance, active minutes](#10-activity-steps-distance-active-minutes)
11. [Energy expenditure (calories)](#11-energy-expenditure-calories)
12. [Estimated fitness (VO2max) and fitness age](#12-estimated-fitness-vo2max-and-fitness-age)
13. [Workouts](#13-workouts)
14. [Trends and "your normal" ranges](#14-trends-and-your-normal-ranges)
15. [Known gaps and inconsistencies in v1.2.0](#15-known-gaps-and-inconsistencies-in-v120)

---

## 1. What comes from the strap, and what we calculate

**Taken from the strap as is:** heart rate, steps, sleep sessions and stages, RMSSD (HRV) samples, SpO₂, stress (0–100), skin temperature, breathing rate, the daily resting HR, PAI, the strap's sleep score, its sleep-apnea summary (desaturation event count), and workout records with their training effect.

**Calculated by OpenStrap:** everything in sections 3–14: Readiness, body-signal alerts, Energy, training load, Strain, fitness/fatigue, sleep score (when the strap gave none), sleep debt, sleep regularity, sleep-health count, active minutes, distance, calories, VO2max, workout zones and recovery HR, and training effect, EPOC, recovery time and run VO2max for workouts started in OpenStrap.

**Logged but not used in any score:** the strap's own Readiness and its parts, and your morning check-in (1–5, asked on Today before noon). They're kept for the calibration review.

---

## 2. Shared building blocks

These few tools are used all over the app.

### Personal baseline ("your usual")

Most scores compare today with your own history, not with population averages.

* **Centre** = the median of your past values (the middle one when sorted).
* **Spread** = 1.4826 × the median distance from that centre (the "MAD"). For normal data, this is about the same as one standard deviation.
* The spread never goes below a **floor**, so a very steady history can't turn a tiny change into a huge one:

| Signal | Floor |
|---|---|
| HRV (on the log scale) | 0.05 |
| Resting heart rate | 2 bpm |
| Breathing rate | 0.5 breaths/min |
| Skin temperature | 0.15 °C |
| Blood oxygen | 0.5 % |

* A baseline needs at least 7 values, and it looks back over at most 60 nights.
* **Why median and MAD, not mean and SD:** one bad week (illness, alcohol, a loose strap) would drag a mean with it and become "normal". The median barely moves.

### z-score

z = (today − centre) ÷ spread. It means "how many spreads away from usual". Where lower is better (resting HR), the sign is flipped so that a positive z always means better.

### Dead zone

Changes smaller than half a spread are treated as noise:

z_eff = sign(z) × max(0, |z| − 0.5)

So z = 0.4 counts as 0, and z = 1.2 counts as 0.7.

### z → score (0–100)

score = 100 ÷ (1 + e^−(1.19·z + ln 4))

| z | Score | Meaning |
|---|---|---|
| +1 | 93 | better than usual |
| 0 | 80 | a normal night |
| −1 | 55 | worse than usual |
| −2 | 27 | much worse |

A normal night scores 80, not 100, which leaves room to show a better-than-usual one.

### Percentile

Values are sorted and the p-th percentile is read off by linear interpolation. The median is the 50th.

### Heart-rate reserve (HRR)

How hard your heart is working, from 0 (resting) to 1 (maximum):

HRR = (HR − resting HR) ÷ (max HR − resting HR), kept between 0 and 1.2

This is the intensity measure behind training load, Energy, active minutes and calories. Section 4 explains where resting and max HR come from.

### TRIMP (training impulse) per minute

Banister's formula. For one minute at reserve x:

* men, or sex not set: x × 0.64 × e^(1.92·x)
* women: x × 0.86 × e^(1.67·x)

The curve rises steeply, so one minute at 80 % counts about 3× as much as one at 50 %. Example: an hour at 60 % HRR (men's curve) ≈ 73 TRIMP.

### Exponentially weighted average (EWMA)

A running average where recent days count more: each day, v = λ·today + (1 − λ)·v, with λ = 2 ÷ (N + 1) for an N-day average.

### Days and nights

* A day runs from local midnight to midnight.
* A sleep belongs to the **day it ended on** (the day you woke up), midnight to midnight, by its times as shown, so a night the user edits to end after midnight moves to the new day. This is one rule everywhere (`SleepHistory.nightOf`): the Sleep tab, Today, the widgets, Recovery and the nights strip, naps and Health Connect imports included. It wins over the strap's own day for the session, which Zepp follows. The main sleep of a day is the longest session that ended on it and isn't a nap.
* The strap's own nightly rows (its readiness with the night's HRV and resting HR, and the sleep-apnea summary, whose day is its time + 2 hours) are keyed by the strap's day for the session. They go with the main sleep from that strap session (`SleepHistory.strapDay`), which an edit never changes, so a night that moves day keeps them.
* Time windows allow two hours of slack so a daylight-saving change doesn't drop a day.

---

## 3. The night: turning sleep into numbers

For each main sleep, OpenStrap works out the following (`RecoveryEngine.night`).

**Artefact filters**
* Heart-rate minutes outside 30–220 bpm are ignored.
* RMSSD samples outside 5–200 ms are ignored.

| Value | How it's calculated |
|---|---|
| **Time asleep** | Deep + light + REM minutes from the strap. Without stage totals: session length − awake minutes. |
| **WASO** (wake after sleep onset) | Awake minutes between the start of the first sleep stage and the end of the last. Empty without stages. |
| **HRV** | Mean of the night's RMSSD samples. For scoring it's put on the log scale: **ln(mean RMSSD)**. |
| **Resting HR** | The lowest 30-minute average HR in the sleep. The window needs ≥ 20 minutes of data. |
| **Sleep-mean HR** | Average of all HR minutes in the sleep (used by body-signal alerts). |
| **10th-percentile HR** | Logged for the calibration review only. |
| **Lowest-HR time** | The middle of the lowest 10-minute average (for the recovery index). |
| **Skin temperature** | Mean of the night's readings, only with ≥ 30 of them. |
| **Breathing rate** | Mean of the night's readings. |
| **Blood oxygen** | Mean of the night's sleep SpO₂ readings, only with ≥ 5 of them. |
| **Breathing disturbances** | Desaturation events × 60 ÷ minutes asleep (events per hour). Needs ≥ 2 h asleep. |

### When a night is "valid"

OpenStrap's own HRV and resting HR are only trusted, and only enter a baseline, when the night has all of:

* a main sleep of at least 3 hours
* at least 6 good RMSSD samples
* at least 120 minutes of good heart rate

On an invalid night, the strap's own nightly HRV and resting HR are **shown** instead, labelled as the strap's, but they're never scored.

---

## 4. Your heart-rate settings: resting and maximum

Code: `LoadModel.kt` (`daytimeRestingHr`) and `MaxHrModel.kt` (`maxHeartRate`).

### Daytime resting HR (for heart-rate reserve)

Heart-rate reserve is defined against resting HR while **awake**. Using the much lower sleeping minimum would make every waking minute look harder than it was. In order of preference:

1. The median of the strap's daily resting HR over the last 14 days.
2. Otherwise, the median of the nightly resting HR over those 14 days, plus a typical day–night gap. The gap is the median of (daily − nightly) on recent days that have both, else 3 bpm.
3. Otherwise, 60 bpm.

### Maximum HR

One rule for the whole app: Readiness, Energy, load, active minutes, calories, and workout zones when a workout has no max HR of its own (section 13).

1. A **tested** max you entered in the profile (120–230 bpm) always wins.
2. Otherwise, an estimate:
   * from your age: **208 − 0.7 × age** (Tanaka)
   * without an age: the strap's own value from the Zepp profile (140–225)
   * otherwise 190
3. The estimate is **raised, never lowered**, when you've really gone higher, by at most 15 bpm. "Really" means:
   * Each day's peak is the highest heart-rate minute in 30–220 bpm that has a neighbouring minute within 10 bpm of it. A lone spike isn't a heartbeat.
   * The strap's daily exercise maximum can raise a day's peak, but only when it's in 30–220 bpm and within 10 bpm of that day's filtered minute peak. On a day with no filtered minute peak it's ignored, because nothing backs it up.
   * Two separate days must reach within 3 bpm of each other. The lower of those two is your observed peak, so one freak day can never set it.
   * Days come from the last 90.

---

## 5. Readiness (0–100, each morning)

Code: `ReadinessModel.kt` (`readiness`).

"How far last night sat from your own normal." It's a comparison, not a prediction.

### Step 1: protect the baselines

The body-signal check (section 6) runs first. Any earlier night in **Watch** or **Alert** is left out of every Readiness baseline, so a sick week can't become your new normal.

### Step 2: learning phases

The phase is set by how many valid nights are in the HRV or resting-HR baseline, whichever has more.

| Valid nights | What you see |
|---|---|
| 0–6 | HRV and resting HR withheld. The strap's own Readiness is shown as "Strap's estimate". |
| 7–13 | Scored, labelled "learning" |
| 14–27 | Scored |
| 28+ | Labelled "calibrated" |

### Step 3: score each contributor

Each contributor scores 0–100. Weights are a design choice, and the evidence review explains them.

| Contributor | Weight | How it's scored |
|---|---|---|
| **HRV balance** | 30 % | 0.4 × z(tonight) + 0.6 × z(7-night average), on the log scale, through the dead zone, then z → score. The 7-night average needs ≥ 3 valid nights in the past 7 days (tonight included); below 5 it's marked "thin". Without it, tonight alone. The detail shows the % difference from usual. |
| **Resting heart rate** | 22 % | Tonight's lowest 30-minute average vs baseline, flipped so lower is better, through the dead zone, then z → score. |
| **Last night's sleep** | 16 % | The strap's sleep score when it gave one, otherwise OpenStrap's (section 9). |
| **Sleep balance** | 12 % | 100 up to 1 h of sleep debt (section 9), then falling linearly to 0 at 6 h. So 2 h of debt → 80, 3 h → 60 and 4 h → 40. |
| **Body temperature** | 6 % | Distance from your usual nightly skin temperature, in **either** direction, through the dead zone, then z → score. |
| **Breathing rate** | 4 % | Like temperature: a change either way counts. |
| **Previous day's load** | 4 % | Yesterday's training load ÷ your usual (fitness, section 8, up to the day before). A ratio up to 1.5 → 100, then −25 points per extra 1.0, never below 30. Needs 7 days of load. |
| **Blood oxygen** | 3 % | Only a **drop** counts: 100 − 25 points per % below your usual, after a 1 % free band. So −2 % → 75 and −3 % → 50. Higher than usual is no better. |
| **Recovery index** | 0 % (shown only) | When the night's lowest HR came. Shown with the others but not scored: no peer-reviewed study links its timing to recovery. In the first half of the night → 100. Later, it loses 1.2 points per % of the night after halfway, never below 40. |

Temperature, breathing and blood-oxygen baselines use all usable past nights, not only valid ones, and need 7 of them.

### Step 4: combine

* **No HRV and no resting HR contributor → no score.** Sleep alone isn't readiness. You see the sleep contributor and the strap's estimate instead ("withheld").
* Otherwise: **Readiness = weighted average of the contributors present.** The weights are re-scaled over what's there, so a missing signal doesn't count as zero.
* **Red-flag cap:** if any body signal (HRV, resting HR, temperature, breathing) scored below 40, Readiness can be at most that score + 30. One very bad signal shouldn't be averaged away by a good night's sleep. Blood oxygen doesn't count here: a wrist reading is off by 2 to 4 points, so a low night is more often the sensor.
* **Alert cap:** during a body-signal Alert, Readiness is at most 54, so it always lands in the lowest band.

### Extras shown with Readiness

* **HRV normal range:** the 7-night HRV is "below", "within" or "above" your usual ± 0.5 spread.
* **Data-quality notes:** shown when OpenStrap's and the strap's values disagree by more than 15 % (HRV) or 3 bpm (resting HR).
* **7-day trend:** Readiness recalculated for each of the last 7 mornings, each against its own baseline (the 60 nights before it) and with the daily loads up to the day before it, so a past day shows what that morning showed. (Loads use today's resting and max HR, so a change to those since can still move a past value slightly.)

### Breathing disturbances card (not part of Readiness)

Tonight's events per hour, next to the median of your earlier nights (up to 60, shown once there are 7). It shows no severity words, because those belong to apnea diagnosis.

---

## 6. Body signals off baseline

A night-by-night watch on resting HR, skin temperature and breathing rate, for "something's off, take it easy". Each night is **Normal**, **Watch** (shown in the app) or **Alert** (one push notification, Readiness capped at 54, the top of the lowest band).

### Baselines for this check

* Earlier valid nights only, skipping the 5 most recent nights and any night that wasn't Normal. Illness can lift resting HR and temperature 3 to 5 days before symptoms, so those nights stay out of the baseline they are judged against.
* The last 28 such nights.
* No alerts at all until resting HR has 14 nights (a 28-night window covers a whole menstrual cycle).
* Resting HR here is the **sleep-mean HR**, not the lowest 30 minutes. Our own value is used on a valid night, else the strap's.

### Flags each night

| Flag | Rule (difference from usual) | Counts as |
|---|---|---|
| Resting HR flag | up ≥ 4 bpm **and** ≥ 2 spreads | core vote (cardiac) |
| Resting HR watch | up ≥ 3 bpm | watch only |
| Temperature flag | up ≥ 0.5 °C **and** ≥ 2 spreads | core vote (thermal) |
| Breathing flag | up ≥ 2 /min **and** ≥ 2 spreads | core vote (respiratory) |
| HRV support | z ≤ −2 | supports resting HR, never a vote of its own |

Only signals from **different families** count as separate votes. 0.5 °C is used because the normal luteal-phase rise (about 0.3 °C at the finger) would otherwise fire every cycle.

### Deciding the state

**Alert** when any of these holds:
* a resting-HR flag on two nights in a row
* core flags from 2 or more families on the same night
* a core flag tonight, and core flags on at least 3 of the last 7 nights
* breathing up ≥ 3 /min (and ≥ 2 spreads): an alert on its own

Once on, an Alert stays until **2 nights in a row without a core flag**.

**Watch** when there's any core flag, or a resting-HR watch together with HRV support.

**Normal** otherwise.

**Gaps:** one missing night is bridged; two or more missing nights reset the run.

**Likely cause:** a hard training day the day before (more than 1.5× your fitness, the 42-day load that Readiness compares yesterday with) is named next to an alert, but never cancels it.

---

## 7. Energy (0–100, minute by minute)

Code: `ChargeModel.kt` (`charge`).

A body-battery: sleep fills it, and the day drains it.

### How it runs

* The simulation starts **8 days before** the selected day at 50. The first night washes the starting guess out, and the week gives the wake values for a restart after a night with the strap off. For a past day it runs up to 14 hours past the day's end, only to see when the strap went back on.
* Every minute gets one state, checked **in this order**:

| State | When | Change per minute |
|---|---|---|
| **Sleep** | inside a sleep stage | + 0.006 × stage weight × relaxation × HRV factor × (100 − charge) |
| **Not worn** | no heart rate, and the strap said it was off your body, or marked off-wrist, or a gap of 10+ minutes | − 0.0482 (charge ≥ 50) or − 0.0349 (below), then no value (see below) |
| **No data** | a heart-rate gap under 10 minutes, or one not synced yet | 0, the value holds |
| **Exercise** | HRR ≥ 30 % and moving, or 10+ minutes in a row at HRR ≥ 30 %, or in a workout's stress exclusion | − 0.04 − 0.32 × TRIMP of that minute |
| **Exercise recovery** | after exercise, while HR isn't rising | − 0.04 |
| **Activity** | HRR ≥ 20 % and moving (or in a workout's stress exclusion), or ≥ 60 steps that minute | − 0.04 − 0.32 × TRIMP of that minute |
| **Around a workout** | in the stress exclusion (below) | Activity inside the workout, Exercise recovery in the 20 minutes after; Rest in either when HRR < 10 % |
| **Stress** | stress ≥ your threshold for 3+ minutes in a row, awake | − 0.04 − 0.007 × (stress − threshold) |
| **Relax** | stress < 25, < 10 steps and HRR < 20 % | + 0.00015 × (100 − charge), no drain (85 → 86 over 8 hours) |
| **Rest** | anything else awake | − 0.0005 × charge (0.04 at 80, 0.025 at 50) |

Energy is always kept within 0–100.

* **Moving** means the strap counted 1–250 steps that minute, as for daily load. Sitting with a raised heart rate (heat, coffee, a fever) isn't Activity or Exercise outside a workout, short of a sustained run (below): it's Stress if the stress score says so, otherwise Rest, and it can't Relax.
* **Sustained effort:** 10 or more minutes in a row at HRR ≥ 30 % outside a workout count as Exercise even without steps (unrecorded cycling or rowing). Shorter spikes stay Stress or Rest.
* **Not worn** uses the strap's own off-body reports where OpenStrap was connected to hear them, plus the heart-rate gap guess everywhere (an "on body" report doesn't cancel it, as it may be stale). Energy then follows Zepp: it drifts down at Zepp's default no-wear rate (about 2.9 an hour above 50). After 384 not-worn minutes (6.4 hours) since waking, or straight away when the strap is off over your usual sleep time, there is no value (a gap in the chart). After a night off, Energy restarts when the strap is back on from the median of your last 7 wake values (80 without any), and the day says "Strap off overnight, Energy estimated." After a long stretch off in the day, the gap lasts until the next sleep, which starts from 50.
* **Rest drain** is proportional to charge, so a desk day still runs Energy down but more slowly as it falls, as Zepp's mental pool does; Zepp's physical pool doesn't drain at rest at all.
* **Body-signal Alert:** from the start of a night in Alert until the next sleep starts, every gain is × 0.5 and every drain × 1.5 (Zepp's health-alarm limits). Watch changes nothing.

**Sleep details**
* **Stage weight:** deep 1.0, light 0.8, REM 0.65, awake-inside-sleep 0.1.
* **Relaxation:** 1.2 − 0.04 × (HR − that night's resting HR), kept between 0.5 and 1.2. A calmer heart recharges better.
* **HRV factor:** 0.75 + 0.25 × (the sleep's HRV contributor ÷ 80), kept between 0.6 and 1.1. Each sleep uses its own night's contributor, so an earlier night in the replay isn't scored by tonight's HRV.
* Recharge is a share of the room left, so it slows as you fill up. About 30 → 92 over 7.5 hours of normal sleep.

**Exercise recovery details**
* An "after-burn" tally adds up exercise TRIMP and halves about every 20 minutes once exercise stops (× 0.966 per minute).
* While the tally is above 5 and HR isn't climbing (at most 2 bpm above the last reading), raised HR counts as recovery, not stress.

**Stress readings:** a minute uses the latest stress reading from the past 10 minutes.

**Stress exclusion around workouts:** the strap's stress score comes from HRV, which exercise suppresses, so readings taken during a stored workout (phone or strap, active time only, pauses left out) or in the **20 minutes after** it ends are ignored. 20 minutes is the half-life of the after-burn tally above. A phone workout's pause counts as an end, so a pause is also covered for its first 20 minutes. After a hard workout HRV stays down longer than heart rate, so a finished workout with aerobic training effect ≥ 3 keeps its stress readings out for **60 minutes** after its end, and one ≥ 4 for **90 minutes**. Only the readings are dropped there: the minutes count as ordinary minutes otherwise. In the 20-minute window:
* Energy never enters Stress or Relax, and a reading taken there isn't carried into the minutes after it.
* The personal stress threshold doesn't use those readings.
* The day's stress (the Today tile, the day chart, which shows a gap, and the daily average, lowest and highest behind the week, month and year views) leaves them out.
* The readings are still stored and exported.

### Your personal stress threshold

The point where the strap's stress score starts to drain Energy.

* Default: **60**, where Zepp's "medium" band begins.
* Personal: built from **calm readings** over the last 14 days. A calm reading is awake, under 10 steps that minute, HRR under 20 %, and outside the stress exclusion around workouts.
* Threshold = median calm reading + 10, kept within 50–70.
* Needs readings on at least 3 days and at least 60 readings.

### What the screen shows

* Energy now (the last minute with data), Energy at wake, and the day's highest and lowest.
* **Recharged:** the total gained during the day.
* **Drained:** split into exercise, stress and everyday living. In exercise and stress minutes, the basic 0.04 part (× 1.5 in Alert) goes to "living" and the rest to exercise or stress.
* Nothing is shown until the simulation has seen at least one sleep, because without one the number would only reflect the starting guess.

---

## 8. Training load, Strain and fitness/fatigue

Code: `LoadModel.kt` (`dailyLoad`, `strain`, `fitnessFatigue`).

### Daily training load (TRIMP)

Two parts, added up for the day's load and shown split on the Load screen ("62 today · 48 workouts · 14 everyday"), as Zepp's Exertion splits workout from everyday activity:

* **Workouts:** every minute of a recorded workout (phone or strap) counts, whatever the intensity, except time a phone workout was paused, so an easy warm-up counts too. The minute's heart rate comes from the workout's own recording when it has one. A phone and a strap workout over the same time count each minute once.
* **Everyday:** outside workouts, a minute counts only when you're **awake** and the strap counted **at least one step** that minute, at HRR ≥ **35 %**, or at HRR ≥ **20 %** when it has **60 or more steps** (a walk). A raised heart rate while sitting is not training load. The walking exception is there because sustained walking at about 100 steps a minute sits at HRR 24–34 %, under the 35 % line. A minute also needs at least 3 of the last 5 minutes (itself included) to pass the same test, so single PPG spikes don't add load.
* Steps are the movement signal because the strap's other per-minute activity fields don't separate sitting from walking in real data.
* **Without steps:** 10 or more awake minutes in a row at HRR ≥ **30 %** outside a workout (and the 20 minutes after one) count, all of them, as everyday load. It is the same sustained run that Energy counts as Exercise, so unrecorded cycling or rowing counts in both. Weights between sets rarely make one, so record a workout for them.
* Fitness, fatigue, the acute:chronic ratio and Readiness all use the total. A workout's EPOC (below, section 13) is a different measure and is never added in.
* For history, days with under 8 hours of heart-rate data are skipped (strap mostly off), unless a recorded workout is in them: its load still counts. Up to 90 days are used.
* Today's load is the running total so far.

### Strain (0–21)

Strain = 21 × (1 − e^(−TRIMP ÷ 180))

A fixed curve, so a 12 today means the same as a 12 last year.

| TRIMP | Strain |
|---|---|
| 50 | 5.1 |
| 100 | 9.0 |
| 180 | 13.3 |
| 360 | 18.2 |

**"vs your usual":** the share of your last 90 worn days with a load at or below today's (0–100). Needs 7 days.

### Fitness, fatigue and form

Classic 42-day / 7-day model, over worn days only (an unworn day holds rather than decays):

* **Fitness (CTL)** moves toward each day's load by 1 − e^(−1/42) ≈ 2.4 % a day.
* **Fatigue (ATL)** moves the same way by 1 − e^(−1/7) ≈ 13.3 % a day.
* **Form** = fitness − fatigue, as they stood before the latest day.
* Both start at the average of the first week, so a short history doesn't start from zero. Shown once there are 7 days of load.

### Acute:chronic ratio (Load screen only, labelled contested)

* Acute = 7-day EWMA of all days.
* Chronic = 28-day EWMA of the days **before** the last 7, so this week isn't counted twice.
* Ratio = acute ÷ chronic, with the chronic value floored at 20 to avoid dividing by almost nothing.
* Needs 21 days. It's descriptive only and never enters Readiness.

### Long run (Load screen, Day view)

A run (outdoor, treadmill or trail; phone or strap) that is more than **10 % longer** than the longest run in the 30 days before it gets a sentence under the day's Load. It needs at least 2 earlier runs in those 30 days. Time is the active time, pauses left out; a strap run under a phone run counts once. In 5,205 runners a single run more than 10 % longer than the month's longest raised the injury hazard 1.5 to 2.3 times, while the acute:chronic ratio didn't predict injury (Frandsen 2025). The study measured distance; we use time, which every run has.

---

## 9. Sleep screen

### OpenStrap's sleep score

Used only when the strap didn't give its own score:

**Sleep score = 0.7 × duration + 0.3 × continuity**

* **Duration** = 100 × (time asleep ÷ need)², capped at 100. 7 h of an 8 h need → 77, and 6 h → 56.
* **Continuity** = 100 − (WASO − 30 min) × 100 ÷ 90, kept within 0–100. Up to 30 minutes awake is free, and 120 minutes scores 0.
* Without stages (no WASO), duration alone.
* Sleep stages are shown but **not scored**: consumer devices agree poorly with lab staging.

### Sleep score rating and headline

The sleep score (the strap's, or OpenStrap's above) is rated in Zepp's bands: **90–100 Optimal, 80–89 Good, 60–79 Fair, 0–59 Pay attention**. The Sleep screen shows it next to the score, and the Recovery tab uses it for the Sleep contributor. Other contributors keep their own 85 / 70 / 55 bands.

The headline names what pulled the night down, checked in this order:

* **Short night:** under 6 h asleep (the bottom of Zepp's duration bands).
* **Broken sleep:** 30 min or more awake after falling asleep (where continuity starts losing points). The number of wake-ups isn't used: wearables catch only 29–52 % of real wake time, so a count adds up their errors.
* Both → **Short, broken night**. Either one wins over the score: a short night at 70 is still "Short night".
* Otherwise by score: 90+ **Solid night**, 80+ **Restful night**, below 80 **Okay night**. Deep and REM never name the night: wearable staging is too unreliable (the Mi Band 5, the closest published relative of the strap, scored kappa 0.22).

Total sleep in "What shaped the score" is rated against need, plus Zepp's oversleep bands: over 9 h 30 is Fair, over 10 h Pay attention.

### Sleep need

* 8 h for ages 18–64, 7 h 30 m from 65.
* You can set your own between 7 h and 9 h in the profile.

### Sleep debt

Built over the last 14 nights, oldest first. Naps count, and missing nights are skipped. For each night, shortfall d = need − total sleep:

* **Short night (d ≥ 0):** debt = debt × 0.906 + d
* **Long night (d < 0):** debt = max(0, debt × 0.906 − min(the surplus, 2 h))

0.906 means old debt halves over 7 nights. A long night repays all its extra sleep, at most 2 h: in the lab the first free night ran about 2.2 h above need (Kitamura 2016), and one lie-in still can't undo a short week on its own. Needs 7 of the 14 nights.

### Sleep Regularity Index (SRI)

This is the **Regularity** number on the Sleep screen ("This week" and "Sleep health"), worked out for the selected day.

* Each day is split into 1,440 one-minute slots from **noon to noon**, so a night isn't cut at midnight.
* Each slot is marked asleep (deep, light, REM, naps included), awake (an awake stage, or the strap was worn), or missing.
* Each slot is compared with the same slot the next day, over 8 days (7 pairs), ending at noon on the selected day. Only pairs where both minutes are known count.
* **SRI = −100 + 200 × (matching pairs ÷ valid pairs).** This is the standard scale (Phillips et al. 2017, as GGIR and Windred et al. 2024 report it): 100 means an identical schedule every day, 0 is what random sleep gives; in UK Biobank the middle half scored 73.8–86.3. Shown rounded; the bar is clamped to 0–100.
* Withheld under 120 hours of valid pairs (7200, Windred's validity rule). "This week" then says "Not enough nights yet" with the hours so far; the Sleep-health panel falls back to the spread (SD) of your sleep midpoints over the last 7 days, from at least 4 sleeps.
* Bands: under 72 irregular, 72–78 fair, 79–86 regular, 87+ very regular. These are the UK Biobank SRI quintile cuts (71.65 / 78.56 / 83.17 / 87.32, median 81.0; [Windred et al. 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC10782501/)), rounded, with the middle two fifths merged. Irregular is the bottom fifth, where most of the mortality risk sat. They place you against that population; they aren't clinical thresholds, and the strap's sleep/wake minutes aren't GGIR's.
* Zepp's 60/80/90 bands belong to its own night-overlap score (a different formula; see `analysis/native/05-sleep-regularity.md` in the parent project) and aren't used.

### Sleep-health count (0–3)

One point each for:
* **Duration:** 7–9 h asleep (7–8.5 h from 65).
* **Continuity:** WASO ≤ 30 min, or ≥ 90 % of the sleep period actually asleep.
* **Regularity:** SRI ≥ 72, or midpoint spread under 1 hour.

The sleep midpoint is flagged when it falls outside 01:30–04:30, but it's never scored.

### Usual sleep times (Sleep screen, "This week")

The average fall-asleep and wake-up time over the last 7 nights' main sleeps, from as many of them as have sleep. Bedtime is averaged from noon and wake time from midnight, so neither wraps in a normal night. They're drawn as dashed lines on the week chart. They are not scored: the bedtime/wake-spread score that used to sit here (100 × (1 − spread ÷ 120), Zepp's bands) was replaced by the SRI above.

### Sleep vitals "usual / higher / lower"

Last night's HRV, blood oxygen, breathing and skin temperature are compared with the previous 13 nights:
* "Usual" = within the mean ± 1 standard deviation.
* Needs 7 nights; before that it says "Learning n/7 nights".

---

## 10. Activity: steps, distance, active minutes

### Steps today

The synced minute-by-minute steps, plus the strap's live counter. A believable live total is used directly. On firmware 3.25 the live total carries a large fixed offset, so OpenStrap anchors it: it remembers the live reading each time the synced total changes, and adds only the steps counted since.

* A warning shows when the strap's daily total and the sum of minutes differ by more than 5 %.
* **Active hours:** hours of the day with at least 250 steps.

### Default step goal

8,000 a day, or 7,000 from age 60. That's where most of the measured health benefit is reached.

### Distance

Steps × step length, from your height:
* under 120 steps/min: 0.414 × height
* 130+ steps/min: 0.45 × height
* in between, a straight line between the two

It's an estimate, always shown with "≈". It needs a height.

### Moderate-to-vigorous minutes (MVPA)

Minutes are counted in two passes, and no minute counts twice.

1. **By cadence:** awake minutes at ≥ 100 steps/min (at every age), in runs of at least 2 minutes in a row. At ≥ 130 steps/min a minute is vigorous.
2. **By heart rate**, for effort without steps (cycling, rowing): awake runs of at least 5 minutes at HRR ≥ 40 %, with under 100 steps/min or inside a workout. At HRR ≥ 60 % a minute is vigorous. These are marked "HR-assisted". WHO 2020 dropped the 10-minute bout rule, so 5 minutes is only there to keep stairs and short spikes out.

**Weekly target:** moderate + 2 × vigorous over the last 7 days, against 150 minutes (WHO).

Minutes with more than 250 steps are treated as bad data.

---

## 11. Energy expenditure (calories)

It needs weight, height and age, and is built minute by minute on top of your resting burn. No allowance for digesting food is added.

### Resting burn (BMR, Mifflin–St Jeor)

BMR (kcal/day) = 10 × weight kg + 6.25 × height cm − 5 × age + sex term

The sex term is +5 for men and −161 for women. With sex not set, it's −78 (halfway).

b = BMR ÷ 1,440 is the resting burn per minute.

### Each minute with data

Taken in this order:

| Minute | kcal |
|---|---|
| Inside a recorded workout | a strap workout's own calories spread evenly over its minutes, a phone workout's own per-minute calories (shaped by its heart rate, scaled to its saved total), never below b |
| Asleep | 0.95 × b |
| ≥ 60 steps | b + extra for the activity's MET (below) |
| A few steps, or steps in a neighbouring minute | 1.3 × b |
| Still | 1.15 × b |

**Extra for an activity** = (MET − 1) × 3.5 × weight ÷ 200 kcal/min. Starting from BMR means rest isn't counted twice.

**Walking MET by cadence** (Moore et al. 2021, measured walking oxygen cost):
* VO2 = −0.4367 + 0.001430·c² − 0.001455·age·c − 0.000009012·BMI·c² + 0.0008086·age·height (ml/kg/min, c in steps/min, height in cm), MET = VO2 ÷ 3.5, at least 1. Age is held to the study's 21 to 84.
* For a 30-year-old, 175 cm, BMI 22.9: 60 steps/min → 1.6, 100 → 3.3, 130 → 5.4. Older and heavier people get a little less per step at the same cadence, taller people a little more.

**Running:** 145+ steps/min in this and a neighbouring minute uses the ACSM running equation, MET = (3.5 + 0.2 × speed in m/min) ÷ 3.5. Speed is cadence × step length.

**Heart-rate boost ("HR-assisted"):** for awake, non-workout minutes with under 20 steps, in runs of at least 5 minutes at HRR ≥ 40 % (the same run as active minutes):
* MET = 1 + HRR × (VO2max ÷ 3.5 − 1), capped at 6.
* VO2max is your estimate (section 12), else 35.
* This replaces the step-based value only when it's higher.

### Day totals

* Only minutes with data are added up. Under 20 hours of data, the day is marked **partial**.
* **Activity level (PAL)** = total ÷ BMR on a full day. Outside 1.2–2.5 it's flagged as likely a wear or profile problem.

---

## 12. Estimated fitness (VO2max) and fitness age

A non-exercise estimate (Jurca et al. 2005), recalculated weekly as of the first day of the week in the phone's locale (Sunday in the US).

### Formula

METs = 2.77 × (1 if male, 0 if female) − 0.10 × age − 0.17 × BMI − 0.03 × resting HR + activity score + 18.07

VO2max (ml/kg/min) = METs × 3.5

| Activity answer (profile) | Score |
|---|---|
| Inactive, little beyond daily life | 0 |
| Light activity ≥ 10 min, 5+ days a week | 0.32 |
| Aerobic exercise 20–60 min a week | 1.06 |
| Aerobic exercise 1–3 h a week | 1.76 |
| Aerobic exercise over 3 h a week | 3.03 |

**Inputs**
* Resting HR is the median of your last 7 nightly resting HRs (valid nights). It needs at least 5 nights in the last 2 weeks.
* BMI = weight ÷ height².

**Shown as a range:** the value ± 1.45 METs (about ± 5 ml/kg/min), rounded to whole numbers, never a decimal.

**Withheld when** any of these holds:
* age is outside 20–79
* sex isn't set
* weight and height weren't entered in the last 90 days
* the activity question wasn't answered in the last 6 months
* you're taking beta-blockers or are pregnant (resting HR doesn't reflect fitness then)
* there aren't enough resting-HR nights

### Percentile band (FRIEND 2022)

Your estimate is compared with the treadmill reference table for your age decade and sex (Kaminsky et al. 2022). Bands: below 30th, 30th–50th, 50th–70th, 70th–90th, top 10 %.

### Fitness age

* The age at which the median person of your sex has your VO2max.
* Medians are placed at each decade's midpoint (25, 35 … 85) and joined with straight lines.
* Shown as a range of ± 10 years, kept within 20–80, e.g. "typical of a woman aged about 35–55".

---

## 13. Workouts

* **Workout end:** the strap's sport-effect end time. Without it, the last HR sample minus 3 minutes (the strap keeps recording 3 minutes for recovery HR, and never before the recorded end). Otherwise the recorded end.
* **Heart-rate zones** are % of max HR:

| Zone | % of max HR |
|---|---|
| 1 | 50–60 |
| 2 | 60–70 |
| 3 | 70–80 |
| 4 | 80–90 |
| 5 | 90+ |

  * Time between samples counts toward the zone of the earlier sample, at most 5 s per gap.
  * Max HR here is the strap's value from the workout. Without it, your usual max HR from section 4 (tested value, else Tanaka or the strap's value, raised by observed peaks).
* **Recovery HR:** HR at the end minus HR 60 s and 120 s later. Each reading is a median over ± 2 s. It's only treated as meaningful when you finished at ≥ 65 % of max HR.
* Training effect and the strap's recovery time come from the strap unchanged for the strap's own workouts. Workouts started in OpenStrap get OpenStrap's own, below.

### Training effect, EPOC, recovery and VO2max for phone workouts

The strap works these out for the workouts it records. Zepp works them out for app workouts with neural networks whose weights are its own (analysis `native/04` §1), so OpenStrap uses open methods on the same scales. Code: `WorkoutEffect.kt`, fed by `WorkoutEffectEngine.kt`. Worked out when a workout ends, again after a trim or a gap fill, for older workouts the first time their summary is opened, and for every workout once when the model changes (`WorkoutEffect.MODEL`).

**The strap's own figures come first.** When the strap kept a record of nearly all of a phone workout (at least 90 % of it, as a session it ran alongside does), the workout takes the strap's EPOC, aerobic and anaerobic training effect and recovery time instead of the model's, after the sync that brings them (`WorkoutEffectEngine.strapEffect`). A record the strap only started partway through, as an auto-detected one, covers too little and the model stands. The rest of this section is that model.

**Epochs.** Heart rate in 5-second epochs, each read as the median of its readings between 40 and 220 bpm, as Zepp does. An epoch with no reading keeps the last one twice, then counts as missing: a dropout neither adds load nor clears it.

**EPOC (ml/kg), shown as EPOC.** Added up epoch by epoch from heart-rate reserve x (0 at resting, 1 at max; resting and max HR from section 4). As in Zepp's own model (analysis `native/04` §4.2) it only adds, nothing clearing during the workout:

EPOC ← EPOC + 0.077 × (e^(4.5x) − 1) × minutes

* The share rises steeply with intensity, as EPOC does (Børsheim & Bahr 2003), and grows with duration.
* The workout's EPOC is the total, shown as a whole number labelled "Load", as the strap's is, so the two compare. It is the workout's effect figure, not daily load: daily load is TRIMP only (section 8).
* 0.077 is fitted to the strap's own EPOC on three of its workouts with the same heart rate (37 minutes at 117 bpm gave 16.3, 9 minutes at 119 gave 6), all easy; 4.5 keeps harder work where it was aimed: 30 minutes at 60 % HRR ≈ 31 (TE 2.5), six 3-minute intervals at 92 % ≈ TE 3.7, an hour at 85 % at TE 5.
* An earlier version (until `WorkoutEffect.MODEL` 2) let EPOC clear as it went and took its peak. It held a long easy walk to about a third of the strap's figure.

**Aerobic training effect (0–5).** EPOC against Zepp's breakpoints for your activity class (analysis `native/04` §4.4–4.5), joined by straight lines and rounded down to a tenth:

| Class (last 28 days' workout EPOC) | TE 1 | TE 2 | TE 3 | TE 4 | TE 5 |
|---|---|---|---|---|---|
| 40 (≤ 450) | 6.5 | 12.8 | 40.9 | 96.4 | 156.7 |
| 50 (≤ 570, or none) | 7.9 | 15.7 | 49.7 | 119.8 | 199.2 |
| 60 (≤ 720) | 9.4 | 18.6 | 58.8 | 141.3 | 242.1 |
| 70 (≤ 870) | 10.8 | 21.5 | 67.5 | 163.9 | 292.4 |
| 75 (≤ 1020) | 11.5 | 23.0 | 71.9 | 177.1 | 316.2 |
| 80 (≤ 1320) | 12.3 | 24.5 | 76.2 | 189.7 | 343.3 |
| 85 (≤ 1570) | 13.0 | 25.8 | 80.9 | 201.8 | 384.5 |
| 90 (≤ 1920) | 13.8 | 27.5 | 85.3 | 215.3 | 418.9 |
| 95 (≤ 2450) | 14.6 | 28.9 | 89.6 | 230.6 | 429.5 |
| 100 (above) | 15.4 | 30.6 | 94.2 | 245.3 | 445.9 |

* The 28-day sum adds the phone's workout EPOC and the strap's workout load, counting a workout both recorded once.
* No effect for a minute or less, or under 1 ml/kg, as Zepp.

**Anaerobic training effect** is not worked out ("—"). Zepp's comes from another network; heart rate alone lags short efforts too much for an open model to hold up.

**Recovery time.** From aerobic TE, joined by straight lines: TE 1 → 5 h, 2 → 10 h, 3 → 20 h, 4 → 40 h, 5 → 72 h. TE 2 sits by the strap's own 11 h for a walk at TE 2.1; the rest keeps the shape.

* Up to 25 % longer when fatigue runs ahead of fitness: × (1 + 0.25 × (ATL − CTL) ÷ CTL), kept between ×1 and ×1.25, with CTL at least 5. Fitness and fatigue are section 8's 42/7-day model run over daily workout EPOC.
* Never less than what an earlier workout (phone or strap) still has to run, as Zepp's own rule. Never more than 72 h.
* A design choice, not a fitted model: Zepp switches its own off for app workouts (`native/04` §6.2), so only its shape is kept.

**VO2max (outdoor runs with GPS).** The ACSM running equation on the level, VO2 = 3.5 + 0.2 × speed (m/min), at heart-rate reserve x gives VO2max = 3.5 + (VO2 − 3.5) ÷ r, with oxygen reserve r = (x − 0.0886) ÷ 0.972. Heart-rate reserve runs 6 to 8 points above oxygen reserve (Ferri Marini 2021, 451 HERITAGE adults), so dividing by x itself read VO2max 9 to 14 % low. Gated as Zepp gates its own (`native/04` §5.2):

* Outdoor run only, with GPS (the route in memory at the end, or a kept one).
* At least 120 qualifying epochs (about 10 minutes): ≥ 1.5 m/s and ≥ half of max HR.
* 2-minute windows every 30 s, from 2 minutes in. A window counts when its four 30-second speeds (from the smoothed track) have an SD ≤ 0.3 m/s, heart rate over its second minute (once HR has caught up with the pace) has an SD ≤ 4 bpm, x is 0.5–0.95, and GPS height changes by no more than 2 % of the distance.
* The result is the median of at least 5 such windows, which must be at least 30 % of the windows run at pace, with their middle half within 5 ml/kg/min, and between 20 and 90. Otherwise none.

---

## 14. Trends and "your normal" ranges

* **Daily values** for charts: the average of the day's samples (the total for steps), with the day's lowest and highest. Resting HR uses the strap's one value per day.
* **Year view:** days are averaged into Monday-starting weeks.
* **"Your normal"** on metric screens is the mean ± 1 standard deviation of the last 30 days. It needs 7 days with data. Today is "above", "within" or "below".
* **Weekly resting-HR chart:** the strap's daily resting HR. Without it, OpenStrap estimates one as the day's lowest 30-minute average HR. That needs ≥ 6 hours of data, ≥ 5 samples in the window, and the window covering at least 80 % of 30 minutes. These days are marked as estimated.

---

## 15. Known gaps and inconsistencies in v1.2.0

Found while writing this document. These are not bugs in the formulas above, but places where two parts of the app disagree or a setting isn't wired up. Worth a look before the 2026-10-30 review.

1. **Two regularity measures (fixed).** The Sleep screen used to show a bedtime/wake-spread score while the sleep-health count used the SRI. Both now use the SRI (section 9).
2. **Two resting-HR definitions on purpose.** Readiness uses the lowest 30-minute average; body-signal alerts use the sleep-mean HR. This is intended: the calibration review will pick one.
