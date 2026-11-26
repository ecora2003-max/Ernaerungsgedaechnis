package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ImportedBodyFatHistoryTest {
    @Test fun originalOffsetControlsTheWindowAndUnlocatedOrHiddenValuesAreNotPlotted() {
        val day = LocalDate.parse("2026-10-25")
        val start = Instant.parse("2026-10-24T22:15:00Z").toEpochMilli()
        fun row(id: String, seconds: Int?, value: Double?, visible: Boolean = true) =
            HealthExtraStored(HealthExtraRecord("test.synthetic.scale", HealthExtraKind.BODY_FAT,
                id, start, null, seconds, null, start, null, 1, "Testwaage", null, null, 0,
                value?.toRawBits()), 1, visible, "synthetic", id)
        val copy = listOf(row("known-zero", 7200, -0.0), row("unknown-value", 7200, null),
            row("no-original-day", null, 12.125), row("hidden", 7200, 23.0, visible = false))

        val window = selectImportedBodyFat(copy, BodyHistoryPeriod.DAYS_7, day)
        assertEquals(listOf("known-zero", "unknown-value"), window.samples.map { it.original.record.externalId })
        assertEquals(setOf(day.toString()), window.series.points.map { it.input.localDate }.toSet())
        assertEquals("-0.0", window.series.points.first().input.rawValue)
        assertNull(window.series.points.last().input.rawValue)
        assertEquals("no-original-day", window.missingSourceDay.single().original.record.externalId)
        assertNull(window.missingSourceDay.single().sourceTime)
        val plot = BodyPlotProjection.project(window.series, window.start, window.end)
        assertEquals(1, plot.points.size); assertEquals(1, plot.unknownCount)
        assertEquals(BodyMethod.UNKNOWN, plot.sources.single().method)
        assertEquals("test.synthetic.scale", plot.sources.single().methodDetail)
        assertTrue(selectImportedBodyFat(copy, BodyHistoryPeriod.DAYS_7, day.minusDays(1)).samples.isEmpty())
    }
}
