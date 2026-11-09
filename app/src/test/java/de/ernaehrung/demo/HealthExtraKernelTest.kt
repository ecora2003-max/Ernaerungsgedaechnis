package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic values and sources only, never copies of user health screenshots. */
class HealthExtraKernelTest {
    private val at = Instant.parse("2025-03-30T16:00:00Z").toEpochMilli()
    private val selection = HealthExtraSelection(HealthExtraKind.entries.toSet())
    private val query = HealthExtraKernel.plan(selection, at, "Europe/Berlin")
    private fun row(kind: HealthExtraKind, value: Double = 0.0, source: String = "test.scale") = HealthExtraRecord(
        source, kind, "synthetic-$kind", at - 30_000, if (kind.shape == HealthExtraKind.Shape.INSTANT) null else at - 1,
        3600, if (kind.shape == HealthExtraKind.Shape.INSTANT) null else 7200, at - 1,
        "synthetic-client", 1, null, null, null, 0,
        if (kind.shape in setOf(HealthExtraKind.Shape.INSTANT, HealthExtraKind.Shape.INTERVAL)) value.toRawBits() else null,
        when (kind) {
            HealthExtraKind.HEART_RATE -> listOf(HealthExtraSample(at - 20_000, bpm = 73))
            HealthExtraKind.SPEED -> listOf(HealthExtraSample(at - 20_000, doubleBits = value.toRawBits()))
            else -> emptyList()
        })
    private fun snapshot(records: List<HealthExtraRecord> = emptyList(), value: Long? = null, q: HealthExtraQuery = query) =
        HealthExtraSnapshot(query = q, capturedAt = q.requestedAt, records = records,
            stepDay = if (HealthExtraKind.ALL_SOURCE_STEPS in q.selection.kinds) HealthExtraStepDay(q.lastDay, q.zone,
                q.lastDayStart, q.windowEnd, value, if (value == null) emptySet() else setOf("test.phone", "test.watch")) else null)
    private fun stored(c: HealthExtraChange) = HealthExtraStored(c.record, c.revision, c.visible, c.reason, c.fingerprint)
    private fun reject(s: HealthExtraSnapshot, q: HealthExtraQuery = query) = assertTrue(runCatching {
        HealthExtraKernel.validate(s, q, s.capturedAt)
    }.isFailure)

    @Test fun calendarWindowsRetainZoneAndDaylightSavingWithoutChangingTheCoreV1Scope() {
        assertEquals("2025-03-30", query.lastDay)
        assertEquals(Instant.parse("2025-03-02T23:00:00Z").toEpochMilli(), query.windowStart)
        assertEquals(Instant.parse("2025-03-29T23:00:00Z").toEpochMilli(), query.lastDayStart)
        assertFalse(query.needsHistory)
        assertEquals(8, HealthExtraKernel.permissions(query).size)
        assertFalse(HealthExtraKernel.HISTORY_PERMISSION in HealthExtraKernel.permissions(query))
        assertEquals(setOf("android.permission.health.READ_STEPS", "android.permission.health.READ_EXERCISE"), FitnessHealthPolicy.REQUIRED_PERMISSIONS)
        val serialized = HealthExtraKernel.kindsText(selection.kinds)
        assertEquals(selection.kinds, HealthExtraKernel.parseKinds(serialized))
        assertTrue(runCatching { HealthExtraKernel.parseKinds("SPEED,SPEED") }.isFailure)
    }
    @Test fun olderDataRequiresSeparateConsentAndOnlyActuallyNeededHistoryPermission() {
        assertTrue(runCatching { HealthExtraKernel.plan(selection.copy(windowDays = 90), at, query.zone) }.isFailure)
        val old = HealthExtraKernel.plan(selection.copy(windowDays = 90, historyOptIn = true), at, query.zone)
        assertTrue(old.needsHistory); assertTrue(HealthExtraKernel.HISTORY_PERMISSION in HealthExtraKernel.permissions(old))
        val stepsOnly = HealthExtraKernel.plan(HealthExtraSelection(setOf(HealthExtraKind.ALL_SOURCE_STEPS), 90), at, query.zone)
        assertFalse(stepsOnly.needsHistory); assertEquals(setOf("android.permission.health.READ_STEPS"), HealthExtraKernel.permissions(stepsOnly))
        val earlier = HealthExtraKernel.plan(selection.copy(endDay = "2024-11-12", historyOptIn = true), at, query.zone)
        assertEquals("2024-11-12", earlier.lastDay); assertTrue(earlier.needsHistory)
        assertTrue(runCatching { HealthExtraKernel.plan(selection.copy(endDay = "2025-03-31"), at, query.zone) }.isFailure)
    }
    @Test fun rawCanonicalZeroMinusZeroUnitsAndSourceOriginsAreNotRewrittenOrAdded() {
        val rows = selection.kinds.filter { it.shape != HealthExtraKind.Shape.AGGREGATE }.map { row(it) }
        val snap = snapshot(rows, 19)
        HealthExtraKernel.validate(snap, query, at)
        assertEquals(19L, snap.stepDay!!.steps)
        val energy = row(HealthExtraKind.ACTIVE_ENERGY, 1.25, "test.phone")
        val total = energy.copy(kind = HealthExtraKind.TOTAL_ENERGY)
        assertNotEquals(HealthExtraKernel.recordFingerprint(energy), HealthExtraKernel.recordFingerprint(total))
        val other = energy.copy(sourcePackage = "test.watch")
        HealthExtraKernel.validate(snapshot(listOf(energy, other)), query, at)
        assertEquals("W", HealthExtraKind.BASAL_RATE.unit)
        assertEquals("kcal", HealthExtraKind.TOTAL_ENERGY.unit)
        val zero = row(HealthExtraKind.BASAL_RATE, 0.0)
        assertNotEquals(HealthExtraKernel.recordFingerprint(zero), HealthExtraKernel.recordFingerprint(zero.copy(scalarBits = (-0.0).toRawBits())))
        assertEquals(0L, zero.scalarBits)
        reject(snapshot(listOf(zero.copy(scalarBits = null))))
    }
    @Test fun providerStepAbsenceKnownZeroAndUnrequestedScopeRemainDifferent() {
        HealthExtraKernel.validate(snapshot(value = null), query, at)
        HealthExtraKernel.validate(snapshot(value = 0), query, at)
        assertNotEquals(HealthExtraKernel.fingerprint(snapshot(value = null)), HealthExtraKernel.fingerprint(snapshot(value = 0)))
        reject(snapshot(value = -1))
        reject(snapshot().copy(stepDay = null))
        val q = HealthExtraKernel.plan(HealthExtraSelection(setOf(HealthExtraKind.BODY_FAT)), at, query.zone)
        HealthExtraKernel.validate(snapshot(q = q), q, at)
        reject(snapshot().copy(query = q), q)
    }
    @Test fun futureIntervalsAndSamplesStayOriginalDeferredThenReleaseExactlyOnce() {
        val pending = row(HealthExtraKind.SPEED).copy(endAt = at + 30_000,
            samples = listOf(HealthExtraSample(at + 5_000, doubleBits = 2.25.toRawBits())))
        val first = HealthExtraKernel.changes(snapshot(listOf(pending)), query, emptyList(), at).single()
        assertFalse(first.visible); assertEquals(FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE, first.reason)
        assertEquals(pending, first.record)
        assertTrue(HealthExtraKernel.changes(snapshot(listOf(pending)), query, listOf(stored(first)), at).isEmpty())
        val later = HealthExtraKernel.plan(selection, at + 60_000, query.zone)
        val next = HealthExtraKernel.changes(snapshot(listOf(pending), q = later), later, listOf(stored(first)), later.requestedAt).single()
        assertTrue(next.visible); assertEquals(2L, next.revision); assertEquals(pending, next.record)
        assertTrue(HealthExtraKernel.changes(snapshot(listOf(pending), q = later), later, listOf(stored(next)), later.requestedAt).isEmpty())
    }
    @Test fun onlySelectedFullyCoveredSourceRecordsAreReconciledAndCorrectionsAreVersioned() {
        val heart = row(HealthExtraKind.HEART_RATE); val body = row(HealthExtraKind.BODY_FAT)
        val old = HealthExtraKernel.changes(snapshot(listOf(heart, body)), query, emptyList(), at).map(::stored)
        val chosen = HealthExtraKernel.plan(HealthExtraSelection(setOf(HealthExtraKind.BODY_FAT)), at, query.zone)
        val missing = HealthExtraKernel.changes(snapshot(q = chosen), chosen, old, at).single()
        assertEquals(body, missing.record); assertFalse(missing.visible)
        assertEquals(FitnessRecordVisibility.NOT_VISIBLE_IN_WINDOW, missing.reason)
        val corrected = body.copy(scalarBits = 12.75.toRawBits(), clientRecordVersion = 2)
        val change = HealthExtraKernel.changes(snapshot(listOf(corrected), q = chosen), chosen, old, at).single()
        assertEquals(2L, change.revision); assertEquals(corrected, change.record)
        val outside = old.single { it.record.kind == HealthExtraKind.BODY_FAT }.copy(record = body.copy(startAt = query.windowStart - 1))
            .let { it.copy(fingerprint = HealthExtraKernel.recordFingerprint(it.record)) }
        assertTrue(HealthExtraKernel.changes(snapshot(), query, listOf(outside), at).isEmpty())
    }
    @Test fun malformedSourceShapeValuesAndCaptureNeverBecomeCompletedObservations() {
        val base = row(HealthExtraKind.BODY_FAT)
        listOf(base.copy(scalarBits = Double.NaN.toRawBits()), base.copy(scalarBits = (-1.0).toRawBits()),
            base.copy(scalarBits = 101.0.toRawBits()), base.copy(endAt = at), base.copy(sourceModifiedAt = at + 1),
            base.copy(sourcePackage = " "), base.copy(startOffsetSeconds = 70_000), base.copy(recordingMethod = -1)).forEach { reject(snapshot(listOf(it))) }
        reject(snapshot(listOf(base, base)))
        reject(snapshot(listOf(row(HealthExtraKind.HEART_RATE).copy(samples = listOf(HealthExtraSample(at - 1, bpm = 0))))))
        reject(snapshot(listOf(row(HealthExtraKind.SPEED).copy(samples = listOf(HealthExtraSample(at + 1, doubleBits = 0.0.toRawBits()))))))
        reject(snapshot().copy(formatVersion = 2)); reject(snapshot().copy(capturedAt = at - 1))
        reject(snapshot().copy(query = query.copy(windowStart = query.windowStart + 1)))
    }
}
