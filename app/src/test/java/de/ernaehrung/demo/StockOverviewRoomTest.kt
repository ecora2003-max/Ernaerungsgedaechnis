package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StockOverviewRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val date = AppCalendar.today().toString()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        store = FoodStore(db)
    }
    @After fun close() = db.close()
    private suspend fun rows() = StockOverviewPreviews.build(store.versions().first(),store.batches().first(),store.locations().first(),date)
    private suspend fun receive(key: String,version: String,location: String,amount: Long,release: ReleaseStatus = ReleaseStatus.RELEASED) =
        store.receive(key,BatchInput(version,location,amount,date,release = release),true)

    @Test fun totalsKeepExactHistoricalVersionsAndUnitsSeparateAndReuseCurrentMealEligibility() = runBlocking {
        val first = store.rememberProduct("v1",ProductInput("Alte Vorratsware"))
        val identity = db.foodDao().version(first)!!.productId
        val place = store.addLocation("place","Schrank",true)
        receive("usable",first,place,150_123L)
        receive("unchecked",first,place,200_222L,ReleaseStatus.UNCHECKED)
        val second = store.rememberProduct("v2",ProductInput("Neue Vorratsware"),identity,"Neue Rezeptur",first)
        receive("new",second,place,700_000L)
        val liquid = store.rememberProduct("liquid",ProductInput("Flüssiger Vorrat",unit = BaseUnit.MILLILITRE))
        receive("liquid-stock",liquid,place,1_000_000L)
        val before = db.foodDao().operationCount()
        val groups = StockOverviewPreviews.select(rows()).associateBy { it.versionId }
        assertEquals(3,groups.size)
        assertEquals(BigInteger.valueOf(350_345L),groups.getValue(first).physicalAmount)
        assertEquals(BigInteger.valueOf(150_123L),groups.getValue(first).usableKnownAmount)
        assertEquals(0,groups.getValue(first).unknownUsableCount)
        assertTrue(groups.getValue(first).entries.any { it.reason == StockExclusionReason.NOT_RELEASED.label })
        assertEquals(1,groups.getValue(first).revision); assertEquals(2,groups.getValue(second).revision)
        assertEquals(BigInteger.valueOf(700_000L),groups.getValue(second).physicalAmount)
        assertEquals(BaseUnit.MILLILITRE,groups.getValue(liquid).unit)
        assertEquals(BigInteger.valueOf(1_000_000L),groups.getValue(liquid).usableKnownAmount)
        assertEquals(before,db.foodDao().operationCount()); assertEquals(0,db.foodDao().activeConsumptionCount())
    }

    @Test fun searchLocationAndRemainingFiltersAffectOnlyShownBatchesAndNeverBookOrEraseTheirHistory() = runBlocking {
        val version = store.rememberProduct("product",ProductInput("Vorratsware"))
        val first = store.addLocation("first-place","Schrank",true)
        val second = store.addLocation("second-place","Kühlraum",true)
        val a = receive("a",version,first,100_000L)
        val b = receive("b",version,second,50_000L)
        store.waste("waste",WasteInput(b,50_000L,date,"Tatsächlich entsorgt"),true)
        val before = db.foodDao().operationCount(); val stock = db.foodDao().planningBatches()
        val entries = rows()
        assertEquals(BigInteger.valueOf(100_000L),StockOverviewPreviews.select(entries,"ware schrank").single().physicalAmount)
        assertEquals(listOf(a),StockOverviewPreviews.select(entries,locationId = first).single().entries.map { it.row.batch.id })
        assertEquals(BigInteger.ZERO,StockOverviewPreviews.select(entries,query = b).single().physicalAmount)
        assertEquals(BigInteger.ZERO,StockOverviewPreviews.select(entries,query = b).single().usableKnownAmount)
        assertTrue(StockOverviewPreviews.select(entries,query = b,positiveOnly = true).isEmpty())
        assertEquals(listOf(a),StockOverviewPreviews.select(entries,usableOnly = true).single().entries.map { it.row.batch.id })
        assertEquals(before,db.foodDao().operationCount()); assertEquals(stock,db.foodDao().planningBatches())
    }

    @Test fun unknownVersionEvidenceIsNotConvertedToKnownZeroButKnownUnconfirmedLocationIsExcluded() = runBlocking {
        val version = store.rememberProduct("product",ProductInput("Vorratsware"))
        val place = store.addLocation("place","Schrank",true)
        receive("stock",version,place,100_000L)
        val batches = store.batches().first(); val locations = store.locations().first()
        val unknown = StockOverviewPreviews.select(StockOverviewPreviews.build(emptyList(),batches,locations,date)).single()
        assertEquals(BigInteger.valueOf(100_000L),unknown.physicalAmount)
        assertNull(unknown.usableKnownAmount); assertEquals(1,unknown.unknownUsableCount)
        val unconfirmed = StockOverviewPreviews.select(StockOverviewPreviews.build(store.versions().first(),batches,
            locations.map { it.copy(confirmed = false) },date)).single()
        assertEquals(BigInteger.ZERO,unconfirmed.usableKnownAmount); assertEquals(0,unconfirmed.unknownUsableCount)
        assertEquals(StockExclusionReason.LOCATION_UNCONFIRMED.label,unconfirmed.entries.single().reason)
    }
}
