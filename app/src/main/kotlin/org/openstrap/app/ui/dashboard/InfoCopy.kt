package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.Activity
import org.openstrap.app.data.History
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.SleepRegularity
import org.openstrap.app.presentation.RecoveryBands
import org.openstrap.app.presentation.SleepBands
import org.openstrap.app.presentation.Units

/*
 * What every detail's info sheet says (spec 6.2): a bold one-line meaning, the scale its value is read on, and three
 * folded rows. Every method name, formula and caveat that used to sit on a card or a detail lives here, one tap from
 * the number, so the cards can lead with what it means.
 */

/** An info sheet's words: the [meaning] in bold, the [scale] from low to high, and the three folded rows. */
data class Info(
    val name: String,
    val meaning: String,
    val scale: List<String>,
    val how: String,
    val moves: String,
    val off: String,
)

object InfoCopy {
    /** The scale of every value read against your normal, low to high. */
    val NORMAL_SCALE = listOf("Below your normal", "Usual", "Above your normal")

    /** Recovery's bands, low to high, in the words beside the score ([TodayCopy.recoveryWord]). */
    val RECOVERY_SCALE = RecoveryBands.WORDS

    /** Charge's bands, low to high. */
    val CHARGE_SCALE = listOf("Running low", "Some left", "Good", "Full")

    /** The Sleep score's bands, low to high, in the words beside it ([TabCopy.sleepScoreWord]). */
    val SLEEP_SCALE = SleepBands.WORDS

    /** Sleep regularity's bands, low to high ([SleepRegularity.Band]). */
    val REGULARITY_SCALE = SleepRegularity.Band.entries.map { it.label }

    /** Which bar of [SLEEP_SCALE] a Sleep score is in. */
    fun sleepBar(score: Int): Int = SleepBands.bar(score)

    /** Which bar of [REGULARITY_SCALE] a band is. */
    fun regularityBar(band: SleepRegularity.Band): Int = band.ordinal

    /** Which bar of [NORMAL_SCALE] a status against your normal is in; null without a normal. */
    fun normalBar(status: Int?): Int? = status?.let { it.coerceIn(-1, 1) + 1 }

    /** Which bar of [RECOVERY_SCALE] a score is in. */
    fun recoveryBar(score: Int): Int = RecoveryBands.bar(score)

    /** Which bar of [CHARGE_SCALE] a Charge is in, by quarters. */
    fun chargeBar(charge: Int): Int = (charge / 25).coerceIn(0, 3)

    /**
     * The line under the scale: "You are here. Your normal is 44 to 58 ms, from 30 days." Before there's a normal, how
     * far along it is.
     */
    fun normalLine(normal: History.Normal?, days: Int, format: (Float) -> String, unit: String, nightly: Boolean): String {
        val word = if (nightly) "nights" else "days"
        if (normal == null) return "Your normal shows after ${History.MIN_NORMAL_DAYS} $word with data. ${days.coerceAtMost(History.MIN_NORMAL_DAYS)} so far."
        val u = if (unit.isEmpty()) "" else if (unit.startsWith("%") || unit.startsWith("°")) unit else " $unit"
        return "You are here. Your normal is ${format(normal.low)} to ${format(normal.high)}$u, from ${normal.days} $word."
    }

    /** The line under Recovery's and Charge's scales. */
    const val HERE = "You are here."

    fun of(metric: Metric): Info = when (metric) {
        Metric.HeartRate -> Info(
            "Heart rate",
            "How fast your heart beats, in beats per minute (bpm), read by the strap through the day and night.",
            NORMAL_SCALE,
            how = "The strap reads your pulse with light through the skin, about once a minute, and more often in a " +
                "workout or while Live is on. The band behind the day's line is your resting range: your usual resting " +
                "heart rate, give or take a usual spread, over the last 30 days.",
            moves = "Movement, stress, caffeine and heat raise it. Rest and sleep bring it down.",
            off = "A loose strap, cold hands or busy arm movement can make readings jump. Live readings come more often, " +
                "so the line looks busier while it's on.",
        )
        Metric.RestingHr -> Info(
            "Resting heart rate",
            "Your heart rate at its calmest. Lower usually means a fitter, well rested heart.",
            NORMAL_SCALE,
            how = "The strap works it out each day from your calmest stretch, usually in sleep. Your normal is the middle " +
                "of your last 30 days, give or take a usual spread, once 7 of them have a value.",
            moves = "It rises after hard training, late meals, alcohol, poor sleep or when your body is fighting " +
                "something off, and falls as fitness builds.",
            off = "A night with the strap loose or off gives it less to go on. One high day says less than a few in a row.",
        )
        Metric.Hrv -> Info(
            "HRV",
            "Heart rate variability, the small changes in time between heartbeats. Higher than your usual means your body has recovered.",
            NORMAL_SCALE,
            how = "The strap measures the time between beats, in milliseconds (ms), while you're still, mostly in " +
                "sleep, and the day's value is the average of its readings. It's read against your own normal: " +
                "the middle of your last 30 days, give or take a usual spread.",
            moves = "Good sleep and rest raise it. Stress, hard training and alcohol lower it. Compare it with your own " +
                "normal, not other people's.",
            off = "Moving during a reading, a loose strap or a short night leave fewer clean readings. HRV swings a lot " +
                "from day to day, so a few days in a row say more than one.",
        )
        Metric.Spo2 -> Info(
            "Blood oxygen",
            "How much of your blood's capacity is carrying oxygen. Most people sit at 95 to 100%.",
            NORMAL_SCALE,
            how = "The strap shines red and infrared light through the skin and compares how much of each comes back. The " +
                "day's value is the average of its readings.",
            moves = "Altitude and pauses in breathing during sleep can lower it.",
            off = "A loose strap, cold skin or moving during a reading can give low values. It's a wellness reading, not a " +
                "medical one.",
        )
        Metric.Stress -> Info(
            "Stress",
            "The strap's stress level from 0 to 100. Higher means your body is more on alert.",
            NORMAL_SCALE,
            how = "The strap works it out from your heart rate and its variability while you're still. Readings during " +
                "and just after a workout are left out, since exercise isn't stress.",
            moves = "Pressure, caffeine and too little sleep raise it. Calm breathing, rest and time outdoors bring it down.",
            off = "Excitement, heat and an illness look like stress to the strap. Small movements while sitting can push it up.",
        )
        Metric.SkinTemperature -> Info(
            "Skin temperature",
            "The temperature of your skin under the strap. What matters is the change from your normal, not the level.",
            NORMAL_SCALE,
            how = "The strap measures it against your skin. The level depends on fit and the room, so OpenStrap shows it " +
                "as a change from your normal of the last 30 days.",
            moves = "It moves with alcohol, a warm room, hard training, the menstrual cycle, or when your body is fighting " +
                "something off.",
            off = "A warm bed, a heavy duvet or the strap sitting loose all change it without your body changing.",
        )
        Metric.Breathing -> Info(
            "Breathing rate",
            "How many breaths you take a minute while asleep. It stays very steady for most people.",
            NORMAL_SCALE,
            how = "The strap measures it during sleep, so there's one value a night, read against your normal of the last " +
                "30 days.",
            moves = "It can rise above your normal after a hard training day, in a warm, stuffy room, or when your body is " +
                "fighting something off.",
            off = "A restless night, or the strap off for part of it, leaves fewer readings to go on.",
        )
        Metric.Steps -> Info(
            "Steps",
            "Steps counted by the strap through the day.",
            NORMAL_SCALE,
            how = "The strap counts them from your arm's movement and keeps them minute by minute. A day's steps are the " +
                "total of its minutes.",
            moves = "Short walks spread across the day add up. Most people aim for 7,000 to 10,000.",
            off = "Pushing a pram or a trolley, or holding a rail, hides steps. Waving your arms about can add a few.",
        )
    }

    fun of(score: Score): Info = when (score) {
        Score.Recovery -> Info(
            "Recovery",
            "How ready your body is for effort today, from 0 to 100, worked out each morning from your night.",
            RECOVERY_SCALE,
            how = "Each morning OpenStrap compares your night with what's normal for you, not with other people: " +
                "HRV (heart rate variability), resting heart rate, skin temperature, breathing rate and blood " +
                "oxygen, last night's sleep and the past few nights, and how hard yesterday was. About half the " +
                "score comes from HRV and resting heart rate, about a quarter from sleep. One signal far from " +
                "your normal caps it, so good sleep can't fully make up for it. Everything is worked out on this " +
                "phone from your strap's data.",
            moves = "Good sleep, easy days and nights without alcohol lift it. Hard training, short nights, late meals, " +
                "stress and illness hold it down.",
            off = "It needs ${Recovery.SCORED_NIGHTS} good nights to start and settles over ${Recovery.SETTLED_NIGHTS}. A " +
                "night with the strap loose or off, or too few clean HRV readings, gives it less to go on, and the " +
                "strap's own readings may stand in.",
        )
        Score.Charge -> Info(
            "Energy",
            "Your energy right now, from 0 to 100. Sleep fills it and the day uses it.",
            CHARGE_SCALE,
            how = "Think of it as a battery that runs all day. Sleep fills it, deep sleep most of all, and a calm, steady " +
                "night fills it faster. Being awake uses a little every hour. Exercise and stress use it faster, while " +
                "quiet, relaxed moments slow the drain. It's worked out on this phone from your heart rate, minute by " +
                "minute, as a guide to plan your day, not medical advice.",
            moves = "A long, calm night fills it more. Workouts, a stressful day and a late night use it up.",
            off = "Energy starts after your first night. With the strap off overnight there's no sleep to fill it from, " +
                "so the day starts from your usual Energy at waking, an estimate.",
        )
        Score.Sleep -> Info(
            "Sleep score",
            "How well you slept, from 0 to 100, scored by the strap from the night.",
            SLEEP_SCALE,
            how = "The score is the strap's own, from the length and the stages of the night, and it mostly " +
                "follows how long you slept. The stages are light, deep and REM (rapid eye movement), when most " +
                "dreams happen. What shaped the score rates the night against general sleep guidance: time asleep " +
                "against your sleep need, and staying asleep, with under 30 minutes awake after falling asleep " +
                "counting as settled. Too much sleep counts against it too, past 9½ hours. Stages aren't rated, " +
                "since a strap reads them less surely than sleep from waking. It's a guide, not a medical " +
                "assessment.",
            moves = "Regular bedtimes, a dark, cool room and less caffeine and alcohol late in the day help. Late nights, " +
                "a restless night and waking early pull it down.",
            off = "Lying still awake can count as asleep, and light and deep sleep are hard to tell apart through the " +
                "skin. A night with the strap off or loose may be missed or cut short.",
        )
        Score.Regularity -> Info(
            "Sleep regularity",
            "How alike your sleep and wake times are from one day to the next. 100 is the same every day.",
            REGULARITY_SCALE,
            how = "OpenStrap works out the Sleep Regularity Index. It marks each minute of the day asleep or awake and " +
                "counts how often a minute matches the same minute the day before, over the last 7 days. It needs " +
                "${SleepRegularity.MIN_VALID_PAIRS / 60} h of wear compared day to day, about 5 days worn around the " +
                "clock, and until then shows how much the middle of your sleep moved. The bands follow how UK Biobank " +
                "adults scored, where half of them were between 74 and 86. Your usual times are the average " +
                "of the last 7 nights.",
            moves = "Going to bed and getting up at the same times, weekends too, raises it. Late nights, lie-ins and " +
                "long naps lower it.",
            off = "Minutes with the strap off aren't compared, so a week with little wear rests on fewer days. Lying " +
                "awake in bed can count as asleep.",
        )
        Score.Naps -> Info(
            "Naps",
            "Sleep in the day, which the strap records apart from your night.",
            emptyList(),
            how = "The strap tells a nap from your night itself, and records its stages when it's long enough. A nap " +
                "belongs to the day it started on. Its heart rate comes from the day's readings, since the strap " +
                "sends no average for a nap.",
            moves = "A short nap early in the afternoon refreshes without taking much from the night. A long or late " +
                "one can make it harder to fall asleep.",
            off = "Lying still awake can look like a short nap, and a short nap may have no stages.",
        )
        // The Activity tab's (spec 5.3). The method notes that sat on the old Activity panels (Paluch, Mifflin-St Jeor,
        // Jurca, FRIEND, acute:chronic, the strap's weekly EPOC range, training effect) live here now.
        Score.HeartPoints -> Info(
            "Heart points",
            "Points for the minutes your heart rate is raised, harder minutes earning more, over the last 7 days. Aim for ${ActivityCopy.HEART_POINTS_TARGET}.",
            emptyList(),
            how = "Heart points are the strap's PAI, Personal Activity Intelligence. The strap works them out itself from " +
                "how far your heart rate rises above resting, against your age and sex, and OpenStrap shows them as they " +
                "come. Each day's points count for 7 days, then drop off, so the total always covers the last week.",
            moves = "Anything that gets you out of breath adds the most. Brisk walking adds a little. A few hard sessions " +
                "a week keep the total at ${ActivityCopy.HEART_POINTS_TARGET} or more.",
            off = "The total updates when the strap syncs, so it can lag behind today's effort. A loose strap in a workout " +
                "misses raised heart rate.",
        )
        Score.ActiveMinutes -> Info(
            "Active minutes",
            "Minutes of brisk movement or harder over the last 7 days, against the ${Activity.MVPA_WEEKLY_TARGET} a week health guidelines aim for.",
            emptyList(),
            how = "A minute at ${Activity.MODERATE_CADENCE} steps or more counts as moderate, and ${Activity.VIGOROUS_CADENCE} " +
                "or more as vigorous, inside a run of at least ${Activity.CADENCE_RUN_MINUTES} such minutes. Effort " +
                "without steps, like cycling or rowing, counts from heart rate after ${Activity.HR_RUN_MINUTES} minutes in " +
                "a row at ${(Activity.HR_MODERATE_HRR * 100).toInt()}% of your heart rate reserve or more, vigorous from " +
                "${(Activity.HR_VIGOROUS_HRR * 100).toInt()}%. A vigorous minute counts double, as in the World Health Organization's 2020 guidelines.",
            moves = "Brisk walks of a few minutes or more, cycling, running and sport all count. Strolling and short bursts don't.",
            off = "No study has checked a strap's step counts against walking pace, so brisk minutes near the line can be " +
                "missed or counted. Pushing a trolley or holding a rail hides steps.",
        )
        Score.Load -> Info(
            "Load",
            "How hard your heart worked today, from workouts and moving about, against your usual day.",
            ActivityCopy.LOAD_SCALE,
            how = "Each minute counts by how hard your heart works between resting and maximum, Banister's training " +
                "impulse (TRIMP). Every workout minute counts. Outside workouts only moving minutes do, at 35% of your " +
                "heart rate reserve or more, or brisk walking from 20%. Your usual is your fitness, a 42-day average of " +
                "your daily Load. Building, Steady and Easing off compare the last 7 days with it, Steady within a " +
                "quarter either side. Strain puts the day on a fixed curve from 0 to 21, where an hour at 60% of your " +
                "reserve is about 7.",
            moves = "Workouts add the most, longer and harder ones more. A day of walking about adds a little. Rest days " +
                "bring the week's balance down.",
            off = "Days the strap was mostly off are left out of your usual. A low maximum heart rate makes every minute " +
                "count for more. Workouts also carry the strap's EPOC, its measure of what one workout took, on a " +
                "different scale from Load.",
        )
        Score.Vo2Max -> Info(
            "VO2 max",
            "The most oxygen your body can use in hard effort, a measure of fitness. Higher is fitter.",
            emptyList(),
            how = "It's measured in millilitres of oxygen per kilogram of body weight a minute (ml/kg/min). After a " +
                "workout the strap can read it from, the number is the strap's own estimate from your heart rate and " +
                "pace. Otherwise OpenStrap estimates it from your age, sex, body mass index, resting heart rate while " +
                "asleep and how active you say you are (Jurca and others, 2005), updated weekly, and shows it as a " +
                "range. Where you sit for your age and sex comes from treadmill tests of thousands of adults (FRIEND, 2022).",
            moves = "Regular hard effort raises it over weeks. Weight, age and long breaks from training lower it.",
            off = "The estimate from your profile is typically within about 5 either way. A resting heart rate thrown " +
                "by a short night or illness moves it.",
        )
        Score.Recover -> Info(
            "Time to recover",
            "How long your body needs after your last workout before it's ready for another hard one.",
            emptyList(),
            how = "It's the strap's estimate, or OpenStrap's own for a workout recorded on the phone, from how hard the " +
                "workout was: its EPOC, the extra oxygen your body uses afterwards to recover. The same EPOC gives each " +
                "workout its training effect, from 0 to 5, for how much it builds your fitness.",
            moves = "Longer and harder workouts need more time. Easy sessions need little.",
            off = "It knows only the workout, not your sleep, stress or the rest of the day. Recovery here is time, not " +
                "the morning's Recovery score.",
        )
        Score.Energy -> Info(
            "Calories",
            "Active kcal are what moving burned on top of your body's use at rest. The total adds that rest. Both are estimates, in kilocalories (kcal).",
            emptyList(),
            how = "Your body's use at rest comes from your weight, height, age and sex (Mifflin-St Jeor). On top of it " +
                "come your steps each minute and, for effort without steps, your heart rate. A workout recorded in " +
                "OpenStrap counts its own minutes from your heart rate and steps, the same figure its page shows. A " +
                "strap workout uses the strap's kcal. Sleep counts at a little under resting. Active is the total less " +
                "your use at rest for each minute worn, never below zero, so sleep takes nothing away. Your goal is in " +
                "active kcal, as the strap's is.",
            moves = "Moving more raises it, most of all workouts. A heavier body uses more at rest and in motion.",
            off = "Food digestion isn't included. It only covers the minutes the strap was worn, so a day with it off " +
                "for hours reads low. A profile out of date throws the resting part.",
        )
        Score.Distance -> Info(
            "Distance",
            "How far you walked and ran today, estimated from your steps.",
            emptyList(),
            how = "Each minute's steps times a step length from your height: ${(Activity.WALK_STEP_FRACTION * 100).roundToIntString()}% " +
                "of it at a walk, rising to ${(Activity.RUN_STEP_FRACTION * 100).roundToIntString()}% at 130 steps a " +
                "minute and faster. Without a height the strap's own figure stands in.",
            moves = "More steps, and faster ones, which are longer.",
            off = "Step length varies from person to person and with hills, so it can be off by a tenth or more. A " +
                "workout with GPS has its own distance.",
        )
    }

    private fun Float.roundToIntString(): String = if (this % 1f == 0f) toInt().toString() else "%.1f".format(this)

    /** What's particular about today's active minutes: how many came from heart rate. */
    fun activeNotes(fromHeartRate: Int, today: Boolean): List<String> = listOfNotNull(
        "$fromHeartRate of ${if (today) "today's" else "that day's"} minutes came from heart rate.".takeIf { fromHeartRate > 0 },
    )

    /** What's particular about today's Load: the heart rates it's read between, and the contested ratio. */
    fun loadNotes(restingHr: Float, maxHr: Float, maxTested: Boolean, acwr: Float?): List<String> = listOfNotNull(
        "Today it's read between ${restingHr.toInt()} bpm resting and ${maxHr.toInt()} bpm maximum" +
            if (maxTested) ", from your test." else ".",
        acwr?.let {
            "Your last 7 days against the last 4 weeks come to %.2f, the acute to chronic ratio. It's shown for ".format(it) +
                "interest only, since the evidence for a safe ratio is disputed, and never enters Recovery."
        },
    )

    /** What's particular about the VO2 max estimate: the resting heart rate used, fitness age, or why it's withheld. */
    fun vo2Notes(estimate: org.openstrap.app.data.Vo2max.Estimate?, withheld: org.openstrap.app.data.Vo2max.Withheld?): List<String> = buildList {
        estimate?.let { e ->
            add("This week's estimate uses a resting heart rate of ${e.restingHr.toInt()} bpm, while asleep.")
            e.fitnessAge?.let { a ->
                e.sex?.let { add("That's typical of a ${if (it == org.openstrap.app.data.settings.UserProfile.Sex.Male) "man" else "woman"} aged about ${a.first} to ${a.last}.") }
            }
        }
        withheld?.let { add("No estimate from your profile yet. ${it.reason}") }
    }

    /** What's particular about the day's energy: part of the day worn, an unusual total, minutes from heart rate. */
    fun energyNotes(e: org.openstrap.app.data.EnergyExpenditure.Day?): List<String> = buildList {
        e ?: return@buildList
        if (e.partial) add("It covers ${Units.duration(e.minutesWithData * 60L)} of wear, so it's part of the day.")
        if (e.palImplausible) add("That's an unusual total for a full day. Check your profile and how the strap was worn.")
        if (e.hrAssistedMinutes > 0) add("${e.hrAssistedMinutes} minutes came from heart rate rather than steps.")
    }

    /** Spec 6.2's sheet for a night vital opened from the Sleep tab: the metric's, saying where the night's numbers come from. */
    fun ofNight(metric: Metric): Info = of(metric).let { it.copy(how = it.how + " " + nightHow(metric)) }

    private fun nightHow(metric: Metric): String = when (metric) {
        Metric.HeartRate -> "Opened from the night, it shows only your sleep: the line from falling asleep to waking, " +
            "the lowest while asleep and the strap's average for the night. Month and Year are whole days."
        Metric.Spo2 -> "Opened from the night, it shows the strap's readings in sleep, kept apart from the spot readings " +
            "in the day, against your earlier nights."
        Metric.Breathing -> "Opened from the night, it's that night's readings, against your earlier nights."
        else -> "Opened from the night, it's the average of the readings taken while you slept, against your earlier " +
            "nights. Month and Year are whole days, day and night."
    }

    /** The breathing disturbances sheet: an early estimate, with no scale, since its only words would be apnea bands. */
    val BREATHING_DISTURBANCES = Info(
        "Breathing disturbances",
        "Dips in blood oxygen while you sleep, counted by the strap, as how many an hour. An early estimate.",
        emptyList(),
        how = "The strap counts each short drop in blood oxygen during sleep, and that's divided by the hours asleep. " +
            "Your usual is the middle of your earlier nights, once there are ${Recovery.SCORED_NIGHTS} of them. They " +
            "are fairly steady for each person, so they're shown with your sleep rather than in Recovery.",
        moves = "Alcohol, a blocked nose, sleeping on your back and altitude can raise it.",
        off = "The strap's units aren't confirmed on a real strap yet, so treat the number as an early estimate. A " +
            "loose strap can read as a dip. ${org.openstrap.app.data.BodySignalsCopy.DISCLAIMER} It isn't a test for sleep apnea.",
    )

    /** Habit Age's bands, against your age: [habitAgeBar] says which one you're in. */
    val HABIT_AGE_SCALE = listOf("Younger than you", "Your age", "Older than you")

    /** Which bar of [HABIT_AGE_SCALE] an estimate [years] younger than your age is in. */
    fun habitAgeBar(years: Int): Int = when {
        years > 0 -> 0
        years < 0 -> 2
        else -> 1
    }

    /** Habit Age's sheet, behind the (i) on its card (spec 5.4): the method, the pace of aging and what moves it. */
    val HABIT_AGE = Info(
        "Habit Age",
        "How old your habits make your body look, as a range of years, worked out each Sunday.",
        HABIT_AGE_SCALE,
        how = "Each habit is scored against the typical person your age and sex: resting heart rate, steps, sleep " +
            "regularity, time asleep, strength training and fitness (VO2 max, the most oxygen your body can use in " +
            "hard effort). Better than typical takes years off, worse adds them. The total is shrunk, because the " +
            "habits overlap, and capped at 5 years either way. It looks back 6 months, 3 for fitness.",
        moves = "Your habits over months, not a single day. The pace of aging compares your last 30 days with those 6 " +
            "months: faster than 1x means the estimate is heading up, slower means it's coming down. The Habit Age " +
            "page shows what each habit takes off or adds.",
        off = "It updates once a week and is withheld until there's enough data. An estimate from under 6 months of " +
            "data is early, so its range is wider. It's an estimate for personal insight, not a medical measurement, " +
            "shown as a range because each habit's effect is uncertain.",
    )

    /** The week story's sheet: what each day's numbers are and how far back the comparisons reach. */
    val WEEK = Info(
        "Your week",
        "Last week, day by day: how much you moved, trained and slept.",
        emptyList(),
        how = "Steps are each day's total from the strap's minutes, and steps a day the average of the days with any. " +
            "Active minutes are worked out as in their own sheet. A night belongs to the evening it started, so " +
            "the week's last night runs into the next week.",
        moves = "Every walk, workout and early night shows here a week later.",
        off = "Steps and workouts are compared with the week before. Active minutes and sleep only go back two weeks, " +
            "so they're shown without a comparison. A day with the strap off reads low.",
    )

    /** Under the breathing disturbances sheet's title: the night against your usual, or how far the usual has got. */
    fun breathingLine(b: Recovery.BreathingDisturbances, today: Boolean): String {
        val night = "${if (today) "Last night" else "That night"} %.1f an hour.".format(b.perHour)
        return when (val usual = b.usual) {
            null -> "$night Learning your usual, ${b.nights.coerceAtMost(Recovery.SCORED_NIGHTS)} of ${Recovery.SCORED_NIGHTS} nights."
            else -> "$night Your usual is %.1f.".format(usual)
        }
    }

    /** What's particular about this week's regularity: how much wear it compares, or what it still needs. */
    fun regularityNotes(reg: SleepRegularity.Result?): List<String> = buildList {
        reg ?: return@buildList
        val hours = reg.validPairs / 60
        if (reg.sri != null) add("This week compares $hours h of wear.")
        else {
            add("Not enough wear for the full measure yet: $hours h of the ${SleepRegularity.MIN_VALID_PAIRS / 60} h it needs.")
            reg.midpointSdMinutes?.let {
                add("Meanwhile the middle of your sleep moved about ${Units.duration(Math.round(it) * 60L)} this week.")
            }
        }
    }

    /** What's particular about the night's Sleep score: the sleep need the guidance measures against. */
    fun sleepNotes(needMinutes: Int): List<String> =
        listOf("Your sleep need here is ${Units.duration(needMinutes * 60L)}, from your age or your own setting.")

    /**
     * What's particular about today's Recovery, for its info sheet: the strap's own score beside ours, and anything that
     * made tonight's reading less sure. Each is a sentence; none when there's nothing to say.
     */
    fun recoveryNotes(ready: Recovery.Readiness?, strapScore: Int?): List<String> = buildList {
        if (strapScore != null) add(
            "The strap's own score today is $strapScore. It doesn't count sleep length, so after a short night it reads " +
                "higher than OpenStrap's. It's kept for comparison and never enters OpenStrap's score.",
        )
        ready ?: return@buildList
        if (ready.capped) add("Today one body signal is far from your normal, so the score is capped.")
        ready.fallbackHrv?.let { add("Last night's own HRV wasn't clean enough, so the strap's ${it.toInt()} ms stands in.") }
        ready.fallbackRestingHr?.let { add("Last night's own resting heart rate wasn't clean enough, so the strap's ${it.toInt()} bpm stands in.") }
        if (ready.hrvWeekThin) add("The week's HRV rests on ${ready.hrvWeekNights} of 7 nights, so it's less sure than usual.")
    }

    /** What's particular about today's Charge: an estimated start after a night with the strap off. */
    fun chargeNotes(c: Recovery.ChargeDay?): List<String> = listOfNotNull(
        "The strap was off overnight, so today started from an estimate.".takeIf { c?.strapOffOvernight == true },
    )
}
