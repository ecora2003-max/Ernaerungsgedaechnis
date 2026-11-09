package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import org.junit.Assert.*
import org.junit.Test

class FitnessWorkoutDurationPolicyTest {
    private val row = healthWorkout()
    private val target = FitnessWorkoutDurationTarget(row.externalId, 1, FitnessImportKernel.recordFingerprint(row))
    private val query = FitnessWorkoutDurationQuery(target, row.startAt, row.endAt, HEALTH_AT)
    private fun snapshot(value: Long? = 51_125, records: List<FitnessSourceRecord> = listOf(row),
        status: FitnessWorkoutDurationStatus = if (value == null) FitnessWorkoutDurationStatus.NOT_RETURNED else FitnessWorkoutDurationStatus.AVAILABLE) =
        FitnessWorkoutDurationSnapshot(sourcePackage = FitnessHealthPolicy.SAMSUNG_PACKAGE, windowStart = row.startAt,
            windowEnd = row.endAt, capturedAt = HEALTH_AT, records = records, durationMillis = value, status = status)
    private fun reject(snapshot: FitnessWorkoutDurationSnapshot) =
        assertTrue(runCatching { FitnessWorkoutDurationPolicy.validate(query, snapshot, HEALTH_AT) }.isFailure)

    @Test fun exactProviderMillisKnownZeroAndAbsenceAreDistinctFromOriginalInterval() {
        listOf(0L, 51_125L, row.endAt - row.startAt, null).forEach {
            FitnessWorkoutDurationPolicy.validate(query, snapshot(it), HEALTH_AT)
            assertEquals(it, snapshot(it).durationMillis)
        }
        assertTrue(FitnessWorkoutDurationPolicy.inReadWindow(query))
        assertNull(FitnessWorkoutDurationPolicy.exclusion(query, listOf(row), HEALTH_AT))
    }
    @Test fun missingOrChangedSourceCannotReceiveAnAggregate() {
        FitnessWorkoutDurationPolicy.validate(query, snapshot(null, emptyList(), FitnessWorkoutDurationStatus.SOURCE_MISSING), HEALTH_AT)
        FitnessWorkoutDurationPolicy.validate(query, snapshot(null, listOf(row.copy(title = "Corrected")), FitnessWorkoutDurationStatus.SOURCE_CHANGED), HEALTH_AT)
        reject(snapshot(0, emptyList())); reject(snapshot(0, listOf(row.copy(clientRecordVersion = 2))))
    }
    @Test fun evenAnUnfinishedOverlappingWorkoutPreventsSingleSessionAttribution() {
        val pending = row.copy(externalId = "pending", endAt = HEALTH_AT + 60_000)
        val records = listOf(row, pending)
        assertEquals(FitnessWorkoutDurationStatus.OVERLAPPING_WORKOUTS, FitnessWorkoutDurationPolicy.exclusion(query, records, HEALTH_AT))
        FitnessWorkoutDurationPolicy.validate(query, snapshot(null, records, FitnessWorkoutDurationStatus.OVERLAPPING_WORKOUTS), HEALTH_AT)
        reject(snapshot(10, records))
        val earlier = row.copy(externalId = "earlier-start", startAt = row.startAt - 60_000)
        assertEquals(FitnessWorkoutDurationStatus.OVERLAPPING_WORKOUTS, FitnessWorkoutDurationPolicy.exclusion(query, listOf(row, earlier), HEALTH_AT))
        val adjacent = earlier.copy(endAt = row.startAt)
        FitnessWorkoutDurationPolicy.validate(query, snapshot(0, listOf(adjacent, row)), HEALTH_AT)
    }
    @Test fun foreignRecordsStepsDuplicatesAndOutOfWindowMetadataAreRejected() {
        listOf(listOf(row, row), listOf(row.copy(sourcePackage = "foreign")), listOf(healthSteps()),
            listOf(row.copy(startAt = row.endAt + 1, endAt = row.endAt + 2)),
            listOf(row.copy(sourceModifiedAt = HEALTH_AT + 1)), listOf(row.copy(endOffsetSeconds = 70_000))).forEach {
            reject(snapshot(0, it))
        }
    }
    @Test fun contractScopeFreshnessAndDurationShapeAreIndependentlyVerified() {
        listOf(snapshot(-1), snapshot(row.endAt - row.startAt + 1), snapshot().copy(formatVersion = 2),
            snapshot().copy(sourcePackage = "foreign"), snapshot().copy(windowStart = row.startAt + 1),
            snapshot().copy(capturedAt = HEALTH_AT - 1), snapshot().copy(capturedAt = HEALTH_AT + 1),
            snapshot(null).copy(status = FitnessWorkoutDurationStatus.AVAILABLE),
            snapshot(0).copy(status = FitnessWorkoutDurationStatus.READ_FAILED)).forEach(::reject)
    }
    @Test fun fullIntervalMustFitTheExistingTwentyEightDayReadPermission() {
        val cutoff = query.startAt + FitnessHealthPolicy.MAX_WINDOW_MILLIS
        assertTrue(FitnessWorkoutDurationPolicy.inReadWindow(query.copy(requestedAt = cutoff)))
        assertFalse(FitnessWorkoutDurationPolicy.inReadWindow(query.copy(requestedAt = cutoff + 1)))
        assertFalse(FitnessWorkoutDurationPolicy.inReadWindow(query.copy(requestedAt = row.endAt - 1)))
    }
}
