package de.ernaehrung.demo.data.local

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** UI and ViewModel calendar defaults must use the same Berlin day as Room's planning, shopping and actual-event gates. */
internal object AppCalendar {
    private val berlin = ZoneId.of("Europe/Berlin")

    fun today(at: Instant = Instant.now()): LocalDate = at.atZone(berlin).toLocalDate()

    fun startOfDayMillis(day: LocalDate): Long = day.atStartOfDay(berlin).toInstant().toEpochMilli()

    /** Last representable millisecond of the local day, including 23/25-hour DST days. */
    fun endOfDayMillis(day: LocalDate): Long = startOfDayMillis(day.plusDays(1)) - 1L
}
