package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    private suspend fun consent(
        key: String, expected: Gate.Review, text: String = json,
        facts: Map<String, Catalog.ConfirmedLine>? = null,
        req: Gate.Request = request, confirmedAction: Boolean = true
    ) = store.recordReviewedRecipeDraftV1Consent(key, text, req,
        facts ?: confirmed(), states, expected, confirmedAction)

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected transaction to reject the recipe write") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun exactReviewedInputSavesOnceAndNeverBooksStockOrConsumption() = runBlocking {
        val ready = preview()
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, ready.status)
        assertNotNull(ready.input)
        val expected = ready
        consent("recipe-save", expected)
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
        listOf("changed-title", "changed-amount", "changed-allergen").forEach {
            consent(it, expected)
        }
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
        consent("stale-save", expected)
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
        listOf("draft-id", "trace-id", "warnings").forEach { consent(it, expected) }
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
        consent("changed-restrictions", expected)
        consent("changed-evidence", expected)
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
        consent("unbound", ready)
        val unbound = ready.copy(approvalBasisDigest = null)
        rejected { save("unbound", unbound) }
        assertNull(dao.operation("unbound"))
    }


    @Test fun operationKeyRejectsAnotherDraftWithIdenticalRecipeProjection() = runBlocking {
        val first = preview()
        consent("same-projection-key", first)
        val saved = save("same-projection-key", first)
        val count = dao.operationCount()
        val otherJson = json.replace("\"draftId\":\"draft-save\"", "\"draftId\":\"another-draft\"")
        val other = preview(otherJson)
        assertEquals(first.input, other.input)
        assertNotEquals(first.approvalBasisDigest, other.approvalBasisDigest)
        rejected { save("same-projection-key", other, text = otherJson) }
        assertEquals(count, dao.operationCount())
        assertEquals(saved, dao.operation("same-projection-key")!!.resultId)
        assertEquals(saved, save("same-projection-key", first))
    }

    @Test fun manualAndDraftRecipeOperationsCannotReplayEachOthersKeys() = runBlocking {
        val approved = preview()
        consent("manual-key", approved)
        consent("draft-key", approved)
        val input = requireNotNull(approved.input)
        val manual = store.saveRecipe("manual-key", input,
            reason = "Bestätigter Rezeptentwurf v1")
        rejected { save("manual-key", approved) }
        val draft = save("draft-key", approved)
        rejected { store.saveRecipe("draft-key", input,
            reason = "Bestätigter Rezeptentwurf v1") }
        assertEquals(manual, dao.operation("manual-key")!!.resultId)
        assertEquals(draft, dao.operation("draft-key")!!.resultId)
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun reusedOperationKeyWithDifferentReviewedRecipeCannotOverwriteHistory() = runBlocking {
        val original = preview()
        consent("same-key", original)
        val saved = save("same-key", original)
        val altered = json.replace("\"amount\":200", "\"amount\":201")
        val changedPreview = preview(altered)
        val before = dao.operationCount()
        rejected { save("same-key", changedPreview, text = altered) }
        assertEquals(before, dao.operationCount())
        assertEquals(saved, dao.operation("same-key")!!.resultId)
        assertEquals(200_000L, dao.ingredients(saved).single().amount)
    }

    @Test fun absentOrUnconfirmedConsentCannotAuthorizeRecipeWrite() = runBlocking {
        val ready = preview()
        val before = dao.operationCount()
        rejected { save("no-consent-ledger", ready) }
        rejected { consent("unconfirmed-consent", ready, confirmedAction = false) }
        assertEquals(before, dao.operationCount())
        assertNull(dao.operation("no-consent-ledger"))
        assertNull(dao.operation("RECIPE_DRAFT_CONSENT_V1:unconfirmed-consent"))
    }

    @Test fun consentCheckpointIsDurableIdempotentAndDraftSpecific() = runBlocking {
        val ready = preview()
        val before = dao.operationCount()
        val digest = consent("consent-audit", ready)
        val checkpoint = requireNotNull(dao.operation("RECIPE_DRAFT_CONSENT_V1:consent-audit"))
        assertEquals("RECIPE_DRAFT_CONSENT_V1", checkpoint.kind)
        assertEquals(digest, checkpoint.resultId)
        assertEquals(at, checkpoint.recordedAt)
        assertEquals(digest, consent("consent-audit", ready))
        assertEquals(before + 1, dao.operationCount())
        assertNull(dao.operation("consent-audit"))
        val changed = json.replace("\"draftId\":\"draft-save\"", "\"draftId\":\"changed-id\"")
        rejected { consent("consent-audit", preview(changed), text = changed) }
        assertEquals(before + 1, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun consentSurvivesDatabaseRestartAndRevalidatedSaveIsIdempotent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recipe-consent-restart-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        try {
            val firstDb = Room.databaseBuilder(context, FoodDatabase::class.java, name).build()
            val facts: Map<String, Catalog.ConfirmedLine>
            val ready: Gate.Review
            try {
                val firstStore = FoodStore(firstDb) { at }
                val id = firstStore.rememberProduct("restart-product",
                    ProductInput("Reis", source = Source.USER, quality = Quality.CONFIRMED))
                val v = requireNotNull(firstDb.foodDao().version(id))
                facts = mapOf("rice" to Catalog.ConfirmedLine("rice", v.id, v.revision,
                    v.recordedAt, at, BaseUnit.GRAM, Gate.Basis.NET, Gate.State.RAW,
                    allergens = mapOf("SOY" to false)))
                ready = firstStore.previewRecipeDraftV1(json, request, facts, states)
                assertNotNull(ready.approvalBasisDigest)
                firstStore.recordReviewedRecipeDraftV1Consent("restart-save", json,
                    request, facts, states, ready, true)
                assertNull(firstDb.foodDao().operation("restart-save"))
            } finally {
                firstDb.close()
            }
            val secondDb = Room.databaseBuilder(context, FoodDatabase::class.java, name).build()
            try {
                val secondStore = FoodStore(secondDb) { at }
                assertEquals(ready.approvalBasisDigest,
                    secondDb.foodDao().operation("RECIPE_DRAFT_CONSENT_V1:restart-save")?.resultId)
                val saved = secondStore.saveReviewedRecipeDraftV1("restart-save",
                    json, request, facts, states, ready, true)
                assertEquals(saved, secondStore.saveReviewedRecipeDraftV1("restart-save",
                    json, request, facts, states, ready, true))
                assertNotNull(secondDb.foodDao().recipeVersion(saved))
                assertEquals(0, secondDb.foodDao().consumptionCount())
                assertTrue(secondDb.foodDao().allPaidPrices().isEmpty())
            } finally {
                secondDb.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun futureDatedDraftCannotBeConfirmedOrSavedBeforeItsCreationInstant() = runBlocking {
        var trustedClock = at
        store = FoodStore(db) { trustedClock }
        val futureJson = json.replace(
            "\"createdAt\":\"2026-11-01T04:00:00Z\"",
            "\"createdAt\":\"2026-11-01T05:02:00+01:00\"")
        // The importer tolerates a small producer-clock skew for display only.
        val ready = preview(futureJson)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, ready.status)
        val before = dao.operationCount()
        rejected { consent("future-consent", ready, text = futureJson) }
        rejected { save("future-consent", ready, text = futureJson) }
        assertEquals(before, dao.operationCount())
        assertNull(dao.operation("RECIPE_DRAFT_CONSENT_V1:future-consent"))
        assertNull(dao.operation("future-consent"))
        trustedClock = at + 120_000L
        assertEquals(ready.approvalBasisDigest, preview(futureJson).approvalBasisDigest)
        assertEquals(ready.approvalBasisDigest,
            consent("future-consent", ready, text = futureJson))
        val saved = save("future-consent", ready, text = futureJson)
        assertNotNull(dao.recipeVersion(saved))
        assertEquals(0, dao.consumptionCount())
        assertTrue(dao.allPaidPrices().isEmpty())
    }

    @Test fun consentUsesOneTrustedInstantEvenIfDeviceClockChangesDuringTransaction() = runBlocking {
        val futureJson = json.replace(
            "\"createdAt\":\"2026-11-01T04:00:00Z\"",
            "\"createdAt\":\"2026-11-01T05:02:00+01:00\"")
        val ready = preview(futureJson)
        var clockReads = 0
        store = FoodStore(db) {
            if (clockReads++ == 0) at + 120_000L else at
        }
        val digest = consent("clock-shift", ready, text = futureJson)
        assertEquals(ready.approvalBasisDigest, digest)
        val checkpoint = requireNotNull(dao.operation("RECIPE_DRAFT_CONSENT_V1:clock-shift"))
        assertEquals(at + 120_000L, checkpoint.recordedAt)
        assertEquals(1, clockReads)
        // A clock rollback must not let the subsequent save predate creation.
        rejected { save("clock-shift", ready, text = futureJson) }
        assertNull(dao.operation("clock-shift"))
        store = FoodStore(db) { at + 120_000L }
        assertNotNull(dao.recipeVersion(save("clock-shift", ready, text = futureJson)))
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun concurrentConsentAndSaveRetriesProduceOneRecipe() = runBlocking {
        val ready = preview()
        val facts = confirmed()
        val before = dao.operationCount()
        val consentDigests = (1..8).map {
            async(Dispatchers.Default) {
                store.recordReviewedRecipeDraftV1Consent(
                    "concurrent-recipe", json, request, facts, states, ready, true)
            }
        }.awaitAll()
        assertEquals(1, consentDigests.toSet().size)
        assertEquals(ready.approvalBasisDigest, consentDigests.first())
        assertEquals(before + 1, dao.operationCount())

        val recipeIds = (1..8).map {
            async(Dispatchers.Default) {
                store.saveReviewedRecipeDraftV1(
                    "concurrent-recipe", json, request, facts, states, ready, true)
            }
        }.awaitAll()
        assertEquals(1, recipeIds.toSet().size)
        val recipeId = recipeIds.first()
        assertEquals(recipeId, dao.operation("concurrent-recipe")?.resultId)
        assertEquals(before + 2, dao.operationCount())
        assertEquals(1, dao.ingredients(recipeId).size)
        assertEquals(0, dao.consumptionCount())
        assertTrue(dao.allPaidPrices().isEmpty())
    }
}
