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
class RecipeProductionIntentUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun fold() = compose.onNodeWithText("Tatsächliche Herstellung buchen").performScrollTo().performClick()
    @Test fun consumedRecipeSelectionCannotReopenOrOverwriteTheFormAfterRestartAndANewExplicitSelectionStillOpensFreshly() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val token = UUID.randomUUID().toString()
        val product = store.rememberProduct("$token-product",ProductInput("Synthetische Intentzutat $token"))
        val recipe = store.saveRecipe("$token-recipe",RecipeInput("Synthetisches Intentrezept $token",2000,
            listOf(RecipeItemInput(product,200_000L)),"Garen"))
        val before = db.foodDao().operationCount()
        val batches = db.foodDao().planningBatches()
        val meals = db.foodDao().activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_KITCHEN")
        waitTag("recipe_library_prepare_$recipe"); tag("recipe_library_prepare_$recipe")
        waitTag("production_portions_input")
        compose.onNodeWithTag("production_portions_input",useUnmergedTree = true).performScrollTo().performTextReplacement("3")
        compose.onNodeWithTag("production_storage_note_input",useUnmergedTree = true).performScrollTo().performTextReplacement("Noch ungespeicherte eigene Notiz")
        compose.onNodeWithText("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft").performScrollTo().performClick()
        fold()
        compose.onNodeWithTag("production_portions_input").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitTag("workspace_page_KITCHEN"); waitTag("recipe_library_prepare_$recipe")
        compose.onNodeWithTag("production_portions_input").assertDoesNotExist()
        fold(); waitTag("production_portions_input")
        compose.onNodeWithTag("production_portions_input",useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("3")))
        compose.onNodeWithTag("production_storage_note_input",useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Noch ungespeicherte eigene Notiz")))
        compose.onNodeWithText("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft").assertIsOff()
        compose.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertIsOff()
        fold(); tag("recipe_library_prepare_$recipe"); waitTag("production_portions_input")
        compose.onNodeWithTag("production_portions_input",useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertIsOff()
        assertEquals(before,db.foodDao().operationCount()); assertEquals(batches,db.foodDao().planningBatches())
        assertEquals(meals,db.foodDao().activeConsumptionCount())
    }
}
