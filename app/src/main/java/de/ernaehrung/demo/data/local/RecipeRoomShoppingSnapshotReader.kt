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
internal class RecipeRoomShoppingSnapshotReader(
    private val db: FoodDatabase,
    private val now: () -> Long
) {
    companion object {
        private const val PREFIX = "LOCAL_ROOM_SHOPPING_V1"
        private const val MAX_LINES = 10_000

        fun scopeId(scope: ShoppingScope): String =
            "$"+"PREFIX:"+"$"+"{scope.startsOn}:"+"$"+"{scope.endsOn}:"+"$"+"{scope.purchaseOn}"

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
        // ShoppingReader internally reads planning, stock, price versions and
        // checklist revisions in one Room transaction. Its price observations
        // are never promoted to approved external offers by this adapter.
        val report = try {
            ShoppingReader(db, now).report(scope)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (report.scope != scope || report.items.size > MAX_LINES) return null
        val rows = report.items.sortedBy { it.need.versionId }
        if (rows.map { it.need.versionId }.toSet().size != rows.size) return null
        val dao = db.foodDao()
        val versions = mutableMapOf<String, ProductVersionEntity>()
        for (row in rows) {
            val need = row.need
            if (need.versionId.isBlank() || need.unknownRequirements != 0 ||
                need.knownMissing !in 0..Amounts.MAX || row.staleCheck) return null
            val version = dao.version(need.versionId) ?: return null
            if (version.baseUnit != need.unit) return null
            versions[need.versionId] = version
        }
        val revision = WriteRules.fingerprint(
            "RECIPE_ROOM_SHOPPING_REVISION_V1", scopeId(scope), report.asOf,
            rows.map { row ->
                val need = row.need
                val version = versions.getValue(need.versionId)
                listOf(need.versionId, version.revision, version.recordedAt,
                    version.baseUnit.name, need.knownMissing, need.unit.name,
                    row.firstNeededOn, row.lastNeededOn, row.checked,
                    row.staleCheck, row.snapshot, row.contentToken,
                    row.offer?.id, row.issues.map { it.name }.sorted())
            }
        )
        val lines = rows.map { row ->
            RecipeContextContentBinding.Line(
                identity = "shopping:"+"$"+"{row.need.versionId}",
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
