package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeLibraryRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        store = FoodStore(db)
    }
    @After fun close() = db.close()
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected atomic recipe rejection") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun input(): RecipeInput {
        val rice = store.rememberProduct("rice",ProductInput("Synthetischer Reis"))
        return RecipeInput("Reistopf",2000,listOf(RecipeItemInput(rice,200_000)),"Garen")
    }

    @Test fun legacyHashesAndRetriesStayExactWhileExplicitMealPrepFlagIsVersionedAndCannotReuseAnotherPayload() = runBlocking {
        val old = input()
        val original = store.saveRecipe("legacy",old)
        val expectedHash = WriteRules.fingerprint("RECIPE",null,"Erste Fassung",old.name,old.yieldPortions,old.steps,
            old.storageInstructions,old.safetySource,old.ingredients.map { listOf(it.versionId,it.amount,it.note) })
        assertEquals(expectedHash,db.foodDao().operation("legacy")!!.fingerprint)
        assertEquals(original,store.saveRecipe("legacy",old))
        assertTrue(db.foodDao().recipeVersion(original)!!.mealPrep)
        val manual = store.saveRecipe("ordinary",old.copy(mealPrep = false))
        assertFalse(db.foodDao().recipeVersion(manual)!!.mealPrep)
        val before = db.foodDao().operationCount()
        reject { store.saveRecipe("ordinary",old.copy(mealPrep = true)) }
        assertEquals(before,db.foodDao().operationCount())
        assertTrue(db.foodDao().planningBatches().isEmpty()); assertEquals(0,db.foodDao().activeConsumptionCount())
    }

    @Test fun exactLoadedVersionProtectsEditsAndSuccessfulRetryReturnsItsOwnHistoricalResultAfterFurtherChanges() = runBlocking {
        val source = input().copy(mealPrep = false)
        val v1 = store.saveRecipe("v1",source)
        val identity = db.foodDao().recipeVersion(v1)!!.recipeId
        val correction = source.copy(name = "Aktueller Topf",mealPrep = true)
        val v2 = store.saveRecipe("v2",correction,identity,"Erster bewusster Ersatz",expectedVersionId = v1)
        val count = db.foodDao().operationCount()
        reject { store.saveRecipe("stale",source,identity,"Veraltetes Formular",expectedVersionId = v1) }
        assertNull(db.foodDao().operation("stale")); assertEquals(count,db.foodDao().operationCount())
        assertEquals(v2,db.foodDao().latestRecipeVersion(identity)!!.id)
        val v3 = store.saveRecipe("v3",source,identity,"Neue bewusste Fassung",expectedVersionId = v2)
        assertEquals(v2,store.saveRecipe("v2",correction,identity,"Erster bewusster Ersatz",expectedVersionId = v1))
        assertEquals(v3,db.foodDao().latestRecipeVersion(identity)!!.id)
        assertFalse(db.foodDao().recipeVersion(v1)!!.mealPrep); assertTrue(db.foodDao().recipeVersion(v2)!!.mealPrep)
        assertEquals(db.foodDao().ingredients(v1).map { it.productVersionId to it.amount },
            db.foodDao().ingredients(v3).map { it.productVersionId to it.amount })
        reject { store.saveRecipe("invalid",source,expectedVersionId = v3) }
        assertNull(db.foodDao().operation("invalid"))
        assertTrue(db.foodDao().planningBatches().isEmpty()); assertEquals(0,db.foodDao().activeConsumptionCount())
    }
}
