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

    @Test fun unconfirmedLocationIsNotUsableStock() = runBlocking {
        val other = store.addLocation("unconfirmed-location", "Unbestätigt", false)
        val op = "legacy-receipt-location"
        db.foodDao().insertOperation(OperationEntity(op, "RECEIPT", op, at.toEpochMilli(), "legacy-batch"))
        db.foodDao().insertBatch(BatchEntity("legacy-batch", versionId, other, op,
            "2026-11-01", null, null, release = ReleaseStatus.RELEASED))
        db.foodDao().insertMovement(StockMovementEntity("legacy-movement", "legacy-batch", op,
            500L, MovementKind.RECEIPT, null, Source.USER, "Testdaten",
            at.toEpochMilli(), occurredOn = "2026-11-01"))
        assertNull(store.readRecipeRoomStockSnapshot())
    }

    @Test fun futureAcquiredLegacyBatchIsNotCurrentStock() = runBlocking {
        val op = "legacy-future-receipt"
        db.foodDao().insertOperation(OperationEntity(op, "RECEIPT", op, at.toEpochMilli(), "future-batch"))
        db.foodDao().insertBatch(BatchEntity("future-batch", versionId, locationId, op,
            "2026-11-02", null, null, release = ReleaseStatus.RELEASED))
        db.foodDao().insertMovement(StockMovementEntity("future-movement", "future-batch", op,
            500L, MovementKind.RECEIPT, null, Source.USER, "Testdaten",
            at.toEpochMilli(), occurredOn = "2026-11-02"))
        assertNull(store.readRecipeRoomStockSnapshot())
    }
    @Test fun expiredZeroBalanceIsExcludedWithoutHidingCurrentKnownZero() = runBlocking {
        val expiring = store.receive("room-stock-expiring-zero",
            BatchInput(versionId, locationId, 100L, acquiredOn = "2026-11-01",
                bestBefore = "2026-11-01", release = ReleaseStatus.RELEASED), true)
        store.correctStock("room-stock-expiring-zero-count", StockCountInput(
            expiring, 100L, 0L, "2026-11-01", "Bestätigte Nullzählung"), true)

        // On the best-before date, zero is a known amount in a valid batch.
        val today = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(2, today.lines.size)
        assertEquals(0L, today.lines.single { it.identity == "batch:$expiring" }.amountMilli)

        // The following Berlin day must not expose an expired batch as current
        // stock merely because its amount is zero. Valid positive stock remains.
        val nextDay = FoodStore(db) { at.plusSeconds(86_400).toEpochMilli() }
        val next = requireNotNull(nextDay.readRecipeRoomStockSnapshot())
        assertEquals(1, next.lines.size)
        assertEquals("batch:$batchId", next.lines.single().identity)
        assertEquals(2_000L, next.lines.single().amountMilli)
        assertNotEquals(today.revision, next.revision)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun futureRecordedMovementCannotInflateCurrentStockSnapshot() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        val operation = "future-recorded-stock"
        db.foodDao().insertOperation(OperationEntity(operation, "RECEIPT", operation,
            at.plusSeconds(60).toEpochMilli(), batchId))
        db.foodDao().insertMovement(StockMovementEntity("future-recorded-movement", batchId,
            operation, 500L, MovementKind.RECEIPT, null, Source.USER,
            "Legacy future record", at.plusSeconds(60).toEpochMilli(),
            occurredOn = "2026-11-01"))
        assertNull(store.readRecipeRoomStockSnapshot())
        val later = FoodStore(db) { at.plusSeconds(120).toEpochMilli() }
        val current = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(2_500L, current.lines.single().amountMilli)
        assertNotEquals(original.revision, current.revision)
        assertEquals(before + 1, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun futureEffectiveMovementDayFailsClosedUntilBerlinDayArrives() = runBlocking {
        val operation = "future-effective-stock"
        db.foodDao().insertOperation(OperationEntity(operation, "RECEIPT", operation,
            at.toEpochMilli(), batchId))
        db.foodDao().insertMovement(StockMovementEntity("future-effective-movement", batchId,
            operation, 500L, MovementKind.RECEIPT, null, Source.USER,
            "Legacy future day", at.toEpochMilli(), occurredOn = "2026-11-02"))
        assertNull(store.readRecipeRoomStockSnapshot())
        val nextDay = FoodStore(db) { at.plusSeconds(86_400).toEpochMilli() }
        assertEquals(2_500L, requireNotNull(nextDay.readRecipeRoomStockSnapshot())
            .lines.single().amountMilli)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun malformedLegacyMovementDayCannotBeAssumedCurrent() = runBlocking {
        val operation = "malformed-effective-stock"
        db.foodDao().insertOperation(OperationEntity(operation, "RECEIPT", operation,
            at.toEpochMilli(), batchId))
        db.foodDao().insertMovement(StockMovementEntity("malformed-effective-movement",
            batchId, operation, 100L, MovementKind.RECEIPT, null, Source.USER,
            "Legacy malformed date", at.toEpochMilli(), occurredOn = "not-a-date"))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun futureOccurredAtCannotInflateAsOfStockEvenOnSameBerlinDay() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val operation = "future-occurrence-stock"
        db.foodDao().insertOperation(OperationEntity(operation, "RECEIPT", operation,
            at.toEpochMilli(), batchId))
        db.foodDao().insertMovement(StockMovementEntity("future-occurrence-movement",
            batchId, operation, 500L, MovementKind.RECEIPT,
            at.plusSeconds(120).toEpochMilli(), Source.USER,
            "Legacy occurrence after snapshot", at.toEpochMilli(),
            occurredOn = "2026-11-01"))
        assertNull(store.readRecipeRoomStockSnapshot())
        val later = FoodStore(db) { at.plusSeconds(121).toEpochMilli() }
        val current = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(2_500L, current.lines.single().amountMilli)
        assertNotEquals(original.revision, current.revision)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun invalidNegativeOccurredAtFailsClosedInsteadOfAssumingCurrent() = runBlocking {
        val operation = "negative-occurrence-stock"
        db.foodDao().insertOperation(OperationEntity(operation, "RECEIPT", operation,
            at.toEpochMilli(), batchId))
        db.foodDao().insertMovement(StockMovementEntity("negative-occurrence-movement",
            batchId, operation, 100L, MovementKind.RECEIPT, -1L, Source.USER,
            "Legacy invalid occurrence", at.toEpochMilli(),
            occurredOn = "2026-11-01"))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(0, db.foodDao().consumptionCount())
    }


    @Test fun futureOrNegativeConfirmedConsumptionCannotPoisonCurrentRoomStock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val beforeOperations = db.foodDao().operationCount()
        val beforeConsumption = db.foodDao().consumptionCount()
        for ((key, occurredAt) in listOf(
            "future-confirmed-consumption" to at.plusSeconds(30).toEpochMilli(),
            "negative-confirmed-consumption" to -1L
        )) {
            val input = ConsumptionInput(versionId, "2026-11-01", "Mittag", 100L,
                listOf(Allocation(batchId, 100L)), occurredAt = occurredAt)
            assertTrue("Invalid physical occurrence must roll back: $key",
                runCatching { store.consume(key, input, true) }.isFailure)
        }
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(beforeConsumption, db.foodDao().consumptionCount())
        val unchanged = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, unchanged.revision)
        assertEquals(2_000L, unchanged.lines.single().amountMilli)
    }

    @Test fun validPastConsumptionUsesOneRecordedAtAndRemainsIdempotent() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        var clock = at.toEpochMilli()
        val movingClockStore = FoodStore(db) { clock++ }
        val input = ConsumptionInput(versionId, "2026-11-01", "Mittag", 100L,
            listOf(Allocation(batchId, 100L)),
            occurredAt = at.minusSeconds(30).toEpochMilli())
        val first = movingClockStore.consume("confirmed-past-consumption", input, true)
        assertEquals(first, movingClockStore.consume("confirmed-past-consumption", input, true))
        val operation = requireNotNull(db.foodDao().operation("confirmed-past-consumption"))
        val movements = db.foodDao().movements("confirmed-past-consumption")
        assertEquals(1, movements.size)
        assertEquals(at.toEpochMilli(), operation.recordedAt)
        assertEquals(operation.recordedAt, movements.single().recordedAt)
        assertEquals(input.occurredAt, movements.single().occurredAt)
        assertEquals(1, db.foodDao().consumptionCount())
        val current = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(1_900L, current.lines.single().amountMilli)
        assertNotEquals(original.revision, current.revision)
    }
}
