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
    @Test fun reimportAfterViewModelRecreationRecoversDurableConsentWithoutReplay() = runBlocking {
        vm.prepareRecipeDraftConsent(json, request, facts, states)
        val first = ready()
        val digest = requireNotNull(first.review?.approvalBasisDigest)
        val originalKey = requireNotNull(first.sessionId)
        vm.confirmRecipeDraftConsent(digest, originalKey)
        withTimeout(10_000) {
            vm.recipeDraftConsentState.first { it.recordedDigest == digest }
        }
        val before = db.foodDao().operationCount()
        assertEquals(2, before)

        // A new ViewModel represents lost in-memory session state. It must
        // discover the old operation key, not create a new consent gesture.
        val recreated = LocalDataViewModel(FoodStore(db) { at })
        recreated.prepareRecipeDraftConsent(json, request, facts, states)
        val restored = withTimeout(10_000) {
            recreated.recipeDraftConsentState.first { !it.busy && it.review != null }
        }
        assertEquals(digest, restored.recordedDigest)
        // A recovered ledger operation is NOT a recovered UI gesture.
        assertNull(restored.sessionId)
        assertNotNull(FoodStore(db) { at }.recordedRecipeDraftV1ConsentKey(digest))
        recreated.confirmRecipeDraftConsent(digest, originalKey)
        assertEquals(before, db.foodDao().operationCount())

        // A different raw draft is a different approval basis, even when the
        // projected ingredient/product version is otherwise unchanged.
        recreated.prepareRecipeDraftConsent(
            json.replace("\"title\":\"Reis\"", "\"title\":\"Reis neu\""),
            request, facts, states)
        val changed = withTimeout(10_000) {
            recreated.recipeDraftConsentState.first {
                !it.busy && it.review != null &&
                    it.review?.approvalBasisDigest != digest
            }
        }
        assertNull(changed.recordedDigest)
        assertNotEquals(originalKey, changed.sessionId)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
        assertTrue(db.foodDao().observeRecipes().first().isEmpty())
    }

    @Test fun pendingKeySurvivesProcessRecreationButOldUiGestureCannotReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pendingKeys = PrivateRecipeDraftPendingKeyStore(context) { at }
        pendingKeys.discard()
        try {
            val firstVm = LocalDataViewModel(FoodStore(db) { at },
                pendingKeyStore = pendingKeys)
            firstVm.prepareRecipeDraftConsent(json, request, facts, states)
            val first = withTimeout(10_000) {
                firstVm.recipeDraftConsentState.first { !it.busy && it.review != null }
            }
            val digest = requireNotNull(first.review?.approvalBasisDigest)
            val oldGesture = requireNotNull(first.sessionId)
            val persistedKey = pendingKeys.resumeOrCreate(digest)
            assertEquals(1, db.foodDao().operationCount())

            val newVm = LocalDataViewModel(FoodStore(db) { at },
                pendingKeyStore = PrivateRecipeDraftPendingKeyStore(context) { at })
            newVm.prepareRecipeDraftConsent(json, request, facts, states)
            val resumed = withTimeout(10_000) {
                newVm.recipeDraftConsentState.first { !it.busy && it.review != null }
            }
            assertEquals(digest, resumed.review?.approvalBasisDigest)
            assertNotEquals(oldGesture, resumed.sessionId)
            assertEquals(persistedKey, pendingKeys.resumeOrCreate(digest))
            newVm.confirmRecipeDraftConsent(digest, oldGesture)
            assertEquals(1, db.foodDao().operationCount())
            assertNull(newVm.recipeDraftConsentState.value.recordedDigest)

            newVm.confirmRecipeDraftConsent(digest, resumed.sessionId)
            withTimeout(10_000) {
                newVm.recipeDraftConsentState.first { it.recordedDigest == digest }
            }
            assertEquals(persistedKey,
                FoodStore(db) { at }.recordedRecipeDraftV1ConsentKey(digest))
            assertEquals(2, db.foodDao().operationCount())
            assertEquals(0, db.foodDao().consumptionCount())
            assertTrue(db.foodDao().observeRecipes().first().isEmpty())

            val afterRestart = LocalDataViewModel(FoodStore(db) { at },
                pendingKeyStore = PrivateRecipeDraftPendingKeyStore(context) { at })
            afterRestart.prepareRecipeDraftConsent(json, request, facts, states)
            val recorded = withTimeout(10_000) {
                afterRestart.recipeDraftConsentState.first { !it.busy && it.review != null }
            }
            assertEquals(digest, recorded.recordedDigest)
            assertNull(recorded.sessionId)
            assertEquals(2, db.foodDao().operationCount())
        } finally {
            pendingKeys.discard()
        }
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


    @Test fun competingPreviewCannotCancelConsentAlreadyInsideRoomTransaction() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holdTransaction = java.util.concurrent.atomic.AtomicBoolean(false)
        val guardedVm = LocalDataViewModel(FoodStore(db) {
            if (holdTransaction.get()) {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    "synthetic consent transaction was not released"
                }
            }
            at
        })
        guardedVm.prepareRecipeDraftConsent(json, request, facts, states)
        val preview = withTimeout(10_000) {
            guardedVm.recipeDraftConsentState.first { !it.busy && it.review != null }
        }
        val digest = requireNotNull(preview.review?.approvalBasisDigest)
        val session = requireNotNull(preview.sessionId)
        holdTransaction.set(true)
        guardedVm.confirmRecipeDraftConsent(digest, session)
        try {
            assertTrue("consent must reach the Room transaction",
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val revised = json.replace("\"title\":\"Reis\"", "\"title\":\"Reis neu\"")
            guardedVm.prepareRecipeDraftConsent(revised, request, facts, states)
            guardedVm.clearRecipeDraftConsent()
            assertTrue(guardedVm.recipeDraftConsentState.value.busy)
            assertEquals(session, guardedVm.recipeDraftConsentState.value.sessionId)
        } finally {
            release.countDown()
        }
        withTimeout(10_000) {
            guardedVm.recipeDraftConsentState.first { it.recordedDigest == digest }
        }
        assertEquals(2, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
        assertTrue(db.foodDao().observeRecipes().first().isEmpty())
    }
}
