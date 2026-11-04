package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unsaved version-bound recipe preview uses the real Room nutrition resolver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipePreviewNutritionTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            FoodDatabase::class.java).build()
        store = FoodStore(db)
    }
    @After fun close() = db.close()

    @Test fun gramAndMillilitrePreviewKeepsMissingValuesAndKnownZeroWithoutWritingAnything() = runBlocking {
        val rice = store.rememberProduct("preview-rice", ProductInput("Reis",
            energyKcal = 350.0, proteinGrams = 7.0, fatGrams = 0.0,
            source = Source.USER, quality = Quality.CONFIRMED))
        val drink = store.rememberProduct("preview-drink", ProductInput("Pflanzendrink",
            unit = BaseUnit.MILLILITRE, energyKcal = 20.0, fatGrams = 0.0,
            source = Source.USER, quality = Quality.CONFIRMED))
        val before = db.foodDao().operationCount()
        val result = store.previewRecipeNutrition(RecipeInput("Noch nicht gespeicherter Entwurf", 2_000L,
            listOf(RecipeItemInput(rice, 150_000L), RecipeItemInput(drink, 200_000L)), "Vorbereiten"))
        assertEquals(282.5, requireNotNull(result[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
        assertTrue(result[Nutrient.ENERGY].complete)
        assertEquals(5.25, requireNotNull(result[Nutrient.PROTEIN].knownAmount).toDouble(), 0.0001)
        assertEquals(1, result[Nutrient.PROTEIN].missingCount)
        assertFalse(result[Nutrient.PROTEIN].complete)
        assertEquals(0, requireNotNull(result[Nutrient.FAT].knownAmount).signum())
        assertTrue(result[Nutrient.FAT].complete)
        assertNull(result[Nutrient.B12].knownAmount)
        assertEquals(2, result[Nutrient.B12].missingCount)
        assertEquals(before, db.foodDao().operationCount())
        assertTrue(db.foodDao().observeRecipes().first().isEmpty())
        assertTrue(db.foodDao().planningBatches().isEmpty())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun previewDoesNotReplaceItsHistoricalVersionWithANewerCatalogValue() = runBlocking {
        val old = store.rememberProduct("preview-v1", ProductInput("Reis",
            energyKcal = 350.0, source = Source.USER, quality = Quality.CONFIRMED))
        val product = requireNotNull(db.foodDao().version(old)).productId
        val latest = store.rememberProduct("preview-v2", ProductInput("Reis korrigiert",
            energyKcal = 400.0, source = Source.USER, quality = Quality.CONFIRMED), product, "Etikett erneut geprüft")
        val before = db.foodDao().operationCount()
        suspend fun preview(id: String) = store.previewRecipeNutrition(RecipeInput("Versionsvorschau", 1_000L,
            listOf(RecipeItemInput(id, 100_000L)), "Vorbereiten"))
        assertEquals(350.0, requireNotNull(preview(old)[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
        assertEquals(400.0, requireNotNull(preview(latest)[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
        assertNull(preview("missing-version")[Nutrient.ENERGY].knownAmount)
        assertEquals(before, db.foodDao().operationCount())
        assertTrue(db.foodDao().observeRecipes().first().isEmpty())
    }
}
