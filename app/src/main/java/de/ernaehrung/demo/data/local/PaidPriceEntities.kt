package de.ernaehrung.demo.data.local

import androidx.room.*

/**
 * Explicitly confirmed private purchase-line price evidence. Never an external offer or
 * automatically inferred from OCR. Cancellation is an append-only purchase reversal;
 * the original evidence is preserved for audit and excluded from active projections.
 */
@Entity(tableName = "paid_price_observations", foreignKeys = [
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = PurchaseEventEntity::class, parentColumns = ["operationId"], childColumns = ["purchaseOperationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ReceiptLineEntity::class, parentColumns = ["id"], childColumns = ["receiptLineId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProductVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["operationId"], unique = true), Index(value = ["purchaseOperationId"]),
    Index(value = ["receiptLineId"], unique = true), Index(value = ["versionId"])])
data class PaidPriceObservationEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val purchaseOperationId: String,
    val receiptLineId: String,
    val versionId: String,
    val retailerId: String,
    val storeId: String?,
    val purchasedOn: String,
    val purchaseUnits: Long,
    val unitPriceCents: Long,
    val linePaidCents: Long,
    val allocatedDiscountCents: Long,
    val depositPerUnitCents: Long?,
    val basisReason: String,
    val recordedAt: Long
)
