package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class StockHistoryCursor(val recordedAt: Long, val operationId: String)
internal data class StockHistoryQuery(val batchId: String? = null, val operationId: String? = null,
    val before: StockHistoryCursor? = null)
internal data class StockHistoryPage(val query: StockHistoryQuery, val entries: List<OperationDetails>,
    val older: StockHistoryCursor?)

/** Read-only audit pages over immutable actual-event identities. Never infers an event time. */
internal class StockHistoryReader(private val db: FoodDatabase) {
    companion object { const val PAGE_SIZE = 25 }
    private val tables = arrayOf("operations", "stock_movements", "operation_reversals", "batches",
        "product_versions", "consumption_events", "consumption_allocations")
    fun watch(query: StockHistoryQuery) = db.invalidationTracker.createFlow(*tables, emitInitialState = true)
        .map { read(query) }.distinctUntilChanged()
    fun watchOperation(id: String) = db.invalidationTracker.createFlow(*tables, emitInitialState = true)
        .map { details(id) }.distinctUntilChanged()

    suspend fun read(query: StockHistoryQuery): StockHistoryPage = db.withTransaction {
        require(query.batchId == null || query.batchId.isNotBlank()) { "Charge vollständig auswählen." }
        require(query.operationId == null || query.operationId.isNotBlank()) { "Vorgangskennung vollständig angeben." }
        val operations = db.foodDao().historyOperations(query.batchId, query.operationId,
            query.before?.recordedAt, query.before?.operationId, PAGE_SIZE + 1)
        val visible = operations.take(PAGE_SIZE)
        StockHistoryPage(query, visible.map { details(it.id) },
            visible.lastOrNull()?.takeIf { operations.size > PAGE_SIZE }?.let { StockHistoryCursor(it.recordedAt,it.id) })
    }

    suspend fun details(id: String): OperationDetails = db.withTransaction {
        val dao = db.foodDao()
        OperationDetails(requireNotNull(dao.operation(id)) { "Vorgang nicht gefunden." },dao.reversal(id),
            dao.movementDetails(id),dao.consumptionForOperation(id))
    }
}
