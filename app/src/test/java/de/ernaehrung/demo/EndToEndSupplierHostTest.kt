package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic receipt -> paid history -> shopping -> actual stock -> recipe -> meal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndToEndSupplierHostTest {
    private lateinit var db: FoodDatabase
    private lateinit var food: FoodStore
    private val dao get() = db.foodDao()
    private val date = "2026-10-30"
    private val clock = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        food = FoodStore(db) { clock }
    }
    @After fun close() { db.close() }

    @Test fun reviewPaymentHistoryPlanCookingAndEatingStaySeparateAndIdempotent() = runBlocking {
        val v = food.rememberProduct("tofu", ProductInput("Tofu"))
        food.saveAlias("alias", AliasInput(v, "TOFU 2x", "TESTMARKT",
            AliasKind.RECEIPT, reason = "Ausdrücklich bestätigter Bonalias"), confirmed = true)
        val scan = food.saveReceiptScan("scan",
            "TESTMARKT\n30.10.2026\nTOFU 2x 3,98\nSUMME 3,98", "MANUAL_TEXT")
        val review = food.reviewReceiptScan(scan)
        assertEquals(v, review.matches.single().reusableVersionId)
        assertTrue(dao.receipts().isEmpty())

        val input = PurchaseInput("TESTMARKT", date, 398L,
            listOf(PurchaseLineInput(review.parsed.lines.single().originalText,
                "TOFU 2x", v, totalCents = 398L)), "Bon und Zahlung geprüft")
        val receipt = food.acceptReceiptScan("pay", scan, input, review.snapshot, true)
        assertEquals(receipt, food.acceptReceiptScan("pay", scan, input, review.snapshot, true))
        val line = dao.receiptLines(receipt).single()
        val basis = PaidPriceBasisReview("pay", line.id, v, 2, 0, null,
            discountReconciled = true, amountExcludesDeposit = true,
            unitsConfirmed = true, foodAndProductConfirmed = true,
            basisReason = "Zwei gleiche Packungen, Nettowarenpreis geprüft")
        val history = food.saveHistoricalPaidPrice("history", basis, true)
        assertEquals(history, food.saveHistoricalPaidPrice("history", basis, true))
        assertEquals(199L, food.historicalPaidComparison(
            HistoricalPaidComparisonScope(v, date, clock)).lowestHistoricalUnitPriceCents)
        assertTrue(dao.prices().isEmpty())

        food.savePlan("plan", PlanInput("Tofu", date, date,
            listOf(PlanEntryInput("meal", date, PlanKind.DIRECT_CONSUMPTION,
                v, amount = 350_000, unit = BaseUnit.GRAM)), "Geplanter Bedarf"))
        val shopping = food.shoppingReport(ShoppingScope(date, date, date))
        assertEquals(350_000L, shopping.items.single().need.knownMissing)
        assertTrue(shopping.items.single().quotes.isEmpty())
        assertNull(shopping.estimatedTotalCents)
        assertTrue(dao.planningBatches().isEmpty())

        val location = food.addLocation("location", "Kühlschrank", true)
        val booking = PurchaseStockInput(line.id,
            BatchInput(v, location, 400_000, date, release = ReleaseStatus.RELEASED),
            true, "Zwei Packungen, 400g nutzbare Ware", "Zugang geprüft")
        val batch = food.bookPurchaseLine("stock", booking, true)
        assertEquals(batch, food.bookPurchaseLine("stock", booking, true))
        assertEquals(400_000L, dao.balance(batch))
        val recipe = food.saveRecipe("recipe", RecipeInput("Tofugericht", 1000,
            listOf(RecipeItemInput(v, 200_000)), "Erhitzen"))
        assertEquals(400_000L, dao.balance(batch))
        assertEquals(0, dao.consumptionCount())

        val cook = ProductionInput(recipe, 1000, listOf(Allocation(batch, 200_000)),
            location, date, true, "Geeigneter gekühlter Behälter geprüft",
            release = ReleaseStatus.RELEASED)
        val output = food.produce("cook", cook, true)
        assertEquals(output, food.produce("cook", cook, true))
        assertEquals(0, dao.consumptionCount())
        assertEquals(1000L, dao.balance(output))

        food.consume("eat", ConsumptionInput(dao.batch(output)!!.versionId, date,
            "Mittag", 1000, listOf(Allocation(output, 1000))), true)
        assertEquals(1, dao.activeConsumptionCount())
        assertEquals(0L, dao.balance(output))
        assertEquals(200_000L, dao.balance(batch))
        assertEquals(1, dao.purchases().size)
        assertEquals(1, food.historicalPaidPriceAudit().size)
        assertTrue(dao.prices().isEmpty())
    }
}
