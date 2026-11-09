package de.ernaehrung.demo.data.fitness

/** Independent read-only source port. No Android, Room, food or network writer. */
internal interface FitnessHealthSource {
    val requiredPermissions: Set<String>
    fun availability(): Availability
    suspend fun grantedPermissions(): Set<String>
    suspend fun readSnapshot(): FitnessHealthSnapshot
    /** Separate opt-in query; the complete-window v1 import contract stays unchanged. */
    suspend fun readWorkoutDuration(query: FitnessWorkoutDurationQuery): FitnessWorkoutDurationSnapshot =
        throw UnsupportedOperationException("HEALTH_WORKOUT_DURATION_UNAVAILABLE")

    enum class Availability { AVAILABLE, INSTALL_OR_UPDATE_REQUIRED, UNSUPPORTED }
}

internal enum class FitnessRecordKind { STEPS, WORKOUT }

internal data class FitnessSourceRecord(
    val externalId: String, val kind: FitnessRecordKind, val sourcePackage: String,
    val startAt: Long, val endAt: Long, val startOffsetSeconds: Int?, val endOffsetSeconds: Int?,
    val sourceModifiedAt: Long, val clientRecordId: String?, val clientRecordVersion: Long,
    val manufacturer: String?, val model: String?, val deviceType: Int?, val recordingMethod: Int,
    val steps: Long? = null, val exerciseType: Int? = null, val title: String? = null, val notes: String? = null
)

/** Complete bounded rescan only after a Health Connect change-token barrier.
 * Missing records can be reconciled strictly inside this window; absence outside
 * it says nothing. The provider aggregate is never a sum of overlapping raw rows.
 */
internal data class FitnessHealthSnapshot(
    val sourcePackage: String, val windowStart: Long, val windowEnd: Long, val capturedAt: Long,
    val records: List<FitnessSourceRecord>, val day: String, val dayZone: String,
    val dayStart: Long, val dayEnd: Long, val aggregatedSteps: Long?
)

internal class FitnessPermissionMissing : IllegalStateException("HEALTH_PERMISSION_MISSING")
internal class FitnessSourceChanged : IllegalStateException("HEALTH_SOURCE_CHANGED_RETRY")
