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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StockHistoryRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var version: String
    private lateinit var batch: String
    private val day = "2026-10-30"
    private val dao get() = db.foodDao()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        store = FoodStore(db) { 1_800_000_000_000L }
        version = store.rememberProduct("product",ProductInput("Historische Ware"))
        val place = store.addLocation("place","Schrank",true)
        batch = store.receive("receive",BatchInput(version,place,100_000L,day),true)
        repeat(40) { store.waste("waste-%02d".format(it),WasteInput(batch,1_000L,day,"Tatsächlicher Verlust $it"),true) }
    }
    @After fun close() = db.close()

    @Test fun stablePagesReachEveryOldOperationExactlyOnceEvenWhenAllTimesTieAndKeepTheOriginalProductVersion() = runBlocking {
        val product = dao.version(version)!!.productId
        val next = store.rememberProduct("product-v2",ProductInput("Neuer Name",energyKcal = 0.0),product,"Neue Rezeptur",version)
        val before = dao.operationCount(); val prices = dao.allPaidPrices(); val bodies = db.bodyDao().versions()
        val seen = mutableListOf<OperationDetails>(); var cursor: StockHistoryCursor? = null
        do {
            val page = store.readStockHistory(StockHistoryQuery(batchId = batch,before = cursor))
            assertEquals(cursor,page.query.before); assertTrue(page.entries.size <= StockHistoryReader.PAGE_SIZE)
            seen.addAll(page.entries); cursor = page.older
        } while (cursor != null)
        assertEquals((listOf("receive") + (0 until 40).map { "waste-%02d".format(it) }).sortedDescending(),seen.map { it.operation.id })
        assertEquals(41,seen.map { it.operation.id }.toSet().size)
        assertTrue(seen.flatMap { it.movements }.all { it.productName == "Historische Ware" && it.unit == BaseUnit.GRAM })
        assertTrue(seen.flatMap { it.movements }.all { it.movement.batchId == batch && it.movement.occurredOn == day })
        assertTrue(seen.flatMap { it.movements }.all { it.movement.occurredAt == null })
        val exact = store.readStockHistory(StockHistoryQuery(batch,"waste-00"))
        assertEquals("waste-00",exact.entries.single().operation.id); assertNull(exact.older)
        assertTrue(store.readStockHistory(StockHistoryQuery(batchId = "missing")).entries.isEmpty())
        assertTrue(store.readStockHistory(StockHistoryQuery(operationId = "product-v2")).entries.isEmpty())
        assertEquals(before,dao.operationCount()); assertEquals(60_000L,dao.balance(batch))
        assertNull(dao.version(version)!!.energyKcal); assertEquals(0.0,dao.version(next)!!.energyKcal!!,0.0)
        assertEquals(prices,dao.allPaidPrices()); assertEquals(bodies,db.bodyDao().versions())
    }

    @Test fun anOldSelectedOperationStaysLiveBeyondThirtyAndExposesItsExactCounterMovementWithoutWritingOnRead() = runBlocking {
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount()
        val channel = Channel<OperationDetails>(Channel.UNLIMITED)
        val reader = launch(Dispatchers.Default) { store.watchOperationDetails("waste-00").collect { channel.send(it) } }
        try {
            val old = withTimeout(5_000) { channel.receive() }
            assertNull(old.reversal); assertEquals("Tatsächlicher Verlust 0",old.movements.single().movement.reason)
            assertEquals(before,dao.operationCount())
            assertEquals("waste-00",store.reverse("undo-first",ReversalInput("waste-00",day,"Korrektur"),true))
            val updated = withTimeout(5_000) { channel.receive() }
            assertEquals("undo-first",updated.reversal!!.reversalOperationId)
            assertEquals(old.movements,updated.movements)
            val reverse = store.readStockHistory(StockHistoryQuery(batch,"undo-first")).entries.single()
            assertEquals("REVERSAL",reverse.operation.kind); assertEquals("waste-00",reverse.operation.resultId)
            assertEquals(old.movements.single().movement.id,reverse.movements.single().movement.reversesId)
            assertEquals(1_000L,reverse.movements.single().movement.delta)
            assertEquals("waste-00",store.reverse("undo-first",ReversalInput("waste-00",day,"Korrektur"),true))
            assertEquals(before + 1,dao.operationCount()); assertEquals(61_000L,dao.balance(batch)); assertEquals(meals,dao.activeConsumptionCount())
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
        } finally { reader.cancelAndJoin(); channel.close() }
    }
}
