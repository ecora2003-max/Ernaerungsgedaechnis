package de.ernaehrung.demo.data.local

import androidx.room.*
import de.ernaehrung.demo.data.fitness.*

/** Separate consent: the older Samsung steps/workouts consent never enables this scope. */
@Entity(tableName = "health_extra_state", foreignKeys = [ForeignKey(entity = HealthExtraRunEntity::class,
    parentColumns = ["id"], childColumns = ["lastSuccessRunId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("lastSuccessRunId")])
internal data class HealthExtraStateEntity(@PrimaryKey val id: String = "health-extra-v1",
    val enabled: Boolean = false, val authorizationRevision: Long = 0,
    val kinds: String = "", val windowDays: Int = 28, val endDay: String? = null,
    val historyOptIn: Boolean = false, val lastSuccessRunId: String? = null,
    val lastAttemptAt: Long? = null, val lastErrorCode: String? = null) {
    fun selection() = HealthExtraSelection(HealthExtraKernel.parseKinds(kinds), windowDays, endDay, historyOptIn)
}

/** An aggregate is the provider's single selected day, never a sum of raw phone/watch intervals. */
@Entity(tableName = "health_extra_runs")
internal data class HealthExtraRunEntity(@PrimaryKey val id: String, val kind: String,
    val fingerprint: String, val recordedAt: Long, val authorizationRevision: Long,
    val kinds: String? = null, val windowDays: Int? = null, val endDay: String? = null,
    val historyOptIn: Boolean? = null, val capturedAt: Long? = null,
    val windowStart: Long? = null, val windowEnd: Long? = null, val zone: String? = null,
    val day: String? = null, val dayStart: Long? = null, val dayEnd: Long? = null,
    val steps: Long? = null, val stepSources: String? = null,
    val changedRecords: Int = 0, val hiddenRecords: Int = 0)

@Entity(tableName = "health_extra_versions", primaryKeys = ["sourcePackage", "kind", "externalId", "revision"],
    foreignKeys = [ForeignKey(entity = HealthExtraRunEntity::class, parentColumns = ["id"],
        childColumns = ["runId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("runId")])
internal data class HealthExtraVersionEntity(val sourcePackage: String, val kind: String,
    val externalId: String, val revision: Long, val runId: String, val visible: Boolean,
    val reason: String, val fingerprint: String, val unit: String,
    val startAt: Long, val endAt: Long?, val startOffsetSeconds: Int?, val endOffsetSeconds: Int?,
    val sourceModifiedAt: Long, val clientRecordId: String?, val clientRecordVersion: Long,
    val manufacturer: String?, val model: String?, val deviceType: Int?, val recordingMethod: Int,
    val scalarBits: Long?) {
    fun stored(samples: List<HealthExtraSample>): HealthExtraStored {
        val type = HealthExtraKind.valueOf(kind)
        check(unit == type.unit) { "HEALTH_EXTRA_UNIT_INVALID" }
        return HealthExtraStored(HealthExtraRecord(sourcePackage, type, externalId, startAt, endAt,
            startOffsetSeconds, endOffsetSeconds, sourceModifiedAt, clientRecordId, clientRecordVersion,
            manufacturer, model, deviceType, recordingMethod, scalarBits, samples), revision, visible, reason, fingerprint)
    }
    companion object {
        fun from(c: HealthExtraChange, run: String): HealthExtraVersionEntity = c.record.let { r ->
            HealthExtraVersionEntity(r.sourcePackage, r.kind.name, r.externalId, c.revision, run, c.visible,
                c.reason, c.fingerprint, r.kind.unit, r.startAt, r.endAt, r.startOffsetSeconds, r.endOffsetSeconds,
                r.sourceModifiedAt, r.clientRecordId, r.clientRecordVersion, r.manufacturer, r.model,
                r.deviceType, r.recordingMethod, r.scalarBits)
        }
    }
}

@Entity(tableName = "health_extra_samples",
    primaryKeys = ["sourcePackage", "kind", "externalId", "revision", "position"], foreignKeys = [ForeignKey(
        entity = HealthExtraVersionEntity::class, parentColumns = ["sourcePackage", "kind", "externalId", "revision"],
        childColumns = ["sourcePackage", "kind", "externalId", "revision"], onDelete = ForeignKey.RESTRICT)])
internal data class HealthExtraSampleEntity(val sourcePackage: String, val kind: String, val externalId: String,
    val revision: Long, val position: Int, val at: Long, val doubleBits: Long?, val bpm: Long?) {
    fun sample() = HealthExtraSample(at, doubleBits, bpm)
}
