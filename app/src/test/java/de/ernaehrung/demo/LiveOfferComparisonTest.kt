package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.offers.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LiveOfferComparisonTest {
    private val at = Instant.parse("2026-11-08T10:00:00Z")
    private val day = LocalDate.parse("2026-11-08")
    private val scope = LiveOfferScope(LiveRetailer.LIDL, "DE123", null, null, day)
    private fun offer(id: String, price: Long?, amount: Long) = LiveOffer(id, "synthetic", LiveRetailer.LIDL,
        "Synthetisch", id, "same-untrusted-feed-barcode", price, null, amount, OfferMassUnit.GRAM,
        OfferQuantityBasis.NET, LiveOfferKind.PROMOTION, day, day, at, at, "DE123", null, null,
        LiveOfferApplicability.STORE_CONFIRMED, null, true, "https://feed.example.test/offers")
    private fun comparison(allowSource: Boolean = true, allowArticle: Boolean = true) = LiveOfferComparison(
        reviews = LiveArticleReviewRegistry { row, _, _ -> LiveArticleReview("version-${row.id}",
            ShoppingComparabilityKernel.Article("independently-reviewed-food", quantity = ShoppingUnitRateKernel.Quantity(
                if (row.id == "a") 500 else 1000, "g", "NET", 1, true)), null, true, true, true) },
        articles = ShoppingPriceComparisonGate.ConfirmedArticleRegistry { _, _, _, _ -> allowArticle },
        sourceStillPermitted = { _, _ -> allowSource })
    @Test fun exactExistingRateKernelComparesPackAmountsRatherThanNominalPriceAndDoesNotInventCheckoutOrCoverage() {
        val a = offer("a", 199, 500_000); val b = offer("b", 299, 1_000_000)
        val result = comparison().compare(a, scope, b, scope, at)
        assertTrue(result.rateOrder!! > 0); assertFalse(result.marketWideBestPriceProven)
        assertEquals("3,98 €/kg · NET", formatReviewedOfferRate(result.targetRate!!))
        assertEquals("2,99 €/kg · NET", formatReviewedOfferRate(result.alternativeRate!!))
        assertNull(a.checkoutCents); assertNull(b.checkoutCents)
    }
    @Test fun sameNameAndFeedBarcodeDoNotGrantIndependentIdentityScopeOrSourcePermission() {
        val a = offer("a", 199, 500_000); val b = offer("b", 100, 500_000)
        for (gate in listOf(LiveOfferComparison(), comparison(allowSource = false), comparison(allowArticle = false))) {
            assertNull(gate.compare(a, scope, b, scope, at).rateOrder)
        }
        assertNull(comparison().compare(a, scope, b.copy(applicability = LiveOfferApplicability.CHAIN_REPORTED), scope, at).rateOrder)
    }
    @Test fun pastShoppingDateDifferentQuantityBasisOrUnknownPriceNeverProduceCheaperClaim() {
        val a = offer("a", 199, 500_000); val b = offer("b", 0, 1_000_000)
        assertTrue(comparison().compare(a, scope, b, scope, at).rateOrder!! > 0)
        assertNull(comparison().compare(a, scope, b.copy(merchandiseCents = null), scope, at).rateOrder)
        assertNull(comparison().compare(a, scope, b.copy(quantityBasis = OfferQuantityBasis.DRAINED), scope, at).rateOrder)
        assertNull(comparison().compare(a, scope, b, scope, at.plusSeconds(86400)).rateOrder)
        assertNull(comparison().compare(a, scope, b.copy(fetchedAt = at.plusSeconds(1)), scope, at).rateOrder)
    }
    @Test fun missingSupplierQuantityStaysUnknownButCanUseIndependentlyConfirmedExactOfferPackageWithoutOverridingConflicts() {
        val a = offer("a", 199, 500_000); val b = offer("b", 299, 1_000_000)
            .copy(quantityMilli = null, unit = null, quantityBasis = null)
        assertTrue(comparison().compare(a, scope, b, scope, at).rateOrder!! > 0)
        assertNull(b.quantityMilli); assertNull(b.unitPriceText()) // original source facts unchanged
        assertNull(comparison().compare(a, scope, b.copy(quantityMilli = 250_000), scope, at).rateOrder)
        val uncheckedPackage = LiveOfferComparison(reviews = LiveArticleReviewRegistry { _, _, _ ->
            LiveArticleReview("v", ShoppingComparabilityKernel.Article("food"), null, true, true) },
            sourceStillPermitted = { _, _ -> true })
        assertNull(uncheckedPackage.compare(a, scope, b, scope, at).rateOrder)
    }
}
