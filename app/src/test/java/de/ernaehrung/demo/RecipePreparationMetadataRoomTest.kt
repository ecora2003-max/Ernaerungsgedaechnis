package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.backup.*
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipePreparationMetadataRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        store = FoodStore(db)
    }
    @After fun close() = db.close()
    private suspend fun input(): RecipeInput {
        val version = store.rememberProduct("ingredient",ProductInput("Synthetische Metadatenzutat",energyKcal = 100.0))
        return RecipeInput("Synthetisches Metadatenrezept",2000,listOf(RecipeItemInput(version,200_000L)),"Garen")
    }
    private suspend fun reject(action: suspend () -> Unit) {
        try { action(); fail("Expected rejection without partial recipe writes") } catch (_: IllegalArgumentException) { }
    }

    @Test fun correctionsKeepOriginalUnknownAndZeroTimesAndExactGuidanceWithHistoricalRetries() = runBlocking {
        val original = input()
        val old = store.saveRecipe("old",original)
        val identity = requireNotNull(db.foodDao().recipeVersion(old)).recipeId
        val instructions = "  Eigene Hinweise\nBedingungen vor Ort prüfen.  "
        val source = "Eigene Notiz vom 07.11.; keine automatische Haltbarkeitsfreigabe"
        val changed = original.copy(activeMinutes = 0,totalMinutes = 20,storageInstructions = instructions,safetySource = source)
        val current = store.saveRecipe("correct",changed,identity,"Eigene Zeiten und Quellen ergänzt",expectedVersionId = old)
        val value = requireNotNull(db.foodDao().recipeVersion(current))
        assertEquals(0,value.activeMinutes); assertEquals(20,value.totalMinutes)
        assertEquals(instructions,value.storageInstructions); assertEquals(source,value.safetySource)
        assertNull(db.foodDao().recipeVersion(old)!!.activeMinutes); assertNull(db.foodDao().recipeVersion(old)!!.totalMinutes)
        assertNull(db.foodDao().recipeVersion(old)!!.storageInstructions)
        reject { store.saveRecipe("correct",changed.copy(activeMinutes = 1),identity,"Eigene Zeiten und Quellen ergänzt",expectedVersionId = old) }
        val latest = store.saveRecipe("clear",original,identity,"Ungeklärte Angaben bewusst zurückgenommen",expectedVersionId = current)
        assertEquals(current,store.saveRecipe("correct",changed,identity,"Eigene Zeiten und Quellen ergänzt",expectedVersionId = old))
        assertEquals(latest,db.foodDao().latestRecipeVersion(identity)!!.id)
        assertNull(db.foodDao().recipeVersion(latest)!!.activeMinutes); assertNull(db.foodDao().recipeVersion(latest)!!.safetySource)
        assertEquals(value,db.foodDao().recipeVersion(current))
        assertEquals(db.foodDao().ingredients(old).map { it.productVersionId to it.amount },
            db.foodDao().ingredients(latest).map { it.productVersionId to it.amount })
        assertTrue(db.foodDao().planningBatches().isEmpty()); assertEquals(0,db.foodDao().activeConsumptionCount())
    }

    @Test fun contradictoryOrNegativeTimesLeaveNoPartialVersionAndTheUnusedKeyCanBeRetried() = runBlocking {
        val original = input(); val old = store.saveRecipe("old",original)
        val identity = db.foodDao().recipeVersion(old)!!.recipeId
        val before = db.foodDao().operationCount()
        for (bad in listOf(original.copy(activeMinutes = -1),original.copy(totalMinutes = -1),original.copy(activeMinutes = 21,totalMinutes = 20))) {
            reject { store.saveRecipe("same-key",bad,identity,"Ungültige Zeitangabe",expectedVersionId = old) }
            assertEquals(before,db.foodDao().operationCount()); assertEquals(old,db.foodDao().latestRecipeVersion(identity)!!.id)
            assertNull(db.foodDao().operation("same-key"))
        }
        val current = store.saveRecipe("same-key",original.copy(totalMinutes = 0),identity,"Geklärte Null, aktive Zeit unbekannt",expectedVersionId = old)
        assertNull(db.foodDao().recipeVersion(current)!!.activeMinutes); assertEquals(0,db.foodDao().recipeVersion(current)!!.totalMinutes)
        assertEquals(before + 1,db.foodDao().operationCount())
    }

    @Test fun existingFoodBackupPreservesAllMetadataAndOriginalVersionIdsWithoutSchemaChanges() = runBlocking {
        val original = input()
        val old = store.saveRecipe("old",original)
        val identity = db.foodDao().recipeVersion(old)!!.recipeId
        val current = store.saveRecipe("current",original.copy(activeMinutes = 0,totalMinutes = 30,
            storageInstructions = "Originale mehrzeilige Hinweise\nkeine Ist-Freigabe",safetySource = "Eigene geprüfte Quellenangabe"),
            identity,"Angaben präzisiert",expectedVersionId = old)
        val app = "de.ernaehrung.demo"
        val archive = store.captureBackup(app,false,true)
        val digest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(archive))
        val target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FoodDatabase::class.java).build()
        try {
            val destination = FoodStore(target)
            assertEquals(BackupRestoreStatus.RESTORED,destination.restoreBackup("restore",archive,digest,app,true).status)
            for (id in listOf(old,current)) assertEquals(db.foodDao().recipeVersion(id),target.foodDao().recipeVersion(id))
            assertEquals(BackupRestoreStatus.ALREADY_PRESENT,destination.restoreBackup("retry",archive,digest,app,true).status)
            assertTrue(target.foodDao().planningBatches().isEmpty()); assertEquals(0,target.foodDao().activeConsumptionCount())
            target.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
        } finally { target.close() }
    }
}
