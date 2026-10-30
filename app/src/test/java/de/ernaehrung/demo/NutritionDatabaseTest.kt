package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NutritionDatabaseTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val dao get() = db.foodDao()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val day = "2026-10-30"
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build()
        store = FoodStore(db) { 1_800_000_000_000L }
    }
    @After fun close() { db.close() }
    private fun equal(expected: String, result: NutrientResult) {
        val actual = requireNotNull(result.knownAmount)
        assertTrue("Expected $expected, got $actual", actual.subtract(BigDecimal(expected)).abs() < BigDecimal("0.000000000000000000000000001"))
    }
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected transaction") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun product(key: String, kcal: Double? = 125.0) = store.rememberProduct(key,
        ProductInput(key, energyKcal = kcal, proteinGrams = 13.0, fiberGrams = 5.0, vitaminB12Micrograms = 0.5))
    private suspend fun eat(key: String, version: String, amount: Long?) = store.consume(key, ConsumptionInput(version, day, key, amount), true)
    private data class Cooking(val lentil: String, val tomato: String, val a: String, val b: String, val location: String, val recipe: String)
    private suspend fun cooking(tomatoEnergy: Double? = 30.0): Cooking {
        val l = store.rememberProduct("l", ProductInput("Linsen", energyKcal = 340.0, proteinGrams = 24.0))
        val t = store.rememberProduct("t", ProductInput("Tomaten", energyKcal = tomatoEnergy, proteinGrams = 1.4))
        val location = store.addLocation("loc", "Kühlschrank", true)
        val a = store.receive("buy-a", BatchInput(l, location, 300_000, day, release = ReleaseStatus.RELEASED), true)
        val b = store.receive("buy-b", BatchInput(t, location, 500_000, day, release = ReleaseStatus.RELEASED), true)
        val r = store.saveRecipe("recipe", RecipeInput("Gericht", 6000, listOf(RecipeItemInput(l, 300_000), RecipeItemInput(t, 500_000)), "Garen"))
        return Cooking(l, t, a, b, location, r)
    }
    private suspend fun cook(c: Cooking, amount: Long = 300_000, portions: Long = 6000) = store.produce("cook",
        ProductionInput(c.recipe, portions, listOf(Allocation(c.a, amount), Allocation(c.b, 500_000)), c.location, day,
            true, "Platz und geeignete Lagerbedingungen geprüft", ReleaseStatus.RELEASED, "Tatsächliche Mengen/Ausbeute geprüft"), true)

    @Test fun existingProductRetryEncodingRemainsCompatibleAndExtraNutrientsAreVersioned() = runBlocking {
        val old = store.rememberProduct("legacy", ProductInput("Tofu"))
        assertEquals("9193891322dd6b9760b09d8180ce41b4493d178bec4c5af858174713a775ec95", dao.operation("legacy")!!.fingerprint)
        assertEquals(old, store.rememberProduct("legacy", ProductInput("Tofu")))
        val input = ProductInput.from(dao.version(old)!!).copy(fiberGrams = 5.0, vitaminB12Micrograms = 0.5, saltGrams = 0.0)
        val new = store.rememberProduct("extended", input, dao.version(old)!!.productId, "Ergänzung")
        assertEquals(new, store.rememberProduct("extended", input, dao.version(old)!!.productId, "Ergänzung"))
        rejects { store.rememberProduct("extended", input.copy(fiberGrams = 6.0), dao.version(old)!!.productId, "Ergänzung") }
        assertNull(dao.version(old)!!.fiberGrams); assertEquals(5.0, dao.version(new)!!.fiberGrams!!, 0.0)
        assertEquals(0.0, dao.version(new)!!.saltGrams!!, 0.0)
    }

    @Test fun actualConsumptionScalesVersionedNutrientsAndLaterCorrectionDoesNotRewriteIt() = runBlocking {
        val v = product("Tofu")
        eat("meal", v, 50_000)
        val before = store.dayNutrition(day)
        equal("62.5", before.totals[Nutrient.ENERGY]); equal("6.5", before.totals[Nutrient.PROTEIN])
        equal("2.5", before.totals[Nutrient.FIBRE]); equal("0.25", before.totals[Nutrient.B12])
        assertEquals(DayStatus.PARTIAL, before.status)
        store.rememberProduct("new-label", ProductInput.from(dao.version(v)!!).copy(energyKcal = 200.0), dao.version(v)!!.productId, "Neues Etikett")
        equal("62.5", store.dayNutrition(day).totals[Nutrient.ENERGY])
        assertEquals(before.snapshot, store.dayNutrition(day).snapshot)
        assertEquals(v, store.dayNutrition(day).meals.single().event.productVersionId)
    }
    @Test fun unknownQuantityAndMissingNutrientsKeepKnownSubtotalsWithoutFalseZeros() = runBlocking {
        val known = product("known"); val missing = product("missing", null)
        eat("known-meal", known, 100_000); eat("unknown-energy", missing, 50_000); eat("unknown-amount", known, null)
        val report = store.dayNutrition(day)
        equal("125", report.totals[Nutrient.ENERGY])
        assertEquals(2, report.totals[Nutrient.ENERGY].missingCount)
        equal("19.5", report.totals[Nutrient.PROTEIN]); assertEquals(1, report.totals[Nutrient.PROTEIN].missingCount)
        assertNull(report.meals.single { it.event.operationId == "unknown-amount" }.nutrition[Nutrient.ENERGY].knownAmount)
        assertTrue(report.totals.hasGaps); assertEquals(3, report.meals.size)
        assertTrue(dao.movements("known-meal").isEmpty())
    }
    @Test fun actualYieldAndActualIngredientAmountsDifferFromRecipePreviewAndSurviveTransfer() = runBlocking {
        val c = cooking()
        equal("195", store.recipeNutrition(c.recipe)[Nutrient.ENERGY])
        val output = cook(c, 150_000, 5000)
        assertTrue(store.dayNutrition(day).meals.isEmpty()) // cooking is not eating
        val version = dao.batch(output)!!.versionId
        store.consume("eat-original", ConsumptionInput(version, day, "Mittag", 1000, listOf(Allocation(output, 1000))), true)
        equal("132", store.dayNutrition(day).totals[Nutrient.ENERGY]); equal("8.6", store.dayNutrition(day).totals[Nutrient.PROTEIN])
        val target = store.addLocation("other", "Zweiter Kühlschrank", true)
        val moved = store.transfer("move", TransferInput(output, target, 1000, day, "Umstellen", true), true)
        store.consume("eat-moved", ConsumptionInput(version, day, "Abend", 1000, listOf(Allocation(moved, 1000))), true)
        equal("264", store.dayNutrition(day).totals[Nutrient.ENERGY])
        assertEquals(150_000L, dao.balance(c.a)); assertEquals(0L, dao.balance(c.b)); assertEquals(3000L, dao.balance(output))
        store.rememberProduct("changed-product", ProductInput("Andere Linsen", energyKcal = 999.0), dao.version(c.lentil)!!.productId, "Andere Rezeptur")
        store.saveRecipe("changed-recipe", RecipeInput("Neu", 1000, listOf(RecipeItemInput(c.lentil, 300_000)), "Garen"), dao.recipeVersion(c.recipe)!!.recipeId, "Neu")
        equal("264", store.dayNutrition(day).totals[Nutrient.ENERGY])
    }
    @Test fun nestedProductionUsesItsInputsOnceAndRetainsPartialCoveragePerNutrient() = runBlocking {
        val c = cooking(null); val first = cook(c)
        val version = dao.batch(first)!!.versionId
        val recipe = store.saveRecipe("second-recipe", RecipeInput("Weiterverarbeitet", 2000, listOf(RecipeItemInput(version, 2000)), "Umfüllen"))
        val second = store.produce("second", ProductionInput(recipe, 2000, listOf(Allocation(first, 2000)), c.location, day,
            true, "Geeignete Lagerbedingungen und Platz geprüft", ReleaseStatus.RELEASED), true)
        store.consume("eat", ConsumptionInput(dao.batch(second)!!.versionId, day, "Mittag", 1000, listOf(Allocation(second, 1000))), true)
        val report = store.dayNutrition(day)
        equal("170", report.totals[Nutrient.ENERGY]); assertEquals(1, report.totals[Nutrient.ENERGY].missingCount)
        equal("13.166666666666666666666666666666666", report.totals[Nutrient.PROTEIN]); assertTrue(report.totals[Nutrient.PROTEIN].complete)
        assertEquals(0L, dao.balance(c.a)); assertEquals(0L, dao.balance(c.b)); assertEquals(4000L, dao.balance(first))
    }
    @Test fun reversalExcludesActualNutritionAndPlansAndWasteNeverEnterIt() = runBlocking {
        val v = product("Tofu"); val id = eat("meal", v, 100_000)
        store.reverse("undo", ReversalInput("meal", day, "Nicht gegessen"), true)
        val result = store.dayNutrition(day)
        assertTrue(result.meals.isEmpty()); assertNull(result.totals[Nutrient.ENERGY].knownAmount)
        assertNotNull(dao.consumption(id))
        dao.insertPlan(PlanEntity("plan", 1, day, day, EntryStatus.CONFIRMED, Source.USER, 1))
        dao.insertPlannedEntry(PlannedEntryEntity("planned", "plan", day, PlanKind.DIRECT_CONSUMPTION, v, null, 500_000, BaseUnit.GRAM))
        val location = store.addLocation("loc", "Vorrat", true)
        val batch = store.receive("buy", BatchInput(v, location, 200_000, day, release = ReleaseStatus.RELEASED), true)
        store.waste("waste", WasteInput(batch, 100_000, day, "Entsorgt"), true)
        assertTrue(store.dayNutrition(day).meals.isEmpty())
    }
    @Test fun completenessRequiresExplicitFreshConfirmationAndDoesNotHideDataGaps() = runBlocking {
        val v = product("Tofu"); val empty = store.dayNutrition(day)
        assertEquals(DayStatus.OPEN, empty.status); assertNull(empty.totals[Nutrient.ENERGY].knownAmount)
        eat("unknown", v, null)
        rejects { store.setDayStatus("stale", day, DayStatus.COMPLETE, empty.snapshot, true) }
        assertNull(dao.operation("stale"))
        val partial = store.dayNutrition(day)
        rejects { store.setDayStatus("not-confirmed", day, DayStatus.COMPLETE, partial.snapshot, false) }
        store.setDayStatus("complete", day, DayStatus.COMPLETE, partial.snapshot, true)
        val completed = store.dayNutrition(day)
        assertEquals(DayStatus.COMPLETE, completed.status); assertTrue(completed.totals.hasGaps)
        assertNull(completed.totals[Nutrient.ENERGY].knownAmount)
        eat("extra", v, 10_000)
        assertEquals(DayStatus.PARTIAL, store.dayNutrition(day).status)
        store.setDayStatus("complete", day, DayStatus.COMPLETE, partial.snapshot, true) // retry cannot re-confirm a changed day
        assertEquals(DayStatus.PARTIAL, store.dayNutrition(day).status)
    }
    @Test fun statusChangesAtSameClockTimeInvalidateOldSnapshotsAndFutureDaysCannotBeConfirmed() = runBlocking {
        val empty = store.dayNutrition(day)
        store.setDayStatus("first", day, DayStatus.COMPLETE, empty.snapshot, true)
        val first = store.dayNutrition(day)
        store.setDayStatus("reopen", day, DayStatus.OPEN, first.snapshot, true)
        store.setDayStatus("second", day, DayStatus.COMPLETE, store.dayNutrition(day).snapshot, true)
        val second = store.dayNutrition(day)
        assertEquals(first.confirmedAt, second.confirmedAt); assertNotEquals(first.snapshot, second.snapshot)
        rejects { store.setDayStatus("stale-again", day, DayStatus.OPEN, first.snapshot, true) }
        val future = store.dayNutrition("2099-01-01")
        rejects { store.setDayStatus("future", future.date, DayStatus.COMPLETE, future.snapshot, true) }
        assertNull(dao.day(future.date))
    }
    @Test fun liveDayViewRecalculatesAfterNewConsumption() = runBlocking {
        val v = product("Tofu")
        val reports = Channel<DayNutrition>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { store.watchDay(day).collect { reports.send(it) } }
        try {
            assertTrue(withTimeout(10_000) { reports.receive() }.meals.isEmpty())
            eat("new-meal", v, 50_000)
            val updated = withTimeout(10_000) { var next = reports.receive(); while (next.meals.isEmpty()) next = reports.receive(); next }
            equal("62.5", updated.totals[Nutrient.ENERGY])
        } finally { job.cancelAndJoin(); reports.close() }
    }
    @Test fun nutritionAndConfirmedDaySurviveFileReopeningWithoutSchemaChange() = runBlocking {
        db.close(); val filename = "nutrition-${UUID.randomUUID()}.db"
        try {
            db = Room.databaseBuilder(context, FoodDatabase::class.java, filename).build(); store = FoodStore(db)
            val c = cooking(); val output = cook(c, 150_000, 5000)
            store.consume("eat", ConsumptionInput(dao.batch(output)!!.versionId, day, "Mittag", 1000, listOf(Allocation(output, 1000))), true)
            store.setDayStatus("complete", day, DayStatus.COMPLETE, store.dayNutrition(day).snapshot, true)
            val before = store.dayNutrition(day); db.close()
            db = Room.databaseBuilder(context, FoodDatabase::class.java, filename).build(); store = FoodStore(db)
            assertEquals(before, store.dayNutrition(day)); equal("132", store.dayNutrition(day).totals[Nutrient.ENERGY])
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(filename) }
    }
}
