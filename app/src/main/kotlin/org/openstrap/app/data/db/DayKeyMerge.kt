package org.openstrap.app.data.db

/**
 * Which row a day keeps when two old keys turn out to be one date ([DayKeyMigration], and a restore of an old backup):
 * rows written in two zones for the same date, after travel. One set of rules for both, per table.
 */
object DayKeyMerge {
    /** How a table picks its row for a date. */
    enum class Rule {
        /** The row whose old key is nearest the date's local midnight now, what a sync now writes. */
        NEAREST_MIDNIGHT,

        /** The highest [Table.value], as the writer already merges the day's values with `maxOf`. */
        HIGHEST,

        /** The newest [Table.value] (`updatedAt`), what was worked out or answered last. */
        NEWEST,

        /** Every tag either row had: one row per date and tag. */
        UNION,
    }

    /** A day-keyed table: its name, its day column, its rule and the column the rule compares, if any. */
    class Table(val name: String, val column: String, val rule: Rule, val value: String? = null)

    /** The tables whose day column is a primary key, or part of one (day_tag). */
    val TABLES = listOf(
        Table("resting_hr", "day", Rule.NEAREST_MIDNIGHT),
        Table("daily_max_hr", "day", Rule.HIGHEST, "bpm"),
        Table("strap_readiness", "day", Rule.NEWEST, "updatedAt"),
        Table("check_in", "day", Rule.NEWEST, "updatedAt"),
        Table("day_tag", "day", Rule.UNION),
        Table("night_metrics", "day", Rule.NEWEST, "updatedAt"),
        Table("habit_age_week", "asOf", Rule.NEWEST, "updatedAt"),
        Table("daily_energy", "day", Rule.NEWEST, "updatedAt"),
    )

    /** One row for a date: its old key, and its [Table.value] (0 for a rule that compares none). */
    data class Candidate(val old: Long, val value: Long)

    /**
     * The old key to keep of [candidates], all for one date whose local midnight is now [midnight]. Ties go to the
     * larger old key, so the pick never depends on the order rows were read in. Not for [Rule.UNION], which keeps
     * one row per tag ([unionKeep]).
     */
    fun winner(rule: Rule, candidates: List<Candidate>, midnight: Long): Long {
        require(candidates.isNotEmpty())
        val order = when (rule) {
            Rule.NEAREST_MIDNIGHT -> compareBy<Candidate> { -Math.abs(it.old - midnight) }
            Rule.HIGHEST, Rule.NEWEST -> compareBy { it.value }
            Rule.UNION -> error("a union keeps a row per tag")
        }
        return candidates.maxWith(order.thenBy { it.old }).old
    }

    /** For a [Rule.UNION] date: of the old keys that hold one tag, the one to keep. */
    fun unionKeep(olds: List<Long>): Long = olds.max()

    /** The value [winner] compares for a restored row, as the database column [Table.value] holds it. */
    fun valueOf(row: Any?): Long = when (row) {
        is DailyMaxHr -> row.bpm.toLong()
        is StrapReadinessRow -> row.updatedAt
        is CheckInRow -> row.updatedAt
        is NightMetricsRow -> row.updatedAt
        is HabitAgeWeekRow -> row.updatedAt
        is DailyEnergyRow -> row.updatedAt
        else -> 0
    }
}
