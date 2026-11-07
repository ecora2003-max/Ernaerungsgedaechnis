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
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StockEventConfirmationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) { waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun text(value: String) = compose.onNodeWithText(value).performScrollTo().performClick()
    private fun field(label: String, value: String) = compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
    private val approval = "Diesen tatsächlichen Bestandsvorgang bestätigen"
    private val conditions = "Geeignete Zielbedingungen geprüft · bestehende Freigabe beibehalten"

    @Test fun transferFieldsSurviveActivityRecreationButStockAndTargetSafetyApprovalsRequireFreshGestures() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val store = FoodStore(db); val dao = db.foodDao()
        val token = UUID.randomUUID().toString(); val today = AppCalendar.today().toString()
        val version = store.rememberProduct("$token-product", ProductInput("Umlagerware $token"))
        val source = store.addLocation("$token-source", "Vorrat $token", true)
        val target = store.addLocation("$token-target", "Kühlraum $token", true)
        val batch = store.receive("$token-buy", BatchInput(version, source, 100_000, today, release = ReleaseStatus.RELEASED), true)
        val before = dao.operationCount()
        compose.onNodeWithTag("workspace_tab_STOCK").performClick(); waitTag("stock_overview_search")
        text("Umlagern, entsorgen oder zählen")
        tag("Quellcharge"); tag("choose_$batch"); tag("Ziellager"); tag("choose_$target")
        field("Menge für Bestandsvorgang", "10"); field("Begründung des Bestandsvorgangs", "Tatsächlich umgeräumt")
        text(conditions); text(approval)
        compose.onNodeWithText(conditions).assertIsOn(); compose.onNodeWithText(approval).assertIsOn()
        compose.activityRule.scenario.recreate(); waitTag("stock_overview_search")
        text("Umlagern, entsorgen oder zählen")
        compose.onNode(hasText("Menge für Bestandsvorgang") and hasSetTextAction()).assertTextContains("10")
        compose.onNodeWithText(conditions).assertIsOff(); compose.onNodeWithText(approval).assertIsOff()
        tag("Bestandsvorgang buchen")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Nicht gespeichert.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, dao.operationCount()); assertEquals(100_000L, dao.balance(batch))
        text(conditions); text(approval); tag("Bestandsvorgang buchen")
        compose.waitUntil(25_000) { runBlocking { dao.balance(batch) == 90_000L } }
        assertEquals(before + 1, dao.operationCount())
        val moved = dao.planningBatches().single { it.batch.parentBatchId == batch }
        assertEquals(target, moved.batch.locationId); assertEquals(10_000L, moved.stockAmount)
        assertEquals(ReleaseStatus.RELEASED, moved.batch.release)
    }

    @Test fun aNewWasteOperationRetainsItsFieldsButCannotReuseThePreviousSuccessfulApproval() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val store = FoodStore(db); val dao = db.foodDao()
        val token = UUID.randomUUID().toString(); val today = AppCalendar.today().toString()
        val version = store.rememberProduct("$token-product", ProductInput("Entsorgungsware $token"))
        val location = store.addLocation("$token-location", "Vorrat $token", true)
        val batch = store.receive("$token-buy", BatchInput(version, location, 100_000, today), true)
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_STOCK").performClick(); waitTag("stock_overview_search")
        text("Umlagern, entsorgen oder zählen"); tag("stock_action_Entsorgen")
        tag("Quellcharge"); tag("choose_$batch")
        field("Menge für Bestandsvorgang", "10"); field("Begründung des Bestandsvorgangs", "Tatsächlicher Verderb")
        text(approval); tag("Bestandsvorgang buchen")
        compose.waitUntil(25_000) { runBlocking { dao.balance(batch) == 90_000L } }
        assertEquals(before + 1, dao.operationCount())
        tag("Neuen Vorgang beginnen")
        compose.onNodeWithText(approval).assertIsOff()
        compose.onNode(hasText("Menge für Bestandsvorgang") and hasSetTextAction()).assertTextContains("10")
        tag("Bestandsvorgang buchen")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Nicht gespeichert.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before + 1, dao.operationCount()); assertEquals(90_000L, dao.balance(batch))
        text(approval); tag("Bestandsvorgang buchen")
        compose.waitUntil(25_000) { runBlocking { dao.balance(batch) == 80_000L } }
        assertEquals(before + 2, dao.operationCount()); assertEquals(meals, dao.activeConsumptionCount())
    }
}
