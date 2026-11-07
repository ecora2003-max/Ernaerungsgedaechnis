package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StockEventsOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private val approval = "Diesen tatsächlichen Bestandsvorgang bestätigen"
    private val conditions = "Geeignete Zielbedingungen geprüft · bestehende Freigabe beibehalten"
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) {
        finishDeviceTextEditing(compose); waitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun text(value: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(value).performScrollTo().performClick()
    }
    private fun field(label: String, value: String) = compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
    private fun rejected() = compose.waitUntil(25_000) { compose.onAllNodesWithText("Nicht gespeichert.",substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun offlineTransferWasteExactZeroCountAndReversalRequireFreshGesturesAndRetainHistoryAcrossRecreationAndSqliteReopen() = runBlocking {
        assertTrue("Synthetic stock events require a disposable emulator",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = compose.activity.applicationContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val today = AppCalendar.today().toString()
        val version = store.rememberProduct("$token-product",ProductInput("Geräte-Umlagerware $token"))
        val source = store.addLocation("$token-source","Geräte-Vorrat $token",true)
        val destination = store.addLocation("$token-destination","Geräte-Kühlraum $token",true)
        val batch = store.receive("$token-buy",BatchInput(version,source,100_000L,today,release = ReleaseStatus.RELEASED),true)
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount(); val prices = dao.allPaidPrices()
        val bodies = db.bodyDao().versions(); val health = db.healthDao().history("com.sec.android.app.shealth")
        try {
            compose.onNodeWithTag("workspace_tab_STOCK").performClick(); waitTag("stock_overview_search")
            text("Umlagern, entsorgen oder zählen")
            tag("Quellcharge"); tag("choose_$batch"); tag("Ziellager"); tag("choose_$destination")
            field("Menge für Bestandsvorgang","20"); field("Begründung des Bestandsvorgangs","Geräte-Umlagerung")
            text(conditions); text(approval)
            compose.activityRule.scenario.recreate(); waitTag("stock_overview_search")
            text("Umlagern, entsorgen oder zählen")
            compose.onNode(hasText("Menge für Bestandsvorgang") and hasSetTextAction()).assertTextContains("20")
            compose.onNodeWithText(conditions).assertIsOff(); compose.onNodeWithText(approval).assertIsOff()
            tag("Bestandsvorgang buchen"); rejected()
            assertEquals(before,dao.operationCount()); assertEquals(100_000L,dao.balance(batch))
            text(conditions); text(approval); tag("Bestandsvorgang buchen")
            compose.waitUntil(25_000) { runBlocking { dao.balance(batch) == 80_000L } }
            val moved = dao.planningBatches().single { it.batch.parentBatchId == batch }
            assertEquals(destination,moved.batch.locationId); assertEquals(20_000L,moved.stockAmount)
            assertEquals(version,moved.batch.versionId); assertEquals(ReleaseStatus.RELEASED,moved.batch.release)
            assertEquals(before + 1,dao.operationCount())

            tag("Neuen Vorgang beginnen"); compose.onNodeWithText(approval).assertIsOff()
            compose.onNodeWithText(conditions).assertIsOff()
            tag("stock_action_Entsorgen"); tag("Quellcharge"); tag("choose_${moved.batch.id}")
            field("Menge für Bestandsvorgang","5"); field("Begründung des Bestandsvorgangs","Geräte-Verderb")
            text(approval); tag("Bestandsvorgang buchen")
            compose.waitUntil(25_000) { runBlocking { dao.balance(moved.batch.id) == 15_000L } }
            val waste = dao.stockMovementSnapshotRows().single { it.batchId == moved.batch.id && it.movementKind == MovementKind.WASTE }
            assertEquals(moved.batch.id,store.waste(waste.operationId,WasteInput(moved.batch.id,5_000L,today,"Geräte-Verderb"),true))
            assertEquals(before + 2,dao.operationCount()); assertEquals(meals,dao.activeConsumptionCount())

            tag("Neuen Vorgang beginnen"); compose.onNodeWithText(approval).assertIsOff()
            tag("stock_action_Istzählung"); tag("Quellcharge"); tag("choose_${moved.batch.id}")
            field("Tatsächlich gezählter Restbestand · 0 erlaubt","0")
            field("Begründung des Bestandsvorgangs","Geräte-Leerzählung")
            text(approval); compose.activityRule.scenario.recreate(); waitTag("stock_overview_search")
            text("Umlagern, entsorgen oder zählen")
            compose.onNodeWithText(approval).assertIsOff()
            compose.onNode(hasText("Tatsächlich gezählter Restbestand · 0 erlaubt") and hasSetTextAction()).assertTextContains("0")
            tag("Bestandsvorgang buchen"); rejected()
            assertEquals(before + 2,dao.operationCount()); assertEquals(15_000L,dao.balance(moved.batch.id))
            text(approval); tag("Bestandsvorgang buchen")
            compose.waitUntil(25_000) { runBlocking { dao.balance(moved.batch.id) == 0L } }
            val count = dao.stockMovementSnapshotRows().single { it.batchId == moved.batch.id && it.movementKind == MovementKind.CORRECTION }
            assertEquals(-15_000L,count.delta)
            assertEquals(moved.batch.id,store.correctStock(count.operationId,StockCountInput(moved.batch.id,15_000L,0L,today,"Geräte-Leerzählung"),true))
            assertEquals(before + 3,dao.operationCount())

            tag("Neuen Vorgang beginnen")
            compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_HISTORY")
            text("Vorgang prüfen und stornieren")
            tag("Ursprungsvorgang · letzte 30"); tag("choose_${count.operationId}")
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Vorgesehene Gegenbuchungen").fetchSemanticsNodes().size == 1 }
            field("Stornogrund","Geräte-Zählfehler")
            val reverseApproval = "Diese vollständige Gegenbuchung ausdrücklich bestätigen"
            text(reverseApproval)
            compose.activityRule.scenario.recreate()
            text("Vorgang prüfen und stornieren")
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Vorgesehene Gegenbuchungen").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(reverseApproval).assertIsOff()
            tag("Vorgang stornieren"); rejected()
            assertEquals(before + 3,dao.operationCount()); assertEquals(0L,dao.balance(moved.batch.id))
            text(reverseApproval); tag("Vorgang stornieren")
            compose.waitUntil(25_000) { runBlocking { dao.reversal(count.operationId) != null } }
            val reversal = requireNotNull(dao.reversal(count.operationId))
            val original = dao.movements(count.operationId).single()
            val opposite = dao.movements(reversal.reversalOperationId).single()
            assertEquals(original.id,opposite.reversesId); assertEquals(-original.delta,opposite.delta)
            assertEquals(count.operationId,store.reverse(reversal.reversalOperationId,ReversalInput(count.operationId,today,"Geräte-Zählfehler"),true))
            assertEquals(before + 4,dao.operationCount()); assertEquals(15_000L,dao.balance(moved.batch.id))
            assertEquals(80_000L,dao.balance(batch)); assertEquals(meals,dao.activeConsumptionCount())

            val reopened = Room.databaseBuilder(target,FoodDatabase::class.java,FoodDatabase.TEST_DATABASE_NAME).build()
            try {
                assertEquals(before + 4,reopened.foodDao().operationCount())
                assertEquals(80_000L,reopened.foodDao().balance(batch)); assertEquals(15_000L,reopened.foodDao().balance(moved.batch.id))
                assertEquals(original,reopened.foodDao().movements(count.operationId).single())
                assertEquals(opposite,reopened.foodDao().movements(reversal.reversalOperationId).single())
                assertEquals(reversal,reopened.foodDao().reversal(count.operationId))
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
            } finally { reopened.close() }
            assertEquals(prices,dao.allPaidPrices()); assertEquals(bodies,db.bodyDao().versions())
            assertEquals(health,db.healthDao().history("com.sec.android.app.shealth")); assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("stock-events-flow"); throw failure }
    }
}
