package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.local.WriteRules.actualDate
import de.ernaehrung.demo.data.local.WriteRules.available
import de.ernaehrung.demo.data.local.WriteRules.batchDate
import de.ernaehrung.demo.data.local.WriteRules.fingerprint
import de.ernaehrung.demo.data.local.WriteRules.id
import de.ernaehrung.demo.data.local.WriteRules.replay

data class TransferInput(val batchId: String, val targetLocationId: String, val amount: Long,
    val date: String, val reason: String, val conditionsConfirmed: Boolean = false)
data class WasteInput(val batchId: String, val amount: Long, val date: String, val reason: String)
data class StockCountInput(val batchId: String, val expectedBefore: Long, val countedAmount: Long,
    val date: String, val reason: String)
data class ReversalInput(val originalOperationId: String, val date: String, val reason: String)

internal class StockOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    suspend fun transfer(key: String, input: TransferInput, confirmed: Boolean): String = db.withTransaction {
        validate(input.date, input.reason, confirmed)
        Amounts.positive(input.amount)
        val hash = fingerprint("TRANSFER", input.batchId, input.targetLocationId, input.amount, input.date, input.reason, input.conditionsConfirmed)
        replay(dao, key, hash)?.let { return@withTransaction it }
        actualDate(input.date, now())
        val from = available(dao, input.batchId, input.amount, input.date, false)
        val target = requireNotNull(dao.location(input.targetLocationId)) { "Ziellager nicht gefunden." }
        require(target.confirmed) { "Ziellager ist nicht als vorhanden bestätigt." }
        require(from.locationId != target.id) { "Quell- und Ziellager müssen verschieden sein." }
        val destination = id()
        dao.insertOperation(OperationEntity(key, "TRANSFER", hash, now(), destination))
        val release = if (from.release == ReleaseStatus.RELEASED && !input.conditionsConfirmed) ReleaseStatus.UNCHECKED else from.release
        dao.insertBatch(from.copy(id = destination, locationId = target.id, originOperationId = key, parentBatchId = from.id,
            release = release, safetyNote = listOfNotNull(from.safetyNote,
                if (input.conditionsConfirmed) "Zielbedingungen ausdrücklich geprüft: ${input.reason}" else "Lagerbedingungen am Ziel ungeklärt").joinToString("\n")))
        movement(key, from.id, -input.amount, MovementKind.TRANSFER, input.date, input.reason)
        movement(key, destination, input.amount, MovementKind.TRANSFER, input.date, input.reason)
        destination
    }

    suspend fun waste(key: String, input: WasteInput, confirmed: Boolean): String = db.withTransaction {
        validate(input.date, input.reason, confirmed); Amounts.positive(input.amount)
        val hash = fingerprint("WASTE", input.batchId, input.amount, input.date, input.reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        actualDate(input.date, now()); available(dao, input.batchId, input.amount, input.date, false)
        dao.insertOperation(OperationEntity(key, "WASTE", hash, now(), input.batchId))
        movement(key, input.batchId, -input.amount, MovementKind.WASTE, input.date, input.reason)
        input.batchId
    }

    suspend fun correctStock(key: String, input: StockCountInput, confirmed: Boolean): String = db.withTransaction {
        validate(input.date, input.reason, confirmed)
        require(input.expectedBefore in 0..Amounts.MAX && input.countedAmount in 0..Amounts.MAX) { "Bestand außerhalb des zulässigen Bereichs." }
        val hash = fingerprint("STOCK_COUNT", input.batchId, input.expectedBefore, input.countedAmount, input.date, input.reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        actualDate(input.date, now())
        val batch = requireNotNull(dao.batch(input.batchId)) { "Charge nicht gefunden." }
        batchDate(dao, batch, input.date)
        require(dao.balance(batch.id) == input.expectedBefore) { "Bestand hat sich geändert. Aktuellen Bestand erneut prüfen." }
        val delta = input.countedAmount - input.expectedBefore
        require(delta != 0L) { "Gezählter und gespeicherter Bestand sind bereits gleich." }
        dao.insertOperation(OperationEntity(key, "STOCK_COUNT", hash, now(), batch.id))
        movement(key, batch.id, delta, MovementKind.CORRECTION, input.date, input.reason)
        batch.id
    }

    suspend fun reverse(key: String, input: ReversalInput, confirmed: Boolean): String = db.withTransaction {
        validate(input.date, input.reason, confirmed)
        val hash = fingerprint("REVERSAL", input.originalOperationId, input.date, input.reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        actualDate(input.date, now())
        val original = requireNotNull(dao.operation(input.originalOperationId)) { "Ursprungsvorgang nicht gefunden." }
        require(original.kind in setOf("RECEIPT", "TRANSFER", "WASTE", "STOCK_COUNT", "PRODUCTION", "CONSUMPTION")) { "Dieser Vorgang kann nicht storniert werden." }
        require(dao.reversal(original.id) == null) { "Ursprungsvorgang wurde bereits storniert." }
        val movements = dao.movements(original.id)
        val meal = dao.consumptionForOperation(original.id)
        require(meal == null || meal.date <= input.date) { "Storno liegt vor dem Verzehr." }
        for ((batchId, entries) in movements.groupBy { it.batchId }) {
            val batch = requireNotNull(dao.batch(batchId))
            batchDate(dao, batch, input.date)
            if (batch.originOperationId == original.id) {
                require(!dao.hasActiveDependentMovement(batchId, original.id)) { "Es gibt abhängige Buchungen. Diese zuerst stornieren." }
            }
            val delta = entries.sumOf { it.delta }
            require(dao.balance(batchId) - delta in 0..Amounts.MAX) { "Storno würde einen unzulässigen Bestand erzeugen. Abhängige Buchungen zuerst prüfen." }
        }
        dao.insertOperation(OperationEntity(key, "REVERSAL", hash, now(), original.id))
        dao.insertReversal(OperationReversalEntity(original.id, key, input.date, input.reason))
        movements.forEach { old ->
            dao.insertMovement(StockMovementEntity(id(), old.batchId, key, -old.delta, MovementKind.CORRECTION,
                null, Source.USER, input.reason, now(), reversesId = old.id, occurredOn = input.date))
        }
        meal?.let { dao.saveDay(DiaryDayEntity(it.date, DayStatus.PARTIAL, null)) }
        original.id
    }

    private suspend fun movement(key: String, batch: String, delta: Long, kind: MovementKind, date: String, reason: String) {
        require(dao.balance(batch) + delta in 0..Amounts.MAX) { "Unzulässiger Bestand. Es wurde nichts gebucht." }
        dao.insertMovement(StockMovementEntity(id(), batch, key, delta, kind, null, Source.USER, reason, now(), occurredOn = date))
    }
    private fun validate(date: String, reason: String, confirmed: Boolean) {
        require(confirmed) { "Tatsächlichen Vorgang ausdrücklich bestätigen." }
        WriteRules.date(date)
        require(reason.isNotBlank()) { "Begründung fehlt." }
    }
}
