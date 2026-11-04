package de.ernaehrung.demo.data.integration

import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source

/**
 * Only explicit user-entered, version-scoped pre-selection evidence may filter
 * allergen-restricted offline proposals. It is NOT a product allergen registry,
 * a verified absence for FoodStore or a substitute for final manual review.
 */
internal object RecipeOfflineAllergenEvidence {
    fun confirmedAbsent(
        catalog: List<ProductVersionEntity>,
        independentlyMappedVersions: Map<String, String>,
        declaredAbsent: Set<Pair<String, String>>,
        restrictions: Set<String>,
        nowMillis: Long
    ): Map<String, Map<String, Boolean?>> {
        require(nowMillis >= 0L)
        require(restrictions.all(RecipeDraftHostGate::isSupportedRestriction)) {
            "unsupported restriction"
        }
        val excludedAllergens = restrictions.filter { it.startsWith("ALLERGEN:") }
            .map { it.removePrefix("ALLERGEN:") }.toSet()
        val knownFoods = RecipeOfflineDraftGenerator.ingredientTypes
            .map { it.foodTypeId }.toSet()
        require(declaredAbsent.all {
            it.first in knownFoods && it.second in excludedAllergens
        }) { "allergen evidence outside the current proposal scope" }
        val foodsWithEvidence = declaredAbsent.map { it.first }.toSet()
        val selectedVersions = foodsWithEvidence.map { food ->
            independentlyMappedVersions[food]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("missing independently selected product version")
        }
        require(selectedVersions.size == selectedVersions.toSet().size) {
            "ambiguous product mapping"
        }
        for (id in selectedVersions) {
            val selected = catalog.filter { it.id == id }.singleOrNull()
                ?: throw IllegalArgumentException("unknown product version")
            val current = catalog.filter {
                it.productId == selected.productId &&
                    it.recordedAt in 0L..nowMillis
            }
            val latest = current.maxOfOrNull { it.revision }
            require(selected.recordedAt in 0L..nowMillis &&
                selected.revision > 0 && selected.revision == latest &&
                current.count { it.revision == latest } == 1 &&
                selected.source != Source.DEMO &&
                selected.quality in setOf(Quality.CONFIRMED, Quality.SOURCED) &&
                selected.baseUnit == BaseUnit.GRAM) {
                "allergen pre-selection requires a current sourced gram product version"
            }
        }
        return declaredAbsent.groupBy { it.first }.mapValues { (_, items) ->
            items.associate { it.second to false as Boolean? }
        }
    }
}
