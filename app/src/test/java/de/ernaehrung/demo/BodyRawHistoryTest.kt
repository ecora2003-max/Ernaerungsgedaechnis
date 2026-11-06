package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BodyRawHistoryTest {
    private val day = LocalDate.parse("2026-10-29")
    private fun point(id: String, date: String, value: String? = "73,0400") =
        BodyRawObservation(id, BodyMeasurementInput(BodyMetric.WEIGHT, value, date))

    @Test fun everyRequiredWindowUsesInclusiveCalendarDaysRatherThanElapsedHours() {
        BodyHistoryPeriod.entries.filter { it.days != null }.forEach { period ->
            val start = day.minusDays(period.days!!.toLong() - 1L)
            val result = BodyRawHistory.select(listOf(point("before", start.minusDays(1).toString()),
                point("first", start.toString()), point("last", day.toString()),
                point("after", day.plusDays(1).toString())), period, day)
            assertEquals(start, result.start); assertEquals(day, result.end)
            assertEquals(listOf("last", "first"), result.series.single().points.map { it.id })
            assertEquals(period.days!! - 2, result.series.single().daysWithoutKnownValue!!.size)
            assertEquals(2, result.outsideWindowCount)
        }
    }

    @Test fun originalDatesSurviveOffsetsDstOverlapAndDateOnlyMeasurements() {
        val eastern = point("east", "2026-10-23").copy(input = point("", "2026-10-23").input.copy(
            localTime = "00:30", zoneId = "Pacific/Kiritimati"))
        val summer = point("summer", "2026-10-25").copy(input = point("", "2026-10-25").input.copy(
            localTime = "02:30", zoneId = "Europe/Berlin", explicitOffset = "+02:00"))
        val winter = summer.copy(id = "winter", input = summer.input.copy(explicitOffset = "+01:00"))
        val dateOnly = point("date-only", "2026-10-29")
        val utc = BodyMeasurementRules.normalize(eastern.input, Instant.parse("2026-11-01T00:00:00Z")).utc
        assertEquals("2026-10-22T10:30:00Z", utc) // UTC is outside; original local date is inside.
        val selected = BodyRawHistory.select(listOf(eastern, summer, winter, dateOnly), BodyHistoryPeriod.DAYS_7, day)
        assertEquals(setOf(eastern, summer, winter, dateOnly), selected.series.single().points.toSet())
        assertEquals(listOf("2026-10-24", "2026-10-26", "2026-10-27", "2026-10-28"),
            selected.series.single().daysWithoutKnownValue!!.map { it.toString() })
        assertNull(selected.series.single().points.single { it.id == "date-only" }.input.localTime)
    }

    @Test fun zeroUnknownExcludedOutliersAndChangedMeasurementConditionsRemainRaw() {
        val zero = point("zero", day.toString(), "0,000")
        val unknown = point("unknown", day.minusDays(1).toString(), null)
        val excluded = point("excluded", day.minusDays(2).toString(), "9999,9999").copy(input =
            point("", day.minusDays(2).toString(), "9999,9999").input.copy(device = "Andere Testwaage",
                model = "Synthetisch", method = BodyMethod.BIA, quality = BodyQuality.DEVICE_ESTIMATE,
                methodDetail = "Waagenwert", conditions = "Abends nach Sport", excludedFromTrend = true))
        val result = BodyRawHistory.select(listOf(unknown, excluded, zero), BodyHistoryPeriod.DAYS_7, day).series.single()
        assertEquals(listOf(zero, unknown, excluded), result.points)
        assertFalse(day in result.daysWithoutKnownValue!!)
        assertTrue(day.minusDays(1) in result.daysWithoutKnownValue!!)
        assertFalse(day.minusDays(2) in result.daysWithoutKnownValue!!)
        assertEquals(5, result.daysWithoutKnownValue!!.size)
    }

    @Test fun differentUnitsAndCustomDefinitionsNeverBecomeOneMeasureAndEmptyWindowsKeepGaps() {
        val points = listOf(point("weight", "2026-01-01"),
            point("muscle-kg", "2026-10-25").copy(input = BodyMeasurementInput(BodyMetric.MUSCLE_MASS, "32", "2026-10-25", methodDetail = "Skelettmuskelmasse")),
            point("muscle-percent", "2026-10-25").copy(input = BodyMeasurementInput(BodyMetric.MUSCLE_PERCENT, "40", "2026-10-25", methodDetail = "Skelettmuskelanteil")),
            point("left", "2026-10-25").copy(input = BodyMeasurementInput(BodyMetric.OTHER, "30", "2026-10-25", name = "Arm links", customUnit = "cm")),
            point("right", "2026-10-25").copy(input = BodyMeasurementInput(BodyMetric.OTHER, "31", "2026-10-25", name = "Arm rechts", customUnit = "cm")),
            point("mm", "2026-10-25").copy(input = BodyMeasurementInput(BodyMetric.OTHER, "300", "2026-10-25", name = "Arm links", customUnit = "mm")))
        val window = BodyRawHistory.select(points, BodyHistoryPeriod.DAYS_7, day)
        assertEquals(6, window.series.size)
        val weight = window.series.single { it.measure.metric == BodyMetric.WEIGHT }
        assertTrue(weight.points.isEmpty()); assertEquals(7, weight.daysWithoutKnownValue!!.size)
        assertEquals(1, window.outsideWindowCount)
        val all = BodyRawHistory.select(points.reversed(), BodyHistoryPeriod.ALL, day)
        assertEquals(6, all.series.sumOf { it.points.size }); assertEquals(0, all.outsideWindowCount)
        assertNull(all.start); assertNull(all.end); assertTrue(all.series.all { it.daysWithoutKnownValue == null })
    }
}
