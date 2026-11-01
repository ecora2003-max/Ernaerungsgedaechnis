package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Room transaction: a reviewed draft must be revalidated before its first recipe write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeDraftCatalogSaveTest {
    private val at = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var versionId: String
    private val dao get() = db.foodDao()
    private val states = mapOf("rice" to Gate.State.RAW)
    private val request = Gate.Request(Gate.Flags(false, false, false), 2,
        setOf("ALLERGEN:SOY"), userConfirmedRecipeSave = true)
    private val json = """
        {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
         "meta":{"producer":"synthetic","traceId":"save-test",
                 "createdAt":"2026-11-01T04:00:00Z"},
         "payload":{"draftId":"draft-save","title":"Reis","portionCount":2,
           "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"UNKNOWN",
             "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":false}}],
           "steps":["Kochen"],"flags":{"considerStock":false,
             "considerShopping":false,"considerOffers":false},
           "warnings":[],"status":"DRAFT"}}
    """.trimIndent()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        store = FoodStore(db) { at }
        versionId = store.rememberProduct("product-rice", ProductInput("Reis",
            source = Source.USER, quality = Quality.CONFIRMED))
    }
    @After fun close() { db.close() }

    private suspend fun confirmed(): Map<String, Catalog.ConfirmedLine> {
        val v = requireNotNull(dao.version(versionId))
        return mapOf("rice" to Catalog.ConfirmedLine("rice", v.id, v.revision,
            v.recordedAt, at, BaseUnit.GRAM, Gate.Basis.NET, Gate.State.RAW,
            allergens = mapOf("SOY" to false)))
    }
    private suspend fun preview(
        text: String = json,
        facts: Map<String, Catalog.ConfirmedLine>? = null
    ) = store.previewRecipeDraftV1(text, request, facts ?: confirmed(), states)

    private suspend fun save(
        key: String, expected: Gate.Review, text: String = json,
        facts: Map<String, Catalog.ConfirmedLine>? = null,
        req: Gate.Request = request, confirmedSave: Boolean = true
    ) = store.saveReviewedRecipeDraftV1(key, text, req, facts ?: confirmed(), states, expected, confirmedSave)

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected transaction to reject the recipe write") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun exactReviewedInputSavesOnceAndNeverBooksStockOrConsumption() = runBlocking {
        val ready = preview()
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, ready.status)
        assertNotNull(ready.input)
        val expected = ready
        val before = dao.operationCount()
        val recipe = save("recipe-save", expected)
        assertEquals(recipe, save("recipe-save", expected))
        assertNotNull(dao.recipeVersion(recipe))
        assertEquals(versionId, dao.ingredients(recipe).single().productVersionId)
        assertEquals(before + 1, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
        assertTrue(dao.allPaidPrices().isEmpty())
    }

    @Test fun missingSeparateConfirmationCannotWriteAnything() = runBlocking {
        val expected = preview()
        val before = dao.operationCount()
        rejected { save("no-confirm", expected, confirmedSave = false) }
        rejected { save("request-no-confirm", expected,
            req = request.copy(userConfirmedRecipeSave = false)) }
        assertEquals(before, dao.operationCount())
        assertNull(dao.operation("no-confirm"))
        assertNull(dao.operation("request-no-confirm"))
    }

    @Test fun changedDraftOrChangedHostAllergenEvidenceCannotReusePreview() = runBlocking {
        val expected = preview()
        val before = dao.operationCount()
        rejected { save("changed-title", expected,
            text = json.replace("\"title\":\"Reis\"", "\"title\":\"Reis geändert\"")) }
        rejected { save("changed-amount", expected,
            text = json.replace("\"amount\":200", "\"amount\":201")) }
        val allergic = confirmed().mapValues {
            it.value.copy(allergens = mapOf("SOY" to true))
        }
        rejected { save("changed-allergen", expected, facts = allergic) }
        assertEquals(before, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun interveningCatalogRevisionInvalidatesPreviouslyReadyPreview() = runBlocking {
        val expected = preview()
        val productId = requireNotNull(dao.version(versionId)).productId
        store.rememberProduct("revision", ProductInput("Reis, neue Version",
            source = Source.USER, quality = Quality.CONFIRMED),
            productId, "Neue Produktversion")
        val before = dao.operationCount()
        rejected { save("stale-save", expected) }
        assertNull(dao.operation("stale-save"))
        assertEquals(before, dao.operationCount())
        assertEquals(Gate.Status.REVIEW_REQUIRED, preview().status)
    }

    @Test fun sameRecipeInputButDifferentDraftIdentityOrMetadataCannotSave() = runBlocking {
        val expected = preview()
        val before = dao.operationCount()
        val changedDraftId = json.replace("\"draftId\":\"draft-save\"", "\"draftId\":\"other-draft\"")
        val changedTrace = json.replace("\"traceId\":\"save-test\"", "\"traceId\":\"new-trace\"")
        val changedWarnings = json.replace("\"warnings\":[]", "\"warnings\":[\"Changed warning\"]")
        for ((key, text) in listOf("draft-id" to changedDraftId,
            "trace-id" to changedTrace, "warnings" to changedWarnings)) {
            val current = preview(text)
            assertEquals(expected.input, current.input)
            assertNotEquals(expected.approvalBasisDigest, current.approvalBasisDigest)
            rejected { save(key, expected, text = text) }
            assertNull(dao.operation(key))
        }
        assertEquals(before, dao.operationCount())
    }

    @Test fun changedRestrictionsOrAllergenEvidenceCannotReuseOtherwiseIdenticalPreview() = runBlocking {
        val expected = preview()
        val before = dao.operationCount()
        val fewerRestrictions = request.copy(confirmedRestrictions = emptySet())
        val alteredFacts = confirmed().mapValues {
            it.value.copy(allergens = mapOf("SOY" to false, "WHEAT" to false))
        }
        assertEquals(expected.input,
            store.previewRecipeDraftV1(json, fewerRestrictions, confirmed(), states).input)
        assertEquals(expected.input, preview(facts = alteredFacts).input)
        rejected { save("changed-restrictions", expected, req = fewerRestrictions) }
        rejected { save("changed-evidence", expected, facts = alteredFacts) }
        assertEquals(before, dao.operationCount())
    }

    @Test fun rawGenericReviewCannotMasqueradeAsHostBoundPreview() = runBlocking {
        val ready = preview()
        val unbound = ready.copy(approvalBasisDigest = null)
        rejected { save("unbound", unbound) }
        assertNull(dao.operation("unbound"))
    }

    @Test fun reusedOperationKeyWithDifferentReviewedRecipeCannotOverwriteHistory() = runBlocking {
        val original = preview()
        val saved = save("same-key", original)
        val altered = json.replace("\"amount\":200", "\"amount\":201")
        val changedPreview = preview(altered)
        val before = dao.operationCount()
        rejected { save("same-key", changedPreview, text = altered) }
        assertEquals(before, dao.operationCount())
        assertEquals(saved, dao.operation("same-key")!!.resultId)
        assertEquals(200_000L, dao.ingredients(saved).single().amount)
    }
}
