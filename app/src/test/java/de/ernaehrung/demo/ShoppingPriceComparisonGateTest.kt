package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ExternalPriceBridge as Price
import de.ernaehrung.demo.data.integration.RetailerPolicy as Policy
import de.ernaehrung.demo.data.integration.ShoppingComparabilityKernel as Comparable
import de.ernaehrung.demo.data.integration.ShoppingCoverageSignalKernel as Coverage
import de.ernaehrung.demo.data.integration.ShoppingOfferGate as OfferGate
import de.ernaehrung.demo.data.integration.ShoppingPriceComparisonGate as Gate
import de.ernaehrung.demo.data.integration.ShoppingUnitRateKernel as UnitRate
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.time.OffsetDateTime

class ShoppingPriceComparisonGateTest {
    private val day = LocalDate.parse("2026-11-02")
    private val asOf = OffsetDateTime.parse("2026-10-31T12:00:00+01:00")
    private val permissions = Price.DisplayPermissionRegistry { source, ref, capability, on ->
        source == "licensed-test-source" && ref == "host-grant" &&
            capability == Policy.Capability.PUBLISHED_OFFER_COLLECTION && on == day
    }
    private val stores = OfferGate.ConfirmedStoreRegistry { retailer, store, region, _ ->
        retailer == Policy.Retailer.LIDL && store == "canonical-42" && region == "berlin"
    }
    private val articles = Gate.ConfirmedArticleRegistry { version, article, _, _ ->
        version in setOf("v-target", "v-red", "v-orange", "v-unknown", "v-sub", "v-expensive") &&
            article.foodTypeId in setOf("milk", "oat")
    }
    private val complete = Gate.HostCoverage(Coverage.Coverage(true), true)
    private val qty = UnitRate.Quantity(1000, "g", "NET", 1, true)
    private fun article(food: String = "milk", gtin: String? = null,
                        quantity: UnitRate.Quantity? = qty) =
        Comparable.Article(foodTypeId = food, attributes = mapOf("state" to "liquid"),
            quantity = quantity, gtin = gtin)

    private fun offer(
        id: String, version: String, cents: Long? = 200, organic: Boolean? = true,
        article: Comparable.Article = article(), deposit: Long? = 25
    ): Gate.Input {
        val request = Price.Request(Policy.Retailer.LIDL,
            Policy.Capability.PUBLISHED_OFFER_COLLECTION, version, day, asOf,
            "canonical-42", "berlin")
        val candidate = Price.Candidate(id, version, true, Policy.Retailer.LIDL,
            Policy.Capability.PUBLISHED_OFFER_COLLECTION, "licensed-test-source",
            Price.Origin.EXTERNAL, Price.RightsStatus.PERMITTED, "host-grant",
            OffsetDateTime.parse("2026-10-31T10:00:00+01:00"),
            day, day.plusDays(2), "canonical-42", "berlin",
            cents, deposit, 1_000_000, Price.Unit.GRAM, conditionsVerified = true)
        return Gate.Input(request, candidate,
            OfferGate.HostFacts("SALE", "CONFIRMED", "AVAILABLE", false,
                "STORE_CONFIRMED", true),
            article, organic)
    }
    private val target get() = offer("target", "v-target", cents = 300)
    private fun compare(
        target: Gate.Input = this.target, candidates: List<Gate.Input>,
        coverage: Gate.HostCoverage? = complete,
        rights: Price.DisplayPermissionRegistry = permissions,
        storeRegistry: OfferGate.ConfirmedStoreRegistry = stores,
        articleRegistry: Gate.ConfirmedArticleRegistry = articles,
        matches: List<Comparable.Match> = emptyList()
    ) = Gate.compare(target, candidates, coverage, matches,
        rights, storeRegistry, articleRegistry)

    @Test fun defaultRegistriesDenyEvenWithFeedPermissionAndCoverageClaims() {
        val result = Gate.compare(target, listOf(offer("red", "v-red")))
        assertFalse(result.targetEligible)
        assertTrue(result.cheaper.isEmpty())
        assertTrue(result.signals.signals.contains("GREY_MAY_BE_CHEAPER"))
        assertFalse(result.marketWideBestPriceProven)
        assertEquals("CHEAPEST_KNOWN_MATCHING_ONLY", result.signals.bestPriceClaim)
    }

    @Test fun verifiedSameAndDifferentOrganicCheaperOffersYieldRedOrange() {
        val result = compare(candidates = listOf(
            offer("orange", "v-orange", 220, organic = false),
            offer("red", "v-red", 200, organic = true)))
        assertTrue(result.targetEligible)
        assertEquals(listOf("red", "orange"), result.cheaper.map { it.observationId })
        assertEquals(listOf("RED_SAME_BIO_CHEAPER", "ORANGE_DIFFERENT_BIO_CHEAPER"),
            result.signals.signals)
        assertEquals(BigInteger.valueOf(-100), result.cheaper[0].targetEquivalentDifferenceCents)
        assertFalse(result.marketWideBestPriceProven)
    }

    @Test fun unknownOrganicOrUnverifiedCoverageCannotSuppressGrey() {
        val unknown = offer("unknown", "v-unknown", organic = null)
        val result = compare(candidates = listOf(unknown, offer("red", "v-red")),
            coverage = Gate.HostCoverage(Coverage.Coverage(true), false))
        assertEquals(listOf("RED_SAME_BIO_CHEAPER", "GREY_MAY_BE_CHEAPER"),
            result.signals.signals)
        assertTrue(result.signals.gaps.contains("comparison coverage not supplied"))
        assertTrue(result.signals.gaps.contains("unknown: unknown organic status"))
    }

    @Test fun functionalSubstitutionRequiresExplicitConfirmationAndNeverAutoSignals() {
        val substitute = offer("sub", "v-sub", 100, article = article(food = "oat"))
        val unconfirmed = compare(candidates = listOf(substitute))
        assertTrue(unconfirmed.cheaper.isEmpty())
        assertTrue(unconfirmed.signals.incomplete)
        val confirmed = compare(candidates = listOf(substitute), matches = listOf(
            Comparable.Match("target", "sub", "CONFIRMED_BY_USER",
                "FUNCTIONAL_SUBSTITUTE")))
        assertEquals(1, confirmed.cheaper.size)
        assertEquals("FUNCTIONAL_SUBSTITUTE", confirmed.cheaper.single().comparability)
        assertTrue(confirmed.signals.signals.isEmpty())
    }

    @Test fun rightsStoreIdentityFutureDateAndUnconfirmedArticleStayExcluded() {
        val badRights = offer("red", "v-red").let {
            it.copy(candidate = it.candidate.copy(rightsEvidenceRef = "untrusted"))
        }
        val badStore = offer("orange", "v-orange").let {
            it.copy(request = it.request.copy(storeId = "alias"),
                candidate = it.candidate.copy(storeId = "alias"))
        }
        val future = offer("unknown", "v-unknown").let {
            it.copy(candidate = it.candidate.copy(observedAt = asOf.plusMinutes(1)))
        }
        val result = compare(candidates = listOf(badRights, badStore, future))
        assertTrue(result.cheaper.isEmpty())
        assertEquals(3, result.excluded.size)
        assertEquals(listOf("GREY_MAY_BE_CHEAPER"), result.signals.signals)
        assertTrue(result.signals.gaps.any { it.contains("red:") })
        val noArticle = compare(candidates = listOf(offer("red", "v-red")),
            articleRegistry = Gate.denyAllArticles)
        assertFalse(noArticle.targetEligible)
    }

    @Test fun candidateCannotMoveDecisionCutoffToImportFuturePrices() {
        val later = asOf.plusDays(1)
        val candidate = offer("red", "v-red", 100).let {
            it.copy(request = it.request.copy(asOf = later),
                candidate = it.candidate.copy(observedAt = asOf.plusHours(2)))
        }
        val result = compare(candidates = listOf(candidate))
        assertTrue(result.targetEligible)
        assertTrue(result.cheaper.isEmpty())
        assertEquals("comparison date/evidence cutoff mismatch", result.excluded["red"])
        assertEquals(listOf("GREY_MAY_BE_CHEAPER"), result.signals.signals)
        val otherDay = offer("orange", "v-orange", 100).let {
            it.copy(request = it.request.copy(purchaseOn = day.plusDays(1)))
        }
        val dateResult = compare(candidates = listOf(otherDay))
        assertTrue(dateResult.cheaper.isEmpty())
        assertTrue(dateResult.signals.incomplete)
    }

    @Test fun inconsistentBridgeQuantityOrMissingBasisCannotBecomeCheaper() {
        val mismatch = offer("red", "v-red").let {
            it.copy(candidate = it.candidate.copy(quantityMilli = 999_000))
        }
        val unknown = offer("orange", "v-orange", article = article(
            quantity = UnitRate.Quantity(1000, "g", null, 1, true)))
        val result = compare(candidates = listOf(mismatch, unknown))
        assertTrue(result.cheaper.isEmpty())
        assertEquals(2, result.excluded.size)
        assertEquals(listOf("GREY_MAY_BE_CHEAPER"), result.signals.signals)
    }

    @Test fun equalAndMoreExpensiveOffersNeverTriggerCheaperSignal() {
        val result = compare(candidates = listOf(
            offer("red", "v-red", 300),
            offer("expensive", "v-expensive", 400)))
        assertTrue(result.cheaper.isEmpty())
        assertTrue(result.signals.signals.isEmpty())
        assertTrue(result.excluded.isEmpty())
    }

    @Test fun merchandiseOnlyRateDoesNotTreatDepositAsDiscount() {
        val result = compare(candidates = listOf(
            offer("red", "v-red", 200, deposit = 500)))
        assertEquals(listOf("RED_SAME_BIO_CHEAPER"), result.signals.signals)
        assertEquals(BigInteger.valueOf(-100), result.cheaper.single()
            .targetEquivalentDifferenceCents)
        val zero = compare(candidates = listOf(
            offer("red", "v-red", cents = 0, deposit = null)))
        assertEquals(BigInteger.valueOf(-300), zero.cheaper.single()
            .targetEquivalentDifferenceCents)
        assertEquals(listOf("RED_SAME_BIO_CHEAPER", "GREY_MAY_BE_CHEAPER"),
            zero.signals.signals)
        assertTrue(zero.signals.gaps.contains("red: deposit/checkout cost UNKNOWN"))
        val unknown = compare(candidates = listOf(
            offer("red", "v-red", cents = null)))
        assertTrue(unknown.cheaper.isEmpty())
        assertTrue(unknown.signals.incomplete)
    }

    @Test fun duplicateOrMissingObservationIdsFailBeforeComparison() {
        fun rejected(target: Gate.Input, rows: List<Gate.Input>) {
            try {
                compare(target = target, candidates = rows)
                fail("must reject ambiguous observation IDs")
            } catch (_: IllegalArgumentException) { }
        }
        rejected(target, listOf(offer("target", "v-red")))
        rejected(target, listOf(offer("red", "v-red"), offer("red", "v-red")))
        rejected(target, listOf(offer("", "v-red")))
    }
}
