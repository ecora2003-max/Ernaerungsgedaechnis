package de.ernaehrung.demo.data.local

import androidx.room.*

/** Receipt total is the single paid amount; only an explicit event makes it an actual expense. */
@Entity(tableName = "purchase_events", foreignKeys = [
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["receiptId"], unique = true)])
data class PurchaseEventEntity(@PrimaryKey val operationId: String, val receiptId: String, val currency: String, val reason: String)

/** At most one stock booking per original line, including after cancellation. Never another payment. */
@Entity(tableName = "receipt_stock_bookings", foreignKeys = [
    ForeignKey(entity = ReceiptLineEntity::class, parentColumns = ["id"], childColumns = ["lineId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["operationId"], unique = true)])
data class ReceiptStockBookingEntity(@PrimaryKey val lineId: String, val operationId: String, val basisDescription: String, val reason: String)

/** Append-only completeness statements tied to the exact expense history, including cancellations. */
@Entity(tableName = "expense_status_events", foreignKeys = [
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ExpenseStatusEntity::class, parentColumns = ["operationId"], childColumns = ["previousId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["startsOn", "endsOn", "revision"], unique = true), Index(value = ["previousId"], unique = true)])
data class ExpenseStatusEntity(@PrimaryKey val operationId: String, val startsOn: String, val endsOn: String,
    val revision: Int, val previousId: String?, val complete: Boolean, val dataToken: String, val reason: String)
