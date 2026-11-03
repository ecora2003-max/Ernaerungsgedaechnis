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

    /** A movement must belong to the kind and result scope of its author operation.
     * Foreign-key existence alone does not prove that a stock event was authorized.
     * The legacy reversal's result points to the original operation, not a batch.
     */
    private fun authorMatchesMovement(m: StockMovementSnapshotRow): Boolean {
        val resultId = m.operationResultId ?: return false
        return when (m.movementKind) {
            MovementKind.RECEIPT -> m.operationKind == "RECEIPT" &&
                resultId == m.batchId && m.delta > 0L
            MovementKind.TRANSFER -> m.operationKind == "TRANSFER" && m.delta != 0L &&
                ((m.delta > 0L) == (resultId == m.batchId))
            MovementKind.PRODUCTION_INPUT -> m.operationKind == "PRODUCTION" &&
                resultId != m.batchId && m.delta < 0L
            MovementKind.PRODUCTION_OUTPUT -> m.operationKind == "PRODUCTION" &&
                resultId == m.batchId && m.delta > 0L
            MovementKind.CONSUMPTION -> m.operationKind == "CONSUMPTION" &&
                resultId.isNotBlank() && m.delta < 0L
            MovementKind.WASTE -> m.operationKind == "WASTE" &&
                resultId == m.batchId && m.delta < 0L
            MovementKind.CORRECTION -> m.delta != 0L && when (m.operationKind) {
                "STOCK_COUNT" -> resultId == m.batchId
                "REVERSAL" -> resultId.isNotBlank()
                else -> false
            }
        }
    }

    suspend fun read(capturedAt: Instant): RecipeContextContentBinding.Snapshot? {
        val asOfMillis = capturedAt.toEpochMilli()
        if (asOfMillis < 0L) return null
        val rows = dao.planningBatches()
        // Inner joins to product versions and locations can silently hide damaged
        // legacy batches. A partial projection is not an independently verified
        // stock snapshot, even if the hidden batch currently has zero movements.
        if (rows.size > MAX_ROWS || rows.size != dao.batchCount() ||
            rows.map { it.batch.id }.toSet().size != rows.size) return null
        val today = capturedAt.atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
        // A released batch in an unconfirmed storage location is not usable stock.
        // Include location facts in the revision to invalidate a prior review if
        // the storage evidence changes without a stock movement.
        val locations = rows.map { it.batch.locationId }.distinct()
            .associateWith { dao.location(it) }
        // Planning balances alone do not prove that the referenced product
        // version and originating receipt/production existed at this instant.
        // Legacy rows with future-dated evidence must not become current stock.
        val versions = rows.map { it.batch.versionId }.distinct()
            .associateWith { dao.version(it) }
        val origins = rows.map { it.batch.originOperationId }.distinct()
            .associateWith { dao.operation(it) }
        val eligibleBatchIds = mutableSetOf<String>()
        val batchOriginRecordedAt = mutableMapOf<String, Long>()
        val batchAcquiredOn = mutableMapOf<String, LocalDate?>()
        for (row in rows) {
            val batch = row.batch
            if (batch.id.isBlank() || batch.versionId.isBlank() || batch.locationId.isBlank() ||
                row.stockAmount !in 0..Amounts.MAX) return null
            val location = locations[batch.locationId] ?: return null
            val version = versions[batch.versionId] ?: return null
            val origin = origins[batch.originOperationId] ?: return null
            if (version.recordedAt !in 0L..asOfMillis ||
                origin.recordedAt !in 0L..asOfMillis ||
                // A batch cannot originate before its independently persisted
                // product version existed. Waiting until both timestamps are
                // in the past does not repair an impossible receipt chronology.
                origin.recordedAt < version.recordedAt ||
                origin.kind !in setOf("RECEIPT", "TRANSFER", "PRODUCTION") ||
                origin.resultId != batch.id) return null
            // Opening and thawing are physical batch-state events, not mere
            // labels. A legacy future or pre-origin event cannot be treated as
            // already established evidence at this recipe-review instant.
            // Either timestamp may be absent (unknown), but not invented as 0.
            if (listOf(batch.openedAt, batch.thawedAt).any { eventAt ->
                    eventAt != null && eventAt !in origin.recordedAt..asOfMillis
                }) return null
            batchOriginRecordedAt[batch.id] = origin.recordedAt
            // A zero balance is known zero, but an expired, future-acquired or
            // unconfirmed batch is not a current stock line even when empty.
            // Malformed dates remain an unavailable snapshot, never an assumption.
            val acquired = batch.acquiredOn?.let {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: return null
            }
            // Opening or thawing is a physical batch-state change. A legacy
            // receipt can have an acquisition day later than its recorded
            // operation timestamp; such a state change cannot predate that
            // acquisition even when the review takes place days afterward.
            if (acquired != null && listOfNotNull(batch.openedAt, batch.thawedAt).any { eventAt ->
                    Instant.ofEpochMilli(eventAt)
                        .atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
                        .isBefore(acquired)
                }) return null
            batchAcquiredOn[batch.id] = acquired
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
        val knownBatches = rows.map { it.batch.id }.toSet()
        val batchVersions = rows.associate { it.batch.id to it.batch.versionId }
        val batchById = rows.associateBy { it.batch.id }
        // A single confirmed receipt operation may credit a given batch only
        // once. Older imported histories can contain separately authorized
        // receipt operations for that same batch; do not discard those, but
        // never count a duplicate physical credit under the same operation.
        val receiptCredits = movements.filter { it.movementKind == MovementKind.RECEIPT }
            .groupBy { it.operationId to it.batchId }
        if (receiptCredits.values.any { it.size != 1 }) return null
        // A transfer is one atomic physical move, not two independent stock
        // credits/debits. The author operation must have exactly one source and
        // one destination of equal quantity and product version. A damaged
        // legacy ledger must not inflate the stock available to recipe review.
        val transfers = movements.filter { it.movementKind == MovementKind.TRANSFER }
            .groupBy { it.operationId }
        for ((operationId, entries) in transfers) {
            if (entries.size != 2) return null
            val debit = entries.singleOrNull { it.delta < 0L } ?: return null
            val credit = entries.singleOrNull { it.delta > 0L } ?: return null
            if (debit.batchId == credit.batchId ||
                debit.delta != -credit.delta ||
                // One transfer has one physical occurrence, not two independently
                // timed movements. Contradictory legacy instants invalidate stock.
                (debit.occurredAt != null && credit.occurredAt != null &&
                    debit.occurredAt != credit.occurredAt) ||
                // A transfer's two physical ledger legs also share one local
                // occurrence day. Different legacy days are not one transfer.
                (debit.occurredOn != null && credit.occurredOn != null &&
                    debit.occurredOn != credit.occurredOn) ||
                batchVersions[debit.batchId] != batchVersions[credit.batchId] ||
                batchById[credit.batchId]?.batch?.parentBatchId != debit.batchId ||
                batchById[credit.batchId]?.batch?.originOperationId != operationId) return null
        }
        // Planning balances sum every stored movement, including legacy or
        // future-dated rows. Never expose those as current physical stock.
        // A legacy consumption date is resolved by the DAO when occurredOn is null.
        for (movement in movements) {
            // A movement's own author must not predate the batch-creating
            // operation. Later recording of the movement cannot make an
            // impossible correction, consumption or transfer legitimate.
            val batchCreatedAt = batchOriginRecordedAt[movement.batchId] ?: return null
            // A consumption movement must be backed by the same confirmed
            // consumption event and its per-batch allocation. An operation
            // labelled CONSUMPTION alone is not proof of a physical deduction.
            // Keep this read-only: invalid legacy evidence makes stock unknown.
            // A receipt, transfer or other non-consumption author cannot also
            // carry a confirmed consumption event. The left join would otherwise
            // expose that contradictory history as a valid stock snapshot.
            if (movement.movementKind != MovementKind.CONSUMPTION &&
                movement.consumptionId != null) return null
            if (movement.movementKind == MovementKind.CONSUMPTION &&
                (movement.consumptionId.isNullOrBlank() ||
                    movement.operationResultId != movement.consumptionId ||
                    movement.consumptionVersionId != batchVersions[movement.batchId] ||
                    movement.consumptionAmount == null ||
                    movement.allocationAmount == null ||
                    movement.allocationAmount <= 0L ||
                    movement.consumptionAllocatedTotal == null ||
                    movement.consumptionAmount != movement.consumptionAllocatedTotal ||
                    // A matching amount on this one movement does not prove
                    // that every allocation has exactly one physical deduction.
                    // Reject missing or duplicated movement evidence for any
                    // batch allocated by the same confirmed consumption.
                    movement.unmatchedConsumptionAllocations != 0 ||
                    movement.delta != -movement.allocationAmount ||
                    movement.consumptionDate != movement.occurredOn ||
                    movement.consumptionOccurredAt != movement.occurredAt)) return null
            val day = movement.occurredOn?.let {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: return null
            }
            // A movement cannot physically precede the batch's recorded
            // acquisition day. A later review must not legitimize legacy
            // stock activity from before the acquisition, even if the batch
            // is now released and the movement's author operation exists.
            val acquiredOn = batchAcquiredOn[movement.batchId]
            if (acquiredOn != null && (
                    day?.isBefore(acquiredOn) == true ||
                    movement.occurredAt?.let { eventAt ->
                        Instant.ofEpochMilli(eventAt)
                            .atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
                            .isBefore(acquiredOn)
                    } == true)) return null
            // Legacy rows can carry both a physical instant and a calendar
            // day. They must agree in Berlin before either can authorize an
            // as-of stock projection. Null occurrence remains unknown, not zero.
            if (day != null && movement.occurredAt != null &&
                Instant.ofEpochMilli(movement.occurredAt)
                    .atZone(ZoneId.of("Europe/Berlin")).toLocalDate() != day) return null
            // The movement row alone is not proof of an already committed
            // stock event. Its independently stored author operation must
            // exist and have been recorded by this exact snapshot instant.
            if (movement.id.isBlank() || movement.batchId !in knownBatches ||
                movement.operationId.isBlank() || movement.operationKind.isNullOrBlank() ||
                movement.operationResultId == null ||
                !authorMatchesMovement(movement) ||
                movement.operationRecordedAt == null ||
                movement.operationRecordedAt !in 0L..asOfMillis ||
                movement.operationRecordedAt < batchCreatedAt ||
                // A confirmed physical event cannot happen after the operation
                // that already committed it. A later review time must not
                // legitimize a legacy future-occurrence movement.
                (movement.occurredAt != null &&
                    movement.occurredAt > movement.operationRecordedAt) ||
                // Day-only legacy events must also have occurred no later than
                // their confirming operation's Berlin-local calendar day. A
                // later snapshot cannot repair a future physical event.
                (day != null && movement.operationRecordedAt?.let { authorAt ->
                    day.isAfter(Instant.ofEpochMilli(authorAt)
                        .atZone(ZoneId.of("Europe/Berlin")).toLocalDate())
                } == true) ||
                // A backdated physical event cannot consume or change a batch
                // before its creating operation existed. A later recording or
                // snapshot must not turn that impossible history into stock.
                (movement.occurredAt != null && movement.occurredAt < batchCreatedAt) ||
                // Legacy movements may have only a Berlin-local occurrence day.
                // That day cannot precede the day their batch first existed;
                // a later recording or review cannot repair physical history.
                (day != null && day.isBefore(Instant.ofEpochMilli(batchCreatedAt)
                    .atZone(ZoneId.of("Europe/Berlin")).toLocalDate())) ||
                movement.delta !in -Amounts.MAX..Amounts.MAX ||
                movement.recordedAt !in 0L..asOfMillis ||
                // A stock movement cannot have been committed before its
                // independently persisted author operation existed. Merely
                // advancing the snapshot clock must not legitimize a legacy
                // row whose two recorded timestamps contradict one another.
                movement.operationRecordedAt > movement.recordedAt ||
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
                    versions[b.versionId]?.let { listOf(it.revision, it.recordedAt) },
                    origins[b.originOperationId]?.let { listOf(it.kind, it.resultId, it.recordedAt) },
                    locations[b.locationId]?.let { location ->
                        listOf(location.id, location.name, location.confirmed,
                            location.usableCapacityMl, location.temperatureCelsius)
                    })
            }, movements.map { listOf(it.id, it.batchId, it.operationId, it.movementKind.name, it.operationKind,
                it.operationResultId, it.operationRecordedAt, it.delta, it.recordedAt,
                it.occurredAt, it.occurredOn, it.consumptionId,
                it.consumptionVersionId, it.consumptionAmount, it.consumptionDate,
                it.consumptionOccurredAt, it.allocationAmount,
                it.consumptionAllocatedTotal, it.unmatchedConsumptionAllocations) },
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
