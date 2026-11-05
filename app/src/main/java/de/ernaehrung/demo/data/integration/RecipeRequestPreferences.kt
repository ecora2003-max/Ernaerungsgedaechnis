package de.ernaehrung.demo.data.integration

import de.eg.recipe.RecipeEngine as Core
import de.ernaehrung.demo.data.local.Money
import java.math.BigDecimal

/** Independently entered request values, never read back from untrusted JSON. */
internal data class RecipeRequestPreferences(
    val mealType: String? = null,
    val maxMinutes: Int? = null,
    /** null = unknown; an empty set = explicitly no equipment available. */
    val equipment: Set<String>? = null,
    val targets: List<Target> = emptyList(),
    val preferredFoodTypes: Set<String> = emptySet(),
    val budgetCents: Long? = null,
    val budgetConfirmed: Boolean = false,
    val freezePortions: Int = 0,
    val freezerCapacityPortions: Int? = null
) {
    data class Target(val nutrient: String, val min: BigDecimal?, val max: BigDecimal?)

    companion object {
        val nutrientLabels = linkedMapOf("energyKcal" to "Energie (kcal)", "proteinG" to "Eiweiß (g)",
            "carbohydrateG" to "Kohlenhydrate (g)", "fatG" to "Fett (g)", "fiberG" to "Ballaststoffe (g)",
            "vitaminB12Ug" to "Vitamin B12 (µg)", "vitaminDUg" to "Vitamin D (µg)")
        private val token = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
    }

    fun validate(portions: Int) {
        require(portions in 1..1000)
        require(mealType == null || token.matches(mealType)) { "invalid meal type" }
        require(maxMinutes == null || maxMinutes in 1..1440) { "invalid cooking time" }
        require(equipment == null || equipment.size <= 32 && equipment.all(token::matches)) { "invalid equipment" }
        require(preferredFoodTypes.size <= 64 && preferredFoodTypes.all(token::matches)) { "invalid preferred foods" }
        require(targets.size <= nutrientLabels.size && targets.map { it.nutrient }.distinct().size == targets.size)
        targets.forEach {
            require(it.nutrient in nutrientLabels && (it.min != null || it.max != null)) { "invalid nutrition target" }
            require(listOfNotNull(it.min, it.max).all { value -> value.signum() >= 0 &&
                value.precision() <= 20 && value.scale() in 0..6 }) { "invalid nutrition target range" }
            require(it.min == null || it.max == null || it.min <= it.max) { "inconsistent nutrition target" }
        }
        budgetCents?.let(Money::valid)
        require(!budgetConfirmed || budgetCents != null) { "confirmed budget missing" }
        require(freezePortions in 0..portions && (freezerCapacityPortions == null || freezerCapacityPortions in 0..1000)) {
            "invalid freezing portions"
        }
    }

    fun applyTo(request: Core.Request): Core.Request {
        validate(request.portions)
        return request.copy(mealType = mealType, maxMinutes = maxMinutes, equipment = equipment?.toSet(),
            targets = targets.map { Core.Target(it.nutrient, it.min, it.max, true) },
            preferredFoodTypes = preferredFoodTypes.toSet(), budgetCents = budgetCents, budgetConfirmed = budgetConfirmed,
            freezePortions = freezePortions, freezerCapacityPortions = freezerCapacityPortions)
    }

    /** Stable host consent binding. Sorting collections does not change their meaning. */
    fun binding(): List<Any?> = listOf(mealType, maxMinutes, equipment?.sorted(),
        targets.sortedBy { it.nutrient }.map { listOf(it.nutrient,
            it.min?.stripTrailingZeros()?.toPlainString(), it.max?.stripTrailingZeros()?.toPlainString()) },
        preferredFoodTypes.sorted(), budgetCents, budgetConfirmed, freezePortions, freezerCapacityPortions)
}
