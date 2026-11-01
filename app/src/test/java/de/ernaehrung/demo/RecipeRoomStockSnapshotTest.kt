package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room-backed stock source and transactional recipe-review regression. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeRoomStockSnapshotTest {
    private val at = Instant.parse("2026-11-01T04:00:00Z")
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var versionId: String
    private lateinit var locationId: String
    private lateinit var batchId: String
    private val flags = Gate.Flags(true, false, false)
    private val request = Gate.Request(flags, 2, userConfirmedRecipeSave = true)
    private val states = mapOf("rice" to Gate.State.RAW)
    private val json = """
        {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
         "meta":{"producer":"synthetic","traceId":"room-stock-test",
                 "createdAt":"2026-11-01T04:00:00Z",
                 "inputsSnapshotAt":"2026-11-01T04:00:00Z"},
         "payload":{"draftId":"room-stock","title":"Reis","portionCount":2,
           "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"UNKNOWN",
             "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":false}}],
           "steps":["Kochen"],"flags":{"considerStock":true,
             "considerShopping":false,"considerOffers":false},
           "warnings":[],"status":"DRAFT"}}
    """.trimIndent()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        store = FoodStore(db) { at.toEpochMilli() }
        versionId = store.rememberProduct("room-stock-product",
            ProductInput("Reis", source = Source.USER, quality = Quality.CONFIRMED))
        locationId = store.addLocation("room-stock-location", "Küche", true)
        batchId = store.receive("room-stock-receipt",
            BatchInput(versionId, locationId, 2_000L, acquiredOn = "2026-11-01",
                release = ReleaseStatus.RELEASED), true)
    }
    @After fun close() { db.close() }

    private suspend fun confirmations(): Map<String, Catalog.ConfirmedLine> {
        val v = requireNotNull(db.foodDao().version(versionId))
        return mapOf("rice" to Catalog.ConfirmedLine("rice", v.id, v.revision,
            v.recordedAt, at.toEpochMilli(), BaseUnit.GRAM,
            Gate.Basis.NET, Gate.State.RAW))
    }

    private suspend fun review(snapshot: de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot):
        Gate.Review {
        val context = Gate.typedContext(flags, at,
            Gate.Evidence(snapshot, true), null, null)
        return store.previewRecipeDraftV1(json, request, confirmations(), states,
            context, OffsetDateTime.parse("2026-11-01T04:00:00Z"))
    }

    @Test fun actualReleasedRoomStockBindsAndPreviewDoesNotBook() = runBlocking {
        val before = db.foodDao().operationCount()
        val snapshot = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals("LOCAL_ROOM_STOCK_V1", snapshot.scopeId)
        assertEquals(versionId, snapshot.lines.single().versionId)
        assertEquals(2_000L, snapshot.lines.single().amountMilli)
        assertEquals("g", snapshot.lines.single().unit)
        val preview = review(snapshot)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, preview.status)
        assertNotNull(preview.input)
        // Active context is not yet backed by independent save-action provenance.
        assertNull(preview.approvalBasisDigest)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun interveningRoomMovementInvalidatesPreviouslyBoundPreview() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(original).status)
        store.correctStock("room-stock-count", StockCountInput(
            batchId, 2_000L, 1_000L, "2026-11-01", "Bestätigte Zählung"), true)
        val changed = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertNotEquals(original.revision, changed.revision)
        val stale = review(original)
        assertEquals(Gate.Status.REVIEW_REQUIRED, stale.status)
        assertTrue("ROOM_STOCK_SNAPSHOT_STALE_OR_CHANGED" in stale.gaps)
        assertNull(stale.input)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(changed).status)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun zeroIsExplicitAndCompensatingMovementsStillInvalidateHistory() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        store.correctStock("room-stock-zero", StockCountInput(
            batchId, 2_000L, 0L, "2026-11-01", "Zählung null"), true)
        val zero = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(0L, zero.lines.single().amountMilli)
        assertNotEquals(original.revision, zero.revision)
        store.correctStock("room-stock-restored", StockCountInput(
            batchId, 0L, 2_000L, "2026-11-01", "Bestätigte Korrektur"), true)
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.lines, restored.lines)
        assertNotEquals(original.revision, restored.revision)
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
    }

    @Test fun unknownReleaseOfPositiveStockFailsClosedNotAsEmptySnapshot() = runBlocking {
        store.receive("room-stock-unchecked",
            BatchInput(versionId, locationId, 500L, acquiredOn = "2026-11-01",
                release = ReleaseStatus.UNCHECKED), true)
        assertNull(store.readRecipeRoomStockSnapshot())
    }

    @Test fun bestBeforeExpiryRequiresReviewWhileCurrentDayIsStillValid() = runBlocking {
        store.receive("room-stock-current-best-before",
            BatchInput(versionId, locationId, 100L, acquiredOn = "2026-11-01",
                bestBefore = "2026-11-01", release = ReleaseStatus.RELEASED), true)
        assertNotNull(store.readRecipeRoomStockSnapshot())
        store.receive("room-stock-expired-best-before",
            BatchInput(versionId, locationId, 100L, acquiredOn = "2026-11-01",
                bestBefore = "2026-10-31", release = ReleaseStatus.RELEASED), true)
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(0, db.foodDao().consumptionCount())
    }
}
