# Healthspan age

An estimate of how old your habits look, built only from data unstrap already has. **Status: implemented (2026-10-20)** in `data/Healthspan.kt` (the formula) and `data/HealthspanEngine.kt` (loading the data), with §7's examples as unit tests in `HealthspanTest`. [evidence-review.md](evidence-review.md) dropped biological age on 2026-10-18 for three reasons; this document answers each one and lists what is still open. That section of evidence-review.md is now "Healthspan age: implemented".

unstrap is a wellness app. This is an estimate for personal insight, not a medical measurement.

## Contents

1. [The formula](#1-the-formula)
2. [Inputs and references](#2-inputs-and-references)
3. [The six terms](#3-the-six-terms)
4. [Pace of aging](#4-pace-of-aging)
5. [Uncertainty and display](#5-uncertainty-and-display)
6. [Withholding rules](#6-withholding-rules)
7. [Worked examples](#7-worked-examples)
8. [The three objections in evidence-review.md](#8-the-three-objections-in-evidence-reviewmd)
9. [Known problems](#9-known-problems)
10. [Sources](#10-sources)

---

## 1. The formula

```
HealthspanAge = Age + clamp(S · (c_fit + c_sri + c_sleep + c_strength), −5, +5)

c_fit = clamp(c_rhr + c_steps + c_vo2, −5, +5)      fitness group: shared limit (§3.7)
cᵢ    = ln HRᵢ(xᵢ vs refᵢ) / G                       years, one term per metric, each with its own limits (§3)
G     = 0.09165                                      PhenoAge's Gompertz age slope (risk → years)
S     = 0.6                                          shrinkage, because the terms weren't adjusted for each other
```

* **Each term is scored against a typical value** (§2 lists them): by age and sex for resting HR and VO2max, by age for steps, and fixed references for regularity, time asleep and strength. Someone at the median on every term reads their own age; better habits read younger. That is what "age" means: the death rate at any age is the whole population's. PhenoAge works the same way. (WHOOP scores against targets instead, so meeting every target reads as your own age and a typical user reads older.)
* **The typical user reads about +0.3 y, not 0** (simulated 35-year-old men, §9). Short sleep can only add years, the SRI curve adds more below the median than it takes off above, and the under-60 step term runs from +1.5 to only −0.86.
* **Targets** (8,000–10,000 steps, 90 min/week of strength, at least 7 h measured sleep) appear in the coaching (§5). They don't set the age.
* **Windows:** 180 days for every input, except VO2max (90 days). Pace uses 30 days.
* **A missing term** (for example no VO2max) is left out, which is the same as assuming the median.
* **The shown offset is capped at ±5 y.** Li et al. 2018 found HR 0.26 for 5 vs 0 low-risk factors (smoking, BMI, alcohol, diet, exercise), about 14.7 y on this scale. ±5 y allows a 10 y span, two-thirds of that, and unstrap scores none of smoking, BMI, alcohol or diet. It is a loose ceiling, not a calibration.
* **Design choices:** S = 0.6, the ±5 y cap, the ±5 y fitness-group limit, the per-term limits, the 0.5 weight on VO2max, and how each curve is drawn between published points.

## 2. Inputs and references

| Input | Source in unstrap | Window rule | Reference |
|---|---|---|---|
| Age | `UserProfile.age()` (birth year only, so ±1 y) | 20–79 | — |
| Sex | `UserProfile.sex` | required | — |
| Resting HR | `night_metrics.rhrLowest30`: lowest 30-min mean during sleep (`Recovery.Night.restingHr`) | median of nightly values; a night counts with ≥ 3 h of main sleep and ≥ 120 min of heart rate, with no HRV requirement (decided 2026-10-20: Recovery's HRV rule is for scoring HRV, and Aune 2017's cohorts measured pulse without one) | NHANES seated pulse P50 by sex, joined linearly between age-band midpoints (Ostchega 2011), after adding 10 bpm to the sleeping value |
| Steps | the sum of synced `activity_minute.steps` for each local day, leaving out any minute over 250 steps as bad data (`dailyStepsOnce`). Only days worn ≥ 10 h count | mean of valid days | Paluch 2022 median of the cohort medians: 7,803 (< 60 y), 5,649 (≥ 60 y), blended from 55 to 65 |
| Sleep regularity | `SleepRegularity.sri()` over 8 grid days (7 day-pairs) | mean of valid weekly SRIs | Windred 2024 UK Biobank median, 81.0 |
| Sleep duration | main sleeps (`sleep_session.nap = false`), asleep = deep + light + REM (`RecoveryEngine.asleepMinutes`) | mean minutes per night | 7 h (420 min), Saint-Maurice 2024's reference group. 7 h and over scores 0 |
| Strength training | `workout` rows with `sportType` 52 (Strength training) or 50 (Core training), `exerciseSeconds` | minutes per week | 0 min/week: 57.8 % of US adults do none (Bennie 2018) |
| VO2max | the strap's exercise estimate: `workout.vo2max`, else `sport_effect.vo2max` | median, ≥ 3 workouts in 90 days | `Vo2max.Friend` P50 for age and sex (lab-tested, apparently healthy adults), joined linearly between decade midpoints as `fitnessAge` does |

**Not used:** HRV (no mortality dose–response at this sampling), SpO₂, skin temperature, stress, PAI, the strap's sleep score, and the Jurca VO2max (§8).

## 3. The six terms

### 3.1 Resting HR (Established; limits ±4; fitness group)

Risk rises 17 % per 10 bpm (RR 1.17, 95 % CI 1.14–1.19), close to linear down to 40 bpm ([Aune et al. 2017](https://pubmed.ncbi.nlm.nih.gov/28552551/)). That is the all-cause analysis of 59 studies, from a meta-analysis of 87, with 1.8 million people. The studies measured seated or lying pulse at rest. The floor at 45 bpm seated is kept (design choice).

```
seated   = RHR_sleep + 10
c_rhr    = 0.1713 · (max(seated, 45) − ref(age, sex))       0.1713 = ln 1.17 / 10 / G

ref (bpm, NHANES seated P50)     age 30   age 50   age 70     (band midpoints of 20–39, 40–59, 60–79)
  men                              69       68       67
  women                            74       71       70
joined linearly between the midpoints, flat below 30 and above 70
```

* **Why +10 bpm:** office HR is 10.4 bpm above mean night HR in 56,901 ambulatory recordings (75.4 ± 11.5 vs 65.0 ± 9.7 bpm; Böhm 2020), and about 12 bpm in 32,569 (Vinyoles 2014). unstrap uses the lowest 30 minutes, which is below the night mean, so +10 is the low end and the term leans slightly young (§9).
* Böhm's night HR was a cuff reading every 30 minutes, in hypertensive patients (mean age 58, 15 % on beta-blockers), with no lowest value reported. Night HR predicted death better than office HR, with risk rising above about 70 bpm at night.
* **Cross-check:** Fitbit's daily resting HR in 92,457 users (Quer 2020, read from a figure) is about 60–65 bpm for men and 64–68 for women over ages 20–80. Fitbit estimates lying-still HR just after waking, mostly from sleep data, not a minimum. The sleeping medians implied here (NHANES − 10: men 57–59, women 60–64) are a few bpm lower. That fits +10 bpm but doesn't prove it.
* NHANES excludes people with conditions or medicines that affect pulse, so its median is for fairly healthy adults.
* **No jump between age bands:** joining the band midpoints means a woman's reference falls from 74 to 71 bpm over ages 30–50, instead of on her 40th birthday (which would add 0.31 shown years overnight).
* Sex is required for the reference.
* **Limits:** the ±4 limit binds about 23.4 bpm either side of the reference. For a 35-year-old man that is a sleeping RHR above about 82 bpm. On the low side the 45 bpm floor stops the raw term at −3.8 to −4.1 for men and −4.3 to −5.0 for women (by age), so on that side the limit binds only near the floor, and never for men over about 43.
* Part of this slope overlaps steps and VO2max. The fitness limit and S absorb that (§3.7).
* **Band:** the slope at both ends of Aune's CI, 0.1430–0.1898 per bpm.

### 3.2 Steps (Contested; limits ±1.5 under 55, ±2 from 65; fitness group)

Against the lowest quartile, HR 0.60 (0.51–0.71), 0.55 (0.49–0.62) and 0.47 (0.39–0.57) for quartiles 2–4 ([Paluch et al. 2022](https://pmc.ncbi.nlm.nih.gov/articles/PMC9289978/)), at quartile medians 3,553 / 5,801 / 7,842 / 10,901 steps. We use the appendix's age-specific curves (Figure S2c, HR vs 3,500 steps), **read from the figure (±0.02)**:

```
HR vs 3,500 steps     3k    4k    5k    6k    7k    8k    9k    10k   12k   14k   16k
  < 60 y             1.12  0.89  0.71  0.56  0.46  0.40  0.38  0.38
  ≥ 60 y             1.12  0.89  0.71  0.61  0.56  0.54  0.52  0.50  0.47  0.44  0.41

L(x) = ln HR / G, joined linearly between the points, flat outside them

c_young = clamp(L_<60(steps) − L_<60(7,803), −1.5, +1.5)
c_old   = clamp(L_≥60(steps) − L_≥60(5,649), −2,   +2)
w       = clamp((age − 55) / 10, 0, 1)
c_steps = (1 − w) · c_young + w · c_old
```

* **Blended from 55 to 65 (design choice).** Switching at exactly 60 made the term jump: 7,000 steps scored +1.22 y at 59 and −1.51 y at 60. Blending spreads that over ten years, about 0.27 y per year of age at 7,000 steps.

  | Steps | 55 | 57 | 59 | 60 | 61 | 63 | 65 |
  |---|---|---|---|---|---|---|---|
  | 5,000 | +1.50 | +1.41 | +1.33 | +1.29 | +1.24 | +1.16 | +1.07 |
  | 7,000 | +1.22 | +0.68 | +0.13 | −0.14 | −0.42 | −0.97 | −1.51 |
  | 9,000 | −0.86 | −1.09 | −1.32 | −1.43 | −1.54 | −1.77 | −2.00 |

* **Under 60:** the curve bottoms out at 9,000–10,000 and rises slightly after (0.40 at 12k), so the line stops at 10,000. Best value −0.86 y. The +1.5 limit binds below about 6,900 steps.
* **60 and over:** risk keeps falling past 8,000, so there's no 8,000 cap. The −2 limit binds at about 8,200 steps, the +2 limit below about 4,600.
* **Why ±1.5 under 55:** the under-60 curve is steep near its median (about 1.5 y per 1,000 steps between 7,000 and 8,000, before shrinkage) and rests on thin data (7 studies, 359 deaths). Its CI is wide (about 0.21–0.70 at 9,000), and under-60 Q4 (0.60) wasn't lower than Q3 (0.51); the age difference at Q4 had p = 0.08. The limit caps how far a low or miscounted step total can push the estimate. It doesn't flatten the slope between about 6,900 and 7,800 steps.
* The limits (not ±4) are design choices: strap step counts aren't validated for chest or arm wear (evidence-review.md, "Steps and distance").
* A valid day has ≥ 10 h worn (design choice).
* **Band:** ±50 % of the term after its limits are applied (design choice), then clamped to the limits. The published CIs are all against the lowest quartile, so they can't bound a contrast against the median: the two HRs share a reference group and their covariance isn't published.

### 3.3 Sleep regularity (Probable; limits −0.57 / +2.71)

All-cause mortality by SRI quintile in UK Biobank, fully adjusted (n = 46,721), against the bottom quintile ([Windred et al. 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC10782501/), supplement Table S2.4):

| Model | Q2 | Q3 | Q4 | Q5 |
|---|---|---|---|---|
| SRI alone | 0.80 (0.69–0.93) | 0.75 (0.64–0.88) | 0.72 (0.61–0.84) | 0.70 (0.59–0.83) |
| **SRI + sleep-duration quintiles (used)** | 0.83 (0.71–0.96) | 0.78 (0.66–0.92) | 0.75 (0.64–0.89) | 0.74 (0.62–0.89) |

Median SRI 81.0 (IQR 73.8–86.3). Quintile cuts 71.65 / 78.56 / 83.17 / 87.32.

```
c_sri: ln HR re-based to the middle quintile, placed at quintile midpoints, joined linearly, flat outside

  SRI     68.2     75.1     80.9     85.2     89.4
  c_sri  +2.71    +0.68     0       −0.43    −0.57
  quint.   Q1       Q2       Q3       Q4       Q5
```

* **Why the model with duration:** the score also has a sleep-duration term (§3.4), so using SRI ratios adjusted for duration avoids counting sleep twice. The adjustment barely weakens SRI, and SRI and duration correlate weakly (adjusted R² 0.124, Table S2.3).
* Q3's midpoint, 80.9, stands in for the median (81.0 scores −0.01 y). Q1 and Q5 have no outer edge, so their points sit half a neighbouring quintile beyond the cut (design choice). The paper gives quintiles only, no continuous curve.
* The curve has the plateau built in: most of the risk is in the bottom fifth, and there's little benefit above the median. It replaces an earlier linear 0.237 y per point, taken from a 2022 conference abstract with no CI.
* The fully adjusted model is the cautious choice. The minimal model (age, sex, ethnicity) gives larger effects: SRI alone 0.72 / 0.62 / 0.54 / 0.52.
* Windred's SRI: GGIR sleep/wake from wrist accelerometry, each epoch compared with the one 24 h later, at least 120 h of valid pairs.
* SRI is the main sleep term: it's objective (accelerometry, not self-report) and predicted mortality better than duration.
* **Band:** ±50 % of the term after its limits are applied (design choice), then clamped to the limits, for the same reason as steps.
* **Reference problem:** 81 comes from wrist actigraphy. unstrap's SRI comes from strap sleep staging plus worn minutes, with GGIR-style valid pairs. Two open-source tools disagreed on the same data ([Sleep 2025](https://academic.oup.com/sleep/article/8273793)). See §9.

### 3.4 Short sleep only (Probable; limits 0 / +4)

All-cause mortality by device-measured sleep in UK Biobank, wrist actigraphy, 88,282 people and 2,973 deaths, fully adjusted (including MVPA), against 7.0 h (Saint-Maurice et al. 2024):

| Asleep | 4.5 h | 5 h | 5.5 h | 6 h | 6.5 h | 7 h |
|---|---|---|---|---|---|---|
| HR | 1.44 | 1.29 (1.09–1.52) | 1.16 | 1.06 | 1.01 | 1.00 |

```
c_sleep = ln HR / G at each point, joined linearly, held below 4.5 h, 0 from 7 h

  asleep (min)   270     300     330     360     390     420+
  c_sleep       +3.98   +2.78   +1.62   +0.64   +0.11     0        then limited to 0 … +4
```

* **Penalty only.** Nothing from 7.5 h up was significant, so 7 h and over scores 0 (`SLEEP_COUNTS_BELOW_MINUTES = 420`). Device data shows no long-sleep risk, and the self-reported long-sleep risk is likely caused by illness, not the other way round ([Yin et al. 2017](https://pmc.ncbi.nlm.nih.gov/articles/PMC5634263)).
* Below 4.5 h the term is held at +3.98, so the +4 limit (a design choice) never binds on the term itself. It binds on the top of the band below about 303 min.
* **Band:** ±50 % of the term after its limits are applied (design choice), then clamped to the limits.
* This replaces Yuan 2024 (UK Biobank, against 7–7.9 h), whose HR of 2.48 below 5 h is the outlier among recent device cohorts. Yuan gave +9.17 y at 4.5 h, +2.12 at 5.5 h and 0 from 6.5 h.
* Yuan in turn replaced Cappuccio 2010, whose ratios came from self-report. Self-report runs 48–66 min above measured sleep (Lauderdale 2008, Jackson 2018), most of all for short sleepers.
* How strap minutes compare with wrist actigraphy is unknown (§9).

### 3.5 Strength training (Probable; limits −1.52 / 0)

All-cause mortality by weekly minutes of muscle-strengthening activity against none, in 147,374 people with 35,798 deaths over up to 30 years (Zhang et al. 2026, Br J Sports Med, Table 2). The model is adjusted for aerobic activity, so steps and VO2max aren't credited twice:

| min/week | 0 | 1–29 | 30–59 | 60–89 | 90–119 | 120+ |
|---|---|---|---|---|---|---|
| HR | 1.00 | 0.95 (0.92–0.97) | 0.91 (0.87–0.95) | 0.91 (0.86–0.96) | 0.87 (0.81–0.95) | 0.92 (0.86–0.98) |

```
c_strength = ln HR(minutesPerWeek) / G, placed at the category midpoints, joined linearly, held from 135 min, 0 at 0 min

  min/week    0      15      45      75      105     135+
  HR         1.00   0.95    0.91    0.91    0.87    0.92
  c (y)       0    −0.56   −1.03   −1.03   −1.52   −0.91
```

* **Credit only:** the reference is 0 min/week, the population median, so this term can only take years off. No dose carries a penalty: every category's CI is below 1.00.
* No opt-in is needed. An unrecorded gym session earns no credit, but it can't add years either.
* Only `sportType` 52 (Strength training) and 50 (Core training) count. That is a deliberate subset of `SportTypes.STRENGTH`, which also holds Indoor fitness (24), Hyrox training (222) and Hybrid training (226): those mix in cardio, and only dedicated strength sessions count here.
* Not in the fitness group: strength is a separate exposure in Zhang 2026, adjusted for aerobic activity, and none of the fitness terms measures it.
* **The shape between points is a design choice.** The paper gives categories, not a curve. 120+ has no upper edge, so its point sits at 135, half a category past the cut. The best category, 90–119 min, gives the limit: ln 0.87 / G = −1.52 y.
* **Target:** the coaching target is 90 min/week (`STRENGTH_TARGET_MINUTES`), where the best category starts.
* **Band:** the same table's CIs at the same points, joined the same way, then clamped to the limits. The reference is the table's own reference, so the CIs apply directly. The low end reaches the −1.52 limit from 45 min.
* This replaces Momma 2022, a curve read off a figure, which rose back to 1.00 at 140 min/week and gave −2.03 y at its best (40 min).

### 3.6 VO2max (Established for the ratio, Contested for the input; optional; limits ±2; fitness group)

Each 1 MET higher: RR 0.87 (0.84–0.90) for all-cause mortality ([Kodama et al. 2009](https://bioblast.at/index.php/Kodama_2009_JAMA)).

```
c_vo2 = 0.5 · (−1.520) · (VO2 − VO2_ref(age, sex)) / 3.5
```

* Only the strap's **exercise-based** estimate. Never Jurca (§8).
* Half weight because wearable estimates have limits of agreement around ±10 ml/kg/min (evidence-review.md, Molina-Garcia 2022). At full weight that's ±4.3 y of noise; at half weight, ±2.2 y.
* **Band:** the slope at both ends of Kodama's CI (RR 0.84–0.90).

### 3.7 Fitness group (design choice; limit ±5)

```
c_fit = clamp(c_rhr + c_steps + c_vo2, −5, +5)
```

* Resting HR, steps and VO2max all track cardiorespiratory fitness, and the strap's VO2max is itself worked out from heart rate. None of the three source ratios is adjusted for the others (Aune 2017 not for fitness, Kodama not for activity, Paluch not for fitness), so adding them up counts fitness more than once.
* **An outlier guard, not the overlap fix.** The shared ±5 y limit (±3 y after S) binds for about 1–5 % of users in a simulated population of 35-year-old men (§9): only those far from the median on several fitness terms at once (Example C). For everyone else, the overlap is handled only by S = 0.6, which also shrinks the sleep and strength terms that don't overlap.
* On their own, the three terms could reach about −6.8 y (a man under 55) to −8 y (a woman of 70+) and +7.5 to +8 y. With the steeper resting HR slope (§3.1) the −5 side can bind too, but only when all three are far better than typical at once.
* **Why not tighter:** a ±3 limit bound for 19–36 % of users, so it would have been a hidden extra shrink on the fitness terms. That would fit the evidence better: adding plain activity volume removes about two-thirds to four-fifths of activity clocks' predictive power (Pyrkov 2018), so the overlap is probably large. ±5 was chosen to keep the fitness terms' spread. The overlap is a known weakness (§9).
* **Band:** the summed term bands, clamped to ±5.

## 4. Pace of aging

WHOOP projects the last 30 days over five months, on a scale of −1x to 3x.

```
offset_w = clamp(S · (c_fit + c_sri + c_sleep + c_strength)(window w), −5, +5)
Δ        = offset_30 − offset_180
σ        = SD of offset over the earlier non-overlapping 30-day blocks in the 180 days (days 31–60, 61–90 …)
Pace     = 1                                       if |Δ| < max(2σ, 0.25 y)
         = clamp(1 + Δ / (5/12), −1, 3)            otherwise
```

* 1x = in step with the calendar; below 1 = habits improving; above 1 = slipping.
* **Why the noise gate:** 0.42 y of Δ moves Pace by a whole 1x. That's a few SRI points near the bottom quintile, or about 4 bpm of 30-day resting HR. Without the gate, Pace mostly tracks noise.
* offset_180 includes the last 30 days, so Δ understates a real change by about a sixth.
* **VO2max is held at its 90-day value in every window** (decided 2026-10-20). Most 30-day blocks have fewer than 3 workouts, so scoring it per block would drop it to the median and read as a change that didn't happen. Holding it fixed means it cancels out of Δ and σ: pace reflects resting HR, steps, sleep and strength. A real VO2max change still moves the estimate itself, through the weekly 90-day median. When the fitness limit binds, VO2max can still nudge pace slightly, because the limit absorbs part of a change in resting HR or steps.
* The 5/12 divisor follows WHOOP's five-month projection (design choice), as do the 2σ threshold and the 0.25 y floor. σ comes from your own history, so users with steady habits see smaller real changes.
* **Show** to the nearest 0.5x.
* Needs ≥ 21 valid days and ≥ 2 valid weekly SRIs in the 30-day window, and ≥ 3 earlier blocks meeting the same rule for σ. Otherwise Pace is withheld.

## 5. Uncertainty and display

* **Band:** work out every term at both ends of its band (§3), clamp each to that term's limits and the fitness group to ±5, add up the low ends and the high ends separately, then apply S and the ±5 y cap. Widen the result to at least ±2 y around the estimate, because the bands don't include uncertainty in S, measurement error or the reference problems in §9.
* **In practice the shown range is the estimate ±2 y**, in whole years: the summed bands come out narrower than ±2 y in all three worked examples. It's a display convention, not a confidence interval, and the app should never call it one.
* **Whole years only**, never a decimal (as for VO2max).
* **Wording:** shown as "Habit Age". No mortality language, as for fitness age.
* **Recompute** weekly (Sunday), like WHOOP and like VO2max.
* **The card** (`HabitAgeEdgeCard`, one design wherever it shows): an orchid edge card lit from the left. It shows:
  * the title "Habit Age" and an (i) button, which opens an info sheet on the method, the pace of aging and what moves it
  * "Updated Sun 18 Oct" under the title
  * the estimate rounded to a whole number, with "years" beside it
  * one status line: "Likely 30 to 34" until there's a last week's estimate, then "1 year younger than last week", "older" or "Same as last week", in orchid only when the number moved
  * an age scale of your age ±7: a faint track, the range as an orchid band, a white dot at the estimate and a hairline at your age ("You, 35"), with the range ("30 to 34") under the band
  * There's no lever row and no green or amber. Before the first estimate your own age stands in ("Your age for now" and when the first estimate comes). Without a birth year or sex, the card opens the profile.
* **Where the card shows:**
  * **Recovery tab:** always, as the last card.
  * **Today tab:** near the end of the list, after Week and before Start workout. It shows on its own only on the Sunday of a new estimate and the day after, until opened, and can be pinned in Edit Today. Never before the first estimate.
  * Not on the Activity tab, which keeps VO2max and fitness age.
* **Habit Age screen**, opened from the card: the subtitle "Updated Sunday, next in 5 days" ("Updated today, next on Sunday" on a Sunday), the same card, then:
  * "Pace of aging": one row saying what the pace means, over a −1x to 3x scale
  * "What moves it" (over 6 months): one row per habit with its metric icon, "Takes off about 1 year" or "Adds about ½ year", and the numbers behind it (yours, the typical value, and an aim for steps below 8,000 and strength below 90 min/week. Time asleep says it only counts below 7 h). Each row has an effect bar on one shared scale (−1½ y to +1½ y, a bar at either end meaning that or more). The bars are in Habit Age's own ink, never green or amber: the side of the middle says better or worse. When the fitness limit binds, a line says so.

## 6. Withholding rules

Checked in this order (design choices, mirroring `Vo2max.Withheld`):

1. Age outside 20–79, or no sex.
2. Beta-blocker or pregnancy flag: resting HR doesn't reflect fitness then.
3. Fewer than 90 nights of resting HR in 180 days.
4. Fewer than 90 valid step days in 180 days.
5. Fewer than 8 valid weekly SRIs in 180 days.
6. Fewer than 90 main sleeps in 180 days.

**Early estimate:** after 30 days, with the same rules scaled to the window, shown with at least ±4 y and labelled "early". VO2max and strength are never required.

## 7. Worked examples

Use all three as unit tests. Band ends are clamped to each term's limits.

**A. Close to average.** Man, 35. Over 180 days: sleeping RHR 58, 7,000 steps/day, SRI 75, 400 min asleep, no strength sessions, strap VO2max 44 (FRIEND P50 for 30–39: 39.7).

| Term | cᵢ (y) | Band |
|---|---|---|
| Resting HR (68 seated vs 68.75) | −0.13 | −0.14 … −0.11 |
| Steps (vs 7,803) | +1.22 | +0.61 … +1.50 |
| VO2max | −0.93 | −1.17 … −0.71 |
| *Fitness group* | *+0.16* | *−0.70 … +0.68* |
| SRI | +0.71 | +0.35 … +1.06 |
| Short sleep (6 h 40, just under 7 h) | +0.07 | +0.04 … +0.11 |
| Strength | 0 | 0 |
| **Σ** | **+0.94** | **−0.32 … +1.85** |

`35 + 0.6 × 0.94 = 35.57`. Band 34.8–36.1, widened to ±2 → shown as **about 34–38**. Biggest levers: steps (7,000 → 9,000 would move the term from +1.22 to −0.86 y) and strength training (up to −1.52 y).

**B. Healthy.** Man, 35: sleeping RHR 55, 10,000 steps/day, SRI 87, 450 min asleep, 40 min/week of strength, strap VO2max 46.4 (FRIEND P70).

| Term | cᵢ (y) | Band |
|---|---|---|
| Resting HR (65 seated vs 68.75) | −0.64 | −0.71 … −0.54 |
| Steps | −0.86 | −1.29 … −0.43 |
| VO2max | −1.45 | −1.82 … −1.10 |
| *Fitness group* | *−2.95* | *−3.82 … −2.07* |
| SRI | −0.49 | −0.57 … −0.25 |
| Short sleep | 0 | 0 |
| Strength (HR 0.917, 0.878–0.953, at 40 min) | −0.95 | −1.42 … −0.52 |
| **Σ** | **−4.39** | **−5.81 … −2.84** |

`35 + 0.6 × (−4.39) = 32.36`. Band 31.5–33.3, widened to ±2 → shown as **about 30–34**.

**C. Unfit (fitness limit binds).** Man, 35: sleeping RHR 80, 4,000 steps/day, SRI 75, 400 min asleep, no strength sessions, strap VO2max 30.

| Term | cᵢ (y) | Band |
|---|---|---|
| Resting HR (90 seated vs 68.75) | +3.64 | +3.04 … +4.00 (limit) |
| Steps (vs 7,803) | +1.50 (limit) | +0.75 … +1.50 |
| VO2max | +2.00 (limit) | +1.59 … +2.00 |
| *Fitness group* | *+7.14 → +5.00* | *+5.00 … +5.00* |
| SRI | +0.71 | +0.35 … +1.06 |
| Short sleep (6 h 40) | +0.07 | +0.04 … +0.11 |
| Strength | 0 | 0 |
| **Σ** | **+5.78** | **+5.39 … +6.17** |

The steps band is ±50 % of the term *after* its limit (§3.2), so a capped term keeps a real range: +1.50 × 0.5 = +0.75 up to the limit. VO2max's band comes from Kodama's CI ends, each clamped to ±2.

Even the low ends of the fitness bands add up past +5 (+5.38), so the whole fitness band sits at the limit.

`35 + 0.6 × 5.78 = 38.47`. Band 38.2–38.7, widened to ±2 → shown as **about 36–40**. Biggest levers (change in the shown estimate): 90 min/week of strength −0.76 y, 40 min −0.57 y, 10,000 steps only −0.13 y, and median steps (7,803) or a sleeping RHR 10 bpm lower 0 y. Fitness gains count for little here because the limit absorbs the first 2.14 y of them.

A user exactly at every median gets 35.0.

## 8. The three objections in evidence-review.md

| Objection | What this proposal does | Resolved? |
|---|---|---|
| Summing hazard ratios that weren't adjusted for each other gives a falsely precise number | Sleep terms use mutually adjusted ratios (§3.3). The three fitness terms share a ±5 y outlier limit (§3.7), so for most users their overlap is handled only by shrinkage S = 0.6. Per-term limits, a ±5 y cap, a ±2 y minimum display range, whole years only | **Partly.** S and the fitness limit are design choices; there's no outcome data to fit them |
| The sleep ratios come from self-report, and the long-sleep risk is likely reverse causation | Both sleep terms now come from accelerometer studies (Windred 2024, Saint-Maurice 2024). No long-sleep term | **Mostly.** Strap sleep isn't the same measurement as wrist accelerometry (§9) |
| Jurca's SEE adds ±2.3–2.7 "years" (as originally stated; an SEE of 1.45–1.97 METs actually works out to ±2.2–3.0 y) | Jurca isn't used; its RHR term would also count resting HR twice. Only the strap's exercise estimate, at half weight, optional, inside the fitness limit | **Mostly.** The strap estimate still carries about ±2.2 y of noise at half weight, and there's no VO2max term for users without workouts |

## 9. Known problems

* **S isn't fitted.** That would need outcome data unstrap will never have, so it stays a design choice. Evidence on how much the terms overlap is partial:
  * Sleep vs sleep: adding sleep duration barely weakens SRI (Windred, §3.3).
  * Sleep vs activity: in 6,673 NHANES adults with wrist accelerometers (Zhang Y 2024), an irregular sleep pattern carried HR 1.44 (1.09–1.90) before and 1.42 (1.01–1.99) after adjusting for activity volume and sleep duration. That pattern is a k-means cluster, not SRI, so it isn't scored.
  * Within fitness (resting HR, steps, VO2max): no mutually adjusted source, and the activity-clock evidence (Pyrkov 2018) suggests a large overlap. The ±5 y shared limit (§3.7) only catches outliers, so for most users this overlap is handled by S alone. A lower S for the fitness group, or a tighter limit, would address it; neither is chosen (design choice).
* **Strap values are taken as they are (decision).** No side-by-side check against a wrist accelerometer is planned. The strap's sleep staging, asleep minutes, steps and lowest-30-minute HR are scored directly against references from other devices. The ±2 y minimum range, whole-year display and the ±1.5 / ±2 step limits absorb that offset.
* **Strap vs wrist accelerometry.** The SRI median (81) and the sleep-duration studies use wrist accelerometers and GGIR-style processing. unstrap's SRI and asleep minutes come from strap sleep staging, so both sleep terms may be off by an unknown amount. Option: once enough users opt in to share anonymised SRI and sleep minutes, re-centre on unstrap's own medians.
* **Sleeping vs seated RHR.** +10 bpm is the night-mean gap; the lowest 30 minutes is lower still, so the term leans young. No study compares a wearable's overnight minimum with clinic seated HR directly. Fitbit's population figures (Quer 2020) are the closest wearable reference, but they're read from a figure and use a different, proprietary resting HR.
* **Curves read off figures.** The age-specific step curves (Paluch Figure S2c) are read off a published figure, to about ±0.02. Replace them if the authors publish tables.
* **Strength is scored from categories.** Zhang 2026 gives HRs for bands of minutes, not a curve, so the midpoints and the straight lines between them are a design choice. 120+ min (0.92) is less protective than 90–119 min (0.87), so the credit eases from −1.52 y at 105 min to −0.91 y from 135 min. That is still credit, never a penalty.
* **Strap steps aren't validated**, hence the ±1.5 / ±2 limits.
* **Mixed references.** Each reference comes from a different population and statistic: NHANES P50 (US, healthy reference sample), Paluch medians of cohort medians (15 cohorts), UK Biobank median SRI (healthier than the UK average), a fixed 7 h for sleep, FRIEND P50 (US, lab-tested, apparently healthy), BRFSS (US). An average unstrap user won't land exactly on 0.
* **Windows differ from the studies.** The studies used about one week of wear; unstrap scores 180-day averages. A 180-day value varies less than a one-week value, so unstrap users will cluster nearer the middle than the studies' quintiles and bands suggest.
* **The typical user reads slightly older.** A user at every median reads their own age, but the population doesn't centre on 0. In a simulated population of 35-year-old men the median shown offset is +0.3 y. Three terms push it older: short sleep (penalty-only; mean +0.32 y), SRI (bigger penalty below the median than credit above; +0.28 y) and steps (the under-60 term runs +1.5 to −0.86; +0.17 y). Strength pulls the other way (credit-only; −0.39 y); without it the median would be +0.7 y. These simulation figures, here and in §1, §3.7 and the next bullet, predate the 2026-11-04 change to resting HR, short sleep and strength (§3.1, §3.4, §3.5) and haven't been rerun.
* **Comparison with wearable ageing clocks validated against mortality.** A 200,000-draw simulation of 35-year-old men (inputs from this document's own sources; fitness inputs independent, or correlated at ρ = 0.4) gives a shown offset with SD 1.7–1.9 y, 5th–95th percentile −2.3 to +3.2 y (independent) or −2.7 to +3.6 y (ρ = 0.4), and median +0.3 y. The fitness limit binds for 1–5 % and the ±5 y cap for under 1 %. Published clocks:
  * **CosinorAge** (Shim et al. 2024; UK Biobank wrist accelerometry, built like PhenoAge): HR 1.08 (1.06–1.11) per year of age gap in the UK Biobank validation set (868 deaths), 1.12 (1.05–1.18) in NHANES (199 deaths). The paper doesn't report how widely the gap spreads.
  * **Pyrkov et al. 2018** (NHANES hip accelerometry, ages 18–85, 701 deaths): HR up to 1.45–1.53 per SD of gap for the two best models (1.05–1.31 for the others), about 4.1–4.6 y per SD on this scale. Adding activity volume removes most of it (HR 1.07–1.15).
  * Clocks trained to predict age rather than death (PPG: Nie 2025; sleep studies: Brink-Kjaer 2022) carry only HR 1.02–1.03 per year.
  * **What it means:** this formula spreads users less than the clocks validated against death. Most of those come from older cohorts that include sick people (CosinorAge median age 55–58, Nie mean 56–61, Brink-Kjaer 66; Pyrkov's NHANES sample spans 18–85), so a narrower spread for healthy younger users is expected, but probably not this much narrower. One shown year here implies HR exp(G/S) ≈ 1.17, above the clocks' 1.08–1.12 and calendar age's 1.10. That's the cost of S = 0.6. Nothing in this literature measures overlap across activity, fitness and sleep well enough to fit S; anything from 0.6 to 0.8 is defensible, so S stays 0.6.
  * The ±5 y cap binds for under 1 % of users. It's a guard against extreme inputs, not something that shapes typical results.
* **Paluch's quartiles were study-specific.** Each cohort split its own sample, and no pooled cut-points exist (checked in the appendix). The age-specific HRs use whole-cohort quartiles, not age-specific ones.
* **Missing terms.** A missing term is treated as the median. The range should widen when a term is missing (open).
* **Cohort ages.** Most of the hazard ratios come from middle-aged and older cohorts, and the Gompertz conversion assumes the ratios hold at every age. The estimate is least supported for users in their 20s.
* **No MVPA term.** WHOOP scores heart-rate zone time, and unstrap already computes MVPA-equivalent minutes (`Activity.mvpa`), but a sourced dose–response hazard ratio is needed before adding it. It would join the fitness group.
* **Not read in full text:** Kodama 2009, Vinyoles 2014, Bennie 2018 and Jackson 2018 (abstracts only).

## 10. Sources

* Aune D et al. Resting heart rate and the risk of cardiovascular disease, total cancer, and all-cause mortality: a systematic review and dose-response meta-analysis of prospective studies. Nutr Metab Cardiovasc Dis 2017;27(6):504-17. [PubMed](https://pubmed.ncbi.nlm.nih.gov/28552551/)
* Zhang D et al. Resting heart rate and all-cause and cardiovascular mortality in the general population: a meta-analysis. CMAJ 2016;188(3):E53-63. [PubMed](https://pubmed.ncbi.nlm.nih.gov/26598376/) (used before Aune 2017)
* Ostchega Y et al. Resting pulse rate reference data for children, adolescents, and adults: United States, 1999–2008. National Health Statistics Reports no. 41, 2011. [Archived PDF](https://web.archive.org/web/2020/https://www.cdc.gov/nchs/data/nhsr/nhsr041.pdf)
* Böhm M et al. Association of clinic and ambulatory heart rate parameters with mortality in hypertension. J Hypertens 2020;38(12):2416-26 (Spanish ABPM Registry; office vs night HR). [DOI](https://doi.org/10.1097/HJH.0000000000002565)
* Quer G et al. Inter- and intraindividual variability in daily resting heart rate and its associations with age, sex, sleep, BMI, and time of year: retrospective, longitudinal cohort study of 92,457 adults. PLoS One 2020;15(2):e0227709. [PMC7001906](https://pmc.ncbi.nlm.nih.gov/articles/PMC7001906/)
* Vinyoles et al. J Hypertens 2014 (Spanish ABPM Registry; office vs night HR; abstract only).
* Paluch AE et al. Daily steps and all-cause mortality: a meta-analysis of 15 international cohorts. Lancet Public Health 2022;7(3):e219-28. [PMC9289978](https://pmc.ncbi.nlm.nih.gov/articles/PMC9289978/) (appendix Figures S1c, S2a, S2c used)
* Zhang Y et al. Diurnal patterns of accelerometer-measured physical activity and sleep and risk of all-cause mortality: a follow-up of the National Health and Nutrition Examination Surveys (NHANES). Int J Behav Nutr Phys Act 2024;21:120. [DOI](https://doi.org/10.1186/s12966-024-01673-9) (§9 only)
* Windred DP et al. Sleep regularity is a stronger predictor of mortality risk than sleep duration. Sleep 2024;47(1):zsad253. [PMC10782501](https://pmc.ncbi.nlm.nih.gov/articles/PMC10782501/) (supplement Tables S2.3, S2.4 used)
* Yuan et al. npj Digit Med 2024;7:86 (wrist-accelerometer sleep duration, efficiency and mortality in UK Biobank). [PMC11106264](https://pmc.ncbi.nlm.nih.gov/articles/PMC11106264/) (Supplementary Figs 5, 6, 26 used; correction 2024-07-01 checked, replaced by Saint-Maurice 2024)
* Saint-Maurice et al. 2024, Sleep (UK Biobank wrist actigraphy, 88,282 people, device-measured sleep duration and all-cause mortality, fully adjusted including MVPA). [doi:10.1093/sleep/zsad312](https://doi.org/10.1093/sleep/zsad312) (§3.4)
* Yin et al. 2017, sleep duration and mortality meta-analysis (long-sleep reverse causation). [PMC5634263](https://pmc.ncbi.nlm.nih.gov/articles/PMC5634263)
* Lauderdale DS et al. Self-reported and measured sleep duration: how similar are they? Epidemiology 2008;19(6):838-45. [PMC2785092](https://pmc.ncbi.nlm.nih.gov/articles/PMC2785092/)
* Jackson et al. Sleep 2018;41(6) (self-reported vs actigraphy and polysomnography sleep duration in MESA; abstract only). [PMC5995218](https://pmc.ncbi.nlm.nih.gov/articles/PMC5995218/)
* Momma H et al. Muscle-strengthening activities are associated with lower risk and mortality in major non-communicable diseases. Br J Sports Med 2022;56(13):755-63. [PMC9209691](https://pmc.ncbi.nlm.nih.gov/articles/PMC9209691/) (replaced by Zhang 2026)
* Zhang et al. 2026. Br J Sports Med (muscle-strengthening minutes and all-cause mortality, 147,374 people, 35,798 deaths, up to 30 years, adjusted for aerobic activity). [doi:10.1136/bjsports-2025-110503](https://doi.org/10.1136/bjsports-2025-110503) (Table 2 used, §3.5)
* Bennie JA et al. Am J Prev Med 2018;55(6):864 (muscle-strengthening exercise prevalence, BRFSS 2015, n = 397,423; abstract only). [DOI](https://doi.org/10.1016/j.amepre.2018.07.022)
* Kodama S et al. Cardiorespiratory fitness as a quantitative predictor of all-cause mortality and cardiovascular events. JAMA 2009;301(19):2024-35. [Summary](https://bioblast.at/index.php/Kodama_2009_JAMA)
* Levine ME et al. 2018 / Liu Z et al. 2018, PhenoAge Gompertz transformation (G = 0.09165, as printed in the correction). [Correction, PLOS Med 2019](https://pmc.ncbi.nlm.nih.gov/articles/PMC6388911)
* Kaminsky LA et al. FRIEND 2022 reference standards. Mayo Clin Proc 2022;97(2):285-93 (already in `Vo2max.Friend`).
* Li Y et al. Impact of healthy lifestyle factors on life expectancies in the US population. Circulation 2018;138(4):345-55. [PMC6207481](https://pmc.ncbi.nlm.nih.gov/articles/PMC6207481/)
* Shim, Fleisch, Barata 2024. npj Digit Med (CosinorAge: wrist-accelerometer biological age, UK Biobank and NHANES, validated against mortality). [PMC11150246](https://pmc.ncbi.nlm.nih.gov/articles/PMC11150246/) (§9 only)
* Pyrkov TV et al. 2018. Sci Rep (activity-based biological age from NHANES accelerometry, validated against mortality). [PMC5980076](https://pmc.ncbi.nlm.nih.gov/articles/PMC5980076/) (§3.7, §9)
* Nie et al. 2025. Commun Med (PPG-derived age in UK Biobank). [PMC12630966](https://pmc.ncbi.nlm.nih.gov/articles/PMC12630966/) (§9 only)
* Brink-Kjaer A et al. 2022. npj Digit Med (sleep-study-derived age and mortality). [PMC9307657](https://pmc.ncbi.nlm.nih.gov/articles/PMC9307657/) (§9 only)
* WHOOP Healthspan (target-based WHOOP Age, 30-day Pace, −1x to 3x). [WHOOP](https://www.whoop.com//thelocker/how-healthspan-on-whoop-helps-you-optimize-longevity)
