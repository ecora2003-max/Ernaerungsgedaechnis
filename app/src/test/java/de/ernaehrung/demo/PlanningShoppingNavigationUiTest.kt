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
class PlanningShoppingNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun input(tag: String,value: String) = compose.onNodeWithTag(tag,useUnmergedTree = true).performScrollTo().performTextReplacement(value)
    @Test fun appliedPlanningPeriodOpensTheSameShoppingPeriodKeepsExplicitPurchaseAndBudgetAndWritesNothing() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val token = UUID.randomUUID().toString()
        val first = AppCalendar.today().plusDays(1)
        val last = first.plusDays(55)
        val version = store.rememberProduct("$token-product",ProductInput("Zeitraumzutat $token"))
        store.savePlan("$token-plan",PlanInput("Acht Wochen $token",first.toString(),last.toString(),listOf(
            PlanEntryInput("first",first.toString(),PlanKind.DIRECT_CONSUMPTION,version,amount = 100_000L,unit = BaseUnit.GRAM),
            PlanEntryInput("later",first.plusDays(30).toString(),PlanKind.DIRECT_CONSUMPTION,version,amount = 300_000L,unit = BaseUnit.GRAM)),"Eigene belegte Planung"))
        val budget = store.saveBudget("$token-budget",BudgetInput("Zeitraumbudget $token",first.toString(),last.toString(),1234L,true,"Eigene Budgetgrenze"))
        val before = db.foodDao().operationCount()
        val batches = db.foodDao().planningBatches()
        val meals = db.foodDao().activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_SHOPPING")
        waitTag("shopping_start_input")
        input("shopping_start_input",first.toString()); input("shopping_purchase_input",first.toString())
        waitTag("Budget für Einkaufsvergleich"); tag("Budget für Einkaufsvergleich"); waitTag("choose_$budget"); tag("choose_$budget")
        compose.onNodeWithTag("Einkaufsliste anzeigen").performScrollTo().performClick()
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("shopping_need_$version") and hasText("100 g",substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("workspace_tab_PLAN").performClick()
        waitTag("planning_start_input"); input("planning_start_input",first.toString()); tag("plan_horizon_56 Tage")
        tag("Planungszeitraum anzeigen")
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("planning_applied_scope") and hasText(last.toString(),substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("shopping_$version") and hasText("400 g",substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // Unapplied editing must not silently replace the displayed scope.
        input("planning_start_input",first.plusDays(90).toString())
        tag("plan_open_shopping"); waitTag("workspace_page_SHOPPING")
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("shopping_applied_scope") and hasText(last.toString(),substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shopping_horizon_56 Tage").assertIsSelected()
        compose.onNodeWithTag("shopping_start_input",useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(first.toString())))
        compose.onNodeWithTag("shopping_purchase_input",useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(first.toString())))
        compose.onNodeWithTag("Budget für Einkaufsvergleich").assertTextContains("Zeitraumbudget $token",substring = true)
        compose.onNodeWithTag("shopping_need_$version").assertTextContains("400 g",substring = true)
        compose.onNodeWithTag("shopping_total").assertTextContains("Ungeklärt",substring = true)
        assertEquals(before,db.foodDao().operationCount()); assertEquals(batches,db.foodDao().planningBatches())
        assertEquals(meals,db.foodDao().activeConsumptionCount())
    }
}
