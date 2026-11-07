package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StockOverviewOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) {
        finishDeviceTextEditing(compose); waitTag(tag)
        val node = compose.onNodeWithTag(tag)
        if (tag != "workspace_back") node.performScrollTo()
        node.performClick()
    }
    private fun text(text: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun search(value: String) = compose.onNodeWithTag("stock_overview_search",useUnmergedTree = true).performScrollTo().performTextReplacement(value)

    @Test fun offlineVersionedStockFiltersReviewNavigationAndExplicitConsumptionPreserveHistoryAndRetryAcrossRestart() = runBlocking {
        assertTrue("Synthetic stock only on disposable emulator",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = compose.activity.applicationContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val date = AppCalendar.today().toString()
        val name = "Geräte-Vorratsware $token"
        val first = store.rememberProduct("$token-v1",ProductInput(name))
        val identity = dao.version(first)!!.productId
        val a = store.addLocation("$token-place-a","Geräte-Schrank $token",true)
        val b = store.addLocation("$token-place-b","Geräte-Kühlraum $token",true)
        val used = store.receive("$token-received-a",BatchInput(first,a,100_000L,date,release = ReleaseStatus.RELEASED),true)
        val unchecked = store.receive("$token-received-b",BatchInput(first,b,50_000L,date),true)
        val second = store.rememberProduct("$token-v2",ProductInput("Neue Geräte-Vorratsware $token"),identity,"Neue Rezeptur",first)
        val other = store.receive("$token-received-c",BatchInput(second,b,200_000L,date,release = ReleaseStatus.RELEASED),true)
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount(); val prices = dao.prices()
        val bodies = db.bodyDao().versions(); val health = db.healthDao().history("com.sec.android.app.shealth")
        try {
            compose.onNodeWithTag("workspace_tab_STOCK").performClick(); waitTag("stock_overview_search"); search(token)
            waitTag("stock_overview_physical_$first")
            compose.onNodeWithTag("stock_overview_physical_$first").assertTextContains("150 g",substring = true)
            compose.onNodeWithTag("stock_overview_usable_$first").assertTextContains("100 g",substring = true)
            compose.onNodeWithTag("stock_overview_physical_$second").assertTextContains("200 g",substring = true)
            text("Nur heute nach Bestandsregeln angerechnete Chargen")
            compose.onNodeWithTag("stock_batch_$unchecked").assertDoesNotExist()
            compose.onNodeWithTag("stock_overview_physical_$first").assertTextContains("100 g",substring = true)
            text("Nur heute nach Bestandsregeln angerechnete Chargen")
            tag("Lagerort der Übersicht"); tag("choose_$a")
            compose.onNodeWithTag("stock_overview_group_$second").assertDoesNotExist()
            tag("Lagerort der Übersicht"); tag("choose_")
            tag("stock_review_$unchecked"); waitTag("workspace_page_BATCH_REVIEW"); waitTag("batch_review_balance")
            compose.onNodeWithTag("batch_review_product").assertTextEquals(name)
            compose.onNodeWithTag("batch_review_balance").assertTextContains("50 g",substring = true)
            assertEquals(before,dao.operationCount())
            tag("workspace_back"); waitTag("stock_overview_search")
            compose.onNodeWithTag("stock_overview_search",useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(token)))
            text("Testverzehr aus einer Charge"); text("Charge"); text("$name · $used")
            compose.onNode(hasText("Verzehrmenge in g") and hasSetTextAction()).performScrollTo().performTextReplacement("10")
            val approval = "Tatsächlich gegessen · als Testereignis bestätigen"
            text(approval); compose.onNodeWithText(approval).assertIsOn()
            compose.activityRule.scenario.recreate(); waitTag("stock_overview_search")
            text("Testverzehr aus einer Charge"); compose.onNodeWithText(approval).assertIsOff()
            compose.onNode(hasText("Verzehrmenge in g") and hasSetTextAction()).assertTextContains("10")
            text(approval); tag("Testverzehr buchen")
            compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("stock_overview_physical_$first") and hasText("140 g",substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("stock_overview_usable_$first").assertTextContains("90 g",substring = true)
            assertEquals(before + 1,dao.operationCount()); assertEquals(meals + 1,dao.activeConsumptionCount())
            val meal = store.dayNutrition(date).meals.single { it.event.productVersionId == first }
            assertEquals(meal.event.id,store.consume(meal.event.operationId,ConsumptionInput(first,date,"Testmahlzeit",10_000L,
                listOf(Allocation(used,10_000L))),true))
            compose.activityRule.scenario.recreate(); waitTag("stock_overview_search")
            val reopened = Room.databaseBuilder(target,FoodDatabase::class.java,FoodDatabase.TEST_DATABASE_NAME).build()
            try {
                assertEquals(90_000L,reopened.foodDao().balance(used)); assertEquals(50_000L,reopened.foodDao().balance(unchecked))
                assertEquals(200_000L,reopened.foodDao().balance(other)); assertEquals(2,reopened.foodDao().versions(identity).size)
                assertEquals(meal.event,reopened.foodDao().consumption(meal.event.id))
                assertEquals(before + 1,reopened.foodDao().operationCount())
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
            } finally { reopened.close() }
            assertEquals(prices,dao.prices()); assertEquals(bodies,db.bodyDao().versions())
            assertEquals(health,db.healthDao().history("com.sec.android.app.shealth")); assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("stock-overview-flow"); throw failure }
    }
}
