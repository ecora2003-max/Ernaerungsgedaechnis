package de.ernaehrung.demo.data.fitness

/** This contract does not change HEALTH_WINDOW_V1 or its raw record fingerprint. */
internal data class FitnessWorkoutDurationTarget(val externalId: String, val revision: Long, val fingerprint: String)
internal data class FitnessWorkoutDurationQuery(val target: FitnessWorkoutDurationTarget,
    val startAt: Long, val endAt: Long, val requestedAt: Long)
internal enum class FitnessWorkoutDurationStatus {
    AVAILABLE, NOT_RETURNED, SOURCE_CHANGED, SOURCE_MISSING, OVERLAPPING_WORKOUTS,
    OUTSIDE_READ_WINDOW, READ_FAILED
}
internal data class FitnessWorkoutDurationSnapshot(val formatVersion: Int = 1,
    val sourcePackage: String, val windowStart: Long, val windowEnd: Long, val capturedAt: Long,
    val records: List<FitnessSourceRecord>, val durationMillis: Long?, val status: FitnessWorkoutDurationStatus)

/** Provider exercise time for the selected raw interval, bound to its current
 * local version. This is a WINDOW aggregate, not an individual-session active
 * duration. Records reads filter by start, aggregates by overlap (HC platform).
 * Scan the whole existing 28-day scope to detect earlier overlapping sessions;
 * older inaccessible intervals still cannot be excluded. Never assert single-
 * session attribution or subtract optional pause segments. Zero is not null.
 */
internal object FitnessWorkoutDurationPolicy {
    const val RUN_KIND = "WORKOUT_DURATION_V1"
    fun recordsWindowStart(query: FitnessWorkoutDurationQuery) =
        (query.requestedAt - FitnessHealthPolicy.MAX_WINDOW_MILLIS).coerceAtLeast(0L)
    fun inReadWindow(query: FitnessWorkoutDurationQuery): Boolean {
        require(query.target.externalId.isNotBlank() && query.target.revision > 0 &&
            query.target.fingerprint.matches(Regex("[0-9a-f]{64}"))) { "HEALTH_CACHE_INVALID" }
        require(query.startAt >= 0L && query.startAt < query.endAt && query.requestedAt >= 0L) { "HEALTH_WINDOW_INVALID" }
        return query.endAt <= query.requestedAt &&
            query.startAt >= recordsWindowStart(query)
    }

    /** null means a provider aggregate may be read; a reason means no attribution. */
    fun exclusion(query: FitnessWorkoutDurationQuery, records: List<FitnessSourceRecord>, capturedAt: Long): FitnessWorkoutDurationStatus? {
        require(inReadWindow(query)) { "HEALTH_WINDOW_INVALID" }
        require(records.size <= FitnessHealthPolicy.MAX_RECORDS) { "HEALTH_IMPORT_LIMIT" }
        require(records.map { it.externalId }.toSet().size == records.size) { "HEALTH_DUPLICATE_SOURCE_ID" }
        records.forEach {
            require(it.kind == FitnessRecordKind.WORKOUT) { "HEALTH_WORKOUT_INVALID" }
            FitnessImportKernel.validateRecord(it, FitnessHealthPolicy.SAMSUNG_PACKAGE, recordsWindowStart(query), query.endAt, capturedAt)
            require(it.startAt >= recordsWindowStart(query)) { "HEALTH_RECORD_OUTSIDE_WINDOW" }
        }
        val target = records.find { it.externalId == query.target.externalId }
            ?: return FitnessWorkoutDurationStatus.SOURCE_MISSING
        if (FitnessImportKernel.recordFingerprint(target) != query.target.fingerprint ||
            target.startAt != query.startAt || target.endAt != query.endAt)
            return FitnessWorkoutDurationStatus.SOURCE_CHANGED
        if (records.any { it.externalId != target.externalId && it.endAt > query.startAt })
            return FitnessWorkoutDurationStatus.OVERLAPPING_WORKOUTS
        return null
    }

    fun validate(query: FitnessWorkoutDurationQuery, snapshot: FitnessWorkoutDurationSnapshot, now: Long) {
        require(snapshot.formatVersion == 1 && snapshot.sourcePackage == FitnessHealthPolicy.SAMSUNG_PACKAGE) { "HEALTH_SOURCE_SCOPE" }
        require(snapshot.windowStart == query.startAt && snapshot.windowEnd == query.endAt &&
            snapshot.capturedAt in query.requestedAt..now) { "HEALTH_WINDOW_INVALID" }
        val excluded = exclusion(query, snapshot.records, snapshot.capturedAt)
        if (excluded != null) require(snapshot.status == excluded && snapshot.durationMillis == null) { "HEALTH_AGGREGATE_INVALID" }
        else if (snapshot.durationMillis == null)
            require(snapshot.status == FitnessWorkoutDurationStatus.NOT_RETURNED) { "HEALTH_AGGREGATE_INVALID" }
        else require(snapshot.status == FitnessWorkoutDurationStatus.AVAILABLE &&
            snapshot.durationMillis in 0L..(query.endAt - query.startAt)) { "HEALTH_AGGREGATE_INVALID" }
    }
}
