package de.ernaehrung.demo.data.offers

import de.ernaehrung.demo.data.integration.*
import java.math.BigInteger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Independent host article/store/condition reviews. Never populated by an
 * HTTP reply, name similarity, barcode alone or a source's PERMITTED flag. */
internal data class LiveArticleReview(val productVersionId: String, val article: ShoppingComparabilityKernel.Article,
    val organic: Boolean?, val conditionsConfirmed: Boolean, val scopeConfirmed: Boolean,
    val offerPackageConfirmed: Boolean = false)
internal fun interface LiveArticleReviewRegistry {
    fun review(offer: LiveOffer, scope: LiveOfferScope, asOf: Instant): LiveArticleReview?
}
internal data class LivePriceComparison(val rateOrder: Int?, val reason: String?,
    val targetRate: ShoppingUnitRateKernel.Rate? = null, val alternativeRate: ShoppingUnitRateKernel.Rate? = null) {
    val marketWideBestPriceProven: Boolean get() = false
}

internal fun formatReviewedOfferRate(rate: ShoppingUnitRateKernel.Rate): String =
    BigDecimal(rate.numerator).divide(BigDecimal(rate.denominator) * BigDecimal(100), 2, RoundingMode.HALF_UP)
        .toPlainString().replace('.', ',') + " €/${if (rate.displayBasis == "piece") "Stück" else rate.displayBasis} · ${rate.quantityBasis}"

/** Read-only bridge to the existing host catalog, comparability and exact-rate
 * kernels, including delivery scopes without inventing a stationary v1 enum.
 * It compares merchandise rates only; unknown deposits/shipping stay unknown. */
internal class LiveOfferComparison(
    private val reviews: LiveArticleReviewRegistry = LiveArticleReviewRegistry { _, _, _ -> null },
    private val articles: ShoppingPriceComparisonGate.ConfirmedArticleRegistry = ShoppingPriceComparisonGate.denyAllArticles,
    private val sourceStillPermitted: (LiveOfferScope, LiveOffer) -> Boolean = { _, _ -> false }) {
    fun compare(target: LiveOffer, targetScope: LiveOfferScope, alternative: LiveOffer, alternativeScope: LiveOfferScope,
        asOf: Instant): LivePriceComparison {
        fun denied(reason: String) = LivePriceComparison(null, reason)
        if (targetScope.purchaseOn != alternativeScope.purchaseOn) return denied("Verschiedene Einkaufstage")
        if (listOf(target.id, alternative.id).distinct().size != 2) return denied("Zwei verschiedene Angebotsbeobachtungen wählen")
        val cutoff = asOf.atZone(ZoneId.of("Europe/Berlin")).toOffsetDateTime()
        if (cutoff.toLocalDate() > targetScope.purchaseOn) return denied("Neue Preiskenntnis darf keinen vergangenen Einkauf bewerten")
        val checked = listOf(target to targetScope, alternative to alternativeScope).map { (offer, scope) ->
            if (!offer.applicable(scope)) return denied("Filial-/Liefergebietsbezug oder Gültigkeit ungeklärt")
            if (offer.fetchedAt > asOf || offer.sourceObservedAt?.let { it > asOf } == true)
                return denied("Preisbeobachtung liegt nach dem Vergleichsstand")
            val sourceOkay = try { sourceStillPermitted(scope, offer) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (!sourceOkay) return denied("Quellfreigabe nicht mehr bestätigt")
            val review = try { reviews.review(offer, scope, asOf) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                ?: return denied("Unabhängige Artikel-/Produktversionszuordnung fehlt")
            if (!review.conditionsConfirmed || !review.scopeConfirmed || !review.offerPackageConfirmed)
                return denied("Preisbedingungen, Angebotsgebinde oder genaue Zuordnung ungeprüft")
            val confirmed = try { articles.isConfirmed(review.productVersionId, review.article, review.organic, cutoff) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (!confirmed) return denied("Historische Produktfassung oder Packungsbasis ungeklärt")
            val quantity = try { ShoppingUnitRateKernel.confirmedQuantity(review.article.quantity) } catch (_: Exception) { null }
                ?: return denied("Bestätigte Menge fehlt")
            val expectedUnit = when (offer.unit) { OfferMassUnit.GRAM -> "g"; OfferMassUnit.MILLILITRE -> "ml"; else -> null }
            if ((expectedUnit != null && quantity.unit != expectedUnit) ||
                (offer.quantityBasis != null && quantity.quantityBasis != offer.quantityBasis.name) ||
                (offer.quantityMilli != null && quantity.amount * BigInteger.valueOf(1000) != BigInteger.valueOf(offer.quantityMilli)))
                return denied("Angebotsmenge stimmt nicht mit geprüfter Artikelmenge überein")
            val rate = try { ShoppingUnitRateKernel.rate(offer.merchandiseCents, "EUR", review.article.quantity) } catch (_: Exception) { null }
                ?: return denied("Warenpreis oder vergleichbare Menge unbekannt")
            review to rate
        }
        val match = ShoppingComparabilityKernel.compare(
            ShoppingComparabilityKernel.Observation(target.id, checked[0].first.article),
            ShoppingComparabilityKernel.Observation(alternative.id, checked[1].first.article), emptyList())
        if (match.level !in setOf("EXACT_SKU", "SAME_FOOD_VARIANT")) return denied("Artikel nicht unabhängig vergleichbar; keine automatische Ersatzproduktfreigabe")
        val order = ShoppingUnitRateKernel.compare(checked[0].second, checked[1].second)
            ?: return denied("Einheit oder NET-/DRAINED-/GROSS-Basis verschieden")
        return LivePriceComparison(order, null, checked[0].second, checked[1].second)
    }
}
