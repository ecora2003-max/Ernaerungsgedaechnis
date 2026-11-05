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

/** Default app UI, live FEFO proposal invalidation, atomic stock meal, restart. */
@RunWith(AndroidJUnit4::class)
class MultiBatchMealOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlockFixture() = prepareUnlockedTestDevice()
    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test fun changedFefoAllocationsNeedNewGestureAndCommitOnlyOneMealAcrossThreeBatches() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val key = "fefo-device-" + UUID.randomUUID()
        val day = AppCalendar.today()
        val activeBefore = db.foodDao().activeConsumptionCount()
        val dayBefore = store.dayNutrition(day.toString()).meals.size
        val purchasesBefore = db.foodDao().purchases().size
        val version = store.rememberProduct(key, ProductInput("Gerätetest FEFO Reis",
            source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0))
        val location = store.addLocation("$key-location", "FEFO Vorrat", true)
        val early = store.receive("$key-early", BatchInput(version, location, 40_000L,
            day.toString(), useBy = day.plusDays(1).toString(), release = ReleaseStatus.RELEASED), true)
        val later = store.receive("$key-later", BatchInput(version, location, 60_000L,
            day.toString(), useBy = day.plusDays(3).toString(), release = ReleaseStatus.RELEASED), true)
        compose.onNodeWithTag("workspace_go_NUTRITION").performScrollTo().performClick()
        compose.waitUntil(25_000) {
            compose.onAllNodesWithText("Tatsächlichen Verzehr erfassen").fetchSemanticsNodes().isNotEmpty()
        }
        tap("Tatsächlichen Verzehr erfassen")
        compose.onNodeWithTag("meal_source_Bestand nach FEFO").performScrollTo().performClick()
        compose.onNodeWithTag("Verzehr-Artikelversion").performScrollTo().performClick()
        compose.waitUntil(25_000) {
            compose.onAllNodesWithTag("choose_$version").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("choose_$version").performScrollTo().performClick()
        compose.onNodeWithText("Mahlzeit / Beschreibung").performScrollTo().performTextReplacement("FEFO Gerätemahlzeit")
        compose.onNodeWithText("Gegessene Menge in g").performScrollTo().performTextReplacement("100")
        compose.onNodeWithTag("meal_fefo_$early").assertTextContains("40 g", substring = true)
        compose.onNodeWithTag("meal_fefo_$later").assertTextContains("60 g", substring = true)
        assertEquals(activeBefore, db.foodDao().activeConsumptionCount())
        tap("Tatsächlich gegessen · lokal speichern")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("workspace_page_NUTRITION").assertExists()
        tap("Tatsächlichen Verzehr erfassen")
        compose.onNodeWithText("Tatsächlich gegessen · lokal speichern").performScrollTo().assertIsOff()
        tap("Tatsächlich gegessen · lokal speichern")
        val newest = store.receive("$key-newest", BatchInput(version, location, 20_000L,
            day.toString(), useBy = day.toString(), release = ReleaseStatus.RELEASED), true)
        compose.waitUntil(25_000) {
            runCatching { compose.onNodeWithText("Tatsächlich gegessen · lokal speichern").assertIsOff(); true }.getOrDefault(false)
        }
        val operations = db.foodDao().operationCount()
        tap("Verzehr lokal buchen")
        compose.waitUntil(25_000) {
            compose.onAllNodesWithText("Tatsächlichen Verzehr bestätigen.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(operations, db.foodDao().operationCount())
        tap("Tatsächlich gegessen · lokal speichern")
        tap("Verzehr lokal buchen")
        compose.waitUntil(25_000) {
            runCatching { compose.onNodeWithTag("active_meal_count")
                .assertTextContains("${dayBefore + 1} aktive", substring = true); true }.getOrDefault(false)
        }
        assertEquals(activeBefore + 1, db.foodDao().activeConsumptionCount())
        assertEquals(purchasesBefore, db.foodDao().purchases().size)
        assertEquals(0L, db.foodDao().balance(newest))
        assertEquals(0L, db.foodDao().balance(early))
        assertEquals(20_000L, db.foodDao().balance(later))
        val meal = store.dayNutrition(day.toString()).meals.single { it.event.productVersionId == version }
        assertEquals(100.0, requireNotNull(meal.nutrition[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
        assertNull(meal.nutrition[Nutrient.PROTEIN].knownAmount)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("workspace_page_NUTRITION").assertExists()
        assertEquals(meal.event.id, store.consume(meal.event.operationId,
            ConsumptionInput(version, day.toString(), "FEFO Gerätemahlzeit", 100_000L,
                listOf(Allocation(newest, 20_000L), Allocation(early, 40_000L), Allocation(later, 40_000L))), true))
        assertEquals(operations + 1, db.foodDao().operationCount())
        assertEquals(activeBefore + 1, db.foodDao().activeConsumptionCount())
    }
}
