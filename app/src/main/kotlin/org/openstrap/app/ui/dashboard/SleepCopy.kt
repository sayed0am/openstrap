package org.openstrap.app.ui.dashboard

import org.openstrap.app.R
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.DayStat
import org.openstrap.app.data.Days
import org.openstrap.app.data.History
import org.openstrap.app.data.SleepEdits
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.Ranges
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import org.openstrap.app.presentation.Units
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.DetailCopy
import org.openstrap.app.presentation.Metric
import org.openstrap.app.presentation.Range
import org.openstrap.app.presentation.TabCopy

/*
 * The numbers and words of the Sleep tab's details (spec 5.2 and 6.1): the night-scoped vitals, the Sleep score, Sleep
 * regularity and the day's naps. Built only from what the day already loads, like [DetailCopy], so every rule is
 * tested without a screen. A night-scoped value always comes from the sleep itself, and its words say "Last night" or
 * "That night". The text is in res/values/strings_day_sleep.xml.
 */
internal object SleepCopy {
    /* ------------------------------------------- Night vitals ------------------------------------------- */

    /** The night shown, with the vitals averaged over its sleep; null without a main sleep that night. */
    fun tonight(state: DayUiState): SleepNight? = state.nights.lastOrNull()?.takeIf { it.date == state.date && state.sleep != null }

    /**
     * A night's value of [metric] from its sleep: the vitals averaged over the sleep, and for heart rate the strap's
     * average for the night. Null for a metric that isn't a night vital.
     */
    fun pick(metric: Metric): ((SleepNight) -> Float?)? = when (metric) {
        Metric.Hrv -> { n -> n.hrv }
        Metric.Breathing -> { n -> n.breathing }
        Metric.Spo2 -> { n -> n.spo2 }
        Metric.SkinTemperature -> { n -> n.skinTemperature }
        Metric.HeartRate -> { n -> n.session?.averageHeartRate?.toFloat() }
        else -> null
    }

    /** The night's heart rate readings while asleep (5-minute averages). */
    fun sleepHeart(state: DayUiState): List<ChartPoint> =
        state.sleep?.let { s -> state.sleepHeartRate.filter { it.x in s.start..s.end } }.orEmpty()

    /** The lowest heart rate while asleep, and when. */
    fun lowestHeart(state: DayUiState): ChartPoint? = sleepHeart(state).minByOrNull { it.y }

    /** The average heart rate while asleep: the strap's for the night, else the average of the readings. */
    fun averageHeart(state: DayUiState): Int? =
        state.sleep?.averageHeartRate ?: sleepHeart(state).takeIf { it.isNotEmpty() }?.map { it.y }?.average()?.roundToInt()

    /** The night's value of [metric], the one its night vital row shows. */
    fun nightValue(state: DayUiState, metric: Metric): Float? =
        if (metric == Metric.HeartRate) averageHeart(state)?.toFloat() else tonight(state)?.let { n -> pick(metric)?.invoke(n) }

    /** The nights before the one shown, oldest first. */
    private fun previous(state: DayUiState): List<SleepNight> = state.nights.filter { it.date < state.date }

    /**
     * Your normal of [metric] at night, from the nights before the one shown (up to [SLEEP_NIGHTS] - 1 of them), as the
     * night vitals read it. Heart rate has none: its normal is a resting one, not an average while asleep.
     */
    fun nightNormal(state: DayUiState, metric: Metric): History.Normal? {
        if (metric == Metric.HeartRate) return null
        val p = pick(metric) ?: return null
        return History.normal(previous(state).mapNotNull { n -> p(n)?.let { DayStat(n.date, it, it, it, 1) } }, state.date.minusDays(1), SLEEP_NIGHTS - 1)
    }

    /** How many of the nights before the one shown have a value of [metric], for "Learning your normal, 4 of 7 nights". */
    fun nightNormalNights(state: DayUiState, metric: Metric): Int = pick(metric)?.let { p -> previous(state).count { p(it) != null } } ?: 0

    /** The last 7 nights' values of [metric], oldest first, the night shown last with the same value as its Day. */
    fun nightWeek(state: DayUiState, metric: Metric): List<Pair<java.time.LocalDate, Float?>> {
        val p = pick(metric) ?: return emptyList()
        return state.nights.takeLast(org.openstrap.app.data.SleepHistory.WEEK_NIGHTS).map { n ->
            n.date to if (n.date == state.date) nightValue(state, metric) else n.session?.let { p(n) }
        }
    }

    /** The night's readings of [metric] while asleep, for its line. */
    fun nightSeries(state: DayUiState, metric: Metric): List<ChartPoint> {
        val s = state.sleep ?: return emptyList()
        val series = when (metric) {
            Metric.HeartRate -> state.sleepHeartRate
            Metric.Hrv -> state.sleepHrv
            Metric.Breathing -> state.breathRate
            Metric.Spo2 -> state.sleepSpo2
            Metric.SkinTemperature -> state.sleepSkinTemperature
            else -> emptyList()
        }
        return series.filter { it.x in s.start..s.end }
    }

    /**
     * The per-day series a night-scoped detail's Month and Year draw: blood oxygen's own night table, and for the rest
     * the metric's usual per-day one.
     */
    fun nightDaily(metric: Metric): DailyMetric = if (metric == Metric.Spo2) DailyMetric.SleepSpo2 else metric.daily

    /** Whether [nightDaily] comes only from sleep (breathing rate, night blood oxygen), so Month and Year are nights too. */
    fun dailyIsNightly(metric: Metric): Boolean = metric == Metric.Spo2 || metric == Metric.Breathing

    /**
     * The label under a night-scoped value: "ms, average asleep", "ms, average of the last 7 nights". Month and Year say
     * whether their days are nights or whole days, since only the night table and breathing rate are from sleep alone.
     */
    fun nightLabel(metric: Metric, range: Range, strings: Strings = Strings.app): String {
        val nightly = dailyIsNightly(metric)
        val id = when (range) {
            Range.Day -> R.string.day_sleep_night_label_day
            Range.Week -> R.string.day_sleep_night_label_week
            Range.Month -> if (nightly) R.string.day_sleep_night_label_month_nights else R.string.day_sleep_night_label_month_days
            Range.Year -> if (nightly) R.string.day_sleep_night_label_year_nights else R.string.day_sleep_night_label_year_days
        }
        return strings.format(id, metric.unit)
    }

    /** Under the state word when the night has no value of a vital. */
    fun nightNoValue(hasSleep: Boolean, today: Boolean, strings: Strings = Strings.app): Pair<String, String> =
        if (!hasSleep) {
            strings.get(R.string.day_sleep_no_sleep) to
                strings.get(if (today) R.string.day_sleep_none_last_night else R.string.day_sleep_none_that_night)
        } else {
            strings.get(R.string.day_sleep_no_reading) to
                strings.get(if (today) R.string.day_sleep_not_measured_last_night else R.string.day_sleep_not_measured_that_night)
        }

    /** The night's heart rate sentence: "Lowest 48 bpm at 03:10 last night, 55 on average while asleep." */
    fun nightHeart(lowest: Int?, at: String?, average: Int, today: Boolean, strings: Strings = Strings.app): String =
        if (lowest != null && at != null) {
            val id = if (today) R.string.day_sleep_heart_lowest_last_night else R.string.day_sleep_heart_lowest_that_night
            strings.format(id, lowest, at, average)
        } else {
            strings.format(if (today) R.string.day_sleep_heart_average_last_night else R.string.day_sleep_heart_average_that_night, average)
        }

    /**
     * The night heart rate's week, without a normal to read it against: "Averaged 55 bpm asleep over the last 7
     * nights, from 51 to 60." Null without any.
     */
    fun heartWeek(values: List<Int?>, strings: Strings = Strings.app): String? {
        val known = values.filterNotNull()
        return when (known.size) {
            0 -> null
            1 -> strings.get(R.string.day_sleep_heart_week_one)
            else -> strings.format(R.string.day_sleep_heart_week, Math.round(known.average()), known.min(), known.max())
        }
    }

    /* -------------------------------------------- Sleep score ------------------------------------------- */

    /** The label under the score. */
    fun scoreLabel(today: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (today) R.string.day_sleep_score_label_last_night else R.string.day_sleep_score_label_that_night)

    /** The Sleep score's state with no score: no sleep at all, or a night the strap didn't score. */
    fun noScore(hasSleep: Boolean, today: Boolean, strings: Strings = Strings.app): Pair<String, String> = when {
        !hasSleep -> strings.get(R.string.day_sleep_no_sleep) to
            strings.get(if (today) R.string.day_sleep_none_last_night else R.string.day_sleep_none_that_night)
        else -> strings.get(R.string.day_sleep_no_score) to
            strings.get(if (today) R.string.day_sleep_no_score_last_night else R.string.day_sleep_no_score_that_night)
    }

    /** The night's sentence: what pulled it down or lifted it, then how long ("Short night. 5 h 50 asleep."). */
    fun night(headline: String?, asleepSeconds: Long, others: List<Long>, strings: Strings = Strings.app): String =
        listOfNotNull(headline?.let { strings.format(R.string.day_sleep_headline_sentence, it) }, TabCopy.night(asleepSeconds, others))
            .joinToString(" ")

    /** One row of What shaped the score: its name, the reading behind it, its word, how full its bar is, and whether it's lit. */
    data class Factor(val name: String, val detail: String, val word: String, val fraction: Float, val off: Boolean)

    /**
     * What shaped the score, from [Narrative.sleepContributors]: time asleep against your need and minutes awake after
     * falling asleep, each against general sleep guidance. The words say what each did to the night, never the
     * strap's own bands ("Optimal", "Pay attention").
     */
    fun factors(
        contributors: List<Narrative.Contributor>, asleepSeconds: Long, needMinutes: Int, wasoMinutes: Int?, strings: Strings = Strings.app,
    ): List<Factor> =
        contributors.map { c ->
            val total = c.label == Narrative.TOTAL_SLEEP
            val detail = if (total) {
                strings.format(R.string.day_sleep_factor_asleep_detail, Units.duration(asleepSeconds), Units.duration(needMinutes * 60L))
            } else {
                strings.format(R.string.day_sleep_factor_awake_detail, Units.duration((wasoMinutes ?: 0) * 60L))
            }
            val name = strings.get(if (total) R.string.day_sleep_factor_time_asleep else R.string.day_sleep_factor_staying_asleep)
            Factor(name, detail, factorWord(c.rating, strings), c.fraction, c.rating != Narrative.Rating.GOOD)
        }

    /** A factor's word, by its rating against sleep guidance. */
    fun factorWord(rating: Narrative.Rating, strings: Strings = Strings.app): String = strings.get(
        when (rating) {
            Narrative.Rating.OPTIMAL -> R.string.day_sleep_factor_helping
            Narrative.Rating.GOOD -> R.string.day_sleep_factor_good
            Narrative.Rating.FAIR -> R.string.day_sleep_factor_fair
            Narrative.Rating.LOW -> R.string.day_sleep_factor_low
        },
    )

    /** The bedtime and wake figures' labels. */
    val FELL_ASLEEP: String get() = Strings.app.get(R.string.day_sleep_fell_asleep)
    val WOKE: String get() = Strings.app.get(R.string.day_sleep_woke)
    val ASLEEP: String get() = Strings.app.get(R.string.day_sleep_asleep)

    /* --------------------------------------------- Regularity ------------------------------------------- */

    /** The label under the regularity number. */
    val REGULARITY_LABEL: String get() = Strings.app.get(R.string.day_sleep_regularity_label)

    /** Regularity's state with no number: still learning, with what it needs or how the bedtimes moved. */
    fun regularityState(reg: SleepRegularity.Result?, strings: Strings = Strings.app): Pair<String, String> =
        strings.get(R.string.day_sleep_regularity_learning) to TabCopy.regularity(reg)

    /** The week's sentence, by its band, or by how much the middle of your sleep moved while the full measure waits. */
    fun regularity(reg: SleepRegularity.Result?, strings: Strings = Strings.app): String {
        val band = reg?.band
        val sd = reg?.midpointSdMinutes
        return when {
            band == SleepRegularity.Band.VeryRegular -> strings.get(R.string.day_sleep_regularity_very_regular)
            band == SleepRegularity.Band.Regular -> strings.get(R.string.day_sleep_regularity_regular)
            band == SleepRegularity.Band.Fair -> strings.get(R.string.day_sleep_regularity_fair)
            band == SleepRegularity.Band.Irregular -> strings.get(R.string.day_sleep_regularity_irregular)
            sd != null -> strings.format(R.string.day_sleep_regularity_midpoint, Units.duration(sd.roundToInt() * 60L))
            else -> strings.get(R.string.day_sleep_regularity_needs)
        }
    }

    /**
     * The night shown against your usual times, from minutes past midnight: "Last night you fell asleep at 23:40 and
     * woke at 07:05, 30 min later than usual." Within 20 minutes of your usual bedtime it says so.
     */
    fun regularityNight(bedMinutes: Int, wakeMinutes: Int, usualBed: Int?, today: Boolean, strings: Strings = Strings.app): String {
        val bed = clock(bedMinutes)
        val wake = clock(wakeMinutes)
        if (usualBed == null) {
            return strings.format(if (today) R.string.day_sleep_bedtime_last_night else R.string.day_sleep_bedtime_that_night, bed, wake)
        }
        val diff = Math.floorMod(bedMinutes - usualBed + 12 * 60, 24 * 60) - 12 * 60
        return when {
            abs(diff) < USUAL_MINUTES -> strings.format(
                if (today) R.string.day_sleep_bedtime_last_night_usual else R.string.day_sleep_bedtime_that_night_usual, bed, wake,
            )
            diff > 0 -> strings.format(
                if (today) R.string.day_sleep_bedtime_last_night_later else R.string.day_sleep_bedtime_that_night_later,
                bed, wake, Units.duration(diff * 60L),
            )
            else -> strings.format(
                if (today) R.string.day_sleep_bedtime_last_night_earlier else R.string.day_sleep_bedtime_that_night_earlier,
                bed, wake, Units.duration(-diff * 60L),
            )
        }
    }

    /** A bedtime this close to your usual one is "close to your usual". */
    const val USUAL_MINUTES = 20

    /** The night shown, without a sleep. */
    fun noNight(today: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (today) R.string.day_sleep_no_night_last_night else R.string.day_sleep_no_night_that_night)

    private fun clock(m: Int) = TimeOfDay.minutes(m)

    /* ----------------------------------------------- Naps ----------------------------------------------- */

    /** The label under the time napped. */
    fun napsLabel(today: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (today) R.string.day_sleep_naps_label_today else R.string.day_sleep_naps_label_that_day)

    /** The naps detail's state with none. */
    fun noNaps(today: Boolean, strings: Strings = Strings.app): Pair<String, String> =
        strings.get(R.string.day_sleep_no_naps) to
            strings.get(if (today) R.string.day_sleep_no_naps_today else R.string.day_sleep_no_naps_that_day)

    /** The day's naps in a sentence: "One nap, 45 min." or "Two naps, 1 h 10 in all." */
    fun naps(count: Int, totalSeconds: Long, strings: Strings = Strings.app): String =
        if (count == 1) strings.format(R.string.day_sleep_naps_one, Units.duration(totalSeconds))
        else strings.format(
            R.string.day_sleep_naps_many, DetailCopy.count(count, NAP).replaceFirstChar { it.uppercase() }, Units.duration(totalSeconds),
        )

    /** The unit [DetailCopy.count] spells the day's naps with ("two naps"). */
    private const val NAP = "nap"

    /** Under a nap's heart rate line. */
    fun napHeart(lowest: Int, average: Int, strings: Strings = Strings.app): String =
        strings.format(R.string.day_sleep_nap_heart, lowest, average)

    /** A nap the strap recorded without stages. */
    val NAP_NO_STAGES: String get() = Strings.app.get(R.string.day_sleep_nap_no_stages)

    /* --------------------------------------- Adjust times (edit spec 4.2) ------------------------------- */

    /** The times sheet's title. */
    fun timesTitle(nap: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (nap) R.string.day_sleep_times_title_nap else R.string.day_sleep_times_title_night)

    /** Under the sheet's title. */
    fun strapSaw(from: String, to: String, strings: Strings = Strings.app): String =
        strings.format(R.string.day_sleep_strap_saw, Ranges.of(from, to))

    /** How to read the sheet's heart rate. */
    val TIMES_HINT: String get() = Strings.app.get(R.string.day_sleep_times_hint)

    /**
     * Above the sheet's bar: the times and how long they span. A night's says "in bed", since the page above gives the
     * shorter time asleep ("23:05 to 08:16, 9 h 11 in bed"). A nap's is its plain length.
     */
    fun trimLine(nap: Boolean, from: String, to: String, seconds: Long, strings: Strings = Strings.app): String =
        strings.format(
            if (nap) R.string.day_sleep_trim_line_nap else R.string.day_sleep_trim_line_night, Ranges.of(from, to), Units.duration(seconds),
        )

    /**
     * Under the stages of a sleep or nap whose times reach past what the strap saw ([strapStart] to [strapEnd]): the
     * stages are only the strap's, so this says how much of the times they cover. Null when the times stay inside the
     * strap's, or miss them.
     */
    fun stagesCover(start: Long, end: Long, strapStart: Long, strapEnd: Long, strings: Strings = Strings.app): String? {
        if (start >= strapStart && end <= strapEnd) return null
        val seen = minOf(end, strapEnd) - maxOf(start, strapStart)
        return if (seen <= 0) null else strings.format(R.string.day_sleep_stages_cover, Units.duration(seen))
    }

    /** On a detail whose sleep or nap has the user's times. */
    fun yourTimes(from: String, to: String, strings: Strings = Strings.app): String =
        strings.format(R.string.day_sleep_your_times, strapSaw(from, to, strings))

    /** The score's info sheet, when the strap's score was moved for the user's times ([org.openstrap.app.data.SleepEdits.anchored]). */
    val SCORE_FROM_YOUR_TIMES: String get() = Strings.app.get(R.string.day_sleep_score_from_your_times)

    /* ------------------------------------------ Delete (edit spec 4.3) ---------------------------------- */

    /** The delete sheet's question. */
    fun deleteTitle(nap: Boolean, today: Boolean, strings: Strings = Strings.app): String = strings.get(
        when {
            nap -> R.string.day_sleep_delete_title_nap
            today -> R.string.day_sleep_delete_title_last_night
            else -> R.string.day_sleep_delete_title_that_night
        },
    )

    /** What a delete does, after the times. */
    val DELETE_WHAT: String get() = Strings.app.get(R.string.day_sleep_delete_what)

    /** Where to undo it later. */
    val DELETE_RESTORE: String get() = Strings.app.get(R.string.day_sleep_delete_restore)

    /** The snackbar after a delete, with Undo. */
    fun deleted(nap: Boolean, today: Boolean, start: String, strings: Strings = Strings.app): String = when {
        nap -> strings.format(R.string.day_sleep_deleted_nap, start)
        today -> strings.get(R.string.day_sleep_deleted_last_night)
        else -> strings.get(R.string.day_sleep_deleted_that_night)
    }

    /** The card in a deleted night's place. */
    val NO_SLEEP_TITLE: String get() = Strings.app.get(R.string.day_sleep_no_sleep_title)

    fun deletedNight(from: String, to: String, today: Boolean, strings: Strings = Strings.app): String =
        strings.format(
            if (today) R.string.day_sleep_deleted_night_last_night else R.string.day_sleep_deleted_night_that_night, Ranges.of(from, to),
        )

    /** The headline's label on a deleted night. */
    fun noScoreDeleted(today: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (today) R.string.day_sleep_deleted_label_last_night else R.string.day_sleep_deleted_label_that_night)

    /* ---------------------------------- Deleted sleep page (edit spec 4.4) ------------------------------ */

    val DELETED_INTRO: String get() = Strings.app.get(R.string.day_sleep_deleted_intro)
    val DELETED_FOOT: String get() = Strings.app.get(R.string.day_sleep_deleted_foot)

    /** The Deleted sleep page once nothing is left in it. */
    val DELETED_NONE: String get() = Strings.app.get(R.string.day_sleep_deleted_none)

    /**
     * The day a deleted entry is named by, from its times as they were shown (the user's when they moved it): a night by
     * the evening before the day it belongs to ([SleepHistory.nightOf]), a nap by its own day.
     */
    fun deletedDay(r: SleepEdits.Resolved, zone: ZoneId): LocalDate =
        if (r.strap.nap) Days.date(r.start, zone) else SleepHistory.nightOf(r.end, zone).minusDays(1)

    /** A deleted entry's name: "Night of Wed 29 Oct" or "Nap, Thu 30 Oct". */
    fun deletedName(nap: Boolean, day: String, strings: Strings = Strings.app): String =
        strings.format(if (nap) R.string.day_sleep_deleted_name_nap else R.string.day_sleep_deleted_name_night, day)

    /** Under it, on two lines so neither wraps beside Restore: "23:20 to 07:21", then "6 days left". */
    fun deletedLine(from: String, to: String, daysLeft: Int, strings: Strings = Strings.app): String =
        strings.plural(R.plurals.day_sleep_deleted_line, daysLeft, daysLeft, Ranges.of(from, to))

    /** Under a nap whose heart rate stayed as when sitting still (edit spec 4.5). */
    fun stillNap(bpm: Int, strings: Strings = Strings.app): String = strings.format(R.string.day_sleep_still_nap, bpm)

    /** The You page's row value. */
    fun deletedCount(n: Int, strings: Strings = Strings.app): String = strings.plural(R.plurals.day_sleep_deleted_count, n, n)
}
