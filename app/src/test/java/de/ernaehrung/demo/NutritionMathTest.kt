package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class NutritionMathTest {
    private fun product(unit: BaseUnit = BaseUnit.GRAM, basis: Long = 100_000, energy: Double? = 125.0) = ProductVersionEntity(
        "v1", "p1", 1, "Test", null, null, null, unit, null, basis, energy, 13.0, 7.0, 2.0,
        source = Source.USER, sourceDetail = null, quality = Quality.UNKNOWN, recordedAt = 1, changeReason = "Test")
    private fun equal(expected: String, value: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(value)))
    @Test fun exactScalingUsesDeclaredBasisAndDoesNotInventFibre() {
        val values = NutritionResult.product(product(), 50_000, BaseUnit.GRAM)
        equal("62.5", values[Nutrient.ENERGY].knownAmount)
        equal("6.5", values[Nutrient.PROTEIN].knownAmount)
        assertTrue(values[Nutrient.ENERGY].complete)
        assertNull(values[Nutrient.FIBRE].knownAmount); assertEquals(1, values[Nutrient.FIBRE].missingCount)
    }
    @Test fun volumeAndPortionsStayDistinctAndUnknownConversionsAreNotGuessed() {
        equal("250", NutritionResult.product(product(BaseUnit.MILLILITRE), 200_000, BaseUnit.MILLILITRE)[Nutrient.ENERGY].knownAmount)
        equal("62.5", NutritionResult.product(product(BaseUnit.PORTION, 1000), 500, BaseUnit.PORTION)[Nutrient.ENERGY].knownAmount)
        val wrong = NutritionResult.product(product(BaseUnit.MILLILITRE), 200_000, BaseUnit.GRAM)
        assertNull(wrong[Nutrient.ENERGY].knownAmount); assertTrue(NutritionGap.UNIT_MISMATCH in wrong[Nutrient.ENERGY].gaps)
    }
    @Test fun knownZeroUnknownAndPartialSumsRemainDifferent() {
        val zero = NutritionResult.product(product(energy = 0.0), 50_000, BaseUnit.GRAM)
        val unknown = NutritionResult.product(product(energy = null), 50_000, BaseUnit.GRAM)
        equal("0", zero[Nutrient.ENERGY].knownAmount); assertTrue(zero[Nutrient.ENERGY].complete)
        assertNull(unknown[Nutrient.ENERGY].knownAmount)
        val partial = NutritionResult.sum(listOf(zero, unknown))[Nutrient.ENERGY]
        equal("0", partial.knownAmount); assertFalse(partial.complete)
        assertEquals(1, partial.knownCount); assertEquals(1, partial.missingCount)
        assertNull(NutritionResult.sum(emptyList())[Nutrient.ENERGY].knownAmount)
    }
    @Test fun missingQuantityProducesNoCalorieGuessAndRetainsQualityMetadata() {
        val unknown = NutritionResult.product(product(), null, BaseUnit.GRAM)
        assertNull(unknown[Nutrient.ENERGY].knownAmount)
        assertTrue(NutritionGap.UNKNOWN_AMOUNT in unknown[Nutrient.ENERGY].gaps)
        assertEquals(setOf(Quality.UNKNOWN), NutritionResult.product(product(), 1000, BaseUnit.GRAM)[Nutrient.ENERGY].qualities)
    }
}
