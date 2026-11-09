package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.fitness.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal enum class WorkoutSourceWindowStatus { CURRENT, CHANGED, NOT_VISIBLE, MISSING }
internal data class WorkoutSourceWindowSnapshot(val status: WorkoutSourceWindowStatus,
    val workout: HealthRecordVersionEntity?, val extraState: HealthExtraStateEntity,
    val lastExtraRead: HealthExtraRunEntity?, val selection: WorkoutSourceSelection)

/** One SQLite snapshot of both independently imported ledgers. No provider,
 * permission request, writer, silent rebinding to a correction, or booking. */
internal class WorkoutSourceWindowReader(private val db: FoodDatabase,
    private val extraCache: suspend () -> HealthExtraCache) {
    fun observe(target: WorkoutSourceTarget) = db.invalidationTracker.createFlow(
        "health_source_record_versions", "health_import_state", "health_import_runs",
        "health_extra_state", "health_extra_runs", "health_extra_versions", "health_extra_samples").map {
        read(target)
    }.distinctUntilChanged()

    suspend fun read(target: WorkoutSourceTarget): WorkoutSourceWindowSnapshot = db.withTransaction {
        require(target.sourcePackage == FitnessHealthPolicy.SAMSUNG_PACKAGE && target.externalId.isNotBlank() &&
            target.revision > 0 && target.fingerprint.matches(Regex("[0-9a-f]{64}"))) { "HEALTH_CACHE_INVALID" }
        val row = db.healthDao().latestWorkout(target.sourcePackage, target.externalId)
        val extra = extraCache()
        val status = when {
            row == null -> WorkoutSourceWindowStatus.MISSING
            !row.visible -> WorkoutSourceWindowStatus.NOT_VISIBLE
            !WorkoutSourceWindow.current(target, row.stored()) -> WorkoutSourceWindowStatus.CHANGED
            else -> WorkoutSourceWindowStatus.CURRENT
        }
        val last = extra.lastSuccess
        val selection = if (status != WorkoutSourceWindowStatus.CURRENT) WorkoutSourceSelection(emptyList(), 0, 0)
            else WorkoutSourceWindow.select(requireNotNull(row).record(), extra.records,
                last?.kinds?.let(HealthExtraKernel::parseKinds).orEmpty(), last?.windowStart, last?.windowEnd)
        WorkoutSourceWindowSnapshot(status, row, extra.state, last, selection)
    }
}
