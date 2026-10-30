package de.ernaehrung.demo.data.local

/** Original text, interpretation, and a proposed stock quantity are deliberately separate. */
data class PurchaseLineInput(val originalText: String? = null, val interpretedName: String? = null,
    val versionId: String? = null, val amount: Long? = null, val unit: BaseUnit? = null, val totalCents: Long? = null)
data class PurchaseInput(val merchant: String?, val purchasedOn: String?, val paidCents: Long?,
    val lines: List<PurchaseLineInput>, val reason: String, val source: Source = Source.USER)
data class PurchaseStockInput(val lineId: String, val batch: BatchInput, val usableBasisConfirmed: Boolean,
    val basisDescription: String, val reason: String)
data class ExpenseScope(val startsOn: String, val endsOn: String)
data class ExpenseStatusInput(val scope: ExpenseScope, val complete: Boolean, val expectedSnapshot: String, val reason: String)
data class PurchaseLineView(val line: ReceiptLineEntity, val booking: ReceiptStockBookingEntity?,
    val batch: BatchEntity?, val reversedBy: String?)
data class PurchaseView(val event: PurchaseEventEntity, val receipt: ReceiptEntity, val lines: List<PurchaseLineView>,
    val cancellation: OperationReversalEntity?)
data class ExpenseReport(val scope: ExpenseScope, val purchases: List<PurchaseView>,
    val unconfirmedReceipts: List<ReceiptEntity>, val knownSubtotalCents: Long?, val unknownPurchases: Int,
    val totalCents: Long?, val complete: Boolean, val staleConfirmation: Boolean,
    val dataToken: String, val snapshot: String)
