package de.ernaehrung.demo.data.local

import androidx.room.*
import androidx.room.withTransaction
import java.time.ZoneId

/** Original locations remain immutable. A review changes their current name/status,
 * never a batch's identity, quantity or recorded location. Capacity is a separate check. */
@Entity(tableName = "storage_location_reviews", foreignKeys = [
    ForeignKey(entity = StorageLocationEntity::class, parentColumns = ["id"], childColumns = ["locationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = StorageLocationReviewEntity::class, parentColumns = ["locationId", "id"], childColumns = ["locationId", "previousId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = StorageCapacityReviewEntity::class, parentColumns = ["locationId", "id"], childColumns = ["locationId", "capacityReviewId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["locationId", "revision"], unique = true), Index(value = ["locationId", "id"], unique = true),
    Index(value = ["locationId", "previousId"]), Index(value = ["previousId"], unique = true),
    Index(value = ["operationId"], unique = true), Index(value = ["locationId", "capacityReviewId"])])
data class StorageLocationReviewEntity(@PrimaryKey val id: String, val locationId: String, val revision: Int,
    val previousId: String?, val operationId: String, val name: String, val confirmed: Boolean,
    val capacityReviewId: String?, val reason: String, val source: Source, val quality: Quality,
    val recordedAt: Long, val zoneId: String)

internal const val CURRENT_LOCATIONS_SQL = """SELECT l.id,
    CASE WHEN r.id IS NULL THEN l.name ELSE r.name END AS name,
    CASE WHEN r.id IS NULL THEN l.confirmed ELSE r.confirmed END AS confirmed,
    l.usableCapacityMl,l.temperatureCelsius FROM storage_locations l LEFT JOIN storage_location_reviews r
    ON r.locationId=l.id AND r.revision=(SELECT MAX(revision) FROM storage_location_reviews WHERE locationId=l.id)"""

@DatabaseView(value = CURRENT_LOCATIONS_SQL, viewName = "current_storage_locations")
data class CurrentStorageLocationView(@Embedded val location: StorageLocationEntity)

internal fun StorageLocationEntity.withReview(review: StorageLocationReviewEntity?) =
    if (review == null) this else copy(name = review.name, confirmed = review.confirmed)

internal data class StorageLocationReviewInput(val locationId: String, val previousId: String?,
    val capacityReviewId: String?, val name: String, val confirmed: Boolean, val reason: String)

@Dao
internal interface StorageLocationReviewDao {
    @Insert suspend fun insert(row: StorageLocationReviewEntity)
    @Query("SELECT * FROM storage_location_reviews ORDER BY locationId,revision") suspend fun history(): List<StorageLocationReviewEntity>
    @Query("SELECT * FROM storage_location_reviews WHERE locationId=:id ORDER BY revision DESC LIMIT 1") suspend fun latest(id: String): StorageLocationReviewEntity?
    @Query("SELECT * FROM storage_location_reviews r WHERE recordedAt<=:at AND revision=(SELECT MAX(revision) FROM storage_location_reviews WHERE locationId=r.locationId AND recordedAt<=:at) ORDER BY locationId")
    suspend fun at(at: Long): List<StorageLocationReviewEntity>
}

internal class StorageLocationReviewOperations(private val db: FoodDatabase, private val now: () -> Long,
    private val zone: () -> String = { ZoneId.systemDefault().id }) {
    /** Also used before backup/restore commit: foreign keys alone allow cycles,
     * disconnected revisions or an operation belonging to a different action. */
    suspend fun validateHistory() {
        val capacities = db.storageCapacityDao().history().associateBy { it.id }
        db.storageLocationReviewDao().history().groupBy { it.locationId }.values.forEach { rows ->
            val ordered = rows.sortedBy { it.revision }
            ordered.forEachIndexed { index, row ->
                val previous = ordered.getOrNull(index - 1)
                require(row.id.isNotBlank() && row.revision == index + 1 && row.previousId == previous?.id)
                require(row.name.isNotBlank() && row.name == row.name.trim() && row.name.length <= 200)
                require(row.reason.isNotBlank() && row.reason.length <= 2000 && row.recordedAt >= 0L)
                require(row.source == Source.USER && row.quality == Quality.CONFIRMED)
                ZoneId.of(row.zoneId)
                require(previous == null || previous.recordedAt <= row.recordedAt)
                row.capacityReviewId?.let { id ->
                    val basis = requireNotNull(capacities[id])
                    require(basis.locationId == row.locationId && basis.recordedAt <= row.recordedAt)
                }
                val operation = requireNotNull(db.foodDao().operation(row.operationId))
                require(operation.kind == "STORAGE_LOCATION_REVIEW_V1" && operation.resultId == row.id && operation.recordedAt == row.recordedAt)
                require(operation.fingerprint == WriteRules.fingerprint("STORAGE_LOCATION_REVIEW_V1", row.locationId,
                    row.previousId, row.capacityReviewId, row.name, row.confirmed, row.reason))
            }
        }
    }

    suspend fun save(key: String, input: StorageLocationReviewInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Diesen Namen und Status ausdrücklich bestätigen." }
        require(input.name.isNotBlank() && input.name.length <= 200) { "Lagername mit höchstens 200 Zeichen angeben." }
        require(input.reason.isNotBlank() && input.reason.length <= 2000) { "Nachvollziehbaren Prüf-/Korrekturgrund angeben." }
        val hash = WriteRules.fingerprint("STORAGE_LOCATION_REVIEW_V1", input.locationId, input.previousId,
            input.capacityReviewId, input.name.trim(), input.confirmed, input.reason)
        val food = db.foodDao()
        WriteRules.replay(food, key, hash)?.let { return@withTransaction it }
        val current = requireNotNull(food.location(input.locationId)) { "Lagerort nicht gefunden." }
        val previous = db.storageLocationReviewDao().latest(input.locationId)
        val capacity = db.storageCapacityDao().latest(input.locationId)
        require(previous?.id == input.previousId && capacity?.id == input.capacityReviewId) {
            "Lagerort oder Kapazitätsprüfung inzwischen geändert. Aktuellen Stand erneut prüfen." }
        if (current.confirmed && !input.confirmed) require(food.planningBatches().none { it.batch.locationId == current.id && it.stockAmount > 0L }) {
            "Hier liegt noch Bestand. Zuerst tatsächliche Umlagerung, Entsorgung oder Bestandskorrektur separat bestätigen. Es wird keine Menge automatisch verschoben." }
        val at = now()
        require(at >= 0L && (previous == null || at >= previous.recordedAt) && (capacity == null || at >= capacity.recordedAt)) {
            "Prüfzeitpunkt liegt vor den bisherigen Lagerangaben." }
        val id = WriteRules.id()
        food.insertOperation(OperationEntity(key, "STORAGE_LOCATION_REVIEW_V1", hash, at, id))
        db.storageLocationReviewDao().insert(StorageLocationReviewEntity(id, current.id, Math.addExact(previous?.revision ?: 0, 1),
            previous?.id, key, input.name.trim(), input.confirmed, capacity?.id, input.reason,
            Source.USER, Quality.CONFIRMED, at, zone().also { ZoneId.of(it) }))
        id
    }
}
