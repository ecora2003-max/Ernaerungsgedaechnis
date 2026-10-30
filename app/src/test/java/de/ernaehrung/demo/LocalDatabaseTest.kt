package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDatabaseTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = db.foodDao()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build()
        store = FoodStore(db) { 1_800_000_000_000 }
    }
    @After fun close() { db.close() }
    private suspend fun product(energy: Double? = null) = store.rememberProduct("product", ProductInput("Tofu", energyKcal = energy))
    private suspend fun location() = store.addLocation("location", "Kühlschrank", true)
    private suspend fun received(version: String, location: String, key: String = "receipt", amount: Long = 200_000,
        release: ReleaseStatus = ReleaseStatus.RELEASED, useBy: String? = null): String = store.receive(key,
        BatchInput(version, location, amount, "2026-10-30", useBy = useBy, release = release), true)
    private fun consumption(version: String, batch: String, amount: Long = 50_000) = ConsumptionInput(
        version, "2026-10-30", "Mittagessen", amount, listOf(Allocation(batch, amount)))
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected transaction") } catch (_: IllegalArgumentException) { }
    }

    @Test fun rememberingProductsCreatesNoStockAndUnknownIsNotZero() = runBlocking {
        val first = product()
        val second = store.rememberProduct("zero", ProductInput("Wasser", energyKcal = 0.0))
        assertNull(dao.version(first)!!.energyKcal)
        assertEquals(0.0, dao.version(second)!!.energyKcal!!, 0.0)
        assertTrue(store.batches().first().isEmpty())
        assertTrue(store.movements().first().isEmpty())
        assertTrue(store.catalog().first().all { it.stockAmount == 0L })
    }

    @Test fun idempotentReceiptsSurviveRetriesButRejectChangedPayload() = runBlocking {
        val v = product(); val l = location()
        val batch = received(v, l)
        assertEquals(batch, received(v, l))
        assertEquals(200_000L, dao.balance(batch))
        assertEquals(1, dao.movements("receipt").size)
        rejects { received(v, l, amount = 300_000) }
        assertEquals(200_000L, dao.balance(batch))
    }

    @Test fun operationKeysRejectDifferentKindsAndDelimiterContainingProductFields() = runBlocking {
        val v = store.rememberProduct("same-key", ProductInput("A, brand=B", brand = "C"))
        rejects { store.rememberProduct("same-key", ProductInput("A", brand = "B, brand=C")) }
        rejects { store.addLocation("same-key", "Kühlschrank", true) }
        assertEquals(v, store.catalog().first().single().version.id)
        assertTrue(store.locations().first().isEmpty())
        assertEquals(1, dao.operationCount())
    }

    @Test fun alreadyCommittedRetriesRemainSuccessfulEvenWhenDeviceClockChanges() = runBlocking {
        val v = product(); val l = location(); val b = received(v, l)
        val meal = store.consume("meal", consumption(v, b), true)
        store = FoodStore(db) { 0L } // Device clock reset after restart, not a new real-world event.
        assertEquals(b, received(v, l))
        assertEquals(meal, store.consume("meal", consumption(v, b), true))
        rejects { received(v, l, "new-future-receipt") }
        assertEquals(150_000L, dao.balance(b))
        assertEquals(1, dao.consumptionCount())
    }

    @Test fun separateIdenticalMealsRemainSeparateAndRetriesAreHarmless() = runBlocking {
        val v = product(); val b = received(v, location())
        val c = consumption(v, b)
        val first = store.consume("meal-1", c, true)
        assertEquals(first, store.consume("meal-1", c, true))
        store.consume("meal-2", c, true)
        assertEquals(100_000L, dao.balance(b))
        assertEquals(2, dao.consumptionCount())
        assertEquals(DayStatus.PARTIAL, dao.day(c.date)!!.status)
    }

    @Test fun overdraftRollsBackAllEffectsAndSameKeyCanBeRetriedAfterCorrection() = runBlocking {
        val v = product(); val b = received(v, location())
        val count = dao.operationCount()
        rejects { store.consume("too-much", consumption(v, b, 300_000), true) }
        assertEquals(200_000L, dao.balance(b))
        assertEquals(count, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
        assertNull(dao.day("2026-10-30"))
        store.consume("too-much", consumption(v, b, 200_000), true)
        assertEquals(0L, dao.balance(b))
        assertEquals(1, store.catalog().first().size) // depleted identity is retained
    }

    @Test fun lateFailureRollsBackEarlierBatchWithdrawalInSameTransaction() = runBlocking {
        val v = product(); val l = location()
        val a = received(v, l, "a", 100_000); val b = received(v, l, "b", 20_000)
        val input = ConsumptionInput(v, "2026-10-30", "Mittagessen", 150_000,
            listOf(Allocation(a, 100_000), Allocation(b, 50_000)))
        rejects { store.consume("multi", input, true) }
        assertEquals(100_000L, dao.balance(a)); assertEquals(20_000L, dao.balance(b))
        assertTrue(dao.movements("multi").isEmpty()); assertNull(dao.operation("multi"))
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun unknownAndBlockedBatchesCannotBeConsumedAndUseByIsRespected() = runBlocking {
        val v = product(); val l = location()
        for (release in listOf(ReleaseStatus.UNCHECKED, ReleaseStatus.BLOCKED)) {
            val b = received(v, l, release.name, release = release)
            rejects { store.consume("consume-${release.name}", consumption(v, b), true) }
            assertEquals(200_000L, dao.balance(b))
        }
        val expired = received(v, l, "expired", useBy = "2026-10-29")
        rejects { store.consume("expired-meal", consumption(v, expired), true) }
        val future = received(v, l, "future")
        rejects { store.consume("too-early", consumption(v, future).copy(date = "2026-10-29"), true) }
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun unknownConsumptionWithoutStockIsRetainedAndPlansDoNotCreateFacts() = runBlocking {
        val v = product()
        val c = store.consume("restaurant", ConsumptionInput(v, "2026-10-30", "Unterwegs", null,
            note = "Menge noch unbekannt"), true)
        assertNull(dao.consumption(c)!!.amount)
        assertTrue(store.movements().first().isEmpty())
        val count = dao.consumptionCount()
        dao.insertPlan(PlanEntity("plan", 1, "2026-10-30", "2026-11-05", EntryStatus.CONFIRMED, Source.USER, 1))
        dao.insertPlannedEntry(PlannedEntryEntity("future-meal", "plan", "2026-10-31", PlanKind.DIRECT_CONSUMPTION, v, null, 100_000, BaseUnit.GRAM))
        assertEquals(count, dao.consumptionCount())
        assertTrue(store.batches().first().isEmpty())
        assertTrue(store.movements().first().isEmpty())
    }

    @Test fun newConsumptionInvalidatesCompletenessAndFutureFactsAreRejected() = runBlocking {
        val v = product(); val l = location()
        dao.saveDay(DiaryDayEntity("2026-10-30", DayStatus.COMPLETE, 42))
        store.consume("extra-meal", ConsumptionInput(v, "2026-10-30", "Nachtrag", null), true)
        assertEquals(DayStatus.PARTIAL, dao.day("2026-10-30")!!.status)
        assertNull(dao.day("2026-10-30")!!.confirmedAt)
        rejects { store.consume("future-meal", ConsumptionInput(v, "2099-01-01", "Geplant", 1000), true) }
        rejects { store.receive("future-stock", BatchInput(v, l, 1000, acquiredOn = "2099-01-01"), true) }
        assertEquals(1, dao.consumptionCount())
        assertTrue(store.batches().first().isEmpty())
    }

    @Test fun laterVersionsNeverRewriteHistoricalConsumptionOrRecipes() = runBlocking {
        val v1 = product(125.0); val identity = dao.version(v1)!!.productId
        val b = received(v1, location())
        val meal = store.consume("meal", consumption(v1, b), true)
        dao.insertRecipe(RecipeEntity("recipe", 1))
        dao.insertRecipeVersion(RecipeVersionEntity("r1", "recipe", 1, "Tofupfanne", 2000, null, "Braten",
            null, null, false, null, null, Quality.UNKNOWN, 1, "Erste Fassung"))
        dao.insertIngredient(RecipeIngredientEntity("r1", 0, v1, 200_000, BaseUnit.GRAM, null))
        val v2 = store.rememberProduct("revision", ProductInput("Tofu neue Rezeptur", energyKcal = 140.0), identity, "Etikett geändert")
        assertEquals(2, dao.versions(identity).size)
        assertEquals(v1, dao.consumption(meal)!!.productVersionId)
        assertEquals(v1, dao.ingredients("r1").single().productVersionId)
        assertEquals(125.0, dao.version(v1)!!.energyKcal!!, 0.0)
        assertEquals(v2, store.catalog().first().single().version.id)
        assertEquals(150_000L, store.catalog().first().single().stockAmount)
        rejects { store.rememberProduct("change-unit", ProductInput("Tofu", unit = BaseUnit.MILLILITRE), identity) }
    }

    @Test fun noImplicitConfirmationAndInvalidReferencesHaveNoEffects() = runBlocking {
        val v = product(); val l = location()
        rejects { store.receive("not-confirmed", BatchInput(v, l, 1000), false) }
        rejects { received("missing-version", l) }
        rejects { received(v, "missing-location") }
        val plannedLocation = store.addLocation("planned-location", "Geplanter Gefrierschrank", false)
        rejects { received(v, plannedLocation) }
        assertTrue(store.batches().first().isEmpty())
    }

    @Test fun mismatchedOrRepeatedAllocationsAreRejected() = runBlocking {
        val v = product(); val l = location(); val b = received(v, l)
        val other = store.rememberProduct("other", ProductInput("Hafer"))
        rejects { store.consume("mismatch", consumption(other, b), true) }
        rejects { store.consume("duplicate", consumption(v, b).copy(allocations = listOf(Allocation(b, 25_000), Allocation(b, 25_000))), true) }
        rejects { store.consume("unknown-with-stock", consumption(v, b).copy(amount = null), true) }
        rejects { store.consume("partial", consumption(v, b).copy(amount = 100_000), true) }
        assertEquals(200_000L, dao.balance(b))
    }

    @Test fun concurrentRetriesAndCompetingWithdrawalsCannotDuplicateOrOverdraw() = runBlocking {
        val v = product(); val l = location()
        val ids = coroutineScope { (1..8).map { async(Dispatchers.IO) { received(v, l) } }.awaitAll() }
        assertEquals(1, ids.toSet().size)
        val b = ids.first()
        val outcomes = coroutineScope { (1..4).map { n -> async(Dispatchers.IO) {
            try { store.consume("parallel-$n", consumption(v, b, 100_000), true); true }
            catch (_: IllegalArgumentException) { false }
        } }.awaitAll() }
        assertEquals(2, outcomes.count { it }); assertEquals(0L, dao.balance(b)); assertEquals(2, dao.consumptionCount())
    }

    @Test fun malformedInputNeverBecomesARecordedSuccess() = runBlocking {
        rejects { store.rememberProduct("nan", ProductInput("Tofu", energyKcal = Double.NaN)) }
        rejects { store.rememberProduct("negative", ProductInput("Tofu", energyKcal = -1.0)) }
        rejects { store.rememberProduct("blank", ProductInput(" ")) }
        val v = product(); val l = location()
        rejects { received(v, l, amount = -1) }
        rejects { store.receive("bad-date", BatchInput(v, l, 1000, "2026-02-30"), true) }
        assertTrue(store.batches().first().isEmpty())
    }

    @Test fun foreignKeysRejectOrphansAndMismatchedAliasVersions() = runBlocking {
        val v = product(); val other = store.rememberProduct("other", ProductInput("Hafer"))
        try {
            dao.insertAlias(ProductAliasEntity("alias", dao.version(v)!!.productId, other, "TOFU", null, null,
                null, Quality.CONFIRMED, Source.USER, 1))
            fail("Mismatched alias must be rejected")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        val cursor = db.openHelper.readableDatabase.query("PRAGMA foreign_key_check")
        cursor.use { assertEquals(0, it.count) }
    }

    @Test fun persistentFileReopensWithSameHistoryAndOperationKeys() = runBlocking {
        db.close()
        val filename = "test-reopen-${UUID.randomUUID()}.db"
        try {
            db = Room.databaseBuilder(context, FoodDatabase::class.java, filename).build(); store = FoodStore(db)
            val v = product(); val l = location(); val b = received(v, l)
            val meal = store.consume("meal", consumption(v, b), true)
            db.close()
            db = Room.databaseBuilder(context, FoodDatabase::class.java, filename).build(); store = FoodStore(db)
            assertEquals(150_000L, dao.balance(b))
            assertEquals(b, received(v, l))
            assertEquals(meal, store.consume("meal", consumption(v, b), true))
            assertEquals(1, dao.consumptionCount())
            assertEquals(v, dao.batch(b)!!.versionId)
        } finally { db.close(); context.deleteDatabase(filename) }
    }
}
