package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class PlannedNutritionLine(val entry: PlannedEntryEntity, val planVersionId: String,
    val planRevision: Int, val name: String, val referenceRevision: Int?, val nutrition: NutritionResult)
internal data class PlannedNutritionDay(val date: String, val lines: List<PlannedNutritionLine>,
    val nutrition: NutritionResult, val goalVersion: NutritionGoalVersionEntity?, val goals: List<NutritionGoalRange>)
internal data class PlannedNutritionWeek(val startsOn: String, val endsOn: String,
    val days: List<PlannedNutritionDay>, val nutrition: NutritionResult)
internal data class PlanNutritionReport(val startsOn: String, val endsOn: String, val capturedAt: Long,
    val days: List<PlannedNutritionDay>, val preparationsExcluded: Int) {
    /** Calendar weeks clipped to the applied scope. Missing plan days do not
     * acquire invented zero meals, nutrition, or complete-day status.
     */
    fun weeks(): List<PlannedNutritionWeek> = days.groupBy {
        LocalDate.parse(it.date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }.toSortedMap().map { (monday, entries) ->
        PlannedNutritionWeek(maxOf(startsOn, monday.toString()), minOf(endsOn, monday.plusDays(6).toString()),
            entries, NutritionResult.sum(entries.map { it.nutrition }))
    }
}

/** Full reference meal intents of the same latest confirmed plan versions as
 * demand planning. Not remaining demand, actual intake, or a manufacturing yield.
 * A preparation is never counted again as a meal. No ledger write is possible.
 */
internal class PlanNutritionReader(private val db: FoodDatabase, private val now: () -> Long) {
    fun watch(startsOn: String, endsOn: String) = db.invalidationTracker.createFlow(
        "plans", "planned_entries", "plan_series", "product_versions", "recipe_versions", "recipe_ingredients",
        "productions", "batches", "stock_movements", "operation_reversals", "recipe_output_products",
        "nutrition_goal_runs", "nutrition_goal_versions", "nutrition_goal_values", emitInitialState = true
    ).map { report(startsOn, endsOn) }.distinctUntilChanged()

    suspend fun report(startsOn: String, endsOn: String): PlanNutritionReport = db.withTransaction {
        val at = now(); require(at >= 0)
        val planning = PlanningReader(db) { at }.report(startsOn, endsOn)
        val nutrients = NutritionReader(db)
        val goals = NutritionGoalSnapshot(db.nutritionGoalDao().versions(), db.nutritionGoalDao().values())
        val lines = mutableListOf<PlannedNutritionLine>()
        var preparations = 0
        for (plan in planning.plans.filter { it.version.status == EntryStatus.CONFIRMED }) {
            for (entry in plan.entries.filter { it.date in startsOn..endsOn }) {
                if (entry.kind == PlanKind.PREPARATION) { preparations++; continue }
                val preparation = entry.preparationEntryId?.let { id -> plan.entries.singleOrNull { it.id == id } }
                val linkedRecipeId = preparation?.takeIf {
                    it.kind == PlanKind.PREPARATION && it.date <= entry.date && it.unit == BaseUnit.PORTION &&
                        it.productVersionId == null && it.preparationEntryId == null
                }?.recipeVersionId
                val product = entry.productVersionId?.let { db.foodDao().version(it) }
                val recipe = linkedRecipeId?.let { db.foodDao().recipeVersion(it) }
                val amount = entry.amount
                val nutrition = when {
                    amount == null || amount !in 1..Amounts.MAX -> NutritionResult.unknown(NutritionGap.UNKNOWN_AMOUNT)
                    entry.kind == PlanKind.DIRECT_CONSUMPTION && entry.productVersionId != null &&
                        entry.recipeVersionId == null && entry.preparationEntryId == null ->
                        nutrients.referenceProduct(entry.productVersionId, amount, entry.unit)
                    entry.kind == PlanKind.PREPARED_PORTION && entry.recipeVersionId == null && entry.unit == BaseUnit.PORTION -> when {
                        entry.preparationEntryId != null && entry.productVersionId == null && linkedRecipeId != null ->
                            nutrients.recipe(linkedRecipeId).scale(amount, 1000)
                        entry.preparationEntryId == null && entry.productVersionId != null ->
                            nutrients.referenceProduct(entry.productVersionId, amount, entry.unit)
                        else -> NutritionResult.unknown(NutritionGap.MISSING_VERSION)
                    }
                    else -> NutritionResult.unknown(NutritionGap.MISSING_VERSION)
                }
                lines += PlannedNutritionLine(entry, plan.version.id, plan.version.revision,
                    entry.label ?: product?.name ?: recipe?.name ?: "Ungeklärter Planverzehr",
                    product?.revision ?: recipe?.revision, nutrition)
            }
        }
        val days = lines.groupBy { it.entry.date }.toSortedMap().map { (date, entries) ->
            val active = goals.active(date, at)
            PlannedNutritionDay(date, entries.sortedBy { it.entry.id }, NutritionResult.sum(entries.map { it.nutrition }),
                active, active?.let { goals.ranges(it.id) }.orEmpty())
        }
        PlanNutritionReport(startsOn, endsOn, at, days, preparations)
    }
}
