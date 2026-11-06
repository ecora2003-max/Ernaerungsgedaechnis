package de.ernaehrung.demo.data.fitness

internal data class WorkoutIntervalKey(val source: String, val recordId: String)
internal data class WorkoutInterval(val key: WorkoutIntervalKey, val startAt: Long, val endAt: Long) {
    init { require(startAt < endAt) }
}

/** A possible temporal overlap is evidence to review, never an identity match
 * or a reason to add/drop source records or their energy. Endpoints are UTC;
 * adjacent half-open intervals do not overlap. Unknown legacy intervals are
 * deliberately absent instead of becoming a zero-duration observation.
 */
internal object WorkoutOverlaps {
    fun with(entry: WorkoutInterval, candidates: List<WorkoutInterval>): List<WorkoutIntervalKey> =
        candidates.filter { it.key != entry.key && it.startAt < entry.endAt && entry.startAt < it.endAt }
            .map { it.key }.distinct()
}
