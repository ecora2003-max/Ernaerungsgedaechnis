package de.ernaehrung.demo.data.local

import androidx.room.*

/** Original aliases remain immutable; only leaves of explicit decision chains may be reused. */
@Entity(tableName = "alias_decisions", foreignKeys = [
    ForeignKey(entity = ProductAliasEntity::class, parentColumns = ["id"], childColumns = ["aliasId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProductAliasEntity::class, parentColumns = ["id"], childColumns = ["supersedesId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["operationId"], unique = true), Index(value = ["supersedesId"], unique = true)])
data class AliasDecisionEntity(@PrimaryKey val aliasId: String, val operationId: String, val kind: String,
    val supersedesId: String?, val active: Boolean, val reason: String)

/** A saved OCR/transcription result is evidence to review, never a paid receipt or stock event. */
@Entity(tableName = "receipt_scans", foreignKeys = [
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["imageHash"], unique = true)])
data class ReceiptScanEntity(@PrimaryKey val operationId: String, val rawText: String, val method: String, val parserVersion: String,
    val imageHash: String? = null)

/** A draft can be accepted once or discarded once. Retrying or reloading cannot create another purchase. */
@Entity(tableName = "receipt_scan_resolutions", foreignKeys = [
    ForeignKey(entity = ReceiptScanEntity::class, parentColumns = ["operationId"], childColumns = ["scanId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["operationId"], unique = true), Index(value = ["receiptId"], unique = true)])
data class ReceiptScanResolutionEntity(@PrimaryKey val scanId: String, val operationId: String, val receiptId: String?, val reason: String)
