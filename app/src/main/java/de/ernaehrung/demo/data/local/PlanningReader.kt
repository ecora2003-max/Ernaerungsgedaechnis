package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One read transaction and one shared allocation ledger for all active plan identities. No writes. */
internal class PlanningReader(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    fun watch(startsOn: String, endsOn: String) = db.invalidationTracker.createFlow(
        "plans", "planned_entries", "plan_series", "product_versions", "recipe_versions", "recipe_ingredients",
        "batches", "stock_movements", "operation_reversals", "storage_locations", "plan_positions", "plan_executions",
        "operations", "consumption_events", "productions"
    ).map { report(startsOn, endsOn) }.distinctUntilChanged()

    suspend fun report(startsOn: String, endsOn: String): PlanningReport = db.withTransaction {
        WriteRules.date(startsOn); WriteRules.date(endsOn)
        require(startsOn <= endsOn) { "Ende liegt vor dem Beginn." }
        val asOf = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val executions = ExecutionLedger.load(dao)
        val versions = dao.allPlans()
        val entriesByPlan = dao.allPlannedEntries().groupBy { it.planId }
        val plans = versions.groupBy { it.seriesId ?: it.id }.values.map { history ->
            val latest = history.maxBy { it.revision }
            PlanRecord(latest, entriesByPlan[latest.id].orEmpty())
        }.sortedWith(compareBy({ it.version.startsOn }, { it.version.id }))
        val stocks = dao.planningBatches()
        val remaining = stocks.associate { it.batch.id to it.stockAmount.coerceAtLeast(0) }.toMutableMap()
        val locations = stocks.map { it.batch.locationId }.distinct().associateWith { dao.location(it) }
        val products = mutableMapOf<String, ProductVersionEntity?>()
        suspend fun product(id: String): ProductVersionEntity? {
            if (!products.containsKey(id)) products[id] = dao.version(id)
            return products[id]
        }
        fun exclusion(row: BatchRow, date: String): StockExclusionReason? = when {
            row.originReversed -> StockExclusionReason.ORIGIN_REVERSED
            row.batch.release != ReleaseStatus.RELEASED -> StockExclusionReason.NOT_RELEASED
            locations[row.batch.locationId]?.confirmed != true -> StockExclusionReason.LOCATION_UNCONFIRMED
            row.batch.acquiredOn != null && row.batch.acquiredOn > date -> StockExclusionReason.NOT_YET_ACQUIRED
            row.batch.useBy != null && row.batch.useBy < date -> StockExclusionReason.USE_BY_EXPIRED
            row.batch.bestBefore != null && row.batch.bestBefore < date -> StockExclusionReason.BEST_BEFORE_REVIEW
            date > asOf && row.batch.useBy == null && row.batch.bestBefore == null -> StockExclusionReason.FUTURE_SHELF_LIFE_UNKNOWN
            else -> null
        }
        fun allocate(versionIds: Set<String>, required: Long?, date: String): Pair<List<PlannedAllocation>, List<ExcludedStock>> {
            val candidates = stocks.filter { it.batch.versionId in versionIds }.sortedWith(compareBy(
                { listOfNotNull(it.batch.useBy, it.batch.bestBefore).minOrNull() ?: "9999-12-31" }, { it.batch.id }))
            val excluded = candidates.mapNotNull { row -> exclusion(row, date)?.let {
                ExcludedStock(row.batch.id, row.stockAmount, it).takeIf { it.amount > 0 }
            } }
            var needed = required ?: 0L
            val allocations = mutableListOf<PlannedAllocation>()
            if (required != null) candidates.forEach { row ->
                if (exclusion(row, date) == null) {
                    val take = minOf(remaining.getValue(row.batch.id), needed)
                    if (take > 0) {
                        allocations += PlannedAllocation(row.batch.id, take)
                        remaining[row.batch.id] = remaining.getValue(row.batch.id) - take
                        needed -= take
                    }
                }
            }
            return allocations to excluded
        }
        suspend fun demand(versionId: String, amount: Long?, unit: BaseUnit, date: String,
            initialIssues: Set<PlanningIssue> = emptySet()): DemandLine {
            val version = product(versionId)
            val issues = initialIssues.toMutableSet()
            if (version == null || version.baseUnit != unit) issues += PlanningIssue.INVALID_REFERENCE
            if (amount == null) issues += PlanningIssue.UNKNOWN_AMOUNT
            if (amount != null && amount !in 1..Amounts.MAX) issues += PlanningIssue.QUANTITY_RANGE
            val required = amount?.takeIf { it in 1..Amounts.MAX && version?.baseUnit == unit }
            val (allocations, excluded) = allocate(setOf(versionId), required, date)
            val covered = allocations.sumOf { it.amount }
            return DemandLine(versionId, version?.name ?: "Ungeklärte Artikelversion", unit, required,
                covered, required?.let { it - covered }, allocations, excluded, issues)
        }
        val scheduled = plans.filter { it.version.status == EntryStatus.CONFIRMED }.flatMap { plan ->
            plan.entries.map { plan.version to it }
        }.sortedWith(compareBy({ it.second.date }, { if (it.second.kind == PlanKind.PREPARATION) 0 else 1 },
            { it.first.seriesId ?: it.first.id }, { it.second.id }))
        val projected = mutableMapOf<String, Long?>()
        val results = mutableListOf<PlannedEntryResult>()
        for ((plan, entry) in scheduled) {
            val status = executions.status(entry)
            val open = status.remainingAmount
            val executionIssues = buildSet {
                if (status.hasUnknownExecuted) add(PlanningIssue.UNKNOWN_EXECUTION)
                if (status.referenceConflict) add(PlanningIssue.EXECUTION_REFERENCE_CHANGED)
            }
            when (entry.kind) {
                PlanKind.PREPARATION -> {
                    val recipe = entry.recipeVersionId?.let { dao.recipeVersion(it) }
                    val ingredients = recipe?.let { dao.ingredients(it.id) }.orEmpty()
                    val issues = executionIssues.toMutableSet()
                    if (open != 0L) issues += PlanningIssue.STORAGE_UNCHECKED
                    if (recipe == null || ingredients.isEmpty() || entry.unit != BaseUnit.PORTION ||
                        entry.productVersionId != null || entry.preparationEntryId != null) issues += PlanningIssue.INVALID_REFERENCE
                    if (open == null) issues += PlanningIssue.UNKNOWN_AMOUNT
                    projected[entry.id] = open?.takeIf { it in 0..Amounts.MAX && recipe != null && entry.unit == BaseUnit.PORTION && (entry.date >= asOf || it == 0L) }
                    val lines = if (entry.date < asOf || open == 0L) emptyList() else ingredients.map { ingredient ->
                        val scaled = scale(ingredient.amount, open, recipe!!.yieldPortions)
                        val errors = if (ingredient.amount != null && open != null && scaled == null)
                            setOf(PlanningIssue.QUANTITY_RANGE) else emptySet()
                        demand(ingredient.productVersionId, scaled, ingredient.unit, entry.date, errors)
                    }
                    results += PlannedEntryResult(plan.id, entry, recipe?.name ?: "Ungeklärte Zubereitung", lines, issues = issues, execution = status)
                }
                PlanKind.DIRECT_CONSUMPTION, PlanKind.PREPARED_PORTION -> {
                    if (entry.kind == PlanKind.PREPARED_PORTION && entry.preparationEntryId != null) {
                        val prep = entriesByPlan[plan.id].orEmpty().find { it.id == entry.preparationEntryId }
                        val issues = executionIssues.toMutableSet()
                        val valid = prep?.kind == PlanKind.PREPARATION && prep.date <= entry.date &&
                            entry.unit == BaseUnit.PORTION && entry.productVersionId == null && entry.recipeVersionId == null
                        if (!valid) issues += PlanningIssue.INVALID_REFERENCE
                        val (allocations, excluded) = if (valid && entry.date >= asOf && open != 0L)
                            allocate(executions.outputVersions(prep?.positionId), open, entry.date) else emptyList<PlannedAllocation>() to emptyList()
                        val covered = allocations.sumOf { it.amount }
                        val needed = open?.let { it - covered }
                        val available = if (valid) projected[entry.preparationEntryId] else null
                        if (needed != 0L && prep != null && prep.date < asOf && available == null) issues += PlanningIssue.PAST_PREPARATION
                        if (needed == null || needed != 0L && available == null) issues += PlanningIssue.UNKNOWN_AMOUNT
                        val take = if (needed == 0L) 0L else if (available != null && needed != null) minOf(available, needed) else null
                        if (take != null && take > 0 || needed != 0L && available != null) issues += PlanningIssue.PLANNED_ONLY
                        if (take != null && available != null) projected[entry.preparationEntryId] = available - take
                        val name = prep?.recipeVersionId?.let { dao.recipeVersion(it)?.name } ?: "Geplante Fertigportionen"
                        results += PlannedEntryResult(plan.id, entry, name, projectedPortions = take,
                            missingPortions = take?.let { needed!! - it }, issues = issues, execution = status,
                            actualPortionsCovered = covered, portionAllocations = allocations, excludedPortions = excluded)
                    } else {
                        val valid = entry.productVersionId != null && entry.recipeVersionId == null && entry.preparationEntryId == null &&
                            (entry.kind != PlanKind.PREPARED_PORTION || entry.unit == BaseUnit.PORTION)
                        val line = if (valid && entry.date >= asOf && open != 0L) demand(entry.productVersionId!!, open, entry.unit, entry.date) else null
                        results += PlannedEntryResult(plan.id, entry, line?.name ?: "Ungeklärter Planeintrag", listOfNotNull(line),
                            issues = executionIssues + if (valid) emptySet() else setOf(PlanningIssue.INVALID_REFERENCE), execution = status)
                    }
                }
            }
        }
        // Allocate the whole future schedule first. Day/week views cannot reuse stock reserved on earlier dates.
        val visible = results.filter { it.entry.date in startsOn..endsOn }
        val shopping = visible.flatMap { it.demands }.groupBy { it.versionId }.map { (id, lines) ->
            ShoppingNeed(id, lines.first().name, lines.first().unit,
                lines.fold(0L) { sum, line -> Math.addExact(sum, line.missing ?: 0) }, lines.count { it.required == null })
        }.filter { it.knownMissing > 0 || it.unknownRequirements > 0 }.sortedBy { it.name }
        val unknown = results.any { result -> result.issues.any { it in setOf(PlanningIssue.UNKNOWN_AMOUNT, PlanningIssue.INVALID_REFERENCE,
            PlanningIssue.UNKNOWN_EXECUTION, PlanningIssue.EXECUTION_REFERENCE_CHANGED) } ||
            result.demands.any { it.required == null } }
        val presentPositions = plans.flatMap { it.entries }.mapNotNull { it.positionId }.toSet()
        val detached = executions.latest.values.filter { it.assigned && it.positionId !in presentPositions }
            .mapNotNull { link -> entriesByPlan.values.asSequence().flatten().find { it.id == link.entryId } }
            .distinctBy { it.positionId }.sortedBy { it.id }
        PlanningReport(asOf, startsOn, endsOn, plans, versions, visible, shopping, unknown, detached)
    }

    /** Round ingredient demand upwards to the smallest supported quantity; never round missing food down to zero. */
    private fun scale(ingredient: Long?, portions: Long?, yield: Long): Long? {
        if (ingredient == null || portions == null || ingredient <= 0 || portions <= 0 || yield <= 0) return null
        val numerator = BigInteger.valueOf(ingredient) * BigInteger.valueOf(portions)
        val denominator = BigInteger.valueOf(yield)
        val rounded = (numerator + denominator - BigInteger.ONE) / denominator
        return rounded.takeIf { it in BigInteger.ONE..BigInteger.valueOf(Amounts.MAX) }?.toLong()
    }
}
