package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real default Activity/navigation; no replacement content or model injection. */
@RunWith(AndroidJUnit4::class)
class LocalWorkspaceOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlockFixture() = prepareUnlockedTestDevice()
    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test fun actualCatalogToExplicitMealAndActivityRecreationThroughDefaultNavigation() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val key = "workspace-device-" + UUID.randomUUID()
        val before = db.foodDao().activeConsumptionCount()
        val day = AppCalendar.today().toString()
        val dayBefore = store.dayNutrition(day).meals.size
        val stocksBefore = db.foodDao().planningBatches().size
        val purchasesBefore = db.foodDao().purchases().size
        val version = store.rememberProduct(key, ProductInput("Gerätetest Navigation Reis",
            source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0))
        compose.waitUntil(25_000) {
            runCatching { compose.onNodeWithTag("workspace_catalog_count").assertExists(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("screen_S01").assertDoesNotExist()
        compose.onNodeWithTag("workspace_tab_MORE").performClick()
        compose.onNodeWithTag("workspace_go_CATALOG").performScrollTo().performClick()
        compose.waitUntil(25_000) {
            compose.onAllNodesWithText("Gerätetest Navigation Reis").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("workspace_tab_HOME").performClick()
        compose.onNodeWithTag("workspace_go_NUTRITION").performScrollTo().performClick()
        tap("Tatsächlichen Verzehr erfassen")
        compose.onNodeWithTag("Verzehr-Artikelversion").performScrollTo().performClick()
        compose.onNodeWithTag("choose_$version").performScrollTo().performClick()
        compose.onNodeWithText("Mahlzeit / Beschreibung").performScrollTo().performTextReplacement("Gerätenavigation Mahlzeit")
        compose.onNodeWithText("Gegessene Menge in g").performScrollTo().performTextReplacement("100")
        tap("Tatsächlich gegessen · lokal speichern")
        tap("Verzehr lokal buchen")
        compose.waitUntil(25_000) {
            runCatching { compose.onNodeWithTag("active_meal_count")
                .assertTextContains("${dayBefore + 1} aktive", substring = true); true }.getOrDefault(false)
        }
        assertEquals(before + 1, db.foodDao().activeConsumptionCount())
        assertEquals(stocksBefore, db.foodDao().planningBatches().size)
        assertEquals(purchasesBefore, db.foodDao().purchases().size)
        val nutrition = store.dayNutrition(day).meals.single { it.event.productVersionId == version }.nutrition
        assertEquals(100.0, requireNotNull(nutrition[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
        assertNull(nutrition[Nutrient.PROTEIN].knownAmount)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("workspace_page_NUTRITION").assertExists()
        compose.onNodeWithTag("active_meal_count").assertTextContains("${dayBefore + 1} aktive", substring = true)
        assertEquals(before + 1, db.foodDao().activeConsumptionCount())
    }
}
