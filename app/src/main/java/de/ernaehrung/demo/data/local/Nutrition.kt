package de.ernaehrung.demo.data.local

import java.math.BigDecimal
import java.math.MathContext

enum class Nutrient(val label: String, val unit: String) {
    ENERGY("Energie", "kcal"), PROTEIN("Protein", "g"), FAT("Fett", "g"), CARBS("Kohlenhydrate", "g"),
    FIBRE("Ballaststoffe", "g"), SUGAR("Zucker", "g"), SATURATED("Gesättigte Fettsäuren", "g"), SALT("Salz", "g"),
    CALCIUM("Calcium", "mg"), IRON("Eisen", "mg"), MAGNESIUM("Magnesium", "mg"), IODINE("Jod", "µg"),
    B12("Vitamin B12", "µg"), VITAMIN_D("Vitamin D", "µg")
}
enum class NutritionGap { UNKNOWN_AMOUNT, MISSING_VALUE, MISSING_VERSION, UNIT_MISMATCH, INVALID_PRODUCTION, REVERSED_PRODUCTION, CYCLE }

/** knownAmount is a known subtotal, never a zero substituted for missing data. Counts are contributions, not percentages. */
data class NutrientResult(val knownAmount: BigDecimal?, val knownCount: Int, val missingCount: Int,
    val gaps: Set<NutritionGap> = emptySet(), val qualities: Set<Quality> = emptySet()) {
    val complete get() = missingCount == 0 && knownCount > 0
}
data class NutritionResult(val values: Map<Nutrient, NutrientResult>) {
    operator fun get(nutrient: Nutrient): NutrientResult = values.getValue(nutrient)
    val hasGaps get() = values.values.any { !it.complete }
    fun scale(numerator: Long, denominator: Long): NutritionResult {
        if (numerator <= 0 || denominator <= 0) return unknown(NutritionGap.UNKNOWN_AMOUNT)
        return NutritionResult(values.mapValues { (_, value) -> value.copy(knownAmount = value.knownAmount
            ?.multiply(BigDecimal.valueOf(numerator), PRECISION)?.divide(BigDecimal.valueOf(denominator), PRECISION)) })
    }
    companion object {
        val PRECISION: MathContext = MathContext.DECIMAL128
        const val CALCULATION_VERSION = "nutrition-1"
        fun unknown(gap: NutritionGap) = NutritionResult(Nutrient.entries.associateWith { NutrientResult(null, 0, 1, setOf(gap)) })
        fun sum(parts: List<NutritionResult>) = NutritionResult(Nutrient.entries.associateWith { nutrient ->
            val items = parts.map { it[nutrient] }
            val known = items.mapNotNull { it.knownAmount }
            NutrientResult(if (known.isEmpty()) null else known.fold(BigDecimal.ZERO) { a, b -> a.add(b, PRECISION) },
                items.sumOf { it.knownCount }, items.sumOf { it.missingCount }, items.flatMap { it.gaps }.toSet(), items.flatMap { it.qualities }.toSet())
        })
        fun product(version: ProductVersionEntity?, amount: Long?, unit: BaseUnit): NutritionResult {
            if (version == null) return unknown(NutritionGap.MISSING_VERSION)
            if (version.baseUnit != unit) return unknown(NutritionGap.UNIT_MISMATCH)
            if (amount == null || amount <= 0 || version.nutritionBasis <= 0) return unknown(NutritionGap.UNKNOWN_AMOUNT)
            return NutritionResult(Nutrient.entries.associateWith { nutrient ->
                val number = version.nutrient(nutrient)
                if (number == null || !number.isFinite() || number < 0) NutrientResult(null, 0, 1, setOf(NutritionGap.MISSING_VALUE), setOf(version.quality))
                else NutrientResult(BigDecimal.valueOf(number).multiply(BigDecimal.valueOf(amount), PRECISION)
                    .divide(BigDecimal.valueOf(version.nutritionBasis), PRECISION), 1, 0, qualities = setOf(version.quality))
            })
        }
    }
}

fun ProductVersionEntity.nutrient(value: Nutrient): Double? = when (value) {
    Nutrient.ENERGY -> energyKcal; Nutrient.PROTEIN -> proteinGrams; Nutrient.FAT -> fatGrams; Nutrient.CARBS -> carbsGrams
    Nutrient.FIBRE -> fiberGrams; Nutrient.SUGAR -> sugarGrams; Nutrient.SATURATED -> saturatedFatGrams; Nutrient.SALT -> saltGrams
    Nutrient.CALCIUM -> calciumMg; Nutrient.IRON -> ironMg; Nutrient.MAGNESIUM -> magnesiumMg; Nutrient.IODINE -> iodineMicrograms
    Nutrient.B12 -> vitaminB12Micrograms; Nutrient.VITAMIN_D -> vitaminDMicrograms
}

data class MealNutrition(val event: ConsumptionEntity, val productName: String, val unit: BaseUnit,
    val revision: Int?, val nutrition: NutritionResult)
data class DayNutrition(val date: String, val status: DayStatus, val confirmedAt: Long?, val meals: List<MealNutrition>,
    val totals: NutritionResult, val snapshot: String)
