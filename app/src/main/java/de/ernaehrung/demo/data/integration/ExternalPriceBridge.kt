package de.ernaehrung.demo.data.integration

import java.time.LocalDate
import java.time.OffsetDateTime

/** ADR-016/018: scope is not a licence to fetch, cache or display retailer data.
 * Pinned EG-Architecture@18794be978cc324e5366996178d368929a4a0fa4
 * retailer policy 1.0.3, blob 7ed2ef6918c8fdcdb9c482cb0a38eb38e5f6852c.
 */
object RetailerPolicy {
    enum class Retailer { ALDI_NORD, ALDI_SUED, REWE, LIDL, PENNY, KAUFLAND, EDEKA, NETTO_MD }
    enum class Capability { TARGETED_PRODUCT_PRICE_QUERY, PUBLISHED_OFFER_COLLECTION, PUBLISHED_OFFER_CATALOG_DISCOVERY }

    private val fourCatalogRetailers = setOf(Retailer.ALDI_NORD, Retailer.ALDI_SUED, Retailer.REWE, Retailer.LIDL)

    fun supports(retailer: Retailer, capability: Capability): Boolean = when (capability) {
        Capability.PUBLISHED_OFFER_COLLECTION -> true
        Capability.TARGETED_PRODUCT_PRICE_QUERY, Capability.PUBLISHED_OFFER_CATALOG_DISCOVERY ->
            retailer in fourCatalogRetailers
    }
}

/** A pure host boundary: no IO, Room access, implicit product matching or write events. */
object ExternalPriceBridge {
    enum class UsageContext { APP_DISPLAY, RESEARCH_ONLY, PRIVATE_USER_HISTORY }
    enum class RightsStatus { PERMITTED, UNVERIFIED, RESTRICTED }
    enum class Origin { EXTERNAL, USER_CONFIRMED_RECEIPT, SYNTHETIC }
    enum class Unit { GRAM, MILLILITRE, PORTION }
    enum class Gap {
        UNSUPPORTED_CAPABILITY, NON_PRICE_CAPABILITY, NON_DISPLAY_CONTEXT, INVALID_SOURCE, MISSING_RIGHTS,
        PRODUCT_IDENTITY, SCOPE_MISMATCH, DATE_INVALID, PRICE_UNKNOWN, QUANTITY_UNKNOWN,
        PRICE_CONDITIONS_UNKNOWN, DUPLICATE_OBSERVATION, LIMIT_EXCEEDED
    }

    data class Request(
        val retailer: RetailerPolicy.Retailer,
        val capability: RetailerPolicy.Capability,
        val productVersionId: String,
        val purchaseOn: LocalDate,
        val asOf: OffsetDateTime,
        val storeId: String? = null,
        val regionId: String? = null,
        val usageContext: UsageContext = UsageContext.APP_DISPLAY
    )

    /** Provided by an upstream read-only adapter, NEVER considered trusted app evidence. */
    data class Candidate(
        val observationId: String,
        val productVersionId: String,
        val productVersionConfirmed: Boolean,
        val retailer: RetailerPolicy.Retailer,
        val capability: RetailerPolicy.Capability,
        val sourceId: String,
        val origin: Origin,
        val rightsStatus: RightsStatus,
        val rightsEvidenceRef: String?,
        val observedAt: OffsetDateTime,
        val validFrom: LocalDate?,
        val validUntil: LocalDate?,
        val storeId: String?,
        val regionId: String?,
        val merchandiseCents: Long?,
        val depositCents: Long?,
        val quantityMilli: Long?,
        val unit: Unit?,
        val currency: String = "EUR",
        val conditionsVerified: Boolean = false
    )

    /** Must be supplied by the HOST from independently verified permissions, never by the price feed. */
    fun interface DisplayPermissionRegistry {
        fun permits(sourceId: String, evidenceRef: String, capability: RetailerPolicy.Capability,
                    purchaseOn: LocalDate): Boolean
    }

    /** The only result shape that may pass this gate to a future price-display UI. */
    data class VisiblePrice(
        val observationId: String,
        val productVersionId: String,
        val retailer: RetailerPolicy.Retailer,
        val storeId: String?,
        val regionId: String?,
        val observedAt: OffsetDateTime,
        val validUntil: LocalDate,
        val merchandiseCents: Long,
        val depositCents: Long?,
        val checkoutCents: Long?,
        val quantityMilli: Long,
        val unit: Unit
    )

    data class Projection(val visible: List<VisiblePrice>, val gaps: Set<Gap>, val incomplete: Boolean) {
        /** Never equate a finite supplied snapshot with full market coverage. */
        val marketWideBestPriceProven: Boolean get() = false
    }

    val denyAll = DisplayPermissionRegistry { _, _, _, _ -> false }
    private const val MAX_CANDIDATES = 256
    private const val MAX_CENTS = 1_000_000_000_000L
    private const val MAX_QUANTITY_MILLI = 1_000_000_000_000L

    fun project(request: Request, candidates: List<Candidate>, permissions: DisplayPermissionRegistry = denyAll): Projection {
        require(request.productVersionId.isNotBlank()) { "A confirmed host product version is required" }
        // No external source may use the separate private-receipt or research context to bypass APP_DISPLAY.
        if (request.usageContext != UsageContext.APP_DISPLAY)
            return Projection(emptyList(), setOf(Gap.NON_DISPLAY_CONTEXT), true)
        if (!RetailerPolicy.supports(request.retailer, request.capability))
            return Projection(emptyList(), setOf(Gap.UNSUPPORTED_CAPABILITY), true)
        // Catalog discovery is a listing, never a price.observation. This price
        // display adapter must not reinterpret catalog rights as price rights.
        if (request.capability == RetailerPolicy.Capability.PUBLISHED_OFFER_CATALOG_DISCOVERY)
            return Projection(emptyList(), setOf(Gap.NON_PRICE_CAPABILITY), true)
        if (candidates.size > MAX_CANDIDATES)
            return Projection(emptyList(), setOf(Gap.LIMIT_EXCEEDED), true)

        val gaps = linkedSetOf<Gap>()
        val found = mutableListOf<VisiblePrice>()
        val seen = mutableSetOf<String>()
        for (candidate in candidates) {
            if (candidate.observationId.isBlank() || !seen.add(candidate.observationId)) {
                gaps += Gap.DUPLICATE_OBSERVATION; continue
            }
            if (candidate.origin != Origin.EXTERNAL || candidate.sourceId.isBlank() ||
                candidate.retailer != request.retailer || candidate.capability != request.capability) {
                gaps += Gap.INVALID_SOURCE; continue
            }
            val ref = candidate.rightsEvidenceRef
            // Permission providers are external trust anchors: failure is DENY,
            // not an uncaught exception or a fallback to the feed's PERMITTED flag.
            val permitted = if (candidate.rightsStatus == RightsStatus.PERMITTED &&
                !ref.isNullOrBlank()) {
                try {
                    permissions.permits(candidate.sourceId, ref, request.capability, request.purchaseOn)
                } catch (_: RuntimeException) { false }
            } else false
            if (!permitted) {
                gaps += Gap.MISSING_RIGHTS; continue
            }
            if (!candidate.productVersionConfirmed || candidate.productVersionId != request.productVersionId) {
                gaps += Gap.PRODUCT_IDENTITY; continue
            }
            if (candidate.storeId != request.storeId || candidate.regionId != request.regionId) {
                gaps += Gap.SCOPE_MISMATCH; continue
            }
            if (candidate.observedAt.toInstant().isAfter(request.asOf.toInstant()) ||
                candidate.validFrom == null || candidate.validUntil == null ||
                candidate.validFrom.isAfter(candidate.validUntil) ||
                request.purchaseOn.isBefore(candidate.validFrom) || request.purchaseOn.isAfter(candidate.validUntil)) {
                gaps += Gap.DATE_INVALID; continue
            }
            val cents = candidate.merchandiseCents
            if (cents == null || cents !in 0..MAX_CENTS || candidate.currency != "EUR" ||
                candidate.depositCents != null && candidate.depositCents !in 0..MAX_CENTS) {
                gaps += Gap.PRICE_UNKNOWN; continue
            }
            val quantity = candidate.quantityMilli
            val unit = candidate.unit
            if (quantity == null || quantity !in 1..MAX_QUANTITY_MILLI || unit == null) {
                gaps += Gap.QUANTITY_UNKNOWN; continue
            }
            if (!candidate.conditionsVerified) {
                gaps += Gap.PRICE_CONDITIONS_UNKNOWN; continue
            }
            val checkout = candidate.depositCents?.let { deposit ->
                runCatching { Math.addExact(cents, deposit) }.getOrNull()?.takeIf { it <= MAX_CENTS }
            }
            if (candidate.depositCents != null && checkout == null) {
                gaps += Gap.PRICE_UNKNOWN; continue
            }
            found += VisiblePrice(candidate.observationId, candidate.productVersionId, candidate.retailer,
                candidate.storeId, candidate.regionId, candidate.observedAt, candidate.validUntil,
                cents, candidate.depositCents, checkout, quantity, unit)
        }
        // Even if all supplied data passed, a subset cannot prove complete market coverage.
        return Projection(found.toList(), gaps.toSet(), found.isEmpty() || gaps.isNotEmpty())
    }
}

/** Plugin seam for future module snapshots. This port supplies no network implementation. */
fun interface ExternalPriceCandidateSupplier {
    suspend fun load(request: ExternalPriceBridge.Request): List<ExternalPriceBridge.Candidate>
}

/** Forbidden retailer capabilities and private contexts are rejected BEFORE any supplier call. */
class ReadOnlyExternalPricePort(
    private val supplier: ExternalPriceCandidateSupplier,
    private val permissions: ExternalPriceBridge.DisplayPermissionRegistry = ExternalPriceBridge.denyAll
) {
    suspend fun preview(request: ExternalPriceBridge.Request): ExternalPriceBridge.Projection {
        require(request.productVersionId.isNotBlank()) { "A confirmed host product version is required" }
        if (request.usageContext != ExternalPriceBridge.UsageContext.APP_DISPLAY ||
            !RetailerPolicy.supports(request.retailer, request.capability) ||
            request.capability == RetailerPolicy.Capability.PUBLISHED_OFFER_CATALOG_DISCOVERY) {
            return ExternalPriceBridge.project(request, emptyList(), permissions)
        }
        return ExternalPriceBridge.project(request, supplier.load(request), permissions)
    }
}
