package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.LocalDataViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in UI query -> real FoodStore/Room history; never a current offer or a write. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalPaidShoppingViewModelTest {
    private lateinit var db: FoodDatabase
    private lateinit var model: LocalDataViewModel
    private lateinit var version: String
    private val paidAt = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private val beforePaid = paidAt - 1L
    private val afterPaid = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        val store = FoodStore(db) { paidAt }
        runBlocking {
            version = store.rememberProduct("v", ProductInput("Tofu"))
            val receipt = store.savePurchase("buy", PurchaseInput("Testmarkt", "2026-10-30",
                398L, listOf(PurchaseLineInput("TOFU", "Tofu", version, totalCents = 398L)),
                "Synthetisch bezahlt"), true)
            val line = db.foodDao().receiptLines(receipt).single().id
            store.saveHistoricalPaidPrice("paid", PaidPriceBasisReview("buy", line, version,
                2L, 0L, null, discountReconciled = true, amountExcludesDeposit = true,
                unitsConfirmed = true, foodAndProductConfirmed = true,
                basisReason = "Zwei Einheiten bestätigt"), true)
        }
        model = LocalDataViewModel(store)
    }

    @After fun close() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun result() = withTimeout(10_000) {
        model.historicalPaid.first { !it.busy && it.report != null }
    }

    @Test fun privateHistoryRespectsKnowledgeCutoffAndCreatesNoShoppingOrPurchaseWrites() = runBlocking {
        val initial = db.foodDao().operationCount()
        model.showHistoricalPaidForShopping(version, "2026-10-30", beforePaid)
        assertTrue(result().report!!.references.isEmpty())
        model.showHistoricalPaidForShopping(version, "2026-10-30", afterPaid)
        val paid = result().report!!
        assertEquals(1, paid.references.size)
        assertEquals(199L, paid.lowestHistoricalUnitPriceCents)
        assertEquals("HISTORICAL_PAID", paid.priceKind)
        assertFalse(paid.isCurrentOffer)
        assertEquals(Instant.parse("2026-10-30T23:59:59.999Z").toEpochMilli(),
            paid.scope.knownAtMillis)
        model.showHistoricalPaidForShopping(version, "2026-10-29", afterPaid)
        assertTrue(result().report!!.references.isEmpty())
        assertEquals(initial, db.foodDao().operationCount())
        assertTrue(db.foodDao().prices().isEmpty())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun malformedDateOrUnknownVersionNeverCreatesAHistoryOrBooking() = runBlocking {
        val initial = db.foodDao().operationCount()
        model.showHistoricalPaidForShopping(version, "30.10.2026", afterPaid)
        assertNotNull(model.historicalPaid.value.error)
        assertNull(model.historicalPaid.value.report)
        model.showHistoricalPaidForShopping("missing", "2026-10-30", afterPaid)
        assertTrue(result().report!!.references.isEmpty())
        assertEquals(initial, db.foodDao().operationCount())
        assertTrue(db.foodDao().prices().isEmpty())
    }
}
