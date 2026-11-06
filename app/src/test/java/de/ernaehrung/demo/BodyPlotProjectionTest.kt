package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BodyPlotProjectionTest {
    @Test fun originalLocalDatesDoNotShiftDuringDstAndEveryKnownZeroExtremeAndExcludedPointKeepsItsIdentity() {
        val huge = "99999999999999999999999999999999999999999999999999999999999999999999999999999"
        fun value(id: String, raw: String?, date: String, time: String? = null, offset: String? = null,
            method: BodyMethod = BodyMethod.SCALE, excluded: Boolean = false) = BodyRawObservation(id,
            BodyMeasurementInput(BodyMetric.WEIGHT, raw, date, localTime = time,
                zoneId = if (time != null) "Europe/Berlin" else null, explicitOffset = offset,
                method = method, device = if (method == BodyMethod.BIA) "BIA-Gerät" else "Waage",
                conditions = if (method == BodyMethod.BIA) "Abends" else "Morgens", excludedFromTrend = excluded))
        val rows = listOf(value("zero", "0", "2026-10-24"),
            value("early", "73,0400", "2026-10-25", "02:30", "+02:00"),
            value("late", "73,0500", "2026-10-25", "02:30", "+01:00", BodyMethod.BIA),
            value("extreme-excluded", huge, "2026-10-26", excluded = true),
            value("unknown", null, "2026-10-27"))
        val window = BodyRawHistory.select(rows, BodyHistoryPeriod.DAYS_7, LocalDate.parse("2026-10-30"))
        val plot = BodyPlotProjection.project(window.series.single(), window.start, window.end)
        assertEquals(setOf("zero", "early", "late", "extreme-excluded"), plot.points.map { it.id }.toSet())
        assertEquals(1, plot.unknownCount); assertEquals(0, plot.unplottableCount)
        assertEquals(BigDecimal.ZERO, plot.minimum); assertEquals(BigDecimal(huge), plot.maximum)
        assertEquals(0f, plot.points.single { it.id == "zero" }.yFromBottom, 0f)
        assertEquals(1f, plot.points.single { it.id == "extreme-excluded" }.yFromBottom, 0f)
        assertTrue(plot.points.single { it.id == "extreme-excluded" }.excluded)
        val early = plot.points.single { it.id == "early" }; val late = plot.points.single { it.id == "late" }
        assertEquals(early.date, late.date); assertEquals(early.x, late.x, 0f)
        assertEquals("73,0400", early.rawValue); assertNotEquals(early.sourceIndex, late.sourceIndex)
        assertTrue(plot.points.all { it.x.isFinite() && it.yFromBottom.isFinite() && it.x in 0f..1f && it.yFromBottom in 0f..1f })
    }

    @Test fun constantSameDayZeroPointsStayKnownAndDoNotInventAnAxisSpanOrTimestamp() {
        val input = BodyMeasurementInput(BodyMetric.WEIGHT, "0,000", "2026-10-25")
        val rows = listOf(BodyRawObservation("a", input), BodyRawObservation("b", input.copy(excludedFromTrend = true)))
        val window = BodyRawHistory.select(rows, BodyHistoryPeriod.ALL, LocalDate.parse("2026-10-30"))
        val plot = BodyPlotProjection.project(window.series.single(), window.start, window.end)
        assertEquals(LocalDate.parse("2026-10-25"), plot.first); assertEquals(plot.first, plot.last)
        assertEquals(0, requireNotNull(plot.minimum).compareTo(BigDecimal.ZERO)); assertEquals(plot.minimum, plot.maximum)
        assertEquals(2, plot.points.size); assertEquals(0, plot.unknownCount)
        assertTrue(plot.points.all { it.x == 0.5f && it.yFromBottom == 0.5f && it.rawValue == "0,000" })
    }

    @Test fun unknownAndUnpositionableOriginalTextsProduceNoZeroCurveOrManufacturedPoint() {
        val rows = listOf(BodyRawObservation("unknown", BodyMeasurementInput(BodyMetric.WAIST, null, "2026-10-25")),
            BodyRawObservation("original", BodyMeasurementInput(BodyMetric.WAIST, "früherer Originaltext", "2026-10-26")))
        val window = BodyRawHistory.select(rows, BodyHistoryPeriod.DAYS_7, LocalDate.parse("2026-10-30"))
        val plot = BodyPlotProjection.project(window.series.single(), window.start, window.end)
        assertTrue(plot.points.isEmpty()); assertEquals(1, plot.unknownCount); assertEquals(1, plot.unplottableCount)
        assertNull(plot.minimum); assertNull(plot.maximum)
        assertEquals(rows.map { it.id }.toSet(), window.series.single().points.map { it.id }.toSet())
    }
}
