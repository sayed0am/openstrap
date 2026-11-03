package org.openstrap.app.data.db

import androidx.room.TypeConverter
import org.openstrap.app.data.DayKey
import java.time.LocalDate

/**
 * Stores a day ([LocalDate]) as its [DayKey], its date's UTC midnight in epoch seconds, so a day keeps its date
 * whatever zone the phone is in. The day tables' columns stay `INTEGER NOT NULL`. Nothing else in the database is a
 * [LocalDate], so moments never go through this.
 */
object DayKeyConverter {
    @TypeConverter fun toKey(date: LocalDate): Long = DayKey.of(date)

    @TypeConverter fun toDate(key: Long): LocalDate = DayKey.date(key)
}
