package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.offers.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Synthetic signatures here test the independent registry orchestration.
 * The separate Android case must use the real credential UI and Keystore. */
class LiveArticleReviewSessionTest {
    private var clock = Instant.parse("2026-11-17T12:00:00Z")
    private var monotonic = 1000L
    private var permitted = true
    private val day = LocalDate.parse("2026-11-17")
    private val scope = LiveOfferScope(LiveRetailer.PENNY, "9999001", null, null, day)
    private val grant = LiveSourceGrant("synthetic", LiveRetailer.PENNY, "synthetic-independent-source",
        day, day, setOf("feed.example.test"), false, 600, scope)
    private val verifier = OfferArticleProofVerifier { proof, at -> proof.signature == "synthetic-signed" &&
        proof.issuedAt <= at && at - proof.issuedAt <= 30_000 }
    private fun session() = LiveArticleReviewSession({ _, _, g -> permitted && g == grant }, verifier, { clock }, { monotonic })
    private fun version(id: String = "v1", product: String = "p1", revision: Int = 1) = ProductVersionEntity(
        id = id, productId = product, revision = revision, name = "Eigener geprüfter Reis", brand = "Eigenmarke",
        category = "Lebensmittel", barcode = "12345678", baseUnit = BaseUnit.GRAM,
        packageAmount = 500_000, nutritionBasis = 100_000, energyKcal = null, proteinGrams = null, fatGrams = null, carbsGrams = null,
        source = Source.USER, sourceDetail = null, quality = Quality.CONFIRMED,
        recordedAt = clock.minusSeconds(3600).toEpochMilli(), changeReason = "Synthetische unabhängige Testfassung")
    private fun offer(id: String = "a", price: Long? = 199) = LiveOffer("synthetic:$id", "synthetic", LiveRetailer.PENNY,
        "Ungeprüfter Quellenname", id, null, price, null, null, null, null, LiveOfferKind.PROMOTION,
        null, null, null, clock, scope.storeId, null, null, LiveOfferApplicability.STORE_CONFIRMED, null, false,
        "https://feed.example.test/offers")
    private fun draft(id: String = "v1", count: Long = 1) = LiveArticleReviewDraft(id, "Reis roh ungesüßt", 500, count,
        "NET", null, day, day, emptyMap(), true, true, true)
    private fun proof(c: LiveArticleReviewSession.Challenge) = OfferArticleProof(c.id, c.digest, c.nonce, clock.toEpochMilli(), "synthetic-signed")
    private fun approve(s: LiveArticleReviewSession, o: LiveOffer, d: LiveArticleReviewDraft, vs: List<ProductVersionEntity>) {
        val c = s.prepare(o, scope, grant, d, vs); s.accept(c.id, proof(c), vs)
    }
    private fun refused(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun authenticatedSessionConnectsExactProductPackagesPricesAndKnownShoppingNeedWithoutChangingRawUnknowns() {
        val s = session(); val vs = listOf(version()); val a = offer(); val b = offer("b", 299)
        assertNull(s.compare(a, scope, b, scope, vs).rateOrder)
        approve(s, a, draft(), vs); approve(s, b, draft(count = 2), vs)
        assertTrue(s.compare(a, scope, b, scope, vs).rateOrder!! > 0)
        val need = ShoppingNeed("v1", "Eigener Reis", BaseUnit.GRAM, 1_250_000, 1)
        assertEquals(PackCalculation(2, 2_000_000, 750_000, 598), s.previewNeed(b, scope, need, vs))
        assertNull(a.quantityMilli); assertNull(a.validUntil); assertNull(b.checkoutCents)
        assertNull(s.previewNeed(b, scope, need.copy(versionId = "different"), vs))
        assertNull(s.previewNeed(b, scope, need.copy(unit = BaseUnit.MILLILITRE), vs))
        assertNull(session().reviewed(a, scope, vs)) // Process/session restart is fail-closed.
        clock = clock.plusSeconds(60)
        assertNotNull(s.reviewed(a, scope, vs)) // An accepted signature is checked at its original confirmation.
    }

    @Test fun refreshSourceRevocationOrCurrentCatalogRevisionInvalidatesPricesBeforeComparisonAndNeedPreview() {
        val s = session(); val v = version(); val a = offer(); val b = offer("b", 0); val vs = listOf(v)
        approve(s, a, draft(), vs); approve(s, b, draft(), vs)
        assertTrue(s.compare(a, scope, b, scope, vs).rateOrder!! > 0)
        assertNull(s.reviewed(a.copy(merchandiseCents = 201), scope, vs))
        assertNull(s.reviewed(a, scope, vs + version("v2", revision = 2)))
        permitted = false
        assertNull(s.reviewed(a, scope, vs)); assertNull(s.compare(a, scope, b, scope, vs).rateOrder)
        assertNull(s.previewNeed(a, scope, ShoppingNeed("v1", "Reis", BaseUnit.GRAM, 100_000, 0), vs))
    }

    @Test fun actualProofNonceAndOneShotChallengeCannotBeReplayedAndOldCallbackDoesNotConsumeNewChallenge() {
        val s = session(); val vs = listOf(version()); val a = offer()
        val old = s.prepare(a, scope, grant, draft(), vs)
        val fresh = s.prepare(a, scope, grant, draft(), vs)
        refused { s.accept(old.id, proof(old), vs) }
        s.accept(fresh.id, proof(fresh), vs)
        refused { s.accept(fresh.id, proof(fresh), vs) }
        val invalid = s.prepare(a, scope, grant, draft(), vs)
        refused { s.accept(invalid.id, proof(invalid).copy(nonce = old.nonce), vs) }
        refused { s.accept(invalid.id, proof(invalid), vs) }
        val timeout = s.prepare(a, scope, grant, draft(), vs); monotonic += 300_001
        refused { s.accept(timeout.id, proof(timeout), vs) }
        val changed = s.prepare(a, scope, grant, draft(), vs)
        refused { s.accept(changed.id, proof(changed), listOf(version().copy(brand = "Andere Marke"))) }
    }

    @Test fun manualReviewCannotOverrideKnownQuantityGtinValidityOrInventFutureKnowledgeOrDemoCatalogIdentity() {
        val s = session(); val vs = listOf(version()); val a = offer()
        refused { s.prepare(a.copy(gtin = "87654321"), scope, grant, draft(), vs) }
        refused { s.prepare(a.copy(quantityMilli = 400_000), scope, grant, draft(), vs) }
        refused { s.prepare(a.copy(quantityBasis = OfferQuantityBasis.DRAINED), scope, grant, draft(), vs) }
        refused { s.prepare(a.copy(validUntil = day.minusDays(1)), scope, grant, draft(), vs) }
        refused { s.prepare(a, scope, grant, draft().copy(identityConfirmed = false), vs) }
        refused { s.prepare(a, scope, grant, draft(), listOf(version().copy(source = Source.DEMO))) }
        refused { s.prepare(a, scope, grant, draft(), listOf(version().copy(packageAmount = 500_001))) }
        clock = clock.plusSeconds(86400)
        refused { s.prepare(a, scope, grant, draft(), vs) }
    }

    @Test fun differentVersionsRequireIndependentlyCompleteVariantFactsOnBothSidesRatherThanMatchingSourceNames() {
        val s = session(); val vs = listOf(version(), version("other", "p2")); val a = offer(); val b = offer("b", 299)
        approve(s, a, draft(), vs)
        refused { approve(s, b, draft("other"), vs) }
        s.clear()
        val attrs = LiveArticleReviewSession.criticalAttributes.associateWith { "ausdrücklich geprüft" }
        approve(s, a, draft().copy(attributes = attrs), vs)
        approve(s, b, draft("other").copy(attributes = attrs), vs)
        assertNotNull(s.compare(a, scope, b, scope, vs).rateOrder)
    }
}
