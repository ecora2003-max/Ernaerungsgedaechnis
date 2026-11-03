package de.ernaehrung.demo.data.local

import de.ernaehrung.demo.data.integration.RecipeContextContentBinding
import java.time.Instant
import java.time.LocalDate

/**
 * Projects the app's actual Room-backed shopping needs into an optional recipe
 * context. This is a planning snapshot, NOT a purchase, confirmed offer, or
 * permission to save a recipe. Prices and private receipts are not forwarded.
 *
 * Called within FoodStore's Room transaction, including at review time.
 */
internal class RecipeRoomShoppingSnapshotReader(private val db: FoodDatabase) {
    companion object {
        private const val PREFIX = "LOCAL_ROOM_SHOPPING_V1"
        private const val MAX_LINES = 10_000

        fun scopeId(scope: ShoppingScope): String =
            "${PREFIX}:${scope.startsOn}:${scope.endsOn}:${scope.purchaseOn}"

        /** Only canonical, budget-free planning scopes can be reconstructed. */
        fun scopeFromId(id: String): ShoppingScope? {
            val parts = id.split(':')
            if (parts.size != 4 || parts[0] != PREFIX) return null
            if (parts.drop(1).any { date ->
                    runCatching { LocalDate.parse(date).toString() == date }.getOrDefault(false).not()
                }) return null
            if (parts[1] > parts[2]) return null
            return ShoppingScope(parts[1], parts[2], parts[3])
        }
    }

    suspend fun read(
        scope: ShoppingScope,
        capturedAt: Instant
    ): RecipeContextContentBinding.Snapshot? {
        if (scope.budgetId != null || scopeFromId(scopeId(scope)) != scope) return null
        val asOfMillis = capturedAt.toEpochMilli()
        if (asOfMillis < 0L) return null
        // ShoppingReader internally reads planning, stock, price versions and
        // checklist revisions in one Room transaction. Its price observations
        // are never promoted to approved external offers by this adapter.
        // Freeze the clock at the same instant as the snapshot. A real clock
        // crossing Berlin midnight between capture and nested planning reads
        // must not mix two different days in one transactional context.
        val report = try {
            ShoppingReader(db) { capturedAt.toEpochMilli() }.report(scope)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (report.scope != scope || report.items.size > MAX_LINES) return null
        val rows = report.items.sortedBy { it.need.versionId }
        if (rows.map { it.need.versionId }.toSet().size != rows.size) return null
        val dao = db.foodDao()
        // PlanningReader uses the latest revision of every plan series and
        // allocates earlier needs before the requested window. A future
        // revision (including cancellation) must not silently rewrite the
        // shopping evidence captured at an earlier instant.
        val relevantPlanHistories = dao.allPlans()
            .groupBy { it.seriesId ?: it.id }.values
            .filter { history -> history.any { it.startsOn <= scope.endsOn } }
        // A valid latest revision does not repair a future-dated or reversed
        // earlier revision in the same persisted series. Validate the entire
        // history before using the latest plan as recipe-context evidence.
        if (relevantPlanHistories.any { history ->
                history.any { it.recordedAt !in 0L..asOfMillis } ||
                    history.sortedBy { it.revision }.zipWithNext().any { (older, newer) ->
                        older.recordedAt > newer.recordedAt
                    }
            }) return null
        // A planning need may still reference a superseded product version. Only
        // the current, independently recorded Room identity at capture time can
        // substantiate recipe context; future revisions do not invalidate history.
        val catalogVersions = dao.allVersions()
        val catalogById = catalogVersions.associateBy { it.id }
        val referencedProducts = rows.mapNotNull { catalogById[it.need.versionId]?.productId }.toSet()
        // The latest revision alone cannot establish a trustworthy catalog
        // identity if an earlier persisted revision has an impossible timestamp.
        // Restrict the history gate to products actually used by this context;
        // unrelated catalog edits must not invalidate the recipe review.
        val relevantVersions = catalogVersions.filter { it.productId in referencedProducts }
        val versionHistories = relevantVersions
            .filter { it.recordedAt <= asOfMillis }
            .groupBy { it.productId }
        if (versionHistories.values.any { history ->
                history.any { it.recordedAt < 0L || it.revision <= 0 } ||
                    history.sortedBy { it.revision }.zipWithNext().any { (older, newer) ->
                        older.recordedAt > newer.recordedAt
                    }
            }) return null
        val latestRevisionByProduct = versionHistories
            .mapValues { (_, history) -> history.maxOf { it.revision } }
        // A future-dated successor may legitimately be unknown at capture time.
        // A future-dated *predecessor* of an already current revision cannot:
        // excluding it from the as-of history would conceal reversed chronology.
        if (relevantVersions.any { version ->
                version.recordedAt > asOfMillis &&
                    version.revision <= (latestRevisionByProduct[version.productId] ?: 0)
            }) return null
        val versions = mutableMapOf<String, ProductVersionEntity>()
        for (row in rows) {
            val need = row.need
            if (need.versionId.isBlank() || need.unknownRequirements != 0 ||
                need.knownMissing !in 0..Amounts.MAX || row.staleCheck) return null
            val version = dao.version(need.versionId) ?: return null
            // A future-dated or invalid product revision cannot substantiate
            // a Room shopping need at an earlier recipe-review instant.
            // Planning reports may still show it; this evidence port fails closed.
            if (version.recordedAt !in 0L..asOfMillis ||
                version.productId.isBlank() || version.revision <= 0 ||
                // A synthetic or unverified catalog row is not independently
                // confirmed recipe-planning evidence, even if a plan references it.
                version.source == Source.DEMO ||
                version.quality !in setOf(Quality.SOURCED, Quality.CONFIRMED) ||
                latestRevisionByProduct[version.productId] != version.revision ||
                version.baseUnit != need.unit) return null
            versions[need.versionId] = version
        }
        val revision = WriteRules.fingerprint(
            "RECIPE_ROOM_SHOPPING_REVISION_V1", scopeId(scope), report.asOf,
            relevantPlanHistories.flatten().sortedBy { it.id }.map { plan ->
                listOf(plan.id, plan.seriesId, plan.revision, plan.status.name,
                    plan.startsOn, plan.endsOn, plan.recordedAt)
            },
            // A correction to a historical product revision changes the
            // provenance of the currently selected version as well.
            versionHistories.values.flatten().sortedWith(
                compareBy<ProductVersionEntity> { it.productId }.thenBy { it.revision }
            ).map { version ->
                listOf(version.id, version.productId, version.revision,
                    version.recordedAt, version.source.name, version.quality.name,
                    // Historical identity and ingredient/allergen corrections
                    // must invalidate a previously reviewed shopping context,
                    // even when the currently selected version is unchanged.
                    version.name, version.brand, version.category, version.barcode,
                    version.baseUnit.name, version.packageAmount,
                    version.ingredients, version.allergens, version.sourceDetail,
                    version.changeReason, version.nutritionBasis,
                    version.energyKcal, version.proteinGrams, version.fatGrams,
                    version.carbsGrams, version.fiberGrams, version.sugarGrams,
                    version.saturatedFatGrams, version.saltGrams,
                    version.calciumMg, version.ironMg, version.magnesiumMg,
                    version.iodineMicrograms, version.vitaminB12Micrograms,
                    version.vitaminDMicrograms)
            },
            rows.map { row ->
                val need = row.need
                val version = versions.getValue(need.versionId)
                listOf(need.versionId, version.productId, version.revision,
                    version.recordedAt, version.source.name, version.quality.name,
                    version.baseUnit.name, need.knownMissing, need.unit.name,
                    row.firstNeededOn, row.lastNeededOn, row.checked,
                    row.staleCheck, row.snapshot, row.contentToken,
                    row.offer?.id, row.issues.map { it.name }.sorted())
            }
        )
        val lines = rows.map { row ->
            RecipeContextContentBinding.Line(
                identity = "shopping:${row.need.versionId}",
                versionId = row.need.versionId,
                amountMilli = row.need.knownMissing,
                unit = row.need.unit.label
            )
        }
        return RecipeContextContentBinding.Snapshot(
            RecipeContextContentBinding.Source.SHOPPING,
            scopeId(scope), revision, capturedAt, lines
        )
    }
}
