package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StockOperationsTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var version: String
    private lateinit var source: String
    private lateinit var target: String
    private lateinit var batch: String
    private val dao get() = db.foodDao()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { 1_800_000_000_000L }
        version = store.rememberProduct("p", ProductInput("Tofu"))
        source = store.addLocation("s", "Kühlschrank", true)
        target = store.addLocation("t", "Zweiter Kühlschrank", true)
        batch = store.receive("buy", BatchInput(version, source, 200_000, "2026-10-30", "2026-11-01", "2026-11-02", ReleaseStatus.RELEASED, "Etikett geprüft"), true)
    }
    @After fun close() { db.close() }
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Transaction should be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun transferConservesStockAndProvenanceAndDoesNotSilentlyGrantSafety() = runBlocking {
        val input = TransferInput(batch, target, 75_000, "2026-10-30", "Aufteilen")
        val moved = store.transfer("move", input, true)
        assertEquals(moved, store.transfer("move", input, true))
        assertEquals(125_000L, dao.balance(batch)); assertEquals(75_000L, dao.balance(moved))
        val before = dao.batch(batch)!!; val after = dao.batch(moved)!!
        assertEquals(batch, after.parentBatchId)
        assertEquals(before.versionId, after.versionId); assertEquals(before.acquiredOn, after.acquiredOn)
        assertEquals(before.bestBefore, after.bestBefore); assertEquals(before.useBy, after.useBy)
        assertEquals(ReleaseStatus.UNCHECKED, after.release)
        assertEquals(0L, dao.movements("move").sumOf { it.delta })
        assertEquals(200_000L, store.catalog().first().single().stockAmount)
        assertEquals(0, dao.consumptionCount())
    }
    @Test fun invalidOrUnconfirmedTransferLeavesNoTargetOrMovement() = runBlocking {
        val before = store.batches().first().size
        rejects { store.transfer("missing", TransferInput(batch, "missing", 50_000, "2026-10-30", "Umräumen"), true) }
        rejects { store.transfer("too-much", TransferInput(batch, target, 201_000, "2026-10-30", "Umräumen"), true) }
        rejects { store.transfer("same", TransferInput(batch, source, 50_000, "2026-10-30", "Umräumen"), true) }
        rejects { store.transfer("no", TransferInput(batch, target, 50_000, "2026-10-30", "Umräumen"), false) }
        assertEquals(before, store.batches().first().size)
        assertEquals(200_000L, dao.balance(batch)); assertNull(dao.operation("too-much"))
    }
    @Test fun wasteAndCountCorrectionsPreserveTheOriginalAndProtectAgainstStaleCounts() = runBlocking {
        val original = dao.movements("buy").single()
        val waste = WasteInput(batch, 30_000, "2026-10-30", "Verdorben")
        store.waste("waste", waste, true); store.waste("waste", waste, true)
        assertEquals(170_000L, dao.balance(batch)); assertEquals(0, dao.consumptionCount())
        rejects { store.correctStock("stale", StockCountInput(batch, 200_000, 160_000, "2026-10-30", "Gewogen"), true) }
        store.correctStock("count", StockCountInput(batch, 170_000, 160_000, "2026-10-30", "Gewogen"), true)
        assertEquals(-10_000L, dao.movements("count").single().delta)
        assertEquals(original, dao.movements("buy").single())
        store.correctStock("empty", StockCountInput(batch, 160_000, 0, "2026-10-30", "Leer"), true)
        assertEquals(0L, dao.balance(batch)); assertNull(dao.operation("stale"))
    }
    @Test fun reversalIsLinkedIdempotentAndNeverReversesAReversal() = runBlocking {
        store.waste("waste", WasteInput(batch, 50_000, "2026-10-30", "Irrtum"), true)
        val original = dao.movements("waste").single()
        val first = store.reverse("undo", ReversalInput("waste", "2026-10-30", "Nicht entsorgt"), true)
        assertEquals(first, store.reverse("undo", ReversalInput("waste", "2026-10-30", "Nicht entsorgt"), true))
        assertEquals(200_000L, dao.balance(batch))
        assertEquals(original.id, dao.movements("undo").single().reversesId)
        assertEquals(original, dao.movements("waste").single())
        rejects { store.reverse("twice", ReversalInput("waste", "2026-10-30", "Nochmal"), true) }
        rejects { store.reverse("undo-undo", ReversalInput("undo", "2026-10-30", "Nochmal"), true) }
    }
    @Test fun reversingConsumptionRestoresStockAndRemovesItOnlyFromActiveNutrition() = runBlocking {
        val meal = store.consume("eat", ConsumptionInput(version, "2026-10-30", "Mittag", 50_000, listOf(Allocation(batch, 50_000))), true)
        assertEquals(1, dao.activeConsumptionCount())
        store.reverse("undo", ReversalInput("eat", "2026-10-30", "Nicht gegessen"), true)
        assertEquals(200_000L, dao.balance(batch)); assertEquals(0, dao.activeConsumptionCount())
        assertNotNull(dao.consumption(meal)); assertEquals(1, dao.consumptionCount())
        assertEquals(DayStatus.PARTIAL, dao.day("2026-10-30")!!.status)
    }
    @Test fun dependentEventsMustBeReversedBeforeTheirSourceAndTransferUndoesBothSides() = runBlocking {
        val moved = store.transfer("move", TransferInput(batch, target, 100_000, "2026-10-30", "Umräumen", true), true)
        store.consume("eat", ConsumptionInput(version, "2026-10-30", "Mittag", 10_000, listOf(Allocation(moved, 10_000))), true)
        rejects { store.reverse("undo-buy", ReversalInput("buy", "2026-10-30", "Irrtum"), true) }
        rejects { store.reverse("undo-move", ReversalInput("move", "2026-10-30", "Irrtum"), true) }
        assertNull(dao.operation("undo-move"))
        store.reverse("undo-eat", ReversalInput("eat", "2026-10-30", "Irrtum"), true)
        store.reverse("undo-move", ReversalInput("move", "2026-10-30", "Irrtum"), true)
        assertEquals(200_000L, dao.balance(batch)); assertEquals(0L, dao.balance(moved))
        store.reverse("undo-buy", ReversalInput("buy", "2026-10-30", "Irrtum"), true)
        assertEquals(0L, dao.balance(batch))
    }
    @Test fun concurrentWasteAndTransferCannotOverdraw() = runBlocking {
        val outcomes = coroutineScope { listOf(
            async(Dispatchers.IO) { try { store.waste("w", WasteInput(batch, 150_000, "2026-10-30", "Entsorgt"), true); true } catch (_: IllegalArgumentException) { false } },
            async(Dispatchers.IO) { try { store.transfer("m", TransferInput(batch, target, 150_000, "2026-10-30", "Umräumen"), true); true } catch (_: IllegalArgumentException) { false } }
        ).awaitAll() }
        assertEquals(1, outcomes.count { it }); assertEquals(50_000L, dao.balance(batch))
    }

    @Test fun backdatedCorrectionsAndResurrectingCancelledBatchAreRejected() = runBlocking {
        store.waste("later", WasteInput(batch, 10_000, "2026-10-31", "Entsorgt"), true)
        rejects { store.correctStock("backdated", StockCountInput(batch, 190_000, 180_000, "2026-10-30", "Rückdatiert"), true) }
        store.reverse("undo-waste", ReversalInput("later", "2026-10-31", "Irrtum"), true)
        store.reverse("undo-buy", ReversalInput("buy", "2026-10-31", "Irrtum"), true)
        rejects { store.correctStock("resurrect", StockCountInput(batch, 0, 100_000, "2026-10-31", "Doch vorhanden"), true) }
        assertEquals(0L, dao.balance(batch)); assertNull(dao.operation("resurrect"))
    }
}
