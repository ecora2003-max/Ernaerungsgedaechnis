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

    private suspend fun review(
        snapshot: de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot,
        reviewStore: FoodStore = store
    ): Gate.Review {
        val context = Gate.typedContext(flags, at,
            Gate.Evidence(snapshot, true), null, null)
        return reviewStore.previewRecipeDraftV1(json, request, confirmations(), states,
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

    @Test fun duplicateOrForeignReceiptCannotDoubleCreditRecipeStock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val beforeOperations = db.foodDao().operationCount()
        val beforeConsumption = db.foodDao().consumptionCount()
        val origin = requireNotNull(db.foodDao().batch(batchId)).originOperationId
        val duplicateId = "legacy-duplicate-receipt-credit"
        val sql = db.openHelper.writableDatabase

        // A second physical credit under the original confirmed operation
        // must not inflate the available stock or pass transactional review.
        db.foodDao().insertMovement(StockMovementEntity(
            duplicateId, batchId, origin, 2_000L, MovementKind.RECEIPT,
            null, Source.USER, "Duplicate legacy receipt",
            at.toEpochMilli(), occurredOn = "2026-11-01"))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
        sql.execSQL("DELETE FROM stock_movements WHERE id = ?", arrayOf(duplicateId))
        assertEquals(original, requireNotNull(store.readRecipeRoomStockSnapshot()))

        // Even a separately persisted RECEIPT operation cannot re-create
        // an existing batch: its original creation identity is immutable.
        val foreignOperation = "legacy-second-receipt-operation"
        val foreignMovement = "legacy-second-receipt-movement"
        db.foodDao().insertOperation(OperationEntity(
            foreignOperation, "RECEIPT", foreignOperation, at.toEpochMilli(), batchId))
        db.foodDao().insertMovement(StockMovementEntity(
            foreignMovement, batchId, foreignOperation, 500L,
            MovementKind.RECEIPT, null, Source.USER, "Foreign receipt credit",
            at.toEpochMilli(), occurredOn = "2026-11-01"))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
        sql.execSQL("DELETE FROM stock_movements WHERE id = ?", arrayOf(foreignMovement))
        sql.execSQL("DELETE FROM operations WHERE id = ?", arrayOf(foreignOperation))
        assertEquals(original, requireNotNull(store.readRecipeRoomStockSnapshot()))
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(beforeConsumption, db.foodDao().consumptionCount())
    }

    @Test fun orphanBatchHiddenByEitherPlanningJoinFailsClosedWithoutBooking() = runBlocking {
        val second = store.receive("orphan-join-receipt",
            BatchInput(versionId, locationId, 100L, acquiredOn = "2026-11-01",
                release = ReleaseStatus.RELEASED), true)
        val complete = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(2, complete.lines.size)
        val operations = db.foodDao().operationCount()
        val sql = db.openHelper.writableDatabase
        // A damaged legacy batch with no movement is invisible to the
        // planning INNER JOIN; a valid first batch must not mask the omission.
        sql.execSQL("DELETE FROM stock_movements WHERE batchId = ?", arrayOf(second))

        fun rewriteBatchReference(column: String, value: String) {
            sql.execSQL("PRAGMA foreign_keys=OFF")
            try {
                sql.execSQL("UPDATE batches SET $column = ? WHERE id = ?",
                    arrayOf(value, second))
            } finally {
                sql.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        // Both joins independently matter: location and product version.
        rewriteBatchReference("locationId", "missing-legacy-location")
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(complete).status)
        rewriteBatchReference("locationId", locationId)
        val restoredLocation = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(2, restoredLocation.lines.size)
        assertEquals(2_000L,
            restoredLocation.lines.single { it.identity == "batch:$batchId" }.amountMilli)

        rewriteBatchReference("versionId", "missing-legacy-version")
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(complete).status)
        rewriteBatchReference("versionId", versionId)
        val restoredVersion = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(restoredLocation, restoredVersion)
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun receiptOperationWithConsumptionEvidenceFailsClosedUntilRepaired() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val beforeOperations = db.foodDao().operationCount()
        val receiptOperation = requireNotNull(db.foodDao().batch(batchId)).originOperationId
        val bogusConsumption = "legacy-consumption-on-receipt"

        // Simulate an inconsistent legacy import: a confirmed consumption
        // event points to the operation that actually created the batch.
        db.foodDao().insertConsumption(ConsumptionEntity(
            bogusConsumption, receiptOperation, versionId, "2026-11-01",
            at.toEpochMilli(), "UNKNOWN", 100L, Source.USER,
            Quality.CONFIRMED, null))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())

        // Repair only the contradictory legacy evidence; the read path must
        // not create another purchase, stock event or consumption booking.
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM consumption_events WHERE id = ?", arrayOf(bogusConsumption))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, restored.revision)
        assertEquals(original.lines, restored.lines)
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun extraConsumptionAllocationWithoutMovementInvalidatesWholeStockSnapshot() = runBlocking {
        val otherBatch = store.receive("allocation-orphan-other-receipt",
            BatchInput(versionId, locationId, 300L, acquiredOn = "2026-11-01",
                release = ReleaseStatus.RELEASED), true)
        val later = FoodStore(db) { at.plusSeconds(60).toEpochMilli() }
        val consumptionId = later.consume("allocation-orphan-consumption",
            ConsumptionInput(versionId, "2026-11-01", "Mittag", 100L,
                listOf(Allocation(batchId, 100L)),
                occurredAt = at.plusSeconds(30).toEpochMilli()), true)
        val reader = FoodStore(db) { at.plusSeconds(61).toEpochMilli() }
        val complete = requireNotNull(reader.readRecipeRoomStockSnapshot())
        assertEquals(2, complete.lines.size)
        val beforeOperations = db.foodDao().operationCount()
        val beforeConsumption = db.foodDao().consumptionCount()

        // Corrupt only the imported consumption ledger: its new allocation
        // has no corresponding physical stock movement. Its total still
        // agrees with the consumption event, so amount-only checks miss it.
        db.foodDao().insertAllocation(ConsumptionAllocationEntity(
            consumptionId, otherBatch, 50L))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_events SET amount = ? WHERE id = ?",
            arrayOf(150L, consumptionId))
        assertNull(reader.readRecipeRoomStockSnapshot())
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(beforeConsumption, db.foodDao().consumptionCount())

        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM consumption_allocations WHERE consumptionId = ? AND batchId = ?",
            arrayOf(consumptionId, otherBatch))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_events SET amount = ? WHERE id = ?",
            arrayOf(100L, consumptionId))
        val restored = requireNotNull(reader.readRecipeRoomStockSnapshot())
        assertEquals(complete, restored)
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(beforeConsumption, db.foodDao().consumptionCount())
    }

    @Test fun duplicateConsumptionMovementForOneAllocationFailsClosed() = runBlocking {
        val later = FoodStore(db) { at.plusSeconds(60).toEpochMilli() }
        later.consume("allocation-duplicate-consumption",
            ConsumptionInput(versionId, "2026-11-01", "Mittag", 100L,
                listOf(Allocation(batchId, 100L)),
                occurredAt = at.plusSeconds(30).toEpochMilli()), true)
        val reader = FoodStore(db) { at.plusSeconds(61).toEpochMilli() }
        val complete = requireNotNull(reader.readRecipeRoomStockSnapshot())
        val beforeOperations = db.foodDao().operationCount()
        val beforeConsumption = db.foodDao().consumptionCount()
        val duplicateId = "legacy-duplicate-consumption-movement"
        db.foodDao().insertMovement(StockMovementEntity(
            duplicateId, batchId, "allocation-duplicate-consumption",
            -100L, MovementKind.CONSUMPTION, at.plusSeconds(30).toEpochMilli(),
            Source.USER, "Duplicate legacy movement", at.plusSeconds(60).toEpochMilli(),
            occurredOn = "2026-11-01"))
        assertNull(reader.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM stock_movements WHERE id = ?", arrayOf(duplicateId))
        assertEquals(complete, requireNotNull(reader.readRecipeRoomStockSnapshot()))
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(beforeConsumption, db.foodDao().consumptionCount())
    }

    @Test fun stockReviewExpiresAtExactMillisecondNotRoundedSeconds() = runBlocking {
        val snapshot = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        val atLimit = FoodStore(db) { at.plusMillis(300_000).toEpochMilli() }
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,
            review(snapshot, atLimit).status)
        val justExpired = FoodStore(db) { at.plusMillis(300_001).toEpochMilli() }
        val stale = review(snapshot, justExpired)
        assertEquals(Gate.Status.REVIEW_REQUIRED, stale.status)
        assertTrue("ROOM_STOCK_SNAPSHOT_STALE_OR_CHANGED" in stale.gaps)
        assertNull(stale.input)
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
    @Test fun movementBeforeBatchAcquisitionDayCannotAuthorizeLaterRecipeStock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val beforeOperations = db.foodDao().operationCount()
        val later = FoodStore(db) { at.plusSeconds(86_400).toEpochMilli() }
        val receiptOperation = requireNotNull(db.foodDao().batch(batchId)).originOperationId

        // The receipt's persisted movement occurred on 1 November. A legacy
        // acquisition date of 2 November cannot make that physical movement
        // legitimate merely because the snapshot is captured on 2 November.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET acquiredOn = ? WHERE id = ?",
            arrayOf("2026-11-02", batchId))
        assertNull(later.readRecipeRoomStockSnapshot())

        // A legacy movement can have an instant but no calendar day. That
        // instant must still be checked against the acquisition's Berlin day.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredOn = NULL, occurredAt = ? WHERE operationId = ?",
            arrayOf(at.toEpochMilli(), receiptOperation))
        assertNull(later.readRecipeRoomStockSnapshot())

        // Repair the inconsistent history without writing another Ist-event.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET acquiredOn = ? WHERE id = ?",
            arrayOf("2026-11-01", batchId))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredOn = ?, occurredAt = NULL WHERE operationId = ?",
            arrayOf("2026-11-01", receiptOperation))
        val recovered = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(original.lines, recovered.lines)
        assertEquals(original.revision, recovered.revision)
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
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

    @Test fun futureProductVersionEvidenceCannotAuthorizeEarlierRoomStock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(60).toEpochMilli(), versionId))
        // A valid batch and movement do not make a product version known
        // before its recorded creation time. No stock or recipe is booked.
        assertNull(store.readRecipeRoomStockSnapshot())
        val later = FoodStore(db) { at.plusSeconds(61).toEpochMilli() }
        // Advancing the snapshot clock must not turn a receipt predating its
        // product version into legitimate inventory.
        assertNull(later.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(at.toEpochMilli(), versionId))
        val recovered = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(original.lines, recovered.lines)
        assertEquals(original.revision, recovered.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun oneImpossibleBatchOriginInvalidatesWholeStockContext() = runBlocking {
        val secondVersion = store.rememberProduct("second-stock-version",
            ProductInput("Linsen", source = Source.USER, quality = Quality.CONFIRMED))
        store.receive("second-stock-receipt",
            BatchInput(secondVersion, locationId, 500L, acquiredOn = "2026-11-01",
                release = ReleaseStatus.RELEASED), true)
        val later = FoodStore(db) { at.plusSeconds(90).toEpochMilli() }
        val valid = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(2, valid.lines.size)
        val before = db.foodDao().operationCount()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(30).toEpochMilli(), secondVersion))
        // The first batch is valid, but partial stock is not a safe context.
        assertNull(later.readRecipeRoomStockSnapshot())
        assertEquals(before, db.foodDao().operationCount())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(at.toEpochMilli(), secondVersion))
        val restored = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(valid.revision, restored.revision)
        assertEquals(valid.lines, restored.lines)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun wrongOriginResultCannotAuthorizeRoomRecipeStock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        val origin = requireNotNull(db.foodDao().batch(batchId)).originOperationId
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET resultId = ? WHERE id = ?",
            arrayOf("unrelated-batch", origin))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())

        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET resultId = ? WHERE id = ?",
            arrayOf(batchId, origin))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, restored.revision)
        assertEquals(original.lines, restored.lines)
    }

    @Test fun nonCreatingOriginKindCannotAuthorizeRoomRecipeStock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        val origin = requireNotNull(db.foodDao().batch(batchId)).originOperationId
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET kind = ? WHERE id = ?",
            arrayOf("CONSUMPTION", origin))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())

        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET kind = ? WHERE id = ?",
            arrayOf("RECEIPT", origin))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, restored.revision)
        assertEquals(original.lines, restored.lines)
    }

    @Test fun futureOriginOperationCannotAuthorizeEarlierRoomStock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        val origin = requireNotNull(db.foodDao().batch(batchId)).originOperationId
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(60).toEpochMilli(), origin))
        // The movement's old timestamp cannot override a future-dated
        // originating receipt operation in the same physical stock snapshot.
        assertNull(store.readRecipeRoomStockSnapshot())
        val later = FoodStore(db) { at.plusSeconds(61).toEpochMilli() }
        // The origin's movement is still earlier than its author. A later
        // snapshot cannot repair this inconsistent persisted chronology.
        assertNull(later.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.toEpochMilli(), origin))
        val recovered = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(original.lines, recovered.lines)
        assertEquals(original.revision, recovered.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun futureMovementAuthorOperationCannotAuthorizeCurrentStock() = runBlocking {
        store.correctStock("movement-author-future", StockCountInput(
            batchId, 2_000L, 1_500L, "2026-11-01", "Bestätigte Zählung"), true)
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        assertEquals(1_500L, original.lines.single().amountMilli)
        // The movement row remains backdated, but its separate author operation
        // was not recorded when this Room snapshot was captured.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(60).toEpochMilli(), "movement-author-future"))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
        val later = FoodStore(db) { at.plusSeconds(61).toEpochMilli() }
        // Passing the future author timestamp is not enough: the movement
        // still claims it was recorded before its author existed.
        assertNull(later.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.toEpochMilli(), "movement-author-future"))
        val recovered = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(original.lines, recovered.lines)
        assertEquals(original.revision, recovered.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun movementRecordedBeforeAuthorFailsClosedUntilChronologyRepaired() = runBlocking {
        store.correctStock("movement-author-chronology", StockCountInput(
            batchId, 2_000L, 1_700L, "2026-11-01", "Bestätigte Zählung"), true)
        val before = db.foodDao().operationCount()
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(1_700L, original.lines.single().amountMilli)
        val authorTime = at.plusSeconds(30).toEpochMilli()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(authorTime, "movement-author-chronology"))
        val later = FoodStore(db) { at.plusSeconds(61).toEpochMilli() }
        assertNull(later.readRecipeRoomStockSnapshot())
        // A consistent later recording is valid; no new FoodStore event or
        // consumption may be created by repairing read-only evidence.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET recordedAt = ? WHERE operationId = ?",
            arrayOf(authorTime, "movement-author-chronology"))
        val restored = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(original.lines, restored.lines)
        assertNotEquals(original.revision, restored.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun invalidMovementAuthorOperationTimeFailsClosedUntilRepaired() = runBlocking {
        store.correctStock("movement-author-invalid", StockCountInput(
            batchId, 2_000L, 1_000L, "2026-11-01", "Bestätigte Zählung"), true)
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = -1 WHERE id = ?",
            arrayOf("movement-author-invalid"))
        assertNull(store.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.toEpochMilli(), "movement-author-invalid"))
        val recovered = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, recovered.revision)
        assertEquals(original.lines, recovered.lines)
        assertEquals(before, db.foodDao().operationCount())
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
        // Even after the calendar day arrives, a confirmed operation from the
        // previous day cannot authorize a physical event in its future.
        assertNull(nextDay.readRecipeRoomStockSnapshot())
        val operations = db.foodDao().operationCount()
        // A genuinely next-day author and movement record can authorize the
        // same physical day without inventing any additional stock booking.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(86_400).toEpochMilli(), operation))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET recordedAt = ? WHERE operationId = ?",
            arrayOf(at.plusSeconds(86_400).toEpochMilli(), operation))
        assertEquals(2_500L, requireNotNull(nextDay.readRecipeRoomStockSnapshot())
            .lines.single().amountMilli)
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun dayOnlyReceiptAfterItsAuthorRemainsInvalidAtLaterSnapshotUntilRepaired() = runBlocking {
        val later = FoodStore(db) { at.plusSeconds(2 * 86_400).toEpochMilli() }
        val original = requireNotNull(later.readRecipeRoomStockSnapshot())
        val operations = db.foodDao().operationCount()
        val receipt = requireNotNull(db.foodDao().batch(batchId)).originOperationId
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = NULL, occurredOn = ? WHERE operationId = ?",
            arrayOf("2026-11-02", receipt))
        assertNull(later.readRecipeRoomStockSnapshot())
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredOn = ? WHERE operationId = ?",
            arrayOf("2026-11-01", receipt))
        val restored = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, restored.revision)
        assertEquals(original.lines, restored.lines)
        assertEquals(operations, db.foodDao().operationCount())
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
        // The physical event was recorded as confirmed before it happened.
        // Advancing the snapshot clock must not repair that contradiction.
        assertNull(later.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = ? WHERE operationId = ?",
            arrayOf(at.toEpochMilli(), operation))
        val current = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(2_500L, current.lines.single().amountMilli)
        assertNotEquals(original.revision, current.revision)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun consumptionOccurrenceAfterItsConfirmedOperationRemainsInvalid() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val recordedAt = at.plusSeconds(60).toEpochMilli()
        val actualAt = at.plusSeconds(30).toEpochMilli()
        val key = "late-occurrence-consumption"
        FoodStore(db) { recordedAt }.consume(key,
            ConsumptionInput(versionId, "2026-11-01", "Snack", 100L,
                listOf(Allocation(batchId, 100L)), occurredAt = actualAt), true)
        val later = FoodStore(db) { at.plusSeconds(120).toEpochMilli() }
        val valid = requireNotNull(later.readRecipeRoomStockSnapshot())
        val operations = db.foodDao().operationCount()
        assertEquals(1_900L, valid.lines.single().amountMilli)
        assertEquals(1, db.foodDao().consumptionCount())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = ? WHERE operationId = ?",
            arrayOf(at.plusSeconds(90).toEpochMilli(), key))
        assertNull(later.readRecipeRoomStockSnapshot())
        val nextDay = FoodStore(db) { at.plusSeconds(86_400).toEpochMilli() }
        assertNull(nextDay.readRecipeRoomStockSnapshot())
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = ? WHERE operationId = ?",
            arrayOf(actualAt, key))
        val restored = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(valid.revision, restored.revision)
        assertEquals(valid.lines, restored.lines)
        assertNotEquals(original.revision, restored.revision)
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())
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
        // Receipt is recorded at 'at'; occurrence is later, but precedes the single write instant.
        var clock = at.plusSeconds(60).toEpochMilli()
        val movingClockStore = FoodStore(db) { clock++ }
        val input = ConsumptionInput(versionId, "2026-11-01", "Mittag", 100L,
            listOf(Allocation(batchId, 100L)),
            occurredAt = at.plusSeconds(30).toEpochMilli())
        val first = movingClockStore.consume("confirmed-past-consumption", input, true)
        assertEquals(first, movingClockStore.consume("confirmed-past-consumption", input, true))
        val operation = requireNotNull(db.foodDao().operation("confirmed-past-consumption"))
        val movements = db.foodDao().movements("confirmed-past-consumption")
        assertEquals(1, movements.size)
        assertEquals(at.plusSeconds(60).toEpochMilli(), operation.recordedAt)
        assertEquals(operation.recordedAt, movements.single().recordedAt)
        assertEquals(input.occurredAt, movements.single().occurredAt)
        assertEquals(1, db.foodDao().consumptionCount())
        val current = requireNotNull(FoodStore(db) { at.plusSeconds(61).toEpochMilli() }.readRecipeRoomStockSnapshot())
        assertEquals(1_900L, current.lines.single().amountMilli)
        assertNotEquals(original.revision, current.revision)
    }

    @Test fun receiptCrossingBerlinMidnightKeepsOnePhysicalTimestampAndRetry() = runBlocking {
        // 22:59:59.999 UTC is 23:59:59.999 in Berlin on 1 November.
        // The second wall-clock read would already be the following Berlin day.
        val instant = Instant.parse("2026-11-01T22:59:59.999Z").toEpochMilli()
        var clock = instant
        val movingClockStore = FoodStore(db) { clock++ }
        val input = BatchInput(versionId, locationId, 250L, acquiredOn = "2026-11-01",
            release = ReleaseStatus.RELEASED)
        val before = db.foodDao().operationCount()
        val batch = movingClockStore.receive("receipt-midnight-boundary", input, true)
        assertEquals(instant + 1L, clock)
        assertEquals(batch, movingClockStore.receive("receipt-midnight-boundary", input, true))
        assertEquals(instant + 1L, clock)
        val operation = requireNotNull(db.foodDao().operation("receipt-midnight-boundary"))
        val movement = db.foodDao().movements("receipt-midnight-boundary").single()
        assertEquals(instant, operation.recordedAt)
        assertEquals(operation.recordedAt, movement.recordedAt)
        assertEquals("2026-11-01", movement.occurredOn)
        assertEquals(before + 1, db.foodDao().operationCount())
        val asOfReceipt = FoodStore(db) { instant }
        val snapshot = requireNotNull(asOfReceipt.readRecipeRoomStockSnapshot())
        assertEquals(250L, snapshot.lines.single { it.identity == "batch:$batch" }.amountMilli)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun futureReceiptDayCannotBeAuthorizedByAdvancingClock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        var clock = Instant.parse("2026-11-01T22:59:59.999Z").toEpochMilli()
        val movingClockStore = FoodStore(db) { clock++ }
        val input = BatchInput(versionId, locationId, 250L, acquiredOn = "2026-11-02",
            release = ReleaseStatus.RELEASED)
        assertTrue(runCatching {
            movingClockStore.receive("receipt-future-day", input, true)
        }.isFailure)
        assertNull(db.foodDao().operation("receipt-future-day"))
        assertEquals(before, db.foodDao().operationCount())
        val current = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, current.revision)
        assertEquals(original.lines, current.lines)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun productRevisionKeepsOneRecordedAtAcrossBerlinMidnightAndRetry() = runBlocking {
        val instant = Instant.parse("2026-11-01T22:59:59.999Z").toEpochMilli()
        var clock = instant
        val movingClockStore = FoodStore(db) { clock++ }
        val input = ProductInput("Haferflocken", source = Source.USER, quality = Quality.CONFIRMED)
        val before = db.foodDao().operationCount()
        val versionId = movingClockStore.rememberProduct("product-revision-midnight", input)
        val version = requireNotNull(db.foodDao().version(versionId))
        val operation = requireNotNull(db.foodDao().operation("product-revision-midnight"))
        assertEquals(instant, version.recordedAt)
        assertEquals(version.recordedAt, operation.recordedAt)
        assertEquals(instant + 1L, clock)
        assertEquals(versionId, movingClockStore.rememberProduct("product-revision-midnight", input))
        assertEquals(instant + 1L, clock)
        assertEquals(before + 1, db.foodDao().operationCount())
    }

    @Test fun negativeProductEventTimeRollsBackWithoutCreatingRevision() = runBlocking {
        val before = db.foodDao().operationCount()
        val invalidClockStore = FoodStore(db) { -1L }
        val result = runCatching {
            invalidClockStore.rememberProduct("product-negative-timestamp",
                ProductInput("Unzulässiger Zeitbeleg", quality = Quality.CONFIRMED))
        }
        assertTrue(result.isFailure)
        assertNull(db.foodDao().operation("product-negative-timestamp"))
        assertEquals(before, db.foodDao().operationCount())
        assertNotNull(store.readRecipeRoomStockSnapshot())
    }

    @Test fun confirmedConsumptionRejectsMismatchedBerlinOccurrenceDayAtomically() = runBlocking {
        val before = requireNotNull(store.readRecipeRoomStockSnapshot())
        val beforeOperations = db.foodDao().operationCount()
        val recorded = Instant.parse("2026-11-02T00:10:00Z").toEpochMilli()
        // UTC still says 1 November, but Berlin already says 2 November.
        val occurred = Instant.parse("2026-11-01T23:30:00Z").toEpochMilli()
        val later = FoodStore(db) { recorded }
        val input = ConsumptionInput(versionId, "2026-11-01", "Abendessen", 100L,
            listOf(Allocation(batchId, 100L)), occurredAt = occurred)
        assertTrue(runCatching {
            later.consume("mismatched-berlin-consumption", input, true)
        }.isFailure)
        assertNull(db.foodDao().operation("mismatched-berlin-consumption"))
        assertEquals(beforeOperations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
        val unchanged = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(before.revision, unchanged.revision)
        assertEquals(before.lines, unchanged.lines)
    }

    @Test fun confirmedConsumptionAcceptsMatchingBerlinDayAcrossUtcMidnightAndReplays() = runBlocking {
        val recorded = Instant.parse("2026-11-02T00:10:00Z").toEpochMilli()
        val occurred = Instant.parse("2026-11-01T23:30:00Z").toEpochMilli()
        val later = FoodStore(db) { recorded }
        val input = ConsumptionInput(versionId, "2026-11-02", "Frühstück", 100L,
            listOf(Allocation(batchId, 100L)), occurredAt = occurred)
        val before = db.foodDao().operationCount()
        val result = later.consume("valid-berlin-consumption", input, true)
        assertEquals(result, later.consume("valid-berlin-consumption", input, true))
        val movement = db.foodDao().movements("valid-berlin-consumption").single()
        assertEquals("2026-11-02", movement.occurredOn)
        assertEquals(occurred, movement.occurredAt)
        assertEquals(recorded, movement.recordedAt)
        assertEquals(before + 1, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())
        assertEquals(1_900L, requireNotNull(later.readRecipeRoomStockSnapshot())
            .lines.single().amountMilli)
    }

    @Test fun contradictoryLegacyMovementDayCannotEnterRecipeStockContext() = runBlocking {
        val recorded = Instant.parse("2026-11-02T00:10:00Z").toEpochMilli()
        val occurred = Instant.parse("2026-11-01T23:30:00Z").toEpochMilli()
        val operation = "legacy-contradictory-berlin-day"
        db.foodDao().insertOperation(OperationEntity(operation, "RECEIPT",
            operation, recorded, batchId))
        db.foodDao().insertMovement(StockMovementEntity("legacy-contradictory-movement",
            batchId, operation, 100L, MovementKind.RECEIPT, occurred,
            Source.USER, "Contradictory legacy physical date", recorded,
            occurredOn = "2026-11-01"))
        val later = FoodStore(db) { recorded }
        assertNull(later.readRecipeRoomStockSnapshot())
        assertEquals(0, db.foodDao().consumptionCount())
        assertEquals(0, db.foodDao().movements(operation).count { it.delta < 0L })
    }
    @Test fun unrelatedRecipeOperationCannotAuthorAReceiptMovement() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        val operation = "forged-recipe-receipt-movement"
        db.foodDao().insertOperation(OperationEntity(operation, "RECIPE", operation,
            at.toEpochMilli(), batchId))
        db.foodDao().insertMovement(StockMovementEntity("forged-recipe-receipt-row",
            batchId, operation, 100L, MovementKind.RECEIPT, null, Source.USER,
            "Legacy movement with wrong operation kind", at.toEpochMilli(),
            occurredOn = "2026-11-01"))
        assertNull(store.readRecipeRoomStockSnapshot())
        val blocked = review(original)
        assertEquals(Gate.Status.REVIEW_REQUIRED, blocked.status)
        assertTrue("ROOM_STOCK_SNAPSHOT_STALE_OR_CHANGED" in blocked.gaps)
        assertNull(blocked.input)
        assertEquals(before + 1, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())

        // Repairing only the independent author evidence restores the projection.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET kind = 'RECEIPT' WHERE id = ?", arrayOf(operation))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(2_100L, restored.lines.single().amountMilli)
        assertNotEquals(original.revision, restored.revision)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(restored).status)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun wrongReceiptResultAndMovementDirectionFailClosed() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val operation = "forged-receipt-result"
        db.foodDao().insertOperation(OperationEntity(operation, "RECEIPT", operation,
            at.toEpochMilli(), "other-batch"))
        db.foodDao().insertMovement(StockMovementEntity("forged-receipt-result-row",
            batchId, operation, 100L, MovementKind.RECEIPT, null, Source.USER,
            "Legacy movement with wrong result scope", at.toEpochMilli(),
            occurredOn = "2026-11-01"))
        assertNull(store.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET resultId = ? WHERE id = ?",
            arrayOf(batchId, operation))
        assertEquals(2_100L, requireNotNull(store.readRecipeRoomStockSnapshot())
            .lines.single().amountMilli)

        // A receipt cannot subtract stock even if its operation and batch agree.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET delta = -100 WHERE id = ?",
            arrayOf("forged-receipt-result-row"))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(0, db.foodDao().consumptionCount())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET delta = 100 WHERE id = ?",
            arrayOf("forged-receipt-result-row"))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(2_100L, restored.lines.single().amountMilli)
        assertNotEquals(original.revision, restored.revision)
        assertEquals(0, db.foodDao().consumptionCount())
    }


    @Test fun movementAuthorBeforeBatchCreationFailsClosedEvenAfterClockAdvances() = runBlocking {
        store.correctStock("stock-before-origin-author", StockCountInput(
            batchId, 2_000L, 1_400L, "2026-11-01", "Bestätigte Zählung"), true)
        val valid = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        assertEquals(1_400L, valid.lines.single().amountMilli)

        // The correction movement was recorded at the snapshot instant, but
        // its independently stored author claims to predate the batch receipt.
        // A later as-of instant cannot legitimize this impossible history.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.minusSeconds(60).toEpochMilli(), "stock-before-origin-author"))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(valid).status)
        val later = FoodStore(db) { at.plusSeconds(120).toEpochMilli() }
        assertNull(later.readRecipeRoomStockSnapshot())

        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.toEpochMilli(), "stock-before-origin-author"))
        val restored = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(valid.lines, restored.lines)
        assertEquals(valid.revision, restored.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun physicalConsumptionBeforeBatchCreationFailsClosedUntilEvidenceRepaired() = runBlocking {
        val occurredAt = at.plusSeconds(30).toEpochMilli()
        val recordedAt = at.plusSeconds(60).toEpochMilli()
        val key = "stock-physical-before-origin"
        val confirmed = FoodStore(db) { recordedAt }
        val input = ConsumptionInput(versionId, "2026-11-01", "Snack", 100L,
            listOf(Allocation(batchId, 100L)), occurredAt = occurredAt)
        confirmed.consume(key, input, true)
        val later = FoodStore(db) { at.plusSeconds(120).toEpochMilli() }
        val valid = requireNotNull(later.readRecipeRoomStockSnapshot())
        val operations = db.foodDao().operationCount()
        assertEquals(1_900L, valid.lines.single().amountMilli)
        assertEquals(1, db.foodDao().consumptionCount())

        // A legacy movement can claim an occurrence before the batch receipt
        // even though its author and recording timestamps are both valid.
        // Advancing the review clock must not legitimize that physical history.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = ? WHERE operationId = ?",
            arrayOf(at.minusSeconds(30).toEpochMilli(), key))
        assertNull(later.readRecipeRoomStockSnapshot())
        val nextDay = FoodStore(db) { at.plusSeconds(86_400).toEpochMilli() }
        assertNull(nextDay.readRecipeRoomStockSnapshot())
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())

        // Repairing the stored occurrence restores the exact prior revision;
        // no new physical booking or replay is performed by the reader.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = ? WHERE operationId = ?",
            arrayOf(occurredAt, key))
        val restored = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(valid.revision, restored.revision)
        assertEquals(valid.lines, restored.lines)
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())
    }

    @Test fun dayOnlyLegacyCorrectionBeforeBatchOriginFailsClosedUntilRepaired() = runBlocking {
        store.correctStock("legacy-day-only-correction", StockCountInput(
            batchId, 2_000L, 1_500L, "2026-11-01", "Bestätigte Zählung"), true)
        val valid = requireNotNull(store.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        assertEquals(1_500L, valid.lines.single().amountMilli)

        // A legacy movement has no physical instant, only an independently
        // stored Berlin day preceding the receipt that created its batch.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = NULL, occurredOn = ? WHERE operationId = ?",
            arrayOf("2026-10-31", "legacy-day-only-correction"))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(valid).status)
        val nextDay = FoodStore(db) { at.plusSeconds(86_400).toEpochMilli() }
        assertNull(nextDay.readRecipeRoomStockSnapshot())
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())

        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredOn = ? WHERE operationId = ?",
            arrayOf("2026-11-01", "legacy-day-only-correction"))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(valid.lines, restored.lines)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun legacyConsumptionFallbackDayBeforeBatchOriginFailsClosed() = runBlocking {
        val occurredAt = at.plusSeconds(30).toEpochMilli()
        val confirmed = FoodStore(db) { at.plusSeconds(60).toEpochMilli() }
        val key = "legacy-consumption-fallback-day"
        confirmed.consume(key, ConsumptionInput(versionId, "2026-11-01", "Snack",
            100L, listOf(Allocation(batchId, 100L)), occurredAt = occurredAt), true)
        val later = FoodStore(db) { at.plusSeconds(61).toEpochMilli() }
        val valid = requireNotNull(later.readRecipeRoomStockSnapshot())
        val before = db.foodDao().operationCount()
        assertEquals(1_900L, valid.lines.single().amountMilli)

        // The DAO falls back to the linked consumption date if the movement's
        // own day is absent. That fallback must respect batch chronology too.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = NULL, occurredOn = NULL WHERE operationId = ?",
            arrayOf(key))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_events SET date = ? WHERE operationId = ?",
            arrayOf("2026-10-31", key))
        assertNull(later.readRecipeRoomStockSnapshot())
        assertNull(FoodStore(db) { at.plusSeconds(86_400).toEpochMilli() }
            .readRecipeRoomStockSnapshot())
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())

        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_events SET date = ? WHERE operationId = ?",
            arrayOf("2026-11-01", key))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET occurredAt = ?, occurredOn = ? WHERE operationId = ?",
            arrayOf(occurredAt, "2026-11-01", key))
        val restored = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertEquals(valid.revision, restored.revision)
        assertEquals(valid.lines, restored.lines)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())
    }


    @Test fun futureBatchOpeningCannotAuthorizeEarlierRecipeStock() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val operations = db.foodDao().operationCount()
        val openedAt = at.plusSeconds(60).toEpochMilli()

        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET openedAt = ? WHERE id = ?",
            arrayOf(openedAt, batchId))
        assertNull(store.readRecipeRoomStockSnapshot())
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
        val later = FoodStore(db) { at.plusSeconds(61).toEpochMilli() }
        val laterSnapshot = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertNotEquals(original.revision, laterSnapshot.revision)

        // A physically impossible opening before the batch was created stays
        // invalid even when the review clock advances.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET openedAt = ? WHERE id = ?",
            arrayOf(at.minusSeconds(1).toEpochMilli(), batchId))
        assertNull(later.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET openedAt = NULL WHERE id = ?", arrayOf(batchId))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, restored.revision)
        assertEquals(original.lines, restored.lines)
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun invalidThawTimestampFailsClosedUntilEvidenceIsRepaired() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val operations = db.foodDao().operationCount()
        val later = FoodStore(db) { at.plusSeconds(30).toEpochMilli() }

        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET thawedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(20).toEpochMilli(), batchId))
        assertNull(store.readRecipeRoomStockSnapshot())
        val validLater = requireNotNull(later.readRecipeRoomStockSnapshot())
        assertNotEquals(original.revision, validLater.revision)

        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET thawedAt = ? WHERE id = ?", arrayOf(-1L, batchId))
        assertNull(later.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET thawedAt = NULL WHERE id = ?", arrayOf(batchId))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, restored.revision)
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }



    @Test fun openingAndThawingBeforeAcquisitionDayRemainInvalidAtLaterSnapshots() = runBlocking {
        val original = requireNotNull(store.readRecipeRoomStockSnapshot())
        val operations = db.foodDao().operationCount()
        val later = FoodStore(db) { at.plusSeconds(2 * 86_400).toEpochMilli() }

        // Simulate a receipt legitimately confirmed on the acquisition day,
        // while preserving the original product version from the prior day.
        val receipt = requireNotNull(db.foodDao().batch(batchId)).originOperationId
        val acquiredAt = at.plusSeconds(86_400).toEpochMilli()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(acquiredAt, receipt))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET recordedAt = ?, occurredOn = ? WHERE operationId = ?",
            arrayOf(acquiredAt, "2026-11-02", receipt))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET acquiredOn = ? WHERE id = ?",
            arrayOf("2026-11-02", batchId))

        for (column in listOf("openedAt", "thawedAt")) {
            // A state event after the acquisition day is valid and must remain
            // visible to a later read-only recipe preview.
            db.openHelper.writableDatabase.execSQL(
                "UPDATE batches SET $column = ? WHERE id = ?",
                arrayOf(at.plusSeconds(86_400).toEpochMilli(), batchId))
            val valid = requireNotNull(later.readRecipeRoomStockSnapshot())
            assertEquals(2_000L, valid.lines.single().amountMilli)

            // Same timestamp as the recorded receipt, but one Berlin day
            // before acquisition: later snapshots must never legitimize it.
            db.openHelper.writableDatabase.execSQL(
                "UPDATE batches SET $column = ? WHERE id = ?",
                arrayOf(at.toEpochMilli(), batchId))
            assertNull(later.readRecipeRoomStockSnapshot())
            assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
            assertEquals(operations, db.foodDao().operationCount())
            assertEquals(0, db.foodDao().consumptionCount())

            db.openHelper.writableDatabase.execSQL(
                "UPDATE batches SET $column = NULL WHERE id = ?", arrayOf(batchId))
            assertNotNull(later.readRecipeRoomStockSnapshot())
        }

        db.openHelper.writableDatabase.execSQL(
            "UPDATE batches SET acquiredOn = ? WHERE id = ?",
            arrayOf("2026-11-01", batchId))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET recordedAt = ? WHERE id = ?",
            arrayOf(at.toEpochMilli(), receipt))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE stock_movements SET recordedAt = ?, occurredOn = ? WHERE operationId = ?",
            arrayOf(at.toEpochMilli(), "2026-11-01", receipt))
        val restored = requireNotNull(store.readRecipeRoomStockSnapshot())
        assertEquals(original.revision, restored.revision)
        assertEquals(original.lines, restored.lines)
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }


    @Test fun consumptionMovementRequiresMatchingEventAndBatchAllocation() = runBlocking {
        val eventAt = at.plusSeconds(30).toEpochMilli()
        val after = FoodStore(db) { at.plusSeconds(60).toEpochMilli() }
        val key = "room-stock-consumption-evidence"
        after.consume(key, ConsumptionInput(versionId, "2026-11-01", "Snack",
            100L, listOf(Allocation(batchId, 100L)), occurredAt = eventAt), true)
        val valid = requireNotNull(after.readRecipeRoomStockSnapshot())
        assertEquals(1_900L, valid.lines.single().amountMilli)
        val consumption = requireNotNull(db.foodDao().consumptionForOperation(key))
        val operations = db.foodDao().operationCount()

        // A changed allocation must not be silently accepted because the
        // stock movement still claims the original deduction.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_allocations SET amount = ? WHERE consumptionId = ? AND batchId = ?",
            arrayOf(99L, consumption.id, batchId))
        assertNull(after.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_allocations SET amount = ? WHERE consumptionId = ? AND batchId = ?",
            arrayOf(100L, consumption.id, batchId))

        // Even a locally matching allocation cannot authorize an event whose
        // total consumed quantity no longer matches all its allocations.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_events SET amount = ? WHERE id = ?",
            arrayOf(101L, consumption.id))
        assertNull(after.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_events SET amount = ? WHERE id = ?",
            arrayOf(100L, consumption.id))

        // The operation's result must point to this actual consumption event,
        // not just to an arbitrary nonblank identifier.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET resultId = ? WHERE id = ?", arrayOf(batchId, key))
        assertNull(after.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE operations SET resultId = ? WHERE id = ?", arrayOf(consumption.id, key))

        // A physical movement cannot silently inherit a changed diary day.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_events SET date = ? WHERE id = ?",
            arrayOf("2026-11-02", consumption.id))
        assertNull(after.readRecipeRoomStockSnapshot())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE consumption_events SET date = ? WHERE id = ?",
            arrayOf("2026-11-01", consumption.id))

        val restored = requireNotNull(after.readRecipeRoomStockSnapshot())
        assertEquals(valid.revision, restored.revision)
        assertEquals(valid.lines, restored.lines)
        assertEquals(operations, db.foodDao().operationCount())
        assertEquals(1, db.foodDao().consumptionCount())
    }

}
