package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NutritionGoalOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun tap(tag: String) {
        finishDeviceTextEditing(compose)
        if (tag in setOf("nutrition_goals_confirm", "nutrition_goals_erase_confirm", "workspace_back"))
            compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun field(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)

    @Test fun offlineExplicitPersonalGoalsSurviveRestartKeepUnknownIntakeUnknownAndEraseOnlyTheirOwnPrivateHistory() = runBlocking {
        assertTrue("Synthetic profile only on disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val store = FoodStore(db)
        assertTrue("Fresh goal domain must contain no other profile to erase", store.readNutritionGoals().versions.isEmpty())
        val token = UUID.randomUUID().toString()
        val food = store.rememberProduct("goal-food-$token", ProductInput("Synthetischer Verzehr mit unbekannter Menge",
            source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0, proteinGrams = 0.0))
        store.consume("goal-meal-$token", ConsumptionInput(food, AppCalendar.today().toString(), "Synthetische unbekannte Menge",
            null, quality = Quality.CONFIRMED), true)
        val before = db.foodDao().operationCount()
        val meals = db.foodDao().activeConsumptionCount()
        val batches = db.foodDao().planningBatches()
        val bodies = db.bodyDao().versions()
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_NUTRITION_GOALS")
            waitTag("nutrition_goals_active_empty"); tap("nutrition_goals_new")
            field("nutrition_goals_min_PROTEIN", "0,0000")
            field("nutrition_goals_reason", "Synthetische eigene Vorgabe; kein Ernährungsvorschlag")
            tap("nutrition_goals_save_proposal"); waitTag("nutrition_goals_review")
            compose.onNodeWithTag("nutrition_goals_active_empty").assertExists()
            tap("nutrition_goals_review")
            compose.onNodeWithTag("nutrition_goals_confirm").assertIsNotEnabled()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("workspace_page_NUTRITION_GOALS").assertExists()
            compose.onNodeWithTag("nutrition_goals_confirm_dialog").assertDoesNotExist()
            compose.onNodeWithTag("nutrition_goals_reason").assertDoesNotExist()
            waitTag("nutrition_goals_review"); tap("nutrition_goals_review")
            compose.onNodeWithTag("nutrition_goals_confirm").assertIsNotEnabled()
            compose.onNodeWithText("Diese Bereiche als eigene Vorgabe ausdrücklich bestätigen").performScrollTo().performClick()
            tap("nutrition_goals_confirm"); waitTag("nutrition_goals_active")
            val goals = store.readNutritionGoals()
            assertEquals(2, goals.versions.size)
            assertEquals(NutritionGoalRules.PROPOSED, goals.versions.first().status)
            assertEquals(NutritionGoalRules.CONFIRMED, goals.versions.last().status)
            assertEquals("0,0000", goals.ranges(goals.latest!!.id).single().minimumText)
            assertNull(goals.ranges(goals.latest!!.id).single().maximumText)
            compose.onNodeWithTag("workspace_tab_HOME").performClick(); tap("workspace_go_NUTRITION")
            waitTag("nutrition_day_goal_PROTEIN")
            compose.onNodeWithTag("nutrition_day_goal_PROTEIN").assertTextContains("0,0000 bis offen", substring = true)
            assertTrue(store.dayNutrition(AppCalendar.today().toString()).totals[Nutrient.PROTEIN].missingCount > 0)
            compose.onNodeWithTag("nutrition_day_goal_result_PROTEIN").assertTextContains("un", substring = true)
            compose.onNodeWithTag("nutrition_day_goal_result_PROTEIN").assert(!hasText("Rechnerisch", substring = true))
            // Keep the original diary entry below this screen in the back stack;
            // erased personal values must not return from its suspended observer.
            tap("nutrition_open_goals")
            waitTag("nutrition_goals_erase"); tap("nutrition_goals_erase")
            compose.onNodeWithTag("nutrition_goals_erase_confirm").assertIsNotEnabled()
            compose.onNodeWithText("Diesen aktuellen Zielumfang unwiderruflich löschen").performClick()
            tap("nutrition_goals_erase_confirm"); waitTag("nutrition_goals_active_empty")
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("nutrition_goals_erase_dialog").fetchSemanticsNodes().isEmpty() }
            assertTrue(store.readNutritionGoals().versions.isEmpty())
            assertTrue(db.nutritionGoalDao().values().isEmpty())
            assertEquals(before, db.foodDao().operationCount())
            assertEquals(meals, db.foodDao().activeConsumptionCount())
            assertEquals(batches, db.foodDao().planningBatches())
            assertEquals(bodies, db.bodyDao().versions())
            tap("workspace_back")
            waitTag("nutrition_day_goals_empty")
            compose.onNodeWithTag("nutrition_day_goal_PROTEIN").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            waitTag("nutrition_day_goals_empty")
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("nutrition-goals-flow"); throw failure }
    }
}
