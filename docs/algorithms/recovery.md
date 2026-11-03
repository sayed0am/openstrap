# Readiness and Energy

OpenStrap's own recovery model. Code, in `app/src/main/kotlin/org/openstrap/app/data/`: the pure model is one file per part, `ReadinessModel.kt` (Readiness), `ChargeModel.kt` (Energy), `LoadModel.kt` (training load) and `MaxHrModel.kt` (maximum heart rate), with the inputs, results and constants they share in `Recovery.kt`. `RecoveryEngine.kt` feeds them from the database (database → model). Tests: `app/src/test/.../RecoveryTest.kt`. Constant names below match the code.

Planned changes and their evidence: [evidence-review.md](evidence-review.md).

## Why our own

Zepp's **BioCharge** is not reusable:

* The phone side is a closed native library, `libcharge_jni` (`ChargeAlgorithm.runChargeAlgo`), which takes about 40 arguments.
* The strap side runs over channel module `0x51`.
* The decompiled app shows only the inputs and outputs: per-minute stress, HR, activity, sleep stages, resting HR, age and sex in; `totalCharge`, `phyCharge`, `mentCharge`, `chronicWeight` and `exertionScore` out. Readiness is the same story (`libreadiness`).

Two ideas from Zepp carry over: splitting charge into physical and mental drains, and modelling stress apart from activity (`ChargeStressInfo.proto` regresses stress on HR and activity). Nothing else does, and we don't need it. What follows is built from published methods so every rule can be explained and checked.

## Readiness (0–100, each morning)

Code: `ReadinessModel.kt`.

Each contributor compares the night that ended this morning with the user's own history.

### Valid nights and baselines

* **Validity rule** (`Night.valid`): main sleep ≥ 3 h, ≥ `MIN_HRV_SAMPLES` = 6 RMSSD samples in 5–200 ms, and ≥ `MIN_HR_MINUTES` = 120 minutes of heart rate in 30–220 bpm. Only valid nights enter the HRV and resting-HR baselines.
* **Baseline** (`baseline`): centre = median over the prior ≤ `BASELINE_NIGHTS` = 60 valid nights; scale = max(`MAD_SCALE`·MAD, floor), `MAD_SCALE` = 1.4826. A mean and SD let one bad stretch (illness, alcohol, artefacts) become "normal"; the median and MAD don't.
* **Floors:** ln HRV `HRV_LN_SD_FLOOR` = 0.05, resting HR `RHR_SD_FLOOR` = 2 bpm (above device error of about 1–1.8 bpm), breathing `BREATH_SD_FLOOR` = 0.5 /min, skin temperature `TEMP_SD_FLOOR` = 0.15 °C, SpO2 `SPO2_SD_FLOOR` = 0.5 %.
* **Phases** (`phase`, by valid nights; they replace the old 4 / 14 nights):

| Valid nights | HRV and resting HR |
|---|---|
| < `SCORED_NIGHTS` = 7 | withheld; the strap's score and parts show as "Strap's estimate" |
| 7–13 | scored, labelled "learning" |
| 14–27 | scored |
| ≥ `CALIBRATED_NIGHTS` = 28 | labelled "calibrated" |

* **Withheld:** Readiness has no score unless an HRV or resting-HR contributor is present. On 2026-10-16 sleep alone gave 52 against the strap's 91; such a day shows the sleep contributor and the strap's estimate instead.

The strap's own Readiness is logged daily to calibrate this score against; see [readiness-calibration.md](readiness-calibration.md).

### How contributors are scored

Most contributors are z-scores (deviation from the baseline centre in units of its scale, positive = better). HRV, resting HR, breathing and temperature first go through a dead zone at the smallest worthwhile change, `deadZone`: z_eff = sign(z)·max(0, |z| − `SWC_SD`), `SWC_SD` = 0.5, because changes under half a scale unit are noise (Buchheit, Plews). `scoreFromZ` then maps them with a logistic curve, 100 / (1 + e^−(1.386 + 1.19·z)):

| z | Score |
|---|---|
| 0 (normal) | 80 |
| +1 | 93 |
| −1 | 55 |
| −2 | 27 |

### Contributors

**HRV balance** (weight 0.30)
* Input: `hrv_n` = ln(mean of the night's gated RMSSD samples) on a valid night. This definition never changes inside a baseline.
* `hrv_7` = mean of `hrv_n` over the last 7 nights, needing ≥ `HRV_WEEK_MIN_NIGHTS` = 3 valid nights; "thin" below `HRV_WEEK_FULL_NIGHTS` = 5.
* Score: `HRV_NIGHT_WEIGHT` = 0.4·z(tonight) + `HRV_WEEK_WEIGHT` = 0.6·z(`hrv_7`), through the dead zone. Weighted towards the week because nightly wearable HRV error (6–16 % MAPE) is larger than the ~3 % smallest worthwhile change. Without `hrv_7`, tonight alone.
* Normal-range state (`hrvRange`): `hrv_7` against centre ± `HRV_RANGE_SCALE` = 0.5·scale gives below / within / above, the band the HRV-guided training trials used.
* Source: Plews' weekly-average work: a 7-day rolling mean of lnRMSSD is a steadier marker than a single morning (Plews et al., [AUT](https://openrepository.aut.ac.nz/items/d7b41d29-e92e-4acf-90ea-ed68d84d6c73)). Plews et al. 2012 (Eur J Appl Physiol, [PubMed](https://pubmed.ncbi.nlm.nih.gov/22367011/)) is only a case study of 2 athletes. In the HRV-guided training trials, Vesterinen et al. 2016 used **raw** RMSSD from a 4-minute supine morning chest-belt recording, with a band of mean ± 0.5 SD over 4 baseline weeks ([JYX](https://jyx.jyu.fi/handle/123456789/50625)); effects were small (ES 0.42).
* Why log: RMSSD is right-skewed, and ln makes the SD meaningful.
* Why nightly: whole-night averages are the most accurate wearable HRV (Oura vs ECG, JMIR 2022, PMC8808342).
* Body signals: z(`hrv_n`) ≤ −2 supports a resting-HR rise (`HrvSupport`), never a vote of its own.

**Resting heart rate** (weight 0.22)
* Input: `rhr_n`, the night's lowest 30-minute average HR, the window needing ≥ `RHR_WINDOW_MIN_MINUTES` = 20 minutes of data (Garmin's definition). The sleep mean (`rhr_mean_sleep`) and 10th percentile (`rhr_p10`) are logged too, for the review to pick the least noisy one.
* Score: z against baseline, inverted so lower is better, through the dead zone.
* Source: Buchheit 2014 (Front Physiol 5:73): judge HR changes against measurement noise and the smallest worthwhile change, not absolute thresholds.
* Body signals: the core cardiac signal, using the overnight **mean** sleep HR (see below). Overnight resting HR is the best-tested early-warning signal: in Mishra et al. 2020 ([medRxiv](https://www.medrxiv.org/content/10.1101/2020.07.06.20147512v1)) it rose before symptoms in 32 COVID-19 cases, 63 % detectably in real time. That study says nothing about other conditions.

**Last night's sleep** (weight 0.16)
* The strap's own sleep score when present.
* Otherwise our own score, `sleepScore` = `SLEEP_DURATION_WEIGHT` 0.7·duration + `SLEEP_CONTINUITY_WEIGHT` 0.3·continuity (duration alone without stages):
  * `durationScore` = 100·min(1, TST/need)², so 7 of 8 h ≈ 77 and 6 h ≈ 56.
  * `continuityScore` = clamp(100 − (WASO − 30 min)·(100/90), 0, 100), with WASO (`wasoMinutes`) the awake time between the first sleep stage and the final wake.
* No stage target: four-stage agreement across six devices was kappa 0.21–0.53 and deep sleep was right only 47–70 % of the time (Schyvens et al. 2025). Low wake specificity also inflates wearable efficiency by 2–10 points, so time-in-bed efficiency is dropped for WASO. Stages are shown, not rated.
* **Need** (`sleepNeed`): 480 min for ages 18–64, `SLEEP_NEED_65_MINUTES` = 450 from 65 (NSF 7–9 h and 7–8 h, above the AASM ≥ 7 h floor). The user can set their own, `SLEEP_NEED_MIN`–`SLEEP_NEED_MAX` = 420–540 min, in the profile (individual optimal sleep ran 7.3–9.3 h, Kitamura et al. 2016). The optional personal need learnt from unconstrained nights is not implemented yet.

**Sleep balance** (weight 0.12)
* Input: sleep debt (`sleepDebtMinutes`) over the last `SLEEP_DEBT_NIGHTS` = 14 nights, oldest to newest; missing nights are skipped and naps count toward total sleep. λ = `SLEEP_DEBT_DECAY` = 0.5^(1/7) ≈ 0.906 (half-life 7 nights). With d = need − TST: d ≥ 0 gives debt = debt·λ + d; d < 0 gives debt = max(0, debt·λ − min(0.5·|d|, 60 min)).
* Score: 100 − `SLEEP_DEBT_POINTS_PER_HOUR` = 25 points per hour of debt, so 1 h ≈ 75 and 2 h ≈ 50. Withheld with fewer than `SLEEP_DEBT_MIN_NIGHTS` = 7 of 14 nights.
* The debt is cumulative, so it saturates fast: 30 min short every night for two weeks is about 4 h of debt, a score of 0. Worth checking at the review.
* Why half-life 7: the old 4-night half-life discounted nights 8–14 to 25 % or less, undercounting the 14-day build-up. One 10 h recovery night is incomplete (Banks et al. 2010), and about 1 h of debt took 4 nights to clear (Kitamura 2016).
* Source: Van Dongen et al. 2003 (Sleep 26:117): 6 h nights for 14 days caused deficits that built up night by night, equal to one or two nights of total deprivation, which subjects didn't notice.

**Recovery index** (weight 0.03)
* Input: where the night's lowest 10-minute HR falls.
* Score: 100 when it falls in the first half of the night, then sliding down to 40 at the very end.
* Source: Oura's recovery index (a late nadir suggests late meals, alcohol or late exercise).

**Body temperature** (weight 0.06)
* Input: mean skin temperature during sleep (≥ 30 readings).
* Score: `scoreFromZ`(−|z_eff|): a change in either direction beyond the dead zone counts.
* Source: TemPredict (Oura); adding temperature raised a multimodal model's AUC from 0.770 to 0.819 (Mason et al. 2022, [PMC8891385](https://pmc.ncbi.nlm.nih.gov/articles/PMC8891385)). The luteal-phase rise is about +0.3 °C at the finger.
* Body signals: the thermal family (see below).

**Breathing rate** (weight 0.04)
* Input: mean breathing rate during sleep.
* Score: `scoreFromZ`(−|z_eff|), either direction. Nightly breathing rate is very stable, so its floor is 0.5 /min.
* Body signals: the respiratory family (see below).

**Blood oxygen** (weight 0.03)
* Input: mean sleep SpO2 (strap type 38) during the night, from at least 5 readings.
* Score: −`SPO2_POINTS_PER_PERCENT` = 25 points per % below the baseline centre, beyond a 1 % dead band. A night above the usual level scores 100; higher oxygen isn't better. 2 % down ≈ 75, 3 % down ≈ 50.
* Source: healthy adults hold a mean nightly SpO2 of about 96 %, steady night to night (Gries & Brooks 1996, Chest 110:1489). A sustained fall can come with altitude, disturbed breathing or a respiratory problem.
* Never raises a body-signal alert: SpO2 has no early-warning evidence.

**Breathing disturbances** (not part of Readiness: the Breathing card on the Sleep screen)
* Input: the strap's nightly sleep-apnea summary (type 74). We use only its count of valid desaturation events, divided by hours asleep, because the units of its own ODI fields are unverified. Needs at least 2 h asleep.
* Shown (`breathingDisturbances`) as events per hour against the median of the user's own nights (from 7 nights). Moved out of Readiness because it is a stable trait, so inside the score it only acted as a fixed offset.
* Zepp's 5 / 15 / 30 break points (`bn50.java`) are apnea severity bands applied to the strap's desaturation counts. They are never shown: naming them (normal / mild / moderate / severe) would breach the 2026 wellness guidance. It's a wellness estimate, not a diagnosis.

**Previous day's load** (weight 0.04)
* Input: yesterday's TRIMP divided by the chronic EWMA load. Above 1.5× the score falls by 25 points per unit, to a floor of 30.

**Training load balance** (not part of Readiness: the Load panel on the Activity screen, labelled "contested")
* Input: acute:chronic ratio from EWMA, 7 vs 28 days (`acwr`), needing ≥ 14 days of load.
* Out of Readiness because the evidence is against scoring with it: the coupling critique is accepted by its own proponents (BJSM 53/15/921), ACWR rescales acute load (Impellizzeri 2021), and the 2025 meta-analysis for 0.8–1.3 has a CI of 0.14–0.94 (Qin et al. 2025).

Weights (a design choice, to be checked with the backtest) add up to 1: autonomic (HRV + resting HR) 52 %, sleep 28 %, other body signals 13 %, previous day's load 4 %, recovery index 3 %. They're renormalised over the contributors present, so only their ratios matter. Polar's only public hint is that heart rate has "the biggest influence"; bounding sleep's share keeps one short night from carrying the score.

### The strap's own nightly HRV and resting HR

The strap's method is unknown and may change with firmware, and a baseline that mixes two definitions measures the switch, not the body. So:

1. Before 7 valid own nights (and on a withheld day), the strap's Readiness and its parts show as "Strap's estimate". They never enter OpenStrap's score. The strap's score carries that label rather than a rating word. OpenStrap's score and contributors are rated in its four bands (85 / 70 / 55).
2. On a night where our own value fails the validity rule, the strap's `sleepHrv` / `sleepRestingHr` show (and feed the alerts), flagged "strap fallback", and stay out of the baseline.
3. A data-quality note appears when own and strap differ by more than `HRV_QA_FRACTION` = 15 % (HRV) or `RHR_QA_BPM` = 3 bpm (resting HR).

### Combining contributors

* **Weighting:** weights are renormalised over the contributors that have data. Without HRV or resting HR there is no score (see "Withheld").
* **Red-flag cap:** if any physiological contributor (HRV, resting HR, temperature, breathing) is below `RED_FLAG` = 40, readiness can't exceed that contributor + 30. One collapsed body signal shouldn't be averaged away by a good sleep score.
* **Soft minimum (comparison only):** `Recovery.softMinimum` combines the same contributors Zepp's way, w = weight · e^(−(s/100)²) and Σ w·s / Σ w, in place of the weighted mean and the red-flag cap (the Alert cap still applies). It is returned as `softMinScore` and written by the backtest; the app doesn't show it while `SOFT_MIN_FUSION` is off. See [readiness-calibration.md](readiness-calibration.md).
* **Body signals off baseline:** in the Alert state (below) readiness is capped at `ALERT_CAP` = 54 and `strainAlert` is set. The cap is `FAIR_FROM` − 1, one below where the "Take it steady" (Fair) band starts at 55, so a capped Alert day always rates in the lowest band ("Prioritise recovery"). A cap of 55 would put a capped Alert day in Fair, telling the user to take it steady on a day their body signals say otherwise. Nights in Watch or Alert stay out of every Readiness baseline.
* **Wording** (FDA General Wellness guidance, revised 6 January 2026): the feature is called "Body signals off baseline" and never names illness, infection, fever or COVID. Copy lives in `BodySignalsCopy`; the push text is the evidence review's: "Several of your overnight signals (…) are outside your usual range for the second night. This can happen with heavy training, alcohol, travel, poor sleep, or when your body is fighting something off. Consider an easier day. If you feel unwell, checking in with a healthcare professional may be helpful. This is a wellness estimate, not a medical assessment." No screen shows a temperature threshold.

## Sleep regularity and sleep health (Sleep screen, not Readiness)

`SleepRegularity.kt`.

* **Sleep Regularity Index:** 1440 one-minute epochs per local day, starting at `DAY_START_HOUR` = 12:00. Sleep = any deep, light or REM minute, naps included; awake = an awake stage or a minute with heart rate; strap off = missing. Over the last `SRI_DAYS` = 7 day-pairs, only pairs where both minutes are valid count (as GGIR does): SRI = −100 + 200·matches / validPairs. Withheld below `MIN_VALID_PAIRS` = 7200 (120 h), the validity rule in Windred et al. 2024 (60,977 UK Biobank participants, where SRI predicted all-cause mortality better than duration).
* Shown as "Regularity" on the Sleep screen ("This week" and "Sleep health"). Bands at the Windred UK Biobank quintile cuts, rounded (method-dependent, descriptive): < 72 irregular, 72–79 fair, 79–87 regular, ≥ 87 very regular.
* Fallback when withheld but there are ≥ 4 main sleeps in 7 days: SD of the sleep midpoint, "good" under 60 min.
* **Sleep-health count** (0–3, Brindle et al. 2019: OR 0.901 per point for cardiometabolic morbidity): duration 420–540 min (≥ 65: ≤ 510); continuity WASO ≤ 30 min or sleep-period efficiency ≥ 90 %; regularity SRI ≥ 72 (or the fallback). The midpoint is shown and flagged outside 01:30–04:30, never counted.

## Body signals off baseline

`BodySignals.kt`, a night-by-night state machine (evidence-review.md, "Body-signal alerts"). Resting HR is the best-validated early-warning signal (NightSignal, Alavi et al. 2022: +3 bpm a warning, ≥ +4 bpm on 2 consecutive nights an alert). Persistence is what controls false alarms (≥ 3 anomalous nights in 7 cut breathing rate's healthy-period hit rate from 13.4 % to 0.88 %, Natarajan et al. 2021). Only different signal families vote: cardiac, thermal and respiratory; HRV + resting HR is closer to one signal counted twice. That conjunction part is reasoning, not a measured result.

**Baseline per signal:** the last `ALERT_BASELINE_NIGHTS` = 28 valid nights, excluding the `RECENT_EXCLUDED` = 2 most recent and any Watch or Alert nights; median + 1.4826·MAD with the Readiness floors. No alerts before `MIN_ALERT_NIGHTS` = 14 valid nights. Resting HR here is the overnight mean sleep HR (NightSignal's metric); Readiness keeps the lowest 30-minute window. On a night whose own values fail the validity rule, the strap's values stand in.

| Flag | Condition | Role |
|---|---|---|
| `RhrFlag` | rhr − c ≥ max(`RHR_FLAG_BPM` = 4 bpm, 2·s) | core (cardiac) |
| `RhrWatch` | rhr − c ≥ `RHR_WATCH_BPM` = 3 bpm | watch only |
| `TempFlag` | temp − c ≥ `TEMP_FLAG_C` = 0.5 °C and ≥ 2·s (sleep mean, ≥ 30 readings) | core (thermal) |
| `RrFlag` | rr − c ≥ `RR_FLAG_PER_MIN` = 2.0 /min and ≥ 2·s | core (respiratory) |
| `HrvSupport` | z(`hrv_n`) ≤ −2.0 | support, never a core vote |

* **Watch** (in the app, no push): any single core flag, or `RhrWatch` + `HrvSupport`.
* **Alert** (one push per morning after a sync; Readiness capped at 54), any of: `RhrFlag` on 2 consecutive nights; ≥ 2 different core families on the same night; a core flag tonight and on ≥ 3 of the last 7 nights; rr − c ≥ `RR_ALERT_PER_MIN` = 3.0 /min on its own.
* One missing night is bridged; two or more reset persistence. An alert clears after `CLEAR_NIGHTS` = 2 consecutive nights with no core flag.
* A likely cause is annotated, never used to suppress: a previous day with TRIMP > `HARD_DAY_RATIO` = 1.5× the chronic load. (Day tags were removed from the app; old `day_tag` rows are still exported and imported.)
* SpO2 and desaturations never vote; they stay in Readiness and the Breathing card. +0.5 °C is the lowest temperature line that won't fire every menstrual cycle (the luteal rise is about +0.3 °C), and it is never shown on screen.

## Energy (0–100, minute by minute)

Code: `ChargeModel.kt`.

A body battery, simulated per minute. The simulation starts 8 days before the day shown (`WAKE_HISTORY` + 1), so the arbitrary starting value (50) washes out through the first night and the week's wake values are there for the not-worn restart below. For a past day it runs up to 14 h past the day's end (`LOOKAHEAD_SECONDS`, data permitting) only to see when the strap went back on. It reads every sleep that overlaps the run, not only those that end in the day, so the evening part of a night that ends the next morning counts as sleep on the day it began (Zepp's minute curve does the same). The day's own numbers still run midnight to midnight. Until the simulation has seen at least one sleep (`anchored`), no value is shown: charge is measured from how full a night left you, so without one the number would only echo the starting guess.

### Minute states

Each minute gets a state, following Firstbeat's stress/recovery method (*Stress and Recovery Analysis Method Based on 24-hour HRV*, 2014). In it, activity is VO₂ > 30% of VO₂max, daily activity 20–30%, and recovery after exercise (falling EPOC) is never counted as stress. We use heart-rate reserve as the stand-in for %VO₂ reserve (Swain & Leutholtz 1997): HRR = (HR − rest)/(max − rest).

| State | When | Effect per minute |
|---|---|---|
| Sleep | inside a sleep stage | `SLEEP_K`·w(stage)·relax·hrvFactor·(100 − C). Stage weights: deep 1.0, light 0.8, REM 0.65, awake-in-bed 0.1. relax = 1.2 − 0.04·(HR − night resting), limited to 0.5–1.2. hrvFactor comes from that sleep's own HRV contributor, as its morning's Readiness scored it (0.6–1.1, `sleepHrvScore`). |
| Not worn | no heart rate, not asleep, and the strap reported itself off-body, or marked off-wrist, or a gap ≥ `NO_WEAR_GAP_MINUTES` = 10 | Zepp's drift, or no value (below) |
| Exercise | HRR ≥ 0.30 and moving, in a sustained run (below) or in the stress exclusion | −`BASE_DRAIN` − 0.32·TRIMP(HRR). Also builds an EPOC-like debt. |
| Exercise recovery | debt > 5 and HR not rising | −`BASE_DRAIN` only, even if the strap reports high stress |
| Activity | HRR ≥ 0.20 and moving (or in the stress exclusion), or ≥ 60 steps/min | −`BASE_DRAIN` − 0.32·TRIMP(HRR) |
| Around a workout | in the stress exclusion (below) and none of the above | Activity inside the workout's active time, Exercise recovery in the 20 min after; Rest in either when HRR < `WORKOUT_REST_HRR` = 0.10 |
| Stress | strap stress ≥ your threshold *T* (below) for ≥ `STRESS_GATE_MINUTES` = 3 consecutive minutes | −`BASE_DRAIN` − 0.007·(stress − *T*) |
| Relax | stress < 25, still, HRR < 0.20 | + `RELAX_K`·(100 − C), `RELAX_K` = 0.00015, no drain |
| Rest | otherwise | −`REST_DRAIN`·C (0.0005·C: 0.04 at 80, 0.025 at 50) |
| No data | a heart-rate dropout under 10 min, or a gap still open at the end of the data (not synced yet) | no change, the value holds |

**Night resting, for the sleep term.** The relax factor's night resting is the night's lowest 30-minute average heart rate (as Readiness's `rhr_n`), taken over the night as the strap detected it. When the user moved the night's times, the engine takes it from the strap's own session, not the edited one, so a trim that cuts off the calmest half hour doesn't make the sleep kept count as more restful. On the Fold's 2 to 3 Nov night, trimmed from 11:05 PM to 3:00 AM, the edited night's own value would be 66 bpm against the strap's 59, and the kept sleep would recharge about 40 % faster a minute. A night with no edit uses its own value, as before. Readiness and the sleep pages still use the edited night. Each sleep in the run uses its own night's value (`ChargeParams.sleepRestingHr`, by the sleep's start), not the scored day's: a nap takes that of the main sleep before it, and a sleep without one falls back to the scored night's, then the daytime resting heart rate.

`BASE_DRAIN` = 0.04 (about 2.4/h) is the floor under every minute that is doing something: movement, stress, exercise recovery, the stress exclusion. "Moving" is `moving`, the same signal daily load uses: the strap counted 1–250 steps that minute.

**No drain at rest.** Zepp's physical pool drains only with movement and HRR over 10 %; at rest it recharges slowly. A seated day falls through its mental pool, which drains while stress is 24 or more, and ever more slowly once the pool is under half (native 03 §4.3–4.4). OpenStrap has one pool, so:

* A calm, still minute (Relax) has no drain, only its slow recharge, as Zepp's stress under 24 drains neither pool.
* Relax recharges at `RELAX_K` = 0.00015 of the headroom a minute: 85 → 86 over 8 calm hours, 60 → 62.8. Zepp's toy replay of 8 hours at a desk at stress 20 goes from 85 to 86 (native 03 §11): its physical pool barely moves between 80 and 100 at rest and its mental pool gains a little. The earlier ¼ of light sleep (0.0012) gave 85 → 91.6, so a calm day climbed; now it holds and sleep, about 30 times faster, is what fills Energy.
* Any other still, awake minute outside workouts (Rest) drains in proportion to charge, `REST_DRAIN` = 0.0005 per point a minute. That equals the old flat 0.04 at 80 and halves by 40, so a desk day still runs Energy down, ever more slowly. Zepp's 8 hours at a desk at stress 35 take its total from 85 to 64 (native 03 §11); this takes 85 to 67, against 66 for the old flat drain, but from 50 it drains 11 rather than 19.

**Raised heart rate while still.** Outside the stress exclusion, heart rate makes a minute Activity or Exercise only while moving. Sitting with a raised heart rate (heat, coffee, a fever, sitting stress) is Stress if the stress score says so, else Rest; it can't Relax, so the raised heart rate takes away any recharge. This is Zepp's movement gate (native 03 §4.3: "raised HR without movement only slows recharge") and matches daily load, which stopped counting sitting heart rate. Inside a workout and its 20 minutes after, heart rate counts as before, steps or not (cycling, weights). Outside one, a **sustained run** counts: `SUSTAINED_EFFORT_MINUTES` = 10 or more consecutive awake minutes at HRR ≥ 0.30 are effort for the whole run, steps or not (`sustainedEffort`), so unrecorded cycling or rowing drains as Exercise. 30 % of reserve is about 40 bpm over resting; caffeine adds a few bpm and a tense moment holds a raised heart rate for a minute or two, so neither lasts 10 minutes seated. Shorter runs stay Stress or Rest. Daily load counts the same runs (`sustainedRuns`, shared), so a minute without steps adds load exactly when it drains as Exercise here.

**Not worn.** A minute with no heart rate outside sleep is judged first by the strap's own wear state, where the app recorded one (`Minute.worn`, from the wear-status push, Settings DEVICE_INFORMATION key 16, codemap 07 §4 N5). Each change is stored with its time in `wear_event` and holds until the next; the link going down stores "unknown". Off-body is not worn at once, however short. On body doesn't override anything: it may be a single read at connect that the strap never updated (its push hasn't been seen on hardware yet), so the heart-rate rule still applies: the minute is not worn when the strap's activity record marks it off-wrist (`offWrist`: mode low nibble 3 or 6, or 0x7E, as Zepp reads it, native 03 §2.2) or it sits in a gap of at least 10 minutes that data later closes (Zepp holds the last heart rate for 541 s). A shorter gap, or one still open at the end of the data, holds the value. Following Zepp (native 03 §1, §3 step 12, §4.7):

* **Short stretches drift.** Energy falls at the mean of Zepp's default no-wear slopes for its two pools: `NO_WEAR_DRIFT_HIGH` = −0.0482 a minute at or above 50 (about −2.9 an hour), `NO_WEAR_DRIFT_LOW` = −0.0349 below. Counted as living.
* **Long stretches are a gap.** Once `NO_WEAR_LIMIT_MINUTES` = 384 not-worn minutes (6.4 h) have built up since the last wake, Energy is invalid: no points, so the chart shows a gap. A not-worn stretch that covers at least half (`USUAL_SLEEP_COVER`) of the usual sleep time is invalid from its start. The usual sleep time is the median clock start and end of the last 14 main sleeps (midnight to 06:00 without any). The clock times are read from 12 hours opposite their circular mean (`circularMean`), not from a fixed noon, so bedtimes either side of noon (a night-shift sleeper) don't split into two clusters 24 hours apart. Each night's range ends at the usual wake time on the day the sleep belongs to (its end), so an afternoon bedtime is placed on its own day too.
* **Restart.** After a night off the wrist, Energy restarts when the strap is back on from the median of the last `WAKE_HISTORY` = 7 wake values (Energy at the end of each recorded main sleep, not counting the simulation's first), else `DEFAULT_WAKE` = 80: Zepp's start-value rule. Wake values, not day averages, which would carry the evening lows. That day is flagged `strapOffOvernight`; its "at wake" is the restart value, and the Energy card and Recovery screen say "Strap off overnight, Energy estimated." Before the first recorded sleep there is nothing to restart from, so Energy waits for one as before. After a long daytime stretch Energy stays a gap until the next recorded sleep, which starts from 50.

Future: learn the user's own no-wear drift from their quiet, awake, worn minutes over about 30 days, as Zepp does (native 03 §4.7), and keep the defaults until then.

**Body-signal Alert.** On a night in Alert ([Body signals off baseline](#body-signals-off-baseline)), from that sleep's start until the next main sleep starts, recharge (Sleep, Relax) is × `ALERT_RECHARGE` = 0.5 and every drain × `ALERT_DRAIN` = 1.5. These are the ends of Zepp's health-alarm multipliers (native 03 §4.2, §8.2), which apply from the sleep-start minute too. Zepp ramps them with how far over its one-night threshold (z ≥ 1.66) the night is; OpenStrap's Alert already needs persistence or two signal families, so it takes the full effect. Watch doesn't touch Energy: it is one signal for one night, shown in the app only and leaving Readiness uncapped, and Zepp's multipliers just under its threshold are within 5 % of 1. The engine runs the body-signal rule over every night it loaded (it only looks backwards, so each night gets the state Readiness gave it) and passes each main sleep's start and state in `ChargeParams.bodySignals`.

### Stress exclusion around workouts

The strap's stress score is HRV-based, and exercise suppresses HRV whatever the mind is doing, so a reading during or just after a workout isn't stress. The strap logged none inside the workouts we have, but nothing guarantees that. `stressExcluded` covers every stored workout's active time (phone workouts less their pauses, via `activeRanges`; strap workouts start to end) plus `STRESS_AFTER_WORKOUT_SECONDS` = 20 min after each active part ends. 20 min is the ≈20-min half-life of the post-exercise debt (`EPOC_DECAY` = 0.966 a minute), so a pause is excluded for its first 20 min as well. Membership uses `inWorkout`, the same per-minute test daily load uses.

* **Energy:** inside the window a minute never becomes Stress or Relax, and a reading taken there isn't held into later minutes. Low-intensity workout minutes (strength between sets, yoga) that the intensity states don't claim become Activity, since they are part of a recorded workout and daily load counts them too; in the 20 min after they become Exercise recovery, the same base drain as the debt-driven recovery. In either, a minute under 10 % of reserve is plain Rest: the heart is back at sitting level.
* **Calm level:** readings in the window are left out (`calmStress`).
* **Daily stress:** `HealthRepository.stress` and `daily(Stress)` drop them, so the Today tile, the day chart (a gap), the previous-day comparison and the daily average, lowest and highest behind the week/month/year views leave them out. The raw readings stay in the database and the export.

### Personal stress threshold

The strap's stress score is the same one Zepp shows. Zepp labels it relaxed 1–39, normal 40–59, medium 60–79 and high 80–100. Where stress starts differs between people, so *T* comes from the user's own readings (`stressThreshold`).

* Input: the strap's stress readings from the last `STRESS_BASELINE_DAYS` = 14 days that were calm. Calm means awake, fewer than 10 steps that minute, heart rate below 20% of reserve, and outside the stress exclusion around workouts.
* Calm level: the median of those readings.
* *T* = calm level + `STRESS_MARGIN` (10), kept within 50–70. A calm level of 50 gives 60, where Zepp's "medium" band starts.
* *T* is 60 until there are `MIN_STRESS_DAYS` = 3 days and at least 60 calm readings.
* The Recovery screen states *T* and the calm level it came from.

Zepp does the same job differently. It computes a stress threshold on the phone or in its cloud and writes it to the strap (Readiness module 0x49, opcode `0E`; `j560.java`). The strap only stores what Zepp gives it. A strap used only with OpenStrap returns no records (`0C` → `0d 00 00`, verified on hardware), so there is nothing to read back.

### Calibration targets (checked by unit tests)

* 7.5 h of normal sleep takes charge from 30 to about 90.
* A restless night (high HR, low HRV) recharges clearly less.
* A quiet waking day drains 0.0005·C a minute: 80 → about 59 over 10 hours.
* A calm desk hour drains less than the old flat 2.4; with stress under 25 it recharges.
* Sitting at 25 % of reserve with no steps drains exactly as rest, never as activity, and can't Relax; a raised heart rate walking drains as exercise.
* 30 minutes at HRR 0.5 with no steps is Exercise throughout; 5 (or 9) such minutes aren't.
* 8 calm, still hours from 85 end between 85 and 87.
* A night with the strap off (11 h, over the usual sleep) is a gap, not a sleepless night: the next day restarts from the median wake value, is flagged, and stays above 45, where treating the night as awake leaves it 15+ lower. Two hours off the wrist drift by 120 × −0.0482; a 5-minute dropout holds; 7 hours off in the day are a gap after 384 minutes until the next sleep.
* A recorded workout without steps drains exactly as before: `BASE_DRAIN` + 0.32·TRIMP a minute, then `BASE_DRAIN` in the 20 minutes after.
* An Alert night recharges clearly less (30 → about 79 rather than 94 over 7.5 h of light sleep), the day after drains 1.3–1.5× as much, an Alert ends at the next sleep, and Watch changes nothing.
* An hour at 60% of reserve drains 20–32.
* A post-workout cool-down never counts as stress.
* Stress 80 through a slow strength workout drains nothing as stress; the same reading 30 min after it does.
* An hour at stress 80 with *T* = 60 (0.007·20·60 = 8.4, plus 2.4 base) stays at or below a third of an hour at 60 % of reserve: its total under 32 / 3, its stress part under a third of that exercise hour's drain.
* Stress blips shorter than 3 minutes never drain as stress.
* A seated day in Zepp's "normal" stress band (40–59) drains like rest, not stress. Zepp labels the strap's score relaxed 1–39, normal 40–59, medium 60–79 and high 80–100; counting from 40 had most waking minutes drain as stress.

### What the screen shows

* The charge at waking and now. After a night with the strap off, a small note: "Strap off overnight, Energy estimated.", and the start value is labelled as such.
* What was recharged, and what was drained by movement, by physiological stress, and by simply being awake. This is our version of Zepp's physical/mental split. The stress drain is labelled "physiological stress (estimate)", not mental: consumer stress scores separate rest from lab stress tasks but barely track self-reported daily stress (Rosenbach et al. 2025), and Firstbeat's support is manufacturer-curated.
* Not logged: Zepp's BioCharge `totalCharge`. A strap used only with OpenStrap returns nothing from the Readiness module's record command, and no channel for BioCharge results is known, so there is nothing to fit `SLEEP_K`, `BASE_DRAIN`, `REST_DRAIN` or the drains to yet. A full EPOC model and separate physical and mental pools are deferred until there is a calibration target.

## Training load (TRIMP)

Code: `LoadModel.kt`.

* Formula (Banister 1991): TRIMP per minute = x·0.64·e^(1.92x) for men and x·0.86·e^(1.67x) for women, where x is HRR. Unknown sex uses the men's curve.
* `dailyLoad` returns two parts, `workout` + `everyday` = the day's total, as Zepp's Exertion splits `exerciseScore` from `activityScore` (analysis `native/07` §3.3).
  * **Workout:** every minute overlapping a stored workout, phone (`app_workout`, shown start to end less its pauses, `activeRanges`) or strap (`workout`), counts whatever its intensity, asleep or not. Each minute counts once, so a phone and a strap workout over the same time aren't added twice. The minute's HR is the workout's own recording (mean of its samples in that minute, the higher where two overlap; `withWorkoutHeartRate`), else the all-day value.
  * **Everyday:** any other minute counts only when awake, the strap counted a step that minute (`moving`: steps 1–250), and either x ≥ `LOAD_MIN_HRR` = 0.35 (Zepp's cut-off) or, with ≥ `LOAD_WALK_STEPS` = 60 steps that minute, x ≥ `LOAD_WALK_HRR` = 0.20 (`everydayMinute`); and at least `LOAD_RUN_MINUTES` = 3 of the last `LOAD_RUN_WINDOW` = 5 minutes (itself included) pass too, so isolated PPG spikes don't count. All at full TRIMP.
  * **Why the walking exception:** on a real database, sustained walking at about 100 steps a minute sat at HRR 0.24–0.34, so 0.35 alone dropped whole walks (42 minutes on one day). 60 steps a minute is the bottom of the cadence MET scale used for calories; below it, a few steps around a desk keep the 0.35 line. Before this, every awake minute at x ≥ 0.2 counted, moving or not, so a seated, raised heart rate added load.
  * **Why steps:** Zepp gates on a per-minute activity value > 5. In OpenStrap's stored activity records the `mode` (`kind`) was 0 for every awake minute, and `intensity` was above 5 in 79 % of minutes with no step, so neither separates sitting from walking.
  * **Without steps:** a minute outside workouts also counts when it is part of a sustained run (`sustainedRuns`): `SUSTAINED_EFFORT_MINUTES` = 10 or more awake minutes in a row at x ≥ `EXERCISE_HRR` = 0.30, outside the stress exclusion (a workout and its 20 minutes after). It is the rule Energy drains as Exercise by, so unrecorded cycling or rowing counts in both or neither. All of a run counts, its first minutes too. Weights between sets rarely make a run, so record a workout for them.
* No HR-load method is validated on free-living minutes, and even in sessions Banister's test–retest CV is 15.6 % (Wallace et al. 2014); it's the least-bad choice (Lucia needs lab thresholds, Edwards' zones start at 50 % HRmax, which walking crosses).
* Days with fewer than `LOAD_MIN_WORN_MINUTES` = 8 h of heart-rate data are left out of the load history (`LOAD_DAYS` = 90 days of it), unless a recorded workout put load in them (`loadDayCounts`): the workout is most of such a day's load, and leaving it out dropped it from fitness and fatigue.
* TRIMP is the only daily load. A workout's EPOC (the strap's sport effect, or OpenStrap's for phone workouts) is shown on the workout as "EPOC" and never enters the daily total, CTL/ATL or the ratio.

### Load screen (Activity → Training load; descriptive)

* **Today:** the total, with its split on one line ("62 today · 48 workouts · 14 everyday"). Fitness, fatigue, the ratio and Readiness use the total.

* **Fitness, fatigue, form** (`fitnessFatigue`), the standard 42/7 convention: CTL_t = CTL_{t−1} + (TRIMP_t − CTL_{t−1})·(1 − e^(−1/42)); ATL likewise with 7; Form_t = CTL_{t−1} − ATL_{t−1}. Over worn days only (an unworn day holds rather than decays), seeded with the first week's mean. Personal time constants are not fitted: they're poorly identifiable (Imbach et al. 2025).
* **Strain** (`strain`) = 21·(1 − e^(−TRIMP/180)): about 7 for 1 h at 60 % HRR (men's curve), about 15 for 2 h at 75 %. A fixed curve keeps today's 12 comparable with last year's; WHOOP's 0–21 Strain has no published validation. Shown with "vs your usual" (`loadPercentile`), the share of the last 90 days' loads at or below today's.
* **Acute:chronic** (`acwr`, labelled "contested"): EWMA acute 7 days over chronic 28 days, the chronic part ending where the acute week begins (uncoupled). Needs 21 days.
* Readiness keeps "previous day's load": 100 when yesterday's TRIMP ≤ 1.5 × CTL (up to the day before), −25 points per unit above, floor 30.
* Live heart rate is stored several times a minute; the engine averages it to one value per minute first.

## Activity (Activity screen)

`Activity.kt`. The cadence thresholds are established; a chest- or arm-worn strap's step counting is not validated at all.

* **MVPA** (`mvpa`): moderate at cadence ≥ `MODERATE_CADENCE` = 100 at every age, vigorous at ≥ `VIGOROUS_CADENCE` = 130 (Tudor-Locke). A minute counts only inside a run of ≥ `CADENCE_RUN_MINUTES` = 2 consecutive qualifying minutes (design choice). Then heart rate, for effort without steps: awake, ≥ 10 consecutive minutes at ≥ 40 % of reserve (vigorous ≥ 60 %), with < 100 steps or inside a workout, labelled "HR-assisted". No minute counts twice.
* MVPA-equivalent = moderate + 2·vigorous over the last 7 days, against 150 min a week (WHO 2020). Raw minutes are shown too.
* **Steps:** the strap's daily counter, else the summed minutes; a divergence over 5 % is flagged.
* **Step goal framing:** "most of the benefit is reached" by about 7,000 steps from 60, 8,000 below (Paluch et al. 2022: mortality plateaus at 6,000–8,000 and 8,000–10,000). The user's own goal is unchanged.
* **Distance** ≈ steps·L, L = 0.414·H below 120 spm, linear to 0.45·H at 130 and above; shown with "≈". Workout or phone-GPS distance and a GPS-calibrated step length are not available yet.

## Energy expenditure (Activity screen)

`EnergyExpenditure.kt`. Established for BMR (Mifflin-St Jeor came within 10 % of measured RMR in more people than other equations, Frankenfield et al. 2005), contested above it. Group-calibrated HR-only estimates were less precise than motion against doubly labelled water (Brage et al. 2015), so the model is motion first with a narrow HR branch.

* BMR = 10·W + 6.25·H − 5·A + (male +5, female −161, unset −78) kcal/day; b = BMR/1440 per minute. net(MET) = (MET − 1)·3.5·W/200 kcal/min, so rest is never counted twice.
* Per minute with data (a step record, heart rate or sleep; no row is missing, never zero):

| Minute | kcal/min |
|---|---|
| In a device workout | the workout's kcal, pro-rated (at least b) |
| Asleep | `SLEEP_FACTOR` 0.95·b |
| ≥ 60 steps | b + net(cadence MET) |
| Some steps, or a neighbouring minute stepped | `LIGHT_FACTOR` 1.3·b |
| Awake, sedentary | `SEDENTARY_FACTOR` 1.15·b |

* Cadence MET (`cadenceMet`, CADENCE-Adults, Tudor-Locke et al. 2019): up to 60 y, 60 → 2.0, 100 → 3, 130 → 6, linear between, held at 6 above. From 61: 100 → 3, 110 → 4, 120 → 5, then (our extrapolation) 6 at 130. Sustained ≥ 145 spm (this and a neighbouring minute) uses ACSM running, VO2 = 3.5 + 0.2·v with v from the step length.
* HR branch, "HR-assisted": awake, outside a workout, < 20 steps, ≥ 10 consecutive minutes with x ≥ 0.40 → max(EE, b + net(min(6, 1 + x·(VO2max/3.5 − 1)))). VO2max is the user's estimate when there is one, else `DEFAULT_VO2MAX` = 35 ml/kg/min (an unverified placeholder).
* The daily total is "partial" below 20 h of data; PAL = TEE/BMR outside 1.2–2.5 is flagged. No thermic effect of food.
* 0.95, 1.15 and 1.3 are conventions, not 2024 Compendium values (sleeping and sitting quietly are both 1.0 there).

## Estimated fitness (VO2max, Activity screen)

`Vo2max.kt`. Jurca et al. 2005 (Am J Prev Med 29(3):185–193), the NASA model, checked against the full text (Table 5 and the Figure 1 worksheet):

* METs = 2.77·sex (female 0, male 1) − 0.10·age − 0.17·BMI − 0.03·RHR + SR-PA score + 18.07; × 3.5 for ml/kg/min. SEE 1.45 METs (NASA; 1.50 and 1.97 in the other cohorts), so the band is ±1 SEE ≈ ±5 ml/kg/min, rounded, never a decimal.
* SR-PA is the worksheet's five-level question; the scores are the model's dummy weights 0 / 0.32 / 1.06 / 1.76 / 3.03, not 0–4.
* RHR = median of the last 7 nightly resting HRs (needs ≥ 5 in 14 days). A sleeping RHR runs a few bpm below a seated one; at −0.03 METs/bpm that's small next to the SEE, so it's documented, not corrected.
* Recomputed weekly (as of Monday). Withheld when: age outside 20–79 (the model was derived on 20–70), sex unset, weight or height missing or not confirmed in 90 days, the activity question not answered in 180 days, beta-blockers or pregnancy flagged, or < 5 nightly RHRs in 14 days.
* The estimate feeds the energy model's HR branch.
* FRIEND 2022 band: [Kaminsky et al. 2022](https://doi.org/10.1016/j.mayocp.2021.08.020) (Mayo Clin Proc 97(2):285–293, apparently healthy US adults), Table 3, treadmill rows (RER ≥ 1.0), copied into `Vo2max.Friend` and checked value by value against the published table. Jurca was validated against treadmill VO2max, so the treadmill rows are used. The paper reports only the 10th–90th percentiles, so the evidence review's 25 / 75 / 97.7 cut points are replaced by the nearest reported ones: < 30 / 30–50 / 50–70 / 70–90 / ≥ 90 ("Top 10 %"), against the user's age decade. Nothing is interpolated or extrapolated.
* Fitness age: the age at which the sex-specific 50th percentile equals the estimate. Each decade's median sits at its midpoint (25, 35 … 85), and the points are joined linearly (the medians fall every decade). The centre is clamped to 20–80 and shown as ±10 years, also clamped: "typical of a woman aged about 35–55". Above the 20–29 median the centre stays at 25, so the youngest range is 20–35. Withheld whenever the estimate is.
* **Phone-GPS runs:** each outdoor run with GPS gets its own exercise VO2max (ACSM running equation from %HRR, Zepp's gates), shown with the workout; see [Workout effect](#workout-effect-phone-workouts). It doesn't feed this estimate yet. Biological age is dropped.

## Workout effect (phone workouts)

`WorkoutEffect.kt`, fed by `WorkoutEffectEngine.kt`; plain-words version in [calculations.md §13](calculations.md#13-workouts). Behaviour follows the Zepp teardown (analysis `native/04-sport-quantification.md`); none of Zepp's code or network weights is used.

* **Epochs** (`epochs`): 5 s, median HR of readings in 40–220, last value held for 2 empty epochs, then missing (native 04 §3.1, §4.1).
* **EPOC** (`epoc`): adds only, as Zepp's own model does (native 04 §4.2): each epoch adds `EPOC_RATE`·(e^(`EPOC_STEEPNESS`·x) − 1) per minute with x the heart-rate reserve (%HRR ≈ %VO2R, Swain & Leutholtz 1997, Med Sci Sports Exerc 29(3):410–414), 0.077 and 4.5; the exponential in intensity follows Børsheim & Bahr 2003 (Sports Med 33(14):1037–1060). The total is stored as `epoc` and shown as Load (int, as the strap's int EPOC, native 04 §2.1). 0.077 is fitted to the strap's own EPOC on three easy workouts; 4.5 is a design fit for harder ones (`WorkoutEffectTest`), not validated. When the strap kept a record of 90 % or more of a phone workout, its own figures replace these (`WorkoutEffectEngine.strapEffect`).
* **Aerobic TE** (`aerobicTe`): Zepp's EPOC breakpoints by activity class (native 04 §4.4), linear between them (Zepp's own segments don't quite meet and differ by up to 0.2). Class from the 28-day workout-EPOC sum with Zepp's thresholds, 50 with none (§4.5). Gated at ≥ 12 epochs and EPOC ≥ 1.
* **Anaerobic TE:** not made. Zepp's is an LSTM on EPOC increments (§4.2); we know of no open HR-only model that holds up for sprints, where HR lags.
* **Recovery time** (`recoveryMinutes`): design choice. TE → hours 0/5/10/20/40/72 at TE 0–5 (TE 2 anchored on the strap's 11 h at TE 2.1), × up to 1.25 when ATL > CTL (`Recovery.fitnessFatigue` over daily workout EPOC, CTL floored at 5), at least the leftover from earlier workouts (Zepp's carry-over, §6.2), at most 72 h (Zepp's TE-5 value; its 96-h ATE case needs an ATE).
* **VO2max** (`vo2max`): ACSM running on the level (VO2 = 3.5 + 0.2·v) over %HRR. Zepp's gates (§5.2): outdoor run, ≥ 120 qualifying epochs (≥ 1.5 m/s, HR ≥ max/2), nothing in the first 120 s. 2-min windows every 30 s: 30-s speed SD ≤ 0.3 m/s, second-minute HR SD ≤ 4 bpm, HRR 0.5–0.95, |grade| ≤ 2 % by GPS height. Median of ≥ 5 windows that are ≥ 30 % of those run at pace, IQR ≤ 5, within 20–90. Grade isn't adjusted (Zepp's GAP polynomial is fitted to its network), so hilly windows are dropped instead.
* **Checked against the strap** (2026-10-22, the only two strap workouts, both short "Activity" records, resting 67, max 187): 3 min at avg 128 bpm, ours EPOC 1.3 / TE 0.1 vs the strap's 1.02 / 0.1; 9 min at avg 119, ours 1.7 / 0.2 vs the strap's ≈ 6 / 1.0. Zepp's network credits low-intensity minutes more than ours does. No strap VO2max to compare. Needs longer, harder sessions recorded on both.

## Inputs from the profile (Device → Your profile)

* **Max HR** (`MaxHrModel.kt`): a value the user entered from a test wins. Otherwise Tanaka et al. 2001 (JACC, 18,712 subjects): 208 − 0.7·age; without an age, the strap's own max HR (from the Zepp profile), else 190. Any equation's individual SD is about 7–11 bpm, so raising is sound and lowering is not: it's raised to the highest HR the user has actually reached, by at most `MAX_HR_RAISE` = +15 bpm. "Reached" (`observedPeak`) needs two separate days within `MAX_HR_AGREE_BPM` = 3 bpm of each other over the last 90 days, each day's value being its highest artefact-filtered minute (`filteredMax`: a neighbouring minute within 10 bpm) raised by the strap's daily maximum exercise HR (type 61) only when that is in 30–220 bpm and within `ARTEFACT_JUMP_BPM` = 10 of the filtered minute (`dailyMaxima`; a strap value on a day without one is dropped). The same max HR sets workout zones when a workout has no sport-effect max HR. Max HR sets heart-rate reserve, so it changes both training load and Energy drain.
* **Sex:** selects the TRIMP curve.
* **Weight and height:** energy expenditure (BMR) and step length.
* **Resting HR (daytime)** (`daytimeRestingHr`): median of the strap's daily resting HR over the last 14 days, else the median nightly lowest + an offset. The offset is the median of (daily − nightly lowest) over the latest 14 days that have both, else `RHR_OFFSET_BPM` = 3 bpm. Karvonen and Swain define resting HR as awake rest; a nightly minimum would inflate HRR for every waking minute.
* **Sleep need:** optional, 7–9 h; blank uses the age default.
* **Usual activity, beta-blockers, pregnancy:** for the VO2max estimate only. Saving the profile confirms weight, height and the activity answer, restarting their 90- and 180-day freshness checks.

## Limits

* These are wellness estimates, not medical advice. The alert wording says so ("This is a wellness estimate, not a medical assessment.").
* HRV here is the strap's own RMSSD samples; we don't have beat-to-beat data. Readiness accuracy depends on the strap being worn every night.
* Energy constants are calibrated to typical published body-battery behaviour and the targets above, not fitted to lab data.
