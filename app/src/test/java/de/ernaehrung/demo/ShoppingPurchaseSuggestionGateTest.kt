package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ExternalPriceBridge as Price
import de.ernaehrung.demo.data.integration.RetailerPolicy as Policy
import de.ernaehrung.demo.data.integration.ShoppingComparabilityKernel as Comparable
import de.ernaehrung.demo.data.integration.ShoppingNeedsKernel as Needs
import de.ernaehrung.demo.data.integration.ShoppingOfferGate as OfferGate
import de.ernaehrung.demo.data.integration.ShoppingPriceComparisonGate as Comparison
import de.ernaehrung.demo.data.integration.ShoppingPurchaseSuggestionGate as Suggest
import de.ernaehrung.demo.data.integration.ShoppingUnitRateKernel as UnitRate
import java.math.BigInteger
import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

/** Synthetic host-gate regression, NOT full Python suggest_purchases parity. */
class ShoppingPurchaseSuggestionGateTest {
    private val day = LocalDate.parse("2026-11-01")
    private val cutoff = OffsetDateTime.parse("2026-10-31T12:00:00+01:00")
    private val rights = Price.DisplayPermissionRegistry { source, ref, capability, on ->
        source == "test-licensed-source" && ref == "host-grant-1" &&
            capability == Policy.Capability.PUBLISHED_OFFER_COLLECTION && on == day
    }
    private val stores = OfferGate.ConfirmedStoreRegistry { retailer, store, region, _ ->
        retailer == Policy.Retailer.LIDL && store == "store-1" && region == "berlin"
    }
    private val articles = Comparison.ConfirmedArticleRegistry { version, article, _, _ ->
        version.startsWith("v-") && article.foodTypeId in setOf("milk", "oat")
    }
    private val budget = Suggest.Budget(300, day, day.plusDays(6))
    private val capacity = mapOf<String, Long?>("DRY" to 1000L)
    private fun demand(id: String, food: String, amount: Long?,
                       basis: String = "NET") =
        Needs.Demand(id, food, day.plusDays(1).toString(),
            Needs.Quantity(amount, "g", basis, 1, amount != null))
    private fun needs(vararg demands: Needs.Demand) =
        Needs.project(Needs.Snapshot(day.toString(), emptyList(), demands.toList()),
            listOf(7))
    private fun offer(id: String, food: String, amount: Long, price: Long,
                      deposit: Long? = 0, basis: String = "NET"): Suggest.Offer {
        val version = "v-" + id
        val request = Price.Request(Policy.Retailer.LIDL,
            Policy.Capability.PUBLISHED_OFFER_COLLECTION, version, day, cutoff,
            "store-1", "berlin")
        val candidate = Price.Candidate(id, version, true, Policy.Retailer.LIDL,
            Policy.Capability.PUBLISHED_OFFER_COLLECTION, "test-licensed-source",
            Price.Origin.EXTERNAL, Price.RightsStatus.PERMITTED, "host-grant-1",
            cutoff.minusHours(2), day, day.plusDays(4), "store-1", "berlin",
            price, deposit, amount * 1000, Price.Unit.GRAM,
            conditionsVerified = true)
        val facts = OfferGate.HostFacts("SALE", "CONFIRMED", "AVAILABLE", false,
            "STORE_CONFIRMED", true)
        val article = Comparable.Article(foodTypeId = food,
            attributes = mapOf("state" to "raw"),
            quantity = UnitRate.Quantity(amount, "g", basis, 1, true))
        return Suggest.Offer(Comparison.Input(request, candidate, facts, article, false),
            shelfLifeDays = 5, storageClass = "DRY", packageVolumeMl = 100)
    }
    private fun suggest(
        needs: Needs.Result, offers: List<Suggest.Offer>,
        budget: Suggest.Budget? = this.budget,
        capacity: Map<String, Long?>? = this.capacity,
        rights: Price.DisplayPermissionRegistry = this.rights,
        stores: OfferGate.ConfirmedStoreRegistry = this.stores,
        articles: Comparison.ConfirmedArticleRegistry = this.articles
    ) = Suggest.suggest(needs, 7, day, cutoff, offers, budget, capacity,
        rights, stores, articles)

    @Test fun twoFoodTypesSequentialCheckoutIncludesDepositWithoutBooking() {
        val projected = needs(demand("milk-plan", "milk", 10),
            demand("oat-plan", "oat", 5))
        val offers = listOf(offer("milk-offer", "milk", 10, 100, 25),
            offer("oat-offer", "oat", 5, 50, 0))
        val result = suggest(projected, offers)
        assertEquals(listOf("milk", "oat"), result.rows.map { it.foodTypeId })
        assertEquals(listOf("FEASIBLE_NOT_PURCHASED", "FEASIBLE_NOT_PURCHASED"),
            result.rows.map { it.status })
        assertEquals(BigInteger.valueOf(150), result.totalMerchandiseCents)
        assertEquals(BigInteger.valueOf(175), result.totalCheckoutCents)
        assertEquals(BigInteger.valueOf(125), result.budgetRemainingCents)
        assertEquals(BigInteger.valueOf(125), result.rows[0].checkoutCents)
        assertEquals(BigInteger.valueOf(25), result.rows[0].depositCents)
        assertTrue(result.snapshotComplete)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.sideEffects.isEmpty())
        assertFalse(result.marketWideBestPriceProven)
        assertEquals(2, offers.size)
    }

    @Test fun independentRightsStoreAndArticleRegistriesFailClosed() {
        val projected = needs(demand("need", "milk", 10))
        val pack = offer("offer", "milk", 10, 100, 0)
        val noRights = suggest(projected, listOf(pack), rights = Price.denyAll)
        assertEquals("REVIEW", noRights.rows.single().status)
        assertNull(noRights.totalCheckoutCents)
        assertEquals("store/source-rights/price/validity gate rejected",
            noRights.rows.single().rejected["offer"])
        val badStore = suggest(projected, listOf(pack),
            stores = OfferGate.denyAllStores)
        assertNull(badStore.totalMerchandiseCents)
        val unavailable = suggest(projected, listOf(pack),
            articles = Comparison.ConfirmedArticleRegistry { _, _, _, _ ->
                throw IllegalStateException("registry unavailable")
            })
        assertEquals("host product/version identity not confirmed",
            unavailable.rows.single().rejected["offer"])
        assertTrue(unavailable.sideEffects.isEmpty())
    }

    @Test fun futureEvidenceAndCutoffMismatchNeverAuthorizePrices() {
        val projected = needs(demand("need", "milk", 10))
        val pack = offer("offer", "milk", 10, 100)
        val future = pack.copy(input = pack.input.copy(candidate =
            pack.input.candidate.copy(observedAt = cutoff.plusMinutes(1))))
        assertNull(suggest(projected, listOf(future)).totalCheckoutCents)
        val laterCutoff = pack.copy(input = pack.input.copy(request =
            pack.input.request.copy(asOf = cutoff.plusMinutes(1))))
        val result = suggest(projected, listOf(laterCutoff))
        assertEquals("shopping date/evidence cutoff mismatch",
            result.rows.single().rejected["offer"])
        assertNull(result.budgetRemainingCents)
    }

    @Test fun unknownDepositIsNotZeroAndKnownZeroIsValid() {
        val projected = needs(demand("need", "milk", 10))
        val unknown = offer("unknown", "milk", 10, 1, null)
        val result = suggest(projected, listOf(unknown))
        assertNull(result.totalCheckoutCents)
        assertEquals("deposit/checkout cash UNKNOWN",
            result.rows.single().rejected["unknown"])
        val free = suggest(projected, listOf(offer("free", "milk", 10, 0, 0)))
        assertEquals(BigInteger.ZERO, free.totalCheckoutCents)
        assertEquals(BigInteger.ZERO, free.totalMerchandiseCents)
        assertEquals("FEASIBLE_NOT_PURCHASED", free.rows.single().status)
    }

    @Test fun multibuyRequiresIndependentHostAttestation() {
        val projected = needs(demand("need", "milk", 10))
        val pack = offer("multi", "milk", 5, 50).copy(minPacks = 2)
        val denied = suggest(projected, listOf(pack))
        assertNull(denied.totalCheckoutCents)
        assertEquals("multibuy terms not independently confirmed",
            denied.rows.single().rejected["multi"])
        val attested = suggest(projected, listOf(pack.copy(
            multibuyTermsConfirmedByHost = true)))
        assertEquals(BigInteger.valueOf(100), attested.totalCheckoutCents)
        assertEquals(2L, attested.rows.single().lines.single().packs)
    }

    @Test fun overBudgetCapacityAndShelfLifeNeverClaimFeasible() {
        val projected = needs(demand("need", "milk", 10))
        val pack = offer("offer", "milk", 10, 100, 25)
        val overBudget = suggest(projected, listOf(pack),
            budget = budget.copy(amountCents = 100))
        assertEquals("REVIEW", overBudget.rows.single().status)
        assertNull(overBudget.budgetRemainingCents)
        assertEquals(BigInteger.valueOf(125), overBudget.knownCheckoutSubtotalCents)
        val noSpace = suggest(projected, listOf(pack),
            capacity = mapOf("DRY" to 50L))
        assertEquals("REVIEW", noSpace.rows.single().status)
        val spoils = suggest(projected, listOf(pack.copy(shelfLifeDays = 0)))
        assertEquals("REVIEW", spoils.rows.single().status)
        val noBudget = suggest(projected, listOf(pack), budget = null)
        assertEquals("REVIEW", noBudget.rows.single().status)
        assertNull(noBudget.budgetRemainingCents)
    }

    @Test fun incompleteNeedsAndUnitBasisRemainVisibleAsUnknown() {
        val projected = needs(demand("known", "milk", 10),
            demand("unknown", "milk", null))
        val result = suggest(projected, listOf(offer("offer", "milk", 10, 100)))
        assertFalse(result.snapshotComplete)
        assertNull(result.totalMerchandiseCents)
        assertNull(result.totalCheckoutCents)
        assertNull(result.budgetRemainingCents)
        assertTrue(result.issues.contains("need/stock snapshot incomplete"))
        val mixed = needs(demand("net", "milk", 10),
            demand("drained", "milk", 5, "DRAINED"))
        val distinct = suggest(mixed, listOf(offer("net-offer", "milk", 10, 100)))
        assertEquals(2, distinct.rows.size)
        assertTrue(distinct.rows.any { it.basis == "DRAINED" &&
            it.checkoutCents == null })
        assertNull(distinct.totalCheckoutCents)
    }

    @Test fun separatelyConfirmedQuantityBasesDoNotContaminateEachOthersCoverage() {
        val projected = needs(demand("net", "milk", 10, "NET"),
            demand("drained", "milk", 5, "DRAINED"))
        val offers = listOf(offer("net-price", "milk", 10, 100, basis = "NET"),
            offer("drained-price", "milk", 5, 50, basis = "DRAINED"))
        val result = suggest(projected, offers)
        assertEquals(listOf("DRAINED", "NET"), result.rows.map { it.basis })
        assertEquals(listOf("FEASIBLE_NOT_PURCHASED", "FEASIBLE_NOT_PURCHASED"),
            result.rows.map { it.status })
        assertTrue(result.rows.all { it.rejected.isEmpty() && it.reasons.isEmpty() })
        assertEquals(BigInteger.valueOf(150), result.totalCheckoutCents)
        assertEquals(BigInteger.valueOf(150), result.totalMerchandiseCents)
        assertEquals(BigInteger.valueOf(150), result.budgetRemainingCents)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test fun duplicateObservationsAndStaleSnapshotCannotBeOptimized() {
        val projected = needs(demand("need", "milk", 10))
        val pack = offer("same", "milk", 10, 100)
        assertThrows(IllegalArgumentException::class.java) {
            suggest(projected, listOf(pack, pack))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Suggest.suggest(projected, 7, day.plusDays(1), cutoff, listOf(pack))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Suggest.suggest(projected, 28, day, cutoff, listOf(pack))
        }
    }

    @Test fun laterCutoffCannotRetroactivelyAuthorizeShoppingDay() {
        val projected = needs(demand("need", "milk", 10))
        val pack = offer("offer", "milk", 10, 100)
        val hindsight = day.plusDays(2).atTime(12, 0).atOffset(
            java.time.ZoneOffset.ofHours(1))
        val backdated = pack.copy(input = pack.input.copy(
            request = pack.input.request.copy(asOf = hindsight),
            candidate = pack.input.candidate.copy(observedAt = hindsight.minusHours(1))))
        assertThrows(IllegalArgumentException::class.java) {
            Suggest.suggest(projected, 7, day, hindsight, listOf(backdated),
                budget, capacity, rights, stores, articles)
        }
    }

    @Test fun laterObservedLocalDayCannotLeakThroughOffsetConversion() {
        val projected = needs(demand("need", "milk", 10))
        val localCutoff = day.atTime(22, 0).atOffset(
            java.time.ZoneOffset.ofHours(1))
        val pack = offer("offer", "milk", 10, 100)
        val sourceClock = localCutoff.withOffsetSameInstant(
            java.time.ZoneOffset.ofHours(14))
        assertTrue(sourceClock.toLocalDate().isAfter(day))
        val offsetEvidence = pack.copy(input = pack.input.copy(
            request = pack.input.request.copy(asOf = localCutoff),
            candidate = pack.input.candidate.copy(observedAt = sourceClock)))
        val result = Suggest.suggest(projected, 7, day, localCutoff,
            listOf(offsetEvidence), budget, capacity, rights, stores, articles)
        assertEquals("REVIEW", result.rows.single().status)
        assertEquals("price observation postdates shopping day",
            result.rows.single().rejected["offer"])
        assertNull(result.totalCheckoutCents)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test fun unresolvedEarlierNeedBlocksLaterSequentialFeasibilityClaim() {
        val projected = needs(demand("milk", "milk", 10),
            demand("oat", "oat", 5))
        val result = suggest(projected, listOf(
            offer("milk-unknown", "milk", 10, 100, null),
            offer("oat-verified", "oat", 5, 50, 0)))
        assertEquals(listOf("REVIEW", "REVIEW"),
            result.rows.map { it.status })
        assertEquals("deposit/checkout cash UNKNOWN",
            result.rows.first().rejected["milk-unknown"])
        assertTrue(result.rows.last().reasons.contains(
            "earlier need unresolved; sequential budget/capacity provisional"))
        assertEquals(BigInteger.valueOf(50), result.knownCheckoutSubtotalCents)
        assertNull(result.totalCheckoutCents)
        assertNull(result.budgetRemainingCents)
        assertTrue(result.sideEffects.isEmpty())
    }
}
