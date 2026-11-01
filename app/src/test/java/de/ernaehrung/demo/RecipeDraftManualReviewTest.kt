package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence
import de.ernaehrung.demo.data.integration.RecipeDraftManualReview as Manual
import de.ernaehrung.demo.data.integration.RecipeDraftV1Import
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class RecipeDraftManualReviewTest {
    private val now = OffsetDateTime.parse("2026-11-01T04:00:00Z")
    private val nowMillis = now.toInstant().toEpochMilli()
    private fun json(stock: Boolean = false, status: String = "UNKNOWN") = """
        {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
         "meta":{"producer":"synthetic","traceId":"manual-review","createdAt":"2026-11-01T04:00:00Z"},
         "payload":{"draftId":"manual-1","title":"Reis","portionCount":2,
           "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"$status",
             "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":true}}],
           "steps":["Kochen"],"flags":{"considerStock":$stock,
             "considerShopping":false,"considerOffers":false},
           "warnings":[],"status":"DRAFT"}}
    """.trimIndent()
    private fun catalog(id: String = "v1", revision: Int = 1,
                        source: Source = Source.USER, quality: Quality = Quality.CONFIRMED,
                        recordedAt: Long = nowMillis - 1000) = ProductVersionEntity(
        id = id, productId = "product-rice", revision = revision,
        name = "Reis", brand = null, category = null, barcode = null,
        baseUnit = BaseUnit.GRAM, packageAmount = null, nutritionBasis = 100_000L,
        energyKcal = null, proteinGrams = null, fatGrams = null, carbsGrams = null,
        source = source, sourceDetail = null, quality = quality,
        recordedAt = recordedAt, changeReason = "synthetic"
    )
    private fun choice(allergen: Boolean? = null) = Manual.Choice(
        "v1", Gate.Basis.NET, Gate.State.RAW,
        mapOf("SOY" to allergen)
    )
    private fun imported(json: String = json()) =
        RecipeDraftV1Import.read(json, emptyMap(), now)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected a fail-closed manual review") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun explicitCatalogReviewCreatesOnlyHostFactsNotAWrite() {
        val prepared = Manual.prepare(imported(), listOf(catalog()),
            mapOf("rice" to choice(false)), setOf("ALLERGEN:SOY"), true, nowMillis)
        assertTrue(prepared.request.userConfirmedRecipeSave)
        assertEquals("v1", prepared.confirmations["rice"]?.productVersionId)
        assertEquals(Gate.Basis.NET, prepared.confirmations["rice"]?.basis)
        assertEquals(Gate.State.RAW, prepared.reviewedStates["rice"])
        val resolved = RecipeCatalogEvidence.resolve(imported().draft.lines,
            listOf(catalog()), prepared.confirmations, nowMillis)
        assertEquals(false, resolved["rice"]?.allergens?.get("SOY"))
        val reviewed = RecipeDraftV1Import.review(json(), prepared.request, resolved,
            prepared.reviewedStates, now)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed.status)
        assertNotNull(reviewed.input)
    }

    @Test fun generatorClaimsNeverReplaceExplicitUserReview() {
        val modelClaim = imported(json(status = "CONFIRMED"))
        rejects { Manual.prepare(modelClaim, listOf(catalog()), emptyMap(),
            emptySet(), true, nowMillis) }
        rejects { Manual.prepare(modelClaim, listOf(catalog()),
            mapOf("rice" to choice()), setOf("ALLERGEN:SOY"), false, nowMillis) }
        rejects { Manual.prepare(modelClaim, listOf(catalog()),
            mapOf("rice" to choice()), setOf("ALLERGEN:SOY"), true, nowMillis - 2000) }
    }

    @Test fun staleDemoAndConflictingCatalogVersionsFailClosed() {
        val parsed = imported()
        rejects { Manual.prepare(parsed, listOf(catalog(source = Source.DEMO)),
            mapOf("rice" to choice()), setOf("ALLERGEN:SOY"), true, nowMillis) }
        rejects { Manual.prepare(parsed, listOf(catalog(quality = Quality.ESTIMATED)),
            mapOf("rice" to choice()), setOf("ALLERGEN:SOY"), true, nowMillis) }
        rejects { Manual.prepare(parsed, listOf(catalog(), catalog("v2", 2)),
            mapOf("rice" to choice()), setOf("ALLERGEN:SOY"), true, nowMillis) }
        rejects { Manual.prepare(parsed, listOf(catalog(), catalog()),
            mapOf("rice" to choice()), setOf("ALLERGEN:SOY"), true, nowMillis) }
    }

    @Test fun unknownAllergenNeverBecomesAbsentAndContextIsNotSilentlyDropped() {
        val prepared = Manual.prepare(imported(), listOf(catalog()),
            mapOf("rice" to choice(null)), setOf("ALLERGEN:SOY"), true, nowMillis)
        val facts = RecipeCatalogEvidence.resolve(imported().draft.lines,
            listOf(catalog()), prepared.confirmations, nowMillis)
        val review = RecipeDraftV1Import.review(json(), prepared.request, facts,
            prepared.reviewedStates, now)
        assertEquals(Gate.Status.REVIEW_REQUIRED, review.status)
        assertTrue(review.gaps.contains("ALLERGEN_STATUS_UNKNOWN"))
        assertNull(review.input)
        rejects { Manual.prepare(imported(), listOf(catalog()),
            mapOf("rice" to Manual.Choice("v1", Gate.Basis.NET, Gate.State.RAW)),
            setOf("ALLERGEN:SOY"), true, nowMillis) }
        val contextJson = json(stock = true).replace(
            "\"createdAt\":\"2026-11-01T04:00:00Z\"",
            "\"createdAt\":\"2026-11-01T04:00:00Z\",\"inputsSnapshotAt\":\"2026-11-01T04:00:00Z\""
        )
        rejects { Manual.prepare(imported(contextJson), listOf(catalog()),
            mapOf("rice" to choice(false)), setOf("ALLERGEN:SOY"), true, nowMillis) }
    }
}
