package de.ernaehrung.demo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LocalDataViewModel
import de.ernaehrung.demo.ui.screens.LocalDataContent
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
class FefoMealUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()

    @Test fun changedFefoProposalNeedsFreshConsentThenOneMealConsumesAllAllocationsAtomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(rule.activity.applicationContext, FoodDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val store = FoodStore(db)
            val day = AppCalendar.today()
            val version = store.rememberProduct("fefo-rice", ProductInput("FEFO Reis",
                source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0))
            val location = store.addLocation("fefo-location", "Vorrat", true)
            val early = store.receive("fefo-early", BatchInput(version, location, 40_000L,
                day.toString(), useBy = day.plusDays(1).toString(), release = ReleaseStatus.RELEASED), true)
            val later = store.receive("fefo-later", BatchInput(version, location, 60_000L,
                day.toString(), useBy = day.plusDays(3).toString(), release = ReleaseStatus.RELEASED), true)
            val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(store) as T
            })[LocalDataViewModel::class.java]
            rule.runOnUiThread { rule.activity.setContent { DemoTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model, "Ernährung") }
            } } }
            rule.waitUntil(25_000) {
                rule.onAllNodesWithText("Tatsächlichen Verzehr erfassen").fetchSemanticsNodes().isNotEmpty()
            }
            tap("Tatsächlichen Verzehr erfassen")
            rule.onNodeWithTag("meal_source_Bestand nach FEFO").performScrollTo().performClick()
            rule.onNodeWithTag("Verzehr-Artikelversion").performScrollTo().performClick()
            rule.onNodeWithTag("choose_$version").performScrollTo().performClick()
            rule.onNodeWithText("Mahlzeit / Beschreibung").performScrollTo().performTextReplacement("FEFO Mahlzeit")
            rule.onNodeWithText("Gegessene Menge in g").performScrollTo().performTextReplacement("100")
            rule.onNodeWithTag("meal_fefo_$early").assertTextContains("40 g", substring = true)
            rule.onNodeWithTag("meal_fefo_$later").assertTextContains("60 g", substring = true)
            assertEquals(0, db.foodDao().consumptionCount())
            tap("Tatsächlich gegessen · lokal speichern")
            rule.onNodeWithText("Tatsächlich gegessen · lokal speichern").assertIsOn()
            // A newer, earlier-expiring batch changes the actual proposed plan.
            val newest = store.receive("fefo-newest", BatchInput(version, location, 20_000L,
                day.toString(), useBy = day.toString(), release = ReleaseStatus.RELEASED), true)
            rule.waitUntil(25_000) {
                runCatching { rule.onNodeWithText("Tatsächlich gegessen · lokal speichern").assertIsOff(); true }.getOrDefault(false)
            }
            val baseline = db.foodDao().operationCount()
            tap("Verzehr lokal buchen")
            rule.waitUntil(25_000) {
                rule.onAllNodesWithText("Tatsächlichen Verzehr bestätigen.", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(model.saveState.value.succeeded)
            assertEquals(baseline, db.foodDao().operationCount())
            assertEquals(0, db.foodDao().consumptionCount())
            tap("Tatsächlich gegessen · lokal speichern")
            tap("Verzehr lokal buchen")
            rule.waitUntil(25_000) {
                runCatching { rule.onNodeWithTag("active_meal_count").assertTextContains("1 aktive", substring = true); true }.getOrDefault(false)
            }
            assertEquals(0L, db.foodDao().balance(newest))
            assertEquals(0L, db.foodDao().balance(early))
            assertEquals(20_000L, db.foodDao().balance(later))
            val meal = db.foodDao().activeMeals(day.toString()).single()
            assertEquals(1, db.foodDao().activeConsumptionCount())
            assertTrue(db.foodDao().purchases().isEmpty())
            val nutrition = store.dayNutrition(day.toString()).totals
            assertEquals(100.0, requireNotNull(nutrition[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
            assertNull(nutrition[Nutrient.PROTEIN].knownAmount)
            val input = ConsumptionInput(version, day.toString(), "FEFO Mahlzeit", 100_000L,
                listOf(Allocation(newest, 20_000L), Allocation(early, 40_000L), Allocation(later, 40_000L)))
            assertEquals(meal.id, store.consume(meal.operationId, input, true))
            assertEquals(baseline + 1, db.foodDao().operationCount())
            store.reverse("fefo-undo", ReversalInput(meal.operationId, day.toString(), "Geprüfte Testkorrektur"), true)
            assertEquals(20_000L, db.foodDao().balance(newest))
            assertEquals(40_000L, db.foodDao().balance(early))
            assertEquals(60_000L, db.foodDao().balance(later))
            assertEquals(0, db.foodDao().activeConsumptionCount())
        } finally {
            rule.runOnUiThread { rule.activity.setContent {}; models.clear() }
            db.close()
        }
    }
}
