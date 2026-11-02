package de.ernaehrung.demo

import android.content.Context
import java.time.Instant
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var lentil: String
    private lateinit var tomato: String
    private lateinit var a: String
    private lateinit var b: String
    private lateinit var location: String
    private lateinit var recipe: String
    private val dao get() = db.foodDao()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { 1_800_000_000_000L }
        lentil = store.rememberProduct("l", ProductInput("Linsen", energyKcal = 340.0))
        tomato = store.rememberProduct("t", ProductInput("Tomaten", energyKcal = null))
        location = store.addLocation("loc", "Kühlschrank", true)
        a = store.receive("buy-a", BatchInput(lentil, location, 300_000, "2026-10-30", release = ReleaseStatus.RELEASED), true)
        b = store.receive("buy-b", BatchInput(tomato, location, 500_000, "2026-10-30", release = ReleaseStatus.RELEASED), true)
        recipe = store.saveRecipe("recipe", RecipeInput("Linsengericht", 6000,
            listOf(RecipeItemInput(lentil, 300_000), RecipeItemInput(tomato, 500_000)), "Garen"))
    }
    @After fun close() { db.close() }
    private fun input() = ProductionInput(recipe, 6000, listOf(Allocation(a, 300_000), Allocation(b, 500_000)),
        location, "2026-10-30", true, "Platz für sechs verschlossene Behälter und Kühlbedingungen geprüft", release = ReleaseStatus.RELEASED)
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected transaction") } catch (_: IllegalArgumentException) { }
    }
    @Test fun cookingConsumesRawStockCreatesPortionsButIsNotEatingAndRetryDoesNotDoubleIt() = runBlocking {
        assertEquals(300_000L, dao.balance(a)) // recipe save did not consume anything
        val output = store.produce("cook", input(), true)
        assertEquals(output, store.produce("cook", input(), true))
        assertEquals(0L, dao.balance(a)); assertEquals(0L, dao.balance(b)); assertEquals(6000L, dao.balance(output))
        assertEquals(3, dao.movements("cook").size); assertEquals(0, dao.consumptionCount())
        assertEquals(recipe, dao.batch(output)!!.recipeVersionId)
        assertNull(dao.version(dao.batch(output)!!.versionId)!!.energyKcal) // no fabricated nutrition
        val portions = dao.batch(output)!!.versionId
        store.consume("eat", ConsumptionInput(portions, "2026-10-30", "Mittag", 1000, listOf(Allocation(output, 1000))), true)
        assertEquals(5000L, dao.balance(output)); assertEquals(0L, dao.balance(a)); assertEquals(0L, dao.balance(b))
        assertEquals(1, dao.activeConsumptionCount()) // raw ingredients are not consumed again
    }
    @Test fun failedSecondIngredientAndFaultAtOutputLeaveNoPartialEffects() = runBlocking {
        store.waste("waste", WasteInput(b, 1, "2026-10-30", "Verlust"), true)
        val before = dao.operationCount(); val catalog = store.catalog().first().size
        rejects { store.produce("cook-fail", input(), true) }
        assertEquals(300_000L, dao.balance(a)); assertEquals(499_999L, dao.balance(b))
        assertEquals(before, dao.operationCount()); assertEquals(catalog, store.catalog().first().size)
        assertNull(dao.production("cook-fail")); assertNull(dao.outputProduct(dao.recipeVersion(recipe)!!.recipeId))
        store.reverse("undo-waste", ReversalInput("waste", "2026-10-30", "Eingabefehler"), true)
        // Exercise a real SQLite error after inputs have been removed, not only pre-validation.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER output_fault BEFORE INSERT ON stock_movements WHEN NEW.kind = 'PRODUCTION_OUTPUT' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try { store.produce("fault", input(), true); fail("Expected SQLite fault") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(300_000L, dao.balance(a)); assertEquals(500_000L, dao.balance(b))
        assertNull(dao.operation("fault")); assertNull(dao.production("fault")); assertTrue(dao.movements("fault").isEmpty())
        assertEquals(catalog, store.catalog().first().size)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER output_fault")
        assertEquals(6000L, dao.balance(store.produce("fault", input(), true)))
    }
    @Test fun recipeVersionsAndExactProductVersionsRemainPinned() = runBlocking {
        val identity = dao.recipeVersion(recipe)!!.recipeId
        val newer = store.saveRecipe("new-recipe", RecipeInput("Kleiner Ansatz", 3000,
            listOf(RecipeItemInput(lentil, 150_000), RecipeItemInput(tomato, 250_000)), "Garen"), identity, "Halber Ansatz")
        val output = store.produce("cook", input(), true)
        assertEquals(recipe, dao.production("cook")!!.recipeVersionId)
        assertEquals(recipe, dao.batch(output)!!.recipeVersionId)
        assertEquals(6000L, dao.recipeVersion(recipe)!!.yieldPortions)
        assertEquals(newer, store.recipes().first().single().id)
    }
    @Test fun incompleteUnsafeOrUnexplainedProductionIsRejectedWithoutEffects() = runBlocking {
        rejects { store.produce("no-confirm", input(), false) }
        rejects { store.produce("no-storage", input().copy(storageChecked = false), true) }
        rejects { store.produce("no-note", input().copy(storageNote = " "), true) }
        rejects { store.produce("unknown", input().copy(allocations = listOf(Allocation(a, 300_000))), true) }
        rejects { store.produce("duplicate", input().copy(allocations = input().allocations + Allocation(a, 1)), true) }
        rejects { store.produce("deviation", input().copy(actualPortions = 5000), true) }
        assertEquals(300_000L, dao.balance(a)); assertEquals(500_000L, dao.balance(b))
        val output = store.produce("deviation-ok", input().copy(actualPortions = 5000, deviationNote = "Tatsächliche Ausbeute nur fünf Portionen"), true)
        assertEquals(5000L, dao.balance(output))
    }
    @Test fun productionCanOnlyBeReversedAfterItsConsumptionAndRestoresAllInputs() = runBlocking {
        val output = store.produce("cook", input(), true)
        store.consume("eat", ConsumptionInput(dao.batch(output)!!.versionId, "2026-10-30", "Mittag", 1000, listOf(Allocation(output, 1000))), true)
        rejects { store.reverse("undo-cook", ReversalInput("cook", "2026-10-30", "Irrtum"), true) }
        assertEquals(0L, dao.balance(a)); assertEquals(5000L, dao.balance(output))
        store.reverse("undo-eat", ReversalInput("eat", "2026-10-30", "Irrtum"), true)
        store.reverse("undo-cook", ReversalInput("cook", "2026-10-30", "Irrtum"), true)
        assertEquals(300_000L, dao.balance(a)); assertEquals(500_000L, dao.balance(b)); assertEquals(0L, dao.balance(output))
        assertNotNull(dao.production("cook")); assertEquals(3, dao.movements("undo-cook").size)
    }

    @Test fun productionCrossingBerlinMidnightUsesOneRecordedAtAndRetryDoesNotAdvanceClock() = runBlocking {
        // 22:59:59.999 UTC is 23:59:59.999 in Berlin on 1 November.
        val instant = Instant.parse("2026-11-01T22:59:59.999Z").toEpochMilli()
        var clock = instant
        val movingStore = FoodStore(db) { clock++ }
        val production = input().copy(date = "2026-11-01")
        val before = dao.operationCount()
        val output = movingStore.produce("production-midnight", production, true)
        assertEquals(instant + 1L, clock)
        assertEquals(output, movingStore.produce("production-midnight", production, true))
        assertEquals(instant + 1L, clock)
        val operation = requireNotNull(dao.operation("production-midnight"))
        val movements = dao.movements("production-midnight")
        val outputVersion = requireNotNull(dao.version(requireNotNull(dao.batch(output)).versionId))
        assertEquals(instant, operation.recordedAt)
        assertEquals(instant, outputVersion.recordedAt)
        assertEquals(3, movements.size)
        assertTrue(movements.all { it.recordedAt == instant && it.occurredOn == "2026-11-01" })
        assertEquals(before + 1, dao.operationCount())
        assertEquals(0L, dao.balance(a))
        assertEquals(0L, dao.balance(b))
        assertEquals(6000L, dao.balance(output))
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun invalidNegativeProductionClockRollsBackEveryPhysicalEffect() = runBlocking {
        val before = dao.operationCount()
        val invalidStore = FoodStore(db) { -1L }
        val outcome = runCatching { invalidStore.produce("production-negative-time", input(), true) }
        assertTrue(outcome.isFailure)
        assertNull(dao.operation("production-negative-time"))
        assertNull(dao.production("production-negative-time"))
        assertTrue(dao.movements("production-negative-time").isEmpty())
        assertEquals(before, dao.operationCount())
        assertEquals(300_000L, dao.balance(a))
        assertEquals(500_000L, dao.balance(b))
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun concurrentProductionCannotCreatePortionsTwiceFromSameRawStock() = runBlocking {
        val outcomes = coroutineScope { (1..2).map { n -> async(Dispatchers.IO) {
            try { store.produce("parallel-$n", input(), true); true } catch (_: IllegalArgumentException) { false }
        } }.awaitAll() }
        assertEquals(1, outcomes.count { it })
        val outputs = store.batches().first().filter { it.batch.recipeVersionId != null }
        assertEquals(1, outputs.size); assertEquals(6000L, outputs.single().stockAmount)
        assertEquals(0L, dao.balance(a)); assertEquals(0L, dao.balance(b)); assertEquals(0, dao.consumptionCount())
    }
}
