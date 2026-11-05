package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import org.junit.Assert.*
import org.junit.Test

class MealStockPreviewTest {
    private val date = "2026-11-05"
    private val version = ProductVersionEntity("version", "product", 1, "Rice", null, null, null,
        BaseUnit.GRAM, null, 100_000L, 100.0, null, null, null,
        source = Source.USER, sourceDetail = null, quality = Quality.CONFIRMED,
        recordedAt = 1L, changeReason = "synthetic")
    private val location = StorageLocationEntity("location", "Pantry", true)
    private fun batch(id: String, amount: Long, until: String?, release: ReleaseStatus = ReleaseStatus.RELEASED,
        versionId: String = version.id) = BatchRow(
        BatchEntity(id, versionId, location.id, "origin-$id", date, null, until, null, null, release, null),
        "Rice", BaseUnit.GRAM, "Pantry", amount, false)
    private fun preview(rows: List<BatchRow>, amount: Long? = 100_000L, requested: String = date) =
        MealStockPreviews.build(listOf(version), rows, listOf(location), version.id, amount, requested, date)

    @Test fun physicalPartialBatchesCoverOneMealInFefoOrderWithoutCombiningProductVersions() {
        val early = batch("early", 40_000L, "2026-11-06")
        val later = batch("later", 60_000L, "2026-11-09")
        val other = batch("other-version", 900_000L, date, versionId = "version-2")
        val result = preview(listOf(later, other, early))
        assertTrue(result.complete)
        assertEquals(listOf(PlannedAllocation(early.batch.id, 40_000L), PlannedAllocation(later.batch.id, 60_000L)), result.allocations)
        assertEquals(0L, result.missing)
    }

    @Test fun unavailableExpiredOrUnconfirmedStockStaysExcludedAndDoesNotFillTheMeal() {
        val rows = listOf(batch("unreleased", 100_000L, "2026-11-06", ReleaseStatus.UNCHECKED),
            batch("expired", 100_000L, "2026-11-04"), batch("usable", 30_000L, "2026-11-06"))
        val result = preview(rows)
        assertFalse(result.complete)
        assertEquals(70_000L, result.missing)
        assertEquals(setOf(StockExclusionReason.NOT_RELEASED, StockExclusionReason.USE_BY_EXPIRED), result.excluded.map { it.reason }.toSet())
        val unknownLocation = MealStockPreviews.build(listOf(version), rows, listOf(location.copy(confirmed = false)),
            version.id, 100_000L, date, date)
        assertFalse(unknownLocation.complete)
    }

    @Test fun missingAmountsAndInvalidBalancesAreUnknownRatherThanKnownZero() {
        val rows = listOf(batch("one", 100_000L, null))
        for (amount in listOf(null, 0L, -1L)) {
            val result = preview(rows, amount)
            assertNull(result.missing)
            assertTrue(result.allocations.isEmpty())
            assertFalse(result.complete)
        }
        val invalid = preview(listOf(rows.single().copy(stockAmount = -1L)))
        assertNull(invalid.missing)
        assertNotNull(invalid.issue)
        assertEquals(100_000L, preview(emptyList()).missing) // A known empty source is different.
    }

    @Test fun changedPhysicalEvidenceChangesTheGestureBindingAndFutureActualsAreRefused() {
        val row = batch("one", 100_000L, "2026-11-08")
        assertNotEquals(preview(listOf(row)).digest, preview(listOf(row.copy(stockAmount = 200_000L))).digest)
        assertFalse(preview(listOf(row), requested = "2026-11-06").complete)
        assertFalse(preview(listOf(row), requested = "+10000-01-01").complete)
    }

    @Test fun oneSharedAllocatorNeverReservesTheSameStockTwiceAcrossPlanRequests() {
        val row = batch("one", 100_000L, "2026-11-08")
        val allocator = FefoAllocator(listOf(row), setOf(location.id), date)
        assertEquals(70_000L, allocator.allocate(setOf(version.id), 70_000L, date).first.sumOf { it.amount })
        assertEquals(30_000L, allocator.allocate(setOf(version.id), 70_000L, date).first.sumOf { it.amount })
    }
}
