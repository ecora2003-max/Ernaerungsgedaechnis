package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class WorkoutOverlapsTest {
    private fun interval(source: String, id: String, start: Long, end: Long) =
        WorkoutInterval(WorkoutIntervalKey(source, id), start, end)

    @Test fun adjacentSessionsDoNotOverlapAndIndependentSourceIdentitiesAreNeverMerged() {
        val a = interval("samsung", "same-id", 100L, 200L)
        val adjacent = interval("samsung", "later", 200L, 300L)
        val other = interval("other-source", "same-id", 150L, 250L)
        val duplicateTime = interval("samsung", "independent-record", 100L, 200L)
        val records = listOf(a, adjacent, other, duplicateTime)
        assertEquals(listOf(other.key, duplicateTime.key), WorkoutOverlaps.with(a, records))
        assertEquals(listOf(other.key), WorkoutOverlaps.with(adjacent, records))
        assertEquals(4, records.size) // Evidence to review, no automatic deduplication or sum.
        assertEquals(a, records.first())
    }

    @Test fun repeatedDstClockTimesUseTheirActualUtcIntervalsRatherThanSimilarLocalLabels() {
        fun instant(s: String) = OffsetDateTime.parse(s).toInstant().toEpochMilli()
        val summerStart = instant("2026-10-25T02:30:00+02:00")
        val winterStart = instant("2026-10-25T02:30:00+01:00")
        val summer = interval("samsung", "summer", summerStart, summerStart + 1_800_000L)
        val winter = interval("samsung", "winter", winterStart, winterStart + 1_800_000L)
        assertTrue(WorkoutOverlaps.with(summer, listOf(summer, winter)).isEmpty())
        val crossing = interval("samsung", "crossing", summerStart + 1_200_000L, winterStart + 600_000L)
        assertEquals(listOf(summer.key, winter.key), WorkoutOverlaps.with(crossing, listOf(summer, winter)))
    }
}
