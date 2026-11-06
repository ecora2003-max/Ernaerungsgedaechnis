package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchReviewRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val dao get() = db.foodDao()
    private var at = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private val day = "2026-10-30"
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { at }
    }
    @After fun close() { db.close() }
    private suspend fun batch(key: String, release: ReleaseStatus = ReleaseStatus.UNCHECKED): String {
        val v = store.rememberProduct("$key-product", ProductInput("Testware $key", energyKcal = 100.0))
        val location = store.addLocation("$key-location", "Testlager", true)
        return store.receive("$key-receive", BatchInput(v, location, 100_000, day, "2026-11-10", release = release), true)
    }
    private fun input(id: String, previous: String? = null, release: ReleaseStatus = ReleaseStatus.RELEASED) =
        BatchReviewInput(id, previous, day, "2026-11-10", "2026-11-09", null, null, release,
            "Tatsächliche Bedingungen geprüft", "Synthetische ausdrückliche Prüfung", true)
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected rejected transaction") } catch (_: IllegalArgumentException) { }
    }

    @Test fun confirmedReviewChangesOnlyDescriptionAndActualFoodStoreConsumptionUsesItsCurrentSafetyState() = runBlocking {
        val id = batch("a")
        val original = dao.originalBatch(id)!!
        val movements = dao.movements("a-receive")
        val count = dao.operationCount()
        reject { store.reviewBatch("no-consent", input(id), false) }; assertNull(dao.operation("no-consent"))
        val review = store.reviewBatch("review", input(id), true)
        assertEquals(review, store.reviewBatch("review", input(id), true))
        assertEquals(original, dao.originalBatch(id)); assertEquals(100_000L, dao.balance(id))
        assertEquals(movements, dao.movements("a-receive")); assertTrue(dao.movements("review").isEmpty())
        assertEquals(count + 1, dao.operationCount()); assertEquals(ReleaseStatus.RELEASED, dao.batch(id)!!.release)
        assertEquals(ReleaseStatus.RELEASED, store.batches().first().single().batch.release)
        val snapshot = store.watchBatchReview(id).first()
        assertEquals(original, snapshot.original); assertEquals(review, snapshot.latest!!.id)
        assertTrue(snapshot.latest!!.conditionsConfirmed)
        val blocked = store.reviewBatch("blocked", input(id, review, ReleaseStatus.BLOCKED), true)
        reject { store.consume("blocked-meal", ConsumptionInput(original.versionId, day, "Nicht freigegeben", 10_000, listOf(Allocation(id, 10_000))), true) }
        assertNull(dao.operation("blocked-meal")); assertEquals(100_000L, dao.balance(id))
        val released = store.reviewBatch("released-again", input(id, blocked), true)
        store.consume("meal", ConsumptionInput(original.versionId, day, "Ausdrücklich gegessen", 10_000, listOf(Allocation(id, 10_000))), true)
        assertEquals(90_000L, dao.balance(id)); assertEquals(released, dao.latestBatchReview(id)!!.id)
        assertEquals(review, store.reviewBatch("review", input(id), true))
        assertEquals(released, dao.latestBatchReview(id)!!.id)
    }

    @Test fun concurrentAndForeignPredecessorsCannotOverwriteTheCurrentReviewOrCreatePartialOperations() = runBlocking {
        val id = batch("a"); val other = batch("b")
        val root = store.reviewBatch("root", input(id), true)
        reject { store.reviewBatch("foreign", input(other, root), true) }
        assertNull(dao.operation("foreign"))
        val attempts = listOf("left", "right").map { key -> async(Dispatchers.Default) {
            runCatching { store.reviewBatch(key, input(id, root).copy(reason = key), true) }
        } }.awaitAll()
        assertEquals(1, attempts.count { it.isSuccess }); assertEquals(1, attempts.count { it.isFailure })
        assertEquals(2, dao.batchReviews(id).size); assertTrue(dao.batchReviews(other).isEmpty())
        assertEquals(100_000L, dao.balance(id)); assertEquals(1, listOf("left", "right").count { dao.operation(it) != null })
        reject { store.reviewBatch("stale", input(id, root), true) }; assertNull(dao.operation("stale"))
    }

    @Test fun explicitUnknownCanClearAnOldValueWhileExactZeroAndOriginalOffsetTextRemainDistinct() = runBlocking {
        val id = batch("a", ReleaseStatus.RELEASED)
        // Published original columns can contain known opening times; preserve this synthetic legacy fact.
        db.openHelper.writableDatabase.execSQL("UPDATE batches SET openedAt=? WHERE id=?", arrayOf(at - 1000, id))
        val original = dao.originalBatch(id)!!
        val zeroInput = input(id).copy(acquiredOn = null, bestBefore = null, useBy = null,
            openedAtText = "1970-01-01T00:00:00Z")
        val zero = store.reviewBatch("zero", zeroInput, true)
        assertEquals(0L, dao.batch(id)!!.openedAt); assertNull(dao.batch(id)!!.thawedAt)
        assertNull(dao.batch(id)!!.bestBefore); assertEquals("2026-11-10", dao.originalBatch(id)!!.bestBefore)
        val clear = store.reviewBatch("clear", zeroInput.copy(expectedPreviousId = zero, openedAtText = null), true)
        assertNull(dao.batch(id)!!.openedAt); assertEquals(original.openedAt, dao.originalBatch(id)!!.openedAt)
        val raw = "2026-10-30T10:00:00.000+01:00"
        store.reviewBatch("offset", input(id, clear).copy(openedAtText = raw), true)
        assertEquals(raw, dao.latestBatchReview(id)!!.openedAtText)
        assertEquals(Instant.parse("2026-10-30T09:00:00Z").toEpochMilli(), dao.batch(id)!!.openedAt)
        assertEquals(3, dao.batchReviews(id).size); assertEquals(original, dao.originalBatch(id))
        assertEquals(0L, BatchReviewTimes.parse("1970-01-01T00:00:00Z"))
        assertNull(BatchReviewTimes.parse(""))
        assertTrue(runCatching { BatchReviewTimes.parse("2026-10-30") }.isFailure)
        assertTrue(runCatching { BatchReviewTimes.parse("2026-10-30T08:00:00.000001Z") }.isFailure)
    }

    @Test fun futureTimesExpiredUseByAndUnconfirmedReleaseRollbackButPastBestBeforeNeedsAnExplicitUserDecision() = runBlocking {
        val id = batch("a")
        reject { store.reviewBatch("conditions", input(id).copy(conditionsConfirmed = false), true) }
        reject { store.reviewBatch("use-by", input(id).copy(useBy = "2026-10-29"), true) }
        reject { store.reviewBatch("future-opening", input(id).copy(openedAtText = "2026-10-31T10:00:00Z"), true) }
        reject { store.reviewBatch("late-acquired", input(id).copy(acquiredOn = "2026-10-31"), true) }
        reject { store.reviewBatch("extended-future-date", input(id).copy(acquiredOn = "+25000-01-01"), true) }
        assertTrue(dao.batchReviews(id).isEmpty()); assertEquals(ReleaseStatus.UNCHECKED, dao.batch(id)!!.release)
        val review = store.reviewBatch("past-mhd", input(id).copy(bestBefore = "2026-10-29"), true)
        assertEquals(ReleaseStatus.RELEASED, dao.batch(id)!!.release) // explicit checked decision, never inferred
        assertEquals(review, dao.latestBatchReview(id)!!.id)
        assertEquals(100_000L, dao.balance(id)); assertEquals(0, dao.activeConsumptionCount())
    }

    @Test fun stockContextIsBoundToHistoricalKnowledgeAndReviewIdentityWhileFefoAndSuggestionsUseCurrentFacts() = runBlocking {
        val id = batch("a", ReleaseStatus.RELEASED)
        val initialAt = at
        suspend fun source(time: Long) = db.withTransaction { RecipeRoomStockSnapshotReader(dao).read(Instant.ofEpochMilli(time)) }
        val old = requireNotNull(source(initialAt))
        at += 1000
        val first = store.reviewBatch("review", input(id).copy(useBy = null, safetyNote = null), true)
        assertEquals(old, source(initialAt)) // future review cannot alter a past snapshot or its V1 hash
        val fresh = requireNotNull(source(at))
        assertEquals(old.lines, fresh.lines); assertNotEquals(old.revision, fresh.revision)
        at += 1000
        store.reviewBatch("same-facts-new-evidence", input(id, first).copy(useBy = null, safetyNote = null), true)
        assertNotEquals(fresh.revision, requireNotNull(source(at)).revision)
        val v = dao.batch(id)!!.versionId
        store.savePlan("plan", PlanInput("Nur geplant", day, day, listOf(
            PlanEntryInput("entry", day, PlanKind.DIRECT_CONSUMPTION, productVersionId = v, amount = 100_000, unit = BaseUnit.GRAM)), "Synthetisch"))
        assertEquals(100_000L, store.planningReport(day, day).entries.single().demands.single().covered)
        val latest = dao.latestBatchReview(id)!!.id
        store.reviewBatch("block", input(id, latest, ReleaseStatus.BLOCKED), true)
        assertNull(source(at)); assertEquals(0L, store.planningReport(day, day).entries.single().demands.single().covered)
        assertEquals(100_000L, dao.balance(id)); assertEquals(old, source(initialAt))
        assertTrue(DataQualityReader(db) { at }.read().issues.single { it.id == "batch:$id" }.details.contains("Charge ausdrücklich gesperrt"))
    }
}
