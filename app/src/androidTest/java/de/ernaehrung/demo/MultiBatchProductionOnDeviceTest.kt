package de.ernaehrung.demo

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.util.UUID
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class MultiBatchProductionOnDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Before fun unlockFixture() = prepareUnlockedTestDevice()
    private fun finishEditing() = finishDeviceTextEditing(rule)
    private fun waitTag(tag: String) = rule.waitUntil(25_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tap(text: String) {
        finishEditing()
        rule.waitUntil(25_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun choose(label: String, id: String) {
        finishEditing()
        waitTag(label)
        rule.onNodeWithTag(label).performScrollTo().performClick()
        waitTag("choose_$id")
        rule.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }
    private fun field(label: String, value: String) =
        rule.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    private fun saved() = rule.waitUntil(25_000) {
        rule.onAllNodesWithText("Neuen Vorgang beginnen").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun fefoRecipeManufacturingRevokesStaleConsentAndEatingRequiresASeparateExplicitEvent() = runBlocking {
        val db = FoodDatabase.localSandbox(rule.activity.applicationContext)
        val fixture = "fefo-production-" + UUID.randomUUID()
        val mealsBefore = db.foodDao().activeConsumptionCount()
        val purchasesBefore = db.foodDao().purchases().size
        val store = FoodStore(db)
        val day = AppCalendar.today()
        val version = store.rememberProduct("$fixture-rice", ProductInput("FEFO Reis",
            source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0))
        val location = store.addLocation("$fixture-pantry", "Vorrat", true)
        val early = store.receive("$fixture-early", BatchInput(version, location, 40_000L,
            day.toString(), useBy = day.plusDays(1).toString(), release = ReleaseStatus.RELEASED), true)
        val later = store.receive("$fixture-later", BatchInput(version, location, 60_000L,
            day.toString(), useBy = day.plusDays(3).toString(), release = ReleaseStatus.RELEASED), true)
        val recipe = store.saveRecipe("$fixture-recipe", RecipeInput("FEFO Testgericht", 2_000L,
            listOf(RecipeItemInput(version, 100_000L)), "Kochen"))
        try {
            rule.onNodeWithTag("workspace_go_KITCHEN").performScrollTo().performClick()
            rule.waitUntil(25_000) {
                rule.onAllNodesWithText("Tatsächliche Herstellung buchen").fetchSemanticsNodes().isNotEmpty()
            }
            tap("Tatsächliche Herstellung buchen")
            choose("Rezept für Herstellung", recipe)
            field("Tatsächlich hergestellte Portionen", "2")
            finishEditing()
            rule.onNodeWithTag("production_source_Chargen nach FEFO vorschlagen").performScrollTo().performClick()
            rule.onNodeWithTag("production_source_Chargen nach FEFO vorschlagen").assertIsSelected()
            // A source-mode click is not yet a rendered, Room-backed proposal.
            // Await its actual allocations; do not retry a save or consent action.
            waitTag("production_fefo_$early")
            waitTag("production_fefo_$later")
            rule.onNodeWithTag("production_fefo_$early").assertTextContains("40 g", substring = true)
            rule.onNodeWithTag("production_fefo_$later").assertTextContains("60 g", substring = true)
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            assertTrue(db.foodDao().observeOperations().first().none { db.foodDao().production(it.operation.id)?.recipeVersionId == recipe })
            choose("Fertigportionen lagern in", location)
            tap("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft")
            field("Geprüfte Lagerbedingungen und Platz beschreiben", "Zwei geschlossene Behälter und geeigneter Lagerplatz geprüft")
            tap("Fertigportionen für diesen Test ausdrücklich freigeben")
            tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen")
            rule.activityRule.scenario.recreate()
            rule.waitUntil(25_000) {
                rule.onAllNodesWithText("Tatsächliche Herstellung buchen").fetchSemanticsNodes().isNotEmpty()
            }
            tap("Tatsächliche Herstellung buchen")
            rule.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertIsOff()
            // A restart never replays physical storage/release approval. Read
            // the retained facts and perform both real, fresh user gestures.
            rule.onNodeWithText("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft").assertIsOff()
            rule.onNodeWithText("Fertigportionen für diesen Test ausdrücklich freigeben").assertIsOff()
            tap("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft")
            tap("Fertigportionen für diesen Test ausdrücklich freigeben")
            tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen")
            val newest = store.receive("$fixture-newest", BatchInput(version, location, 20_000L,
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
            val operation = db.foodDao().observeOperations().first().single { db.foodDao().production(it.operation.id)?.recipeVersionId == recipe }.operation
            val output = operation.resultId
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            assertEquals(purchasesBefore, db.foodDao().purchases().size)
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
            rule.waitUntil(25_000) {
                rule.onAllNodes(hasText("FEFO Testgericht · $output") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().size == 1
            }
            rule.onNode(hasText("FEFO Testgericht · $output") and hasAnyAncestor(isPopup())).performClick()
            field("Verzehrmenge in Portion", "1")
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            tap("Fertigportionen tatsächlich gegessen · ausdrücklich bestätigen")
            tap("Fertigportionen-Verzehr buchen")
            saved()
            assertEquals(mealsBefore + 1, db.foodDao().activeConsumptionCount())
            assertEquals(1_000L, db.foodDao().balance(output))
            val totals = store.dayNutrition(day.toString()).meals.single {
                it.event.productVersionId == requireNotNull(db.foodDao().batch(output)).versionId
            }.nutrition
            assertEquals(50.0, requireNotNull(totals[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
            assertNull(totals[Nutrient.PROTEIN].knownAmount)
            rule.activityRule.scenario.recreate()
            rule.onNodeWithTag("workspace_page_KITCHEN").assertExists()
            assertEquals(mealsBefore + 1, db.foodDao().activeConsumptionCount())
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("production-fefo-flow")
            throw failure
        }
    }
}
