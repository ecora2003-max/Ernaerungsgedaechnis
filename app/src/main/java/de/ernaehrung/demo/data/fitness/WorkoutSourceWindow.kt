package de.ernaehrung.demo.data.fitness

/** Read-only selection, not workout attribution or an energy/duration model. */
internal data class WorkoutSourceTarget(val sourcePackage: String, val externalId: String,
    val revision: Long, val fingerprint: String)
internal enum class WorkoutSourceRelation(val label: String) {
    SAME("Identischer Quellzeitraum"), WITHIN("Teilintervall innerhalb des Quellzeitfensters"),
    WIDER("Originalintervall reicht über das Quellzeitfenster hinaus")
}
internal data class WorkoutSourceMatch(val observation: HealthExtraStored, val relation: WorkoutSourceRelation,
    val sampleIndices: List<Int>, val checkedInLastExtraWindow: Boolean) {
    /** A scalar over a larger interval cannot become this workout's value. */
    val scalarInWindow get() = observation.record.scalarBits?.takeIf { relation != WorkoutSourceRelation.WIDER }
    val selectedSamples get() = sampleIndices.map { observation.record.samples[it] }
}
internal data class WorkoutSourceSelection(val matches: List<WorkoutSourceMatch>, val deferred: Int,
    val noLongerVisible: Int)

internal object WorkoutSourceWindow {
    const val METHOD_VERSION = "HEALTH_WORKOUT_SOURCE_WINDOW_V1"
    val TYPES = setOf(HealthExtraKind.HEART_RATE, HealthExtraKind.SPEED, HealthExtraKind.DISTANCE,
        HealthExtraKind.ACTIVE_ENERGY, HealthExtraKind.TOTAL_ENERGY)

    fun current(target: WorkoutSourceTarget, workout: FitnessStoredRecord): Boolean =
        workout.visible && workout.record.kind == FitnessRecordKind.WORKOUT &&
            workout.record.sourcePackage == target.sourcePackage && workout.record.externalId == target.externalId &&
            workout.revision == target.revision && workout.fingerprint == target.fingerprint &&
            FitnessImportKernel.recordFingerprint(workout.record) == target.fingerprint

    /** Half-open time window, same actual origin, current local versions only.
     * Raw samples/order/units/fingerprints never change. No sums, means, clipping
     * of scalar intervals, active time, intensity, or causal association. */
    fun select(workout: FitnessSourceRecord, observations: List<HealthExtraStored>,
        lastKinds: Set<HealthExtraKind> = emptySet(), lastStart: Long? = null, lastEnd: Long? = null): WorkoutSourceSelection {
        require(workout.kind == FitnessRecordKind.WORKOUT && workout.startAt >= 0 && workout.endAt > workout.startAt)
        val eligible = observations.filter { row ->
            val r = row.record
            r.sourcePackage == workout.sourcePackage && r.kind in TYPES && r.endAt != null &&
                r.startAt < workout.endAt && r.endAt > workout.startAt
        }
        require(eligible.all { it.revision > 0 && it.fingerprint == HealthExtraKernel.recordFingerprint(it.record) }) { "HEALTH_CACHE_INVALID" }
        val matches = eligible.filter { it.visible }.map { row ->
            val r = row.record
            val relation = when {
                r.startAt == workout.startAt && r.endAt == workout.endAt -> WorkoutSourceRelation.SAME
                r.startAt >= workout.startAt && requireNotNull(r.endAt) <= workout.endAt -> WorkoutSourceRelation.WITHIN
                else -> WorkoutSourceRelation.WIDER
            }
            val indices = r.samples.indices.filter { r.samples[it].at >= workout.startAt && r.samples[it].at < workout.endAt }
            val checked = r.kind in lastKinds && lastStart != null && lastEnd != null &&
                r.startAt >= lastStart && requireNotNull(r.endAt) <= lastEnd
            WorkoutSourceMatch(row, relation, indices, checked)
        }.sortedWith(compareBy({ it.observation.record.kind.ordinal }, { it.observation.record.startAt }, { it.observation.record.externalId }))
        return WorkoutSourceSelection(matches, eligible.count { it.reason == FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE },
            eligible.count { it.reason == FitnessRecordVisibility.NOT_VISIBLE_IN_WINDOW })
    }
}
