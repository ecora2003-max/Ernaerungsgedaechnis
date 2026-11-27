package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Past-only private prices must never leak into current offers or cause a booking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalPaidComparisonTest {
    private lateinit var db: FoodDatabase
    private val dao get() = db.foodDao()
    private fun at(instant: String) = FoodStore(db) { Instant.parse(instant).toEpochMilli() }
    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()
    private val firstTime = "2026-10-30T12:00:00Z"
    private val secondTime = "2026-10-31T12:00:00Z"
    private val reversalTime = "2026-11-01T12:00:00Z"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            FoodDatabase::class.java).build()
    }
    @After fun tearDown() { db.close() }

    private suspend fun payAndConfirm(key: String, versionId: String, cents: Long, atTime: String): String {
        val store = at(atTime)
        val receipt = store.savePurchase(key, PurchaseInput("Testmarkt", "2026-10-30", cents,
            listOf(PurchaseLineInput("TOFU 2x", "Tofu", versionId, totalCents = cents)),
            "Synthetische Zahlung ausdrücklich bestätigt"), true)
        val lineId = dao.receiptLines(receipt).single().id
        return store.saveHistoricalPaidPrice("history-$key",
            PaidPriceBasisReview(key, lineId, versionId, 2L, 0L, null,
                discountReconciled = true, amountExcludesDeposit = true,
                unitsConfirmed = true, foodAndProductConfirmed = true,
                basisReason = "Zwei Einheiten, Warenpreis und Rabatt geprüft"), true)
    }

    @Test fun comparisonUsesOnlyFactsKnownAtInstantAndRespectsLaterReversalWithoutHindsight() = runBlocking {
        val first = at(firstTime)
        val version = first.rememberProduct("tofu", ProductInput("Tofu", unit = BaseUnit.GRAM))
        val otherVersion = first.rememberProduct("beans", ProductInput("Bohnen", unit = BaseUnit.GRAM))
        val id1 = payAndConfirm("buy-first", version, 398L, firstTime)
        val id2 = payAndConfirm("buy-second", version, 600L, secondTime)
        val scope = HistoricalPaidComparisonScope(version, "2026-10-31", millis(firstTime))
        val operationCount = dao.operationCount()
        val before = first.historicalPaidComparison(scope.copy(knownAtMillis = millis(firstTime) - 1L))
        assertTrue(before.references.isEmpty())
        assertNull(before.lowestHistoricalUnitPriceCents)
        val early = first.historicalPaidComparison(scope)
        assertEquals(listOf(id1), early.references.map { it.observationId })
        assertEquals(199L, early.lowestHistoricalUnitPriceCents)
        val both = first.historicalPaidComparison(scope.copy(knownAtMillis = millis(secondTime)))
        assertEquals(setOf(id1, id2), both.references.map { it.observationId }.toSet())
        assertEquals(199L, both.lowestHistoricalUnitPriceCents)
        assertEquals("HISTORICAL_PAID", both.priceKind)
        assertFalse(both.isCurrentOffer)
        assertTrue(first.historicalPaidComparison(scope.copy(productVersionId = otherVersion)).references.isEmpty())
        assertTrue(first.historicalPaidComparison(scope.copy(targetPurchaseOn = "2026-10-29")).references.isEmpty())

        at(reversalTime).cancelPurchase("reverse-first",
            ReversalInput("buy-first", "2026-11-01", "Synthetische Stornierung"), true)
        val historical = first.historicalPaidComparison(scope.copy(knownAtMillis = millis(secondTime)))
        assertEquals(setOf(id1, id2), historical.references.map { it.observationId }.toSet())
        val after = first.historicalPaidComparison(scope.copy(knownAtMillis = millis(reversalTime)))
        assertEquals(listOf(id2), after.references.map { it.observationId })
        assertEquals(300L, after.lowestHistoricalUnitPriceCents)
        assertEquals(2, first.historicalPaidPriceAudit().size)
        assertTrue(dao.prices().isEmpty())
        assertEquals(operationCount + 1, dao.operationCount()) // Only the explicit reversal writes.
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun invalidComparisonScopeFailsWithoutAnySideEffect() = runBlocking {
        val store = at(firstTime)
        val initial = dao.operationCount()
        for (scope in listOf(
            HistoricalPaidComparisonScope("", "2026-10-30", millis(firstTime)),
            HistoricalPaidComparisonScope("missing", "30.10.2026", millis(firstTime)),
            HistoricalPaidComparisonScope("missing", "2026-10-30", -1L)
        )) {
            try {
                store.historicalPaidComparison(scope)
                fail("Invalid scope must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(initial, dao.operationCount())
        assertTrue(dao.allPaidPrices().isEmpty())
        assertTrue(dao.prices().isEmpty())
    }

    @Test fun lateConfirmedStockAssignmentCanSupportASeparatePrivatePriceUntilItIsReversed() = runBlocking {
        val boughtOn = "2026-10-30"
        val first = at(firstTime)
        val product = first.rememberProduct("late-product", ProductInput("Tofu"))
        val other = first.rememberProduct("other-product", ProductInput("Bohnen"))
        val location = first.addLocation("cellar", "Testlager", true)
        val receipt = first.savePurchase("late-purchase", PurchaseInput("Testmarkt", boughtOn, 398L,
            listOf(PurchaseLineInput("TOFU 2x", "Tofu", totalCents = 398L)), "Bestätigte Zahlung"), true)
        val line = dao.receiptLines(receipt).single()
        val basis = PaidPriceBasisReview("late-purchase", line.id, product, 2L, 0L, null,
            discountReconciled = true, amountExcludesDeposit = true, unitsConfirmed = true,
            foodAndProductConfirmed = true, basisReason = "Beleg und zwei Gebinde geprüft")
        assertFalse(first.previewHistoricalPaidPrice(basis).eligible)
        val later = at(secondTime)
        val batch = later.bookPurchaseLine("late-stock", PurchaseStockInput(line.id,
            BatchInput(product, location, 400_000L, boughtOn), true, "400 g nutzbar", "Artikel geklärt"), true)
        assertTrue(later.activeHistoricalPaidPrices().isEmpty())
        assertFalse(later.previewHistoricalPaidPrice(basis.copy(expectedProductVersionId = other)).eligible)
        assertNull(dao.receiptLine(line.id)?.versionId)
        val id = later.saveHistoricalPaidPrice("late-price", basis, true)
        assertEquals(id, later.saveHistoricalPaidPrice("late-price", basis, true))
        assertEquals(199L, later.activeHistoricalPaidPrices().single().unitPriceCents)
        assertEquals(400_000L, dao.balance(batch))
        val scope = HistoricalPaidComparisonScope(product, "2026-10-31", millis(secondTime))
        assertTrue(later.historicalPaidComparison(scope.copy(knownAtMillis = millis(firstTime))).references.isEmpty())
        assertEquals(id, later.historicalPaidComparison(scope).references.single().observationId)

        val corrected = at(reversalTime)
        corrected.reverse("late-reversal", ReversalInput("late-stock", "2026-11-01", "Artikel falsch zugeordnet"), true)
        assertEquals("REVERSED_PURCHASE_OR_LINE", corrected.previewHistoricalPaidPrice(basis).rejectionReason)
        assertTrue(corrected.activeHistoricalPaidPrices().isEmpty())
        assertEquals(id, corrected.historicalPaidPriceAudit().single().id)
        assertEquals(id, corrected.historicalPaidComparison(scope).references.single().observationId)
        assertTrue(corrected.historicalPaidComparison(scope.copy(knownAtMillis = millis(reversalTime))).references.isEmpty())
        assertNull(dao.reversal("late-purchase")); assertTrue(dao.prices().isEmpty())
    }
}
