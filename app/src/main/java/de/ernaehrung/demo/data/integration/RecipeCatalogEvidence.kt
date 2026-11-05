package de.ernaehrung.demo.data.integration

import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source

/**
 * Read-only adapter from actual versioned host catalog rows to recipe review facts.
 * A model foodTypeId is never inferred to be a productVersionId. The mapping,
 * unit, basis, preparation state and allergen answers require independent review.
 * ProductVersionEntity.allergens is free text and is NEVER treated as evidence of absence.
 */
internal object RecipeCatalogEvidence {
    data class ConfirmedLine(
        val foodTypeId: String,
        val productVersionId: String,
        val productRevision: Int,
        val versionRecordedAt: Long,
        val confirmedAt: Long,
        val unit: BaseUnit,
        val basis: RecipeDraftHostGate.Basis?,
        val state: RecipeDraftHostGate.State?,
        val allergens: Map<String, Boolean?> = emptyMap(),
        val pieceMassMilliGrams: Long? = null,
        val diets: Map<String, Boolean?> = emptyMap()
    )

    fun resolve(
        lines: List<RecipeDraftHostGate.DraftLine>,
        catalog: List<ProductVersionEntity>,
        confirmations: Map<String, ConfirmedLine>,
        nowMillis: Long,
        // When a draft is available, host confirmation cannot predate its creation.
        // A catalog review performed for an earlier draft is not approval of this one.
        draftCreatedAtMillis: Long? = null
    ): Map<String, RecipeDraftHostGate.HostLine> {
        // Never let associateBy/maxBy silently pick a winner when an upstream
        // catalog projection contains duplicate IDs or conflicting latest revisions.
        // Room normally enforces unique IDs; this boundary must remain safe for
        // injected, migrated or otherwise inconsistent catalog snapshots as well.
        if (nowMillis < 0L || (draftCreatedAtMillis != null &&
                draftCreatedAtMillis !in 0L..nowMillis)) return emptyMap()
        // A host review must not mix confirmations from another draft into the
        // current ingredient scope. Otherwise a stale, unrelated confirmation
        // can travel through preview and consent as if it belonged to this draft.
        val requestedFoodTypes = lines.mapTo(mutableSetOf()) { it.foodTypeId }
        // Do not collapse ambiguous or blank ingredient identities through toMap().
        // This read boundary is also called independently of the JSON import gate.
        if (requestedFoodTypes.size != lines.size || requestedFoodTypes.any { it.isBlank() })
            return emptyMap()
        if (confirmations.keys.any { it !in requestedFoodTypes }) return emptyMap()
        // The manual host review never assigns one catalog version to two
        // different food-type ingredients. Enforce the same invariant at the
        // independent FoodStore/Room preview boundary: direct callers must not
        // bypass that ambiguity check with a crafted confirmation map.
        val confirmedVersions = confirmations.values.map { it.productVersionId }
        if (confirmedVersions.size != confirmedVersions.toSet().size) return emptyMap()
        val byVersion = catalog.groupBy { it.id }.mapNotNull { (id, versions) ->
            versions.singleOrNull()?.let { id to it }
        }.toMap()
        // Resolve identity as of the host's trusted review time, not the newest
        // revision ever present in a possibly future-dated Room/catalog snapshot.
        val latest = catalog.groupBy { it.productId }.mapValues { (_, versions) ->
            val known = versions.filter { it.recordedAt in 0L..nowMillis }
            val highest = known.maxOfOrNull { it.revision }
            known.filter { it.revision == highest }.singleOrNull()
                ?.takeIf { byVersion[it.id] == it }?.id
        }
        return lines.mapNotNull { line ->
            val confirmed = confirmations[line.foodTypeId] ?: return@mapNotNull null
            val version = byVersion[confirmed.productVersionId] ?: return@mapNotNull null
            // Version and user confirmation must be for the exact current immutable row.
            if (confirmed.foodTypeId != line.foodTypeId ||
                version.id.isBlank() || version.productId.isBlank() ||
                version.revision <= 0 ||
                confirmed.productRevision != version.revision ||
                confirmed.versionRecordedAt != version.recordedAt ||
                version.recordedAt !in 0L..nowMillis ||
                latest[version.productId] != version.id ||
                confirmed.confirmedAt !in 0L..nowMillis ||
                (draftCreatedAtMillis != null &&
                    confirmed.confirmedAt < draftCreatedAtMillis) ||
                confirmed.confirmedAt < version.recordedAt ||
                confirmed.confirmedAt > nowMillis ||
                confirmed.unit != version.baseUnit ||
                version.source == Source.DEMO ||
                version.quality !in setOf(Quality.SOURCED, Quality.CONFIRMED)
            ) return@mapNotNull null
            line.foodTypeId to RecipeDraftHostGate.HostLine(
                foodTypeId = line.foodTypeId,
                productVersionId = version.id,
                identityConfirmed = true,
                unit = version.baseUnit,
                basis = confirmed.basis,
                basisConfirmed = confirmed.basis != null,
                state = confirmed.state,
                stateConfirmed = confirmed.state != null,
                confirmedPieceMassMilliGrams = confirmed.pieceMassMilliGrams,
                allergens = confirmed.allergens.toMap(),
                diets = confirmed.diets.toMap()
            )
        }.toMap()
    }
}
