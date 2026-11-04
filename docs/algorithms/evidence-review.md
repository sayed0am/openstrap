# Evidence review

What published research says about each part of OpenStrap's health model, and what we're changing. The current model is in [recovery.md](recovery.md); constant names match `Recovery.kt`.

**Status: steps 1–12 are done on branch `evidence-review` (2026-10-18), including the FRIEND 2022 band and fitness age (Kaminsky 2022 Table 3; bands at the reported 30th/50th/70th/90th percentiles). [recovery.md](recovery.md) describes the model as built.** Not done: step 13 (personal weights, which need ≥ 6 weeks of check-ins), VO2max phase 2 (phone GPS), the optional personal sleep need, and BioCharge logging (not readable from the strap). The new weights haven't been checked against real data yet. Constants marked for review are settled at the strap calibration review on **2026-10-30** ([readiness-calibration.md](readiness-calibration.md)).

## Summary

* The evidence supports individual signals, not composite scores. A 2025 review of 14 composite scores from 10 manufacturers found no published formulas and little validation ([Doherty et al. 2025](https://www.degruyterbrill.com/document/doi/10.1515/teb-2025-0001/html)).
* So every weight and curve in Readiness, Strain and Energy is a design choice. What we test them against matters more than the formula.
* Readiness means "how far last night was from your own normal". It doesn't predict anything. Even HRV-guided training, the best-tested use of nightly HRV, shows small effects: effect size 0.42 in Vesterinen 2016 ([JYX](https://jyx.jyu.fi/handle/123456789/50625)) and no significant group difference in Javaloyes 2019 ([UMH](https://dspace.umh.es/handle/11000/30650?locale=en)).
* The first job is a backtest harness and a morning check-in, so the 2026-10-30 review has data to decide with.

**Grades:** **Established** = consistent independent studies or consensus. **Probable** = one or two decent studies, or strong reasoning. **Contested** = conflicting or vendor-only evidence. **Design choice** = no evidence either way.
**Size:** **S** = constants or a small function. **M** = new state or computation. **L** = new subsystem.

| Area | Today | Decision | Evidence | Size |
|---|---|---|---|---|
| Readiness weights | 11 contributors, HRV ≈ 18 % effective | Autonomic block (HRV + RHR) 52 %; withhold without HRV or RHR | Design choice (weights), Probable (structure) | M |
| HRV input | ln mean RMSSD, 0.6 night + 0.4 week | Gated samples, 0.4 night + 0.6 week, normal-range band | Established (ln, rolling), Probable (blend) | S |
| Resting HR input | lowest 30-min average | Keep; also log sleep mean and 10th percentile | Probable | S |
| Baselines | mean/SD, min 4 nights | Median + 1.4826·MAD, show at 7, calibrated at 28 | Probable | S |
| Strap HRV/RHR | stored, unused | Placeholder, flagged fallback, QA check | Probable | S |
| Sleep score | duration 50 / efficiency 25 / stages 25 | Duration 70 / continuity 30; drop stage target | Established (staging weak) | S |
| Sleep need | 8 h flat | 480 min (18–64), 450 min (≥ 65), user override | Established | S |
| Sleep debt | half-life 4, repay ≤ 1 h | Half-life 7, a long night repays its surplus up to 2 h, 1 h free then 0 at 6 h | Established direction, Contested formula | S |
| Sleep regularity | none | SRI, ≥ 120 h valid pairs | Probable | M |
| Body-signal alerts | single night, ≥ 2 of 5 signals | Persistence rule anchored on resting HR, distinct signal families | Probable | M |
| Training load | Banister on HRR ≥ 0.20 | Keep; moving minutes only, HRR ≥ 0.35 (≥ 0.20 at 60+ steps), 3-of-5-minute filter, 14-day daytime RHR | Probable | S |
| ACWR | 5 % of Readiness | Out of Readiness; descriptive, labelled contested | Established (against scoring) | S |
| Strain 0–21 | none | 21·(1 − e^(−TRIMP/180)) plus "vs your usual" | Design choice | S |
| Energy and stress | Firstbeat-style states | Keep; 3-minute stress gate, new label | Contested | S |
| Energy expenditure | none | Mifflin BMR-anchored per-minute model | Established (BMR), Contested (rest) | L |
| MVPA, steps, distance | none | Cadence 100/130, WHO ×2, step length from height | Established (thresholds), Contested (distance) | M |
| VO2max, fitness age | none | Jurca 2005 as a band; fitness age optional; healthspan age implemented ([healthspan.md](healthspan.md)) | Established (Jurca), design choice (fitness age, combining healthspan terms) | M |

## Readiness

### Inputs

**HRV** (Established for ln and rolling average; Probable for the blend)

* A night is valid if main sleep is ≥ 3 h, with ≥ 6 RMSSD samples in 5–200 ms and ≥ 120 HR minutes in 30–220 bpm.
* `hrv_n` = ln(mean of the gated RMSSD samples during sleep). Never change this definition within a baseline.
* `hrv_7` = mean of `hrv_n` over the last 7 nights. It needs ≥ 3 valid nights (Plews, [AUT](https://openrepository.aut.ac.nz/items/d7b41d29-e92e-4acf-90ea-ed68d84d6c73)); flag it "thin" below 5 (5 of 7 nights were needed in 21,000+ wearable users, [VU Amsterdam](https://research.vu.nl/en/publications/heart-rate-variability-coefficient-of-variation-during-sleep-as-a/)).
* Score input: **0.4·z(tonight) + 0.6·z(7-night)**, the reverse of today's 0.6/0.4. Nightly wearable HRV error is 6 % (Oura Gen 4) to 16 % (Polar) MAPE ([Dial et al. 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC12367097/)), bigger than the ~3 % smallest worthwhile change ([Buchheit 2014](https://www.frontiersin.org/journals/physiology/articles/10.3389/fphys.2014.00073/full)). The Helio PPG isn't validated.
* The single night stays as the channel for sudden changes.
* Also show a normal-range state: `hrv_7` against centre ± 0.5·scale gives below / within / above. This is the band the HRV-guided training trials used.

**Resting HR** (Probable)

* Keep `rhr_n` = the lowest 30-minute mean within sleep; the window needs ≥ 20 valid minutes. Garmin uses the same definition.
* No study compares nightly definitions directly. Minimum sleep HR varied by about 8 bpm within people while average sleep HR stayed stable ([J Sports Sci Med 2003](https://jssm.org/02-4-133.p_d_f)), so a lowest 5-minute bucket would be noisier.
* Also log `rhr_mean_sleep` and `rhr_p10` (10th percentile of per-minute sleep HR). The review picks the least noisy one.

### Baselines (Probable)

Illness, alcohol and artefact nights inflate a mean/SD baseline, so a bad stretch soon becomes "normal" ([HRV4Training](https://www.hrv4training.com/blog2/determining-your-normal-range)). Published windows run from 28 nights ([Polar](https://support.polar.com/uk-en/nightly-recharge-recovery-measurement)) to 60 (HRV4Training); the trials used 4 weeks.

* Centre = median of the last ≤ `BASELINE_NIGHTS` = 60 valid nights, leaving out nights in Watch or Alert state.
* Scale = max(1.4826·MAD, floor), where MAD is the median absolute deviation. HRV uses ln values.
* Floors: ln HRV 0.05 (`HRV_LN_SD_FLOOR`), RHR 2.0 bpm, breathing 0.5 /min, skin temperature 0.15 °C, SpO2 0.5 %. The RHR floor is above device error (MAE about 1–1.8 bpm, Dial 2025).
* These phases replace `MIN_BASELINE_NIGHTS` = 4 and `CALIBRATED_NIGHTS` = 14:

| Valid nights | HRV and RHR contributors |
|---|---|
| < 7 | withheld; the strap's values are shown as a placeholder |
| 7–13 | scored, labelled "learning" |
| 14–27 | scored |
| ≥ 28 | labelled "calibrated" |

### Mapping and weights

Keep the logistic curve `100 / (1 + e^−(1.386 + 1.19·z))` (80 at z = 0, 93 at +1, 55 at −1, 27 at −2), with a dead zone at the smallest worthwhile change:

* z_eff = sign(z)·max(0, |z| − 0.5). Changes under 0.5 SD are noise (Buchheit, Plews).
* It applies to the HRV, resting HR, breathing and temperature contributors.

New weights (design choice), renormalised over the contributors present:

| Contributor | Today | New |
|---|---|---|
| HRV balance | 0.25 | 0.30 |
| Resting HR | 0.15 | 0.22 |
| Last night's sleep | 0.18 | 0.16 |
| Sleep balance | 0.10 | 0.12 |
| Body temperature | 0.10 | 0.06 |
| Breathing rate | 0.03 | 0.04 |
| Previous day's load | 0.07 | 0.04 |
| Blood oxygen | 0.05 | 0.03 |
| Recovery index | 0.07 | 0.03 |
| Breathing disturbances | 0.04 | moved to a Breathing card |
| Training load balance | 0.05 | moved to the Load screen |

* Autonomic 52 %, sleep 28 %, other body signals 13 %. Polar's only hint is that HR has "the biggest influence".
* **Withhold Readiness** unless an HRV or resting HR contributor is present. On 2026-10-16, sleep alone gave 52 against the strap's 91. Under this rule that day shows a Sleep score and the strap's placeholder instead.
* Breathing disturbances move out: they're a stable trait, so inside Readiness they only add a fixed offset.
* Keep the red-flag cap (`RED_FLAG` = 40, readiness ≤ contributor + 30) and the alert cap of 54 (one below the Fair band). Neither has a source; both are reasonable guards. Blood oxygen doesn't trigger the red-flag cap: wrist SpO2 is off by 2 to 4 points, so one low night is more often the sensor than the body.
* Sleep was the main driver of a reconstructed Oura Readiness (R² 0.71 from Oura's own inputs; questionnaires R² 0.29, [De Bot et al. 2025](https://www.scitepress.org/PublishedPapers/2025/137331)). Limiting sleep's share stops one short night from carrying the score. The weights are the first thing to backtest.

### The strap's own nightly HRV and resting HR (Probable)

Vendors define these differently and often don't say how: Garmin hides its window timing, WHOOP weights the last slow-wave stage, Polar uses the first 4 h. Those choices change agreement with ECG (Dial 2025). The strap's method is unknown and could change with firmware. A baseline that mixes two definitions measures the switch, not the body.

1. Until there are 7 valid nights of our own values, show the strap's values and sub-scores, labelled "Strap's estimate". They never enter OpenStrap's score.
2. On a night where our own value fails the validity rule, use the strap's value for display and alerts, flagged "strap fallback", and keep it out of the baseline.
3. Raise a data-quality flag when ours and the strap's differ by more than 15 % (HRV) or 3 bpm (RHR).
4. At the 2026-10-30 review, compare the night-to-night robust SD of both, and how each responds to tagged events. Switch to the strap's definitions only if they're clearly less noisy with the same response; then switch completely and rebuild the baseline.

## Sleep

### Score (Established that staging is weak)

* Drop the "deep + REM vs 45 %" target. Across six devices, four-stage kappa was 0.21–0.53, deep sleep was classified correctly only 47–70 % of the time, and wake specificity was 29–52 % ([Schyvens et al. 2025](https://academic.oup.com/sleepadvances/article/6/2/zpaf021/8090472)).
* Low wake specificity inflates wearable efficiency by 2–10 points, so an 85 % time-in-bed threshold uses the wrong denominator.
* Last-night sleep contributor = the strap's sleep score when there is one, else:
  * `durationScore` = 100·min(1, TST/need)², so 7 of 8 h ≈ 77 and 6 h ≈ 56.
  * `continuityScore` = clamp(100 − (WASO − 30 min)·(100/90), 0, 100), with WASO counted from first sleep to final wake.
  * sleep = 0.7·duration + 0.3·continuity.
* Stages are shown for information only, at most against the user's own baseline.

### Need (Established)

* 480 min for ages 18–64, 450 min for 65+. NSF ranges are 7–9 h and 7–8 h ([Hirshkowitz 2015 summary](https://www.hospimedica.com/critical-care/articles/294757126/panel-of-experts-recommend-new-sleep-durations.html)), above the AASM floor of ≥ 7 h ([AASM](https://www.aasm.org/resources/pdf/adultsleepdurationconsensus.pdf)).
* User override 420–540 min. Individual optimal sleep ranged from 7.3 to 9.3 h in the lab ([Kitamura et al. 2016](https://pmc.ncbi.nlm.nih.gov/articles/PMC5075948/)).
* Optional personal need after ≥ 14 nights (design choice): the median TST of unconstrained nights (waking ≥ 60 min later than the weekday median, not counting the night after a night ≥ 1 h short), clamped to 420–540 and blended 50/50 with the default until there are ≥ 6 such nights.

### Debt (direction Established, formula Contested)

Deficits build up almost linearly over 14 days without people noticing ([Van Dongen 2003](https://ntrs.nasa.gov/citations/20040087752)). One 10 h recovery night isn't enough ([Banks et al. 2010](https://pmc.ncbi.nlm.nih.gov/articles/PMC2910531)), and about 1 h of debt took 4 nights to clear (Kitamura). Today's 4-night half-life discounts nights 8–14 to 25 % or less, which undercounts the 14-day build-up.

* Go through the last 14 nights from oldest to newest; skip missing nights; naps count toward TST.
* λ = 0.5^(1/7) ≈ 0.906 (half-life 7 nights). d = need − TST.
* If d ≥ 0: debt = debt·λ + d. If d < 0: debt = max(0, debt·λ − min(|d|, 120 min)). The first free night after restriction ran about 2.2 h above need (Kitamura 2016), so a long night repays its whole surplus.
* Score = clamp(100 × (1 − (debt − 60 min) ÷ 300 min), 0, 100): 1 h of debt is free and 6 h scores 0. A flat 25 points an hour scored 0 for anyone 30 min short every night, because the decayed sum settles near 4 h. Withhold with fewer than 7 of 14 nights.

### Regularity (Probable)

SRI predicted all-cause mortality better than duration in 60,977 UK Biobank participants. Risk fell most between the bottom quintile (SRI < 71.65) and the next (HR 0.80), and a valid SRI needed only ≥ 120 h of paired epochs ([Windred et al. 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC10782501/)).

* Grid: 1440 one-minute epochs per local day, starting at 12:00. Sleep = any deep, light or REM minute, naps included; awake = wake; strap off = missing.
* Over the last 7 day-pairs, count only pairs where both minutes are valid (as [GGIR](https://www.rdocumentation.org/packages/GGIR/versions/3.3-8/topics/CalcSleepRegularityIndex) does). SRI = −100 + 200·matches / validPairs.
* Withhold below 7200 valid pairs (120 h).
* Shown as the Sleep screen's "Regularity", on the standard −100…100 scale (100 identical days, 0 random), rounded.
* Bands at Windred's UK Biobank quintile cuts (71.65 / 78.56 / 83.17 / 87.32), rounded: < 72 irregular (bottom fifth, the high-risk group), 72–79 fair, 79–87 regular, ≥ 87 very regular. Descriptive only: they depend on the sleep/wake method, and the strap's isn't GGIR's. Zepp's 60/80/90 bands are for a different (overlap) score and aren't used.
* Fallback when withheld but there are ≥ 4 main sleeps in 7 days: the SD of sleep midpoint, "good" under 60 min.

### Sleep-health count (Probable)

A count of good sleep dimensions predicted cardiometabolic disease (OR 0.901 per point), while most single dimensions didn't ([Brindle et al. 2019](https://pmc.ncbi.nlm.nih.gov/articles/PMC7458275/)). Shown on the Sleep screen, not in Readiness. Score 0–3:

* Duration: 420 ≤ TST ≤ 540 min (≥ 65: ≤ 510).
* Continuity: WASO ≤ 30 min, or sleep-period efficiency ≥ 90 %.
* Regularity: SRI ≥ 72.
* Timing is for information only: the midpoint is shown and flagged outside 01:30–04:30, never scored. In Brindle it marked 57.5 % of people "poor" and had no independent link.

## Body-signal alerts

Today's rule fires on one night with two or more conditions. It has the right core signal (resting HR) but the wrong structure.

* **Resting HR is the best-validated early-warning signal.** NightSignal compared overnight mean RHR with a streaming median: +3 bpm is a warning, ≥ +4 bpm on 2 nights in a row is an alert. It reached 80 % sensitivity, a median 3-day lead and 87.7 % per-night specificity, but still fired 1.1–1.3 times per 21 days in non-COVID users, set off by alcohol, exercise, travel and vaccination ([Alavi et al. 2022](https://pmc.ncbi.nlm.nih.gov/articles/PMC8799466)).
* **Requiring persistence cuts false alarms.** Needing ≥ 3 anomalous nights in 7 instead of ≥ 1 cut the healthy-period hit rate for breathing rate from 13.4 % to 0.88 % ([Natarajan et al. 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8443549)).
* **Breathing rate alone is specific but misses a lot:** 36.4 % of symptomatic cases had a night ≥ +3 /min, against 4.3 % of healthy people (same source). So +2 /min alone over-fires.
* **Temperature helps:** it raised a multimodal model's AUC from 0.770 to 0.819 ([Mason et al. 2022](https://pmc.ncbi.nlm.nih.gov/articles/PMC8891385)). The luteal-phase rise is about +0.3 °C at the finger ([Maijala et al. 2019](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6883568/)), so +0.5 °C is the lowest threshold that won't fire every cycle.
* **Only different signal families vote.** Cardiac, thermal and respiratory count as independent. HRV + RHR is closer to one signal counted twice. SpO2 has no early-warning evidence. No study tests a rule-based combination directly, so this part is reasoning, not a measured result.

### Rule (Probable)

Baseline per signal: the last 28 valid nights, leaving out the 5 most recent and any Watch or Alert nights (resting HR and temperature start rising 3 to 5 days before symptoms, [Mishra 2020](https://www.nature.com/articles/s41551-020-00640-6)); median + 1.4826·MAD; floors as for Readiness. No alerts before 14 valid nights. A 28-night window covers a whole menstrual cycle.

RHR here is the overnight mean sleep HR (the NightSignal metric). Readiness keeps the lowest-30-minute definition.

| Flag | Condition | Role |
|---|---|---|
| `RHR_FLAG` | rhr − c ≥ max(4 bpm, 2·s) | core |
| `RHR_WATCH` | rhr − c ≥ 3 bpm | watch only |
| `TEMP_FLAG` | temp − c ≥ 0.5 °C and ≥ 2·s (sleep mean, ≥ 30 readings) | core |
| `RR_FLAG` | rr − c ≥ 2.0 /min and ≥ 2·s | core |
| `HRV_SUPPORT` | z(`hrv_n`) ≤ −2.0 | support, never a core vote |

SpO2 and desaturations are left out; they stay in Readiness and the Breathing card. `SPO2_ALERT_DROP` no longer raises an alert.

* **Watch** (in the app, no push): any single core flag, or `RHR_WATCH` + `HRV_SUPPORT`.
* **Alert** (push; Readiness capped at 54), any of:
  * `RHR_FLAG` on 2 nights in a row;
  * ≥ 2 different core flags on the same night;
  * a core flag tonight and on ≥ 3 of the last 7 nights;
  * rr − c ≥ 3.0 /min and ≥ 2 spreads, on its own.
* One missing night is bridged; two or more reset persistence. The alert clears after 2 nights in a row with no core flag.
* Likely causes are noted, not suppressed: a previous day with TRIMP > 1.5× fitness (CTL to the day before), the chronic load Readiness's previous-day contributor uses. Day tags were removed from the app.

### Wording

Under the FDA's General Wellness guidance, revised on 6 January 2026, a wellness product may show HRV, SpO2 and similar values and may say professional evaluation "may be helpful". It stops being a wellness product if it names diseases, calls values abnormal, shows diagnostic thresholds, or uses alerts to prompt clinical action ([Covington](https://cov.com/en/news-and-insights/insights/2026/01/fda-issues-revised-guidance-on-general-wellness-products); [Loeb & Loeb](https://www.loeb.com/en/insights/passle/2026/01/fda-updates-its-general-wellness-device-frameworkwhat-companies-need-to-know)).

* Call the feature "Body signals off baseline". Never illness, infection, fever or COVID.
* Push copy: "Several of your overnight signals (resting heart rate, skin temperature) are outside your usual range for the second night. This can happen with heavy training, alcohol, travel, poor sleep, or when your body is fighting something off. Consider an easier day. If you feel unwell, checking in with a healthcare professional may be helpful. This is a wellness estimate, not a medical assessment."
* Breathing card: events per hour against the user's own history, without normal / mild / moderate / severe labels.
* No screen shows "+0.5 °C" as a fever line.
* Not reviewed: EU MDR and UK MHRA framing, and whether an open-source, non-commercial app counts as a "manufacturer".

## Training load and Strain

No HR-load method has been validated on everyday waking minutes. Even in structured sessions, Banister TRIMP's test–retest CV is 15.6 % ([Wallace et al. 2014](https://researchgate.net/profile/Franco-Impellizzeri/publication/261067400_Establishing_the_Criterion_Validity_and_Reliability_of_Common_Methods_for_Quantifying_Training_Load/links/5caee628a6fdcc1d498c2081/Establishing-the-Criterion-Validity-and-Reliability-of-Common-Methods-for-Quantifying-Training-Load.pdf)). Banister is still the least bad option: Lucia needs lab ventilatory thresholds, and Edwards' zones start at 50 % HRmax, which walking or sitting after coffee can reach.

### TRIMP (Probable as the least bad; design choice for everyday use)

* Keep the formula: x·0.64·e^(1.92x) (male or unknown), x·0.86·e^(1.67x) (female), where x = HRR. Days with < 8 h of HR stay excluded.
* New filter: a minute outside workouts counts only when awake and moving (1 to 250 steps), with x ≥ 0.35, or x ≥ 0.20 at 60+ steps (sustained walking), and ≥ 3 of the last 5 minutes qualify. Single PPG spikes no longer count.
* RHR for HRR = the 14-day median of the strap's daily resting HR, else median(nightly lowest + offset). Offset = median(daily RHR − nightly lowest) over 14 days when both exist, else 3 bpm. Karvonen and Swain define resting HR as awake rest; a nightly minimum would inflate HRR for every waking minute.

### Max HR (Probable)

* Default Tanaka 208 − 0.7·age, from 18,712 subjects ([Tanaka et al. 2001](https://doi.org/10.1016/S0735-1097(00)01054-8)). A user-entered test value overrides it.
* Any equation's individual SD is about 7–11 bpm, so raising the estimate is sound and lowering it isn't.
* Keep raising it to the observed maximum by at most +15 bpm, using each day's artefact-filtered 1-minute maximum over 90 days: the lower of the first two days (from the top) within 3 bpm of each other. One day alone never sets it.

### Load screen (descriptive)

* ACWR leaves Readiness (Established against scoring). Its own proponents accept the coupling critique ([BJSM 53/15/921](https://bjsm.bmj.com/content/53/15/921)), ACWR rescales acute load ([Impellizzeri 2021](https://iris.univr.it/retrieve/e34cfb98-e922-4c2f-aab5-18583ab7e31b/Impellizzeri_What%20Role%20Do%20Chronic%20Workloads%20Play_SportMed_2021.pdf)), and the 2025 meta-analysis for 0.8–1.3 has a CI of 0.14–0.94 in team-sport athletes ([Qin et al. 2025](https://pubmed.ncbi.nlm.nih.gov/41029871/)).
* Fitness and fatigue trend, the standard 42/7 convention:
  * CTL_t = CTL_{t−1} + (TRIMP_t − CTL_{t−1})·(1 − e^(−1/42)); ATL the same with 7.
  * Form = CTL_{t−1} − ATL_{t−1}.
  * Personal time constants aren't fitted: the parameters can't be reliably identified ([Imbach et al. 2025](https://hal.inrae.fr/hal-04999009v1)).
* Optional ACWR, labelled "contested": EWMA acute 7 / chronic 28, with chronic leaving out the current 7 days (uncoupled).
* Readiness keeps "previous day's load" (weight 0.04): 100 if TRIMP_yesterday / CTL ≤ 1.5, else −25 per unit, floor 30.

### Strain (design choice)

* Strain = 21·(1 − e^(−TRIMP_day/180)): about 7 for 1 h at 60 % HRR (male), about 15 for 2 h at 75 %.
* A fixed absolute curve keeps today's 12 comparable with last year's. WHOOP's 0–21 Strain has no published validation ([WHOOP](https://www.whoop.com/thelocker/how-does-whoop-strain-work-101/)).
* Shown separately: "vs your usual" = the percentile of today's TRIMP within the last 90 days.

## Energy and stress (Contested)

Energy stays. Firstbeat's method is the best publicly documented template: activity above 30 % of VO2max, 20–30 % as daily activity, and falling post-exercise EPOC never count as stress ([Firstbeat white paper](https://www.firstbeat.com/wp-content/uploads/2015/10/Stress-and-recovery_white-paper_20145.pdf)). Its only support comes from the manufacturer (r ≈ 0.40–0.50 with waking cortisol). Consumer stress scores separate rest from lab stress tasks but barely track self-reported daily stress ([Rosenbach et al. 2025](https://www.biorxiv.org/content/10.1101/2025.01.06.630177v1); [JMIR 2026](https://www.jmir.org/2026/1/e78244)).

* Keep every current constant, including the personal threshold *T* (`STRESS_MARGIN`, clamped to 50–70).
* New: stress drain starts only after ≥ 3 minutes in a row at or above *T*.
* New label: "physiological stress (estimate)", not "mental".
* New calibration target: an hour at stress 80 with *T* = 60 (0.007·20·60 = 8.4, plus 2.4 base) stays at or below a third of an hour at 60 % HRR (20–32).
* If the strap can report Zepp's BioCharge `totalCharge`, log it like Readiness and fit `SLEEP_K`, `BASE_DRAIN` and the drain coefficients to it. That calibrates to Zepp's black box, not to physiology.
* Deferred until there is something to calibrate against: a full EPOC model and separate physical and mental pools. Without ground truth they add parameters, not accuracy.

## Energy and activity

### Energy expenditure (Established for BMR; Contested for the rest)

Mifflin-St Jeor came within 10 % of measured resting metabolic rate in more people than other equations ([Frankenfield et al. 2005](https://pure.psu.edu/en/publications/comparison-of-predictive-equations-for-resting-metabolic-rate-in-/)). Against doubly labelled water, group-calibrated HR-only estimates were less precise than acceleration (RMSE 37 vs 24 kJ/day/kg), and adding HR to motion removed bias without improving precision ([Brage et al. 2015](https://pmc.ncbi.nlm.nih.gov/articles/PMC4562631/)). We have per-minute steps, not acceleration counts, so the model is motion first, with a narrow, labelled HR branch.

* BMR = 10·W + 6.25·H − 5·A + (male +5, female −161, unset −78) kcal/day; b = BMR/1440 per minute.
* net(MET) = (MET − 1)·3.5·W/200 kcal/min. Anchoring on the user's own BMR avoids counting rest twice.
* Per minute with data (no row = missing, never zero):

| Minute | kcal/min |
|---|---|
| In a phone workout | the workout's own kcal for that minute (below) |
| In a strap workout | the strap's kcal, pro-rated |
| Asleep | 0.95·b |
| ≥ 60 steps | b + net(cadence MET) |
| Some steps, or a neighbouring minute had steps | 1.3·b |
| Awake, sedentary | 1.15·b |

* Cadence MET (Moore et al. 2021, Med Sci Sports Exerc 53(1)): VO2 = −0.4367 + 0.001430·c² − 0.001455·age·c − 0.000009012·BMI·c² + 0.0008086·age·height (ml/kg/min, c in steps/min, height in cm), MET = VO2 ÷ 3.5, at least 1, age held to 21 to 84; sustained ≥ ~145 uses ACSM running. It replaces CADENCE-Adults' cut points ([Tudor-Locke et al. 2019](https://ijbnpa.biomedcentral.com/track/pdf/10.1186/s12966-019-0769-6)), which were set to classify intensity, not to cost a minute: 130 spm read as 6 METs where the measured cost for a typical adult is nearer 5.4. Moore's equation also takes age, BMI and height into account.
* HR branch, labelled "HR-assisted": awake, not in a workout, < 20 steps, ≥ 5 minutes in a row with x ≥ 0.40 → max(EE, b + net(min(6, 1 + x·(VO2max_est/3.5 − 1)))).
* The daily total is "partial" below 20 h of wear. Flag PAL = TEE/BMR outside 1.2–2.5. No thermic effect of food unless stated.
* 0.95, 1.15 and 1.3 are conventions, not 2024 Compendium values (sleeping and sitting quietly are both 1.0 there, [pacompendium.com](https://pacompendium.com/inactivity)).
* *Active kcal* = Σ max(0, EE − b) over the minutes with data: what you burned above resting, the same definition as Apple's and Zepp's "active". Sleep minutes (0.95·b) are held at zero, so sleeping never subtracts. The day also keeps it per local hour, split by source: workout minutes, walking minutes (≥ 60 steps outside a workout, cadence or running MET) and everyday (the rest, HR-assisted minutes included).
* *Phone workouts (design choice):* each active minute (minute start inside the shown range, pauses left out) takes max(motion, HR). Motion is the table above without the sleep row. HR is b + net(1 + x·(VO2max_est/3.5 − 1)) per heart rate sample, capped at 0.9·VO2max_est/3.5 METs rather than 6, and averaged over the minute; history minutes filled after a disconnect count the same way, spread over their minute. No 5-minute rule inside a workout. Every minute is then at least 1.15·b, so the workout's active kcal is its total less b per minute. Saved on the workout when it ends and again after a trim or a gap fill. The day uses these minutes with their active part scaled to the saved total, so a day and its workout always agree, even after a sync moves the steps or resting HR they were worked out with. While recording, a running total counts the minutes ended and the share gone of the one under way, and never goes down.
* *Daily history:* each day's totals, active, BMR, minutes with data, partial, workout and walking active kcal, and 24 hourly active values are kept in `daily_energy`. The day shown in the app rewrites its row; after each sync the last 7 days, any day without a row, and every day after a profile change are worked out again, on the strap's daily resting HR, today's max HR and that week's VO2max (these only move HR-assisted minutes).

### MVPA (Established thresholds)

* Moderate at cadence ≥ 100 (optionally ≥ 105 for age ≥ 61, [McAvoy 2023](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10688086/)), vigorous at ≥ 130 ([Tudor-Locke](https://pmc.ncbi.nlm.nih.gov/articles/PMC6029645)).
* A minute counts only inside a run of ≥ 2 qualifying minutes in a row (design choice).
* HR-MVPA: awake, ≥ 5 minutes in a row with x ≥ 0.40 (vigorous ≥ 0.60), and < 100 steps or in a workout. Never counted twice. WHO 2020 dropped the 10-minute bout, and [Jakicic 2019](https://pubmed.ncbi.nlm.nih.gov/31095078/) found shorter bouts carry the same benefit.
* MVPA-equivalent = moderate + 2·vigorous, against 150 min/week (WHO 2020). Raw minutes are shown too.

### Steps and distance (Contested)

* Steps: the strap's daily counter, else the sum of per-minute steps. Flag a difference > 5 %.
* Default goal 7,000 (≥ 60 y) or 8,000–9,000 (< 60 y), framed as "where most of the benefit is reached". Mortality plateaus at 6,000–8,000 at ≥ 60 and 8,000–10,000 below ([Paluch et al. 2022](https://doaj.org/article/c3639c468b4348958202e52793ab979d)).
* Distance ≈ steps·L: L = 0.414·H below 120 spm, linear to 0.45·H at 130. Running uses workout or GPS distance. A rolling median of GPS distance / steps from phone-GPS walks overrides L. Always shown with "≈".
* No step or cadence validation exists for a chest- or arm-worn strap.

## VO2max and fitness age

### VO2max (Established for Jurca; Probable for phase 2)

Jurca 2005 needs only sex, age, BMI, resting HR and one activity question. Its SEE is 1.45–1.97 METs, with no bias in an independent sample of older adults ([Mailey et al. 2010](https://pmc.ncbi.nlm.nih.gov/articles/PMC2831835/)). Exercise-based wearable estimates do better on average but still have limits of agreement around ±10 ml/kg/min ([Molina-Garcia 2022](https://link.springer.com/10.1007/s40279-021-01639-y)).

* VO2max (METs) = 2.77·sex (m = 1, f = 0) − 0.10·age − 0.17·BMI − 0.03·RHR + SRPA (0–4) + 18.07; × 3.5 for ml/kg/min.
* RHR = median of the last 7 nightly values (≥ 5 in 14 days). Sleeping RHR runs a few bpm below seated; at −0.03 METs/bpm that's small next to the SEE, so it's documented, not corrected.
* Show ±1 SEE (≈ ±5 ml/kg/min) and a FRIEND 2022 percentile band, < 25 / 25–50 / 50–75 / 75–97.7 / ≥ 97.7 ([Kaminsky et al. 2022, Mayo Clin Proc 97(2):285–293](https://doi.org/10.1016/j.mayocp.2021.08.020), open access). Never a decimal. Recompute weekly. *As built:* the paper's Table 3 gives only the 10th–90th percentiles, so the bands use the nearest reported cuts, < 30 / 30–50 / 50–70 / 70–90 / ≥ 90 (treadmill, RER ≥ 1.0).
* Withhold with < 5 nightly RHR in 14 days; height or weight missing or > 90 days old; the activity question unanswered in 180 days; age outside 20–79; a beta-blocker or pregnancy flag.
* Before coding: check the sex coding and SRPA weights against the Am J Prev Med 2005 full text (the weights may not be whole numbers: 0 / 0.32 / 1.06 / 1.76 / 3.03), and transcribe the FRIEND 2022 tables from the primary paper.
* Phase 2, phone GPS: flat, steady 1-minute epochs (speed 1.0–4.5 m/s, accuracy ≤ 10 m, HR SD < 3 bpm over 3 min after ≥ 3 min warm-up). VO2 = 3.5 + 0.1·v (walk) or 0.2·v (run), v in m/min. Fit VO2 = a + b·HR (span ≥ 15 bpm, r² ≥ 0.7), extrapolate to HRmax, and take the median of ≥ 3 sessions in 28 days. If it differs from Jurca by > 2 SEE, show both.

* *As built (phase 2, per workout):* outdoor runs only, ACSM running over %HRR per steady 2-minute window rather than a VO2–HR regression, gated as Zepp gates its own; shown with the workout, not merged with Jurca yet. See recovery.md "Workout effect".

### Workout EPOC and training effect (design choice on a Probable base)

Firstbeat's EPOC-from-%HRR structure is published in its white papers but its coefficients aren't; ours are fitted to round-number targets and mapped through Zepp's activity-class TE table, which the strap itself uses. Treat the figures as on the strap's scale, not as measured EPOC. Recovery time is a design choice, conservative by construction (it only lengthens with fatigue). Anaerobic TE is not attempted.

### Fitness age (design choice, optional)

* The age at which the FRIEND 2022 sex-specific 50th percentile equals the estimate, shown as a range (about ±10 y), clamped to 20–80. Withheld with VO2max.
* Wording: "typical of a [sex] aged about X–Y". No mortality language and no sleep term.

### Healthspan age: implemented (Established to Contested by term; design choice for combining them)

Biological age was dropped here on 2026-10-18 for three reasons. [healthspan.md](healthspan.md) defines a "your habits vs your age" estimate that answers each one. It was implemented on 2026-10-20, outside the implementation steps below.

It scores six terms against **population medians**, so a user at the median on every term reads their own age and better habits read younger: sleeping resting HR, steps, sleep regularity, short sleep, strength training and the strap's exercise VO2max. Each term's hazard ratio is turned into years with PhenoAge's Gompertz slope (0.09165). Resting HR, steps and VO2max share a ±5 y limit; the sum is shrunk by 0.6 and capped at ±5 y.

| Objection (2026-10-18) | Answer in healthspan.md | Resolved? |
|---|---|---|
| Summing hazard ratios that weren't adjusted for each other gives a falsely precise number | Sleep regularity uses ratios adjusted for sleep duration, which barely weakens it ([Windred et al. 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC10782501/)); an irregular-sleep pattern also holds after adjusting for activity volume (HR 1.44 → 1.42, [Zhang Y et al. 2024](https://doi.org/10.1186/s12966-024-01673-9)). Resting HR, steps and VO2max all track fitness and have no mutually adjusted source, so they share a ±5 y limit. That limit only catches outliers (about 1–5 % of users in simulation), so for most users their overlap is handled by the 0.6 shrinkage alone; healthspan.md §9 compares the result with validated wearable ageing clocks. Then shrinkage S = 0.6, the shown offset capped at ±5 y (a loose ceiling under [Li et al. 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC6207481/)'s 15 y five-factor contrast), a ±2 y minimum display range, and whole years only | **Partly.** S and the fitness limit are design choices; there's no outcome data to fit them, and the overlap between the fitness terms is probably larger than S allows for |
| The sleep ratios come from self-reported sleep, and the long-sleep risk is probably reverse causation | Both sleep terms now come from UK Biobank wrist accelerometry: SRI quintiles adjusted for duration (Windred 2024) and fully adjusted hazard ratios against 7 h asleep ([Saint-Maurice et al. 2024](https://doi.org/10.1093/sleep/zsad312), which replaced Yuan et al. 2024's much steeper 5 h figure). Short sleep only adds years, and there's no long-sleep term | **Mostly.** The references come from wrist accelerometers; the strap's values are scored as they are |
| Jurca's SEE adds ±2.3–2.7 "years" of noise (an SEE of 1.45–1.97 METs actually works out to ±2.2–3.0 y) | Jurca isn't used. Only the strap's exercise-based VO2max, at half weight, optional, limited to ±2 y and inside the fitness limit | **Mostly.** The strap estimate still carries about ±2.2 y of noise at half weight, and there's no VO2max term for users without workouts |

Still open (healthspan.md §9):

* **Strap values are taken as they are.** No side-by-side check against a wrist accelerometer is planned (decision). Strap sleep staging, asleep minutes and steps are scored against references from other devices; the ±2 y minimum range and whole-year display absorb the offset. In practice the shown range is the estimate ±2 y, a display convention rather than a worked-out confidence interval.
* **Strap steps aren't validated** (see "Steps and distance"), and the under-60 step curve is steep around its median, so step error moves the estimate more than any other input.
* **Two curves are read off published figures** (±0.01–0.02): Paluch's age-specific step curves. Strength now comes from Zhang 2026's Table 2, not a figure.
* **Sleeping vs seated resting HR:** +10 bpm (office vs night mean) is the low end for a lowest-30-minute value, so that term leans young.
* **Wording** follows fitness age: no mortality language, and the name is still open ("healthspan estimate", "habits age").

## Corrections to recovery.md

| recovery.md says | What the source shows |
|---|---|
| Vesterinen 2016 guided training with 7-day lnRMSSD | He used **raw** RMSSD from a 4-minute supine morning chest-belt recording; the band was mean ± 0.5 SD of 4 baseline weeks ([JYX](https://jyx.jyu.fi/handle/123456789/50625)) |
| Plews 2012 as the basis for the HRV rule | A case study of 2 athletes ([PubMed](https://pubmed.ncbi.nlm.nih.gov/22367011/)). Cite Plews' later weekly-average work and the trials |
| Elevated resting HR precedes symptoms "in most infections" (Mishra 2020) | 32 COVID-19 cases; 63 % detectable in real time ([medRxiv](https://www.medrxiv.org/content/10.1101/2020.07.06.20147512v1)). Nothing on "most infections" |
| Breathing-disturbance bands are "the usual clinical cut-offs" | They're apnea severity bands applied to strap desaturation counts, and naming them breaches the 2026 wellness guidance |
| Max-HR raising is "like Firstbeat" | No Firstbeat document confirming the rule was found |
| Several signals together "cuts false alarms" | Plausible but unmeasured; no study tested a rule-based combination |

## Open questions that need strap data

The Helio Strap has no published validation for nightly RMSSD, resting HR, skin temperature, sleep staging or step counting.

**Inputs**

* Our HRV and RHR or the strap's: which has the lower night-to-night robust SD with the same response to tagged events? (Decides the strap-input switch.)
* Which RHR definition is least noisy for this user: lowest 30-minute, sleep mean or 10th percentile?
* Skin-temperature resolution and night-to-night SD. (Sets the 0.15 °C floor; tests whether +0.5 °C is reachable.)
* The typical gap between daily RHR and the nightly minimum. (Replaces the 3 bpm offset.)

**Protocol**

* Does the strap's "awake" stage include falling-asleep time or a time-in-bed boundary? (WASO vs time-in-bed efficiency.)
* How often does it log sleep SpO2? Does the type-74 apnea summary land on the right morning?
* Does any channel expose beat-to-beat RR intervals, for artefact-corrected HRV?
* Can a strap used only with OpenStrap report BioCharge `totalCharge`?
* Do chest- and arm-worn step counts track cadence well enough for the 100/130 thresholds?

**Validity**

* Agreeing with the strap's Readiness only means agreeing with another unvalidated score. Only the morning check-in makes it a within-user test. In one such study, Oura RHR tracked subjective illness in only 6 of 20 athletes individually ([Spetz et al. 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC12696214/)).
* Open datasets (PMData, LifeSnaps, MMASH) come from other devices: they can test the logic, not the constants.

## Implementation order

1. **Measurement first (M):** a backtest harness that replays the engine day by day over an exported database; a 1–5 morning check-in; tags (alcohol, travel, vaccination, hard session, sick); log `hrv_n`, `rhr_n`, `rhr_mean_sleep` and `rhr_p10` next to the strap's values. Needed before 2026-10-30.
2. **Wording (S):** rename the alert, add the disclaimer copy, remove breathing-disturbance severity labels and on-screen temperature thresholds; fix the citations above.
3. **Readiness statistics (S–M):** median/MAD, floors, 7/14/28 phases, withhold without HRV or RHR, strap placeholder/fallback/QA, 0.4/0.6 blend, dead zone.
4. **Readiness weights (S):** new weights; ACWR to the Load screen; breathing disturbances to the Breathing card. Check with the backtest first.
5. **Alert state machine (M):** Watch/Alert, persistence, family voting, 28-night baseline.
6. **Sleep (S):** drop the stage target, age-based need, new debt formula, continuity score.
7. **Regularity (M):** SRI and the sleep-health count.
8. **Load (S):** 14-day daytime RHR, 3-of-5 filter, max-HR agreement rule, CTL/ATL/Form, Strain.
9. **Energy (S):** 3-minute stress gate, labels; BioCharge logging if readable.
10. **Activity (M):** step difference flag, MVPA, distance.
11. **Energy expenditure (L).**
12. **VO2max (M):** Jurca band after checking the coefficients; optional fitness age; GPS phase 2 later.
13. **Personal weights (M):** fit Readiness weights from check-ins, shrunk toward the defaults, only after ≥ 6 weeks of check-ins.
