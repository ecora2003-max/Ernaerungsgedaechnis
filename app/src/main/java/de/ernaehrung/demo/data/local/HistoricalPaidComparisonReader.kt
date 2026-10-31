package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import java.time.LocalDate

/**
 * Private historical evidence as known at a specified instant. It is NEVER an offer,
 * current retailer price, pack quote, or automatic shopping-list recommendation.
 * Receipt merchant labels are unverified and deliberately not used as store identities.
 */
data class HistoricalPaidComparisonScope(
    val productVersionId: String,
    val targetPurchaseOn: String,
    val knownAtMillis: Long
)

data class HistoricalPaidReference(
    val observationId: String,
    val receiptLineId: String,
    val purchasedOn: String,
    val unitPriceCents: Long,
    val purchaseUnits: Long
)

data class HistoricalPaidComparison(
    val scope: HistoricalPaidComparisonScope,
    val references: List<HistoricalPaidReference>,
    val lowestHistoricalUnitPriceCents: Long?,
    val priceKind: String = "HISTORICAL_PAID",
    val isCurrentOffer: Boolean = false
)

internal data class HistoricalPaidEvidence(
    val observationId: String,
    val receiptLineId: String,
    val versionId: String,
    val purchasedOn: String,
    val unitPriceCents: Long,
    val purchaseUnits: Long,
    val recordedAt: Long,
    val reversedAt: Long?
)

/** Deterministic, read-only, no-hindsight projection with exact product-version scope. */
internal object HistoricalPaidComparisonKernel {
    fun project(scope: HistoricalPaidComparisonScope, evidence: List<HistoricalPaidEvidence>): HistoricalPaidComparison {
        require(scope.productVersionId.isNotBlank()) { "Concrete product version required." }
        require(scope.knownAtMillis >= 0L) { "Invalid knowledge instant." }
        val date = LocalDate.parse(scope.targetPurchaseOn)
        require(date.toString() == scope.targetPurchaseOn) { "ISO purchase date required." }
        val references = evidence.asSequence()
            .filter { it.versionId == scope.productVersionId }
            .filter { it.purchasedOn <= scope.targetPurchaseOn && it.recordedAt <= scope.knownAtMillis }
            .filter { it.reversedAt == null || it.reversedAt > scope.knownAtMillis }
            .filter { it.unitPriceCents in 0..Money.MAX_CENTS && it.purchaseUnits > 0L }
            .map { HistoricalPaidReference(it.observationId, it.receiptLineId, it.purchasedOn,
                it.unitPriceCents, it.purchaseUnits) }
            .sortedWith(compareByDescending<HistoricalPaidReference> { it.purchasedOn }.thenBy { it.observationId })
            .toList()
        return HistoricalPaidComparison(scope, references, references.minOfOrNull { it.unitPriceCents })
    }
}

/**
 * Uses the immutable audit ledger, not the current active projection: a reversal recorded
 * AFTER the requested knowledge instant must not retroactively rewrite the past.
 */
internal class HistoricalPaidComparisonReader(private val db: FoodDatabase) {
    private val dao = db.foodDao()
    suspend fun compare(scope: HistoricalPaidComparisonScope): HistoricalPaidComparison = db.withTransaction {
        val evidence = dao.allPaidPrices().map { row ->
            val reversal = dao.reversal(row.purchaseOperationId)
            val reversedAt = reversal?.let { dao.operation(it.reversalOperationId)?.recordedAt ?: Long.MIN_VALUE }
            HistoricalPaidEvidence(row.id, row.receiptLineId, row.versionId, row.purchasedOn,
                row.unitPriceCents, row.purchaseUnits, row.recordedAt, reversedAt)
        }
        HistoricalPaidComparisonKernel.project(scope, evidence)
    }
}
