package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BodyMeasurementRulesTest {
    private val now = Instant.parse("2026-11-05T14:00:00Z")
    private fun input(value: String? = "73,0400") = BodyMeasurementInput(BodyMetric.WEIGHT, value, "2026-10-25")
    private fun rejected(input: BodyMeasurementInput) {
        try { BodyMeasurementRules.normalize(input, now); fail("Expected invalid body measurement") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun exactDecimalsAndMissingValuesStayDistinctWithoutInventingAnInstant() {
        val i = input(" 073,0400 ")
        val n = BodyMeasurementRules.normalize(i, now)
        assertEquals(i, n.input); assertEquals("73.04", n.value)
        assertNull(n.utc); assertNull(n.offsetSeconds); assertEquals("DATE_ONLY", n.timePrecision)
        assertNull(BodyMeasurementRules.normalize(input(null), now).value)
        assertEquals("0", BodyMeasurementRules.normalize(input("0,000"), now).value)
        assertEquals("0.0000000000000000001", BodyMeasurementRules.normalize(input("0,0000000000000000001"), now).value)
        listOf("", " ", "NaN", "Infinity", "-1", "1e2", "1.2.3").forEach { rejected(input(it)) }
    }
    @Test fun dstGapOverlapAndOffsetMustBeExplicitAndConsistent() {
        val overlap = input().copy(localTime = "02:30", zoneId = "Europe/Berlin")
        rejected(overlap)
        val summer = BodyMeasurementRules.normalize(overlap.copy(explicitOffset = "+02:00"), now)
        val winter = BodyMeasurementRules.normalize(overlap.copy(explicitOffset = "+01:00"), now)
        assertEquals("2026-10-25T00:30:00Z", summer.utc); assertEquals("2026-10-25T01:30:00Z", winter.utc)
        assertEquals("MINUTE", summer.timePrecision)
        rejected(overlap.copy(explicitOffset = "+03:00"))
        rejected(overlap.copy(localDate = "2026-03-29"))
        rejected(input().copy(explicitOffset = "+01:00"))
        rejected(input().copy(zoneId = "Unknown/Zone"))
    }
    @Test fun offsetOnlyUnknownZoneAndSubsecondPrecisionAreRetained() {
        val n = BodyMeasurementRules.normalize(input().copy(localTime = "12:34:56.123456789", explicitOffset = "-03:30"), now)
        assertEquals("2026-10-25T16:04:56.123456789Z", n.utc)
        assertEquals(-12_600, n.offsetSeconds); assertEquals("FRACTION_9", n.timePrecision); assertNull(n.input.zoneId)
        val unzoned = BodyMeasurementRules.normalize(input().copy(localTime = "12:34"), now)
        assertNull(unzoned.utc); assertNull(unzoned.offsetSeconds)
        rejected(input().copy(localDate = "2026-11-06", zoneId = "Europe/Berlin"))
        rejected(input().copy(localDate = "2026-11-05", localTime = "23:00", explicitOffset = "Z"))
    }
    @Test fun percentagesMuscleDefinitionAndBiaEstimationCannotBeSilentlyReinterpreted() {
        rejected(input("100.01").copy(metric = BodyMetric.BODY_FAT))
        rejected(input().copy(metric = BodyMetric.MUSCLE_MASS))
        val n = BodyMeasurementRules.normalize(input().copy(metric = BodyMetric.MUSCLE_PERCENT,
            method = BodyMethod.BIA, methodDetail = "Skelettmuskelanteil laut Testwaage", quality = BodyQuality.MEASURED), now)
        assertEquals("%", n.unit); assertEquals(BodyQuality.DEVICE_ESTIMATE, n.quality)
        assertEquals(BodyQuality.MEASURED, n.input.quality) // Original claim remains distinct.
        rejected(input().copy(metric = BodyMetric.OTHER))
        val other = BodyMeasurementRules.normalize(input("20.50").copy(metric = BodyMetric.OTHER,
            name = "Oberarmumfang links", customUnit = "cm", method = BodyMethod.TAPE), now)
        assertEquals("20.5", other.value); assertEquals("cm", other.unit)
    }
}
