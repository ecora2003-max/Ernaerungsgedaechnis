package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Actual default library, Room, IME, handoff, Activity recreation and independent SQLite reopen. */
@RunWith(AndroidJUnit4::class)
class RecipeLibraryOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) {
        finishDeviceTextEditing(compose)
        waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun text(text: String) {
        finishDeviceTextEditing(compose)
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    @Test fun actualLibrarySearchAndFiltersPreserveVersionsAndOnlyPrepareAnUnconfirmedProductionAcrossRestart() = runBlocking {
        assertTrue("Synthetic library only on disposable emulator",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = compose.activity.applicationContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Require actual network absence",connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val store = FoodStore(db)
        val token = UUID.randomUUID().toString()
        val today = AppCalendar.today()
        val version = store.rememberProduct("$token-p",ProductInput("Gerätereiszutat $token",energyKcal = 100.0))
        val place = store.addLocation("$token-place","Geräterezeptlager $token",true)
        val batch = store.receive("$token-stock",BatchInput(version,place,300_000L,today.toString(),useBy = today.plusDays(2).toString(),
            release = ReleaseStatus.RELEASED),true)
        val input = RecipeInput("Geräte-Reistopf $token",2000,listOf(RecipeItemInput(version,200_000L)),"Garen",mealPrep = false)
        val old = store.saveRecipe("$token-r1",input)
        val identity = db.foodDao().recipeVersion(old)!!.recipeId
        val current = store.saveRecipe("$token-r2",input.copy(name = "Aktueller Geräte-Reistopf $token",mealPrep = true),
            identity,"Fassung vor Ort geprüft",expectedVersionId = old)
        val unknown = store.saveRecipe("$token-unknown",input.copy(name = "Ungeklärter Geräte-Reistopf $token",
            ingredients = listOf(RecipeItemInput(version,null))))
        val before = db.foodDao().operationCount()
        val batches = db.foodDao().planningBatches()
        val meals = db.foodDao().activeConsumptionCount()
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_KITCHEN")
            waitTag("recipe_library_search")
            compose.onNodeWithTag("recipe_library_search").performScrollTo().performTextReplacement(token)
            finishDeviceTextEditing(compose)
            compose.onNodeWithTag("recipe_library_$old").assertDoesNotExist()
            compose.onNodeWithTag("recipe_library_$current").assertExists()
            compose.onNodeWithTag("recipe_library_$unknown").assertExists()
            val favoriteTag = "recipe_favorite_$current"
            compose.waitUntil(25_000) { runCatching { compose.onNodeWithTag(favoriteTag).assertIsEnabled(); true }.getOrDefault(false) }
            tag(favoriteTag)
            compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag(favoriteTag) and hasText("Favorit entfernen")).fetchSemanticsNodes().isNotEmpty() }
            text("Nur eigene Favoriten")
            compose.onNodeWithTag("recipe_library_$unknown").assertDoesNotExist()
            text("Nur eigene Favoriten")
            assertEquals(1,store.readRecipeFavorites().history.size)
            text("Nur mit vollständig vorhandenen Zutaten")
            compose.onNodeWithTag("recipe_library_$unknown").assertDoesNotExist()
            text("Nur als Meal Prep gekennzeichnete Rezepte")
            tag("recipe_library_prepare_$current")
            waitTag("Rezept für Herstellung")
            compose.onNodeWithTag("Rezept für Herstellung").performScrollTo().assertTextContains("Aktueller Geräte-Reistopf",substring = true)
            compose.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertIsOff()
            assertEquals(before,db.foodDao().operationCount()); assertEquals(batches,db.foodDao().planningBatches())
            compose.activityRule.scenario.recreate()
            waitTag("workspace_page_KITCHEN"); waitTag("recipe_library_search")
            // Only committed library data returns; the search and confirmations are not restored.
            compose.onNodeWithTag("recipe_library_search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
            compose.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertDoesNotExist()
            compose.onNodeWithTag("recipe_library_search").performScrollTo().performTextReplacement("Gerätereiszutat $token")
            finishDeviceTextEditing(compose)
            compose.onNodeWithTag("recipe_library_$current").assertExists()
            compose.onNodeWithTag("recipe_library_$unknown").assertExists()
            compose.onNodeWithTag(favoriteTag).assertTextContains("Favorit entfernen",substring = true)
            tag(favoriteTag)
            compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag(favoriteTag) and hasText("Als Favorit merken")).fetchSemanticsNodes().isNotEmpty() }
            tag("recipe_library_prepare_$current")
            compose.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertIsOff()
            tag("recipe_library_plan_$current")
            waitTag("workspace_page_PLAN"); waitTag("recipe_plan_prefill_notice")
            compose.onNodeWithTag("Art des Planeintrags").performScrollTo().assertTextContains("Zubereitung",substring = true)
            compose.onNodeWithTag("Planbezug").assertTextContains("Aktueller Geräte-Reistopf",substring = true)
            assertTrue(db.foodDao().allPlannedEntries().none { it.recipeVersionId == current })
            assertEquals(before,db.foodDao().operationCount())
            compose.onNodeWithTag("plan_entry_date_input",useUnmergedTree = true).performScrollTo().performTextReplacement(today.plusDays(1).toString())
            compose.onNodeWithTag("plan_entry_amount_input",useUnmergedTree = true).performScrollTo().performTextReplacement("4")
            tag("Zum Planentwurf hinzufügen")
            assertTrue(db.foodDao().allPlannedEntries().none { it.recipeVersionId == current })
            compose.onNodeWithTag("plan_change_reason_input",useUnmergedTree = true).performScrollTo().performTextReplacement("Eigenen Kochtermin ausdrücklich geplant")
            tag("Planversion lokal speichern")
            compose.waitUntil(25_000) { runBlocking { db.foodDao().allPlannedEntries().any { it.recipeVersionId == current } } }
            val planned = db.foodDao().allPlannedEntries().single { it.recipeVersionId == current }
            assertEquals(PlanKind.PREPARATION,planned.kind); assertEquals(4000L,planned.amount)
            waitTag("shopping_$version")
            compose.onNodeWithTag("shopping_$version").performScrollTo().assertTextContains("100 g",substring = true)
            assertEquals(before + 1,db.foodDao().operationCount())
            assertEquals(batches,db.foodDao().planningBatches()); assertEquals(meals,db.foodDao().activeConsumptionCount())
            compose.activityRule.scenario.recreate(); waitTag("workspace_page_PLAN")
            val reopened = Room.databaseBuilder(target,FoodDatabase::class.java,FoodDatabase.TEST_DATABASE_NAME).build()
            try {
                assertEquals(current,reopened.foodDao().latestRecipeVersion(identity)!!.id)
                assertFalse(reopened.foodDao().recipeVersion(old)!!.mealPrep)
                assertTrue(reopened.foodDao().recipeVersion(current)!!.mealPrep)
                assertNull(reopened.foodDao().ingredients(unknown).single().amount)
                assertEquals(300_000L,reopened.foodDao().balance(batch))
                val reopenedFavorites = FoodStore(reopened).readRecipeFavorites()
                assertEquals(2,reopenedFavorites.history.size)
                assertEquals("REMOVED",reopenedFavorites.latest.getValue(identity).status)
                assertEquals(planned,reopened.foodDao().allPlannedEntries().single { it.recipeVersionId == current })
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
            } finally { reopened.close() }
            assertEquals(before + 1,db.foodDao().operationCount()); assertEquals(batches,db.foodDao().planningBatches())
            assertEquals(meals,db.foodDao().activeConsumptionCount()); assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("recipe-library-flow"); throw failure }
    }
}
