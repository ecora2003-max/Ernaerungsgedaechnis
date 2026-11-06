package de.ernaehrung.demo.data.local

import androidx.room.*
import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A physical check is separate from stock quantity and from planned storage.
 * Capacity and occupied OUTER container volume use whole millilitres. Neither
 * food mass nor remaining portions imply a container's outside volume.
 */
@Entity(tableName = "storage_capacity_reviews", foreignKeys = [
    ForeignKey(entity = StorageLocationEntity::class, parentColumns = ["id"], childColumns = ["locationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = StorageCapacityReviewEntity::class, parentColumns = ["locationId", "id"],
        childColumns = ["locationId", "previousId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["locationId", "revision"], unique = true), Index(value = ["locationId", "id"], unique = true),
    Index(value = ["locationId", "previousId"]), Index(value = ["previousId"], unique = true), Index(value = ["operationId"], unique = true)])
data class StorageCapacityReviewEntity(@PrimaryKey val id: String, val locationId: String, val revision: Int,
    val previousId: String?, val operationId: String, val totalMl: Long?, val occupiedMl: Long?,
    val temperatureCelsiusText: String?, val reason: String, val basisKind: String,
    val addedOuterVolumeMl: Long?, val source: Source, val quality: Quality, val recordedAt: Long, val zoneId: String)

@Dao
internal interface StorageCapacityDao {
    @Insert suspend fun insert(value: StorageCapacityReviewEntity)
    @Query("SELECT * FROM storage_capacity_reviews ORDER BY locationId,revision") suspend fun history(): List<StorageCapacityReviewEntity>
    @Query("SELECT * FROM storage_capacity_reviews WHERE locationId=:id ORDER BY revision") suspend fun history(id: String): List<StorageCapacityReviewEntity>
    @Query("SELECT r.originalOperationId FROM operation_reversals r JOIN storage_capacity_reviews c ON c.operationId=r.originalOperationId")
    suspend fun reversedOperations(): List<String>
    @Query("SELECT * FROM storage_capacity_reviews WHERE locationId=:id ORDER BY revision DESC LIMIT 1")
    suspend fun latest(id: String): StorageCapacityReviewEntity?
    @Query("SELECT * FROM storage_capacity_reviews v WHERE recordedAt <= :at AND revision=(SELECT MAX(revision) FROM storage_capacity_reviews WHERE locationId=v.locationId AND recordedAt <= :at) ORDER BY locationId")
    suspend fun at(at: Long): List<StorageCapacityReviewEntity>
}

internal data class StorageCapacityInput(val locationId: String, val previousId: String?,
    val totalMl: Long?, val occupiedMl: Long?, val temperatureCelsiusText: String?, val reason: String)
data class ProductionCapacityEvidence(val reviewId: String, val addedOuterVolumeMl: Long, val physicallyChecked: Boolean)
internal data class StorageCapacitySnapshot(val locations: List<StorageLocationEntity>, val history: List<StorageCapacityReviewEntity>,
    val reversedOperationIds: Set<String>)

internal object StorageCapacityRules {
    const val MANUAL = "PHYSICAL_CHECK_V1"
    const val PRODUCTION = "CONFIRMED_PRODUCTION_ADDITION_V1"
    fun validate(total: Long?, occupied: Long?, temperature: String?) {
        require(total == null || total >= 0L) { "Nutzbare Gesamtkapazität als ganze Milliliter oder unbekannt angeben." }
        require(occupied == null || occupied >= 0L) { "Belegtes Außenvolumen als ganze Milliliter oder unbekannt angeben." }
        require(total == null || occupied == null || occupied <= total) { "Belegtes Außenvolumen überschreitet die nutzbare Gesamtkapazität." }
        temperature?.let {
            require(it.length <= 80 && it.trim().matches(Regex("[+-]?[0-9]+([.,][0-9]+)?"))) { "Temperatur in °C als Dezimalzahl oder unbekannt angeben." }
            require(BigDecimal(it.trim().replace(',', '.')) >= BigDecimal("-273.15")) { "Temperatur liegt unter dem absoluten Nullpunkt." }
        }
    }
    fun free(total: Long?, occupied: Long?): Long? {
        validate(total, occupied, null)
        return if (total == null || occupied == null) null else total - occupied
    }
    fun free(value: StorageCapacityReviewEntity) = free(value.totalMl, value.occupiedMl)
    fun physicalBasis(value: StorageCapacityReviewEntity, history: List<StorageCapacityReviewEntity>): StorageCapacityReviewEntity? {
        val byId = history.associateBy { it.id }; val visited = mutableSetOf<String>()
        var current = value
        while (visited.add(current.id)) {
            if (current.basisKind == MANUAL) return current
            if (current.basisKind != PRODUCTION) return null
            current = byId[current.previousId] ?: return null
        }
        return null
    }
    fun trustedFree(value: StorageCapacityReviewEntity, history: List<StorageCapacityReviewEntity>, reversed: Set<String>): Long? {
        val byId = history.associateBy { it.id }; val visited = mutableSetOf<String>()
        var current = value
        while (visited.add(current.id)) {
            if (current.operationId in reversed) return null
            if (current.basisKind == MANUAL) return free(value)
            if (current.basisKind != PRODUCTION) return null
            current = byId[current.previousId] ?: return null
        }
        return null
    }
}

/** Only FoodStore/KitchenOperations call this writer. Read projections never
 * create a review, free space or a food event. All SQL mutations stay inside the
 * caller's one FoodStore transaction, including a production's capacity delta.
 */
internal class StorageCapacityOperations(private val db: FoodDatabase, private val now: () -> Long,
    private val zone: () -> String = { ZoneId.systemDefault().id }) {
    private val dao get() = db.storageCapacityDao()
    fun watch() = db.invalidationTracker.createFlow("storage_locations", "storage_capacity_reviews", "operation_reversals", emitInitialState = true)
        .map { db.withTransaction { StorageCapacitySnapshot(db.foodDao().allLocations(), dao.history(), dao.reversedOperations().toSet()) } }.distinctUntilChanged()

    suspend fun save(key: String, input: StorageCapacityInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Diese aktuellen tatsächlichen Lagerangaben ausdrücklich bestätigen." }
        StorageCapacityRules.validate(input.totalMl, input.occupiedMl, input.temperatureCelsiusText)
        require(input.reason.isNotBlank() && input.reason.length <= 2000) { "Prüf-/Korrekturgrund fehlt oder ist zu lang." }
        val hash = WriteRules.fingerprint("STORAGE_CAPACITY_CHECK_V1", input.locationId, input.previousId,
            input.totalMl, input.occupiedMl, input.temperatureCelsiusText, input.reason)
        WriteRules.replay(db.foodDao(), key, hash)?.let { return@withTransaction it }
        val location = requireNotNull(db.foodDao().location(input.locationId)) { "Lagerort fehlt." }
        require(location.confirmed) { "Ein geplanter oder unbestätigter Lagerort erzeugt keine tatsächliche Kapazität." }
        val previous = dao.latest(input.locationId)
        require(previous?.id == input.previousId) { "Kapazitätsprüfung inzwischen geändert. Neu lesen und erneut bestätigen." }
        val at = now(); require(at >= 0L && (previous == null || at >= previous.recordedAt)) { "Zeitpunkt liegt vor der bisherigen Prüfung." }
        val id = WriteRules.id()
        db.foodDao().insertOperation(OperationEntity(key, "STORAGE_CAPACITY_CHECK_V1", hash, at, id))
        dao.insert(StorageCapacityReviewEntity(id, input.locationId, Math.addExact(previous?.revision ?: 0, 1),
            previous?.id, key, input.totalMl, input.occupiedMl, input.temperatureCelsiusText, input.reason,
            StorageCapacityRules.MANUAL, null, Source.USER, Quality.CONFIRMED, at, zone().also { ZoneId.of(it) }))
        id
    }

    suspend fun requireProductionBasis(locationId: String, evidence: ProductionCapacityEvidence): StorageCapacityReviewEntity {
        check(db.inTransaction())
        require(evidence.physicallyChecked) { "Diese Kapazitätsfassung und das aktuelle Außenvolumen vor Ort prüfen und bestätigen." }
        require(evidence.addedOuterVolumeMl >= 0L) { "Tatsächliches zusätzliches Behälter-Außenvolumen in ganzen Millilitern angeben; 0 für bereits belegte Behälter ist erlaubt." }
        val previous = requireNotNull(dao.latest(locationId)) { "Aktuelle strukturierte Kapazitätsprüfung fehlt." }
        require(previous.id == evidence.reviewId) { "Kapazitätsfassung inzwischen geändert. Herstellung neu prüfen." }
        val free = requireNotNull(StorageCapacityRules.trustedFree(previous, dao.history(locationId), dao.reversedOperations().toSet())) {
            "Freie Kapazität bleibt unbekannt oder die Herstellungsbasis wurde storniert. Aktuelle physische Angaben erneut prüfen." }
        require(evidence.addedOuterVolumeMl <= free) { "Zusätzliche Behälter passen nicht in die bestätigte freie Kapazität." }
        return previous
    }

    suspend fun recordProduction(key: String, previous: StorageCapacityReviewEntity,
        evidence: ProductionCapacityEvidence, recordedAt: Long) {
        check(db.inTransaction())
        require(recordedAt >= previous.recordedAt) { "Herstellung liegt vor der Kapazitätsprüfung." }
        // No free-space release from eating/waste/transfer: a container can still
        // occupy space. A later physical check explicitly records that change.
        val occupied = Math.addExact(requireNotNull(previous.occupiedMl), evidence.addedOuterVolumeMl)
        StorageCapacityRules.validate(previous.totalMl, occupied, previous.temperatureCelsiusText)
        dao.insert(previous.copy(id = WriteRules.id(), revision = Math.addExact(previous.revision, 1),
            previousId = previous.id, operationId = key, occupiedMl = occupied,
            reason = "Bestätigte Herstellung: zusätzlich ${evidence.addedOuterVolumeMl} ml Behälter-Außenvolumen eingelagert.",
            basisKind = StorageCapacityRules.PRODUCTION, addedOuterVolumeMl = evidence.addedOuterVolumeMl,
            recordedAt = recordedAt, zoneId = zone().also { ZoneId.of(it) }))
    }
}
