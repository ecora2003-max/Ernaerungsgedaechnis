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
        val allergens: Map<String, Boolean?> = emptyMap(),
        val diets: Map<String, Boolean?> = emptyMap()
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
        nowMillis: Long,
        independentlyBoundContext: Boolean = false,
        preferences: RecipeRequestPreferences = RecipeRequestPreferences()
    ): Prepared {
        preferences.validate(imported.draft.portions)
        require(explicitlyReviewed) { "explicit manual review required" }
        require(nowMillis >= 0L) { "invalid review timestamp" }
        // A separately confirmed review must not predate the draft it approves.
        // The JSON importer tolerates limited generator clock skew, but that
        // tolerance cannot turn a future draft into an already reviewed one.
        val createdAtMillis = imported.createdAt.toInstant().toEpochMilli()
        require(createdAtMillis in 0L..nowMillis) {
            "manual review predates draft creation"
        }
        // A previously parsed draft must not acquire a fresh user confirmation
        // after the import's 24-hour freshness window has expired.
        require(nowMillis - createdAtMillis <= 86_400_000L) {
            "manual review of stale draft"
        }
        val draft = imported.draft
        // The UI must have independently captured and content-bound exactly the
        // requested sources. The FoodStore transaction revalidates them again.
        require((!draft.flags.considerStock && !draft.flags.considerShopping &&
            !draft.flags.considerOffers) || independentlyBoundContext) {
            "context evidence unavailable"
        }
        require(draft.lines.isNotEmpty() && draft.lines.size <= 64)
        val ids = draft.lines.map { it.foodTypeId }
        require(ids.all { it.isNotBlank() } && ids.toSet().size == ids.size)
        require(choices.keys == ids.toSet()) { "each ingredient needs its own review" }
        // Use the exact same syntax as the final host review. In particular,
        // whitespace, format characters and extra delimiters must not survive
        // into a durable manual confirmation checkpoint.
        require(restrictions.all(Gate::isSupportedRestriction)) {
            "unsupported restriction"
        }
        val allergens = restrictions.filter { it.startsWith("ALLERGEN:") }
            .map { it.removePrefix("ALLERGEN:") }.toSet()
        val diets = restrictions.filter { it.startsWith("DIET:") }
            .map { it.removePrefix("DIET:").uppercase() }.toSet()
        val byId = catalog.groupBy { it.id }
        // Select the latest version known at this review instant. A future-dated
        // revision must neither displace a currently valid row nor authorize itself.
        val latestByProduct = catalog.groupBy { it.productId }.mapValues { (_, versions) ->
            val known = versions.filter { it.recordedAt in 0L..nowMillis }
            val highest = known.maxOfOrNull { it.revision }
            known.filter { it.revision == highest }.singleOrNull()?.id
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
                version.revision > 0 && version.recordedAt in 0L..nowMillis &&
                version.source != Source.DEMO &&
                version.quality in setOf(Quality.CONFIRMED, Quality.SOURCED)) {
                "catalog version not independently current and sourced"
            }
            require(selectedVersionIds.add(version.id)) { "duplicate product version" }
            require(choice.basis != null && choice.basis == line.basis &&
                choice.basis != Gate.Basis.UNKNOWN &&
                choice.state in setOf(Gate.State.RAW, Gate.State.COOKED) &&
                (line.state == Gate.State.UNKNOWN || choice.state == line.state)) {
                "basis and preparation state must be manually reviewed consistently"
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
            require(choice.diets.keys == diets) {
                "all confirmed diets need explicit per-line answers (unknown allowed)"
            }
            val state = requireNotNull(choice.state)
            confirmations[line.foodTypeId] = RecipeCatalogEvidence.ConfirmedLine(
                line.foodTypeId, version.id, version.revision, version.recordedAt,
                nowMillis, version.baseUnit, choice.basis, state, choice.allergens.toMap(),
                diets = choice.diets.toMap()
            )
            reviewedStates[line.foodTypeId] = state
        }
        return Prepared(
            Gate.Request(draft.flags, draft.portions, restrictions.toSet(),
                userConfirmedRecipeSave = true, preferences = preferences),
            confirmations, reviewedStates
        )
    }
}
