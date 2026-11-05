package de.ernaehrung.demo.data.fitness

import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

internal object FitnessHealthPolicy {
    const val SAMSUNG_PACKAGE = "com.sec.android.app.shealth"
    const val MAX_RECORDS = 20_000
    val REQUIRED_PERMISSIONS = setOf("android.permission.health.READ_STEPS", "android.permission.health.READ_EXERCISE")
    val MAX_WINDOW_MILLIS: Long = Duration.ofDays(28).toMillis()
}

internal data class FitnessStoredRecord(val record: FitnessSourceRecord, val revision: Long,
    val visible: Boolean, val fingerprint: String)
internal data class FitnessRecordChange(val record: FitnessSourceRecord, val revision: Long,
    val visible: Boolean, val reason: String, val fingerprint: String)

/** Complete source windows only. Pure plan: no Room, Android, network or food write.
 * A missing row means NOT_VISIBLE_IN_WINDOW, not a claim that it was physically
 * deleted. Older/outside rows are retained and are not declared reconciled.
 */
internal object FitnessImportKernel {
    private fun key(record: FitnessSourceRecord) = record.kind.name to record.externalId

    fun fingerprint(vararg parts: Any?): String {
        fun encode(value: Any?): String = when (value) {
            null -> "N"
            is List<*> -> "L${value.size}[${value.joinToString("") { encode(it) }}]"
            else -> value.toString().let { "S${it.length}:$it" }
        }
        return MessageDigest.getInstance("SHA-256").digest(encode(parts.toList()).toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    fun recordFingerprint(r: FitnessSourceRecord) = fingerprint("HEALTH_SOURCE_RECORD_V1",
        r.externalId, r.kind.name, r.sourcePackage, r.startAt, r.endAt, r.startOffsetSeconds,
        r.endOffsetSeconds, r.sourceModifiedAt, r.clientRecordId, r.clientRecordVersion,
        r.manufacturer, r.model, r.deviceType, r.recordingMethod, r.steps, r.exerciseType, r.title, r.notes)

    fun validate(snapshot: FitnessHealthSnapshot, now: Long) {
        require(snapshot.sourcePackage == FitnessHealthPolicy.SAMSUNG_PACKAGE) { "HEALTH_SOURCE_SCOPE" }
        require(snapshot.windowStart >= 0L && snapshot.windowStart < snapshot.windowEnd &&
            snapshot.windowEnd <= snapshot.capturedAt && snapshot.capturedAt <= now &&
            snapshot.windowEnd - snapshot.windowStart <= FitnessHealthPolicy.MAX_WINDOW_MILLIS) { "HEALTH_WINDOW_INVALID" }
        require(snapshot.records.size <= FitnessHealthPolicy.MAX_RECORDS) { "HEALTH_IMPORT_LIMIT" }
        require(snapshot.records.map(::key).toSet().size == snapshot.records.size) { "HEALTH_DUPLICATE_SOURCE_ID" }
        val zone = ZoneId.of(snapshot.dayZone)
        val day = LocalDate.parse(snapshot.day)
        require(day.toString() == snapshot.day && snapshot.dayStart == day.atStartOfDay(zone).toInstant().toEpochMilli() &&
            snapshot.dayEnd > snapshot.dayStart && snapshot.dayEnd <= snapshot.windowEnd &&
            snapshot.dayEnd <= day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()) { "HEALTH_DAY_INVALID" }
        require(snapshot.aggregatedSteps == null || snapshot.aggregatedSteps >= 0L) { "HEALTH_AGGREGATE_INVALID" }
        snapshot.records.forEach { r ->
            require(r.sourcePackage == snapshot.sourcePackage && r.externalId.isNotBlank()) { "HEALTH_SOURCE_SCOPE" }
            require(r.startAt >= 0L && r.startAt < r.endAt && r.endAt <= snapshot.capturedAt &&
                r.startAt < snapshot.windowEnd && r.endAt > snapshot.windowStart &&
                r.sourceModifiedAt in 0L..snapshot.capturedAt && r.clientRecordVersion >= 0L) { "HEALTH_RECORD_TIME_INVALID" }
            require(listOf(r.startOffsetSeconds, r.endOffsetSeconds).all { it == null || it in -64_800..64_800 }) { "HEALTH_OFFSET_INVALID" }
            require(r.recordingMethod >= 0 && (r.deviceType == null || r.deviceType >= 0)) { "HEALTH_METADATA_INVALID" }
            when (r.kind) {
                FitnessRecordKind.STEPS -> require(r.steps != null && r.steps >= 0L &&
                    r.exerciseType == null && r.title == null && r.notes == null) { "HEALTH_STEP_INVALID" }
                FitnessRecordKind.WORKOUT -> require(r.steps == null && r.exerciseType != null &&
                    r.exerciseType >= 0) { "HEALTH_WORKOUT_INVALID" }
            }
        }
    }

    fun changes(snapshot: FitnessHealthSnapshot, existing: List<FitnessStoredRecord>, now: Long): List<FitnessRecordChange> {
        validate(snapshot, now)
        require(existing.map { key(it.record) }.toSet().size == existing.size) { "HEALTH_CACHE_AMBIGUOUS" }
        require(existing.all { it.revision > 0L && it.record.sourcePackage == snapshot.sourcePackage &&
            it.fingerprint == recordFingerprint(it.record) }) { "HEALTH_CACHE_INVALID" }
        val previous = existing.associateBy { key(it.record) }
        val incoming = snapshot.records.associateBy(::key)
        val result = mutableListOf<FitnessRecordChange>()
        fun next(old: FitnessStoredRecord?) = if (old == null) 1L else {
            require(old.revision < Long.MAX_VALUE) { "HEALTH_REVISION_LIMIT" }
            old.revision + 1L
        }
        incoming.forEach { (key, r) ->
            val old = previous[key]
            val digest = recordFingerprint(r)
            if (old == null || !old.visible || old.fingerprint != digest)
                result += FitnessRecordChange(r, next(old), true, "SOURCE_READ", digest)
        }
        previous.forEach { (key, old) ->
            if (old.visible && key !in incoming && old.record.startAt >= snapshot.windowStart &&
                old.record.endAt <= snapshot.windowEnd)
                result += FitnessRecordChange(old.record, next(old), false, "NOT_VISIBLE_IN_WINDOW", old.fingerprint)
        }
        return result.sortedWith(compareBy({ it.record.kind.name }, { it.record.externalId }))
    }
}
