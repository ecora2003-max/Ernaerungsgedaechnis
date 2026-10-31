package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ExternalPriceBridge as Gate
import de.ernaehrung.demo.data.integration.RetailerPolicy as Policy
import de.ernaehrung.demo.data.integration.ReadOnlyExternalPricePort
import de.ernaehrung.demo.data.integration.ExternalPriceCandidateSupplier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class ExternalPriceBridgeTest {
    private val asOf = OffsetDateTime.parse("2026-10-31T12:00:00+01:00")
    private val date = LocalDate.parse("2026-11-02")
    private val permitted = Gate.DisplayPermissionRegistry { source, ref, capability, day ->
        source == "verified-feed" && ref == "legal-case-123" &&
            capability == Policy.Capability.PUBLISHED_OFFER_COLLECTION && day == date
    }
    private fun request(retailer: Policy.Retailer = Policy.Retailer.LIDL,
        capability: Policy.Capability = Policy.Capability.PUBLISHED_OFFER_COLLECTION,
        usage: Gate.UsageContext = Gate.UsageContext.APP_DISPLAY) =
        Gate.Request(retailer, capability, "version-1", date, asOf, "store-1", "berlin", usage)
    private fun candidate() = Gate.Candidate("obs-1", "version-1", true, Policy.Retailer.LIDL,
        Policy.Capability.PUBLISHED_OFFER_COLLECTION, "verified-feed", Gate.Origin.EXTERNAL,
        Gate.RightsStatus.PERMITTED, "legal-case-123", OffsetDateTime.parse("2026-10-31T10:00:00+01:00"),
        date, date.plusDays(5), "store-1", "berlin", 199, null, 200_000,
        Gate.Unit.GRAM, conditionsVerified = true)
    private fun blocked(value: Gate.Candidate, reason: Gate.Gap) {
        val result = Gate.project(request(), listOf(value), permitted)
        assertTrue(result.visible.isEmpty()); assertTrue(result.incomplete); assertTrue(result.gaps.contains(reason))
    }

    @Test fun retailerCapabilitiesMatchAdr016WithoutGrantingSourceRights() {
        val both = setOf(Policy.Retailer.ALDI_NORD, Policy.Retailer.ALDI_SUED, Policy.Retailer.REWE, Policy.Retailer.LIDL)
        Policy.Retailer.values().forEach { retailer ->
            assertTrue(Policy.supports(retailer, Policy.Capability.PUBLISHED_OFFER_COLLECTION))
            assertEquals(retailer in both, Policy.supports(retailer, Policy.Capability.TARGETED_PRODUCT_PRICE_QUERY))
        }
        val disallowed = Gate.project(request(Policy.Retailer.NETTO_MD, Policy.Capability.TARGETED_PRODUCT_PRICE_QUERY),
            listOf(candidate()), permitted)
        assertTrue(disallowed.visible.isEmpty()); assertTrue(disallowed.gaps.contains(Gate.Gap.UNSUPPORTED_CAPABILITY))
    }

    @Test fun defaultRegistryFailsClosedEvenIfFeedClaimsPermitted() {
        val result = Gate.project(request(), listOf(candidate()))
        assertEquals(emptyList<Gate.VisiblePrice>(), result.visible)
        assertEquals(setOf(Gate.Gap.MISSING_RIGHTS), result.gaps)
    }
    @Test fun onlyIndependentlyAuthorizedEvidenceCanBeShown() {
        val result = Gate.project(request(), listOf(candidate()), permitted)
        assertEquals(1, result.visible.size); assertFalse(result.incomplete)
        assertEquals(199L, result.visible.single().merchandiseCents)
        assertNull(result.visible.single().depositCents)
        assertNull(result.visible.single().checkoutCents)
        assertFalse(result.marketWideBestPriceProven)
        blocked(candidate().copy(rightsStatus = Gate.RightsStatus.UNVERIFIED), Gate.Gap.MISSING_RIGHTS)
        blocked(candidate().copy(rightsStatus = Gate.RightsStatus.RESTRICTED), Gate.Gap.MISSING_RIGHTS)
        blocked(candidate().copy(rightsEvidenceRef = "forged"), Gate.Gap.MISSING_RIGHTS)
    }
    @Test fun externalAndPrivateReceiptNeverMixOrBypassDisplayRights() {
        blocked(candidate().copy(origin = Gate.Origin.USER_CONFIRMED_RECEIPT), Gate.Gap.INVALID_SOURCE)
        blocked(candidate().copy(origin = Gate.Origin.SYNTHETIC), Gate.Gap.INVALID_SOURCE)
        for (context in listOf(Gate.UsageContext.PRIVATE_USER_HISTORY, Gate.UsageContext.RESEARCH_ONLY)) {
            val out = Gate.project(request(usage = context), listOf(candidate()), permitted)
            assertTrue(out.visible.isEmpty()); assertEquals(setOf(Gate.Gap.NON_DISPLAY_CONTEXT), out.gaps)
        }
    }
    @Test fun noEstimatedIdentityOrOtherMarketOrDifferentRetailer() {
        blocked(candidate().copy(productVersionConfirmed = false), Gate.Gap.PRODUCT_IDENTITY)
        blocked(candidate().copy(productVersionId = "newer-version"), Gate.Gap.PRODUCT_IDENTITY)
        blocked(candidate().copy(storeId = null), Gate.Gap.SCOPE_MISMATCH)
        blocked(candidate().copy(regionId = "hamburg"), Gate.Gap.SCOPE_MISMATCH)
        blocked(candidate().copy(retailer = Policy.Retailer.PENNY), Gate.Gap.INVALID_SOURCE)
    }
    @Test fun noHindsightExpiredOfferOrUnknownConditions() {
        blocked(candidate().copy(observedAt = OffsetDateTime.parse("2026-10-31T12:01:00+01:00")), Gate.Gap.DATE_INVALID)
        blocked(candidate().copy(validFrom = date.plusDays(1)), Gate.Gap.DATE_INVALID)
        blocked(candidate().copy(validUntil = date.minusDays(1)), Gate.Gap.DATE_INVALID)
        blocked(candidate().copy(validUntil = null), Gate.Gap.DATE_INVALID)
        blocked(candidate().copy(conditionsVerified = false), Gate.Gap.PRICE_CONDITIONS_UNKNOWN)
    }
    @Test fun unknownMoneyQuantityAndDepositAreNotZeroOrProvenCheckout() {
        blocked(candidate().copy(merchandiseCents = null), Gate.Gap.PRICE_UNKNOWN)
        blocked(candidate().copy(merchandiseCents = -1), Gate.Gap.PRICE_UNKNOWN)
        blocked(candidate().copy(currency = "USD"), Gate.Gap.PRICE_UNKNOWN)
        blocked(candidate().copy(quantityMilli = null), Gate.Gap.QUANTITY_UNKNOWN)
        blocked(candidate().copy(quantityMilli = 0), Gate.Gap.QUANTITY_UNKNOWN)
        blocked(candidate().copy(unit = null), Gate.Gap.QUANTITY_UNKNOWN)
        assertEquals(0L, Gate.project(request(), listOf(candidate().copy(merchandiseCents = 0)), permitted).visible.single().merchandiseCents)
        val confirmedDeposit = Gate.project(request(), listOf(candidate().copy(depositCents = 25)), permitted)
        assertEquals(224L, confirmedDeposit.visible.single().checkoutCents)
    }
    @Test fun mixedAndDuplicateObservationsRemainPartialWithoutInventingMarketBest() {
        val out = Gate.project(request(), listOf(candidate(), candidate().copy(observationId = "obs-2", rightsStatus = Gate.RightsStatus.RESTRICTED), candidate()), permitted)
        assertEquals(1, out.visible.size); assertTrue(out.incomplete)
        assertTrue(out.gaps.contains(Gate.Gap.MISSING_RIGHTS))
        assertTrue(out.gaps.contains(Gate.Gap.DUPLICATE_OBSERVATION))
        assertFalse(out.marketWideBestPriceProven)
        val none = Gate.project(request(), emptyList(), permitted)
        assertTrue(none.incomplete); assertFalse(none.marketWideBestPriceProven)
    }
    @Test fun maliciousBulkResponsesAreRejectedBeforeProcessing() {
        val out = Gate.project(request(), List(257) { candidate().copy(observationId = "obs-$it") }, permitted)
        assertTrue(out.visible.isEmpty()); assertTrue(out.gaps.contains(Gate.Gap.LIMIT_EXCEEDED))
    }
    @Test fun supplierIsNotInvokedForDisallowedTargetedQueriesOrPrivateHistory() = runBlocking {
        var calls = 0
        val port = ReadOnlyExternalPricePort(ExternalPriceCandidateSupplier { calls++; listOf(candidate()) }, permitted)
        val blocked = port.preview(request(Policy.Retailer.PENNY, Policy.Capability.TARGETED_PRODUCT_PRICE_QUERY))
        assertTrue(blocked.gaps.contains(Gate.Gap.UNSUPPORTED_CAPABILITY))
        val privateHistory = port.preview(request(usage = Gate.UsageContext.PRIVATE_USER_HISTORY))
        assertTrue(privateHistory.gaps.contains(Gate.Gap.NON_DISPLAY_CONTEXT))
        assertEquals(0, calls)
        val allowed = port.preview(request())
        assertEquals(1, allowed.visible.size); assertEquals(1, calls)
    }
}
