package de.ernaehrung.demo.data.local

import java.math.BigInteger
import java.time.LocalDate
import java.util.Locale

/** Read-only view of the exact historical article versions and current batches. */
internal data class StockOverviewEntry(val row: BatchRow,val version: ProductVersionEntity?,
    val usableAmount: Long?,val reason: String?,val deadline: String?)

internal data class StockOverviewGroup(val versionId: String,val name: String,val revision: Int?,val unit: BaseUnit,
    val entries: List<StockOverviewEntry>) {
    val physicalAmount: BigInteger? = if (entries.any { it.row.stockAmount < 0L }) null else
        entries.fold(BigInteger.ZERO) { total,entry -> total + BigInteger.valueOf(entry.row.stockAmount) }
    private val knownUsable = entries.mapNotNull { it.usableAmount }
    val usableKnownAmount: BigInteger? = knownUsable.takeIf { it.isNotEmpty() }
        ?.fold(BigInteger.ZERO) { total,amount -> total + BigInteger.valueOf(amount) }
    val unknownUsableCount: Int = entries.count { it.usableAmount == null }
}

internal object StockOverviewPreviews {
    fun build(versions: List<ProductVersionEntity>,batches: List<BatchRow>,locations: List<StorageLocationEntity>,date: String): List<StockOverviewEntry> {
        WriteRules.date(date)
        val byVersion = versions.groupBy { it.id }
        val byLocation = locations.groupBy { it.id }
        return batches.map { row ->
            val versionRows = byVersion[row.batch.versionId].orEmpty()
            val version = versionRows.singleOrNull()
            val deadline = listOfNotNull(row.batch.useBy,row.batch.bestBefore)
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.minOrNull()?.toString()
            if (row.stockAmount == 0L) StockOverviewEntry(row,version,0L,
                if (row.originReversed) "Herkunft storniert · nur Historie" else "Aufgebraucht · nur Historie",deadline)
            else {
                // A full single-batch request uses the same eligibility and date
                // checks as actual meal/production previews. No new stock rules.
                val preview = MealStockPreviews.build(versionRows,listOf(row),byLocation[row.batch.locationId].orEmpty(),
                    row.batch.versionId,row.stockAmount,date,date)
                StockOverviewEntry(row,version,if (preview.complete) row.stockAmount else if (preview.issue != null) null else 0L,
                    preview.issue ?: preview.excluded.map { it.reason.label }.distinct().joinToString("; ").takeIf(String::isNotBlank),deadline)
            }
        }
    }

    fun select(entries: List<StockOverviewEntry>,query: String = "",locationId: String? = null,
        positiveOnly: Boolean = false,usableOnly: Boolean = false): List<StockOverviewGroup> {
        val words = query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val selected = entries.filter { entry ->
            val row = entry.row
            val text = listOfNotNull(row.productName,entry.version?.brand,entry.version?.barcode,
                row.batch.versionId,row.batch.id,row.locationName).joinToString(" ").lowercase(Locale.ROOT)
            (locationId == null || row.batch.locationId == locationId) &&
                (!positiveOnly || row.stockAmount > 0L) && (!usableOnly || (entry.usableAmount ?: 0L) > 0L) &&
                words.all { it in text }
        }
        return selected.groupBy { it.row.batch.versionId to it.row.unit }.map { (key,rows) ->
            val ordered = rows.sortedWith(compareBy<StockOverviewEntry> { it.deadline == null }
                .thenBy { it.deadline }.thenBy { it.row.batch.id })
            StockOverviewGroup(key.first,rows.first().row.productName,rows.first().version?.revision,key.second,ordered)
        }.sortedWith(compareBy<StockOverviewGroup> { it.name.lowercase(Locale.ROOT) }.thenBy { it.versionId }.thenBy { it.unit.name })
    }
}
