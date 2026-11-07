package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StockHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) { waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun text(value: String) = compose.onNodeWithText(value).performScrollTo().performClick()
    private fun field(label: String,value: String) = compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)

    @Test fun aChargeOpensItsCompleteHistoryAndAnOlderSelectedEventRequiresFreshConsentBeforeItsHistoricalReversal() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val today = AppCalendar.today().toString()
        val version = store.rememberProduct("$token-product",ProductInput("Chronikware $token"))
        val place = store.addLocation("$token-place","Vorrat $token",true)
        val batch = store.receive("$token-buy",BatchInput(version,place,100_000L,today),true)
        val oldest = "$token-loss-00"
        repeat(35) { store.waste("$token-loss-%02d".format(it),WasteInput(batch,1_000L,today,"Verlust $it"),true) }
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount(); val movements = dao.stockMovementSnapshotRows()
        compose.onNodeWithTag("workspace_tab_STOCK").performClick(); waitTag("stock_overview_search")
        compose.onNodeWithTag("stock_overview_search",useUnmergedTree = true).performTextReplacement(token)
        tag("stock_history_$batch"); waitTag("history_page_count")
        compose.onNodeWithTag("history_applied_batch").assertTextContains(batch,substring = true)
        compose.onNodeWithTag("history_operation_$oldest").assertDoesNotExist()
        tag("history_older"); waitTag("history_operation_$oldest")
        assertEquals(before,dao.operationCount()); assertEquals(movements,dao.stockMovementSnapshotRows())
        tag("history_review_$oldest"); waitTag("reversal_selected_operation")
        compose.onNodeWithTag("reversal_selected_operation").assertTextContains(oldest,substring = true)
        field("Stornodatum JJJJ-MM-TT",today); field("Stornogrund","Altvorgang geprüft")
        val approval = "Diese vollständige Gegenbuchung ausdrücklich bestätigen"
        text(approval); compose.activityRule.scenario.recreate()
        waitTag("history_page_count"); text("Vorgang prüfen und stornieren")
        waitTag("reversal_selected_operation")
        compose.onNodeWithTag("reversal_selected_operation").assertTextContains(oldest,substring = true)
        compose.onNodeWithText(approval).assertIsOff()
        tag("Vorgang stornieren")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Nicht gespeichert.",substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before,dao.operationCount()); assertEquals(65_000L,dao.balance(batch))
        text(approval); tag("Vorgang stornieren")
        compose.waitUntil(25_000) { runBlocking { dao.reversal(oldest) != null } }
        assertEquals(before + 1,dao.operationCount()); assertEquals(66_000L,dao.balance(batch)); assertEquals(meals,dao.activeConsumptionCount())
        val reversal = requireNotNull(dao.reversal(oldest))
        assertEquals(dao.movements(oldest).single().id,dao.movements(reversal.reversalOperationId).single().reversesId)
    }
}
