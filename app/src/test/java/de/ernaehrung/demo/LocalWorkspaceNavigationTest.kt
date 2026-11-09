package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.fitness.*
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
class LocalWorkspaceNavigationTest {
    @get:Rule(order = 0) val database: org.junit.rules.TestRule = IsolatedWorkspaceDatabase()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()

    @Test fun separateWorkoutWindowsKeepTheirOwnSourceVersionWhenGoingBackAfterRecreation(): Unit = runBlocking {
        val db = FoodDatabase.localSandbox(rule.activity.applicationContext)
        val store = FoodStore(db) { HEALTH_AT + 5_000 }
        val a = healthWorkout().copy(externalId = "synthetic-A/:?+%", title = "Synthetisches Training A")
        val b = a.copy(externalId = "synthetic-B/:?+%", title = "Synthetisches Training B",
            startAt = a.startAt - 120_000, endAt = a.startAt - 60_000)
        val source = SyntheticHealthSource(healthSnapshot(records = listOf(a, b)))
        store.enableFitnessImport("navigation-core-enable", true, true)
        store.syncFitness("navigation-core-read", source)
        store.enableFitnessImport("navigation-core-stop", false, true)
        val foodBefore = db.foodDao().operationCount()
        fun open(id: String) {
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("journal_source_window_$id").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("journal_source_window_$id").performScrollTo().performClick()
        }
        fun window(title: String) {
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("workout_source_window").fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("workspace_page_WORKOUT_SOURCE").assertExists()
        }
        rule.onNodeWithTag("workspace_tab_MORE").performClick()
        rule.onNodeWithTag("workspace_go_TRAINING").performScrollTo().performClick()
        open(a.externalId); window(requireNotNull(a.title))
        tap("Trainingsjournal öffnen")
        open(b.externalId); window(requireNotNull(b.title))
        rule.activityRule.scenario.recreate()
        window(requireNotNull(b.title))
        rule.onNodeWithTag("workspace_back").performClick()
        rule.onNodeWithTag("training_journal").assertExists()
        rule.onNodeWithTag("workspace_back").performClick()
        window(requireNotNull(a.title))
        rule.onNodeWithText(requireNotNull(b.title)).assertDoesNotExist()
        assertEquals(1, source.reads.get())
        assertEquals(foodBefore, db.foodDao().operationCount())
        assertFalse(requireNotNull(db.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE)).enabled)
    }

    @Test fun defaultNavigationUsesActualRoomDataAndExplicitMealSurvivesActivityRecreation() = runBlocking {
        val db = FoodDatabase.localSandbox(rule.activity.applicationContext)
        val store = FoodStore(db)
        val version = store.rememberProduct("workspace-rice", ProductInput("Navigationstest Reis",
            source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0))
        rule.waitUntil(25_000) {
            runCatching { rule.onNodeWithTag("workspace_catalog_count").assertTextContains("1", substring = true); true }.getOrDefault(false)
        }
        rule.onNodeWithTag("screen_S01").assertDoesNotExist()
        rule.onNodeWithTag("workspace_tab_MORE").performClick()
        rule.onNodeWithTag("workspace_go_CATALOG").performScrollTo().performClick()
        rule.onNodeWithText("Navigationstest Reis").assertExists()
        rule.onNodeWithTag("workspace_tab_HOME").performClick()
        rule.onNodeWithTag("workspace_go_NUTRITION").performScrollTo().performClick()
        tap("Tatsächlichen Verzehr erfassen")
        rule.onNodeWithTag("Verzehr-Artikelversion").performScrollTo().performClick()
        rule.onNodeWithTag("choose_$version").performScrollTo().performClick()
        rule.onNodeWithText("Mahlzeit / Beschreibung").performScrollTo().performTextReplacement("Lokale Mahlzeit")
        rule.onNodeWithText("Gegessene Menge in g").performScrollTo().performTextReplacement("100")
        tap("Tatsächlich gegessen · lokal speichern")
        tap("Verzehr lokal buchen")
        rule.waitUntil(25_000) {
            runCatching { rule.onNodeWithTag("active_meal_count").assertTextContains("1 aktive", substring = true); true }.getOrDefault(false)
        }
        assertEquals(1, db.foodDao().activeConsumptionCount())
        assertTrue(db.foodDao().planningBatches().isEmpty())
        assertTrue(db.foodDao().purchases().isEmpty())
        val totals = store.dayNutrition(AppCalendar.today().toString()).totals
        assertEquals(100.0, requireNotNull(totals[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
        assertNull(totals[Nutrient.PROTEIN].knownAmount)
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("workspace_page_NUTRITION").assertExists()
        rule.onNodeWithTag("active_meal_count").assertTextContains("1 aktive", substring = true)
        assertEquals(1, db.foodDao().activeConsumptionCount())
        rule.onNodeWithTag("workspace_tab_STOCK").performClick()
        rule.onNodeWithText("Für einen Zugang zuerst ein Produkt im Katalog und einen bestätigten Lagerort anlegen.").assertExists()
        rule.onNodeWithTag("workspace_tab_MORE").performClick()
        rule.onNodeWithTag("workspace_demo").performScrollTo().performClick()
        rule.onNodeWithTag("screen_S01").assertExists()
        assertEquals(1, db.foodDao().activeConsumptionCount()) // Opening the design tour writes nothing.
    }
}
