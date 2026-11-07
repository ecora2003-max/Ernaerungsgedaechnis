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
class RecipeFavoriteRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var target: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var destination: FoodStore
    private val app = "de.ernaehrung.demo"
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context,FoodDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context,FoodDatabase::class.java).build()
        store = FoodStore(db); destination = FoodStore(target)
    }
    @After fun close() { db.close(); target.close() }
    private suspend fun recipe(key: String = "recipe"): Pair<String,RecipeInput> {
        val product = store.rememberProduct("$key-p",ProductInput("Eigene synthetische Zutat $key"))
        val input = RecipeInput("Eigenes Rezept $key",2000,listOf(RecipeItemInput(product,200_000L)),"Garen",mealPrep = false)
        return store.saveRecipe(key,input) to input
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected atomic rejection") } catch (_: IllegalArgumentException) { }
    }
    private fun digest(a: BackupArchive) = BackupArchiveCodec.digest(BackupArchiveCodec.encode(a))
    private suspend fun restore(a: BackupArchive,key: String = "restore") = destination.restoreBackup(key,a,digest(a),app,true)

    @Test fun favoritesFollowRecipeIdentityButEveryNewActionBindsCurrentVersionAndPreferenceWithReplayNeverUndoingNewerChoices() = runBlocking {
        val (v1,input) = recipe()
        val identity = db.foodDao().recipeVersion(v1)!!.recipeId
        val first = RecipeFavoriteInput(v1,null,true)
        val before = db.foodDao().operationCount()
        reject { store.setRecipeFavorite("unconfirmed",first,false) }
        assertTrue(store.readRecipeFavorites().history.isEmpty())
        assertEquals("favorite",store.setRecipeFavorite("favorite",first,true))
        val v2 = store.saveRecipe("new-version",input.copy(name = "Neue genaue Rezeptfassung"),identity,"Bewusst überarbeitet",expectedVersionId = v1)
        assertEquals(setOf(identity),store.readRecipeFavorites().recipeIds)
        assertEquals("favorite",store.setRecipeFavorite("favorite",first,true))
        reject { store.setRecipeFavorite("old-version",first.copy(previousId = "favorite"),true) }
        reject { store.setRecipeFavorite("old-preference",RecipeFavoriteInput(v2,null,false),true) }
        val remove = RecipeFavoriteInput(v2,"favorite",false)
        store.setRecipeFavorite("remove",remove,true)
        assertTrue(store.readRecipeFavorites().recipeIds.isEmpty())
        store.setRecipeFavorite("favorite",first,true)
        assertTrue(store.readRecipeFavorites().recipeIds.isEmpty())
        store.setRecipeFavorite("favorite-again",RecipeFavoriteInput(v2,"remove",true),true)
        store.setRecipeFavorite("remove",remove,true)
        assertEquals(setOf(identity),store.readRecipeFavorites().recipeIds)
        assertEquals(3,store.readRecipeFavorites().history.size)
        assertEquals(before + 1,db.foodDao().operationCount())
        assertTrue(db.foodDao().planningBatches().isEmpty()); assertEquals(0,db.foodDao().activeConsumptionCount())
    }

    @Test fun foodBackupPreservesPreferenceHistoryAndReplayCannotRestoreAFormerlySelectedFavorite() = runBlocking {
        val (v1,_) = recipe()
        val identity = db.foodDao().recipeVersion(v1)!!.recipeId
        store.setRecipeFavorite("favorite",RecipeFavoriteInput(v1,null,true),true)
        val archive = store.captureBackup(app,false,true)
        assertEquals(51,archive.tables.size)
        assertEquals(1,archive.tables.single { it.name == "recipe_favorites" }.rows.size)
        assertEquals(BackupRestoreStatus.RESTORED,restore(archive).status)
        assertEquals(store.readRecipeFavorites(),destination.readRecipeFavorites())
        destination.setRecipeFavorite("remove",RecipeFavoriteInput(v1,"favorite",false),true)
        val before = target.foodDao().operationCount()
        assertEquals(BackupRestoreStatus.ALREADY_PRESENT,restore(archive,"another-retry").status)
        assertTrue(destination.readRecipeFavorites().recipeIds.isEmpty())
        assertEquals(before,target.foodDao().operationCount())
        assertEquals("REMOVED",destination.readRecipeFavorites().latest.getValue(identity).status)
    }

    @Test fun pinnedRoom16ArchiveKeepsOriginalDigestPrivateProfileAndReplayWithoutInventingPreferencesOrRevivingErasedData() = runBlocking {
        val (v1,_) = recipe()
        val proposed = store.proposePersonalRestrictions("profile-propose",PersonalRestrictionInput(null,"ALLERGEN:SOY","Eigene synthetische Vorgabe"),true)
        val proposal = requireNotNull(proposed.versionId)
        store.confirmPersonalRestrictions("profile-confirm",proposal,store.readPersonalRestrictions().versionBinding(proposal),true)
        val current = store.captureBackup(app,true,true)
        val old = current.copy(databaseVersion = 16,identityHash = "e2d5d92fe1b98be4adaf09fc57288228",
            tables = current.tables.filterNot { it.name == "recipe_favorites" })
        assertEquals(50,old.tables.size)
        assertTrue(destination.validateBackupForPreview(old,app))
        assertEquals(BackupRestoreStatus.RESTORED,restore(old).status)
        assertEquals(digest(old),target.foodDao().operation("restore")!!.resultId)
        assertEquals(setOf("ALLERGEN:SOY"),destination.readPersonalRestrictions().tokens)
        assertTrue(destination.readRecipeFavorites().history.isEmpty())
        destination.setRecipeFavorite("new-choice",RecipeFavoriteInput(v1,null,true),true)
        destination.erasePersonalRestrictions("erase-profile",destination.readPersonalRestrictions().token,true)
        assertEquals(BackupRestoreStatus.ALREADY_PRESENT,restore(old,"new-restore-key").status)
        assertEquals(1,destination.readRecipeFavorites().history.size)
        assertTrue(destination.readPersonalRestrictions().tokens.isEmpty())
        assertTrue(destination.readPersonalRestrictions().history.all { it.rawText == null })
    }

    @Test fun typedButCrossedRecipeReferenceRollsBackTheEntireRestoreAndTheSameOperationCanThenRestoreTheValidArchive() = runBlocking {
        val (v1,_) = recipe("first")
        val (other,_) = recipe("other")
        store.setRecipeFavorite("favorite",RecipeFavoriteInput(v1,null,true),true)
        val archive = store.captureBackup(app,false,true)
        val otherIdentity = db.foodDao().recipeVersion(other)!!.recipeId
        val malformed = archive.copy(tables = archive.tables.map { table -> if (table.name != "recipe_favorites") table else
            table.copy(rows = table.rows.map { row -> row.toMutableList().apply {
                this[table.columns.indexOf("recipeId")] = BackupCell.Text(otherIdentity)
            } }) })
        reject { restore(malformed,"same-operation") }
        FoodBackupStore.TABLES.forEach { table -> target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            assertTrue(it.moveToFirst()); assertEquals("No partial rows in $table",0,it.getInt(0))
        } }
        assertEquals(BackupRestoreStatus.RESTORED,restore(archive,"same-operation").status)
        assertEquals(store.readRecipeFavorites(),destination.readRecipeFavorites())
        assertEquals(archive.tables.single { it.name == "recipe_versions" }.rows.size,target.foodDao().currentRecipes().size)
    }
}
