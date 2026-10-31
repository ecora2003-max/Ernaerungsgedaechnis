package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.integration.HistoricalPaidPriceKernel
import de.ernaehrung.demo.data.integration.ProductReceiptClassifier
import java.time.Instant
import java.time.ZoneOffset

/**
 * Extra, explicit per-line facts needed to evaluate a receipt as historical paid-price evidence.
 * The paid receipt alone does not establish food identity, net merchandise, pack count or deposit.
 * This request is for READ-ONLY review and never changes the original receipt.
 */
data class PaidPriceBasisReview(
    val purchaseOperationId: String,
    val receiptLineId: String,
    val expectedProductVersionId: String,
    val purchaseUnits: Long?,
    val allocatedDiscountCents: Long?,
    val depositPerUnitCents: Long?,
    val discountReconciled: Boolean,
    val amountExcludesDeposit: Boolean,
    val unitsConfirmed: Boolean,
    val foodAndProductConfirmed: Boolean,
    val basisReason: String? = null
)

/** No price in this result is a currently valid offer or a future shopping quote. */
data class PaidPricePreview(
    val observationId: String?,
    val productVersionId: String?,
    val purchasedOn: String?,
    val unitPriceCents: Long?,
    val linePaidCents: Long?,
    val depositPerUnitCents: Long?,
    val rejectionReason: String?,
    val priceKind: String = "HISTORICAL_PAID"
) {
    val eligible: Boolean get() = observationId != null && rejectionReason == null
}

/** Bridges existing confirmed FoodStore receipts to the pinned, offline, tested product-data kernel. */
internal class PaidReceiptPriceReader(private val db: FoodDatabase) {
    private val dao = db.foodDao()
    private fun rejected(reason: String) = PaidPricePreview(null, null, null, null, null, null, reason)

    suspend fun preview(input: PaidPriceBasisReview): PaidPricePreview = db.withTransaction {
        val event = dao.purchase(input.purchaseOperationId) ?: return@withTransaction rejected("PURCHASE_NOT_CONFIRMED")
        val operation = dao.operation(event.operationId) ?: return@withTransaction rejected("PURCHASE_OPERATION_MISSING")
        val receipt = dao.receipt(event.receiptId) ?: return@withTransaction rejected("RECEIPT_MISSING")
        val line = dao.receiptLine(input.receiptLineId) ?: return@withTransaction rejected("LINE_MISSING")
        if (line.receiptId != event.receiptId) return@withTransaction rejected("INVALID_LINE_IDENTITY")
        if (dao.reversal(event.operationId) != null) return@withTransaction rejected("REVERSED_PURCHASE_OR_LINE")
        if (!input.foodAndProductConfirmed) return@withTransaction rejected("UNCONFIRMED_PURCHASE_OR_LINE")
        if (line.versionId == null || line.versionId != input.expectedProductVersionId)
            return@withTransaction rejected("PRODUCT_VERSION_MISMATCH")
        val version = dao.version(line.versionId) ?: return@withTransaction rejected("PRODUCT_VERSION_MISSING")
        if (input.allocatedDiscountCents == null) return@withTransaction rejected("DISCOUNT_ACCOUNTING_UNKNOWN")
        if (receipt.totalCents == null) return@withTransaction rejected("UNKNOWN_PAID_RECEIPT_TOTAL")
        // Explicit FOOD confirmation cannot turn obviously nonfood, discount, deposit or return evidence into merchandise.
        val kind = line.originalText?.takeIf { it.length <= 4_096 }?.let {
            runCatching { ProductReceiptClassifier.propose(it).kind }.getOrNull()
        }
        if (kind in setOf(ProductReceiptClassifier.Kind.NON_FOOD, ProductReceiptClassifier.Kind.DEPOSIT,
                ProductReceiptClassifier.Kind.DISCOUNT, ProductReceiptClassifier.Kind.RETURN))
            return@withTransaction rejected("NON_FOOD_DISCOUNT_DEPOSIT_OR_RETURN")
        if (line.originalText != null && line.originalText.length > 4_096)
            return@withTransaction rejected("UNCLASSIFIABLE_LINE")
        val confirmedAt = Instant.ofEpochMilli(operation.recordedAt).atOffset(ZoneOffset.UTC).toString()
        val receiptFields: Map<String, Any?> = mapOf(
            "purchaseConfirmed" to true, "purchaseReversed" to false,
            "purchaseId" to event.operationId, "receiptId" to receipt.id,
            "retailerId" to receipt.merchant, "purchasedOn" to receipt.purchasedOn,
            "confirmedAt" to confirmedAt
        )
        val lineFields: Map<String, Any?> = mapOf(
            "confirmed" to true, "lineReversed" to false, "productConfirmed" to true,
            "lineKind" to "FOOD", "lineId" to line.id, "receiptId" to receipt.id,
            "retailerId" to receipt.merchant, "productId" to version.productId,
            "versionId" to version.id, "amountCents" to line.totalCents,
            "purchaseUnits" to input.purchaseUnits, "unitsConfirmed" to input.unitsConfirmed,
            "discountReconciled" to input.discountReconciled,
            "allocatedDiscountCents" to input.allocatedDiscountCents,
            "amountExcludesDeposit" to input.amountExcludesDeposit,
            "depositPerUnitCents" to input.depositPerUnitCents
        )
        val articleFields: Map<String, Any?> = mapOf("id" to version.productId,
            "versionId" to version.id, "label" to version.name)
        val decision = HistoricalPaidPriceKernel.propose(receiptFields, lineFields, articleFields)
        val observation = decision.observation
        if (observation == null) rejected(decision.reason ?: "UNKNOWN_PRICE_BASIS")
        else PaidPricePreview(observation.observationId, observation.versionId,
            observation.purchasedOn, observation.priceCents, observation.linePaidCents,
            observation.depositCents, null)
    }
}
