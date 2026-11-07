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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NutritionDayConfirmationUiTest {
    @get:Rule(order = 0) val database: org.junit.rules.TestRule = IsolatedWorkspaceDatabase()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) {
        waitTag(tag)
        val node = compose.onNodeWithTag(tag)
        if (tag != "confirm_day_status") node.performScrollTo()
        node.performClick()
    }
    private fun text(text: String) {
        compose.waitUntil(25_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun day(date: String) {
        compose.onNodeWithTag("nutrition_day_input",useUnmergedTree = true).performScrollTo().performTextReplacement(date)
        tag("Tag anzeigen")
        waitTag("nutrition_applied_day_$date")
    }
    private fun openNutrition() {
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_NUTRITION"); waitTag("day_status")
    }

    @Test fun switchingTheActualDayWithdrawsManualMealApprovalAndFreshApprovalBooksOnlyThatDay() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val dao = db.foodDao(); val store = FoodStore(db)
        val version = store.rememberProduct("product",ProductInput("Synthetische Tageszutat",energyKcal = 0.0))
        val before = dao.operationCount(); val stock = dao.planningBatches()
        val first = "2020-04-09"; val second = "2020-04-10"
        openNutrition(); day(first); text("Tatsächlichen Verzehr erfassen")
        tag("Verzehr-Artikelversion"); tag("choose_$version")
        compose.onNodeWithText("Mahlzeit / Beschreibung").performScrollTo().performTextReplacement("Tatsächliche eigene Mahlzeit")
        compose.onNodeWithText("Gegessene Menge in g").performScrollTo().performTextReplacement("100")
        val approval = "Tatsächlich gegessen · lokal speichern"
        text(approval); compose.onNodeWithText(approval).assertIsOn()
        day(second)
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Datum: $second. Einen anderen Tag oben auswählen.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(approval).assertIsOff()
        tag("Verzehr lokal buchen")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Tatsächlichen Verzehr bestätigen.",substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before,dao.operationCount()); assertTrue(store.dayNutrition(first).meals.isEmpty()); assertTrue(store.dayNutrition(second).meals.isEmpty())
        text(approval)
        compose.activityRule.scenario.recreate(); waitTag("day_status"); text("Tatsächlichen Verzehr erfassen")
        compose.onNodeWithText(approval).assertIsOff()
        compose.onNode(hasText("Gegessene Menge in g") and hasSetTextAction()).assertTextContains("100")
        text(approval); tag("Verzehr lokal buchen")
        compose.waitUntil(25_000) { runBlocking { store.dayNutrition(second).meals.size == 1 } }
        val meal = store.dayNutrition(second).meals.single()
        assertEquals(second,meal.event.date); assertTrue(store.dayNutrition(first).meals.isEmpty())
        assertEquals(before + 1,dao.operationCount()); assertEquals(stock,dao.planningBatches())
        assertEquals(meal.event.id,store.consume(meal.event.operationId,ConsumptionInput(version,second,"Tatsächliche eigene Mahlzeit",100_000L),true))
        assertEquals(before + 1,dao.operationCount())
    }

    @Test fun pendingDayCompletionIsDiscardedOnRecreationAndEmptyCompleteDaysDoNotAcquireInventedNutrition() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val dao = db.foodDao(); val store = FoodStore(db)
        val before = dao.operationCount(); val date = "2020-04-10"
        openNutrition(); day(date); tag("Erfassung als vollständig bestätigen")
        text("Alle Mahlzeiten dieses Tages sind erfasst")
        compose.onNodeWithTag("confirm_day_status").assertIsEnabled()
        compose.activityRule.scenario.recreate(); waitTag("workspace_page_NUTRITION")
        compose.onNodeWithTag("day_status_dialog").assertDoesNotExist()
        waitTag("day_status"); assertEquals(before,dao.operationCount())
        assertEquals(DayStatus.OPEN,store.dayNutrition(date).status)
        tag("Erfassung als vollständig bestätigen")
        compose.onNodeWithText("Alle Mahlzeiten dieses Tages sind erfasst").assertIsOff()
        compose.onNodeWithTag("confirm_day_status").assertIsNotEnabled()
        text("Alle Mahlzeiten dieses Tages sind erfasst"); tag("confirm_day_status")
        compose.waitUntil(25_000) { runBlocking { store.dayNutrition(date).status == DayStatus.COMPLETE } }
        assertTrue(store.dayNutrition(date).meals.isEmpty())
        assertNull(store.dayNutrition(date).totals[Nutrient.ENERGY].knownAmount)
        assertEquals(before + 1,dao.operationCount()); assertTrue(dao.planningBatches().isEmpty())
    }

    @Test fun aNewViewModelCannotShowAFormerDateDraftAlongsideTodaysActualReport() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val before = db.foodDao().operationCount()
        openNutrition(); day("2020-04-09")
        compose.onNodeWithTag("nutrition_day_input",useUnmergedTree = true).performScrollTo().performTextReplacement("2020-04-11")
        compose.activityRule.scenario.recreate(); waitTag("nutrition_applied_day_2020-04-09")
        compose.onNodeWithTag("nutrition_day_input",useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("2020-04-11")))
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        compose.activityRule.scenario.recreate(); waitTag("nutrition_applied_day_${AppCalendar.today()}")
        compose.onNodeWithTag("nutrition_day_input",useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(AppCalendar.today().toString())))
        assertEquals(before,db.foodDao().operationCount())
    }
}
