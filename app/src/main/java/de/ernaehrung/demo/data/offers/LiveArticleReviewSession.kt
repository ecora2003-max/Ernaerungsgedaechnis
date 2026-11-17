package de.ernaehrung.demo.data.offers

import android.os.SystemClock
import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.local.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.UUID

/** User-authored product type/variant facts, never classified from feed names.
 * Blank attributes remain absent. Different catalogue versions sharing a type
 * need all critical variant facts on BOTH independently approved records. */
internal data class LiveArticleReviewDraft(val productVersionId: String, val foodTypeLabel: String,
    val amountPerPack: Long, val packageCount: Long, val basis: String, val organic: Boolean?,
    val validFrom: LocalDate, val validUntil: LocalDate, val attributes: Map<String, String>,
    val identityConfirmed: Boolean, val packageConfirmed: Boolean, val conditionsConfirmed: Boolean)

/** Ephemeral read-only review registry. No supplier response archive, database
 * migration or actual booking. Restart clears challenges/approvals and requires
 * a fresh review. Only a fresh device-bound signature can admit a record. */
internal class LiveArticleReviewSession(
    private val sourcePermitted: (LiveOfferScope, String, LiveSourceGrant) -> Boolean,
    private val verifier: OfferArticleProofVerifier = AndroidOfferArticleProof,
    private val now: () -> Instant = Instant::now,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime
) {
    companion object {
        val criticalAttributes = listOf("form", "sweetened", "species", "fatPercent", "state", "diet", "variety", "allergens", "processing", "cut")
        private fun digest(value: Any?): String = MessageDigest.getInstance("SHA-256")
            .digest(JsonOutput.encode(value).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    data class Challenge(val id: String, val digest: String, val nonce: String, val issuedElapsed: Long)
    private data class Basis(val original: LiveOffer, val scope: LiveOfferScope, val authorization: LiveSourceGrant,
        val version: ProductVersionEntity, val draft: LiveArticleReviewDraft, val review: LiveArticleReview,
        val effective: LiveOffer, val digest: String)
    private data class Pending(val challenge: Challenge, val basis: Basis)
    private data class Approved(val basis: Basis, val proof: OfferArticleProof, val confirmedAt: Long)
    private var pending: Pending? = null
    private val approved = mutableMapOf<String, Approved>()

    private fun confirmation(basis: Basis, confirmedAt: Long) = HostCatalogArticleRegistry.ConfirmedArticle(
        basis.version.id, basis.version.revision, basis.version.recordedAt, confirmedAt, basis.review.article, basis.review.organic)
    private fun basis(offer: LiveOffer, scope: LiveOfferScope, authorization: LiveSourceGrant,
        input: LiveArticleReviewDraft, versions: List<ProductVersionEntity>, at: Instant): Basis {
        require(sourcePermitted(scope, offer.sourceId, authorization)) { "Quelle oder Auswahl inzwischen geändert." }
        require(offer.fetchedAt <= at && (offer.sourceObservedAt == null || offer.sourceObservedAt <= at))
        require(at.atZone(ZoneId.of("Europe/Berlin")).toLocalDate() <= scope.purchaseOn) {
            "Neue Preiskenntnis darf keinen vergangenen Einkauf bewerten."
        }
        require(input.identityConfirmed && input.packageConfirmed && input.conditionsConfirmed) { "Artikel, Gebinde und Preisbedingungen ausdrücklich prüfen." }
        val label = input.foodTypeLabel.trim(); require(label.isNotBlank() && label.length <= 120) { "Produkttyp selbst benennen; keine Zuordnung aus dem Angebotsnamen." }
        require(input.attributes.keys.all { it in criticalAttributes })
        val attributes = input.attributes.mapValues { (_, value) -> value.trim().also { require(it.isNotEmpty() && it.length <= 500) } }.toSortedMap()
        val draft = input.copy(foodTypeLabel = label, attributes = Collections.unmodifiableMap(attributes))
        require(draft.validFrom <= draft.validUntil && scope.purchaseOn in draft.validFrom..draft.validUntil)
        require(offer.validFrom == null || draft.validFrom >= offer.validFrom) { "Bestätigung darf veröffentlichte Gültigkeit nicht erweitern." }
        require(offer.validUntil == null || draft.validUntil <= offer.validUntil) { "Bestätigung darf veröffentlichte Gültigkeit nicht erweitern." }
        val effective = offer.copy(validFrom = draft.validFrom, validUntil = draft.validUntil)
        require(effective.applicable(scope)) { "Filiale/Region oder Preisgültigkeit nicht passend." }
        val version = versions.singleOrNull { it.id == draft.productVersionId } ?: error("Produktfassung nicht mehr vorhanden.")
        val unit = when (version.baseUnit) { BaseUnit.GRAM -> "g"; BaseUnit.MILLILITRE -> "ml"; else -> error("Vergleich benötigt eine unabhängig bestätigte g/ml-Packungsbasis.") }
        if (offer.gtin != null) require(offer.gtin == version.barcode) { "Bekannte GTIN widerspricht der gewählten Produktfassung." }
        val article = ShoppingComparabilityKernel.Article("user-reviewed-type:" + digest(label.lowercase(Locale.ROOT)),
            draft.attributes, ShoppingUnitRateKernel.Quantity(draft.amountPerPack, unit, draft.basis, draft.packageCount, true),
            gtin = version.barcode, brandName = version.brand)
        val quantity = requireNotNull(ShoppingUnitRateKernel.confirmedQuantity(article.quantity))
        require(offer.unit == null || offer.unit.name == if (unit == "g") "GRAM" else "MILLILITRE")
        require(offer.quantityBasis == null || offer.quantityBasis.name == draft.basis)
        require(offer.quantityMilli == null || quantity.amount * java.math.BigInteger.valueOf(1000) == java.math.BigInteger.valueOf(offer.quantityMilli)) {
            "Bestätigtes Gebinde widerspricht der bekannten Quellenmenge."
        }
        val review = LiveArticleReview(version.id, article, draft.organic, true, true, true)
        val atMillis = at.toEpochMilli()
        val candidate = Basis(offer, scope, authorization.snapshot(), version, draft, review, effective,
            digest(listOf(offer.toString(), scope.toString(), authorization.toString(), version.id, version.productId,
                version.revision, version.recordedAt, version.baseUnit.name, version.packageAmount, version.barcode, version.brand,
                label, draft.amountPerPack, draft.packageCount, draft.basis, draft.organic, draft.validFrom.toString(),
                draft.validUntil.toString(), draft.attributes)))
        require(HostCatalogArticleRegistry(versions, mapOf(version.id to confirmation(candidate, atMillis)))
            .isConfirmed(version.id, article, draft.organic, at.atZone(ZoneId.of("Europe/Berlin")).toOffsetDateTime())) {
            "Aktuelle Produktfassung, Einheit oder Packungsmenge nicht unabhängig bestätigt."
        }
        for (other in approved.values.map { it.basis }) {
            if (other.review.article.foodTypeId == article.foodTypeId && other.version.id != version.id) {
                require(criticalAttributes.all { it in draft.attributes && it in other.draft.attributes }) {
                    "Für verschiedene Produktfassungen alle Variantenmerkmale auf beiden Seiten prüfen."
                }
            }
        }
        return candidate
    }
    @Synchronized fun prepare(offer: LiveOffer, scope: LiveOfferScope, authorization: LiveSourceGrant,
        draft: LiveArticleReviewDraft, versions: List<ProductVersionEntity>): Challenge {
        pending = null
        val value = basis(offer, scope, authorization, draft, versions, now())
        val challenge = Challenge(UUID.randomUUID().toString(), value.digest, UUID.randomUUID().toString(), elapsed())
        pending = Pending(challenge, value); return challenge
    }
    @Synchronized fun cancel() { pending = null }
    @Synchronized fun clear() { pending = null; approved.clear() }
    @Synchronized fun retainOnly(offerIds: Set<String>) {
        pending = null; approved.keys.retainAll(offerIds)
    }
    @Synchronized fun forget(offerId: String) { pending = null; approved.remove(offerId) }
    @Synchronized fun accept(challengeId: String, proof: OfferArticleProof, versions: List<ProductVersionEntity>) {
        val current = pending
        // A late callback from an old prompt must not consume a newer prompt.
        require(current != null && current.challenge.id == challengeId)
        pending = null // Consume this matching callback, including a failed proof.
        require(proof.challengeId == challengeId)
        require(current.challenge.digest == proof.digest && current.challenge.nonce == proof.nonce)
        val monotonic = elapsed(); require(monotonic >= current.challenge.issuedElapsed && monotonic - current.challenge.issuedElapsed <= 300_000)
        val at = now()
        require(verifier.verify(proof, at.toEpochMilli())) { "Gerätebestätigung nicht mehr gültig." }
        val latest = basis(current.basis.original, current.basis.scope, current.basis.authorization, current.basis.draft, versions, at)
        require(latest.digest == current.basis.digest && latest.version == current.basis.version) { "Produkt oder Vorschau geändert; erneut prüfen." }
        approved[latest.original.id] = Approved(latest, proof, at.toEpochMilli())
    }
    private fun ready(offer: LiveOffer, scope: LiveOfferScope, versions: List<ProductVersionEntity>, at: Instant): Approved? {
        val record = approved[offer.id] ?: return null
        if (record.basis.original != offer || record.basis.scope != scope || record.confirmedAt > at.toEpochMilli()) return null
        if (!verifier.verify(record.proof, record.confirmedAt)) return null
        val latest = runCatching { basis(offer, scope, record.basis.authorization, record.basis.draft, versions, at) }.getOrNull() ?: return null
        return record.takeIf { latest.digest == record.basis.digest && latest.version == record.basis.version }
    }
    @Synchronized fun reviewed(offer: LiveOffer, scope: LiveOfferScope, versions: List<ProductVersionEntity>): LiveArticleReview? =
        ready(offer, scope, versions, now())?.basis?.review
    @Synchronized fun compare(target: LiveOffer, targetScope: LiveOfferScope, alternative: LiveOffer,
        alternativeScope: LiveOfferScope, versions: List<ProductVersionEntity>): LivePriceComparison {
        val at = now()
        val left = ready(target, targetScope, versions, at) ?: return LivePriceComparison(null, "Referenzartikel neu unabhängig prüfen und bestätigen.")
        val right = ready(alternative, alternativeScope, versions, at) ?: return LivePriceComparison(null, "Alternativartikel neu unabhängig prüfen und bestätigen.")
        val records = listOf(left, right)
        val comparison = LiveOfferComparison(
            LiveArticleReviewRegistry { offer, scope, _ -> records.singleOrNull { it.basis.effective == offer && it.basis.scope == scope }?.basis?.review },
            ShoppingPriceComparisonGate.ConfirmedArticleRegistry { id, article, organic, cutoff -> records.any { record ->
                record.basis.version.id == id && record.basis.review.article == article && record.basis.review.organic == organic &&
                    HostCatalogArticleRegistry(versions, mapOf(id to confirmation(record.basis, record.confirmedAt)))
                        .isConfirmed(id, article, organic, cutoff)
            } },
            sourceStillPermitted = { scope, offer -> records.any { it.basis.effective == offer && it.basis.scope == scope &&
                sourcePermitted(scope, offer.sourceId, it.basis.authorization) } })
        // Effective dates are independently signed host facts. The original
        // supplier observations are retained unchanged and never persisted.
        return comparison.compare(left.basis.effective, targetScope, right.basis.effective, alternativeScope, at)
    }
    @Synchronized fun previewNeed(offer: LiveOffer, scope: LiveOfferScope, need: ShoppingNeed,
        versions: List<ProductVersionEntity>): PackCalculation? {
        val record = ready(offer, scope, versions, now()) ?: return null
        if (need.versionId != record.basis.version.id || need.unit != record.basis.version.baseUnit || need.knownMissing <= 0) return null
        return runCatching {
            val amount = Math.multiplyExact(Math.multiplyExact(record.basis.draft.amountPerPack, record.basis.draft.packageCount), 1000)
            ShoppingMath.packs(need.knownMissing, amount, offer.merchandiseCents)
        }.getOrNull()
    }
}
