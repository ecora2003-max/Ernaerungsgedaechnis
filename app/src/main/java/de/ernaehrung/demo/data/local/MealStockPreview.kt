package de.ernaehrung.demo.data.local

import java.time.LocalDate

/** Derived UI proposal, not authorization or a persisted actual event. */
internal data class MealStockPreview(
    val allocations: List<PlannedAllocation> = emptyList(),
    val excluded: List<ExcludedStock> = emptyList(),
    val missing: Long? = null,
    val issue: String? = null,
    val digest: String? = null
) {
    val complete get() = issue == null && missing == 0L && allocations.isNotEmpty() && digest != null
}

internal object MealStockPreviews {
    /** All amounts stay in the selected version's milli-base-unit. The caller
     * must bind its explicit gesture to digest and FoodStore rechecks every
     * physical allocation atomically at commit. Unknown data is never zero.
     */
    fun build(
        versions: List<ProductVersionEntity>, batches: List<BatchRow>,
        locations: List<StorageLocationEntity>, versionId: String, amount: Long?,
        date: String, asOf: String
    ): MealStockPreview {
        fun issue(text: String) = MealStockPreview(issue = text)
        val version = versions.singleOrNull { it.id == versionId }
            ?: return issue("Für den Bestandsvorschlag eine eindeutige Artikelversion auswählen.")
        val dates = runCatching { LocalDate.parse(date) to LocalDate.parse(asOf) }.getOrNull()
            ?: return issue("Für den Bestandsvorschlag ein gültiges Verzehrdatum eingeben.")
        if (dates.first.isAfter(dates.second)) return issue("Geplantes Essen ist noch kein Verzehr. Einen tatsächlichen Ereignistag wählen.")
        if (amount == null) return issue("Für eine Lagerentnahme die tatsächlich gegessene Menge eingeben.")
        if (amount !in 1..Amounts.MAX) return issue("Die Verzehrmenge muss positiv und innerhalb des Mengenbereichs liegen.")
        val rows = batches.filter { it.batch.versionId == version.id }
        if (rows.map { it.batch.id }.toSet().size != rows.size || rows.any { row ->
                row.stockAmount !in 0..Amounts.MAX || row.unit != version.baseUnit ||
                    listOfNotNull(row.batch.acquiredOn, row.batch.useBy, row.batch.bestBefore)
                        .any { runCatching { LocalDate.parse(it) }.isFailure }
            }) return issue("Bestandsdaten oder Einheiten sind widersprüchlich. Chargen vor einer Entnahme prüfen.")
        val confirmed = locations.filter { it.confirmed }.mapTo(mutableSetOf()) { it.id }
        val (allocations, excluded) = FefoAllocator(rows, confirmed, asOf).allocate(setOf(version.id), amount, date)
        val missing = amount - allocations.sumOf { it.amount }
        val digest = WriteRules.fingerprint("MEAL_STOCK_PREVIEW_1", version.id, version.baseUnit.name,
            version.recordedAt, amount, date, asOf, confirmed.sorted(), rows.sortedBy { it.batch.id }.map { row ->
                listOf(row.batch.id, row.batch.versionId, row.stockAmount, row.unit.name,
                    row.batch.locationId, row.batch.acquiredOn, row.batch.bestBefore, row.batch.useBy,
                    row.batch.release.name, row.originReversed)
            }, allocations.map { listOf(it.batchId, it.amount) })
        return MealStockPreview(allocations, excluded, missing, digest = digest)
    }
}
