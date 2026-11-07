package de.ernaehrung.demo.data.local

import androidx.room.*
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A local library preference, never a recipe revision or a food ledger event. */
@Entity(tableName = "recipe_favorites", foreignKeys = [
    ForeignKey(entity = RecipeEntity::class,parentColumns = ["id"],childColumns = ["recipeId"],onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = RecipeVersionEntity::class,parentColumns = ["id"],childColumns = ["recipeVersionId"],onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = RecipeFavoriteEntity::class,parentColumns = ["id"],childColumns = ["previousId"],onDelete = ForeignKey.RESTRICT)
],indices = [Index(value = ["recipeId","revision"],unique = true),Index("recipeVersionId"),Index("previousId")])
data class RecipeFavoriteEntity(@PrimaryKey val id: String,val recipeId: String,val recipeVersionId: String,
    val revision: Int,val previousId: String?,val status: String,val payloadHash: String,val recordedAt: Long,val zoneId: String)

@Dao
internal interface RecipeFavoriteDao {
    @Query("SELECT * FROM recipe_favorites ORDER BY recipeId,revision") suspend fun history(): List<RecipeFavoriteEntity>
    @Query("SELECT * FROM recipe_favorites ORDER BY recipeId,revision") fun observe(): kotlinx.coroutines.flow.Flow<List<RecipeFavoriteEntity>>
    @Query("SELECT * FROM recipe_favorites WHERE id=:key") suspend fun run(key: String): RecipeFavoriteEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM recipe_favorites f JOIN recipe_versions v ON v.id=f.recipeVersionId WHERE f.recipeId<>v.recipeId OR f.recordedAt<v.recordedAt)")
    suspend fun invalidRecipeReference(): Boolean
    @Insert suspend fun insert(row: RecipeFavoriteEntity)
}

internal data class RecipeFavoriteInput(val recipeVersionId: String,val previousId: String?,val favorite: Boolean)
internal data class RecipeFavoriteSnapshot(val history: List<RecipeFavoriteEntity>) {
    val latest: Map<String,RecipeFavoriteEntity> = history.groupBy { it.recipeId }.mapValues { it.value.maxBy { row -> row.revision } }
    val recipeIds: Set<String> = latest.values.filter { it.status == "FAVORITE" }.mapTo(mutableSetOf()) { it.recipeId }
    init {
        require(history.map { it.id }.distinct().size == history.size)
        history.groupBy { it.recipeId }.values.forEach { unsorted ->
            val rows = unsorted.sortedBy { it.revision }
            rows.forEachIndexed { index,row ->
            val prior = rows.getOrNull(index - 1)
            require(row.id.isNotBlank() && row.recipeId.isNotBlank() && row.recipeVersionId.isNotBlank())
            require(row.revision == index + 1 && row.previousId == prior?.id && row.recordedAt >= 0L)
            require(prior == null || row.recordedAt >= prior.recordedAt)
            require(row.status in setOf("FAVORITE","REMOVED")); ZoneId.of(row.zoneId)
            require(row.payloadHash == RecipeFavoriteOperations.hash(RecipeFavoriteInput(row.recipeVersionId,row.previousId,row.status == "FAVORITE")))
        } }
    }
}

internal class RecipeFavoriteOperations(private val db: FoodDatabase,private val now: () -> Long,
    private val zone: () -> String = { ZoneId.systemDefault().id }) {
    companion object {
        fun hash(input: RecipeFavoriteInput) = WriteRules.fingerprint("RECIPE_FAVORITE_V1",input.recipeVersionId,input.previousId,input.favorite)
    }
    private val dao = db.recipeFavoriteDao()
    suspend fun snapshot(): RecipeFavoriteSnapshot {
        require(!dao.invalidRecipeReference()) { "Favoritenbezüge sind widersprüchlich. Keine Änderung vorgenommen." }
        return RecipeFavoriteSnapshot(dao.history())
    }
    fun watch() = dao.observe().map { db.withTransaction { snapshot() } }.distinctUntilChanged()
    suspend fun save(key: String,input: RecipeFavoriteInput,confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Favorit durch ausdrückliche Auswahl ändern." }
        require(key.isNotBlank() && input.recipeVersionId.isNotBlank()) { "Rezeptfassung oder Vorgangskennung fehlt." }
        val hash = hash(input)
        dao.run(key)?.let { require(it.payloadHash == hash) { "Vorgangskennung gehört zu einer anderen Favoritenauswahl." }; return@withTransaction it.id }
        val version = requireNotNull(db.foodDao().recipeVersion(input.recipeVersionId)) { "Gespeicherte Rezeptfassung fehlt." }
        require(db.foodDao().latestRecipeVersion(version.recipeId)?.id == version.id) { "Rezeptfassung inzwischen geändert. Aktuelle Fassung erneut wählen." }
        val previous = snapshot().latest[version.recipeId]
        require(previous?.id == input.previousId) { "Favorit inzwischen geändert. Aktuellen Stand erneut prüfen." }
        val at = now()
        require(at >= 0L && version.recordedAt in 0L..at && (previous == null || previous.recordedAt <= at)) { "Ungültiger Favoriten-Erfassungszeitpunkt." }
        val row = RecipeFavoriteEntity(key,version.recipeId,version.id,(previous?.revision ?: 0) + 1,previous?.id,
            if (input.favorite) "FAVORITE" else "REMOVED",hash,at,zone().also { ZoneId.of(it) })
        dao.insert(row); row.id
    }
}
