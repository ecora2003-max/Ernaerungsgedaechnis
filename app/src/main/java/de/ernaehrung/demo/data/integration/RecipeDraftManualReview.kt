package de.ernaehrung.demo.data.integration

import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate

/**
 * Manual, independent host-catalog evidence acquisition for an untrusted v1 draft.
 * A draft's productRef, sourceStatus, quantity.confirmed and allergenNotes are never
 * used as evidence. No FoodStore write or network call is possible here.
 */
internal object RecipeDraftManualReview {
    data class Choice(
        val versionId: String,
        val basis: Gate.Basis?,
        val state: Gate.State?,
        /** null means unknown; false requires an explicit negative user review. */
        val allergens: Map<String, Boolean?> = emptyMap()
    )
    data class Prepared(
        val request: Gate.Request,
        val confirmations: Map<String, RecipeCatalogEvidence.ConfirmedLine>,
        val reviewedStates: Map<String, Gate.State>
    )

    fun prepare(
        imported: RecipeDraftV1Import.Imported,
        catalog: List<ProductVersionEntity>,
        choices: Map<String, Choice>,
        restrictions: Set<String>,
        explicitlyReviewed: Boolean,
        nowMillis: Long
    ): Prepared {
        require(explicitlyReviewed) { "explicit manual review required" }
        val draft = imported.draft
        // No stock/list/offer snapshot or digest has been independently acquired here.
        require(!draft.flags.considerStock && !draft.flags.considerShopping &&
            !draft.flags.considerOffers) { "context evidence unavailable" }
        require(draft.lines.isNotEmpty() && draft.lines.size <= 64)
        val ids = draft.lines.map { it.foodTypeId }
        require(ids.all { it.isNotBlank() } && ids.toSet().size == ids.size)
        require(choices.keys == ids.toSet()) { "each ingredient needs its own review" }
        val allergens = restrictions.filter { it.startsWith("ALLERGEN:") }
            .map { it.removePrefix("ALLERGEN:") }.toSet()
        require(restrictions.all {
            (it.startsWith("ALLERGEN:") && it.length > 9) ||
                (it.startsWith("FOOD:") && it.length > 5)
        }) { "unsupported restriction" }
        val byId = catalog.groupBy { it.id }
        val latestByProduct = catalog.groupBy { it.productId }.mapValues { (_, versions) ->
            versions.filter { it.revision == versions.maxOf { row -> row.revision } }
                .singleOrNull()?.id
        }
        val confirmations = linkedMapOf<String, RecipeCatalogEvidence.ConfirmedLine>()
        val reviewedStates = linkedMapOf<String, Gate.State>()
        val selectedVersionIds = mutableSetOf<String>()
        for (line in draft.lines) {
            val choice = requireNotNull(choices[line.foodTypeId])
            val version = byId[choice.versionId]?.singleOrNull()
                ?: throw IllegalArgumentException("missing or duplicate catalog version")
            require(version.productId.isNotBlank() &&
                latestByProduct[version.productId] == version.id &&
                version.revision > 0 && version.recordedAt <= nowMillis &&
                version.source != Source.DEMO &&
                version.quality in setOf(Quality.CONFIRMED, Quality.SOURCED)) {
                "catalog version not independently current and sourced"
            }
            require(selectedVersionIds.add(version.id)) { "duplicate product version" }
            require(choice.basis != null && choice.basis == line.basis &&
                choice.basis != Gate.Basis.UNKNOWN &&
                choice.state in setOf(Gate.State.RAW, Gate.State.COOKED)) {
                "basis and preparation state must be manually reviewed"
            }
            val expectedUnit = when (line.unit) {
                Gate.UnitCode.G, Gate.UnitCode.PIECE -> BaseUnit.GRAM
                Gate.UnitCode.ML -> BaseUnit.MILLILITRE
                Gate.UnitCode.PORTION -> BaseUnit.PORTION
            }
            require(version.baseUnit == expectedUnit) { "catalog unit mismatch" }
            require(choice.allergens.keys == allergens) {
                "all restricted allergens need explicit per-line answers (unknown allowed)"
            }
            val state = requireNotNull(choice.state)
            confirmations[line.foodTypeId] = RecipeCatalogEvidence.ConfirmedLine(
                line.foodTypeId, version.id, version.revision, version.recordedAt,
                nowMillis, version.baseUnit, choice.basis, state, choice.allergens.toMap()
            )
            reviewedStates[line.foodTypeId] = state
        }
        return Prepared(
            Gate.Request(draft.flags, draft.portions, restrictions.toSet(),
                userConfirmedRecipeSave = true),
            confirmations, reviewedStates
        )
    }
}
