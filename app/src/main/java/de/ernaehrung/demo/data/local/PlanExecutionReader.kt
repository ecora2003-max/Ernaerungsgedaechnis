package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class PlanExecutionReader(private val db: FoodDatabase) {
    private val dao = db.foodDao()
    fun watch(entryId: String) = db.invalidationTracker.createFlow("plans", "planned_entries", "plan_executions",
        "operations", "operation_reversals", "consumption_events", "productions", "product_versions", "batches")
        .map { view(entryId) }.distinctUntilChanged()

    suspend fun view(entryId: String): PlanExecutionView = db.withTransaction {
        val entry = requireNotNull(dao.plannedEntry(entryId)) { "Planposition nicht gefunden." }
        val plan = requireNotNull(dao.plan(entry.planId))
        val ledger = ExecutionLedger.load(dao)
        val current = plan.status == EntryStatus.CONFIRMED && plan.seriesId != null && dao.latestPlan(plan.seriesId)?.id == plan.id
        val candidates = if (current && entry.positionId != null) dao.actualOperations().mapNotNull { op ->
            ledger.actual(op.id)?.takeIf { !it.reversed && ledger.latest[op.id]?.assigned != true && ledger.matches(entry, it) }
        } else emptyList()
        PlanExecutionView(entry, plan, ledger.status(entry), candidates, current)
    }
}

/** Transaction-scoped facts only. Never reuse a ledger across a database write or a new report. */
internal class ExecutionLedger private constructor(private val dao: FoodDao, val rows: List<PlanExecutionEntity>) {
    val latest = rows.groupBy { it.actualOperationId }.mapValues { (_, history) -> history.maxBy { it.revision } }
    private val actuals = mutableMapOf<String, ActualExecution?>()
    private var baseSnapshot = ""

    companion object {
        suspend fun load(dao: FoodDao): ExecutionLedger {
            val ledger = ExecutionLedger(dao, dao.allPlanExecutions())
            val sources = ledger.rows.map { it.actualOperationId }.distinct().sorted().map {
                val source = ledger.actual(it)
                listOf(it, source?.kind, source?.date, source?.amount, source?.productVersionId, source?.recipeVersionId,
                    dao.reversal(it)?.reversalOperationId)
            }
            ledger.baseSnapshot = WriteRules.fingerprint("EXECUTION_LEDGER_1", ledger.rows.map {
                listOf(it.operationId, it.actualOperationId, it.positionId, it.entryId, it.revision, it.assigned, it.previousId, it.reason)
            }, sources)
            return ledger
        }
    }

    suspend fun actual(id: String): ActualExecution? {
        if (!actuals.containsKey(id)) {
            val op = dao.operation(id)
            val reversed = dao.reversal(id) != null
            actuals[id] = when (op?.kind) {
                "CONSUMPTION" -> dao.consumptionForOperation(id)?.let { meal ->
                    val product = dao.version(meal.productVersionId)
                    product?.let { ActualExecution(id, op.kind, meal.date, meal.meal, meal.amount?.takeIf { it in 1..Amounts.MAX },
                        it.baseUnit, it.id, null, reversed) }
                }
                "PRODUCTION" -> dao.production(id)?.let { production ->
                    val batch = dao.batch(production.outputBatchId)
                    val recipe = dao.recipeVersion(production.recipeVersionId)
                    if (batch == null || recipe == null) null else ActualExecution(id, op.kind, production.producedOn,
                        recipe.name, production.actualPortions.takeIf { it in 1..Amounts.MAX }, BaseUnit.PORTION, batch.versionId, recipe.id, reversed)
                }
                else -> null
            }
        }
        return actuals[id]
    }
    suspend fun facts(positionId: String?): List<PlanExecutionFact> = if (positionId == null) emptyList() else
        latest.values.filter { it.positionId == positionId }.sortedBy { it.actualOperationId }.map { PlanExecutionFact(it, actual(it.actualOperationId)) }

    suspend fun outputVersions(positionId: String?): Set<String> = facts(positionId).filter { it.effective }
        .mapNotNull { it.actual?.takeIf { it.kind == "PRODUCTION" }?.productVersionId }.toSet()

    suspend fun matches(entry: PlannedEntryEntity, source: ActualExecution): Boolean = when (entry.kind) {
        PlanKind.PREPARATION -> source.kind == "PRODUCTION" && entry.recipeVersionId != null && source.recipeVersionId == entry.recipeVersionId &&
            entry.unit == BaseUnit.PORTION && entry.productVersionId == null && entry.preparationEntryId == null
        PlanKind.DIRECT_CONSUMPTION -> source.kind == "CONSUMPTION" && entry.productVersionId != null && source.productVersionId == entry.productVersionId &&
            source.unit == entry.unit && entry.recipeVersionId == null && entry.preparationEntryId == null
        PlanKind.PREPARED_PORTION -> if (source.kind != "CONSUMPTION" || source.unit != BaseUnit.PORTION || entry.unit != BaseUnit.PORTION || entry.recipeVersionId != null) false
            else if (entry.preparationEntryId == null) entry.productVersionId != null && source.productVersionId == entry.productVersionId
            else {
                val prep = dao.plannedEntry(entry.preparationEntryId)
                prep != null && prep.planId == entry.planId && prep.kind == PlanKind.PREPARATION && entry.productVersionId == null && facts(prep.positionId).any {
                    it.effective && it.actual?.kind == "PRODUCTION" && it.actual.productVersionId == source.productVersionId && it.actual.date <= source.date
                }
            }
    }
    suspend fun status(entry: PlannedEntryEntity): PlanExecutionStatus {
        val events = facts(entry.positionId)
        val active = events.filter { it.link.assigned && it.actual?.reversed != true }
        val unknown = active.any { it.actual?.amount == null }
        val total = active.fold(0L) { sum, fact -> Math.addExact(sum, fact.actual?.amount ?: 0) }
        val remaining = if (unknown) null else entry.amount?.let { (it - total).coerceAtLeast(0) }
        val conflict = active.any { it.actual?.let { source -> !matches(entry, source) } ?: true }
        return PlanExecutionStatus(entry.positionId, total, unknown, remaining, events,
            rows.filter { it.positionId == entry.positionId },
            WriteRules.fingerprint("EXECUTION_VIEW_1", baseSnapshot, entry.id, entry.positionId, entry.date,
                entry.kind, entry.productVersionId, entry.recipeVersionId, entry.preparationEntryId, entry.amount, entry.unit), conflict)
    }
}
