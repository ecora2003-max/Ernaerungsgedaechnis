package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.LocalDataViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ViewModel -> FoodStore -> Room checkpoint; never a recipe or consumption. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeDraftConsentViewModelTest {
    private val at = java.time.Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
    private lateinit var db: FoodDatabase
    private lateinit var vm: LocalDataViewModel
    private lateinit var facts: Map<String, Catalog.ConfirmedLine>
    private val states = mapOf("rice" to Gate.State.RAW)
    private val request = Gate.Request(Gate.Flags(false, false, false), 2,
        setOf("ALLERGEN:SOY"), userConfirmedRecipeSave = true)
    private val json = """
        {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
         "meta":{"producer":"synthetic","traceId":"viewmodel-test",
                 "createdAt":"2026-11-01T04:00:00Z"},
         "payload":{"draftId":"draft-ui","title":"Reis","portionCount":2,
           "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"UNKNOWN",
             "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":false}}],
           "steps":["Kochen"],"flags":{"considerStock":false,
             "considerShopping":false,"considerOffers":false},
           "warnings":[],"status":"DRAFT"}}
    """.trimIndent()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        val store = FoodStore(db) { at }
        runBlocking {
            val id = store.rememberProduct("ui-product", ProductInput("Reis",
                source = Source.USER, quality = Quality.CONFIRMED))
            val v = requireNotNull(db.foodDao().version(id))
            facts = mapOf("rice" to Catalog.ConfirmedLine("rice", v.id, v.revision,
                v.recordedAt, at, BaseUnit.GRAM, Gate.Basis.NET, Gate.State.RAW,
                allergens = mapOf("SOY" to false)))
        }
        vm = LocalDataViewModel(store)
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun ready() = withTimeout(10_000) {
        vm.recipeDraftConsentState.first { !it.busy && it.review != null }
    }

    @Test fun digestMismatchCannotRecordAndExplicitCallbackRecordsExactlyOneConsent() = runBlocking {
        vm.prepareRecipeDraftConsent(json, request, facts, states)
        val review = requireNotNull(ready().review)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review.status)
        val digest = requireNotNull(review.approvalBasisDigest)
        assertNull(vm.recipeDraftConsentState.value.recordedDigest)
        vm.confirmRecipeDraftConsent("f".repeat(64), vm.recipeDraftConsentState.value.sessionId)
        assertEquals(1, db.foodDao().operationCount()) // only the product creation
        assertNull(vm.recipeDraftConsentState.value.recordedDigest)
        vm.confirmRecipeDraftConsent(digest, vm.recipeDraftConsentState.value.sessionId)
        val recorded = withTimeout(10_000) {
            vm.recipeDraftConsentState.first { it.recordedDigest == digest }
        }
        assertEquals(digest, recorded.recordedDigest)
        vm.confirmRecipeDraftConsent(digest, vm.recipeDraftConsentState.value.sessionId) // local duplicate gesture is ignored
        assertEquals(2, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
        assertTrue(db.foodDao().allPaidPrices().isEmpty())
        assertTrue(db.foodDao().observeRecipes().first().isEmpty())
    }

    @Test fun changedPreviewInvalidatesOldCallbackWithoutAnyLedgerSideEffect() = runBlocking {
        vm.prepareRecipeDraftConsent(json, request, facts, states)
        val oldDigest = requireNotNull(ready().review?.approvalBasisDigest)
        val revised = json.replace("\"title\":\"Reis\"", "\"title\":\"Reis neu\"")
        vm.prepareRecipeDraftConsent(revised, request, facts, states)
        val newDigest = requireNotNull(ready().review?.approvalBasisDigest)
        assertNotEquals(oldDigest, newDigest)
        vm.confirmRecipeDraftConsent(oldDigest, vm.recipeDraftConsentState.value.sessionId)
        assertEquals(1, db.foodDao().operationCount())
        vm.confirmRecipeDraftConsent(newDigest, vm.recipeDraftConsentState.value.sessionId)
        withTimeout(10_000) {
            vm.recipeDraftConsentState.first { it.recordedDigest == newDigest }
        }
        assertEquals(2, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }
    @Test fun sameDigestNewSessionRejectsOldCallbackToken() = runBlocking {
        vm.prepareRecipeDraftConsent(json, request, facts, states)
        val first = ready()
        val digest = requireNotNull(first.review?.approvalBasisDigest)
        val oldToken = requireNotNull(first.sessionId)
        vm.prepareRecipeDraftConsent(json, request, facts, states)
        val second = ready()
        assertEquals(digest, second.review?.approvalBasisDigest)
        assertNotEquals(oldToken, second.sessionId)
        vm.confirmRecipeDraftConsent(digest, oldToken)
        assertEquals(1, db.foodDao().operationCount())
        vm.confirmRecipeDraftConsent(digest, second.sessionId)
        withTimeout(10_000) {
            vm.recipeDraftConsentState.first { it.recordedDigest == digest }
        }
        assertEquals(2, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }
    @Test fun changingImportEvidenceInvalidatesOldSessionBeforeConsent() = runBlocking {
        vm.prepareRecipeDraftConsent(json, request, facts, states)
        val old = ready()
        val digest = requireNotNull(old.review?.approvalBasisDigest)
        val session = requireNotNull(old.sessionId)
        vm.clearRecipeDraftConsent()
        assertNull(vm.recipeDraftConsentState.value.review)
        assertNull(vm.recipeDraftConsentState.value.sessionId)
        vm.confirmRecipeDraftConsent(digest, session)
        assertEquals(1, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

}
