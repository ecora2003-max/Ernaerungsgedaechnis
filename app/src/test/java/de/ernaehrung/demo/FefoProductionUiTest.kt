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
import kotlinx.coroutines.flow.first
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
class FefoProductionUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()
    private fun choose(label: String, id: String) {
        rule.onNodeWithTag(label).performScrollTo().performClick()
        rule.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }
    private fun field(label: String, value: String) =
        rule.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    private fun saved() = rule.waitUntil(25_000) {
        rule.onAllNodesWithText("Neuen Vorgang beginnen").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun fefoRecipeManufacturingRevokesStaleConsentAndEatingRequiresASeparateExplicitEvent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(rule.activity.applicationContext, FoodDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val store = FoodStore(db)
            val day = AppCalendar.today()
            val version = store.rememberProduct("rice", ProductInput("FEFO Reis",
                source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0))
            val location = store.addLocation("pantry", "Vorrat", true)
            val early = store.receive("early", BatchInput(version, location, 40_000L,
                day.toString(), useBy = day.plusDays(1).toString(), release = ReleaseStatus.RELEASED), true)
            val later = store.receive("later", BatchInput(version, location, 60_000L,
                day.toString(), useBy = day.plusDays(3).toString(), release = ReleaseStatus.RELEASED), true)
            val recipe = store.saveRecipe("recipe", RecipeInput("FEFO Testgericht", 2_000L,
                listOf(RecipeItemInput(version, 100_000L)), "Kochen"))
            val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(store) as T
            })[LocalDataViewModel::class.java]
            rule.runOnUiThread { rule.activity.setContent { DemoTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model, "Küche") }
            } } }
            rule.waitUntil(25_000) {
                rule.onAllNodesWithText("Tatsächliche Herstellung buchen").fetchSemanticsNodes().isNotEmpty()
            }
            tap("Tatsächliche Herstellung buchen")
            choose("Rezept für Herstellung", recipe)
            field("Tatsächlich hergestellte Portionen", "2")
            rule.onNodeWithTag("production_source_Chargen nach FEFO vorschlagen").performScrollTo().performClick()
            rule.onNodeWithTag("production_fefo_$early").assertTextContains("40 g", substring = true)
            rule.onNodeWithTag("production_fefo_$later").assertTextContains("60 g", substring = true)
            assertEquals(0, db.foodDao().activeConsumptionCount())
            assertTrue(db.foodDao().observeOperations().first().none { it.operation.kind == "PRODUCTION" })
            choose("Fertigportionen lagern in", location)
            tap("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft")
            field("Geprüfte Lagerbedingungen und Platz beschreiben", "Zwei geschlossene Behälter und geeigneter Lagerplatz geprüft")
            tap("Fertigportionen für diesen Test ausdrücklich freigeben")
            tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen")
            val newest = store.receive("newest", BatchInput(version, location, 20_000L,
                day.toString(), useBy = day.toString(), release = ReleaseStatus.RELEASED), true)
            rule.waitUntil(25_000) {
                runCatching { rule.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen")
                    .assertIsOff(); true }.getOrDefault(false)
            }
            val baseline = db.foodDao().operationCount()
            tap("Herstellung buchen")
            rule.waitUntil(25_000) {
                rule.onAllNodesWithText("Tatsächliche Herstellung ausdrücklich bestätigen.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(baseline, db.foodDao().operationCount())
            tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen")
            tap("Herstellung buchen")
            saved()
            val operation = db.foodDao().observeOperations().first().single { it.operation.kind == "PRODUCTION" }.operation
            val output = operation.resultId
            assertEquals(0, db.foodDao().activeConsumptionCount())
            assertTrue(db.foodDao().purchases().isEmpty())
            assertEquals(2_000L, db.foodDao().balance(output))
            assertEquals(0L, db.foodDao().balance(newest))
            assertEquals(0L, db.foodDao().balance(early))
            assertEquals(20_000L, db.foodDao().balance(later))
            val retry = ProductionInput(recipe, 2_000L,
                listOf(Allocation(newest, 20_000L), Allocation(early, 40_000L), Allocation(later, 40_000L)),
                location, day.toString(), true, "Zwei geschlossene Behälter und geeigneter Lagerplatz geprüft", ReleaseStatus.RELEASED)
            assertEquals(output, store.produce(operation.id, retry, true))
            assertEquals(baseline + 1, db.foodDao().operationCount())
            tap("Neuen Vorgang beginnen")
            tap("Fertigportionen separat verzehren")
            tap("Charge")
            rule.onNode(hasText("FEFO Testgericht · $output") and hasAnyAncestor(isPopup())).performClick()
            field("Verzehrmenge in Portion", "1")
            assertEquals(0, db.foodDao().activeConsumptionCount())
            tap("Fertigportionen tatsächlich gegessen · ausdrücklich bestätigen")
            tap("Fertigportionen-Verzehr buchen")
            saved()
            assertEquals(1, db.foodDao().activeConsumptionCount())
            assertEquals(1_000L, db.foodDao().balance(output))
            val totals = store.dayNutrition(day.toString()).totals
            assertEquals(50.0, requireNotNull(totals[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
            assertNull(totals[Nutrient.PROTEIN].knownAmount)
        } finally {
            rule.runOnUiThread { rule.activity.setContent {}; models.clear() }
            db.close()
        }
    }
}
