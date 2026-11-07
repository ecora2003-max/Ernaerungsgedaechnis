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
@Config(sdk = [34],qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActualEventApprovalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) { waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun text(value: String) = compose.onNodeWithText(value).performScrollTo().performClick()
    private fun field(label: String,value: String) = compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)

    @Test fun manualInboundRetainsItsFieldsButCannotRestoreTheActualReceiptAndReleaseApprovals() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val date = AppCalendar.today().toString()
        val name = "Zugangsware $token"; val locationName = "Vorrat $token"
        val version = store.rememberProduct("$token-product",ProductInput(name))
        val location = store.addLocation("$token-location",locationName,true)
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_STOCK").performClick(); waitTag("stock_overview_search")
        text("Testzugang bestätigen"); text("Produktversion"); text("$name · $version")
        text("Lagerort"); text("$locationName · $location")
        field("Zugangsmenge in g","100"); field("Zugangsdatum JJJJ-MM-TT · optional",date)
        val release = "Charge für diesen Test ausdrücklich freigeben"
        val approval = "Diesen Testzugang buchen · keine Ausgabe erfassen"
        text(release); text(approval)
        compose.activityRule.scenario.recreate(); waitTag("stock_overview_search"); text("Testzugang bestätigen")
        compose.onNode(hasText("Zugangsmenge in g") and hasSetTextAction()).assertTextContains("100")
        compose.onNodeWithText(release).assertIsOff(); compose.onNodeWithText(approval).assertIsOff()
        tag("Testzugang buchen")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Nicht gespeichert.",substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before,dao.operationCount()); assertTrue(dao.planningBatches().none { it.batch.versionId == version })
        text(release); text(approval); tag("Testzugang buchen")
        compose.waitUntil(25_000) { runBlocking { dao.planningBatches().any { it.batch.versionId == version } } }
        assertEquals(before + 1,dao.operationCount()); assertEquals(meals,dao.activeConsumptionCount())
        val receipt = dao.planningBatches().single { it.batch.versionId == version }
        assertEquals(100_000L,receipt.stockAmount); assertEquals(ReleaseStatus.RELEASED,receipt.batch.release)
        tag("Neuen Vorgang beginnen")
        compose.onNodeWithText(approval).assertIsOff(); compose.onNodeWithText(release).assertIsOff()
        tag("Testzugang buchen")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Nicht gespeichert.",substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before + 1,dao.operationCount())
    }

    @Test fun aNewManualManufacturingOperationRequiresFreshProductionStorageAndReleaseGestures() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val date = AppCalendar.today().toString()
        val version = store.rememberProduct("$token-product",ProductInput("Kochzutat $token",energyKcal = 100.0))
        val location = store.addLocation("$token-location","Kühlraum $token",true)
        val batch = store.receive("$token-received",BatchInput(version,location,300_000L,date,release = ReleaseStatus.RELEASED),true)
        val recipe = store.saveRecipe("$token-recipe",RecipeInput("Kochfassung $token",1_000L,listOf(RecipeItemInput(version,100_000L)),"Garen"))
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_KITCHEN")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Tatsächliche Herstellung buchen").fetchSemanticsNodes().size == 1 }
        text("Tatsächliche Herstellung buchen"); tag("Rezept für Herstellung"); tag("choose_$recipe")
        tag("Zutatencharge 1"); tag("choose_$batch")
        field("Tatsächliche Zutatmenge 1 in g","100"); field("Tatsächlich hergestellte Portionen","1")
        field("Herstellungsdatum JJJJ-MM-TT",date); tag("Fertigportionen lagern in"); tag("choose_$location")
        val storage = "Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft"
        val release = "Fertigportionen für diesen Test ausdrücklich freigeben"
        val approval = "Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen"
        text(storage); field("Geprüfte Lagerbedingungen und Platz beschreiben","Passender Behälter und Kühlplatz geprüft")
        text(release); text(approval); tag("Herstellung buchen")
        compose.waitUntil(25_000) { runBlocking { dao.balance(batch) == 200_000L } }
        assertEquals(before + 1,dao.operationCount()); assertEquals(meals,dao.activeConsumptionCount())
        tag("Neuen Vorgang beginnen")
        compose.onNodeWithText(approval).assertIsOff(); compose.onNodeWithText(storage).assertIsOff(); compose.onNodeWithText(release).assertIsOff()
        compose.onNode(hasText("Tatsächlich hergestellte Portionen") and hasSetTextAction()).assertTextContains("1")
        tag("Herstellung buchen")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Nicht gespeichert.",substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before + 1,dao.operationCount()); assertEquals(200_000L,dao.balance(batch))
        assertEquals(1,dao.planningBatches().count { it.batch.recipeVersionId == recipe })
        text(storage); text(release); text(approval); tag("Herstellung buchen")
        compose.waitUntil(25_000) { runBlocking { dao.balance(batch) == 100_000L } }
        assertEquals(before + 2,dao.operationCount()); assertEquals(meals,dao.activeConsumptionCount())
        assertEquals(2,dao.planningBatches().count { it.batch.recipeVersionId == recipe })
    }
}
