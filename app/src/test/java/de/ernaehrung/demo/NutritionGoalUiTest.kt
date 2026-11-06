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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NutritionGoalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun tap(tag: String) {
        if (tag == "nutrition_goals_confirm") compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun field(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun defaultUiKeepsProposalInactiveAndAChangedRoomProposalRevokesTheOpenConfirmation() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val before = db.foodDao().operationCount()
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_NUTRITION_GOALS")
        waitTag("nutrition_goals_active_empty"); tap("nutrition_goals_new")
        compose.onNodeWithTag("nutrition_goals_min_PROTEIN").assertTextEquals("Untergrenze (g)", "")
        field("nutrition_goals_min_PROTEIN", "9,0000"); field("nutrition_goals_max_PROTEIN", "11.000000000000000001")
        field("nutrition_goals_reason", "Synthetischer eigener Zielvorschlag")
        tap("nutrition_goals_save_proposal"); waitTag("nutrition_goals_review")
        compose.onNodeWithTag("nutrition_goals_active_empty").assertExists()
        tap("nutrition_goals_review")
        compose.onNodeWithTag("nutrition_goals_confirm").assertIsNotEnabled()
        compose.onNodeWithText("Diese Bereiche als eigene Vorgabe ausdrücklich bestätigen").performScrollTo().performClick()
        tap("nutrition_goals_confirm"); waitTag("nutrition_goals_active")
        val confirmed = requireNotNull(store.readNutritionGoals().active(AppCalendar.today().toString(), System.currentTimeMillis()))
        tap("nutrition_goals_new")
        field("nutrition_goals_min_PROTEIN", "12"); field("nutrition_goals_max_PROTEIN", "13")
        field("nutrition_goals_reason", "Noch nicht aktivieren")
        tap("nutrition_goals_save_proposal"); waitTag("nutrition_goals_review"); tap("nutrition_goals_review")
        compose.onNodeWithText("Diese Bereiche als eigene Vorgabe ausdrücklich bestätigen").performScrollTo().performClick()
        val data = store.readNutritionGoals()
        store.proposeNutritionGoals("concurrent-goal", NutritionGoalDraftInput(data.latest?.id, AppCalendar.today().toString(),
            "Anderer neuer Entwurf", listOf(NutritionGoalRange(Nutrient.PROTEIN, "14", null))), true)
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Prüfstand geändert. Dialog schließen und neu prüfen.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nutrition_goals_confirm").assertIsNotEnabled()
        compose.onNodeWithText("Diese Bereiche als eigene Vorgabe ausdrücklich bestätigen").assertIsOff()
        assertEquals(confirmed.id, store.readNutritionGoals().active(AppCalendar.today().toString(), System.currentTimeMillis())?.id)
        assertEquals(before, db.foodDao().operationCount())
    }
}
