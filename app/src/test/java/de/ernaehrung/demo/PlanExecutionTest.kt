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
class PlanExecutionTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = db.foodDao()
    private val day = "2026-10-30"
    private val clock = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build(); store = FoodStore(db) { clock } }
    @After fun close() { db.close() }
    private suspend fun product(key: String = "v") = store.rememberProduct(key, ProductInput(key, energyKcal = 125.0))
    private suspend fun stock(v: String, amount: Long, date: String = day): String {
        val location = store.addLocation("loc-$v", "Lager", true)
        return store.receive("stock-$v", BatchInput(v, location, amount, date, useBy = "2026-11-05", release = ReleaseStatus.RELEASED), true)
    }
    private fun direct(key: String, v: String, amount: Long?, date: String = day) =
        PlanEntryInput(key, date, PlanKind.DIRECT_CONSUMPTION, productVersionId = v, amount = amount, unit = BaseUnit.GRAM)
    private fun input(vararg entries: PlanEntryInput) = PlanInput("Ausführung", "2026-10-29", "2026-11-05", entries.toList(), "Planung")
    private suspend fun entry(key: String, v: String, amount: Long? = 200_000): PlannedEntryEntity {
        val plan = store.savePlan(key, input(direct(key, v, amount)))
        return dao.allPlannedEntries().single { it.planId == plan }
    }
    private suspend fun meal(key: String, v: String, amount: Long?, batch: String? = null, date: String = day) {
        store.consume(key, ConsumptionInput(v, date, key, amount,
            if (batch == null) emptyList() else listOf(Allocation(batch, requireNotNull(amount)))), true)
    }
    private suspend fun link(key: String, entry: PlannedEntryEntity, actual: String, deviations: Boolean = true): String =
        store.linkPlanExecution(key, LinkPlanExecutionInput(entry.id, actual, store.planExecution(entry.id).status.snapshot,
            "Tatsächliche Ausführung geprüft", deviations), true)
    private suspend fun report(start: String = day) = store.planningReport(start, "2026-11-05")
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected write") } catch (_: IllegalArgumentException) { }
    }

    @Test fun partialConsumptionReducesOnlyOutstandingDemandAndNeverBooksFoodTwice() = runBlocking {
        val v = product(); val batch = stock(v, 200_000); val e = entry("p", v)
        meal("eat", v, 50_000, batch)
        val nutrition = store.dayNutrition(day)
        assertEquals(50_000L, report().shopping.single().knownMissing)
        val movements = dao.movements("eat")
        link("link", e, "eat")
        val status = store.planExecution(e.id).status
        assertEquals(50_000L, status.knownExecuted); assertEquals(150_000L, status.remainingAmount)
        assertEquals(150_000L, report().entries.single().demands.single().covered)
        assertTrue(report().shopping.isEmpty())
        assertEquals(150_000L, dao.balance(batch)); assertEquals(movements, dao.movements("eat"))
        assertTrue(dao.movements("link").isEmpty()); assertEquals(1, dao.consumptionCount())
        assertEquals(nutrition, store.dayNutrition(day))
    }
    @Test fun claimReleaseRetryAndReassignmentKeepOneActualEventInOnlyOnePosition() = runBlocking {
        val v = product(); val a = entry("a", v, 50_000); val b = entry("b", v, 50_000)
        meal("eat", v, 50_000)
        val original = LinkPlanExecutionInput(a.id, "eat", store.planExecution(a.id).status.snapshot, "Geprüft")
        store.linkPlanExecution("link", original, true)
        rejects { link("duplicate", b, "eat") }
        val release = UnlinkPlanExecutionInput("link", "Andere Planposition")
        store.unlinkPlanExecution("release", release, true)
        assertEquals("release", store.unlinkPlanExecution("release", release, true))
        assertEquals("link", store.linkPlanExecution("link", original, true)) // old retry cannot reactivate
        assertEquals(50_000L, store.planExecution(a.id).status.remainingAmount)
        link("new-link", b, "eat", false)
        rejects { store.unlinkPlanExecution("stale-release", UnlinkPlanExecutionInput("link", "Alter Aufruf"), true) }
        assertEquals(50_000L, store.planExecution(a.id).status.remainingAmount)
        assertEquals(0L, store.planExecution(b.id).status.remainingAmount)
        assertEquals(3, dao.allPlanExecutions().size); assertEquals(1, dao.consumptionCount())
    }
    @Test fun concurrentClaimsAreSerializedAndCannotCountOneMealTwice() = runBlocking {
        val v = product(); val a = entry("a", v, 50_000); val b = entry("b", v, 50_000); meal("eat", v, 50_000)
        val inputs = listOf(a, b).map { LinkPlanExecutionInput(it.id, "eat", store.planExecution(it.id).status.snapshot, "Geprüft") }
        val results = inputs.mapIndexed { index, data -> async(Dispatchers.IO) {
            try { store.linkPlanExecution("claim-$index", data, true); true } catch (_: IllegalArgumentException) { false }
        } }.awaitAll()
        assertEquals(1, results.count { it }); assertEquals(1, dao.allPlanExecutions().size)
        assertEquals(50_000L, listOf(a, b).sumOf { store.planExecution(it.id).status.knownExecuted })
    }
    @Test fun wrongSourceVersionReversalStalePlanAndMissingConfirmationAreRejected() = runBlocking {
        val v = product(); val other = product("other"); val e = entry("p", v)
        meal("eat", v, 50_000); meal("wrong", other, 50_000)
        val data = LinkPlanExecutionInput(e.id, "eat", store.planExecution(e.id).status.snapshot, "Geprüft")
        rejects { store.linkPlanExecution("not-confirmed", data, false) }
        rejects { store.linkPlanExecution("no-reason", data.copy(reason = ""), true) }
        rejects { store.linkPlanExecution("no-deviation", data, true) }
        rejects { link("wrong-product", e, "wrong") }
        rejects { link("product-operation", e, "v") }
        val record = report().plans.single()
        store.savePlan("edit", input(*record.entries.map(PlanEntryInput::from).toTypedArray()), record.version.id)
        rejects { store.linkPlanExecution("stale", data.copy(deviationAccepted = true), true) }
        val current = report().plans.single().entries.single()
        store.reverse("reverse-eat", ReversalInput("eat", day, "Falsch erfasst"), true)
        rejects { link("reversed", current, "eat") }
        assertTrue(dao.allPlanExecutions().isEmpty())
        for (key in listOf("not-confirmed", "no-reason", "no-deviation", "wrong-product", "product-operation", "stale", "reversed")) assertNull(dao.operation(key))
    }
    @Test fun unknownActualQuantityMakesRemainingUnknownInsteadOfClosingThePlan() = runBlocking {
        val v = product(); val e = entry("p", v); meal("unknown", v, null)
        link("link", e, "unknown")
        val status = store.planExecution(e.id).status
        assertTrue(status.hasUnknownExecuted); assertNull(status.remainingAmount)
        assertNull(report().entries.single().demands.single().required)
        assertEquals(1, report().shopping.single().unknownRequirements); assertTrue(report().hasUnknownRequirements)
        store.unlinkPlanExecution("undo", UnlinkPlanExecutionInput("link", "Menge erst klären"), true)
        assertEquals(200_000L, store.planExecution(e.id).status.remainingAmount)
        assertNull(dao.consumptionForOperation("unknown")!!.amount)
    }
    @Test fun positionIdentitySurvivesVersionsAndChangedExecutedAmountsRequireExplicitCarry() = runBlocking {
        val v = product(); val e = entry("p", v); meal("eat", v, 250_000); link("link", e, "eat")
        assertEquals(0L, store.planExecution(e.id).status.remainingAmount)
        var record = report().plans.single()
        val changed = input(*record.entries.map { PlanEntryInput.from(it).copy(amount = 300_000, date = "2026-10-31") }.toTypedArray())
        rejects { store.savePlan("silent-carry", changed, record.version.id) }
        val second = store.savePlan("carry", changed.copy(carryExecutionsConfirmed = true), record.version.id)
        record = report().plans.single()
        val current = record.entries.single()
        assertEquals(second, current.planId); assertNotEquals(e.id, current.id); assertEquals(e.positionId, current.positionId)
        assertEquals(250_000L, store.planExecution(current.id).status.knownExecuted)
        assertEquals(50_000L, store.planExecution(current.id).status.remainingAmount)
        val other = product("other")
        rejects { store.savePlan("changed-meaning", input(PlanEntryInput.from(current).copy(productVersionId = other)), second) }
        rejects { store.savePlan("stolen-identity", input(PlanEntryInput.from(current))) }
        rejects { store.savePlan("duplicate-position", input(PlanEntryInput.from(current), PlanEntryInput.from(current).copy(key = "another-key")), second) }
        store.cancelPlan("cancel", second, "Termin prüfen")
        assertTrue(report().entries.isEmpty())
        val cancelled = report().plans.single()
        store.savePlan("restore", input(*cancelled.entries.map(PlanEntryInput::from).toTypedArray()), cancelled.version.id)
        val restored = report().plans.single().entries.single()
        assertEquals(e.positionId, restored.positionId); assertEquals(50_000L, store.planExecution(restored.id).status.remainingAmount)
        assertEquals(1, dao.consumptionCount()); assertEquals(1, dao.allPlanExecutions().size)
    }
    @Test fun snapshotDetectsSameClockClaimReleaseCyclesAndOldRetryNeverReactivates() = runBlocking {
        val v = product(); val e = entry("p", v, 50_000); meal("eat", v, 50_000)
        val old = LinkPlanExecutionInput(e.id, "eat", store.planExecution(e.id).status.snapshot, "Geprüft")
        store.linkPlanExecution("first", old, true)
        store.unlinkPlanExecution("release", UnlinkPlanExecutionInput("first", "Erneut prüfen"), true)
        rejects { store.linkPlanExecution("stale-snapshot", old, true) }
        assertEquals("first", store.linkPlanExecution("first", old, true))
        assertEquals(50_000L, store.planExecution(e.id).status.remainingAmount)
        assertNotEquals(old.expectedSnapshot, store.planExecution(e.id).status.snapshot)
    }
    @Test fun removingAClaimedPositionLeavesReachableHistoryAndRequiresExplicitReleaseBeforeReassignment() = runBlocking {
        val v = product(); val e = entry("p", v, 50_000); meal("eat", v, 50_000); link("link", e, "eat", false)
        store.savePlan("remove", input(), e.planId)
        assertEquals(e.id, report().detachedExecutionEntries.single().id)
        assertFalse(store.planExecution(e.id).current)
        val other = entry("other-plan", v, 50_000)
        rejects { link("claimed", other, "eat", false) }
        store.unlinkPlanExecution("release-old", UnlinkPlanExecutionInput("link", "Frühere Position entfernt"), true)
        assertTrue(report().detachedExecutionEntries.isEmpty())
        link("reassign", other, "eat", false)
        assertEquals(0L, store.planExecution(other.id).status.remainingAmount)
        assertEquals(1, dao.consumptionCount())
    }

    private data class Cooking(val v: String, val raw: String, val recipe: String, val prep: PlannedEntryEntity, val meals: List<PlannedEntryEntity>)
    private suspend fun cooking(portions: Long = 6000, prepDate: String = day, mealDate: String = "2026-10-31", mealAmount: Long = 6000): Cooking {
        val v = product(); val raw = stock(v, 300_000, prepDate)
        val recipe = store.saveRecipe("recipe", RecipeInput("Gericht", 6000, listOf(RecipeItemInput(v, 300_000)), "Garen"))
        val plan = store.savePlan("plan", input(PlanEntryInput("prep", prepDate, PlanKind.PREPARATION,
            recipeVersionId = recipe, amount = portions, unit = BaseUnit.PORTION), PlanEntryInput("meal", mealDate,
            PlanKind.PREPARED_PORTION, amount = mealAmount, unit = BaseUnit.PORTION, preparationKey = "prep")))
        val entries = dao.allPlannedEntries().filter { it.planId == plan }
        return Cooking(v, raw, recipe, entries.single { it.kind == PlanKind.PREPARATION }, entries.filter { it.kind == PlanKind.PREPARED_PORTION })
    }
    private suspend fun cook(c: Cooking, key: String, portions: Long, amount: Long, date: String = day): String = store.produce(key,
        ProductionInput(c.recipe, portions, listOf(Allocation(c.raw, amount)), dao.batch(c.raw)!!.locationId, date,
            true, "Bedingungen und Platz geprüft", ReleaseStatus.RELEASED, useBy = "2026-11-05"), true)

    @Test fun partialThenFullCookingUsesActualOutputStockAndOnlyRemainingRecipeIngredients() = runBlocking {
        val c = cooking(); val output = cook(c, "cook-four", 4000, 200_000)
        link("link-four", c.prep, "cook-four")
        var report = report()
        val prep = report.entries.single { it.entry.kind == PlanKind.PREPARATION }
        assertEquals(2000L, prep.execution!!.remainingAmount)
        assertEquals(100_000L, prep.demands.single().required); assertEquals(100_000L, prep.demands.single().covered)
        val portions = report.entries.single { it.entry.kind == PlanKind.PREPARED_PORTION }
        assertEquals(4000L, portions.actualPortionsCovered); assertEquals(2000L, portions.projectedPortions); assertEquals(0L, portions.missingPortions)
        assertTrue(report.shopping.isEmpty()); assertEquals(4000L, dao.balance(output))
        cook(c, "cook-two", 2000, 100_000); link("link-two", c.prep, "cook-two")
        report = report()
        assertTrue(report.entries.single { it.entry.kind == PlanKind.PREPARATION }.demands.isEmpty())
        assertEquals(6000L, report.entries.single { it.entry.kind == PlanKind.PREPARED_PORTION }.actualPortionsCovered)
        assertTrue(report.shopping.isEmpty()); assertEquals(0L, dao.balance(c.raw)); assertEquals(0, dao.consumptionCount())
    }
    @Test fun assignedPortionConsumptionAndItsActualReversalUpdateRemainingWithoutDoubleCounting() = runBlocking {
        val c = cooking(mealDate = day, mealAmount = 2000); val out = cook(c, "cook", 6000, 300_000)
        link("prep-link", c.prep, "cook", false)
        val version = dao.batch(out)!!.versionId
        meal("eat", version, 2000, out)
        link("meal-link", c.meals.single(), "eat", false)
        val before = store.dayNutrition(day)
        assertEquals(0L, report().entries.single { it.entry.kind == PlanKind.PREPARED_PORTION }.execution!!.remainingAmount)
        assertEquals(4000L, dao.balance(out)); assertEquals(0L, dao.balance(c.raw))
        assertEquals(before, store.dayNutrition(day))
        val record = report().plans.single()
        store.savePlan("new-version", input(*record.entries.map(PlanEntryInput::from).toTypedArray()), record.version.id)
        val copied = report().plans.single().entries
        val copiedPrep = copied.single { it.kind == PlanKind.PREPARATION }
        val copiedMeal = copied.single { it.kind == PlanKind.PREPARED_PORTION }
        assertEquals(copiedPrep.id, copiedMeal.preparationEntryId)
        assertEquals(c.prep.positionId, copiedPrep.positionId)
        assertEquals(c.meals.single().positionId, copiedMeal.positionId)
        assertEquals(2000L, store.planExecution(copiedMeal.id).status.knownExecuted)
        assertFalse(store.planExecution(copiedMeal.id).status.referenceConflict)
        store.reverse("storno", ReversalInput("eat", day, "Mahlzeit falsch"), true)
        val entry = report().entries.single { it.entry.kind == PlanKind.PREPARED_PORTION }
        assertEquals(2000L, entry.execution!!.remainingAmount); assertEquals(2000L, entry.actualPortionsCovered)
        assertEquals(6000L, dao.balance(out)); assertTrue(store.dayNutrition(day).meals.isEmpty())
        assertEquals(2, dao.allPlanExecutions().size); assertTrue(entry.execution.events.single().actual!!.reversed)
    }
    @Test fun reversingActualCookingReopensRawRequirementAndNeverKeepsPhantomPortions() = runBlocking {
        val c = cooking(); val out = cook(c, "cook", 6000, 300_000); link("link", c.prep, "cook", false)
        store.reverse("storno", ReversalInput("cook", day, "Herstellung falsch"), true)
        val report = report()
        assertEquals(6000L, report.entries.single { it.entry.kind == PlanKind.PREPARATION }.execution!!.remainingAmount)
        assertEquals(300_000L, report.entries.single { it.entry.kind == PlanKind.PREPARATION }.demands.single().covered)
        val meal = report.entries.single { it.entry.kind == PlanKind.PREPARED_PORTION }
        assertEquals(0L, meal.actualPortionsCovered); assertEquals(6000L, meal.projectedPortions)
        assertEquals(0L, dao.balance(out)); assertEquals(300_000L, dao.balance(c.raw))
    }
    @Test fun productionAssignmentCannotBeReleasedBeforeDependentMealAndReleaseDoesNotReverseRealEvents() = runBlocking {
        val c = cooking(mealDate = day, mealAmount = 2000); val out = cook(c, "cook", 6000, 300_000)
        link("prep-link", c.prep, "cook", false)
        meal("eat", dao.batch(out)!!.versionId, 2000, out); link("meal-link", c.meals.single(), "eat", false)
        rejects { store.unlinkPlanExecution("blocked", UnlinkPlanExecutionInput("prep-link", "Anders zuordnen"), true) }
        assertNull(dao.operation("blocked"))
        store.unlinkPlanExecution("undo-meal", UnlinkPlanExecutionInput("meal-link", "Andere Planposition"), true)
        store.unlinkPlanExecution("undo-prep", UnlinkPlanExecutionInput("prep-link", "Andere Planung"), true)
        assertEquals(4000L, dao.balance(out)); assertEquals(0L, dao.balance(c.raw)); assertEquals(1, dao.activeConsumptionCount())
        assertTrue(dao.reversal("cook") == null && dao.reversal("eat") == null)
    }
    @Test fun linkedMealsRequireTheAssignedProductionVersionAndTransfersRetainThatOrigin() = runBlocking {
        val c = cooking(mealDate = day, mealAmount = 1000)
        val a = cook(c, "cook-a", 3000, 150_000); val b = cook(c, "cook-b", 3000, 150_000)
        link("prep-link", c.prep, "cook-a")
        meal("wrong-source", dao.batch(b)!!.versionId, 1000, b)
        rejects { link("wrong-link", c.meals.single(), "wrong-source") }
        val loc = store.addLocation("other-loc", "Anderes Lager", true)
        val moved = store.transfer("move", TransferInput(a, loc, 1000, day, "Geeignete Bedingungen", true), true)
        meal("right-source", dao.batch(moved)!!.versionId, 1000, moved)
        link("right-link", c.meals.single(), "right-source", false)
        assertEquals(0L, store.planExecution(c.meals.single().id).status.remainingAmount)
        assertEquals(2, dao.consumptionCount())
    }
    @Test fun pastPartiallyExecutedPreparationCanSupplyFutureMealsFromKnownPhysicalStock() = runBlocking {
        val c = cooking(prepDate = "2026-10-29", mealAmount = 2000)
        val out = cook(c, "past-cook", 4000, 200_000, "2026-10-29"); link("past-link", c.prep, "past-cook")
        val result = report().entries.single()
        assertEquals(2000L, result.actualPortionsCovered); assertEquals(0L, result.projectedPortions); assertEquals(0L, result.missingPortions)
        assertFalse(PlanningIssue.PAST_PREPARATION in result.issues)
        assertEquals(4000L, dao.balance(out)); assertEquals(100_000L, dao.balance(c.raw))
    }
    @Test fun sqliteFailureRollsBackClaimAndItsAuditOperationAndAllowsSafeRetry() = runBlocking {
        val v = product(); val e = entry("p", v, 50_000); meal("eat", v, 50_000)
        val before = store.planExecution(e.id).status
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_execution BEFORE INSERT ON plan_executions BEGIN SELECT RAISE(ABORT, 'injected execution failure'); END")
        try { link("failure", e, "eat", false); fail("Expected SQLite failure") }
        catch (error: Exception) { assertTrue(error.message.orEmpty().contains("injected execution failure")) }
        assertNull(dao.operation("failure")); assertTrue(dao.allPlanExecutions().isEmpty()); assertEquals(before, store.planExecution(e.id).status)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_execution")
        link("failure", e, "eat", false); assertEquals(0L, store.planExecution(e.id).status.remainingAmount)
    }
    @Test fun reactivePlanningAndExecutionFactsSurviveFileReopening() = runBlocking {
        db.close(); val name = "execution-${UUID.randomUUID()}.db"
        try {
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            val v = product(); val batch = stock(v, 200_000); val e = entry("p", v); meal("eat", v, 50_000, batch)
            val changes = Channel<PlanningReport>(Channel.UNLIMITED)
            val job = launch(Dispatchers.IO) { store.watchPlanning(day, "2026-11-05").collect { changes.send(it) } }
            try {
                assertEquals(50_000L, withTimeout(10_000) { changes.receive() }.shopping.single().knownMissing)
                link("link", e, "eat")
                val updated = withTimeout(10_000) { var r = changes.receive(); while (r.shopping.isNotEmpty()) r = changes.receive(); r }
                assertEquals(150_000L, updated.entries.single().execution!!.remainingAmount)
            } finally { job.cancelAndJoin(); changes.close() }
            val before = report(); val execution = store.planExecution(e.id); db.close()
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            assertEquals(before, report()); assertEquals(execution, store.planExecution(e.id)); assertEquals(150_000L, dao.balance(batch))
            assertEquals(8, db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
