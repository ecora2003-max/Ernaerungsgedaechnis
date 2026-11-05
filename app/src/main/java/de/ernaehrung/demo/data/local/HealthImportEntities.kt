package de.ernaehrung.demo.data.local

import androidx.room.*
import de.ernaehrung.demo.data.fitness.*

/** Mutable permission/cache status. Nutrition/stock operations never reference it. */
@Entity(tableName = "health_import_state", foreignKeys = [ForeignKey(
    entity = HealthImportRunEntity::class, parentColumns = ["id"], childColumns = ["lastSuccessRunId"],
    onDelete = ForeignKey.RESTRICT)], indices = [Index("lastSuccessRunId")])
internal data class HealthImportStateEntity(@PrimaryKey val sourcePackage: String,
    val enabled: Boolean, val authorizationRevision: Long, val lastSuccessRunId: String? = null,
    val lastAttemptAt: Long? = null, val lastErrorCode: String? = null)

/** Append-only operation evidence. No personal payload in operation keys/logs. */
@Entity(tableName = "health_import_runs", indices = [Index("sourcePackage")])
internal data class HealthImportRunEntity(@PrimaryKey val id: String, val sourcePackage: String,
    val kind: String, val fingerprint: String, val recordedAt: Long, val authorizationRevision: Long,
    val capturedAt: Long? = null, val windowStart: Long? = null, val windowEnd: Long? = null,
    val day: String? = null, val dayZone: String? = null, val dayStart: Long? = null,
    val dayEnd: Long? = null, val aggregatedSteps: Long? = null,
    val changedRecords: Int = 0, val hiddenRecords: Int = 0)

/** Original payload and every correction/visibility revision remain separate.
 * Explicit local purge is the only delete path; it never deletes provider data.
 */
@Entity(tableName = "health_source_record_versions",
    primaryKeys = ["sourcePackage", "kind", "externalId", "revision"], foreignKeys = [ForeignKey(
        entity = HealthImportRunEntity::class, parentColumns = ["id"], childColumns = ["runId"],
        onDelete = ForeignKey.RESTRICT)], indices = [Index("runId")])
internal data class HealthRecordVersionEntity(val sourcePackage: String, val kind: String,
    val externalId: String, val revision: Long, val runId: String, val visible: Boolean,
    val visibilityReason: String, val fingerprint: String,
    val startAt: Long, val endAt: Long, val startOffsetSeconds: Int?, val endOffsetSeconds: Int?,
    val sourceModifiedAt: Long, val clientRecordId: String?, val clientRecordVersion: Long,
    val manufacturer: String?, val model: String?, val deviceType: Int?, val recordingMethod: Int,
    val steps: Long?, val exerciseType: Int?, val title: String?, val notes: String?) {
    fun record() = FitnessSourceRecord(externalId, FitnessRecordKind.valueOf(kind), sourcePackage,
        startAt, endAt, startOffsetSeconds, endOffsetSeconds, sourceModifiedAt, clientRecordId,
        clientRecordVersion, manufacturer, model, deviceType, recordingMethod, steps, exerciseType, title, notes)
    fun stored() = FitnessStoredRecord(record(), revision, visible, fingerprint)
    companion object {
        fun from(change: FitnessRecordChange, runId: String): HealthRecordVersionEntity {
            val r = change.record
            return HealthRecordVersionEntity(r.sourcePackage, r.kind.name, r.externalId, change.revision,
                runId, change.visible, change.reason, change.fingerprint, r.startAt, r.endAt,
                r.startOffsetSeconds, r.endOffsetSeconds, r.sourceModifiedAt, r.clientRecordId,
                r.clientRecordVersion, r.manufacturer, r.model, r.deviceType, r.recordingMethod,
                r.steps, r.exerciseType, r.title, r.notes)
        }
    }
}
