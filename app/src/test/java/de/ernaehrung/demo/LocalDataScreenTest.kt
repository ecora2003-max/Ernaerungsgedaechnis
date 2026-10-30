package de.ernaehrung.demo

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LocalDataViewModel
import de.ernaehrung.demo.ui.screens.LocalDataContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalDataScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var db: FoodDatabase? = null
    private val models = ViewModelStore()
    @After fun close() { models.clear(); db?.close() }

    @Test fun productFormPersistsUnknownValuesAndPreventsRepeatedSave() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        db = database
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(FoodStore(database)) as T
        })[LocalDataViewModel::class.java]
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model) } }
            }
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("0 gespeicherte Produkte · unabhängig vom Bestand").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Produkt speichern / neue Version").performClick()
        rule.onNodeWithText("Produktname · lokal").performScrollTo().performTextInput("Testtofu")
        rule.onNodeWithText("Änderungsgrund").performScrollTo().performTextInput("Erster Test")
        rule.onNodeWithTag("Produkt lokal speichern").performScrollTo().performClick()
        rule.waitUntil(15_000) {
            // Synchronize the paused Android looper as well as Room's background executor.
            rule.waitForIdle()
            model.saveState.value.message != null
        }
        assertTrue(model.saveState.value.message, model.saveState.value.succeeded)
        rule.onNodeWithTag("Produkt lokal speichern").assertIsNotEnabled()
        runBlocking {
            val rows = database.foodDao().observeCatalog().first()
            assertEquals("Testtofu", rows.single().version.name)
            assertEquals(null, rows.single().version.energyKcal)
            assertEquals(0L, rows.single().stockAmount)
            assertEquals(1, database.foodDao().operationCount())
        }
        rule.onNodeWithTag("Neuen Vorgang beginnen").performScrollTo().performClick()
        rule.onNodeWithTag("Produkt lokal speichern").performScrollTo().assertIsEnabled()
    }

    private fun mount(database: FoodDatabase): LocalDataViewModel {
        db = database
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(FoodStore(database) { 1_800_000_000_000L }) as T
        })[LocalDataViewModel::class.java]
        rule.runOnUiThread { rule.activity.setContent {
            DemoTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model) } }
        } }
        rule.waitUntil(15_000) { rule.waitForIdle(); model.state.value.loading.not() }
        return model
    }
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
    private fun tap(text: String) = rule.onNodeWithText(text, substring = false).performScrollTo().performClick()
    private fun choose(label: String, id: String) {
        rule.onNodeWithTag(label).performScrollTo().performClick()
        rule.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }
    private fun field(label: String, value: String) = rule.onNodeWithText(label, substring = false).performScrollTo().performTextReplacement(value)
    private fun saved(model: LocalDataViewModel) {
        rule.waitUntil(15_000) { rule.waitForIdle(); model.saveState.value.message != null }
        assertTrue(model.saveState.value.message, model.saveState.value.succeeded)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun transferAndItsFullReversalThroughUiPreserveBothSides() {
        val database = database(); db = database
        val ids = runBlocking {
            val store = FoodStore(database)
            val version = store.rememberProduct("p", ProductInput("Testtofu"))
            val a = store.addLocation("a", "Kühlschrank", true); val b = store.addLocation("b", "Vorrat", true)
            val batch = store.receive("buy", BatchInput(version, a, 200_000, "2026-10-30", release = ReleaseStatus.RELEASED), true)
            batch to b
        }
        val model = mount(database)
        tap("Lager"); tap("Umlagern, entsorgen oder zählen")
        choose("Quellcharge", ids.first); choose("Ziellager", ids.second)
        field("Menge für Bestandsvorgang", "75")
        field("Datum des Bestandsvorgangs", "2026-10-30")
        field("Begründung des Bestandsvorgangs", "Aufteilen")
        tap("Diesen tatsächlichen Bestandsvorgang bestätigen"); tap("Bestandsvorgang buchen"); saved(model)
        val transfer = runBlocking {
            val row = database.foodDao().observeOperations().first().single { it.operation.kind == "TRANSFER" }
            assertEquals(125_000L, database.foodDao().balance(ids.first))
            assertEquals(75_000L, database.foodDao().balance(row.operation.resultId))
            row.operation
        }
        tap("Neuen Vorgang beginnen"); tap("Chronik")
        rule.onNodeWithTag("local_section_Chronik").assertIsSelected()
        tap("Vorgang prüfen und stornieren")
        choose("Ursprungsvorgang · letzte 30", transfer.id)
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Vorgesehene Gegenbuchungen").fetchSemanticsNodes().isNotEmpty() }
        field("Stornodatum JJJJ-MM-TT", "2026-10-30"); field("Stornogrund", "Falsches Ziellager")
        tap("Diese vollständige Gegenbuchung ausdrücklich bestätigen"); tap("Vorgang stornieren"); saved(model)
        runBlocking {
            assertEquals(200_000L, database.foodDao().balance(ids.first))
            assertEquals(0L, database.foodDao().balance(transfer.resultId))
            assertEquals(2, database.foodDao().movements(transfer.id).size)
            assertEquals(2, database.foodDao().movements(database.foodDao().reversal(transfer.id)!!.reversalOperationId).size)
        }
        capture("local-storno-small")
    }

    @Test fun actualCookingFormPersistsIngredientOutflowAndSixPortionsWithoutEating() {
        val database = database(); db = database
        val ids = runBlocking {
            val store = FoodStore(database)
            val version = store.rememberProduct("p", ProductInput("Testlinsen"))
            val location = store.addLocation("loc", "Kühlschrank", true)
            val batch = store.receive("buy", BatchInput(version, location, 300_000, "2026-10-30", release = ReleaseStatus.RELEASED), true)
            val recipe = store.saveRecipe("recipe", RecipeInput("Linsentopf", 6000, listOf(RecipeItemInput(version, 300_000)), "Garen"))
            Triple(recipe, batch, location)
        }
        val model = mount(database)
        tap("Küche"); tap("Tatsächliche Herstellung buchen")
        choose("Rezept für Herstellung", ids.first); choose("Zutatencharge 1", ids.second)
        field("Tatsächliche Zutatmenge 1 in g", "300"); field("Tatsächlich hergestellte Portionen", "6")
        field("Herstellungsdatum JJJJ-MM-TT", "2026-10-30"); choose("Fertigportionen lagern in", ids.third)
        tap("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft")
        field("Geprüfte Lagerbedingungen und Platz beschreiben", "Platz für sechs geschlossene Behälter und Kühlbedingungen geprüft")
        tap("Fertigportionen für diesen Test ausdrücklich freigeben")
        tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen"); tap("Herstellung buchen"); saved(model)
        runBlocking {
            val operation = database.foodDao().observeOperations().first().single { it.operation.kind == "PRODUCTION" }.operation
            assertEquals(0L, database.foodDao().balance(ids.second))
            assertEquals(6000L, database.foodDao().balance(operation.resultId))
            assertEquals(0, database.foodDao().activeConsumptionCount())
            assertEquals(ids.first, database.foodDao().production(operation.id)!!.recipeVersionId)
        }
        rule.onNodeWithTag("Herstellung buchen").assertIsNotEnabled()
        capture("local-production")
    }

    private fun capture(name: String) = rule.runOnUiThread {
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = java.io.File("build/reports/demo-screenshots/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
