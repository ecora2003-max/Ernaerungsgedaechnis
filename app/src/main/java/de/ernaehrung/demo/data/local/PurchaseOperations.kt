package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.local.WriteRules.actualDate
import de.ernaehrung.demo.data.local.WriteRules.date
import de.ernaehrung.demo.data.local.WriteRules.fingerprint
import de.ernaehrung.demo.data.local.WriteRules.id
import de.ernaehrung.demo.data.local.WriteRules.replay

internal class PurchaseOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()

    suspend fun save(key: String, input: PurchaseInput, confirmed: Boolean): String = db.withTransaction {
        confirmation(confirmed, input.reason)
        input.purchasedOn?.let(::date); input.paidCents?.let(Money::valid)
        require(input.lines.size <= 200) { "Höchstens 200 Positionen je manuellem Einkauf." }
        val hash = fingerprint("PURCHASE_1", input.merchant, input.purchasedOn, input.paidCents, input.reason, input.source,
            input.lines.map { listOf(it.originalText, it.interpretedName, it.versionId, it.amount, it.unit, it.totalCents) })
        replay(dao, key, hash)?.let { return@withTransaction it }
        input.purchasedOn?.let { actualDate(it, now()) }
        for (line in input.lines) {
            line.amount?.let(Amounts::positive); line.totalCents?.let(Money::valid)
            require(line.amount == null || line.unit != null) { "Zur Positionsmenge gehört eine Einheit." }
            line.versionId?.let { id ->
                val version = requireNotNull(dao.version(id)) { "Vorgeschlagene Produktversion fehlt." }
                require(line.unit == null || line.unit == version.baseUnit) { "Position und Produktversion haben verschiedene Einheiten." }
            }
        }
        val receiptId = id()
        dao.insertOperation(OperationEntity(key, "PURCHASE", hash, now(), receiptId))
        // The overall payment is confirmed. Individual stock interpretations remain unconfirmed.
        dao.insertReceipt(ReceiptEntity(receiptId, input.merchant?.trim()?.takeIf(String::isNotEmpty), input.purchasedOn,
            input.paidCents, input.source, Quality.CONFIRMED, now()))
        for (line in input.lines) dao.insertReceiptLine(ReceiptLineEntity(id(), receiptId, line.originalText, line.interpretedName,
            line.versionId, line.amount, line.unit, line.totalCents, Quality.UNKNOWN, null))
        dao.insertPurchase(PurchaseEventEntity(key, receiptId, "EUR", input.reason))
        receiptId
    }

    suspend fun confirmReceipt(key: String, receiptId: String, reason: String, confirmed: Boolean): String = db.withTransaction {
        confirmation(confirmed, reason)
        val hash = fingerprint("RECEIPT_PAYMENT_1", receiptId, reason, "EUR")
        replay(dao, key, hash)?.let { return@withTransaction it }
        val receipt = requireNotNull(dao.receipt(receiptId)) { "Beleg fehlt." }
        receipt.purchasedOn?.let { actualDate(it, now()) }; receipt.totalCents?.let(Money::valid)
        require(dao.purchaseForReceipt(receiptId) == null) { "Dieser Beleg besitzt bereits eine Zahlungsbestätigung einschließlich Historie." }
        dao.insertOperation(OperationEntity(key, "PURCHASE", hash, now(), receiptId))
        dao.insertPurchase(PurchaseEventEntity(key, receiptId, "EUR", reason))
        receiptId
    }

    suspend fun bookLine(key: String, input: PurchaseStockInput, confirmed: Boolean): String = db.withTransaction {
        confirmation(confirmed, input.reason)
        require(input.usableBasisConfirmed && input.basisDescription.isNotBlank()) { "Nutzbare Zugangsmenge und Mengenbasis ausdrücklich klären." }
        val b = input.batch
        Amounts.positive(b.amount)
        listOf(b.acquiredOn, b.bestBefore, b.useBy).forEach { it?.let(::date) }
        val hash = fingerprint("PURCHASE_STOCK_1", input.lineId, b.versionId, b.locationId, b.amount, b.acquiredOn,
            b.bestBefore, b.useBy, b.release, b.safetyNote, input.usableBasisConfirmed, input.basisDescription, input.reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        val line = requireNotNull(dao.receiptLine(input.lineId)) { "Belegposition fehlt." }
        val purchase = requireNotNull(dao.purchaseForReceipt(line.receiptId)) { "Tatsächlichen Einkauf zuerst bestätigen." }
        require(dao.reversal(purchase.operationId) == null) { "Einkaufsbuchung wurde storniert." }
        require(line.stockOperationId == null && dao.receiptBooking(line.id) == null) { "Diese Position wurde bereits gebucht. Die Historie bleibt auch nach Storno erhalten." }
        val receipt = requireNotNull(dao.receipt(line.receiptId))
        // No invented date and no automatic conversion from gross package weight to usable stock.
        require(receipt.purchasedOn != null && b.acquiredOn == receipt.purchasedOn) { "Bekanntes Kaufdatum als Zugangsdatum verwenden." }
        actualDate(receipt.purchasedOn, now())
        requireNotNull(dao.version(b.versionId)) { "Konkrete Produktversion wählen." }
        require(dao.location(b.locationId)?.confirmed == true) { "Tatsächlich vorhandenen Lagerort wählen." }
        val batchId = id()
        dao.insertOperation(OperationEntity(key, "RECEIPT", hash, now(), batchId))
        dao.insertBatch(BatchEntity(batchId, b.versionId, b.locationId, key, b.acquiredOn, b.bestBefore, b.useBy,
            release = b.release, safetyNote = b.safetyNote))
        dao.insertMovement(StockMovementEntity(id(), batchId, key, b.amount, MovementKind.RECEIPT, null,
            Source.USER, "Bestätigte Belegposition: ${input.reason}", now(), occurredOn = b.acquiredOn))
        dao.insertReceiptBooking(ReceiptStockBookingEntity(line.id, key, input.basisDescription, input.reason))
        batchId
    }

    /** Corrects an erroneous record, not a real refund or a return of food. */
    suspend fun cancel(key: String, input: ReversalInput, confirmed: Boolean): String = db.withTransaction {
        confirmation(confirmed, input.reason); date(input.date)
        val hash = fingerprint("PURCHASE_CANCEL_1", input.originalOperationId, input.date, input.reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        actualDate(input.date, now())
        val purchase = requireNotNull(dao.purchase(input.originalOperationId)) { "Einkaufsereignis fehlt." }
        require(dao.reversal(purchase.operationId) == null) { "Einkaufsereignis bereits storniert." }
        val receipt = requireNotNull(dao.receipt(purchase.receiptId))
        require(receipt.purchasedOn == null || input.date >= receipt.purchasedOn) { "Storno liegt vor dem Kauf." }
        for (line in dao.receiptLines(receipt.id)) {
            val stockId = dao.receiptBooking(line.id)?.operationId ?: line.stockOperationId
            require(stockId == null || dao.reversal(stockId) != null) { "Gebuchte Lagerzugänge zuerst samt abhängigen Vorgängen prüfen und stornieren." }
        }
        dao.insertOperation(OperationEntity(key, "REVERSAL", hash, now(), purchase.operationId))
        dao.insertReversal(OperationReversalEntity(purchase.operationId, key, input.date, input.reason))
        purchase.operationId
    }

    suspend fun setStatus(key: String, input: ExpenseStatusInput, confirmed: Boolean): String = db.withTransaction {
        confirmation(confirmed, input.reason)
        val hash = fingerprint("EXPENSE_STATUS_1", input.scope.startsOn, input.scope.endsOn, input.complete, input.expectedSnapshot, input.reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        val current = ExpenseReader(db, now).report(input.scope)
        require(current.snapshot == input.expectedSnapshot) { "Ausgaben oder Abgleich wurden geändert. Aktuellen Zeitraum erneut prüfen." }
        require(!input.complete || current.unknownPurchases == 0 && current.unconfirmedReceipts.isEmpty() && current.knownSubtotalCents != null) {
            "Offene Kaufdaten, Beträge, Belegzahlungen oder Zahlenbereiche zuerst klären."
        }
        val previous = dao.expenseStatus(input.scope.startsOn, input.scope.endsOn)
        dao.insertOperation(OperationEntity(key, "EXPENSE_STATUS", hash, now(), key))
        dao.insertExpenseStatus(ExpenseStatusEntity(key, input.scope.startsOn, input.scope.endsOn,
            (previous?.revision ?: 0) + 1, previous?.operationId, input.complete, current.dataToken, input.reason))
        key
    }

    private fun confirmation(confirmed: Boolean, reason: String) {
        require(confirmed) { "Tatsächlichen Vorgang ausdrücklich bestätigen." }
        require(reason.isNotBlank()) { "Begründung fehlt." }
    }
}
