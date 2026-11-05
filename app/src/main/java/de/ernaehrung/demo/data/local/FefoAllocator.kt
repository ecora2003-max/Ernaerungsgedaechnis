package de.ernaehrung.demo.data.local

/** Read-only allocation ledger shared by planning and immediate meal previews.
 * One instance reserves each physical unit at most once across its requests.
 * It never writes stock or treats a planned purchase as available inventory.
 */
internal class FefoAllocator(
    private val stocks: List<BatchRow>,
    private val confirmedLocations: Set<String>,
    private val asOf: String
) {
    private val remaining = stocks.associate { it.batch.id to it.stockAmount.coerceAtLeast(0) }.toMutableMap()

    private fun exclusion(row: BatchRow, date: String): StockExclusionReason? = when {
        row.originReversed -> StockExclusionReason.ORIGIN_REVERSED
        row.batch.release != ReleaseStatus.RELEASED -> StockExclusionReason.NOT_RELEASED
        row.batch.locationId !in confirmedLocations -> StockExclusionReason.LOCATION_UNCONFIRMED
        row.batch.acquiredOn != null && row.batch.acquiredOn > date -> StockExclusionReason.NOT_YET_ACQUIRED
        row.batch.useBy != null && row.batch.useBy < date -> StockExclusionReason.USE_BY_EXPIRED
        row.batch.bestBefore != null && row.batch.bestBefore < date -> StockExclusionReason.BEST_BEFORE_REVIEW
        date > asOf && row.batch.useBy == null && row.batch.bestBefore == null -> StockExclusionReason.FUTURE_SHELF_LIFE_UNKNOWN
        else -> null
    }

    fun allocate(versionIds: Set<String>, required: Long?, date: String): Pair<List<PlannedAllocation>, List<ExcludedStock>> {
        val candidates = stocks.filter { it.batch.versionId in versionIds }.sortedWith(compareBy(
            { listOfNotNull(it.batch.useBy, it.batch.bestBefore).minOrNull() ?: "9999-12-31" }, { it.batch.id }))
        val excluded = candidates.mapNotNull { row -> exclusion(row, date)?.let {
            ExcludedStock(row.batch.id, row.stockAmount, it).takeIf { it.amount > 0 }
        } }
        var needed = required ?: 0L
        val allocations = mutableListOf<PlannedAllocation>()
        if (required != null) candidates.forEach { row ->
            if (exclusion(row, date) == null) {
                val take = minOf(remaining.getValue(row.batch.id), needed)
                if (take > 0) {
                    allocations += PlannedAllocation(row.batch.id, take)
                    remaining[row.batch.id] = remaining.getValue(row.batch.id) - take
                    needed -= take
                }
            }
        }
        return allocations to excluded
    }
}
