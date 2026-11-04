package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeOfflineAllergenEvidence
import de.ernaehrung.demo.data.integration.RecipeOfflineDraftGenerator
import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import org.junit.Assert.*
import org.junit.Test

class RecipeOfflineAllergenEvidenceTest {
    private val now = 1_783_230_000_000L
    private fun version(id: String, revision: Int = 1,
        product: String = id.substringBefore("-v"),
        recorded: Long = now - 1_000L): ProductVersionEntity =
        ProductVersionEntity(id = id, productId = product, revision = revision,
            name = id, brand = null, category = null, barcode = null,
            baseUnit = BaseUnit.GRAM, packageAmount = null,
            nutritionBasis = 100_000L, energyKcal = null, proteinGrams = null,
            fatGrams = null, carbsGrams = null, source = Source.USER,
            sourceDetail = null, quality = Quality.CONFIRMED,
            recordedAt = recorded, changeReason = "manual evidence fixture")

    private val mapping = mapOf(
        "dry-rice" to "rice-v1", "cooked-beans" to "beans-v1",
        "tomato" to "tomato-v1"
    )
    private val catalog get() = mapping.values.map { version(it) }
    private val absent get() = mapping.keys.map { it to "SOY" }.toSet()
    private val restriction = setOf("ALLERGEN:SOY")

    @Test fun allThreeIndependentlyMappedAbsencesAllowOneUntrustedDraft() {
        val facts = RecipeOfflineAllergenEvidence.confirmedAbsent(
            catalog, mapping, absent, restriction, now
        )
        assertEquals(3, facts.size)
        assertEquals(false, facts["dry-rice"]?.get("SOY"))
        assertEquals(0, RecipeOfflineDraftGenerator.nextAllowedVariant(
            0, restriction, facts
        ))
        assertFalse(RecipeOfflineDraftGenerator.allowsSuggestion(
            1, restriction, facts
        ))
    }

    @Test fun unknownMissingStaleAndUntrustedVersionsCannotClearAllergens() {
        val cases = listOf(
            catalog to (mapping - "dry-rice"),
            catalog to (mapping + ("dry-rice" to "unknown")),
            catalog + version("rice-v2", 2, "rice") to mapping,
            catalog.map { it.copy(recordedAt = now + 1) } to mapping,
            catalog.map { it.copy(source = Source.DEMO) } to mapping,
            catalog.map { it.copy(quality = Quality.ESTIMATED) } to mapping,
            catalog.map { it.copy(baseUnit = BaseUnit.PORTION) } to mapping,
            catalog to (mapping + ("tomato" to "rice-v1"))
        )
        cases.forEachIndexed { i, (versions, selected) ->
            assertTrue("unsafe case $i", runCatching {
                RecipeOfflineAllergenEvidence.confirmedAbsent(
                    versions, selected, absent, restriction, now
                )
            }.isFailure)
        }
        assertTrue(runCatching {
            RecipeOfflineAllergenEvidence.confirmedAbsent(
                catalog, mapping, absent + ("dry-rice" to "PEANUT"),
                restriction, now
            )
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineAllergenEvidence.confirmedAbsent(
                catalog, mapping, absent, emptySet(), now
            )
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineAllergenEvidence.confirmedAbsent(
                catalog, mapping, absent + ("unknown-food" to "SOY"),
                restriction, now
            )
        }.isFailure)
    }
}
