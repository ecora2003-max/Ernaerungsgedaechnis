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
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanningDatabaseTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = db.foodDao()
    private val today = "2026-10-30"
    private val clock = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build()
        store = FoodStore(db) { clock }
    }
    @After fun close() { db.close() }
    private suspend fun product(name: String = "Tofu", unit: BaseUnit = BaseUnit.GRAM) =
        store.rememberProduct("product-$name", ProductInput(name, unit = unit))
    private suspend fun stock(version: String, key: String, amount: Long, useBy: String? = "2026-12-31",
        release: ReleaseStatus = ReleaseStatus.RELEASED, mhd: String? = null): String {
        val loc = store.addLocation("loc-$key", "Lager $key", true)
        return store.receive(key, BatchInput(version, loc, amount, today, mhd, useBy, release), true)
    }
    private fun direct(key: String, version: String, amount: Long?, date: String = today, unit: BaseUnit = BaseUnit.GRAM) =
        PlanEntryInput(key, date, PlanKind.DIRECT_CONSUMPTION, productVersionId = version, amount = amount, unit = unit)
    private fun input(vararg entries: PlanEntryInput) = PlanInput("Testplan", today, "2026-12-24", entries.toList(), "Erste Planung")
    private suspend fun report(start: String = today, end: String = "2026-12-24") = store.planningReport(start, end)
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected transaction") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun recipe(v: String, amount: Long? = 300_000, yield: Long = 6000) =
        store.saveRecipe("recipe-$amount-$yield", RecipeInput("Tofutopf", yield, listOf(RecipeItemInput(v, amount)), "Garen"))
    private fun prep(key: String, recipe: String, portions: Long?, date: String = today) =
        PlanEntryInput(key, date, PlanKind.PREPARATION, recipeVersionId = recipe, amount = portions, unit = BaseUnit.PORTION)
    private fun portion(key: String, preparation: String, amount: Long?, date: String) =
        PlanEntryInput(key, date, PlanKind.PREPARED_PORTION, amount = amount, unit = BaseUnit.PORTION, preparationKey = preparation)

    @Test fun allActivePlansCompeteForOneFefoStockAndDayViewsKeepEarlierAllocations() = runBlocking {
        val v = product()
        val late = stock(v, "late", 300_000)
        val early = stock(v, "early", 200_000, "2026-10-31")
        store.savePlan("p1", input(direct("a", v, 300_000)))
        store.savePlan("p2", input(direct("b", v, 300_000, "2026-10-31")))
        val all = report()
        val first = all.entries.first().demands.single()
        assertEquals(listOf(PlannedAllocation(early, 200_000), PlannedAllocation(late, 100_000)), first.allocations)
        assertEquals(300_000L, first.covered); assertEquals(0L, first.missing)
        assertEquals(200_000L, all.entries.last().demands.single().covered)
        assertEquals(100_000L, all.shopping.single().knownMissing)
        val day = report("2026-10-31", "2026-10-31")
        assertEquals(all.entries.last(), day.entries.single())
        assertEquals(all.shopping, day.shopping)
        assertEquals(200_000L, dao.balance(early)); assertEquals(300_000L, dao.balance(late))
        assertEquals(0, dao.consumptionCount()); assertTrue(store.dayNutrition(today).meals.isEmpty())
    }
    @Test fun targetDateExcludesExpiredUnreleasedUnknownAndUnconfirmedStocksWithoutDeclaringMhdUnsafe() = runBlocking {
        val v = product()
        stock(v, "expired", 100_000, today)
        stock(v, "mhd", 100_000, null, mhd = today)
        stock(v, "unknown-date", 100_000, null)
        stock(v, "blocked", 100_000, release = ReleaseStatus.BLOCKED)
        stock(v, "unchecked", 100_000, release = ReleaseStatus.UNCHECKED)
        val valid = stock(v, "valid", 100_000)
        val unconfirmed = "unconfirmed"
        dao.insertLocation(StorageLocationEntity(unconfirmed, "Nur geplant", false))
        dao.insertBatch(dao.batch(valid)!!.copy(id = "unconfirmed-batch", locationId = unconfirmed))
        dao.insertMovement(StockMovementEntity("unconfirmed-move", "unconfirmed-batch", "valid", 100_000,
            MovementKind.RECEIPT, null, Source.USER, "Altimport-Test", clock, occurredOn = today))
        store.savePlan("plan", input(direct("a", v, 200_000, "2026-10-31")))
        val line = report().entries.single().demands.single()
        assertEquals(100_000L, line.covered); assertEquals(100_000L, line.missing)
        assertEquals(setOf(StockExclusionReason.USE_BY_EXPIRED, StockExclusionReason.BEST_BEFORE_REVIEW,
            StockExclusionReason.FUTURE_SHELF_LIFE_UNKNOWN, StockExclusionReason.NOT_RELEASED,
            StockExclusionReason.LOCATION_UNCONFIRMED), line.excluded.map { it.reason }.toSet())
        assertEquals(600_000L, line.excluded.sumOf { it.amount })
    }
    @Test fun explicitlyReleasedStockWithoutShelfLifeCanCoverTodayButNotBePromisedForTomorrow() = runBlocking {
        val v = product(); stock(v, "b", 300_000, null)
        store.savePlan("p", input(direct("a", v, 100_000), direct("b", v, 100_000, "2026-10-31")))
        val lines = report().entries.map { it.demands.single() }
        assertEquals(100_000L, lines.first().covered)
        assertEquals(0L, lines.last().covered)
        assertEquals(StockExclusionReason.FUTURE_SHELF_LIFE_UNKNOWN, lines.last().excluded.single().reason)
    }
    @Test fun cookingNeedsIngredientsOnceAndLinkedPortionsCompeteWithoutInventingPhysicalStock() = runBlocking {
        val v = product(); stock(v, "b", 200_000)
        val r = recipe(v)
        store.savePlan("plan", input(prep("cook", r, 6000), portion("meal1", "cook", 3000, "2026-10-31"),
            portion("meal2", "cook", 4000, "2026-11-01")))
        val all = report()
        assertEquals(300_000L, all.entries.flatMap { it.demands }.sumOf { it.required!! })
        assertEquals(100_000L, all.shopping.single().knownMissing)
        assertEquals(listOf(3000L, 3000L), all.entries.drop(1).map { it.projectedPortions })
        assertEquals(listOf(0L, 1000L), all.entries.drop(1).map { it.missingPortions })
        assertTrue(all.entries.first().issues.contains(PlanningIssue.STORAGE_UNCHECKED))
        assertTrue(all.entries.drop(1).all { PlanningIssue.PLANNED_ONLY in it.issues && it.demands.isEmpty() })
        assertTrue(report("2026-10-31", "2026-11-01").shopping.isEmpty())
        assertEquals(1, dao.planningBatches().size); assertEquals(0, dao.consumptionCount())
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM productions").use { it.moveToFirst(); it.getLong(0) })
    }
    @Test fun actualPreparedPortionsUseOnlyTheirOwnStockAndNeverRequestRawIngredientsAgain() = runBlocking {
        val v = product(); val batch = stock(v, "b", 300_000); val r = recipe(v)
        val loc = dao.batch(batch)!!.locationId
        val output = store.produce("cook", ProductionInput(r, 6000, listOf(Allocation(batch, 300_000)), loc, today,
            true, "Platz und Bedingungen geprüft", ReleaseStatus.RELEASED, useBy = "2026-11-02"), true)
        val outputVersion = dao.batch(output)!!.versionId
        store.savePlan("plan", input(PlanEntryInput("eat", "2026-10-31", PlanKind.PREPARED_PORTION,
            productVersionId = outputVersion, amount = 2000, unit = BaseUnit.PORTION)))
        val line = report().entries.single().demands.single()
        assertEquals(outputVersion, line.versionId); assertEquals(2000L, line.covered); assertEquals(0L, line.missing)
        assertTrue(report().shopping.isEmpty()); assertEquals(6000L, dao.balance(output)); assertEquals(0L, dao.balance(batch))
        assertEquals(0, dao.activeConsumptionCount())
    }
    @Test fun unknownQuantitiesRemainOpenAndUnitsDatesAndPreparationLinksAreValidated() = runBlocking {
        val v = product(); val ml = product("Drink", BaseUnit.MILLILITRE); stock(v, "b", 300_000)
        val r = recipe(v, null)
        store.savePlan("p", input(direct("unknown", v, null), direct("known", v, 100_000), prep("cook", r, 1000)))
        val report = report()
        assertTrue(report.hasUnknownRequirements)
        assertEquals(2, report.shopping.single().unknownRequirements); assertEquals(0L, report.shopping.single().knownMissing)
        assertEquals(100_000L, report.entries.flatMap { it.demands }.sumOf { it.covered })
        rejects { store.savePlan("wrong-unit", input(direct("wrong", ml, 1000))) }
        rejects { store.savePlan("missing-ref", input(portion("eat", "missing", 1000, today))) }
        rejects { store.savePlan("too-early", input(prep("cook", r, 1000, "2026-11-01"), portion("eat", "cook", 1000, today))) }
        rejects { store.savePlan("zero", input(direct("z", v, 0))) }
        rejects { store.savePlan("outside", input(direct("outside", v, 1000, "2026-12-25"))) }
        rejects { store.savePlan("long", input().copy(endsOn = "2026-12-25")) }
        assertNull(dao.operation("wrong-unit")); assertEquals(1, dao.allPlans().size)
    }
    @Test fun replacementAndCancellationPreserveOldVersionsRemapLinksAndKeepRetryKeysStable() = runBlocking {
        val v = product(); val r = recipe(v)
        val originalInput = input(prep("cook", r, 6000), portion("eat", "cook", 2000, "2026-10-31"))
        val first = store.savePlan("first", originalInput)
        val record = report().plans.single()
        val edited = originalInput.copy(entries = record.entries.map(PlanEntryInput::from).map {
            if (it.kind == PlanKind.PREPARATION) it.copy(amount = 3000) else it
        }, reason = "Weniger kochen")
        val second = store.savePlan("second", edited, first)
        assertEquals(second, store.savePlan("second", edited, first))
        val current = report().plans.single()
        assertEquals(2, current.version.revision); assertEquals(first, dao.plan(first)!!.id)
        val prep = current.entries.single { it.kind == PlanKind.PREPARATION }
        assertEquals(prep.id, current.entries.single { it.kind == PlanKind.PREPARED_PORTION }.preparationEntryId)
        assertFalse(record.entries.any { old -> current.entries.any { it.id == old.id } })
        rejects { store.savePlan("stale", edited, first) }
        rejects { store.savePlan("second", edited.copy(title = "Anders"), first) }
        val cancelled = store.cancelPlan("cancel", second, "Nicht mehr vorgesehen")
        assertEquals(cancelled, store.cancelPlan("cancel", second, "Nicht mehr vorgesehen"))
        assertEquals(first, store.savePlan("first", originalInput))
        assertTrue(report().entries.isEmpty()); assertTrue(report().shopping.isEmpty())
        assertEquals(EntryStatus.CANCELLED, report().plans.single().version.status)
        assertEquals(3, dao.allPlans().size); assertEquals(6, dao.allPlannedEntries().size)
    }
    @Test fun concurrentEditsCannotSilentlyReplaceOneAnother() = runBlocking {
        val v = product(); val initial = store.savePlan("initial", input(direct("a", v, 1000)))
        val results = listOf("one", "two").map { key -> async(Dispatchers.IO) {
            try { store.savePlan(key, input(direct("a", v, 2000)), initial); true }
            catch (_: IllegalArgumentException) { false }
        } }.awaitAll()
        assertEquals(1, results.count { it }); assertEquals(2, dao.allPlans().size)
        assertEquals(2000L, report().entries.single().demands.single().missing)
    }
    @Test fun sqliteFailureRollsBackIdentityVersionEntriesAndOperationTogether() = runBlocking {
        val v = product()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_plan_entry BEFORE INSERT ON planned_entries WHEN NEW.label = 'FAIL' BEGIN SELECT RAISE(ABORT, 'injected planned entry failure'); END")
        val data = input(direct("a", v, 1000), direct("b", v, 1000).copy(label = "FAIL"))
        try { store.savePlan("failure", data); fail("Expected SQLite abort") }
        catch (error: Exception) { assertTrue(error.message.orEmpty().contains("injected planned entry failure")) }
        assertTrue(dao.allPlans().isEmpty()); assertTrue(dao.allPlannedEntries().isEmpty()); assertNull(dao.operation("failure"))
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM plan_series").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_plan_entry")
        store.savePlan("failure", data); assertEquals(2, report().entries.size)
    }
    @Test fun fractionalIngredientDemandRoundsUpAndOversizedOrUnknownDemandNeverBecomesZero() = runBlocking {
        val v = product(); val tiny = recipe(v, 1, 3000); val huge = recipe(v, Amounts.MAX, 1)
        store.savePlan("p", input(prep("tiny", tiny, 1000), prep("huge", huge, Amounts.MAX)))
        val tinyLine = report().entries.single { it.entry.amount == 1000L }.demands.single()
        val hugeLine = report().entries.single { it.entry.amount == Amounts.MAX }.demands.single()
        assertEquals(1L, tinyLine.required); assertEquals(1L, tinyLine.missing)
        assertNull(hugeLine.required); assertNull(hugeLine.missing)
        assertTrue(PlanningIssue.QUANTITY_RANGE in hugeLine.issues)
        assertTrue(report().hasUnknownRequirements)
    }
    @Test fun pastPreparationCannotBecomeAssumedStockForFutureMealsAndVersionsRemainSpecific() = runBlocking {
        val v = product(); val r = recipe(v)
        val data = input(prep("cook", r, 6000, "2026-10-29"), portion("eat", "cook", 1000, today)).copy(startsOn = "2026-10-29", endsOn = "2026-11-01")
        store.savePlan("p", data)
        val current = report().entries.single()
        assertTrue(PlanningIssue.PAST_PREPARATION in current.issues); assertNull(current.projectedPortions)
        val identity = dao.version(v)!!.productId
        val newer = store.rememberProduct("new", ProductInput("Neues Etikett"), identity, "Korrektur")
        stock(v, "old-stock", 100_000)
        store.savePlan("exact", input(direct("v2", newer, 100_000)))
        assertEquals(100_000L, report().entries.flatMap { it.demands }.single { it.versionId == newer }.missing)
    }
    @Test fun reactiveRecalculationAndFileReopeningPreservePlansAndUseActualRemainingStock() = runBlocking {
        db.close(); val name = "planning-${UUID.randomUUID()}.db"
        try {
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            val v = product(); val b = stock(v, "b", 200_000)
            store.savePlan("p", input(direct("a", v, 300_000, "2026-10-31")))
            val reports = Channel<PlanningReport>(Channel.UNLIMITED)
            val job = launch(Dispatchers.IO) { store.watchPlanning(today, "2026-12-24").collect { reports.send(it) } }
            try {
                assertEquals(100_000L, withTimeout(10_000) { reports.receive() }.shopping.single().knownMissing)
                store.consume("actual", ConsumptionInput(v, today, "Tatsächlich", 50_000, listOf(Allocation(b, 50_000))), true)
                val next = withTimeout(10_000) {
                    var value = reports.receive()
                    while (value.shopping.single().knownMissing != 150_000L) value = reports.receive()
                    value
                }
                assertEquals(150_000L, next.shopping.single().knownMissing)
            } finally { job.cancelAndJoin(); reports.close() }
            val before = report(); db.close()
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            assertEquals(before, report()); assertEquals(150_000L, dao.balance(b)); assertEquals(8, db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
