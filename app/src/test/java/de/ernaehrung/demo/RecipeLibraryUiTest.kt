package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.first
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
class RecipeLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun choose(label: String,id: String) {
        compose.onNodeWithTag(label).performScrollTo().performClick()
        compose.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }
    @Test fun currentRecipeSearchFiltersLiveStockAndHandsOffWithoutCookingWhileStaleEditsRequireExplicitReload() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val token = UUID.randomUUID().toString()
        val day = AppCalendar.today().toString()
        val version = store.rememberProduct("$token-p",ProductInput("Suchzutat $token"))
        val place = store.addLocation("$token-loc","Rezeptsuche $token",true)
        val batch = store.receive("$token-stock",BatchInput(version,place,300_000L,day,useBy = AppCalendar.today().plusDays(2).toString(),
            release = ReleaseStatus.RELEASED),true)
        val input = RecipeInput("Suchgericht $token",2000,listOf(RecipeItemInput(version,200_000L)),"Garen",mealPrep = true)
        val old = store.saveRecipe("$token-r1",input)
        val identity = db.foodDao().recipeVersion(old)!!.recipeId
        val current = store.saveRecipe("$token-r2",input.copy(name = "Aktuelles Suchgericht $token"),identity,"Geprüfte neue Fassung",expectedVersionId = old)
        val unknown = store.saveRecipe("$token-unknown",input.copy(name = "Ungeklärtes Suchgericht $token",
            ingredients = listOf(RecipeItemInput(version,null)),mealPrep = false))
        val before = db.foodDao().operationCount()
        val meals = db.foodDao().activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_MORE").performClick()
        compose.onNodeWithTag("workspace_go_KITCHEN").performScrollTo().performClick()
        waitTag("recipe_library_search")
        compose.onNodeWithTag("recipe_library_search").performScrollTo().performTextReplacement(token)
        compose.onNodeWithTag("recipe_library_count").assertTextEquals("2 von ${db.foodDao().observeRecipes().first().size} Rezepten")
        compose.onNodeWithTag("recipe_library_$old").assertDoesNotExist()
        compose.onNodeWithTag("recipe_library_$current").assertExists()
        compose.onNodeWithTag("recipe_library_$unknown").assertExists()
        tap("Nur mit vollständig vorhandenen Zutaten")
        compose.onNodeWithTag("recipe_library_$unknown").assertDoesNotExist()
        compose.onNodeWithTag("recipe_library_prepare_$current").performScrollTo().performClick()
        waitTag("Rezept für Herstellung")
        compose.onNodeWithTag("Rezept für Herstellung").assertTextContains("Aktuelles Suchgericht",substring = true)
        compose.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertIsOff()
        assertEquals(before,db.foodDao().operationCount()); assertEquals(300_000L,db.foodDao().balance(batch))
        // A real consumption changes the live library; merely browsing did not.
        store.consume("$token-eat",ConsumptionInput(version,day,"Vorhandenen Reis gegessen",150_000L,listOf(Allocation(batch,150_000L))),true)
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("recipe_library_$current").fetchSemanticsNodes().isEmpty() }
        tap("Nur mit vollständig vorhandenen Zutaten")
        compose.onNodeWithTag("recipe_library_$current").assertExists()
        tap("Rezept speichern / neue Version")
        choose("Rezept bearbeiten",identity)
        compose.onNodeWithTag("recipe_editor_name").performScrollTo().performTextReplacement("Ungespeicherte Bearbeitung")
        val latest = store.saveRecipe("$token-independent",input.copy(name = "Unabhängig geändertes Gericht $token",mealPrep = false),
            identity,"Unabhängige neue Fassung",expectedVersionId = current)
        waitTag("recipe_editor_reload")
        compose.onNodeWithTag("Rezept lokal speichern").assertIsNotEnabled()
        compose.onNodeWithTag("recipe_editor_name").assertTextContains("Ungespeicherte Bearbeitung",substring = true)
        compose.onNodeWithTag("recipe_editor_reload").performScrollTo().performClick()
        compose.onNodeWithTag("recipe_editor_name").assertTextContains("Unabhängig geändertes Gericht",substring = true)
        compose.onNodeWithText("Als Meal-Prep-Rezept kennzeichnen").assertIsOff()
        assertEquals(latest,db.foodDao().latestRecipeVersion(identity)!!.id)
        assertEquals(before + 2,db.foodDao().operationCount()); assertEquals(meals + 1,db.foodDao().activeConsumptionCount())
    }
}
