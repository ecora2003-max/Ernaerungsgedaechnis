package de.ernaehrung.demo.data.integration

import java.time.OffsetDateTime

/**
 * Fail-closed host-only chain for a single external in-store offer.
 *
 * A confirmed canonical retailer/store identity and independently verified host
 * source rights are REQUIRED in addition to the existing retailer, price,
 * product-version, temporal and shopping-validity gates. Feed-provided labels,
 * aliases and permission flags never confirm a store or grant APP_DISPLAY.
 *
 * This is an eligibility projection, NOT a full price comparison, market
 * coverage proof, shopping recommendation, provider or FoodStore writer.
 */
internal object ShoppingOfferGate {
    data class HostFacts(
        val priceKind: String,
        val dataQuality: String,
        val availability: String?,
        val conditional: Boolean,
        val scopeType: String,
        /** Facts have been checked independently by the host, not just copied from the feed. */
        val verifiedByHost: Boolean
    )

    /** Host-controlled canonical store mapping. Do not implement this using feed aliases. */
    fun interface ConfirmedStoreRegistry {
        fun isConfirmed(
            retailer: RetailerPolicy.Retailer,
            storeId: String,
            regionId: String?,
            asOf: OffsetDateTime
        ): Boolean
    }

    val denyAllStores = ConfirmedStoreRegistry { _, _, _, _ -> false }

    enum class Gap {
        STORE_NOT_CONFIRMED,
        HOST_FACTS_UNVERIFIED,
        SCOPE_NOT_CONFIRMED,
        PRICE_KIND_NOT_SUPPORTED,
        EXTERNAL_PRICE_REJECTED,
        SHOPPING_VALIDITY_REJECTED
    }

    data class Result(
        val offer: ExternalPriceBridge.VisiblePrice?,
        val gaps: Set<Gap>,
        val externalGaps: Set<ExternalPriceBridge.Gap> = emptySet(),
        val validityNotes: List<String> = emptyList()
    ) {
        val eligible: Boolean get() = offer != null && gaps.isEmpty()
        /** Even an eligible offer never establishes complete market coverage. */
        val marketWideBestPriceProven: Boolean get() = false
    }

    fun evaluate(
        request: ExternalPriceBridge.Request,
        candidate: ExternalPriceBridge.Candidate,
        facts: HostFacts,
        permissions: ExternalPriceBridge.DisplayPermissionRegistry = ExternalPriceBridge.denyAll,
        stores: ConfirmedStoreRegistry = denyAllStores
    ): Result {
        val storeId = request.storeId?.takeIf { it.isNotBlank() }
            ?: return Result(null, setOf(Gap.STORE_NOT_CONFIRMED))
        // A temporarily unavailable host registry is not a confirmed store.
        val storeConfirmed = try {
            stores.isConfirmed(request.retailer, storeId, request.regionId, request.asOf)
        } catch (_: RuntimeException) { false }
        if (!storeConfirmed)
            return Result(null, setOf(Gap.STORE_NOT_CONFIRMED))
        if (!facts.verifiedByHost)
            return Result(null, setOf(Gap.HOST_FACTS_UNVERIFIED))
        // The shopping reference accepts chain/region scopes with caveats; this
        // host boundary deliberately requires a verified physical store.
        if (facts.scopeType != "STORE_CONFIRMED")
            return Result(null, setOf(Gap.SCOPE_NOT_CONFIRMED))
        // A targeted product lookup may legitimately return a dated SALE, but it
        // must retain its TARGETED_PRODUCT_PRICE_QUERY provenance and pass the
        // independent host rights, store, conditions and validity checks below.
        val supportedKind = when (request.capability) {
            RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION -> facts.priceKind == "SALE"
            RetailerPolicy.Capability.TARGETED_PRODUCT_PRICE_QUERY ->
                facts.priceKind in setOf("CURRENT_DISPLAYED", "LISTED_REGULAR", "SALE")
            RetailerPolicy.Capability.PUBLISHED_OFFER_CATALOG_DISCOVERY -> false
        }
        if (!supportedKind)
            return Result(null, setOf(Gap.PRICE_KIND_NOT_SUPPORTED))

        // Independent ADR-016 capability + ADR-014 source-rights + exact product,
        // currency, store, time, amount and conditions gate. Default deny.
        val external = ExternalPriceBridge.project(request, listOf(candidate), permissions)
        val visible = external.visible.singleOrNull()
            ?: return Result(null, setOf(Gap.EXTERNAL_PRICE_REJECTED), external.gaps)

        // Do not fabricate price/source/scope fields: reuse the exact candidate
        // that just passed the independent external bridge and only host-verified
        // additional facts. The registry confirms the canonical store ID.
        val observation = ShoppingValidityKernel.Observation(
            priceKind = facts.priceKind,
            priceCents = candidate.merchandiseCents,
            availability = facts.availability,
            conditional = facts.conditional,
            source = ShoppingValidityKernel.Source(
                facts.dataQuality, candidate.rightsStatus.name, candidate.observedAt.toString()
            ),
            scope = ShoppingValidityKernel.Scope(
                candidate.retailer.name, facts.scopeType, candidate.storeId
            ),
            validFrom = candidate.validFrom?.toString(),
            validUntil = candidate.validUntil?.toString(),
            shippingCents = null // in-store only; never assume an online checkout
        )
        val validity = try {
            ShoppingValidityKernel.check(
                observation, request.purchaseOn.toString(), request.asOf.toString(),
                historical = false, allowOnline = false,
                allowedStores = mapOf(request.retailer.name to setOf(storeId))
            )
        } catch (_: IllegalArgumentException) {
            return Result(null, setOf(Gap.SHOPPING_VALIDITY_REJECTED),
                validityNotes = listOf("invalid date or observation format"))
        }
        if (!validity.eligible)
            return Result(null, setOf(Gap.SHOPPING_VALIDITY_REJECTED),
                validityNotes = validity.notes)
        return Result(visible, emptySet())
    }
}
