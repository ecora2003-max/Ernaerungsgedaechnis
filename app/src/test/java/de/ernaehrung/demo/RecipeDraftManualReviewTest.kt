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

    @Test fun futureRevisionDoesNotDisplaceCurrentVersionUntilItsOwnRecordedTime() {
        val versions = listOf(catalog(), catalog("v2", 2, recordedAt = nowMillis + 1_000L))
        val before = Manual.prepare(imported(), versions,
            mapOf("rice" to choice(false)), setOf("ALLERGEN:SOY"), true, nowMillis)
        assertEquals("v1", before.confirmations["rice"]?.productVersionId)
        assertEquals("v1", RecipeCatalogEvidence.resolve(imported().draft.lines,
            versions, before.confirmations, nowMillis)["rice"]?.productVersionId)

        // Once the new revision is known, the old confirmation becomes stale.
        rejects { Manual.prepare(imported(), versions,
            mapOf("rice" to choice(false)), setOf("ALLERGEN:SOY"), true,
            nowMillis + 1_000L) }
        assertTrue(RecipeCatalogEvidence.resolve(imported().draft.lines,
            versions, before.confirmations, nowMillis + 1_000L).isEmpty())
        val after = Manual.prepare(imported(), versions,
            mapOf("rice" to choice(false).copy(versionId = "v2")),
            setOf("ALLERGEN:SOY"), true, nowMillis + 1_000L)
        assertEquals("v2", RecipeCatalogEvidence.resolve(imported().draft.lines,
            versions, after.confirmations, nowMillis + 1_000L)["rice"]?.productVersionId)
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
    @Test fun futureDatedDraftCannotBeManuallyConfirmedBeforeItsCreation() {
        // Import tolerates a one-minute generator clock lead; independent
        // host confirmation must still occur after the claimed draft creation.
        val future = imported(json().replace(
            "2026-11-01T04:00:00Z", "2026-11-01T04:01:00Z"
        ))
        val versions = listOf(catalog(recordedAt = nowMillis - 10_000L))
        val choices = mapOf("rice" to choice(false))
        val restrictions = setOf("ALLERGEN:SOY")
        rejects {
            Manual.prepare(future, versions, choices, restrictions, true, nowMillis)
        }
        val approvedAt = nowMillis + 60_000L
        val prepared = Manual.prepare(future, versions, choices, restrictions,
            true, approvedAt)
        assertEquals(approvedAt, prepared.confirmations["rice"]?.confirmedAt)
        val facts = RecipeCatalogEvidence.resolve(future.draft.lines, versions,
            prepared.confirmations, approvedAt)
        assertEquals("v1", facts["rice"]?.productVersionId)
    }

    @Test fun importedDraftCannotBeConfirmedAfterItsOriginalFreshnessWindow() {
        val parsed = imported()
        val versions = listOf(catalog())
        val choices = mapOf("rice" to choice(false))
        val restrictions = setOf("ALLERGEN:SOY")
        val atLimit = nowMillis + 86_400_000L
        val valid = Manual.prepare(parsed, versions, choices, restrictions, true, atLimit)
        assertEquals(atLimit, valid.confirmations["rice"]?.confirmedAt)
        rejects {
            Manual.prepare(parsed, versions, choices, restrictions, true, atLimit + 1L)
        }
        // Expiration does not mutate the imported draft or block a fresh review.
        val fresh = Manual.prepare(parsed, versions, choices, restrictions, true, nowMillis)
        assertEquals("v1", fresh.confirmations["rice"]?.productVersionId)
    }

    @Test fun stalePreparationChoiceCannotContradictPreviouslyReviewedDraftState() {
        val alreadyReviewed = RecipeDraftV1Import.read(
            json(), mapOf("rice" to Gate.State.RAW), now
        )
        val versions = listOf(catalog())
        val restrictions = setOf("ALLERGEN:SOY")
        rejects {
            Manual.prepare(alreadyReviewed, versions,
                mapOf("rice" to choice(false).copy(state = Gate.State.COOKED)),
                restrictions, true, nowMillis)
        }
        val consistent = Manual.prepare(alreadyReviewed, versions,
            mapOf("rice" to choice(false)), restrictions, true, nowMillis)
        assertEquals(Gate.State.RAW, consistent.reviewedStates["rice"])
        // UNKNOWN has no earlier independently reviewed preparation state.
        val newlyReviewed = Manual.prepare(imported(), versions,
            mapOf("rice" to choice(false).copy(state = Gate.State.COOKED)),
            restrictions, true, nowMillis)
        assertEquals(Gate.State.COOKED, newlyReviewed.reviewedStates["rice"])
    }

    @Test fun negativeCatalogAndReviewTimestampsNeverCreateHostConfirmation() {
        val parsed = imported()
        val reviewed = mapOf("rice" to choice(false))
        val restrictions = setOf("ALLERGEN:SOY")
        rejects { Manual.prepare(parsed, listOf(catalog()), reviewed,
            restrictions, true, -1L) }
        rejects { Manual.prepare(parsed, listOf(catalog(recordedAt = -1L)),
            reviewed, restrictions, true, nowMillis) }
        val valid = Manual.prepare(parsed, listOf(catalog()), reviewed,
            restrictions, true, nowMillis)
        assertEquals("v1", valid.confirmations["rice"]?.productVersionId)
    }


    @Test fun malformedRestrictionTokensAreRejectedBeforeHostConfirmation() {
        val parsed = imported()
        val versions = listOf(catalog())
        val choices = mapOf("rice" to choice(false))
        val malformed = listOf(
            "FOOD:rice ", "FOOD:rice:other", "FOOD:ri\u200Bce",
            "ALLERGEN:SOY\t", "ALLERGEN:SOY:other", "ALLERGEN:SOY\u2060",
            "FOOD:", "ALLERGEN:", "OTHER:rice"
        )
        for (token in malformed) {
            rejects {
                Manual.prepare(parsed, versions, choices, setOf(token), true, nowMillis)
            }
        }
        // The strict gate still permits legitimate restrictions and explicit
        // negative allergen review, without writing a FoodStore event.
        val valid = Manual.prepare(parsed, versions, choices,
            setOf("ALLERGEN:SOY", "FOOD:other"), true, nowMillis)
        assertEquals(setOf("ALLERGEN:SOY", "FOOD:other"),
            valid.request.confirmedRestrictions)
        val facts = RecipeCatalogEvidence.resolve(parsed.draft.lines,
            versions, valid.confirmations, nowMillis)
        val review = RecipeDraftV1Import.review(json(), valid.request,
            facts, valid.reviewedStates, now)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review.status)
        assertNotNull(review.input)

        // A well-formed exclusion of the actual ingredient remains a blocker.
        val excluded = Manual.prepare(parsed, versions, choices,
            setOf("FOOD:rice", "ALLERGEN:SOY"), true, nowMillis)
        val excludedReview = RecipeDraftV1Import.review(json(), excluded.request,
            facts, excluded.reviewedStates, now)
        assertEquals(Gate.Status.BLOCKED, excludedReview.status)
        assertTrue(excludedReview.blockers.contains("CONFIRMED_FOOD_CONFLICT"))
        assertNull(excludedReview.input)
    }

}
