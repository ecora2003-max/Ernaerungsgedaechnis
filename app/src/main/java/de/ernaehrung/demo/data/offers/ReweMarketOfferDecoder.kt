package de.ernaehrung.demo.data.offers

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/** Reads the rendered Top-Angebote section of an official REWE market page.
 * The rest of the document includes lazy placeholders and loyalty modules. */
internal object ReweMarketOfferDecoder {
    private val marketId = Regex("[0-9]{4,12}")
    private val slug = Regex("[a-z0-9-]+")
    private val offerId = Regex("[a-f0-9]{16}")
    private val nan = Regex("[0-9]{1,16}")
    private val deposit = Regex("zzgl\\.\\s*([0-9]{1,4},[0-9]{2})\\s*€\\s*Pfand", RegexOption.IGNORE_CASE)
    private val zone = ZoneId.of("Europe/Berlin")

    fun requireEndpoint(endpoint: URI, storeId: String) {
        requirePublicHttps(endpoint)
        val path = endpoint.rawPath.split('/')
        require(endpoint.host == "www.rewe.de" && endpoint.rawQuery == null && endpoint.rawPath.endsWith('/') &&
            path.size == 6 && path[1] == "angebote" && slug.matches(path[2]) && path[3] == storeId &&
            marketId.matches(storeId) && slug.matches(path[4]))
    }

    fun decode(html: String, sourceId: String, scope: LiveOfferScope, endpoint: URI, at: Instant): List<LiveOffer> {
        val storeId = requireNotNull(scope.storeId)
        requireEndpoint(endpoint, storeId)
        if (html.length > 2_097_152) invalid()
        val document = Jsoup.parse(html)
        if (document.getAllElements().size > 50_000) invalid()
        val canonical = document.select("link[rel=canonical]").singleOrNull()?.attr("href")
        val markedStore = document.select("[data-offer-details-deeplink-ww-ident-value]")
            .map { it.attr("data-offer-details-deeplink-ww-ident-value") }.distinct().singleOrNull()
        if (canonical != endpoint.toString() || markedStore != storeId) unavailable()
        val store = document.select("script[type=application/ld+json]").mapNotNull {
            runCatching { OfferJson.read(it.data()) }.getOrNull()?.takeIf { entry -> entry["@type"] == "GroceryStore" }
        }.singleOrNull() ?: invalid()
        val name = OfferJson.text(store["name"]) ?: invalid()
        val address = OfferJson.objectValue(store["address"]) ?: invalid()
        val street = OfferJson.text(address["streetAddress"]) ?: invalid()
        val postal = OfferJson.text(address["postalCode"]) ?: invalid()
        val locality = OfferJson.text(address["addressLocality"]) ?: invalid()
        val monday = weekStart(at.atZone(zone).toLocalDate())
        if (scope.purchaseOn !in monday..monday.plusDays(6)) unavailable()
        val weekCode = "${monday.get(IsoFields.WEEK_BASED_YEAR)}/${monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0')}"
        val proof = LiveStoreScopeEvidence(storeId, name, "$street, $postal $locality", null, monday, endpoint.toString(), at)
        val top = document.select("#sos-categories-current section[id=markt-topangebote-current-week]").singleOrNull() ?: invalid()
        val cards = top.select("[data-offer-id][data-offer-nan][data-offer-wwident][data-offer-week]")
        if (cards.size > 256) invalid()
        return cards.mapNotNull { card -> readCard(card, sourceId, endpoint, at, storeId, weekCode, proof) }
            .also { if (it.distinctBy(LiveOffer::id).size != it.size) invalid() }
    }

    private fun readCard(card: Element, sourceId: String, endpoint: URI, at: Instant, storeId: String,
        week: String, proof: LiveStoreScopeEvidence): LiveOffer? {
        if (card.attr("data-offer-wwident") != storeId || card.attr("data-offer-week") != week) return null
        val tile = card.select("article.cor-offer-renderer-tile").singleOrNull() ?: return null
        val titleLink = tile.select("[data-offer-title][data-offer-wwident]").singleOrNull() ?: return null
        val title = OfferJson.text(titleLink.text()) ?: return null
        if (titleLink.attr("data-offer-title") != title || titleLink.attr("data-offer-wwident") != storeId) return null
        val id = card.attr("data-offer-id").takeIf(offerId::matches) ?: return null
        val product = card.attr("data-offer-nan").takeIf(nan::matches) ?: return null
        val price = tile.select(".cor-offer-price__tag").singleOrNull() ?: return null
        if (price.select(".cor-offer-price__tag-label").singleOrNull()?.text() != "Aktion") return null
        val cents = OfferJson.euroCents(price.select(".cor-offer-price__tag-price").singleOrNull()?.text()) ?: return null
        val additions = tile.select(".cor-offer-information__additional").mapNotNull {
            OfferJson.text(it.text().trimStart(',', ' '))
        }
        val depositCents = additions.mapNotNull { deposit.matchEntire(it)?.groupValues?.get(1) }
            .singleOrNull()?.let(OfferJson::euroCents)
        val unitPrice = additions.singleOrNull { it.startsWith("(1 ") && it.contains('=') && it.endsWith(')') }
        val rateLabel = additions.firstOrNull(::publicQuantityRate)
        val rateText = rateLabel?.let { "${price.selectFirst(".cor-offer-price__tag-price")?.text()} $it" } ?: unitPrice
        return LiveOffer("$sourceId:$id", sourceId, LiveRetailer.REWE, title, product, null,
            cents.takeIf { rateLabel == null }, depositCents.takeIf { rateLabel == null },
            null, null, null, LiveOfferKind.PROMOTION, null, null, null, at, storeId, null, null,
            LiveOfferApplicability.STORE_CONFIRMED, null, false, endpoint.toString(),
            OfferJson.text(additions.joinToString(" · ")), LivePriceDetails(unitPriceDisplay = rateText), proof)
    }

    private fun weekStart(date: LocalDate) = date.minusDays((date.dayOfWeek.value - 1).toLong())
    private fun invalid(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
    private fun unavailable(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)
}
