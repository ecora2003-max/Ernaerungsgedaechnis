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
class PlanNutritionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun defaultPlanningUsesRealSavedVersionsAndGoalChangesWithoutBookingOrActivatingAProposal() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val token = UUID.randomUUID().toString()
        val day = AppCalendar.today().toString()
        val rice = store.rememberProduct("rice-$token", ProductInput("Planungsreis", source = Source.USER,
            quality = Quality.CONFIRMED, energyKcal = 100.0, proteinGrams = 0.0))
        store.savePlan("plan-$token", PlanInput("Synthetische Vorschau", day, day, listOf(
            PlanEntryInput("m", day, PlanKind.DIRECT_CONSUMPTION, productVersionId = rice, amount = 200_000, unit = BaseUnit.GRAM)), "Geplant"))
        val entry = db.foodDao().allPlannedEntries().single { it.productVersionId == rice }
        val before = db.foodDao().operationCount()
        val meals = db.foodDao().activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_PLAN").performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Plan-Nährwerte aus gespeicherten Verzehrpositionen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Plan-Nährwerte aus gespeicherten Verzehrpositionen").performScrollTo().performClick()
        val week = "plan_nutrition_week_open_$day"
        compose.waitUntil(25_000) { compose.onAllNodesWithTag(week).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasTestTag(week) and hasText("Plantage anzeigen")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(week).performScrollTo().performClick()
        }
        val tag = "plan_nutrition_day_${day}_ENERGY"
        compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertTextContains("200 kcal", substring = true)
        val oldGoals = store.readNutritionGoals()
        val oldActive = oldGoals.active(day, System.currentTimeMillis())?.id
        val p = requireNotNull(store.proposeNutritionGoals("goal-$token", NutritionGoalDraftInput(oldGoals.latest?.id, day, "Eigene Planungsvorgabe",
            listOf(NutritionGoalRange(Nutrient.PROTEIN, "9,0000", null))), true).versionId)
        assertEquals(oldActive, store.readNutritionGoals().active(day, System.currentTimeMillis())?.id)
        store.confirmNutritionGoals("confirm-$token", p, store.readNutritionGoals().versionBinding(p), true)
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("plan_nutrition_goal_${day}_PROTEIN") and
                hasText("9,0000 bis offen", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plan_nutrition_goal_${day}_PROTEIN").assertTextContains("9,0000 bis offen", substring = true)
        compose.onNodeWithText("Planpositionen und weitere Nährwerte · $day").performScrollTo().performClick()
        compose.onNodeWithTag("plan_nutrition_entry_${entry.id}_ENERGY").assertTextContains("200 kcal", substring = true)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(meals, db.foodDao().activeConsumptionCount())
    }
}
