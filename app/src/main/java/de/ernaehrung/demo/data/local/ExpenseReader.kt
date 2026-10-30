package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import java.math.BigInteger
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class ExpenseReader(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    fun watch(scope: ExpenseScope) = db.invalidationTracker.createFlow("purchase_events", "receipts", "receipt_lines",
        "receipt_stock_bookings", "batches", "operation_reversals", "expense_status_events")
        .map { report(scope) }.distinctUntilChanged()

    suspend fun report(scope: ExpenseScope): ExpenseReport = db.withTransaction {
        listOf(scope.startsOn, scope.endsOn).forEach(WriteRules::date)
        require(ChronoUnit.DAYS.between(LocalDate.parse(scope.startsOn), LocalDate.parse(scope.endsOn)) in 0..365) { "Ausgabenzeitraum zwischen einem und 366 Tagen wählen." }
        WriteRules.actualDate(scope.endsOn, now())
        val receipts = dao.receipts().associateBy { it.id }
        val events = dao.purchases()
        fun inScope(receipt: ReceiptEntity) = receipt.purchasedOn == null || receipt.purchasedOn in scope.startsOn..scope.endsOn
        val views = events.mapNotNull { event ->
            val receipt = requireNotNull(receipts[event.receiptId])
            if (!inScope(receipt)) return@mapNotNull null
            PurchaseView(event, receipt, dao.receiptLines(receipt.id).map { line ->
                val booking = dao.receiptBooking(line.id)
                val stockId = booking?.operationId ?: line.stockOperationId
                PurchaseLineView(line, booking, stockId?.let { dao.operation(it)?.resultId }?.let { dao.batch(it) },
                    stockId?.let { dao.reversal(it)?.reversalOperationId })
            }, dao.reversal(event.operationId))
        }.sortedWith(compareBy({ it.receipt.purchasedOn ?: "" }, { it.receipt.id }))
        val unconfirmed = receipts.values.filter { receipt -> inScope(receipt) && events.none { it.receiptId == receipt.id } }.sortedBy { it.id }
        val active = views.filter { it.cancellation == null }
        val unknown = active.count { it.receipt.purchasedOn == null || it.receipt.totalCents == null ||
            it.receipt.totalCents !in 0..Money.MAX_CENTS || it.event.currency != "EUR" }
        val sum = active.filter { it.receipt.purchasedOn != null && it.event.currency == "EUR" }
            .mapNotNull { it.receipt.totalCents?.takeIf { value -> value in 0..Money.MAX_CENTS } }
            .fold(BigInteger.ZERO) { total, cents -> total + BigInteger.valueOf(cents) }
        val subtotal = sum.takeIf { it <= BigInteger.valueOf(Money.MAX_CENTS) }?.toLong()
        // Stock links never change a payment. Cancellation IDs do: same-clock ABA must invalidate confirmation.
        val token = WriteRules.fingerprint("EXPENSE_DATA_1", scope.startsOn, scope.endsOn,
            views.sortedBy { it.event.operationId }.map { listOf(it.event.toString(), it.receipt.toString(), it.cancellation?.toString()) },
            unconfirmed.map { it.toString() })
        val status = dao.expenseStatus(scope.startsOn, scope.endsOn)
        val complete = status?.complete == true && status.dataToken == token && unknown == 0 && unconfirmed.isEmpty() && subtotal != null
        ExpenseReport(scope, views, unconfirmed, subtotal, unknown, subtotal.takeIf { complete }, complete,
            status?.complete == true && status.dataToken != token, token,
            WriteRules.fingerprint("EXPENSE_APPROVAL_1", token, status?.operationId))
    }
}
