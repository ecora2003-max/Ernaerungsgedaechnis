package de.ernaehrung.demo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
class RecipePreparationMetadataUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun text(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun input(tag: String,text: String) = compose.onNodeWithTag(tag,useUnmergedTree = true).performScrollTo().performTextReplacement(text)
    @Test fun exactRecipeMetadataCanBeCorrectedSurvivesAnUnconfirmedRestartAndIsReadableBeforeProduction() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString()
        val product = store.rememberProduct("$token-product",ProductInput("Hinweiszutat $token"))
        val name = "Hinweisrezept $token"
        val old = store.saveRecipe("$token-old",RecipeInput(name,2000,listOf(RecipeItemInput(product,200_000)),"Garen"))
        val identity = dao.recipeVersion(old)!!.recipeId
        val before = dao.operationCount(); val batches = dao.planningBatches(); val meals = dao.activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_KITCHEN")
        waitTag("recipe_library_search"); text("Rezept speichern / neue Version")
        tag("Rezept bearbeiten"); waitTag("choose_$identity"); tag("choose_$identity")
        compose.onNodeWithTag("recipe_editor_active_minutes",useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        input("recipe_editor_active_minutes","21"); input("recipe_editor_total_minutes","20")
        compose.onNodeWithText("Änderungsgrund des Rezepts").performScrollTo().performTextReplacement("Eigene Hinweise ergänzt")
        tag("Rezept lokal speichern")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Gesamte Zubereitungszeit muss mindestens",substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before,dao.operationCount())
        input("recipe_editor_active_minutes","0")
        input("recipe_editor_storage","  Eigene Hinweise\nVor Ort prüfen.  ")
        input("recipe_editor_safety_source","Eigene Quellenangabe $token")
        compose.activityRule.scenario.recreate(); waitTag("workspace_page_KITCHEN"); waitTag("recipe_library_search")
        text("Rezept speichern / neue Version")
        compose.onNodeWithTag("recipe_editor_active_minutes",useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("0")))
        assertEquals(before,dao.operationCount())
        tag("Rezept lokal speichern")
        compose.waitUntil(25_000) { runBlocking { dao.latestRecipeVersion(identity)?.id != old } }
        val current = dao.latestRecipeVersion(identity)!!
        assertEquals(0,current.activeMinutes); assertEquals(20,current.totalMinutes)
        assertEquals("  Eigene Hinweise\nVor Ort prüfen.  ",current.storageInstructions)
        assertEquals("Eigene Quellenangabe $token",current.safetySource)
        assertNull(dao.recipeVersion(old)!!.activeMinutes)
        text("Rezept speichern / neue Version")
        text("Zeiten und Aufbewahrung · $name")
        compose.onNodeWithTag("recipe_preparation_library_${current.id}").assertTextContains("Aktive Zeit: 0 min · Gesamtzeit: 20 min",substring = true)
        compose.onNodeWithTag("recipe_safety_source_library_${current.id}").assertTextContains("Eigene Quellenangabe $token",substring = true)
        tag("Neuen Vorgang beginnen"); tag("recipe_library_prepare_${current.id}")
        waitTag("Rezept für Herstellung"); text("Gespeicherte Rezeptangaben vor der Herstellung prüfen")
        compose.onNodeWithTag("recipe_preparation_production_${current.id}").assertTextContains("0 min",substring = true)
        compose.onNodeWithTag("recipe_storage_production_${current.id}").assertTextContains("Vor Ort prüfen.",substring = true)
        assertEquals(before + 1,dao.operationCount()); assertEquals(batches,dao.planningBatches()); assertEquals(meals,dao.activeConsumptionCount())
    }
}
