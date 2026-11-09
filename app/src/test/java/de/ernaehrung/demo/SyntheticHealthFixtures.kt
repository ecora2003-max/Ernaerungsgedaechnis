package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.time.*
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic source fixtures only. These are never a Watch/Health Connect proof. */
internal val HEALTH_AT = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()
internal fun healthSteps(count: Long = 100L, id: String = "synthetic-steps") = FitnessSourceRecord(
    id, FitnessRecordKind.STEPS, FitnessHealthPolicy.SAMSUNG_PACKAGE, HEALTH_AT - 60_000L,
    HEALTH_AT - 1L, 3_600, 3_600, HEALTH_AT - 1L, "synthetic-client-steps", 1L,
    "Synthetic", "Test device only", null, 0, steps = count)
internal fun healthWorkout() = FitnessSourceRecord("synthetic-workout", FitnessRecordKind.WORKOUT,
    FitnessHealthPolicy.SAMSUNG_PACKAGE, HEALTH_AT - 3_600_000L, HEALTH_AT - 1_000L,
    null, null, HEALTH_AT - 1L, null, 0L, null, null, null, 0, exerciseType = 0,
    title = "Synthetisches Training", notes = "No real health data")
internal fun healthSnapshot(at: Long = HEALTH_AT,
    records: List<FitnessSourceRecord> = listOf(healthSteps(), healthWorkout()), aggregate: Long? = 123L): FitnessHealthSnapshot {
    val zone = ZoneId.of("Europe/Berlin")
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return FitnessHealthSnapshot(FitnessHealthPolicy.SAMSUNG_PACKAGE, at - Duration.ofDays(28).toMillis(), at,
        at, records, day.toString(), zone.id, day.atStartOfDay(zone).toInstant().toEpochMilli(), at, aggregate)
}
internal class SyntheticHealthSource(var snapshot: FitnessHealthSnapshot = healthSnapshot()) : FitnessHealthSource {
    override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
    var permissions = requiredPermissions
    var availabilityState = FitnessHealthSource.Availability.AVAILABLE
    var onRead: suspend () -> Unit = {}
    var permissionReply: ((Int) -> Set<String>)? = null
    val reads = AtomicInteger()
    val permissionChecks = AtomicInteger()
    val durationReads = AtomicInteger()
    var durationReply: (suspend (FitnessWorkoutDurationQuery) -> FitnessWorkoutDurationSnapshot)? = null
    override fun availability() = availabilityState
    override suspend fun grantedPermissions(): Set<String> =
        permissionReply?.invoke(permissionChecks.incrementAndGet()) ?: permissions
    override suspend fun readSnapshot(): FitnessHealthSnapshot {
        reads.incrementAndGet()
        onRead()
        return snapshot
    }
    override suspend fun readWorkoutDuration(query: FitnessWorkoutDurationQuery): FitnessWorkoutDurationSnapshot {
        durationReads.incrementAndGet()
        return requireNotNull(durationReply) { "No synthetic duration response installed" }.invoke(query)
    }
}
