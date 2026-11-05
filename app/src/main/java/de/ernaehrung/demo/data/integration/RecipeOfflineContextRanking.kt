package de.ernaehrung.demo.data.integration

import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source

/**
 * Read-only ordering of offline suggestions using independently selected current
 * product versions and content-bound Room snapshots. A FoodType is never
 * automatically equated to a catalog version by name or model output.
 *
 * The score is only a gram-quantity planning hint. It cannot establish edible
 * stock, raw/cooked or drained conversion, allergen clearance, or consent;
 * all of those remain mandatory in the separate v1 review/FoodStore gate.
 */
internal object RecipeOfflineContextRanking {
    data class Ranked(
        val variant: Int,
        val stockCoveredMilliGrams: Long,
        val shoppingNeedMilliGrams: Long
    )

    fun rank(
        portions: Int,
        flags: RecipeDraftHostGate.Flags,
        context: RecipeDraftHostGate.Context<RecipeContextContentBinding.Snapshot,
            RecipeContextContentBinding.Snapshot, RecipeContextContentBinding.Snapshot>,
        catalog: List<ProductVersionEntity>,
        independentlySelectedVersions: Map<String, String>,
        explicitlyReviewed: Boolean,
        confirmedRestrictions: Set<String> = emptySet(),
        independentlyConfirmedAllergens: Map<String, Map<String, Boolean?>> = emptyMap(),
        preferences: RecipeRequestPreferences = RecipeRequestPreferences()
    ): List<Ranked> {
        require(portions in 1..1000)
        require(explicitlyReviewed) { "product mapping needs an explicit review" }
        require(flags.considerStock || flags.considerShopping) {
            "no quantity context selected"
        }
        // No independently authorized offer provider is currently registered.
        require(!flags.considerOffers && context.offers == null) {
            "offers are not approved"
        }
        val binding = requireNotNull(context.contentBinding) {
            "typed host context is not bound"
        }
        require(context.contentBindingRequired && context.projectedFlags == flags &&
            context.gaps.isEmpty() && binding.flags == flags &&
            RecipeContextContentBinding.matches(binding, flags, context.stock,
                context.shopping, context.offers)) {
            "stale, incomplete or mismatched Room context"
        }
        val knownTypes = RecipeOfflineDraftGenerator.ingredientTypes.map {
            it.foodTypeId
        }.toSet()
        require(independentlySelectedVersions.isNotEmpty() &&
            independentlySelectedVersions.keys.all { it in knownTypes } &&
            independentlySelectedVersions.values.all { it.isNotBlank() } &&
            independentlySelectedVersions.values.toSet().size ==
                independentlySelectedVersions.size) {
            "unresolved or ambiguous FoodType/product mapping"
        }
        require(confirmedRestrictions.all(RecipeDraftHostGate::isSupportedRestriction)) {
            "invalid restriction"
        }
        val snapshotMillis = binding.snapshotAt.toEpochMilli()
        require(snapshotMillis >= 0L)
        val mapped = independentlySelectedVersions.mapValues { (_, versionId) ->
            val version = catalog.filter { it.id == versionId }.singleOrNull()
                ?: throw IllegalArgumentException("unknown product version")
            val knownVersions = catalog.filter {
                it.productId == version.productId && it.recordedAt in 0L..snapshotMillis
            }
            val latestRevision = knownVersions.maxOfOrNull { it.revision }
            require(version.recordedAt in 0L..snapshotMillis &&
                version.revision > 0 && version.revision == latestRevision &&
                knownVersions.count { it.revision == latestRevision } == 1 &&
                version.source != Source.DEMO &&
                version.quality in setOf(Quality.CONFIRMED, Quality.SOURCED) &&
                version.baseUnit == BaseUnit.GRAM) {
                "mapping is not a current, sourced gram product version"
            }
            version
        }

        fun available(snapshot: RecipeContextContentBinding.Snapshot?,
            versionId: String): Long {
            if (snapshot == null) return 0L
            return snapshot.lines.filter { it.versionId == versionId }.fold(0L) { total, line ->
                require(line.unit == BaseUnit.GRAM.label && line.amountMilli != null &&
                    line.amountMilli >= 0L) {
                    "unknown or incompatible context quantity"
                }
                Math.addExact(total, line.amountMilli)
            }
        }

        return (0 until RecipeOfflineDraftGenerator.VARIANT_COUNT).mapNotNull { variant ->
            val ingredients = RecipeOfflineDraftGenerator.ingredientsFor(variant)
            if (ingredients.any { it.foodTypeId !in mapped || it.unit != "g" } ||
                !RecipeOfflineDraftGenerator.allowsSuggestion(variant, confirmedRestrictions,
                    independentlyConfirmedAllergens, preferences, portions)) return@mapNotNull null
            var stockCovered = 0L
            var shoppingNeed = 0L
            for (ingredient in ingredients) {
                // Only gram-based candidates are comparable without a conversion.
                if (ingredient.basis !in setOf("NET", "DRAINED"))
                    return@mapNotNull null
                val versionId = mapped.getValue(ingredient.foodTypeId).id
                val required = Math.multiplyExact(
                    Math.multiplyExact(ingredient.amountPerPortion, portions.toLong()), 1000L
                )
                if (flags.considerStock) {
                    stockCovered = Math.addExact(stockCovered,
                        minOf(required, available(context.stock, versionId)))
                }
                if (flags.considerShopping) {
                    shoppingNeed = Math.addExact(shoppingNeed,
                        minOf(required, available(context.shopping, versionId)))
                }
            }
            Ranked(variant, stockCovered, shoppingNeed)
        }.sortedWith(compareByDescending<Ranked> { it.stockCoveredMilliGrams }
            .thenBy { it.shoppingNeedMilliGrams }.thenBy { it.variant })
    }
}
