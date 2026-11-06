package de.ernaehrung.demo.data.local

import androidx.room.*
import androidx.room.withTransaction
import de.ernaehrung.demo.data.fitness.*
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Entity(tableName = "manual_workout_runs")
internal data class ManualWorkoutRunEntity(@PrimaryKey val id: String, val kind: String,
    val fingerprint: String, val resultId: String?, val recordedAt: Long)

@Entity(tableName = "manual_workout_versions", foreignKeys = [
    ForeignKey(entity = ManualWorkoutRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ManualWorkoutVersionEntity::class, parentColumns = ["id"], childColumns = ["previousId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = TrainingEventEntity::class, parentColumns = ["id"], childColumns = ["legacyOriginId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["runId"], unique = true), Index(value = ["previousId"], unique = true),
    Index(value = ["legacyOriginId"], unique = true), Index(value = ["seriesId", "revision"], unique = true)])
internal data class ManualWorkoutVersionEntity(@PrimaryKey val id: String, val seriesId: String,
    val revision: Long, val previousId: String?, val legacyOriginId: String?, val runId: String,
    val sport: String, val localDate: String, val localTime: String?, val zoneId: String?, val explicitOffset: String?,
    val utc: String?, val offsetSeconds: Int?, val timePrecision: String, val endUtc: String?,
    val rawDurationMinutes: String?, val durationMinutes: String?, val rawDistanceMetres: String?, val distanceMetres: String?,
    val intensity: String?, val effort: String?, val device: String?, val conditions: String?, val note: String?,
    val source: String, val quality: String, val correctionReason: String?) {
    fun input(sets: List<ManualWorkoutSetEntity>, retainReason: Boolean = false) = ManualWorkoutInput(sport,
        FitnessTimestampInput(localDate, localTime, zoneId, explicitOffset), rawDurationMinutes, rawDistanceMetres,
        intensity, effort, device, conditions, note, sets.filter { it.versionId == id }.sortedBy { it.position }.map {
            ManualWorkoutSetInput(it.exercise, it.rawRepetitions, it.rawLoadKg, it.effort, it.note)
        }, correctionReason.takeIf { retainReason })
}

@Entity(tableName = "manual_workout_sets", foreignKeys = [
    ForeignKey(entity = ManualWorkoutVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["versionId", "position"], unique = true)])
internal data class ManualWorkoutSetEntity(@PrimaryKey val id: String, val versionId: String, val position: Int,
    val exercise: String, val rawRepetitions: String?, val repetitions: Long?, val rawLoadKg: String?, val loadKg: String?,
    val effort: String?, val note: String?)

@Dao
internal interface ManualWorkoutDao {
    @Query("SELECT * FROM manual_workout_runs WHERE id=:key") suspend fun run(key: String): ManualWorkoutRunEntity?
    @Query("SELECT * FROM manual_workout_versions WHERE id=:id") suspend fun version(id: String): ManualWorkoutVersionEntity?
    @Query("SELECT * FROM manual_workout_versions ORDER BY localDate DESC,localTime DESC,seriesId,revision DESC")
    suspend fun versions(): List<ManualWorkoutVersionEntity>
    @Query("SELECT * FROM manual_workout_sets ORDER BY versionId,position") suspend fun sets(): List<ManualWorkoutSetEntity>
    @Query("SELECT * FROM manual_workout_versions WHERE seriesId=:series ORDER BY revision DESC LIMIT 1")
    suspend fun latest(series: String): ManualWorkoutVersionEntity?
    @Query("SELECT * FROM manual_workout_versions WHERE legacyOriginId=:id") suspend fun forLegacy(id: String): ManualWorkoutVersionEntity?
    @Insert suspend fun insertRun(row: ManualWorkoutRunEntity)
    @Query("UPDATE manual_workout_runs SET kind='SAVE',fingerprint=:hash,resultId=:result,recordedAt=:at WHERE id=:key AND kind='OPEN'")
    suspend fun commitIntent(key: String, hash: String, result: String, at: Long): Int
    @Insert suspend fun insertVersion(row: ManualWorkoutVersionEntity)
    @Insert suspend fun insertSets(rows: List<ManualWorkoutSetEntity>)
    @Query("DELETE FROM manual_workout_sets") suspend fun deleteSets()
    @Query("DELETE FROM manual_workout_versions WHERE id NOT IN (SELECT previousId FROM manual_workout_versions WHERE previousId IS NOT NULL)")
    suspend fun deleteVersionLeaves(): Int
    @Query("DELETE FROM training_sets") suspend fun deleteLegacySets()
    @Query("DELETE FROM training_events") suspend fun deleteLegacyEvents()
    @Query("UPDATE manual_workout_runs SET kind='REDACTED',fingerprint='',resultId=NULL,recordedAt=0 WHERE kind!='PURGE'")
    suspend fun redactRuns()
}

/** FoodStore owns these confirmed atomic operations. No food, target or health
 * provider event is written. Existing legacy rows remain immutable originals.
 */
internal class ManualWorkoutOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.manualWorkoutDao()
    suspend fun run(key: String) = db.withTransaction { dao.run(key) }
    fun observeRun(key: String) = db.invalidationTracker.createFlow("manual_workout_runs")
        .map { db.withTransaction { dao.run(key) } }.distinctUntilChanged()
    suspend fun begin(key: String) = db.withTransaction {
        require(key.isNotBlank() && key.length <= 200)
        dao.run(key) ?: ManualWorkoutRunEntity(key, "OPEN", "", null, 0L).also { dao.insertRun(it) }
    }

    suspend fun save(key: String, original: ManualWorkoutInput, previousId: String?, legacyId: String?, confirmed: Boolean): String {
        val frozen = original.copy(sets = original.sets.toList())
        return db.withTransaction {
            require(confirmed) { "Dieses tatsächlich abgeschlossene Training ausdrücklich bestätigen." }
            require(key.isNotBlank() && key.length <= 200) { "Vorgangskennung fehlt." }
            require(previousId == null || legacyId == null) { "Entweder aktuelle Version oder ursprünglichen Alteintrag korrigieren." }
            val at = now().also { require(it >= 0L) }
            val n = ManualWorkoutRules.normalize(frozen, Instant.ofEpochMilli(at)); val input = n.input
            val hash = WriteRules.fingerprint("MANUAL_WORKOUT_V1", input.sport, input.time.localDate,
                input.time.localTime, input.time.zoneId, input.time.explicitOffset, input.durationMinutes,
                input.distanceMetres, input.intensity, input.effort, input.device, input.conditions, input.note,
                input.sets.map { listOf(it.exercise, it.repetitions, it.loadKg, it.effort, it.note) },
                input.correctionReason, previousId, legacyId)
            val intent = requireNotNull(dao.run(key)) { "Trainingsvorgang zuerst öffnen und prüfen." }
            if (intent.kind == "SAVE") {
                require(intent.fingerprint == hash) { "Vorgang bereits mit anderen Angaben abgeschlossen. Gespeicherten Stand prüfen." }
                return@withTransaction requireNotNull(intent.resultId)
            }
            require(intent.kind == "OPEN") { "Vorgang abgeschlossen oder aus Datenschutzgründen gesperrt. Neuen Vorgang beginnen." }
            val previous = previousId?.let { id -> requireNotNull(dao.version(id)) { "Vorversion fehlt; Verlauf neu öffnen." }.also {
                require(dao.latest(it.seriesId)?.id == id) { "Training wurde inzwischen korrigiert. Aktuelle Version neu prüfen." }
            } }
            if (legacyId != null) {
                require(db.foodDao().legacyTrainingEvents().any { it.id == legacyId }) { "Ursprünglicher Trainingseintrag fehlt." }
                require(dao.forLegacy(legacyId) == null) { "Alteintrag wurde bereits korrigiert; aktuelle Version prüfen." }
            }
            require((previous != null || legacyId != null) == (input.correctionReason != null)) { "Korrekturgrund benötigt eine vorhandene Vorversion; für neue Trainings weglassen." }
            val revision = previous?.revision?.let { require(it < Long.MAX_VALUE); it + 1L } ?: 1L
            val id = WriteRules.id()
            check(dao.commitIntent(key, hash, id, at) == 1) { "Trainingsvorgang inzwischen verändert." }
            dao.insertVersion(ManualWorkoutVersionEntity(id, previous?.seriesId ?: id, revision, previousId, legacyId, key,
                input.sport, input.time.localDate, input.time.localTime, input.time.zoneId, input.time.explicitOffset,
                n.time.utc, n.time.offsetSeconds, n.time.precision, n.endUtc, input.durationMinutes, n.durationMinutes,
                input.distanceMetres, n.distanceMetres, input.intensity, input.effort, input.device, input.conditions,
                input.note, "MANUAL", "USER_REPORTED", input.correctionReason))
            dao.insertSets(n.sets.mapIndexed { index, set -> ManualWorkoutSetEntity(WriteRules.id(), id, index,
                set.input.exercise, set.input.repetitions, set.repetitions, set.input.loadKg, set.loadKg,
                set.input.effort, set.input.note) })
            id
        }
    }

    suspend fun purge(key: String, confirmed: Boolean) = db.withTransaction {
        require(confirmed) { "Gesamte manuelle und frühere Trainingshistorie ausdrücklich zum Löschen bestätigen." }
        require(key.isNotBlank() && key.length <= 200)
        dao.run(key)?.let { require(it.kind == "PURGE") { "Vorgangskennung bereits verwendet." }; return@withTransaction }
        dao.deleteSets()
        while (dao.deleteVersionLeaves() > 0) { }
        check(dao.versions().isEmpty()) { "Trainingsversionen konnten nicht vollständig gelöscht werden." }
        dao.deleteLegacySets(); dao.deleteLegacyEvents(); dao.redactRuns()
        dao.insertRun(ManualWorkoutRunEntity(key, "PURGE", "", null, 0L))
    }
}
