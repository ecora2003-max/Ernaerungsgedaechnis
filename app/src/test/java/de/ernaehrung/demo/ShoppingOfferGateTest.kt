package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ExternalPriceBridge as Price
import de.ernaehrung.demo.data.integration.RetailerPolicy as Policy
import de.ernaehrung.demo.data.integration.ShoppingOfferGate as Gate
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class ShoppingOfferGateTest {
    private val day = LocalDate.parse("2026-11-02")
    private val asOf = OffsetDateTime.parse("2026-10-31T12:00:00+01:00")
    private val rights = Price.DisplayPermissionRegistry { source, ref, capability, on ->
        source == "host-approved-source" && ref == "host-permission-42" &&
            capability == Policy.Capability.PUBLISHED_OFFER_COLLECTION && on == day
    }
    private val stores = Gate.ConfirmedStoreRegistry { retailer, store, region, at ->
        retailer == Policy.Retailer.LIDL && store == "canonical-store-42" &&
            region == "berlin" && at.toInstant() == asOf.toInstant()
    }
    private fun request() = Price.Request(
        Policy.Retailer.LIDL, Policy.Capability.PUBLISHED_OFFER_COLLECTION,
        "product-version-7", day, asOf, "canonical-store-42", "berlin"
    )
    private fun candidate() = Price.Candidate(
        "offer-1", "product-version-7", true, Policy.Retailer.LIDL,
        Policy.Capability.PUBLISHED_OFFER_COLLECTION, "host-approved-source",
        Price.Origin.EXTERNAL, Price.RightsStatus.PERMITTED, "host-permission-42",
        OffsetDateTime.parse("2026-10-31T10:00:00+01:00"),
        day, day.plusDays(4), "canonical-store-42", "berlin",
        199, 25, 200_000, Price.Unit.GRAM, conditionsVerified = true
    )
    private fun facts() = Gate.HostFacts("SALE", "CONFIRMED", "AVAILABLE", false,
        "STORE_CONFIRMED", verifiedByHost = true)
    private fun review(
        request: Price.Request = request(), candidate: Price.Candidate = candidate(),
        facts: Gate.HostFacts = facts(), permissions: Price.DisplayPermissionRegistry = rights,
        stores: Gate.ConfirmedStoreRegistry = this.stores
    ) = Gate.evaluate(request, candidate, facts, permissions, stores)

    @Test fun bothIndependentHostRegistriesAreRequiredAndDefaultDeny() {
        val default = Gate.evaluate(request(), candidate(), facts())
        assertFalse(default.eligible)
        assertEquals(setOf(Gate.Gap.STORE_NOT_CONFIRMED), default.gaps)
        val noRights = review(permissions = Price.denyAll)
        assertEquals(setOf(Gate.Gap.EXTERNAL_PRICE_REJECTED), noRights.gaps)
        assertEquals(setOf(Price.Gap.MISSING_RIGHTS), noRights.externalGaps)
        assertNull(noRights.offer)
        val noStore = review(stores = Gate.denyAllStores)
        assertEquals(setOf(Gate.Gap.STORE_NOT_CONFIRMED), noStore.gaps)
        assertNull(noStore.offer)
    }

    @Test fun verifiedInStoreOfferPassesWithoutMarketWidePriceClaim() {
        val result = review()
        assertTrue(result.eligible)
        assertTrue(result.gaps.isEmpty())
        assertEquals("offer-1", result.offer!!.observationId)
        assertEquals(199L, result.offer!!.merchandiseCents)
        assertEquals(224L, result.offer!!.checkoutCents)
        assertFalse(result.marketWideBestPriceProven)
    }

    @Test fun freeTextStoreOrOtherRegionCannotMasqueradeAsConfirmedStore() {
        val alias = review(request = request().copy(storeId = "Lidl am Bahnhof"),
            candidate = candidate().copy(storeId = "Lidl am Bahnhof"))
        assertEquals(setOf(Gate.Gap.STORE_NOT_CONFIRMED), alias.gaps)
        val unknown = review(request = request().copy(storeId = null))
        assertEquals(setOf(Gate.Gap.STORE_NOT_CONFIRMED), unknown.gaps)
        val wrongRegion = review(request = request().copy(regionId = "hamburg"),
            candidate = candidate().copy(regionId = "hamburg"))
        assertEquals(setOf(Gate.Gap.STORE_NOT_CONFIRMED), wrongRegion.gaps)
        val mismatchedCandidate = review(candidate = candidate().copy(storeId = "other-store"))
        assertEquals(setOf(Price.Gap.SCOPE_MISMATCH), mismatchedCandidate.externalGaps)
        assertNull(mismatchedCandidate.offer)
    }

    @Test fun unverifiedHostFactsOrNonStoreScopeNeverPass() {
        assertEquals(setOf(Gate.Gap.HOST_FACTS_UNVERIFIED),
            review(facts = facts().copy(verifiedByHost = false)).gaps)
        assertEquals(setOf(Gate.Gap.SCOPE_NOT_CONFIRMED),
            review(facts = facts().copy(scopeType = "CHAIN_WIDE_REPORTED")).gaps)
        assertEquals(setOf(Gate.Gap.SCOPE_NOT_CONFIRMED),
            review(facts = facts().copy(scopeType = "UNKNOWN")).gaps)
    }

    @Test fun capabilityAndOfferKindMustMatchInsteadOfReinterpretingCatalogs() {
        assertEquals(setOf(Gate.Gap.PRICE_KIND_NOT_SUPPORTED),
            review(facts = facts().copy(priceKind = "HISTORICAL_PAID")).gaps)
        assertEquals(setOf(Gate.Gap.PRICE_KIND_NOT_SUPPORTED),
            review(facts = facts().copy(priceKind = "ONLINE_LISTED")).gaps)
        val catalog = request().copy(capability =
            Policy.Capability.PUBLISHED_OFFER_CATALOG_DISCOVERY)
        assertEquals(setOf(Gate.Gap.PRICE_KIND_NOT_SUPPORTED),
            review(request = catalog).gaps)
        val nonDisplay = request().copy(usageContext = Price.UsageContext.PRIVATE_USER_HISTORY)
        assertEquals(setOf(Price.Gap.NON_DISPLAY_CONTEXT),
            review(request = nonDisplay).externalGaps)
    }

    @Test fun noFutureOrExpiredOfferEvenWithVerifiedRightsAndStore() {
        val future = review(candidate = candidate().copy(observedAt =
            OffsetDateTime.parse("2026-10-31T12:01:00+01:00")))
        assertEquals(setOf(Price.Gap.DATE_INVALID), future.externalGaps)
        val expired = review(candidate = candidate().copy(validUntil = day.minusDays(1)))
        assertEquals(setOf(Price.Gap.DATE_INVALID), expired.externalGaps)
        val noDates = review(candidate = candidate().copy(validUntil = null))
        assertEquals(setOf(Price.Gap.DATE_INVALID), noDates.externalGaps)
    }

    @Test fun shoppingAvailabilityCouponAndQualityAreAdditionalGates() {
        val outOfStock = review(facts = facts().copy(availability = "OUT_OF_STOCK_REPORTED"))
        assertEquals(setOf(Gate.Gap.SHOPPING_VALIDITY_REJECTED), outOfStock.gaps)
        assertTrue(outOfStock.validityNotes.contains("out of stock"))
        val coupon = review(facts = facts().copy(conditional = true))
        assertEquals(setOf(Gate.Gap.SHOPPING_VALIDITY_REJECTED), coupon.gaps)
        assertTrue(coupon.validityNotes.any { it.contains("coupon") })
        val unknownQuality = review(facts = facts().copy(dataQuality = "UNKNOWN"))
        assertEquals(setOf(Gate.Gap.SHOPPING_VALIDITY_REJECTED), unknownQuality.gaps)
        assertNull(unknownQuality.offer)
    }

    @Test fun sourceAndPriceFailuresCannotBeRecoveredByHostFacts() {
        val noPermission = review(candidate = candidate().copy(rightsEvidenceRef = "invented"))
        assertEquals(setOf(Price.Gap.MISSING_RIGHTS), noPermission.externalGaps)
        val privateReceipt = review(candidate = candidate().copy(origin =
            Price.Origin.USER_CONFIRMED_RECEIPT))
        assertEquals(setOf(Price.Gap.INVALID_SOURCE), privateReceipt.externalGaps)
        val noPrice = review(candidate = candidate().copy(merchandiseCents = null))
        assertEquals(setOf(Price.Gap.PRICE_UNKNOWN), noPrice.externalGaps)
        val badQuantity = review(candidate = candidate().copy(quantityMilli = 0))
        assertEquals(setOf(Price.Gap.QUANTITY_UNKNOWN), badQuantity.externalGaps)
        val conditions = review(candidate = candidate().copy(conditionsVerified = false))
        assertEquals(setOf(Price.Gap.PRICE_CONDITIONS_UNKNOWN), conditions.externalGaps)
    }

    @Test fun unavailableHostStoreAndRightsRegistriesFailClosed() {
        val storeUnavailable = Gate.ConfirmedStoreRegistry { _, _, _, _ ->
            throw IllegalStateException("store lookup unavailable")
        }
        val deniedStore = review(stores = storeUnavailable)
        assertEquals(setOf(Gate.Gap.STORE_NOT_CONFIRMED), deniedStore.gaps)
        assertNull(deniedStore.offer)

        val rightsUnavailable = Price.DisplayPermissionRegistry { _, _, _, _ ->
            throw IllegalStateException("permission lookup unavailable")
        }
        val deniedRights = review(permissions = rightsUnavailable)
        assertEquals(setOf(Gate.Gap.EXTERNAL_PRICE_REJECTED), deniedRights.gaps)
        assertEquals(setOf(Price.Gap.MISSING_RIGHTS), deniedRights.externalGaps)
        assertNull(deniedRights.offer)
    }

    @Test fun knownZeroPriceRemainsDistinctFromUnknown() {
        val free = review(candidate = candidate().copy(merchandiseCents = 0, depositCents = null))
        assertTrue(free.eligible)
        assertEquals(0L, free.offer!!.merchandiseCents)
        assertNull(free.offer!!.checkoutCents)
    }
}
