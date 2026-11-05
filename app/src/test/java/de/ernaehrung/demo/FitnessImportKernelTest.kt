package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class FitnessImportKernelTest {
    private fun stored(changes: List<FitnessRecordChange>) = changes.map {
        FitnessStoredRecord(it.record, it.revision, it.visible, it.fingerprint)
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
}
