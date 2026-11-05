package de.ernaehrung.demo.data.local

import androidx.room.*
import androidx.room.withTransaction
import de.ernaehrung.demo.data.fitness.*
import java.time.Instant
import kotlinx.coroutines.flow.map

/** Separate from food operations. Privacy purge retains only opaque retired
 * request keys, so a late save/delete cannot resurrect or erase newer data.
 */
@Entity(tableName = "body_measurement_runs")
internal data class BodyMeasurementRunEntity(@PrimaryKey val id: String, val kind: String,
    val fingerprint: String, val resultId: String?, val recordedAt: Long)

@Entity(tableName = "body_measurement_versions", foreignKeys = [
    ForeignKey(entity = BodyMeasurementRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BodyMeasurementVersionEntity::class, parentColumns = ["id"], childColumns = ["previousId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["runId"], unique = true), Index(value = ["previousId"], unique = true),
    Index(value = ["seriesId", "revision"], unique = true)])
internal data class BodyMeasurementVersionEntity(@PrimaryKey val id: String, val seriesId: String,
    val revision: Long, val previousId: String?, val runId: String,
    val metric: String, val rawValue: String?, val decimalValue: String?, val unit: String, val name: String?,
    val localDate: String, val localTime: String?, val zoneId: String?, val explicitOffset: String?,
    val utc: String?, val offsetSeconds: Int?, val timePrecision: String,
    val source: String, val device: String?, val model: String?, val method: String, val methodDetail: String?,
    val conditions: String?, val quality: String, val excludedFromTrend: Boolean, val correctionReason: String?) {
    fun input() = BodyMeasurementInput(BodyMetric.valueOf(metric), rawValue, localDate, localTime, zoneId,
        explicitOffset, name, unit.takeIf { metric == BodyMetric.OTHER.name }, device, model,
        BodyMethod.valueOf(method), methodDetail, conditions, BodyQuality.valueOf(quality), excludedFromTrend)
}

@Dao
internal interface BodyMeasurementDao {
    @Query("SELECT * FROM body_measurement_runs WHERE id=:id") suspend fun run(id: String): BodyMeasurementRunEntity?
    @Query("SELECT * FROM body_measurement_versions WHERE id=:id") suspend fun version(id: String): BodyMeasurementVersionEntity?
    @Query("SELECT * FROM body_measurement_versions ORDER BY localDate DESC, localTime DESC, seriesId, revision DESC")
    suspend fun versions(): List<BodyMeasurementVersionEntity>
    @Query("SELECT * FROM body_measurement_versions WHERE seriesId=:series ORDER BY revision DESC LIMIT 1")
    suspend fun latest(series: String): BodyMeasurementVersionEntity?
    @Query("SELECT * FROM body_measurements ORDER BY measuredAt DESC, id") suspend fun legacy(): List<BodyMeasurementEntity>
    @Insert suspend fun insertRun(run: BodyMeasurementRunEntity)
    @Insert suspend fun insertVersion(version: BodyMeasurementVersionEntity)
    @Query("DELETE FROM body_measurement_versions WHERE id NOT IN (SELECT previousId FROM body_measurement_versions WHERE previousId IS NOT NULL)")
    suspend fun deleteVersionLeaves(): Int
    @Query("DELETE FROM body_measurements WHERE id NOT IN (SELECT correctsId FROM body_measurements WHERE correctsId IS NOT NULL)")
    suspend fun deleteLegacyLeaves(): Int
    @Query("UPDATE body_measurement_runs SET kind='REDACTED', fingerprint='', resultId=NULL, recordedAt=0 WHERE kind!='PURGE'")
    suspend fun redactRuns()
}

internal data class BodyMeasurementHistory(val versions: List<BodyMeasurementVersionEntity>, val legacy: List<BodyMeasurementEntity>) {
    val latest get() = versions.groupBy { it.seriesId }.values.map { it.maxBy(BodyMeasurementVersionEntity::revision) }
        .sortedWith(compareByDescending<BodyMeasurementVersionEntity> { it.localDate }.thenByDescending { it.localTime })
}

/** Called only by FoodStore. No consumption, stock, energy target or health
 * provider write occurs; version/CAS checks and retry keys commit atomically.
 */
internal class BodyMeasurementOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.bodyDao()
    fun observe() = db.invalidationTracker.createFlow("body_measurement_versions", "body_measurements").map {
        db.withTransaction { BodyMeasurementHistory(dao.versions(), dao.legacy()) }
    }
    suspend fun run(key: String) = db.withTransaction { dao.run(key) }
    suspend fun save(key: String, input: BodyMeasurementInput, expectedPreviousId: String?, confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Messung mit diesen Angaben ausdrücklich bestätigen." }
        require(key.isNotBlank() && key.length <= 200) { "Vorgangskennung fehlt." }
        val at = now().also { require(it >= 0L) }
        val n = BodyMeasurementRules.normalize(input, Instant.ofEpochMilli(at))
        val hash = WriteRules.fingerprint("BODY_MANUAL_V1", input.metric, input.rawValue, input.localDate,
            input.localTime, input.zoneId, input.explicitOffset, input.name, input.customUnit, input.device, input.model,
            input.method, input.methodDetail, input.conditions, n.quality, input.excludedFromTrend,
            input.correctionReason, expectedPreviousId)
        dao.run(key)?.let {
            require(it.kind == "SAVE" && it.fingerprint == hash) { "Dieser Vorgang ist bereits abgeschlossen oder aus Datenschutzgründen gesperrt. Neuen Vorgang beginnen." }
            return@withTransaction requireNotNull(it.resultId)
        }
        val previous = expectedPreviousId?.let { id ->
            requireNotNull(dao.version(id)) { "Zu korrigierende Version fehlt; Verlauf neu öffnen." }.also {
                require(dao.latest(it.seriesId)?.id == id) { "Messung wurde inzwischen korrigiert; aktuellen Verlauf prüfen." }
                require(it.metric == input.metric.name && it.name == input.name && it.unit == n.unit) { "Bei einer Korrektur Körpermaß und Einheit beibehalten." }
            }
        }
        require(previous == null || !input.correctionReason.isNullOrBlank()) { "Grund der Korrektur angeben." }
        require(previous != null || input.correctionReason == null) { "Korrektur benötigt eine vorhandene Messung." }
        val revision = previous?.revision?.let { require(it < Long.MAX_VALUE); it + 1L } ?: 1L
        val id = WriteRules.id()
        dao.insertRun(BodyMeasurementRunEntity(key, "SAVE", hash, id, at))
        dao.insertVersion(BodyMeasurementVersionEntity(id, previous?.seriesId ?: id, revision, previous?.id, key,
            input.metric.name, input.rawValue, n.value, n.unit, input.name, input.localDate, input.localTime,
            input.zoneId, input.explicitOffset, n.utc, n.offsetSeconds, n.timePrecision, "MANUAL",
            input.device, input.model, input.method.name, input.methodDetail, input.conditions, n.quality.name,
            input.excludedFromTrend, input.correctionReason))
        id
    }
    suspend fun purge(key: String, confirmed: Boolean) = db.withTransaction {
        require(confirmed) { "Löschung der lokalen Körpermesshistorie ausdrücklich bestätigen." }
        require(key.isNotBlank() && key.length <= 200)
        dao.run(key)?.let {
            require(it.kind == "PURGE") { "Vorgang bereits verwendet." }
            return@withTransaction // An old purge must never erase a later measurement.
        }
        while (dao.deleteVersionLeaves() > 0) { /* Children first, preserving RESTRICT semantics. */ }
        while (dao.deleteLegacyLeaves() > 0) { }
        check(dao.versions().isEmpty() && dao.legacy().isEmpty()) { "Historie konnte nicht vollständig gelöscht werden." }
        dao.redactRuns()
        dao.insertRun(BodyMeasurementRunEntity(key, "PURGE", "", null, 0L))
    }
}
