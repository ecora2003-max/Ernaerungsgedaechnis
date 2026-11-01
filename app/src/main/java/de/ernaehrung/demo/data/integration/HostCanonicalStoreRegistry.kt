package de.ernaehrung.demo.data.integration

import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Read-only historical canonical-store identity adapter for the existing
 * ShoppingOfferGate.ConfirmedStoreRegistry. Both inputs are HOST snapshots;
 * feed names, scraped aliases and candidate rights claims are not evidence.
 *
 * This is an offline, injectable boundary. Production activation still needs
 * persistent independently authenticated store facts, UI provenance, and
 * source-specific APP_DISPLAY rights (checked by a separate gate).
 */
internal class HostCanonicalStoreRegistry(
    private val snapshots: List<StoreSnapshot>,
    private val reviews: Map<String, StoreReview>
) : ShoppingOfferGate.ConfirmedStoreRegistry {
    data class StoreSnapshot(
        val canonicalStoreId: String,
        val retailer: RetailerPolicy.Retailer,
        val regionId: String?,
        val revision: Int,
        val recordedAt: Long,
        val validFrom: LocalDate,
        val validUntil: LocalDate?,
        val independentlyVerified: Boolean
    )

    /** A separate host-reviewed record, not a field copied from a price feed. */
    data class StoreReview(
        val canonicalStoreId: String,
        val retailer: RetailerPolicy.Retailer,
        val regionId: String?,
        val revision: Int,
        val snapshotRecordedAt: Long,
        val confirmedAt: Long,
        val evidenceRef: String
    )

    override fun isConfirmed(
        retailer: RetailerPolicy.Retailer,
        storeId: String,
        regionId: String?,
        asOf: OffsetDateTime
    ): Boolean {
        if (storeId.isBlank() || regionId?.isBlank() == true) return false
        val cutoff = try { asOf.toInstant().toEpochMilli() }
            catch (_: ArithmeticException) { return false }
        if (cutoff < 0) return false
        val review = reviews[storeId] ?: return false
        if (review.canonicalStoreId != storeId || review.retailer != retailer ||
            review.regionId != regionId || review.evidenceRef.isBlank()) return false

        // Historical as-of selection must never use a later revision. Tied
        // latest revisions and duplicate IDs are ambiguous, not list-order wins.
        val known = snapshots.filter {
            it.canonicalStoreId == storeId && it.recordedAt in 0..cutoff
        }
        val revision = known.maxOfOrNull { it.revision } ?: return false
        val snapshot = known.filter { it.revision == revision }.singleOrNull()
            ?: return false
        if (!snapshot.independentlyVerified || snapshot.revision <= 0 ||
            snapshot.retailer != retailer || snapshot.regionId != regionId ||
            snapshot.validUntil?.isBefore(snapshot.validFrom) == true ||
            asOf.toLocalDate().isBefore(snapshot.validFrom) ||
            snapshot.validUntil?.let { asOf.toLocalDate().isAfter(it) } == true) return false

        return review.revision == snapshot.revision &&
            review.snapshotRecordedAt == snapshot.recordedAt &&
            review.confirmedAt >= snapshot.recordedAt &&
            review.confirmedAt <= cutoff
    }
}
