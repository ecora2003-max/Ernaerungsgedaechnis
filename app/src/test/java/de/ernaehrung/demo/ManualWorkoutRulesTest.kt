package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ManualWorkoutRulesTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private fun input() = ManualWorkoutInput("Krafttraining", FitnessTimestampInput("2026-10-25"))
    private fun reject(i: ManualWorkoutInput) {
        try { ManualWorkoutRules.normalize(i, now); fail("Expected invalid actual training") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun rawDecimalsZerosAndUnknownSetsStayDistinctWithoutInventingAStartInstant() {
        val original = input().copy(durationMinutes = " 045,5000 ", distanceMetres = "0,000",
            effort = "7 von 10 laut eigener Skala", sets = listOf(ManualWorkoutSetInput("Kniebeuge", "008", "40,0000"),
                ManualWorkoutSetInput("Unbekannter Satz"), ManualWorkoutSetInput("Explizite Null", "0", "0.000")))
        val n = ManualWorkoutRules.normalize(original, now)
        assertEquals(original, n.input); assertEquals("45.5", n.durationMinutes); assertEquals("0", n.distanceMetres)
        assertNull(n.time.utc); assertNull(n.endUtc)
        assertEquals(8L, n.sets[0].repetitions); assertEquals("008", n.sets[0].input.repetitions)
        assertEquals("40", n.sets[0].loadKg); assertEquals("40,0000", n.sets[0].input.loadKg)
        assertNull(n.sets[1].repetitions); assertNull(n.sets[1].loadKg)
        assertEquals(0L, n.sets[2].repetitions); assertEquals("0", n.sets[2].loadKg)
        assertNull(ManualWorkoutRules.normalize(input(), now).durationMinutes)
    }
    @Test fun dstChoiceDeterminesTheActualIntervalAndSubsecondsAreNeverSilentlyRounded() {
        val local = input().copy(time = FitnessTimestampInput("2026-10-25", "02:30", "Europe/Berlin"), durationMinutes = "90,5000")
        reject(local)
        val n = ManualWorkoutRules.normalize(local.copy(time = local.time.copy(explicitOffset = "+02:00")), now)
        assertEquals("2026-10-25T00:30:00Z", n.time.utc); assertEquals("2026-10-25T02:00:30Z", n.endUtc)
        val nano = ManualWorkoutRules.normalize(local.copy(time = local.time.copy(explicitOffset = "+01:00"), durationMinutes = "0.0000000001"), now)
        assertEquals("2026-10-25T01:30:00.000000006Z", nano.endUtc)
        reject(local.copy(time = local.time.copy(explicitOffset = "+02:00"), durationMinutes = "0.000000000001"))
    }
    @Test fun futureOrInvalidActualsCannotCommitAndNoRpeScaleIsInvented() {
        reject(input().copy(time = FitnessTimestampInput("2030-01-01")))
        reject(input().copy(time = FitnessTimestampInput("2026-11-05", "11:59", explicitOffset = "Z"), durationMinutes = "2"))
        listOf("-1", "NaN", "1e3", "").forEach { reject(input().copy(durationMinutes = it)) }
        reject(input().copy(sets = listOf(ManualWorkoutSetInput("", "8", "40"))))
        reject(input().copy(sets = listOf(ManualWorkoutSetInput("Satz", "1.5", "40"))))
        assertEquals("hart · ohne numerische Skala", ManualWorkoutRules.normalize(input().copy(effort = "hart · ohne numerische Skala"), now).input.effort)
    }
    @Test fun exactNanosecondIntervalsAndKnownZeroDurationDoNotCreateFalseOverlap() {
        val start = Instant.parse("2026-10-25T00:30:00Z")
        val a = WorkoutInterval(WorkoutIntervalKey("MANUAL", "a"), start, start.plusNanos(6))
        val adjacent = WorkoutInterval(WorkoutIntervalKey("MANUAL", "b"), start.plusNanos(6), start.plusNanos(10))
        val zero = WorkoutInterval(WorkoutIntervalKey("MANUAL", "zero"), start.plusNanos(3), start.plusNanos(3))
        assertTrue(WorkoutOverlaps.with(a, listOf(a, adjacent, zero)).isEmpty())
        assertTrue(WorkoutOverlaps.with(zero, listOf(a, adjacent, zero)).isEmpty())
        val n = ManualWorkoutRules.normalize(input().copy(time = FitnessTimestampInput("2026-10-25", "10:00", explicitOffset = "Z"), durationMinutes = "0"), now)
        assertEquals(n.time.utc, n.endUtc); assertEquals("0", n.durationMinutes)
    }
}
