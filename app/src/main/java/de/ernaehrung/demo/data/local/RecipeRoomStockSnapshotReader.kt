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
        val eligibleBatchIds = mutableSetOf<String>()
        for (row in rows) {
            val batch = row.batch
            if (batch.id.isBlank() || batch.versionId.isBlank() || batch.locationId.isBlank() ||
                row.stockAmount !in 0..Amounts.MAX) return null
            val location = locations[batch.locationId] ?: return null
            // A zero balance is known zero, but an expired, future-acquired or
            // unconfirmed batch is not a current stock line even when empty.
            // Malformed dates remain an unavailable snapshot, never an assumption.
            val acquired = batch.acquiredOn?.let {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: return null
            }
            val expiries = listOfNotNull(batch.bestBefore, batch.useBy).map {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: return null
            }
            val eligible = !row.originReversed &&
                batch.release == ReleaseStatus.RELEASED && location.confirmed &&
                (acquired == null || !acquired.isAfter(today)) &&
                expiries.none { it.isBefore(today) }
            // Positive but unusable stock makes the entire context unknown.
            if (row.stockAmount > 0 && !eligible) return null
            if (eligible) eligibleBatchIds += batch.id
        }
        val movements = dao.stockMovementSnapshotRows()
        if (movements.size > MAX_MOVEMENTS) return null
        val asOfMillis = capturedAt.toEpochMilli()
        val knownBatches = rows.map { it.batch.id }.toSet()
        // Planning balances sum every stored movement, including legacy or
        // future-dated rows. Never expose those as current physical stock.
        // A legacy consumption date is resolved by the DAO when occurredOn is null.
        for (movement in movements) {
            val day = movement.occurredOn?.let {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: return null
            }
            if (movement.id.isBlank() || movement.batchId !in knownBatches ||
                movement.delta !in -Amounts.MAX..Amounts.MAX ||
                movement.recordedAt !in 0L..asOfMillis ||
                // The event's actual timestamp may be more precise than its
                // Berlin-local day. A future or invalid occurrence is not
                // already available physical stock at this snapshot instant.
                (movement.occurredAt != null && movement.occurredAt !in 0L..asOfMillis) ||
                (day != null && day.isAfter(today))) return null
        }
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
            }, movements.map { listOf(it.id, it.batchId, it.delta, it.recordedAt, it.occurredAt, it.occurredOn) },
            eligibleBatchIds.sorted())
        val lines = ordered.filter {
            it.batch.id in eligibleBatchIds
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
