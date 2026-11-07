package de.ernaehrung.demo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
class StockOverviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) { waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun text(value: String) = compose.onNodeWithText(value).performScrollTo().performClick()
    private fun search(value: String) = compose.onNodeWithTag("stock_overview_search",useUnmergedTree = true).performScrollTo().performTextReplacement(value)
    @Test fun actualStockSearchPreservesVersionsFiltersAndRequiresFreshApprovalBeforeTheLiveStockChanges() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val date = AppCalendar.today().toString()
        val name = "Vorratsware $token"
        val first = store.rememberProduct("$token-v1",ProductInput(name))
        val identity = dao.version(first)!!.productId
        val a = store.addLocation("$token-a","Schrank $token",true)
        val b = store.addLocation("$token-b","Kühlraum $token",true)
        val used = store.receive("$token-first",BatchInput(first,a,100_000L,date,release = ReleaseStatus.RELEASED),true)
        val unchecked = store.receive("$token-unchecked",BatchInput(first,b,50_000L,date),true)
        val second = store.rememberProduct("$token-v2",ProductInput("Neue Vorratsware $token"),identity,"Neue Rezeptur",first)
        val other = store.receive("$token-other",BatchInput(second,b,200_000L,date,release = ReleaseStatus.RELEASED),true)
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount()
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
        search(unchecked)
        compose.onNodeWithTag("stock_overview_physical_$first").assertTextContains("50 g",substring = true)
        compose.onNodeWithTag("stock_overview_usable_$first").assertTextContains("0 g",substring = true)
        assertEquals(before,dao.operationCount()); assertEquals(meals,dao.activeConsumptionCount())
        search(token); compose.activityRule.scenario.recreate(); waitTag("stock_overview_search")
        compose.onNodeWithTag("stock_overview_search",useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(token)))
        text("Testverzehr aus einer Charge"); text("Charge"); text("$name · $used")
        compose.onNodeWithText("Verzehrmenge in g").performScrollTo().performTextReplacement("10")
        val approval = "Tatsächlich gegessen · als Testereignis bestätigen"
        text(approval); compose.onNodeWithText(approval).assertIsOn()
        compose.activityRule.scenario.recreate(); waitTag("stock_overview_search")
        text("Testverzehr aus einer Charge")
        compose.onNodeWithText(approval).assertIsOff()
        compose.onNode(hasText("Verzehrmenge in g") and hasSetTextAction()).assertTextContains("10")
        text(approval); tag("Testverzehr buchen")
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("stock_overview_physical_$first") and hasText("140 g",substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("stock_overview_usable_$first").assertTextContains("90 g",substring = true)
        assertEquals(before + 1,dao.operationCount()); assertEquals(meals + 1,dao.activeConsumptionCount())
        assertEquals(90_000L,dao.balance(used)); assertEquals(50_000L,dao.balance(unchecked)); assertEquals(200_000L,dao.balance(other))
    }
}
