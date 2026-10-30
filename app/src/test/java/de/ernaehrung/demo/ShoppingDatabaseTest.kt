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
class ShoppingDatabaseTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = db.foodDao()
    private val day = "2026-10-30"
    private val clock = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private val scope = ShoppingScope(day, "2026-11-05", day)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build(); store = FoodStore(db) { clock } }
    @After fun close() { db.close() }
    private suspend fun product(key: String = "p", unit: BaseUnit = BaseUnit.GRAM) = store.rememberProduct(key, ProductInput(key, unit = unit))
    private fun plan(v: String, amount: Long? = 350_000, unknown: Boolean = false) = PlanInput("Testwoche", day, "2026-11-05",
        listOf(PlanEntryInput("known", day, PlanKind.DIRECT_CONSUMPTION, v, amount = amount, unit = BaseUnit.GRAM)) +
            if (unknown) listOf(PlanEntryInput("unknown", "2026-10-31", PlanKind.DIRECT_CONSUMPTION, v, amount = null, unit = BaseUnit.GRAM)) else emptyList(), "Planung")
    private fun price(v: String, amount: Long? = 200_000, cents: Long? = 199) = PriceInput(v, "Testmarkt", day,
        amount, cents, true, amount != null, if (amount != null) "Bestätigte nutzbare Gramm je Kaufpackung" else null,
        day, "2026-11-05", cents != null, "Etikett und Angebot geprüft")
    private suspend fun report(s: ShoppingScope = scope) = store.shoppingReport(s)
    private suspend fun mark(key: String, v: String, offer: String?, checked: Boolean = false, s: ShoppingScope = scope): String {
        val row = report(s).items.single { it.need.versionId == v }
        return store.markShopping(key, ShoppingMarkInput(s, v, offer, checked, row.snapshot))
    }
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected write") } catch (_: IllegalArgumentException) { }
    }

    @Test fun chosenWholePacksRoundKnownShortageAndCheckingCreatesNoPurchaseExpenseOrStock() = runBlocking {
        val v = product(); store.savePlan("plan", plan(v)); val offer = store.savePrice("price", price(v))
        assertNull(report().items.single().exactPackCount) // no silent offer selection
        val quote = report().items.single().quotes.single()
        assertEquals(2L, quote.exactPackCount); assertEquals(398L, quote.costCents)
        mark("select", v, offer, true)
        val result = report(); val row = result.items.single()
        assertEquals(2L, row.exactPackCount); assertEquals(400_000L, row.purchasedAmount); assertEquals(50_000L, row.surplus)
        assertEquals(398L, result.estimatedTotalCents); assertTrue(row.checked); assertFalse(result.incomplete)
        assertTrue(dao.planningBatches().isEmpty()); assertEquals(0, dao.consumptionCount()); assertTrue(dao.movements("select").isEmpty())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM receipts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        assertEquals(350_000L, store.planningReport(day, "2026-11-05").shopping.single().knownMissing)
    }
    @Test fun legacyPriceQuantityQualityAndProductPackageAmountDoNotProveAConfirmedSalePack() = runBlocking {
        val v = store.rememberProduct("p", ProductInput("Dose", packageAmount = 400_000))
        store.savePlan("plan", plan(v))
        dao.insertPriceObservation(PriceObservationEntity("legacy", v, "Altmarkt", day, 400_000, 199, Source.RETAILER, Quality.CONFIRMED))
        mark("select", v, "legacy")
        val row = report().items.single()
        assertNull(row.exactPackCount); assertNull(row.calculatedCostCents); assertNull(report().estimatedTotalCents)
        assertTrue(ShoppingIssue.PACK_BASIS_UNKNOWN in row.issues); assertTrue(ShoppingIssue.CURRENCY_UNKNOWN in row.issues)
    }
    @Test fun unknownPriceQuantityAndConfirmedZeroRemainDistinct() = runBlocking {
        val v = product(); store.savePlan("plan", plan(v))
        val missing = store.savePrice("missing", price(v, cents = null)); mark("select-missing", v, missing)
        assertEquals(2L, report().items.single().exactPackCount); assertNull(report().items.single().calculatedCostCents)
        assertNull(report().estimatedTotalCents)
        val free = store.savePrice("free", price(v, cents = 0), missing); mark("select-free", v, free)
        assertEquals(0L, report().estimatedTotalCents); assertFalse(report().incomplete)
        val previous = store.planningReport(day, "2026-11-05").plans.single().version.id
        store.savePlan("unknown-plan", plan(v, unknown = true), previous)
        mark("refresh", v, free)
        val row = report().items.single()
        assertEquals(2L, row.packsForKnownNeed); assertNull(row.exactPackCount)
        assertEquals(0L, report().knownSubtotalCents); assertNull(report().estimatedTotalCents); assertTrue(report().incomplete)
    }
    @Test fun priceValidityQualityAndPurchaseTimingAreCheckedSeparately() = runBlocking {
        val v = product(); store.savePlan("plan", plan(v))
        val expired = store.savePrice("expired", price(v).copy(validFrom = "2026-10-01", validUntil = "2026-10-29"))
        mark("select-expired", v, expired)
        assertEquals(2L, report().items.single().exactPackCount); assertNull(report().estimatedTotalCents)
        assertTrue(ShoppingIssue.PRICE_NOT_VALID in report().items.single().issues)
        val unknownPeriod = store.savePrice("unknown-period", price(v).copy(validUntil = null), expired)
        mark("select-unknown", v, unknownPeriod)
        assertTrue(ShoppingIssue.PRICE_VALIDITY_UNKNOWN in report().items.single().issues)
        val unconfirmed = store.savePrice("unconfirmed", price(v).copy(priceConfirmed = false), unknownPeriod)
        mark("select-unconfirmed", v, unconfirmed)
        assertTrue(ShoppingIssue.PRICE_UNCONFIRMED in report().items.single().issues); assertNull(report().estimatedTotalCents)
        val current = store.savePrice("current", price(v), unconfirmed)
        val late = scope.copy(purchaseOn = "2026-10-31"); mark("late", v, current, s = late)
        assertTrue(ShoppingIssue.PURCHASE_TOO_LATE in report(late).items.single().issues)
        assertEquals(398L, report(late).knownSubtotalCents); assertNull(report(late).estimatedTotalCents)
        rejects { report(scope.copy(purchaseOn = "2026-10-29")) }
    }
    @Test fun priceReplacementPreservesHistoryInvalidatesChecksAndRequiresFreshExplicitSelection() = runBlocking {
        val v = product(); store.savePlan("plan", plan(v)); val firstInput = price(v)
        val first = store.savePrice("first", firstInput); mark("first-check", v, first, true)
        val secondInput = price(v, 600_000, 299).copy(reason = "Neue Packung ausdrücklich geprüft")
        val second = store.savePrice("second", secondInput, first)
        assertEquals(second, store.savePrice("second", secondInput, first))
        assertEquals(first, store.savePrice("first", firstInput))
        assertTrue(report().items.single().staleCheck); assertFalse(report().items.single().checked)
        assertTrue(ShoppingIssue.OBSOLETE_OFFER in report().items.single().issues)
        assertNull(report().estimatedTotalCents); assertEquals(2, dao.prices().size)
        rejects { store.savePrice("stale", secondInput, first) }
        mark("new-choice", v, second, true)
        assertEquals(1L, report().items.single().exactPackCount); assertEquals(250_000L, report().items.single().surplus)
        assertEquals(299L, report().estimatedTotalCents); assertTrue(report().items.single().checked)
    }
    @Test fun listRetriesAndSameClockStateCyclesCannotReapplyOldChecksOrLoseConcurrentChanges() = runBlocking {
        val v = product(); store.savePlan("plan", plan(v)); val offer = store.savePrice("price", price(v))
        val initial = report().items.single()
        val first = ShoppingMarkInput(scope, v, offer, true, initial.snapshot)
        store.markShopping("check", first)
        mark("uncheck", v, offer, false)
        assertEquals("check", store.markShopping("check", first)); assertFalse(report().items.single().checked)
        rejects { store.markShopping("stale", first) }
        val row = report().items.single()
        val outcomes = listOf(true, false).mapIndexed { i, checked -> async(Dispatchers.IO) {
            try { store.markShopping("race-$i", ShoppingMarkInput(scope, v, offer, checked, row.snapshot)); true }
            catch (_: IllegalArgumentException) { false }
        } }.awaitAll()
        assertEquals(1, outcomes.count { it }); assertEquals(3, dao.shoppingMarks(day, "2026-11-05", day).size)
    }
    @Test fun checkingAnotherItemDoesNotInvalidateExistingChecksButPlanAbaDoes() = runBlocking {
        val a = product("a"); val b = product("b")
        store.savePlan("a-plan", plan(a)); store.savePlan("b-plan", plan(b))
        val x = store.savePrice("a-price", price(a)); val y = store.savePrice("b-price", price(b))
        mark("a-check", a, x, true); mark("b-check", b, y, true)
        assertTrue(report().items.all { it.checked })
        var current = store.planningReport(day, "2026-11-05").plans.single { it.entries.single().productVersionId == a }.version.id
        current = store.savePlan("changed", plan(a, 400_000), current)
        store.savePlan("same-again", plan(a), current)
        assertEquals(350_000L, report().items.single { it.need.versionId == a }.need.knownMissing)
        assertTrue(report().items.all { !it.checked && it.staleCheck })
    }
    @Test fun budgetsUseSelectedLatestPeriodAndNeverInventPaidExpensesOrRemainingBudget() = runBlocking {
        val v = product(); store.savePlan("plan", plan(v)); val offer = store.savePrice("price", price(v)); mark("select", v, offer)
        val budgetInput = BudgetInput("Woche", day, "2026-11-05", 300, true, "Bestätigter Rahmen")
        val first = store.saveBudget("budget", budgetInput)
        var result = report(scope.copy(budgetId = first))
        assertEquals(300L, result.budget.usableLimitCents); assertEquals(98L, result.budget.knownCostsOverLimitCents)
        assertNull(result.budget.remainingBudgetCents)
        val second = store.saveBudget("budget-new", budgetInput.copy(amountCents = 500), first)
        assertNull(report(scope.copy(budgetId = first)).budget.usableLimitCents)
        result = report(scope.copy(budgetId = second))
        assertEquals(0L, result.budget.knownCostsOverLimitCents); assertNull(result.budget.remainingBudgetCents)
        assertEquals(first, store.saveBudget("budget", budgetInput))
        val outside = store.saveBudget("other-period", budgetInput.copy(startsOn = "2026-11-01", endsOn = "2026-11-30"))
        assertNull(report(scope.copy(budgetId = outside)).budget.usableLimitCents)
        val zero = store.saveBudget("zero", budgetInput.copy(amountCents = 0))
        assertEquals(0L, report(scope.copy(budgetId = zero)).budget.usableLimitCents)
        val unknown = store.saveBudget("unknown", budgetInput.copy(amountCents = null, confirmed = false))
        assertNull(report(scope.copy(budgetId = unknown)).budget.usableLimitCents)
    }
    @Test fun invalidVersionUnitsAmountsAndConfirmationsRollBackWithoutInventingConversions() = runBlocking {
        val v = product(); val ml = product("ml", BaseUnit.MILLILITRE); store.savePlan("plan", plan(v))
        val price = store.savePrice("price", price(v)); val wrong = store.savePrice("ml-price", price(ml))
        rejects { mark("wrong-offer", v, wrong) }
        rejects { store.savePrice("switch", price(ml), price) }
        rejects { store.savePrice("bad-basis", price(v).copy(basisDescription = null)) }
        rejects { store.savePrice("unknown-confirmed", price(v).copy(totalCents = null)) }
        rejects { store.savePrice("negative", price(v).copy(totalCents = -1)) }
        rejects { store.savePrice("future", price(v).copy(observedOn = "2099-01-01")) }
        rejects { store.saveBudget("bad-budget", BudgetInput("x", day, "2026-11-05", null, true, "x")) }
        assertEquals(2, dao.prices().size); assertTrue(dao.budgets().isEmpty()); assertTrue(dao.shoppingMarks(day, "2026-11-05", day).isEmpty())
    }
    @Test fun oversizedCostsAndUnconfirmedPackBasesRemainUnknownInsteadOfOverflowOrZeroTotal() = runBlocking {
        val v = product(); store.savePlan("plan", plan(v, Amounts.MAX))
        val huge = store.savePrice("huge", price(v, 1, Money.MAX_CENTS)); mark("choose-huge", v, huge)
        assertNull(report().items.single().calculatedCostCents); assertNull(report().estimatedTotalCents)
        assertTrue(ShoppingIssue.NUMBER_RANGE in report().items.single().issues)
        val unknown = store.savePrice("basis-unknown", price(v).copy(basisConfirmed = false), huge)
        mark("choose-unknown", v, unknown)
        assertNull(report().items.single().exactPackCount); assertNull(report().estimatedTotalCents)
    }
    @Test fun sqliteAbortRollsBackPriceBudgetAndChecklistAuditRows() = runBlocking {
        val v = product(); store.savePlan("plan", plan(v))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_price BEFORE INSERT ON price_observations BEGIN SELECT RAISE(ABORT, 'injected shopping failure'); END")
        try { store.savePrice("price", price(v)); fail("Expected SQLite abort") }
        catch (error: Exception) { assertTrue(error.message.orEmpty().contains("injected shopping failure")) }
        assertNull(dao.operation("price")); assertTrue(dao.prices().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_price")
        val offer = store.savePrice("price", price(v))
        val budget = BudgetInput("Woche", day, "2026-11-05", 500, true, "Budgetrahmen")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_budget BEFORE INSERT ON budgets BEGIN SELECT RAISE(ABORT, 'injected shopping failure'); END")
        try { store.saveBudget("budget-failure", budget); fail("Expected SQLite abort") }
        catch (error: Exception) { assertTrue(error.message.orEmpty().contains("injected shopping failure")) }
        assertNull(dao.operation("budget-failure")); assertTrue(dao.budgets().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_budget")
        store.saveBudget("budget-failure", budget)
        val before = report()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_mark BEFORE INSERT ON shopping_marks BEGIN SELECT RAISE(ABORT, 'injected shopping failure'); END")
        try { mark("mark", v, offer, true); fail("Expected SQLite abort") }
        catch (error: Exception) { assertTrue(error.message.orEmpty().contains("injected shopping failure")) }
        assertNull(dao.operation("mark")); assertEquals(before, report())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_mark")
        mark("mark", v, offer, true); assertTrue(report().items.single().checked)
    }
    @Test fun currentStockAndFulfilledPlansUpdateProposalsAndPersistedMarksSurviveFileReopening() = runBlocking {
        db.close(); val name = "shopping-${UUID.randomUUID()}.db"
        try {
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            val v = product(); store.savePlan("plan", plan(v)); val offer = store.savePrice("price", price(v)); mark("checked", v, offer, true)
            val channel = Channel<ShoppingReport>(Channel.UNLIMITED)
            val job = launch(Dispatchers.IO) { store.watchShopping(scope).collect { channel.send(it) } }
            try {
                assertTrue(withTimeout(10_000) { channel.receive() }.items.single().checked)
                val loc = store.addLocation("loc", "Lager", true)
                store.receive("stock", BatchInput(v, loc, 50_000, day, useBy = "2026-11-05", release = ReleaseStatus.RELEASED), true)
                val updated = withTimeout(10_000) {
                    var r = channel.receive(); while (r.items.single().need.knownMissing != 300_000L) r = channel.receive(); r
                }
                assertEquals(2L, updated.items.single().exactPackCount); assertTrue(updated.items.single().staleCheck); assertFalse(updated.items.single().checked)
                store.consume("outside-meal", ConsumptionInput(v, day, "Außer Haus gegessen", 350_000), true)
                val entry = store.planningReport(day, "2026-11-05").plans.single().entries.single()
                store.linkPlanExecution("fulfilled", LinkPlanExecutionInput(entry.id, "outside-meal", store.planExecution(entry.id).status.snapshot, "Plan tatsächlich erfüllt"), true)
                val fulfilled = withTimeout(10_000) { var r = channel.receive(); while (r.items.isNotEmpty()) r = channel.receive(); r }
                assertEquals(0L, fulfilled.estimatedTotalCents)
                store.unlinkPlanExecution("reopen", UnlinkPlanExecutionInput("fulfilled", "Andere Planposition gemeint"), true)
                val reopened = withTimeout(10_000) { var r = channel.receive(); while (r.items.isEmpty()) r = channel.receive(); r }
                assertEquals(300_000L, reopened.items.single().need.knownMissing); assertTrue(reopened.items.single().staleCheck)
                assertEquals(1, dao.consumptionCount())
            } finally { job.cancelAndJoin(); channel.close() }
            val before = report(); db.close()
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            assertEquals(before, report()); assertEquals(6, db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
