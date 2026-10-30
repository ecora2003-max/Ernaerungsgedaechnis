package de.ernaehrung.demo.data.local

data class ActualExecution(val operationId: String, val kind: String, val date: String, val label: String,
    val amount: Long?, val unit: BaseUnit, val productVersionId: String?, val recipeVersionId: String?, val reversed: Boolean)
data class PlanExecutionFact(val link: PlanExecutionEntity, val actual: ActualExecution?) {
    val effective: Boolean get() = link.assigned && actual != null && !actual.reversed
}
data class PlanExecutionStatus(val positionId: String?, val knownExecuted: Long, val hasUnknownExecuted: Boolean,
    val remainingAmount: Long?, val events: List<PlanExecutionFact>, val history: List<PlanExecutionEntity>,
    val snapshot: String, val referenceConflict: Boolean = false)
data class PlanExecutionView(val entry: PlannedEntryEntity, val plan: PlanEntity, val status: PlanExecutionStatus,
    val candidates: List<ActualExecution>, val current: Boolean)
data class LinkPlanExecutionInput(val entryId: String, val actualOperationId: String, val expectedSnapshot: String,
    val reason: String, val deviationAccepted: Boolean = false)
data class UnlinkPlanExecutionInput(val assignmentId: String, val reason: String)
