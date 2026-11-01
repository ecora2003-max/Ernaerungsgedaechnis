package de.ernaehrung.demo.data.local

import de.ernaehrung.demo.data.integration.RecipeContextContentBinding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads real Room stock inside the caller's database transaction. A snapshot
 * describes only independently released, unexpired batches; ambiguous positive
 * stock makes the entire source unavailable, never silently empty.
 *
 * This is a read-only host projection, not a recipe-save or stock-booking port.
 * It does not authenticate user consent or validate other context sources.
 */
internal class RecipeRoomStockSnapshotReader(private val dao: FoodDao) {
    companion object {
        const val SCOPE = "LOCAL_ROOM_STOCK_V1"
        private const val MAX_ROWS = 10_000
        private const val MAX_MOVEMENTS = 100_000
    }

    suspend fun read(capturedAt: Instant): RecipeContextContentBinding.Snapshot? {
        val rows = dao.planningBatches()
        if (rows.size > MAX_ROWS || rows.map { it.batch.id }.toSet().size != rows.size) return null
        val today = capturedAt.atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
        // A released batch in an unconfirmed storage location is not usable stock.
        // Include location facts in the revision to invalidate a prior review if
        // the storage evidence changes without a stock movement.
        val locations = rows.map { it.batch.locationId }.distinct()
            .associateWith { dao.location(it) }
        for (row in rows) {
            val batch = row.batch
            if (batch.id.isBlank() || batch.versionId.isBlank() || batch.locationId.isBlank() ||
                row.stockAmount !in 0..Amounts.MAX) return null
            val location = locations[batch.locationId] ?: return null
            if (row.stockAmount > 0) {
                // Planning excludes unreleased, reversed, unconfirmed-location,
                // not-yet-acquired and past-best-before stock. Recipe context
                // must not silently advertise any of those batches as usable.
                if (row.originReversed || batch.release != ReleaseStatus.RELEASED ||
                    !location.confirmed) return null
                batch.acquiredOn?.let {
                    val date = runCatching { LocalDate.parse(it) }.getOrNull() ?: return null
                    if (date.isAfter(today)) return null
                }
                for (expiry in listOfNotNull(batch.bestBefore, batch.useBy)) {
                    val date = runCatching { LocalDate.parse(expiry) }.getOrNull() ?: return null
                    if (date.isBefore(today)) return null
                }
            }
        }
        val movements = dao.stockMovementIds()
        if (movements.size > MAX_MOVEMENTS) return null
        val ordered = rows.sortedBy { it.batch.id }
        // Include non-visible batches and movement identities in the revision:
        // a release, reversal or compensating movement must invalidate an old review.
        val revision = WriteRules.fingerprint("RECIPE_ROOM_STOCK_REVISION_V1",
            ordered.map { row ->
                val b = row.batch
                listOf(b.id, b.versionId, b.locationId, b.originOperationId,
                    b.acquiredOn, b.bestBefore, b.useBy, b.openedAt, b.thawedAt,
                    b.release.name, b.safetyNote, b.recipeVersionId, b.parentBatchId,
                    row.originReversed, row.stockAmount, row.unit.name,
                    locations[b.locationId]?.let { location ->
                        listOf(location.id, location.name, location.confirmed,
                            location.usableCapacityMl, location.temperatureCelsius)
                    })
            }, movements)
        val lines = ordered.filter {
            !it.originReversed && it.batch.release == ReleaseStatus.RELEASED
        }.map { row ->
            RecipeContextContentBinding.Line(
                identity = "batch:${row.batch.id}",
                versionId = row.batch.versionId,
                amountMilli = row.stockAmount,
                unit = row.unit.label
            )
        }
        return RecipeContextContentBinding.Snapshot(
            RecipeContextContentBinding.Source.STOCK, SCOPE, revision, capturedAt, lines)
    }
}
