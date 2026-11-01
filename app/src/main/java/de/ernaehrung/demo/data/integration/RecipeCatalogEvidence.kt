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
        val pieceMassMilliGrams: Long? = null
    )

    fun resolve(
        lines: List<RecipeDraftHostGate.DraftLine>,
        catalog: List<ProductVersionEntity>,
        confirmations: Map<String, ConfirmedLine>,
        nowMillis: Long
    ): Map<String, RecipeDraftHostGate.HostLine> {
        val byVersion = catalog.associateBy { it.id }
        val latest = catalog.groupBy { it.productId }
            .mapValues { (_, versions) -> versions.maxByOrNull { it.revision }?.id }
        return lines.mapNotNull { line ->
            val confirmed = confirmations[line.foodTypeId] ?: return@mapNotNull null
            val version = byVersion[confirmed.productVersionId] ?: return@mapNotNull null
            // Version and user confirmation must be for the exact current immutable row.
            if (confirmed.foodTypeId != line.foodTypeId ||
                confirmed.productRevision != version.revision ||
                confirmed.versionRecordedAt != version.recordedAt ||
                latest[version.productId] != version.id ||
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
                allergens = confirmed.allergens.toMap()
            )
        }.toMap()
    }
}
