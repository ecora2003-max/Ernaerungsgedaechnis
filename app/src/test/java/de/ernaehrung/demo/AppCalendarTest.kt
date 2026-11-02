package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.AppCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppCalendarTest {
    @Test fun berlinMidnightIsStableAcrossDeviceTimeZones() {
        val before = Instant.parse("2026-11-01T22:59:59Z")
        val after = Instant.parse("2026-11-01T23:00:00Z")
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Honolulu", "Asia/Tokyo", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(LocalDate.parse("2026-11-01"), AppCalendar.today(before))
                assertEquals(LocalDate.parse("2026-11-02"), AppCalendar.today(after))
            }
            assertNotEquals(after.atZone(ZoneId.of("Pacific/Honolulu")).toLocalDate(),
                AppCalendar.today(after))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun berlinEndOfDayCutoffUsesLocalMidnightEvenAcrossDst() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "America/Los_Angeles", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(Instant.parse("2026-03-29T21:59:59.999Z").toEpochMilli(),
                    AppCalendar.endOfDayMillis(LocalDate.parse("2026-03-29")))
                assertEquals(Instant.parse("2026-10-25T22:59:59.999Z").toEpochMilli(),
                    AppCalendar.endOfDayMillis(LocalDate.parse("2026-10-25")))
                assertEquals(Instant.parse("2026-10-30T22:59:59.999Z").toEpochMilli(),
                    AppCalendar.endOfDayMillis(LocalDate.parse("2026-10-30")))
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun berlinDayFollowsDstTransitionsWithoutSkippedOrRepeatedDates() {
        assertEquals(LocalDate.parse("2026-03-29"), AppCalendar.today(Instant.parse("2026-03-29T00:59:59Z")))
        assertEquals(LocalDate.parse("2026-03-29"), AppCalendar.today(Instant.parse("2026-03-29T01:00:00Z")))
        assertEquals(LocalDate.parse("2026-03-30"), AppCalendar.today(Instant.parse("2026-03-29T22:00:00Z")))
        assertEquals(LocalDate.parse("2026-10-25"), AppCalendar.today(Instant.parse("2026-10-25T00:59:59Z")))
        assertEquals(LocalDate.parse("2026-10-25"), AppCalendar.today(Instant.parse("2026-10-25T01:00:00Z")))
        assertEquals(LocalDate.parse("2026-10-26"), AppCalendar.today(Instant.parse("2026-10-25T23:00:00Z")))
    }
}
