package de.ernaehrung.demo.data.integration

import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import java.time.OffsetDateTime

/**
 * Read-only, fail-closed bridge from versioned Room catalog rows and separately
 * reviewed article facts into the shopping comparison's host identity gate.
 *
 * A catalog row alone does NOT establish food type, organic status, package basis,
 * store identity, display rights or a purchase. Confirmations must originate from
 * an independent host review; this adapter never accepts feed-provided attestations.
 * A real UI/registry provider and authenticated confirmation provenance are still
 * required before production activation.
 */
internal class HostCatalogArticleRegistry(
    private val versions: List<ProductVersionEntity>,
    private val confirmations: Map<String, ConfirmedArticle>
) : ShoppingPriceComparisonGate.ConfirmedArticleRegistry {
    data class ConfirmedArticle(
        val productVersionId: String,
        val revision: Int,
        val versionRecordedAt: Long,
        val confirmedAt: Long,
        val article: ShoppingComparabilityKernel.Article,
        val organic: Boolean?
    )

    override fun isConfirmed(
        productVersionId: String,
        article: ShoppingComparabilityKernel.Article,
        organic: Boolean?,
        asOf: OffsetDateTime
    ): Boolean {
        if (productVersionId.isBlank()) return false
        val confirmation = confirmations[productVersionId] ?: return false
        if (confirmation.productVersionId != productVersionId ||
            confirmation.article != article || confirmation.organic != organic) return false

        // Duplicate IDs and tied latest revisions must not be resolved by list order.
        val version = versions.filter { it.id == productVersionId }.singleOrNull()
            ?: return false
        val cutoff = try { asOf.toInstant().toEpochMilli() }
            catch (_: ArithmeticException) { return false }
        if (version.productId.isBlank() || version.revision <= 0 ||
            version.recordedAt > cutoff || version.recordedAt < 0 ||
            confirmation.revision != version.revision ||
            confirmation.versionRecordedAt != version.recordedAt ||
            confirmation.confirmedAt < version.recordedAt ||
            confirmation.confirmedAt > cutoff ||
            version.source == Source.DEMO ||
            version.quality !in setOf(Quality.SOURCED, Quality.CONFIRMED)) return false

        // As-of version selection preserves historical identities without using
        // revisions that were not yet recorded at the shopping evidence cutoff.
        val known = versions.filter {
            it.productId == version.productId && it.recordedAt <= cutoff
        }
        val latestRevision = known.maxOfOrNull { it.revision } ?: return false
        if (known.filter { it.revision == latestRevision }.singleOrNull()?.id != productVersionId)
            return false

        // Only exact separately reviewed attributes can be projected. The catalog
        // barcode/brand may corroborate a claim, never silently fill missing facts.
        if (article.foodTypeId.isNullOrBlank() ||
            (article.gtin != null && article.gtin != version.barcode) ||
            (article.brandName != null && article.brandName != version.brand)) return false
        val quantity = try {
            ShoppingUnitRateKernel.confirmedQuantity(article.quantity)
        } catch (_: IllegalArgumentException) { null } ?: return false
        // Piece/package conversions are not derivable from the host base unit.
        val expectedUnit = when (version.baseUnit) {
            BaseUnit.GRAM -> "g"
            BaseUnit.MILLILITRE -> "ml"
            else -> return false
        }
        return quantity.unit == expectedUnit
    }
}
