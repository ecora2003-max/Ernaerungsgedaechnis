package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class FitnessImportKernelTest {
    private fun stored(changes: List<FitnessRecordChange>) = changes.map {
        FitnessStoredRecord(it.record, it.revision, it.visible, it.fingerprint, it.reason)
    }

    @Test fun completeRescansVersionChangesHideOnlyMissingRowsAndAllowReappearanceWithoutDuplicates() {
        val snapshot = healthSnapshot()
        val original = FitnessImportKernel.changes(snapshot, emptyList(), HEALTH_AT)
        assertEquals(2, original.size)
        assertTrue(original.all { it.visible && it.revision == 1L })
        assertTrue(FitnessImportKernel.changes(snapshot, stored(original), HEALTH_AT).isEmpty())
        val changed = FitnessImportKernel.changes(snapshot.copy(records = listOf(healthSteps(0L))), stored(original), HEALTH_AT)
        assertEquals(2, changed.size)
        assertEquals(0L, changed.single { it.visible }.record.steps)
        assertEquals("NOT_VISIBLE_IN_WINDOW", changed.single { !it.visible }.reason)
        assertTrue(changed.all { it.revision == 2L })
        val restored = FitnessImportKernel.changes(snapshot.copy(records = listOf(healthSteps(0L), healthWorkout())), stored(changed), HEALTH_AT)
        assertEquals(1, restored.size)
        assertTrue(restored.single().visible)
        assertEquals(3L, restored.single().revision)
        assertEquals(healthWorkout(), restored.single().record)
    }

    @Test fun absenceOutsideOrAcrossTheWindowBoundaryNeverDeletesHistoricalEvidence() {
        val snapshot = healthSnapshot(records = emptyList())
        val old = healthSteps().copy(startAt = snapshot.windowStart - 100_000L,
            endAt = snapshot.windowStart - 1L)
        val boundary = healthSteps(id = "boundary").copy(startAt = snapshot.windowStart - 1L,
            endAt = snapshot.windowStart + 1_000L)
        val baseline = listOf(old, boundary).map { FitnessStoredRecord(it, 1L, true, FitnessImportKernel.recordFingerprint(it)) }
        assertTrue(FitnessImportKernel.changes(snapshot, baseline, HEALTH_AT).isEmpty())
        assertTrue(baseline.all { it.visible })
    }

    @Test fun malformedOrAmbiguousSnapshotsAbortTheWholePlan() {
        val good = healthSnapshot()
        val invalid = listOf(
            good.copy(records = listOf(healthSteps(), healthSteps())),
            good.copy(records = listOf(healthSteps().copy(sourcePackage = "foreign.app"))),
            good.copy(records = listOf(healthSteps(-1))),
            good.copy(records = listOf(healthSteps().copy(startOffsetSeconds = 64_801))),
            good.copy(records = listOf(healthWorkout().copy(steps = 1))),
            good.copy(aggregatedSteps = -1), good.copy(capturedAt = HEALTH_AT + 1),
            good.copy(windowStart = good.windowStart - 1))
        invalid.forEach { bad -> assertTrue(runCatching {
            FitnessImportKernel.changes(bad, emptyList(), HEALTH_AT)
        }.isFailure) }
        assertEquals(2, FitnessImportKernel.changes(good, emptyList(), HEALTH_AT).size)
    }

    @Test fun dstOffsetsUnknownMetadataAndKnownZeroArePreservedWithoutDayLengthAssumptions() {
        val zone = ZoneId.of("Europe/Berlin")
        val day = LocalDate.parse("2026-10-25")
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(25L, Duration.ofMillis(end - start).toHours())
        val record = healthSteps(0L).copy(startAt = start, endAt = end, sourceModifiedAt = end,
            startOffsetSeconds = 7_200, endOffsetSeconds = 3_600, manufacturer = null, model = null)
        val snapshot = FitnessHealthSnapshot(FitnessHealthPolicy.SAMSUNG_PACKAGE, start, end,
            end, listOf(record), day.toString(), zone.id, start, end, null)
        val change = FitnessImportKernel.changes(snapshot, emptyList(), end).single()
        assertEquals(record, change.record)
        assertEquals(0L, change.record.steps)
        assertNull(change.record.model)
        assertNull(snapshot.aggregatedSteps)
        FitnessImportKernel.validate(snapshot.copy(aggregatedSteps = 0L), end)
    }

    @Test fun laterEndedIntervalsAreDeferredUnchangedWhileCompletedRecordsStillImport() {
        val steps = healthSteps(9_999, "pending-steps").copy(endAt = HEALTH_AT + 60_000)
        val workout = healthWorkout().copy(externalId = "pending-workout", endAt = HEALTH_AT + 120_000)
        val boundary = healthSteps(0, "at-capture").copy(endAt = HEALTH_AT)
        val snapshot = healthSnapshot(records = listOf(healthWorkout(), steps, workout, boundary), aggregate = 123)
        val changes = FitnessImportKernel.changes(snapshot, emptyList(), HEALTH_AT)
        assertEquals(4, changes.size)
        assertEquals(setOf(steps, workout), changes.filterNot { it.visible }.map { it.record }.toSet())
        assertTrue(changes.filterNot { it.visible }.all { it.reason == "DEFERRED_END_AFTER_CAPTURE" })
        assertEquals(setOf(healthWorkout(), boundary), changes.filter { it.visible }.map { it.record }.toSet())
        assertEquals(123L, snapshot.aggregatedSteps)
    }

    @Test fun deferredRetriesAndLaterCompletionProduceOnlyOneVisibilityRevision() {
        val pending = healthSteps().copy(endAt = HEALTH_AT + 60_000)
        val snapshot = healthSnapshot(records = listOf(pending), aggregate = null)
        val first = FitnessImportKernel.changes(snapshot, emptyList(), HEALTH_AT)
        assertFalse(first.single().visible)
        assertTrue(FitnessImportKernel.changes(snapshot, stored(first), HEALTH_AT).isEmpty())
        val completed = healthSnapshot(HEALTH_AT + 60_000, listOf(pending), aggregate = 0)
        val released = FitnessImportKernel.changes(completed, stored(first), HEALTH_AT + 60_000).single()
        assertTrue(released.visible)
        assertEquals("SOURCE_READ", released.reason)
        assertEquals(2L, released.revision)
        assertEquals(pending, released.record)
        assertTrue(FitnessImportKernel.changes(completed, stored(listOf(released)), HEALTH_AT + 60_000).isEmpty())
    }

    @Test fun deferredAbsenceNeedsACompleteWindowAndIsNotRepeatedlyDeclaredMissing() {
        val pending = healthWorkout().copy(endAt = HEALTH_AT + 60_000)
        val first = FitnessImportKernel.changes(healthSnapshot(records = listOf(pending)), emptyList(), HEALTH_AT)
        assertTrue(FitnessImportKernel.changes(healthSnapshot(records = emptyList()), stored(first), HEALTH_AT).isEmpty())
        val later = healthSnapshot(HEALTH_AT + 60_000, emptyList())
        val missing = FitnessImportKernel.changes(later, stored(first), HEALTH_AT + 60_000).single()
        assertFalse(missing.visible)
        assertEquals("NOT_VISIBLE_IN_WINDOW", missing.reason)
        assertEquals(2L, missing.revision)
        assertTrue(FitnessImportKernel.changes(later, stored(listOf(missing)), HEALTH_AT + 60_000).isEmpty())
    }

    @Test fun deferralNeverRelaxesModifiedTimeOrOtherSourceValidation() {
        val pending = healthSteps().copy(endAt = HEALTH_AT + 60_000)
        listOf(pending.copy(sourceModifiedAt = HEALTH_AT + 1), pending.copy(startAt = HEALTH_AT + 1),
            pending.copy(steps = -1), pending.copy(sourcePackage = "foreign.app")).forEach { invalid ->
            assertTrue(runCatching { FitnessImportKernel.changes(
                healthSnapshot(records = listOf(healthWorkout(), invalid)), emptyList(), HEALTH_AT) }.isFailure)
        }
    }

    @Test fun sourceCorrectionCanDeferAPreviouslyCompletedRecordWithoutLosingItsIdentity() {
        val initial = FitnessImportKernel.changes(healthSnapshot(records = listOf(healthSteps())), emptyList(), HEALTH_AT)
        val correction = healthSteps(250).copy(endAt = HEALTH_AT + 60_000, clientRecordVersion = 1)
        val deferred = FitnessImportKernel.changes(healthSnapshot(HEALTH_AT + 1,
            listOf(correction)), stored(initial), HEALTH_AT + 1).single()
        assertEquals(2L, deferred.revision)
        assertFalse(deferred.visible)
        assertEquals(correction, deferred.record)
        assertEquals("DEFERRED_END_AFTER_CAPTURE", deferred.reason)
        assertTrue(FitnessImportKernel.changes(healthSnapshot(HEALTH_AT + 2, listOf(correction)),
            stored(listOf(deferred)), HEALTH_AT + 2).isEmpty())
    }
}
