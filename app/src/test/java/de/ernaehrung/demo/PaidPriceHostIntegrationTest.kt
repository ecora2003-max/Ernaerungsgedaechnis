package de.ernaehrung.demo

import android.content.Context
import de.ernaehrung.demo.data.integration.RetailerPolicy as Policy
import de.ernaehrung.demo.data.integration.ExternalPriceBridge as Price
import de.ernaehrung.demo.data.integration.ShoppingOfferGate as OfferGate
import de.ernaehrung.demo.data.integration.ShoppingPriceComparisonGate as CompareGate
import de.ernaehrung.demo.data.integration.ShoppingPurchaseSuggestionGate as SuggestGate
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import de.ernaehrung.demo.data.integration.ShoppingNeedsKernel
import de.ernaehrung.demo.data.integration.ShoppingPurchaseSuggestionGate

/** Synthetic multi-module integration without retailer feeds, live user data or write side-effects from price previews. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaidPriceHostIntegrationTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val dao get() = db.foodDao()
    private val day = "2026-10-30"
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { Instant.parse("2026-10-30T12:00:00Z").toEpochMilli() }
    }
    @After fun close() { db.close() }
    private suspend fun tofu() = store.rememberProduct("tofu", ProductInput("Tofu", unit = BaseUnit.GRAM))
    private fun review(key: String, lineId: String, version: String) = PaidPriceBasisReview(
        key, lineId, version, 2, 0, null, true, true, true, true
    )
    private fun purchase(v: String, date: String = day, nonFood: Boolean = false) = PurchaseInput(
        "Testmarkt", date, 398,
        listOf(PurchaseLineInput(if (nonFood) "TASCHE 3,98" else "TOFU 2x 1,99",
            if (nonFood) "Tragetasche" else "Tofu", v, totalCents = 398)),
        "Echte Zahlung anhand des Testbelegs bestätigt"
    )

    @Test fun scanToPaidLineToReadOnlyHistoryPreviewRequiresSeparateLineAndBasisConfirmation() = runBlocking {
        val v = tofu()
        val original = "TESTMARKT\n30.10.2026\nTOFU 2x 1,99\nSUMME 3,98"
        val scan = store.saveReceiptScan("scan", original, "MANUAL_TEXT")
        val draft = store.reviewReceiptScan(scan)
        assertEquals(0, dao.purchases().size)
        assertTrue(dao.receipts().isEmpty())
        assertEquals(0, dao.consumptionCount())
        val receiptId = store.acceptReceiptScan("paid", scan, purchase(v), draft.snapshot, true)
        assertEquals(receiptId, store.acceptReceiptScan("paid", scan, purchase(v), draft.snapshot, true))
        val line = dao.receiptLines(receiptId).single()
        val request = review("paid", line.id, v)
        assertEquals("UNCONFIRMED_PURCHASE_OR_LINE",
            store.previewHistoricalPaidPrice(request.copy(foodAndProductConfirmed = false)).rejectionReason)
        assertEquals("UNKNOWN_PURCHASE_UNITS",
            store.previewHistoricalPaidPrice(request.copy(purchaseUnits = null)).rejectionReason)
        assertEquals("DISCOUNT_ACCOUNTING_UNKNOWN",
            store.previewHistoricalPaidPrice(request.copy(allocatedDiscountCents = null)).rejectionReason)
        assertEquals("DEPOSIT_ACCOUNTING_UNKNOWN",
            store.previewHistoricalPaidPrice(request.copy(amountExcludesDeposit = false)).rejectionReason)
        val first = store.previewHistoricalPaidPrice(request)
        assertTrue(first.eligible)
        assertEquals(199L, first.unitPriceCents)
        assertEquals(398L, first.linePaidCents)
        assertEquals("HISTORICAL_PAID", first.priceKind)
        assertEquals(first, store.previewHistoricalPaidPrice(request))
        assertEquals(1, dao.purchases().size)
        assertEquals(0, dao.consumptionCount())
        assertTrue(dao.planningBatches().isEmpty())
        assertTrue(dao.prices().isEmpty()) // Crucial: history is NOT accidentally a current offer.
    }

    @Test fun twoRealPurchasesGetDistinctEvidenceAndCancellationTombstonesOldPurchase() = runBlocking {
        val v = tofu()
        val first = store.savePurchase("buy-one", purchase(v, "2026-10-29"), true)
        val second = store.savePurchase("buy-two", purchase(v), true)
        val firstReq = review("buy-one", dao.receiptLines(first).single().id, v)
        val secondReq = review("buy-two", dao.receiptLines(second).single().id, v)
        val a = store.previewHistoricalPaidPrice(firstReq)
        val b = store.previewHistoricalPaidPrice(secondReq)
        assertTrue(a.eligible && b.eligible)
        assertNotEquals(a.observationId, b.observationId)
        assertEquals("2026-10-29", a.purchasedOn)
        assertEquals(day, b.purchasedOn)
        assertEquals(first, store.savePurchase("buy-one", purchase(v, "2026-10-29"), true))
        assertEquals(2, dao.purchases().size)
        store.cancelPurchase("undo-one", ReversalInput("buy-one", day, "Testkauf fälschlich erfasst"), true)
        assertEquals("REVERSED_PURCHASE_OR_LINE", store.previewHistoricalPaidPrice(firstReq).rejectionReason)
        assertEquals(b, store.previewHistoricalPaidPrice(secondReq))
        assertEquals(2, dao.purchases().size)
        assertTrue(dao.prices().isEmpty())
    }

    @Test fun nonFoodAndWrongVersionCannotBePromotedToPaidFoodPrice() = runBlocking {
        val v = tofu()
        val other = store.rememberProduct("other", ProductInput("Bohnen"))
        val receipt = store.savePurchase("bag", purchase(v, nonFood = true), true)
        val id = dao.receiptLines(receipt).single().id
        assertEquals("NON_FOOD_DISCOUNT_DEPOSIT_OR_RETURN",
            store.previewHistoricalPaidPrice(review("bag", id, v)).rejectionReason)
        assertEquals("PRODUCT_VERSION_MISMATCH",
            store.previewHistoricalPaidPrice(review("bag", id, other)).rejectionReason)
        assertEquals("PURCHASE_NOT_CONFIRMED",
            store.previewHistoricalPaidPrice(review("not-a-payment", id, v)).rejectionReason)
    }
    @Test fun explicitHistoricalPriceWriteIsAtomicIdempotentAndPurchaseReversalIsTombstone() = runBlocking {
        val v = tofu()
        val first = store.savePurchase("paid-one", purchase(v), true)
        val second = store.savePurchase("paid-two", purchase(v, "2026-10-29"), true)
        val request1 = review("paid-one", dao.receiptLines(first).single().id, v)
            .copy(basisReason = "Zwei Stück, Warenbetrag und Rabatte manuell geprüft")
        val request2 = review("paid-two", dao.receiptLines(second).single().id, v)
            .copy(basisReason = "Zweiter eigenständiger Kassenbon geprüft")
        try {
            store.saveHistoricalPaidPrice("price-unconfirmed", request1, false)
            fail("An unconfirmed review cannot create an observation")
        } catch (_: IllegalArgumentException) { }
        assertNull(dao.operation("price-unconfirmed"))
        assertTrue(dao.allPaidPrices().isEmpty())
        val id1 = store.saveHistoricalPaidPrice("price-one", request1, true)
        assertEquals(id1, store.saveHistoricalPaidPrice("price-one", request1, true))
        assertEquals(199L, dao.allPaidPrices().single().unitPriceCents)
        assertEquals(1, dao.activePaidPrices().size)
        try {
            store.saveHistoricalPaidPrice("price-duplicate", request1.copy(basisReason = "Andere Deutung"), true)
            fail("No second historical price for the same receipt line")
        } catch (_: IllegalArgumentException) { }
        assertNull(dao.operation("price-duplicate"))
        assertEquals(1, dao.allPaidPrices().size)
        val id2 = store.saveHistoricalPaidPrice("price-two", request2, true)
        assertNotEquals(id1, id2)
        assertEquals(2, dao.activePaidPrices().size)
        assertTrue(dao.prices().isEmpty()) // No historical price is silently a current offer.
        assertEquals(0, dao.consumptionCount())
        store.cancelPurchase("undo-one", ReversalInput("paid-one", day, "Testkauf zurückgenommen"), true)
        assertEquals(listOf(id2), store.activeHistoricalPaidPrices().map { it.id })
        assertEquals(2, store.historicalPaidPriceAudit().size)
        assertEquals(id1, store.saveHistoricalPaidPrice("price-one", request1, true)) // replay of immutable fact
        assertEquals(2, dao.allPaidPrices().size)
    }

    @Test fun invalidHistoricalPriceReviewRollsBackOperationAndNeverCreatesObservation() = runBlocking {
        val v = tofu()
        val receipt = store.savePurchase("paid-invalid", purchase(v), true)
        val lineId = dao.receiptLines(receipt).single().id
        val base = review("paid-invalid", lineId, v).copy(basisReason = "Mengenbasis geprüft")
        for ((index, bad) in listOf(
            base.copy(purchaseUnits = null), base.copy(allocatedDiscountCents = null),
            base.copy(amountExcludesDeposit = false), base.copy(foodAndProductConfirmed = false),
            base.copy(basisReason = ""), base.copy(purchaseUnits = 3)
        ).withIndex()) {
            val key = "bad-price-$index"
            try {
                store.saveHistoricalPaidPrice(key, bad, true)
                fail("Unsafe price review must fail: $index")
            } catch (_: IllegalArgumentException) { }
            assertNull(dao.operation(key))
            assertTrue(dao.allPaidPrices().isEmpty())
        }
    }

    @Test fun receiptToPrivatePriceToUnbookedShoppingNeedNeverPromotesHistoryToLiveOffer() = runBlocking {
        val version = tofu()
        val scan = store.saveReceiptScan("chain-scan", "TESTMARKT\n30.10.2026\nTOFU 2x 1,99\nSUMME 3,98", "MANUAL_TEXT")
        val draft = store.reviewReceiptScan(scan)
        assertTrue(dao.purchases().isEmpty())
        assertTrue(dao.allPaidPrices().isEmpty())
        val receipt = store.acceptReceiptScan("chain-payment", scan, purchase(version), draft.snapshot, true)
        val line = dao.receiptLines(receipt).single()
        val paid = store.saveHistoricalPaidPrice("chain-price",
            review("chain-payment", line.id, version).copy(basisReason = "Zwei Stück und Kassenbetrag geprüft"), true)
        assertEquals(paid, store.saveHistoricalPaidPrice("chain-price",
            review("chain-payment", line.id, version).copy(basisReason = "Zwei Stück und Kassenbetrag geprüft"), true))
        val historical = HistoricalPaidComparisonReader(db).compare(
            HistoricalPaidComparisonScope(version, day, Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()))
        assertEquals(listOf(paid), historical.references.map { it.observationId })
        assertEquals(199L, historical.lowestHistoricalUnitPriceCents)
        assertEquals("HISTORICAL_PAID", historical.priceKind)
        assertFalse(historical.isCurrentOffer)

        val need = ShoppingNeedsKernel.project(ShoppingNeedsKernel.Snapshot(day, emptyList(),
            listOf(ShoppingNeedsKernel.Demand("tomorrow-tofu", "tofu", "2026-10-31",
                ShoppingNeedsKernel.Quantity(10, "g", "NET", 1, true)))), listOf(7))
        assertEquals(10L, need.horizons.getValue(7).totals.single().quantityToBuy.toLong())
        val beforeOperations = dao.operationCount()
        val suggestion = ShoppingPurchaseSuggestionGate.suggest(need, 7, LocalDate.parse(day),
            OffsetDateTime.parse("2026-10-30T11:00:00Z"), emptyList(),
            ShoppingPurchaseSuggestionGate.Budget(500, LocalDate.parse(day), LocalDate.parse(day).plusDays(6)),
            mapOf("DRY" to 1000L))
        assertEquals("REVIEW", suggestion.rows.single().status)
        assertNull(suggestion.totalCheckoutCents)
        assertTrue(suggestion.rows.single().lines.isEmpty())
        assertTrue(suggestion.sideEffects.isEmpty())
        assertEquals(beforeOperations, dao.operationCount())
        assertEquals(1, dao.purchases().size)
        assertEquals(1, dao.allPaidPrices().size)
        assertTrue(dao.prices().isEmpty())
        assertEquals(0, dao.consumptionCount())

        store.cancelPurchase("chain-reversal", ReversalInput("chain-payment", day, "Testkauf storniert"), true)
        val afterReversal = HistoricalPaidComparisonReader(db).compare(
            HistoricalPaidComparisonScope(version, day, Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()))
        assertTrue(afterReversal.references.isEmpty())
        assertEquals(1, dao.allPaidPrices().size) // Immutable audit retained, not a current offer.
    }


    @Test fun authorizedPriceAlternativeAndShoppingProposalRemainReadOnlyAfterPaidReceipt() = runBlocking {
        // A positive synthetic cross-module chain. Trust registries are supplied by
        // the test host, not by a feed; this does not enable a real retailer.
        val version = tofu()
        val scan = store.saveReceiptScan("positive-scan",
            "TESTMARKT\n30.10.2026\nTOFU 2x 1,99\nSUMME 3,98", "MANUAL_TEXT")
        val draft = store.reviewReceiptScan(scan)
        assertTrue(dao.purchases().isEmpty())
        assertTrue(dao.allPaidPrices().isEmpty())
        val receipt = store.acceptReceiptScan("positive-payment", scan,
            purchase(version), draft.snapshot, true)
        assertEquals(receipt, store.acceptReceiptScan("positive-payment", scan,
            purchase(version), draft.snapshot, true))
        val lineId = dao.receiptLines(receipt).single().id
        val basis = review("positive-payment", lineId, version).copy(
            basisReason = "Zwei Einheiten und Warenbetrag gesondert bestätigt")
        val historicalId = store.saveHistoricalPaidPrice("positive-paid-price", basis, true)
        assertEquals(historicalId,
            store.saveHistoricalPaidPrice("positive-paid-price", basis, true))
        val historical = HistoricalPaidComparisonReader(db).compare(
            HistoricalPaidComparisonScope(version, day,
                Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()))
        assertEquals(listOf(historicalId), historical.references.map { it.observationId })
        assertFalse(historical.isCurrentOffer)
        assertTrue(dao.prices().isEmpty())

        val shoppingDay = LocalDate.parse(day)
        val cutoff = OffsetDateTime.parse("2026-10-30T11:00:00Z")
        val article = de.ernaehrung.demo.data.integration.ShoppingComparabilityKernel.Article(
            foodTypeId = "tofu", attributes = mapOf("state" to "raw"),
            quantity = de.ernaehrung.demo.data.integration.ShoppingUnitRateKernel.Quantity(
                10, "g", "NET", 1, true))
        val rights = Price.DisplayPermissionRegistry { source, ref, capability, on ->
            source == "synthetic-licensed-source" && ref == "synthetic-host-grant" &&
                capability == Policy.Capability.PUBLISHED_OFFER_COLLECTION && on == shoppingDay
        }
        val stores = OfferGate.ConfirmedStoreRegistry { retailer, storeId, region, _ ->
            retailer == Policy.Retailer.LIDL && storeId == "canonical-test-store" &&
                region == "berlin"
        }
        val articles = CompareGate.ConfirmedArticleRegistry { productVersion, checked, _, _ ->
            productVersion == version && checked.foodTypeId == "tofu" &&
                checked.quantity == article.quantity
        }
        fun observed(id: String, cents: Long, deposit: Long? = 0) : CompareGate.Input {
            val request = Price.Request(Policy.Retailer.LIDL,
                Policy.Capability.PUBLISHED_OFFER_COLLECTION, version, shoppingDay,
                cutoff, "canonical-test-store", "berlin")
            val candidate = Price.Candidate(id, version, true, Policy.Retailer.LIDL,
                Policy.Capability.PUBLISHED_OFFER_COLLECTION, "synthetic-licensed-source",
                Price.Origin.EXTERNAL, Price.RightsStatus.PERMITTED, "synthetic-host-grant",
                cutoff.minusHours(1), shoppingDay, shoppingDay.plusDays(2),
                "canonical-test-store", "berlin", cents, deposit, 10_000,
                Price.Unit.GRAM, conditionsVerified = true)
            return CompareGate.Input(request, candidate,
                OfferGate.HostFacts("SALE", "CONFIRMED", "AVAILABLE", false,
                    "STORE_CONFIRMED", true), article, false)
        }
        val target = observed("synthetic-target", 250)
        val cheaper = observed("synthetic-cheaper", 100, 25)
        val beforeProposals = dao.operationCount()
        val comparison = CompareGate.compare(target, listOf(cheaper),
            permissions = rights, stores = stores, articles = articles)
        assertTrue(comparison.targetEligible)
        assertEquals(listOf("synthetic-cheaper"),
            comparison.cheaper.map { it.observationId })
        assertTrue(comparison.signals.signals.contains("RED_SAME_BIO_CHEAPER"))
        assertFalse(comparison.marketWideBestPriceProven)

        val needs = ShoppingNeedsKernel.project(ShoppingNeedsKernel.Snapshot(
            day, emptyList(), listOf(ShoppingNeedsKernel.Demand(
                "synthetic-need", "tofu", "2026-10-31",
                ShoppingNeedsKernel.Quantity(10, "g", "NET", 1, true)))),
            listOf(7))
        val pack = SuggestGate.Offer(cheaper, shelfLifeDays = 5,
            storageClass = "DRY", packageVolumeMl = 100)
        fun propose(
            offer: SuggestGate.Offer = pack,
            permissions: Price.DisplayPermissionRegistry = rights,
            storeRegistry: OfferGate.ConfirmedStoreRegistry = stores,
            articleRegistry: CompareGate.ConfirmedArticleRegistry = articles
        ) = SuggestGate.suggest(needs, 7, shoppingDay, cutoff, listOf(offer),
            SuggestGate.Budget(500, shoppingDay, shoppingDay.plusDays(6)),
            mapOf("DRY" to 1000L), permissions, storeRegistry, articleRegistry)
        val positive = propose()
        assertEquals("FEASIBLE_NOT_PURCHASED", positive.rows.single().status)
        assertEquals("synthetic-cheaper", positive.rows.single().lines.single().observationId)
        assertEquals(java.math.BigInteger.valueOf(100), positive.totalMerchandiseCents)
        assertEquals(java.math.BigInteger.valueOf(125), positive.totalCheckoutCents)
        assertEquals(java.math.BigInteger.valueOf(375), positive.budgetRemainingCents)
        assertTrue(positive.sideEffects.isEmpty())
        assertFalse(positive.marketWideBestPriceProven)

        // Independent gates: source permission, canonical store, product version,
        // null deposit, future observation and a different physical store.
        assertEquals("REVIEW", propose(permissions = Price.denyAll).rows.single().status)
        assertNull(propose(permissions = Price.denyAll).totalCheckoutCents)
        assertEquals("REVIEW",
            propose(storeRegistry = OfferGate.denyAllStores).rows.single().status)
        assertEquals("REVIEW",
            propose(articleRegistry = CompareGate.denyAllArticles).rows.single().status)
        assertNull(propose(offer = pack.copy(input = cheaper.copy(
            candidate = cheaper.candidate.copy(depositCents = null)))).totalCheckoutCents)
        assertNull(propose(offer = pack.copy(input = cheaper.copy(
            candidate = cheaper.candidate.copy(observedAt = cutoff.plusMinutes(1)))))
            .totalCheckoutCents)
        assertNull(propose(offer = pack.copy(input = cheaper.copy(
            request = cheaper.request.copy(storeId = "unconfirmed-store"),
            candidate = cheaper.candidate.copy(storeId = "unconfirmed-store"))))
            .totalCheckoutCents)
        assertEquals(beforeProposals, dao.operationCount())
        assertEquals(1, dao.purchases().size)
        assertEquals(1, dao.allPaidPrices().size)
        assertTrue(dao.prices().isEmpty())
        assertEquals(0, dao.consumptionCount())

        store.cancelPurchase("positive-reversal", ReversalInput(
            "positive-payment", day, "Synthetischer Kauf storniert"), true)
        val afterReversal = HistoricalPaidComparisonReader(db).compare(
            HistoricalPaidComparisonScope(version, day,
                Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()))
        assertTrue(afterReversal.references.isEmpty())
        assertEquals(1, dao.allPaidPrices().size) // Tombstone keeps the audit.
        assertEquals(beforeProposals + 1, dao.operationCount())
    }

}
