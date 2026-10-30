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
        "batches", "stock_movements", "operation_reversals", "storage_locations"
    ).map { report(startsOn, endsOn) }.distinctUntilChanged()

    suspend fun report(startsOn: String, endsOn: String): PlanningReport = db.withTransaction {
        WriteRules.date(startsOn); WriteRules.date(endsOn)
        require(startsOn <= endsOn) { "Ende liegt vor dem Beginn." }
        val asOf = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
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
        suspend fun demand(versionId: String, amount: Long?, unit: BaseUnit, date: String,
            initialIssues: Set<PlanningIssue> = emptySet()): DemandLine {
            val version = product(versionId)
            val issues = initialIssues.toMutableSet()
            if (version == null || version.baseUnit != unit) issues += PlanningIssue.INVALID_REFERENCE
            if (amount == null) issues += PlanningIssue.UNKNOWN_AMOUNT
            if (amount != null && amount !in 1..Amounts.MAX) issues += PlanningIssue.QUANTITY_RANGE
            val required = amount?.takeIf { it in 1..Amounts.MAX && version?.baseUnit == unit }
            val candidates = stocks.filter { it.batch.versionId == versionId }.sortedWith(compareBy(
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
            return DemandLine(versionId, version?.name ?: "Ungeklärte Artikelversion", unit, required,
                allocations.sumOf { it.amount }, required?.let { needed }, allocations, excluded, issues)
        }
        val scheduled = plans.filter { it.version.status == EntryStatus.CONFIRMED }.flatMap { plan ->
            plan.entries.filter { it.date >= asOf }.map { plan.version to it }
        }.sortedWith(compareBy({ it.second.date }, { if (it.second.kind == PlanKind.PREPARATION) 0 else 1 },
            { it.first.seriesId ?: it.first.id }, { it.second.id }))
        val projected = mutableMapOf<String, Long?>()
        val results = mutableListOf<PlannedEntryResult>()
        for ((plan, entry) in scheduled) {
            when (entry.kind) {
                PlanKind.PREPARATION -> {
                    val recipe = entry.recipeVersionId?.let { dao.recipeVersion(it) }
                    val ingredients = recipe?.let { dao.ingredients(it.id) }.orEmpty()
                    val issues = mutableSetOf(PlanningIssue.STORAGE_UNCHECKED)
                    if (recipe == null || ingredients.isEmpty() || entry.unit != BaseUnit.PORTION ||
                        entry.productVersionId != null || entry.preparationEntryId != null) issues += PlanningIssue.INVALID_REFERENCE
                    if (entry.amount == null) issues += PlanningIssue.UNKNOWN_AMOUNT
                    projected[entry.id] = entry.amount?.takeIf { it in 1..Amounts.MAX && recipe != null && entry.unit == BaseUnit.PORTION }
                    val lines = ingredients.map { ingredient ->
                        val scaled = scale(ingredient.amount, entry.amount, recipe!!.yieldPortions)
                        val errors = if (ingredient.amount != null && entry.amount != null && scaled == null)
                            setOf(PlanningIssue.QUANTITY_RANGE) else emptySet()
                        demand(ingredient.productVersionId, scaled, ingredient.unit, entry.date, errors)
                    }
                    results += PlannedEntryResult(plan.id, entry, recipe?.name ?: "Ungeklärte Zubereitung", lines, issues = issues)
                }
                PlanKind.DIRECT_CONSUMPTION, PlanKind.PREPARED_PORTION -> {
                    if (entry.kind == PlanKind.PREPARED_PORTION && entry.preparationEntryId != null) {
                        val prep = entriesByPlan[plan.id].orEmpty().find { it.id == entry.preparationEntryId }
                        val issues = mutableSetOf(PlanningIssue.PLANNED_ONLY)
                        val valid = prep?.kind == PlanKind.PREPARATION && prep.date <= entry.date &&
                            entry.unit == BaseUnit.PORTION && entry.productVersionId == null && entry.recipeVersionId == null
                        if (!valid) issues += PlanningIssue.INVALID_REFERENCE
                        if (prep != null && prep.date < asOf) issues += PlanningIssue.PAST_PREPARATION
                        val available = if (valid) projected[entry.preparationEntryId] else null
                        if (entry.amount == null || available == null) issues += PlanningIssue.UNKNOWN_AMOUNT
                        val take = if (available != null && entry.amount != null) minOf(available, entry.amount) else null
                        if (take != null) projected[entry.preparationEntryId] = available!! - take
                        val name = prep?.recipeVersionId?.let { dao.recipeVersion(it)?.name } ?: "Geplante Fertigportionen"
                        results += PlannedEntryResult(plan.id, entry, name, projectedPortions = take,
                            missingPortions = take?.let { entry.amount!! - it }, issues = issues)
                    } else {
                        val valid = entry.productVersionId != null && entry.recipeVersionId == null && entry.preparationEntryId == null &&
                            (entry.kind != PlanKind.PREPARED_PORTION || entry.unit == BaseUnit.PORTION)
                        val line = if (valid) demand(entry.productVersionId!!, entry.amount, entry.unit, entry.date) else null
                        results += PlannedEntryResult(plan.id, entry, line?.name ?: "Ungeklärter Planeintrag", listOfNotNull(line),
                            issues = if (valid) emptySet() else setOf(PlanningIssue.INVALID_REFERENCE))
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
        val unknown = results.any { result -> result.issues.any { it in setOf(PlanningIssue.UNKNOWN_AMOUNT, PlanningIssue.INVALID_REFERENCE) } ||
            result.demands.any { it.required == null } }
        PlanningReport(asOf, startsOn, endsOn, plans, versions, visible, shopping, unknown)
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
