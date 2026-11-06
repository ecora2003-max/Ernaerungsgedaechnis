package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class NutritionGoalTest {
    private val range = NutritionGoalRange(Nutrient.PROTEIN, "9,0000", "11.000000000000000001")
    private fun known(value: String, missing: Int = 0) = NutrientResult(BigDecimal(value), 1, missing)
    @Test fun exactPersonalRangesKeepNullZeroOriginalDecimalsAndInclusiveBoundsWithoutDefaultTargets() {
        range.validate()
        assertEquals(BigDecimal("9.0000"), range.minimum)
        assertEquals(BigDecimal("11.000000000000000001"), range.maximum)
        val zero = NutritionGoalRange(Nutrient.PROTEIN, null, "0")
        assertNull(zero.minimum); assertEquals(BigDecimal.ZERO, zero.maximum)
        assertEquals(NutritionGoalRules.Result.WITHIN, NutritionGoalRules.assess(zero, known("0"), DayStatus.COMPLETE))
        assertEquals(NutritionGoalRules.Result.WITHIN, NutritionGoalRules.assess(range, known("9"), DayStatus.COMPLETE))
        assertEquals(NutritionGoalRules.Result.WITHIN, NutritionGoalRules.assess(range, known("11.000000000000000001"), DayStatus.COMPLETE))
        assertEquals(NutritionGoalRules.Result.ABOVE, NutritionGoalRules.assess(range, known("11.000000000000000002"), DayStatus.COMPLETE))
        assertEquals(NutritionGoalRules.Result.BELOW, NutritionGoalRules.assess(range, known("8.999999999999999999"), DayStatus.COMPLETE))
    }
    @Test fun openDiaryOrNutrientGapsNeverBecomeACompleteShortfallOrWithinRangeAssessment() {
        assertEquals(NutritionGoalRules.Result.INCOMPLETE, NutritionGoalRules.assess(range, known("10"), DayStatus.OPEN))
        assertEquals(NutritionGoalRules.Result.INCOMPLETE, NutritionGoalRules.assess(range, known("1"), DayStatus.PARTIAL))
        assertEquals(NutritionGoalRules.Result.INCOMPLETE, NutritionGoalRules.assess(range, known("1", 1), DayStatus.COMPLETE))
        assertEquals(NutritionGoalRules.Result.KNOWN_PART_ABOVE, NutritionGoalRules.assess(range, known("12", 1), DayStatus.PARTIAL))
        assertEquals(NutritionGoalRules.Result.UNKNOWN, NutritionGoalRules.assess(range, NutrientResult(null, 0, 1), DayStatus.COMPLETE))
        assertEquals(NutritionGoalRules.Result.UNKNOWN, NutritionGoalRules.assess(range, NutrientResult(BigDecimal.ZERO, 0, 0), DayStatus.COMPLETE))
    }
    @Test fun invalidOrDuplicateRangesCannotAcquireAMeaningFromRoundingOrMissingValues() {
        val invalid = listOf(NutritionGoalRange(Nutrient.PROTEIN, null, null),
            NutritionGoalRange(Nutrient.PROTEIN, "12", "11"), NutritionGoalRange(Nutrient.ENERGY, "-1", null),
            NutritionGoalRange(Nutrient.ENERGY, "1e4", null), NutritionGoalRange(Nutrient.ENERGY, "NaN", null))
        invalid.forEach { try { it.validate(); fail() } catch (_: IllegalArgumentException) { } }
        try { NutritionGoalRules.validate(listOf(range, range)); fail() } catch (_: IllegalArgumentException) { }
    }
}
