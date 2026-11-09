package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutSourceWindowTest {
    private val workout = FitnessSourceRecord("synthetic-workout", FitnessRecordKind.WORKOUT, "test.origin",
        1000, 2000, null, null, 3000, null, 0, null, null, null, 0, exerciseType = 0)
    private fun row(kind: HealthExtraKind, start: Long = 1000, end: Long? = 2000,
        origin: String = workout.sourcePackage, value: Double? = 0.0, samples: List<HealthExtraSample> = emptyList(),
        visible: Boolean = true, reason: String = FitnessRecordVisibility.SOURCE_READ): HealthExtraStored {
        val r = HealthExtraRecord(origin, kind, "synthetic-$kind-$origin-$start", start, end, null, null, 3000,
            null, 0, null, null, null, 0, value?.toRawBits(), samples)
        return HealthExtraStored(r, 1, visible, reason, HealthExtraKernel.recordFingerprint(r))
    }
    @Test fun exactAndContainedScalarIntervalsKeepRawKnownZeroUnitsAndNeverSumEnergyKinds() {
        val distance = row(HealthExtraKind.DISTANCE)
        val active = row(HealthExtraKind.ACTIVE_ENERGY, 1200, 1400, value = 1.25)
        val total = row(HealthExtraKind.TOTAL_ENERGY, value = 2.75)
        val result = WorkoutSourceWindow.select(workout, listOf(distance, active, total))
        assertEquals(3, result.matches.size)
        assertEquals(0L, result.matches.single { it.observation.record.kind == HealthExtraKind.DISTANCE }.scalarInWindow)
        assertEquals(WorkoutSourceRelation.WITHIN, result.matches.single { it.observation == active }.relation)
        assertEquals(WorkoutSourceRelation.SAME, result.matches.single { it.observation == total }.relation)
        assertEquals(listOf(distance, active, total).map { it.record }.toSet(), result.matches.map { it.observation.record }.toSet())
    }
    @Test fun widerEnergyOrDistanceNeverBecomesAWorkoutScalarOrGetsProrated() {
        val fullDay = row(HealthExtraKind.TOTAL_ENERGY, 0, 86_400_000, value = 2100.5)
        val distance = row(HealthExtraKind.DISTANCE, 500, 1500, value = 12.75)
        val matches = WorkoutSourceWindow.select(workout, listOf(fullDay, distance)).matches
        assertTrue(matches.all { it.relation == WorkoutSourceRelation.WIDER && it.scalarInWindow == null })
        assertEquals(2100.5.toRawBits(), matches.single { it.observation == fullDay }.observation.record.scalarBits)
    }
    @Test fun seriesSelectionIsHalfOpenAndKeepsOriginalIndicesOrderBitsAndFullParentFingerprint() {
        val samples = listOf(HealthExtraSample(500, bpm = 66), HealthExtraSample(1000, bpm = 71),
            HealthExtraSample(1500, bpm = 89), HealthExtraSample(2000, bpm = 100), HealthExtraSample(2500, bpm = 90))
        val raw = row(HealthExtraKind.HEART_RATE, 500, 2500, value = null, samples = samples)
        val selected = WorkoutSourceWindow.select(workout, listOf(raw)).matches.single()
        assertEquals(listOf(1, 2), selected.sampleIndices)
        assertEquals(listOf(71L, 89L), selected.selectedSamples.map { it.bpm })
        assertEquals(raw, selected.observation); assertEquals(samples, selected.observation.record.samples)
        assertEquals(HealthExtraKernel.recordFingerprint(raw.record), selected.observation.fingerprint)
        val speed = row(HealthExtraKind.SPEED, value = null, samples = listOf(HealthExtraSample(1500, (-0.0).toRawBits())))
        assertEquals((-0.0).toRawBits(), WorkoutSourceWindow.select(workout, listOf(speed)).matches.single().selectedSamples.single().doubleBits)
    }
    @Test fun foreignOriginsInstantMeasurementsNonoverlapAndUnfinishedParentsCannotSupplyValues() {
        val outside = listOf(row(HealthExtraKind.ACTIVE_ENERGY, origin = "test.phone"),
            row(HealthExtraKind.BODY_FAT, 1500, null), row(HealthExtraKind.BASAL_RATE, 1500, null),
            row(HealthExtraKind.DISTANCE, 500, 1000), row(HealthExtraKind.DISTANCE, 2000, 3000))
        val deferred = row(HealthExtraKind.SPEED, 500, 4000, value = null,
            samples = listOf(HealthExtraSample(1500, 1.125.toRawBits())), visible = false,
            reason = FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE)
        val missing = row(HealthExtraKind.DISTANCE, visible = false, reason = FitnessRecordVisibility.NOT_VISIBLE_IN_WINDOW)
        val r = WorkoutSourceWindow.select(workout, outside + deferred + missing)
        assertTrue(r.matches.isEmpty()); assertEquals(1, r.deferred); assertEquals(1, r.noLongerVisible)
    }
    @Test fun lastReadCoverageIsPerTypeAndFullParentIntervalRatherThanLastGlobalTimestamp() {
        val distance = row(HealthExtraKind.DISTANCE)
        val wider = row(HealthExtraKind.HEART_RATE, 500, 2500, value = null)
        val r = WorkoutSourceWindow.select(workout, listOf(distance, wider), setOf(HealthExtraKind.DISTANCE), 1000, 2000)
        assertTrue(r.matches.single { it.observation == distance }.checkedInLastExtraWindow)
        assertFalse(r.matches.single { it.observation == wider }.checkedInLastExtraWindow)
        assertFalse(WorkoutSourceWindow.select(workout, listOf(distance), setOf(HealthExtraKind.DISTANCE), 1100, 2000)
            .matches.single().checkedInLastExtraWindow)
    }
    @Test fun sourceCorrectionsAndHiddenWorkoutsCannotSilentlyReuseAnOldTarget() {
        val fingerprint = FitnessImportKernel.recordFingerprint(workout)
        val original = FitnessStoredRecord(workout, 1, true, fingerprint)
        val target = WorkoutSourceTarget(workout.sourcePackage, workout.externalId, 1, fingerprint)
        assertTrue(WorkoutSourceWindow.current(target, original))
        assertFalse(WorkoutSourceWindow.current(target, original.copy(visible = false)))
        assertFalse(WorkoutSourceWindow.current(target, original.copy(revision = 2)))
        assertFalse(WorkoutSourceWindow.current(target, original.copy(record = workout.copy(title = "Synthetic correction"))))
        val bad = row(HealthExtraKind.DISTANCE).copy(fingerprint = "bad")
        assertTrue(runCatching { WorkoutSourceWindow.select(workout, listOf(bad)) }.isFailure)
    }
}
