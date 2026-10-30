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
class PurchaseDatabaseTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = db.foodDao()
    private val day = "2026-10-30"
    private val clock = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private val period = ExpenseScope("2026-10-01", day)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build(); store = FoodStore(db) { clock } }
    @After fun close() { db.close() }
    private fun purchase(cents: Long? = 398, date: String? = day) = PurchaseInput("Testmarkt", date, cents,
        listOf(PurchaseLineInput("TOFU 2x1,99", "Testtofu", totalCents = 398), PurchaseLineInput(interpretedName = "Ungeklärte Position")), "Manuell geprüft")
    private suspend fun report(scope: ExpenseScope = period) = store.expenseReport(scope)
    private suspend fun complete(key: String = "complete", scope: ExpenseScope = period) =
        store.setExpenseStatus(key, ExpenseStatusInput(scope, true, report(scope).snapshot, "Alle Ausgaben abgeglichen"), true)
    private suspend fun prepareStock(key: String = "buy", input: PurchaseInput = purchase()): PurchaseStockInput {
        val v = store.rememberProduct("p-$key", ProductInput("Testtofu"))
        val loc = store.addLocation("loc-$key", "Testlager", true)
        val receipt = store.savePurchase(key, input, true)
        val line = dao.receiptLines(receipt).single { it.interpretedName == "Testtofu" }
        return PurchaseStockInput(line.id, BatchInput(v, loc, 200_000, input.purchasedOn), true, "200 g nutzbare Menge nach Abtropfen", "Position geklärt")
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected rejected transaction") } catch (_: IllegalArgumentException) { }
    }

    @Test fun paymentCountsOnceDespiteUnresolvedLinesAndPreservesOriginalAndSource() = runBlocking {
        val input = purchase().copy(source = Source.RETAILER)
        val receipt = store.savePurchase("buy", input, true)
        assertEquals(receipt, store.savePurchase("buy", input, true))
        assertEquals(1, dao.purchases().size); assertEquals(2, dao.receiptLines(receipt).size)
        assertEquals("TOFU 2x1,99", dao.receiptLines(receipt).single { it.originalText != null }.originalText)
        assertNull(dao.receiptLines(receipt).single { it.originalText == null }.versionId)
        assertEquals(Source.RETAILER, dao.receipt(receipt)!!.source)
        assertEquals(398L, report().knownSubtotalCents); assertNull(report().totalCents)
        assertTrue(dao.planningBatches().isEmpty()); assertEquals(0, dao.consumptionCount()); assertTrue(dao.prices().isEmpty())
        complete(); assertEquals(398L, report().totalCents)
        rejects { store.savePurchase("buy", input.copy(paidCents = 399), true) }
        assertEquals(398L, report().totalCents)
    }

    @Test fun unknownAmountAndDateNeverBecomeZeroOrInventedFactsButConfirmedZeroIsValid() = runBlocking {
        store.savePurchase("unknown", purchase(null, null), true)
        assertEquals(1, report().unknownPurchases); assertEquals(0L, report().knownSubtotalCents); assertNull(report().totalCents)
        rejects { complete() }; assertNull(dao.operation("complete"))
        assertEquals(1, report(ExpenseScope("2026-09-01", "2026-09-30")).unknownPurchases)
        store.cancelPurchase("cancel-unknown", ReversalInput("unknown", day, "Unvollständigen Test korrigieren"), true)
        store.savePurchase("free", purchase(0), true); complete()
        assertEquals(0L, report().totalCents); assertEquals(0L, dao.receipt(dao.purchase("free")!!.receiptId)!!.totalCents)
        assertNull(dao.receipt(dao.purchase("unknown")!!.receiptId)!!.purchasedOn)
    }

    @Test fun explicitSubsetStockBookingDoesNotAlterExpenseOrCompletenessAndNeverAutoReleasesFood() = runBlocking {
        val stock = prepareStock(); complete()
        val before = report(); val batch = store.bookPurchaseLine("stock", stock, true)
        assertEquals(200_000L, dao.balance(batch)); assertEquals(ReleaseStatus.UNCHECKED, dao.batch(batch)!!.release)
        assertEquals(398L, report().totalCents); assertTrue(report().complete)
        assertEquals(before.dataToken, report().dataToken); assertEquals(before.snapshot, report().snapshot)
        assertEquals(1, report().purchases.single().lines.count { it.booking != null })
        assertEquals(0, dao.consumptionCount()); assertTrue(dao.prices().isEmpty())
        assertEquals(batch, store.bookPurchaseLine("stock", stock, true)); assertEquals(200_000L, dao.balance(batch))
        rejects { store.bookPurchaseLine("second", stock, true) }; assertNull(dao.operation("second"))
        store.reverse("undo-stock", ReversalInput("stock", day, "Mengenfehler"), true)
        assertEquals(0L, dao.balance(batch)); assertEquals(batch, store.bookPurchaseLine("stock", stock, true))
        rejects { store.bookPurchaseLine("after-undo", stock, true) }
        assertEquals(398L, report().totalCents); assertEquals(0L, dao.balance(batch))
        assertNotNull(report().purchases.single().lines.single { it.line.id == stock.lineId }.reversedBy)
    }

    @Test fun stockRequiresConfirmedUsableBasisKnownDateExactVersionAndConfirmedLocation() = runBlocking {
        val stock = prepareStock()
        val before = dao.operationCount()
        rejects { store.bookPurchaseLine("no-confirm", stock, false) }
        rejects { store.bookPurchaseLine("no-basis", stock.copy(usableBasisConfirmed = false), true) }
        rejects { store.bookPurchaseLine("empty-basis", stock.copy(basisDescription = " "), true) }
        rejects { store.bookPurchaseLine("bad-version", stock.copy(batch = stock.batch.copy(versionId = "missing")), true) }
        rejects { store.bookPurchaseLine("wrong-day", stock.copy(batch = stock.batch.copy(acquiredOn = "2026-10-29")), true) }
        rejects { store.bookPurchaseLine("zero", stock.copy(batch = stock.batch.copy(amount = 0)), true) }
        val location = store.addLocation("unconfirmed-location", "Ungeklärt", false)
        rejects { store.bookPurchaseLine("unknown-place", stock.copy(batch = stock.batch.copy(locationId = location)), true) }
        assertTrue(dao.planningBatches().isEmpty()); assertNull(dao.receiptBooking(stock.lineId)); assertEquals(before + 1, dao.operationCount())
        val unknownDate = prepareStock("undated", purchase(date = null))
        rejects { store.bookPurchaseLine("date-invented", unknownDate.copy(batch = unknownDate.batch.copy(acquiredOn = day)), true) }
        assertNull(dao.operation("date-invented"))
        val known = purchase().copy(lines = listOf(PurchaseLineInput(versionId = stock.batch.versionId, amount = 10_000, unit = BaseUnit.MILLILITRE)))
        rejects { store.savePurchase("unit-mismatch", known, true) }
        assertNull(dao.operation("unit-mismatch"))
    }

    @Test fun concurrentKeysAndRetriesBookOneLineOnlyOnce() = runBlocking {
        val stock = prepareStock()
        val outcomes = coroutineScope { (1..8).map { n -> async(Dispatchers.Default) {
            runCatching { store.bookPurchaseLine("race-$n", stock, true) }.isSuccess
        } }.awaitAll() }
        assertEquals(1, outcomes.count { it }); assertEquals(1, dao.planningBatches().size)
        assertEquals(200_000L, dao.planningBatches().single().stockAmount)
        val input = purchase(199)
        val ids = coroutineScope { (1..5).map { async(Dispatchers.Default) { store.savePurchase("same-key", input, true) } }.awaitAll() }
        assertEquals(1, ids.distinct().size); assertEquals(2, dao.purchases().size); assertEquals(597L, report().knownSubtotalCents)
    }

    @Test fun erroneousPurchaseCannotBeCancelledUntilDependentStockHistoryIsReversed() = runBlocking {
        val stock = prepareStock(); val batch = store.bookPurchaseLine("stock", stock.copy(batch = stock.batch.copy(release = ReleaseStatus.RELEASED)), true)
        complete(); val paidBefore = dao.receipt(dao.purchase("buy")!!.receiptId)
        store.consume("eat", ConsumptionInput(stock.batch.versionId, day, "Testmahlzeit", 50_000, listOf(Allocation(batch, 50_000))), true)
        rejects { store.cancelPurchase("cancel", ReversalInput("buy", day, "Fehlerhafte Erfassung"), true) }
        rejects { store.reverse("undo-stock", ReversalInput("stock", day, "Fehlerhafte Menge"), true) }
        assertNull(dao.operation("cancel")); assertNull(dao.operation("undo-stock")); assertEquals(398L, report().totalCents)
        store.reverse("undo-eat", ReversalInput("eat", day, "Fehlerhafte Testmahlzeit"), true)
        store.reverse("undo-stock", ReversalInput("stock", day, "Fehlerhafte Menge"), true)
        val cancellation = ReversalInput("buy", day, "Fehlerhafte Erfassung")
        assertEquals("buy", store.cancelPurchase("cancel", cancellation, true))
        assertEquals(0L, report().knownSubtotalCents); assertNull(report().totalCents); assertTrue(report().staleConfirmation)
        assertEquals(paidBefore, dao.receipt(paidBefore!!.id)); assertEquals(0L, dao.balance(batch))
        assertNotNull(dao.reversal("buy")); assertEquals(0, dao.activeConsumptionCount())
        assertEquals("buy", store.cancelPurchase("cancel", cancellation, true))
        assertEquals(paidBefore.id, store.savePurchase("buy", purchase(), true))
        rejects { store.bookPurchaseLine("rebook", stock, true) }
        rejects { store.cancelPurchase("cancel-again", cancellation, true) }
        complete("complete-after-correction"); assertEquals(0L, report().totalCents)
    }

    @Test fun completenessNeedsExplicitZeroRejectsStaleApprovalAndKeepsSameClockAbaHistory() = runBlocking {
        val initial = report(); assertEquals(0L, initial.knownSubtotalCents); assertNull(initial.totalCents)
        val first = ExpenseStatusInput(period, true, initial.snapshot, "Keine Ausgaben nach vollständiger Prüfung")
        store.setExpenseStatus("first", first, true); assertEquals(0L, report().totalCents)
        val beforeAba = report()
        store.savePurchase("temporary", purchase(100), true)
        store.cancelPurchase("undo-temporary", ReversalInput("temporary", day, "Falsche Testdaten"), true)
        val afterAba = report(); assertEquals(0L, afterAba.knownSubtotalCents); assertNull(afterAba.totalCents)
        assertNotEquals(beforeAba.snapshot, afterAba.snapshot); assertTrue(afterAba.staleConfirmation)
        rejects { store.setExpenseStatus("stale", ExpenseStatusInput(period, true, beforeAba.snapshot, "Alter Blick"), true) }
        store.setExpenseStatus("first", first, true); assertNull(report().totalCents)
        complete("fresh"); val current = report()
        val reopen = ExpenseStatusInput(period, false, current.snapshot, "Weitere Belege suchen")
        store.setExpenseStatus("open", reopen, true); assertFalse(report().complete); assertNull(report().totalCents)
        rejects { store.setExpenseStatus("outdated", ExpenseStatusInput(period, true, current.snapshot, "Veraltet"), true) }
        assertNull(dao.operation("outdated"))
    }

    @Test fun datesAndScopedPaymentsDoNotInventCoverageOutsideSelectedPeriod() = runBlocking {
        store.savePurchase("old", purchase(100, "2026-09-30"), true)
        assertTrue(report().purchases.isEmpty()); complete(); assertEquals(0L, report().totalCents)
        store.savePurchase("older", purchase(200, "2026-09-29"), true)
        assertTrue(report().complete) // Known events outside this period do not invalidate its assertion.
        assertEquals(300L, report(ExpenseScope("2026-09-01", "2026-09-30")).knownSubtotalCents)
        rejects { store.savePurchase("future", purchase(date = "2026-10-31"), true) }
        rejects { report(ExpenseScope(day, "2026-10-31")) }
        rejects { report(ExpenseScope(day, "2026-10-01")) }
        rejects { report(ExpenseScope("2025-01-01", day)) }
        rejects { store.savePurchase("bad-money", purchase(-1), true) }
        rejects { store.savePurchase("unconfirmed", purchase(), false) }
        rejects { store.savePurchase("bad-day", purchase(date = "2026-02-30"), true) }
        assertNull(dao.operation("future")); assertNull(dao.operation("bad-money")); assertNull(dao.operation("bad-day"))
    }

    @Test fun concurrentCompletenessAndNewPaymentNeverLeaveAFalseCompletePeriod() = runBlocking {
        val initial = report()
        val input = ExpenseStatusInput(period, true, initial.snapshot, "Geprüfter Stand")
        coroutineScope {
            val status = async(Dispatchers.Default) { runCatching { store.setExpenseStatus("status", input, true) } }
            val payment = async(Dispatchers.Default) { store.savePurchase("new-payment", purchase(), true) }
            payment.await(); status.await()
        }
        assertEquals(398L, report().knownSubtotalCents); assertNull(report().totalCents); assertFalse(report().complete)
        val snapshot = report().snapshot
        val results = coroutineScope { (1..4).map { n -> async(Dispatchers.Default) {
            runCatching { store.setExpenseStatus("status-race-$n", input.copy(expectedSnapshot = snapshot), true) }.isSuccess
        } }.awaitAll() }
        assertEquals(1, results.count { it }); assertEquals(398L, report().totalCents)
    }

    @Test fun legacyReceiptQualityAndStockDoNotProvePaymentAndOneExplicitConfirmationIsEnough() = runBlocking {
        dao.insertReceipt(ReceiptEntity("old", "Altmarkt", day, 0, Source.RETAILER, Quality.CONFIRMED, clock))
        assertEquals(1, report().unconfirmedReceipts.size); assertEquals(0L, report().knownSubtotalCents)
        rejects { complete() }
        assertEquals("old", store.confirmReceiptPayment("pay-old", "old", "Zahlung in EUR geprüft", true))
        assertEquals("old", store.confirmReceiptPayment("pay-old", "old", "Zahlung in EUR geprüft", true))
        rejects { store.confirmReceiptPayment("pay-old-again", "old", "Doppelte Zahlung", true) }
        complete(); assertEquals(0L, report().totalCents); assertEquals(Quality.CONFIRMED, dao.receipt("old")!!.quality)
    }

    @Test fun unknownLinePricesAndMismatchNeverOverrideActualPaymentAndAggregationCannotOverflow() = runBlocking {
        store.savePurchase("actual", purchase(100), true) // line 398 is evidence, not the paid total.
        complete(); assertEquals(100L, report().totalCents)
        store.savePurchase("max", purchase(Money.MAX_CENTS), true)
        assertNull(report().knownSubtotalCents); assertNull(report().totalCents)
        rejects { complete("overflow") }; assertNull(dao.operation("overflow"))
        rejects { store.savePurchase("too-large", purchase(Money.MAX_CENTS + 1), true) }
        assertNull(dao.operation("too-large"))
    }

    @Test fun stockChangesRecalculateOpenDemandButMetadataAloneNeverFulfilsAPlanOrDuplicatesPayment() = runBlocking {
        val stock = prepareStock()
        store.savePlan("plan", PlanInput("Test", day, day, listOf(PlanEntryInput("eat", day, PlanKind.DIRECT_CONSUMPTION,
            stock.batch.versionId, amount = 350_000, unit = BaseUnit.GRAM)), "Plan"))
        val scope = ShoppingScope(day, day, day)
        val item = store.shoppingReport(scope).items.single()
        store.markShopping("mark", ShoppingMarkInput(scope, stock.batch.versionId, null, true, item.snapshot))
        val changes = Channel<ShoppingReport>(Channel.UNLIMITED)
        val job = launch { store.watchShopping(scope).collect { changes.send(it) } }
        try {
            assertEquals(350_000L, withTimeout(10_000) { changes.receive() }.items.single().need.knownMissing)
            store.bookPurchaseLine("stock", stock.copy(batch = stock.batch.copy(release = ReleaseStatus.RELEASED)), true)
            val changed = withTimeout(10_000) { var r = changes.receive(); while (r.items.single().need.knownMissing != 150_000L) r = changes.receive(); r }
            assertTrue(changed.items.single().staleCheck); assertEquals(398L, report().knownSubtotalCents)
            assertEquals(0L, store.planExecution(dao.allPlannedEntries().single().id).status.knownExecuted)
            assertEquals(0, dao.consumptionCount())
        } finally { job.cancelAndJoin(); changes.close() }
    }

    @Test fun budgetRestUsesActualPaidOnceAndOnlyExplicitCoverageThroughToday() = runBlocking {
        val stock = prepareStock()
        val budget = store.saveBudget("budget", BudgetInput("Monat", period.startsOn, "2026-10-31", 1000, true, "Obergrenze"))
        store.savePlan("plan", PlanInput("Test", day, day, listOf(PlanEntryInput("eat", day, PlanKind.DIRECT_CONSUMPTION,
            stock.batch.versionId, amount = 350_000, unit = BaseUnit.GRAM)), "Plan"))
        val price = store.savePrice("price", PriceInput(stock.batch.versionId, "Testmarkt", day, 200_000, 199, true, true,
            "200 g nutzbar je Packung", day, day, true, "Geprüft"))
        val scope = ShoppingScope(day, day, day, budget)
        val item = store.shoppingReport(scope).items.single()
        store.markShopping("mark", ShoppingMarkInput(scope, stock.batch.versionId, price, false, item.snapshot))
        assertNull(store.shoppingReport(scope).budget.remainingBudgetCents)
        complete()
        assertEquals(602L, store.shoppingReport(scope).budget.remainingBudgetCents)
        assertEquals(204L, store.shoppingReport(scope).budget.afterPlannedCents)
        store.bookPurchaseLine("stock", stock.copy(batch = stock.batch.copy(release = ReleaseStatus.RELEASED)), true)
        assertEquals(199L, store.shoppingReport(scope).estimatedTotalCents)
        assertEquals(398L, store.shoppingReport(scope).budget.paidTotalCents)
        assertEquals(602L, store.shoppingReport(scope).budget.remainingBudgetCents)
        assertEquals(403L, store.shoppingReport(scope).budget.afterPlannedCents)
        store.savePurchase("extra", purchase(800), true)
        val changed = store.shoppingReport(scope)
        assertNull(changed.budget.remainingBudgetCents); assertEquals(397L, changed.budget.knownCostsOverLimitCents)
        complete("complete-more"); assertEquals(-198L, store.shoppingReport(scope).budget.remainingBudgetCents)
        assertEquals(-397L, store.shoppingReport(scope).budget.afterPlannedCents)
        assertNull(store.shoppingReport(scope.copy(budgetId = null)).budget.remainingBudgetCents)
    }

    @Test fun databaseAbortRollsBackReceiptPositionsPaymentStockAndStatusIncludingOperationKeys() = runBlocking {
        val sql = db.openHelper.writableDatabase
        for (table in listOf("receipts", "receipt_lines", "purchase_events")) {
            val before = dao.operationCount()
            sql.execSQL("CREATE TRIGGER abort_write BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT,'test rollback'); END")
            try { store.savePurchase("abort-$table", purchase(), true); fail("SQL abort must fail") } catch (_: android.database.sqlite.SQLiteException) { }
            finally { sql.execSQL("DROP TRIGGER abort_write") }
            assertEquals(before, dao.operationCount()); assertTrue(dao.receipts().isEmpty()); assertTrue(dao.purchases().isEmpty())
        }
        val stock = prepareStock()
        for (table in listOf("batches", "stock_movements", "receipt_stock_bookings")) {
            val before = dao.operationCount()
            sql.execSQL("CREATE TRIGGER abort_write BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT,'test rollback'); END")
            try { store.bookPurchaseLine("abort-$table", stock, true); fail("SQL abort must fail") } catch (_: android.database.sqlite.SQLiteException) { }
            finally { sql.execSQL("DROP TRIGGER abort_write") }
            assertEquals(before, dao.operationCount()); assertTrue(dao.planningBatches().isEmpty()); assertNull(dao.receiptBooking(stock.lineId))
        }
        val before = report()
        sql.execSQL("CREATE TRIGGER abort_write BEFORE INSERT ON expense_status_events BEGIN SELECT RAISE(ABORT,'test rollback'); END")
        try { complete("abort-status"); fail("SQL abort must fail") } catch (_: android.database.sqlite.SQLiteException) { }
        finally { sql.execSQL("DROP TRIGGER abort_write") }
        assertNull(dao.operation("abort-status")); assertEquals(before, report())
        sql.execSQL("CREATE TRIGGER abort_write BEFORE INSERT ON operation_reversals BEGIN SELECT RAISE(ABORT,'test rollback'); END")
        try { store.cancelPurchase("abort-cancel", ReversalInput("buy", day, "Fehler"), true); fail("SQL abort must fail") } catch (_: android.database.sqlite.SQLiteException) { }
        finally { sql.execSQL("DROP TRIGGER abort_write") }
        assertNull(dao.operation("abort-cancel")); assertNull(dao.reversal("buy")); assertEquals(before, report())
        assertEquals(398L, report().knownSubtotalCents)
    }

    @Test fun fileReopenPreservesExpenseConfirmationLineIdentityAndRetryWithoutAnyDuplicate() = runBlocking {
        db.close(); val name = "purchase-${UUID.randomUUID()}.db"
        try {
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            val stock = prepareStock(); val batch = store.bookPurchaseLine("stock", stock, true); complete()
            val before = report(); db.close()
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            assertEquals(before, report()); assertEquals(6, db.openHelper.readableDatabase.version)
            assertEquals(batch, store.bookPurchaseLine("stock", stock, true)); assertEquals(200_000L, dao.balance(batch))
            assertEquals(before.purchases.single().receipt.id, store.savePurchase("buy", purchase(), true))
            assertEquals(398L, report().totalCents); assertEquals(1, dao.purchases().size)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
