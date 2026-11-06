package de.ernaehrung.demo.data.local

import androidx.room.*
import androidx.room.withTransaction
import de.ernaehrung.demo.data.fitness.*
import java.time.Instant
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Separate from food operations. Privacy purge retains only opaque retired
 * request keys, so a late save/delete cannot resurrect or erase newer data.
 */
@Entity(tableName = "body_measurement_runs")
internal data class BodyMeasurementRunEntity(@PrimaryKey val id: String, val kind: String,
    val fingerprint: String, val resultId: String?, val recordedAt: Long)

@Entity(tableName = "body_measurement_versions", foreignKeys = [
    ForeignKey(entity = BodyMeasurementRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BodyMeasurementVersionEntity::class, parentColumns = ["id"], childColumns = ["previousId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BodyMeasurementEntity::class, parentColumns = ["id"], childColumns = ["legacyOriginId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["runId"], unique = true), Index(value = ["previousId"], unique = true),
    Index(value = ["seriesId", "revision"], unique = true), Index(value = ["legacyOriginId", "legacyField"], unique = true)])
internal data class BodyMeasurementVersionEntity(@PrimaryKey val id: String, val seriesId: String,
    val revision: Long, val previousId: String?, val runId: String,
    val metric: String, val rawValue: String?, val decimalValue: String?, val unit: String, val name: String?,
    val localDate: String, val localTime: String?, val zoneId: String?, val explicitOffset: String?,
    val utc: String?, val offsetSeconds: Int?, val timePrecision: String,
    val source: String, val device: String?, val model: String?, val method: String, val methodDetail: String?,
    val conditions: String?, val quality: String, val excludedFromTrend: Boolean, val correctionReason: String?,
    val legacyOriginId: String? = null, val legacyField: String? = null) {
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
    @Query("SELECT * FROM body_measurement_versions WHERE legacyOriginId=:id AND legacyField=:field")
    suspend fun forLegacy(id: String, field: String): BodyMeasurementVersionEntity?
    @Insert suspend fun insertRun(run: BodyMeasurementRunEntity)
    @Query("UPDATE body_measurement_runs SET kind='SAVE',fingerprint=:hash,resultId=:result,recordedAt=:at WHERE id=:key AND kind='OPEN'")
    suspend fun commitIntent(key: String, hash: String, result: String, at: Long): Int
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
    suspend fun begin(key: String) = db.withTransaction {
        require(key.isNotBlank() && key.length <= 200)
        dao.run(key) ?: BodyMeasurementRunEntity(key, "OPEN", "", null, 0L).also { dao.insertRun(it) }
    }
    fun observeRun(key: String) = db.invalidationTracker.createFlow("body_measurement_runs")
        .map { db.withTransaction { dao.run(key) } }.distinctUntilChanged()
    suspend fun save(key: String, input: BodyMeasurementInput, expectedPreviousId: String?, confirmed: Boolean,
        legacyId: String? = null, legacyField: LegacyBodyField? = null): String = db.withTransaction {
        require(confirmed) { "Messung mit diesen Angaben ausdrücklich bestätigen." }
        require(key.isNotBlank() && key.length <= 200) { "Vorgangskennung fehlt." }
        require((legacyId == null) == (legacyField == null) && (expectedPreviousId == null || legacyId == null)) { "Aktuelle Version oder bestimmtes früheres Messfeld wählen." }
        val at = now().also { require(it >= 0L) }
        val n = BodyMeasurementRules.normalize(input, Instant.ofEpochMilli(at))
        val originalHash = WriteRules.fingerprint("BODY_MANUAL_V1", input.metric, input.rawValue, input.localDate,
            input.localTime, input.zoneId, input.explicitOffset, input.name, input.customUnit, input.device, input.model,
            input.method, input.methodDetail, input.conditions, n.quality, input.excludedFromTrend,
            input.correctionReason, expectedPreviousId)
        val hash = if (legacyId == null) originalHash else WriteRules.fingerprint("BODY_LEGACY_CORRECTION_V1", originalHash, legacyId, legacyField?.name)
        val intent = dao.run(key)
        if (intent?.kind == "SAVE") {
            require(intent.fingerprint == hash) { "Dieser Vorgang ist bereits mit anderen Angaben abgeschlossen. Gespeicherten Stand prüfen." }
            return@withTransaction requireNotNull(intent.resultId)
        }
        require(intent == null || intent.kind == "OPEN") { "Dieser Vorgang ist abgeschlossen oder nach einer Löschung gesperrt." }
        val previous = expectedPreviousId?.let { id ->
            requireNotNull(dao.version(id)) { "Zu korrigierende Version fehlt; Verlauf neu öffnen." }.also {
                require(dao.latest(it.seriesId)?.id == id) { "Messung wurde inzwischen korrigiert; aktuellen Verlauf prüfen." }
                require(it.metric == input.metric.name && it.name == input.name && it.unit == n.unit) { "Bei einer Korrektur Körpermaß und Einheit beibehalten." }
            }
        }
        if (legacyId != null) {
            val old = dao.legacy()
            require(old.any { it.id == legacyId } && old.none { it.correctsId == legacyId }) { "Frühere Messung fehlt oder wurde bereits durch eine neuere ersetzt." }
            require(dao.forLegacy(legacyId, requireNotNull(legacyField).name) == null) { "Dieses frühere Messfeld wurde bereits korrigiert; aktuelle Version prüfen." }
            require(input.metric == legacyField.metric && input.name == legacyField.customName && n.unit == legacyField.unit) { "Früheres Messfeld und Originaleinheit beibehalten." }
        }
        require((previous != null || legacyId != null) == (input.correctionReason != null)) { "Korrekturgrund benötigt eine vorhandene Messung; für neue Werte weglassen." }
        val revision = previous?.revision?.let { require(it < Long.MAX_VALUE); it + 1L } ?: 1L
        val id = WriteRules.id()
        if (intent == null) dao.insertRun(BodyMeasurementRunEntity(key, "SAVE", hash, id, at))
        else check(dao.commitIntent(key, hash, id, at) == 1) { "Messvorgang inzwischen verändert." }
        dao.insertVersion(BodyMeasurementVersionEntity(id, previous?.seriesId ?: id, revision, previous?.id, key,
            input.metric.name, input.rawValue, n.value, n.unit, input.name, input.localDate, input.localTime,
            input.zoneId, input.explicitOffset, n.utc, n.offsetSeconds, n.timePrecision, "MANUAL",
            input.device, input.model, input.method.name, input.methodDetail, input.conditions, n.quality.name,
            input.excludedFromTrend, input.correctionReason, legacyId, legacyField?.name))
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
