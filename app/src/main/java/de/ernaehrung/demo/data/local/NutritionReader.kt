package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Consistent derived views of immutable events. No cached totals or second writable truth. */
internal class NutritionReader(private val db: FoodDatabase) {
    private val dao = db.foodDao()
    fun watchDay(date: String): Flow<DayNutrition> = invalidations().map { day(date) }.distinctUntilChanged()
    fun watchRecipe(id: String): Flow<NutritionResult> = invalidations().map { recipe(id) }.distinctUntilChanged()
    private fun invalidations() = db.invalidationTracker.createFlow("product_versions", "batches", "stock_movements",
        "productions", "recipe_versions", "recipe_ingredients", "consumption_events", "operation_reversals", "diary_days", "operations")

    suspend fun day(date: String): DayNutrition = db.withTransaction {
        WriteRules.date(date)
        val resolver = Resolver()
        val meals = dao.activeMeals(date).map { event ->
            val version = dao.version(event.productVersionId)
            MealNutrition(event, version?.name ?: "Artikelversion fehlt", version?.baseUnit ?: BaseUnit.GRAM, version?.revision,
                resolver.version(event.productVersionId, event.amount, version?.baseUnit ?: BaseUnit.GRAM))
        }
        val storedDay = dao.day(date)
        val status = storedDay?.status ?: if (meals.isEmpty()) DayStatus.OPEN else DayStatus.PARTIAL
        val snapshot = WriteRules.fingerprint(NutritionResult.CALCULATION_VERSION, date, status, storedDay?.confirmedAt,
            dao.dayStatusEvents(date).map { listOf(it.id, it.kind) },
            meals.map { meal -> listOf(meal.event.id, meal.event.operationId, meal.event.productVersionId, meal.event.amount,
                Nutrient.entries.map { n -> meal.nutrition[n].let { listOf(it.knownAmount?.stripTrailingZeros()?.toPlainString(), it.knownCount,
                    it.missingCount, it.gaps.map { g -> g.name }.sorted(), it.qualities.map { q -> q.name }.sorted()) } }) })
        DayNutrition(date, status, storedDay?.confirmedAt, meals, NutritionResult.sum(meals.map { it.nutrition }), snapshot)
    }

    /** Recipe preview is per one planned portion; it never becomes an actual consumption. */
    suspend fun recipe(id: String): NutritionResult = db.withTransaction {
        val recipe = dao.recipeVersion(id) ?: return@withTransaction NutritionResult.unknown(NutritionGap.MISSING_VERSION)
        val items = dao.ingredients(id)
        if (items.isEmpty()) return@withTransaction NutritionResult.unknown(NutritionGap.MISSING_VALUE)
        val resolver = Resolver()
        NutritionResult.sum(items.map { resolver.version(it.productVersionId, it.amount, it.unit) }).scale(1000, recipe.yieldPortions)
    }

    /** Cache only inside this transaction. A path detects invalid recursive production references. */
    private inner class Resolver {
        private val bases = mutableMapOf<String, NutritionResult>()
        private val path = mutableSetOf<String>()
        suspend fun version(id: String, amount: Long?, unit: BaseUnit): NutritionResult {
            val version = dao.version(id) ?: return NutritionResult.unknown(NutritionGap.MISSING_VERSION)
            if (version.baseUnit != unit) return NutritionResult.unknown(NutritionGap.UNIT_MISMATCH)
            if (amount == null || amount <= 0) return NutritionResult.unknown(NutritionGap.UNKNOWN_AMOUNT)
            val records = dao.productionsForVersion(id)
            if (records.isEmpty()) return NutritionResult.product(version, amount, unit)
            if (unit != BaseUnit.PORTION || records.size != 1) return NutritionResult.unknown(NutritionGap.INVALID_PRODUCTION)
            if (id in path) return NutritionResult.unknown(NutritionGap.CYCLE)
            val record = records.single()
            if (record.actualPortions <= 0) return NutritionResult.unknown(NutritionGap.INVALID_PRODUCTION)
            if (dao.reversal(record.operationId) != null) return NutritionResult.unknown(NutritionGap.REVERSED_PRODUCTION)
            val total = bases[id] ?: run {
                path.add(id)
                try { production(record).also { bases[id] = it } } finally { path.remove(id) }
            }
            return total.scale(amount, record.actualPortions)
        }
        private suspend fun production(record: ProductionEntity): NutritionResult {
            if (record.actualPortions <= 0) return NutritionResult.unknown(NutritionGap.INVALID_PRODUCTION)
            val movements = dao.movements(record.operationId)
            val outputs = movements.filter { it.kind == MovementKind.PRODUCTION_OUTPUT }
            if (outputs.size != 1 || outputs.single().batchId != record.outputBatchId || outputs.single().delta != record.actualPortions)
                return NutritionResult.unknown(NutritionGap.INVALID_PRODUCTION)
            val inputs = movements.filter { it.kind == MovementKind.PRODUCTION_INPUT }
            if (inputs.isEmpty() || inputs.any { it.delta >= 0 || it.delta == Long.MIN_VALUE }) return NutritionResult.unknown(NutritionGap.INVALID_PRODUCTION)
            return NutritionResult.sum(inputs.map { movement ->
                val batch = dao.batch(movement.batchId)
                val product = batch?.let { dao.version(it.versionId) }
                if (product == null) NutritionResult.unknown(NutritionGap.MISSING_VERSION)
                else version(product.id, -movement.delta, product.baseUnit)
            })
        }
    }
}
