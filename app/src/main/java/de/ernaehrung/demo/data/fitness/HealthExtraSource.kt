package de.ernaehrung.demo.data.fitness

/** Independently consented read-only supplement. Existing Samsung core/window
 * v1 and workout-window v1 ports keep their scopes and fingerprints unchanged. */
internal interface HealthExtraSource {
    fun availability(): FitnessHealthSource.Availability
    fun historyAvailable(): Boolean
    fun requiredPermissions(query: HealthExtraQuery): Set<String>
    suspend fun grantedPermissions(): Set<String>
    suspend fun readSnapshot(query: HealthExtraQuery): HealthExtraSnapshot
}

internal enum class HealthExtraKind(val label: String, val permission: String, val unit: String, val shape: Shape) {
    HEART_RATE("Herzfrequenz", "READ_HEART_RATE", "bpm", Shape.INTEGER_SERIES),
    DISTANCE("Distanz", "READ_DISTANCE", "m", Shape.INTERVAL),
    SPEED("Geschwindigkeit", "READ_SPEED", "m/s", Shape.DOUBLE_SERIES),
    ACTIVE_ENERGY("Aktivenergie", "READ_ACTIVE_CALORIES_BURNED", "kcal", Shape.INTERVAL),
    TOTAL_ENERGY("Gesamtenergie", "READ_TOTAL_CALORIES_BURNED", "kcal", Shape.INTERVAL),
    BODY_FAT("Körperfettanteil", "READ_BODY_FAT", "%", Shape.INSTANT),
    BASAL_RATE("Grundumsatzrate", "READ_BASAL_METABOLIC_RATE", "W", Shape.INSTANT),
    ALL_SOURCE_STEPS("Schritt-Tageswert über alle Quellen", "READ_STEPS", "Schritte", Shape.AGGREGATE);

    enum class Shape { INTEGER_SERIES, DOUBLE_SERIES, INTERVAL, INSTANT, AGGREGATE }
}

internal data class HealthExtraSelection(val kinds: Set<HealthExtraKind>, val windowDays: Int = 28,
    val endDay: String? = null, val historyOptIn: Boolean = false)
internal data class HealthExtraQuery(val selection: HealthExtraSelection, val requestedAt: Long,
    val windowStart: Long, val windowEnd: Long, val zone: String, val lastDay: String,
    val lastDayStart: Long, val needsHistory: Boolean)

/** SDK numbers are retained as their canonical-unit IEEE754 bits, including
 * known 0/-0; null is absence. No invented decimal precision or unit inference. */
internal data class HealthExtraSample(val at: Long, val doubleBits: Long? = null, val bpm: Long? = null)
internal data class HealthExtraRecord(val sourcePackage: String, val kind: HealthExtraKind, val externalId: String,
    val startAt: Long, val endAt: Long?, val startOffsetSeconds: Int?, val endOffsetSeconds: Int?,
    val sourceModifiedAt: Long, val clientRecordId: String?, val clientRecordVersion: Long,
    val manufacturer: String?, val model: String?, val deviceType: Int?, val recordingMethod: Int,
    val scalarBits: Long? = null, val samples: List<HealthExtraSample> = emptyList())
internal data class HealthExtraStepDay(val day: String, val zone: String, val startAt: Long,
    val endAt: Long, val steps: Long?, val sourcePackages: Set<String>)
internal data class HealthExtraSnapshot(val formatVersion: Int = 1, val query: HealthExtraQuery,
    val capturedAt: Long, val records: List<HealthExtraRecord>, val stepDay: HealthExtraStepDay? = null)
internal data class HealthExtraStored(val record: HealthExtraRecord, val revision: Long,
    val visible: Boolean, val reason: String, val fingerprint: String)
internal data class HealthExtraChange(val record: HealthExtraRecord, val revision: Long,
    val visible: Boolean, val reason: String, val fingerprint: String)
