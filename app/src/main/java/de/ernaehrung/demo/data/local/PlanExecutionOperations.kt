package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.local.WriteRules.fingerprint
import de.ernaehrung.demo.data.local.WriteRules.replay

internal class PlanExecutionOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    suspend fun link(key: String, input: LinkPlanExecutionInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed && input.reason.isNotBlank()) { "Zuordnung ausdrücklich bestätigen und begründen." }
        val hash = fingerprint("PLAN_LINK_1", input.entryId, input.actualOperationId, input.expectedSnapshot, input.reason, input.deviationAccepted)
        replay(dao, key, hash)?.let { return@withTransaction it }
        val entry = requireNotNull(dao.plannedEntry(input.entryId)) { "Planposition nicht gefunden." }
        val plan = requireNotNull(dao.plan(entry.planId))
        require(plan.status == EntryStatus.CONFIRMED && plan.seriesId != null && dao.latestPlan(plan.seriesId)?.id == plan.id) {
            "Plan wurde geändert oder verworfen. Neueste Planfassung prüfen."
        }
        val position = requireNotNull(entry.positionId) { "Planposition braucht eine bestätigte Identität." }
        val ledger = ExecutionLedger.load(dao)
        val status = ledger.status(entry)
        require(status.snapshot == input.expectedSnapshot) { "Zuordnungen wurden inzwischen geändert. Aktuellen Ausführungsstand erneut prüfen." }
        val source = requireNotNull(ledger.actual(input.actualOperationId)) { "Tatsächlicher Verzehr oder Herstellung nicht gefunden." }
        require(!source.reversed) { "Die tatsächliche Buchung wurde storniert." }
        require(ledger.matches(entry, source)) { "Ist-Ereignis passt nicht zur konkreten Artikel-/Rezeptversion oder verknüpften Herstellung." }
        val previous = ledger.latest[source.operationId]
        require(previous?.assigned != true) { "Diese Ist-Buchung ist bereits einer Planposition zugeordnet. Zuerst diese Zuordnung zurücknehmen." }
        require(input.deviationAccepted || source.date == entry.date && source.amount != null && source.amount == status.remainingAmount) {
            "Abweichenden Termin, Teilmenge, Mehrmenge oder unbekannte Menge bewusst bestätigen und begründen."
        }
        dao.insertOperation(OperationEntity(key, "PLAN_LINK", hash, now(), key))
        dao.insertPlanExecution(PlanExecutionEntity(key, source.operationId, position, entry.id,
            (previous?.revision ?: 0) + 1, true, previous?.operationId, input.reason.trim()))
        key
    }
    suspend fun unlink(key: String, input: UnlinkPlanExecutionInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed && input.reason.isNotBlank()) { "Rücknahme ausdrücklich bestätigen und begründen." }
        val hash = fingerprint("PLAN_UNLINK_1", input.assignmentId, input.reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        val assignment = requireNotNull(dao.planExecution(input.assignmentId)) { "Zuordnung nicht gefunden." }
        val ledger = ExecutionLedger.load(dao)
        require(assignment.assigned && ledger.latest[assignment.actualOperationId]?.operationId == assignment.operationId) {
            "Zuordnung wurde inzwischen verändert oder zurückgenommen. Neu prüfen."
        }
        val source = ledger.actual(assignment.actualOperationId)
        if (source?.kind == "PRODUCTION") {
            val dependent = ledger.latest.values.any { claim ->
                if (!claim.assigned) false else {
                    val actual = ledger.actual(claim.actualOperationId)
                    val entry = dao.plannedEntry(claim.entryId)
                    val prep = entry?.preparationEntryId?.let { dao.plannedEntry(it) }
                    actual != null && !actual.reversed && actual.kind == "CONSUMPTION" &&
                        actual.productVersionId == source.productVersionId && prep?.positionId == assignment.positionId
                }
            }
            require(!dependent) { "Abhängige Portionsverzehre sind noch zugeordnet. Diese Zuordnungen zuerst zurücknehmen." }
        }
        dao.insertOperation(OperationEntity(key, "PLAN_UNLINK", hash, now(), key))
        dao.insertPlanExecution(assignment.copy(operationId = key, revision = assignment.revision + 1,
            assigned = false, previousId = assignment.operationId, reason = input.reason.trim()))
        key
    }
}
