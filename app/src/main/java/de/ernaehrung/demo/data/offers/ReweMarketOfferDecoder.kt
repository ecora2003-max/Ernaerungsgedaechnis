package de.ernaehrung.demo.data.offers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/** Rendered REWE top offers. Market identity and the national publication
 * are verified independently before reading the offer cards. */
internal object ReweMarketOfferDecoder {
    const val NATIONAL_ENDPOINT = "https://www.rewe.de/angebote/nationale-angebote/topangebote/"
    private val marketId = Regex("[0-9]{4,12}")
    private val slug = Regex("[a-z0-9-]+")
    private val offerId = Regex("[a-f0-9]{16}")
    private val nan = Regex("[0-9]{1,16}")
    private val deposit = Regex("zzgl\\.\\s*([0-9]{1,4},[0-9]{2})\\s*€\\s*Pfand", RegexOption.IGNORE_CASE)
    private val weekLabel = Regex("([0-9]{1,2})\\.([0-9]{1,2})\\. bis ([0-9]{1,2})\\.([0-9]{1,2})\\.")
    private val conditionalPrice = Regex("\\b(?:REWE Bonus|Coupon|Payback)\\b|\\bnur mit (?:der )?(?:REWE[- ])?App\\b",
        RegexOption.IGNORE_CASE)
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
        val document = parse(html)
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
        val proof = LiveStoreScopeEvidence(storeId, name, "$street, $postal $locality", null, monday, endpoint.toString(), at)
        val top = document.select("#sos-categories-current section[id=markt-topangebote-current-week]").singleOrNull() ?: invalid()
        val cards = top.select("[data-offer-id][data-offer-nan][data-offer-wwident][data-offer-week]")
        if (cards.size > 256) invalid()
        return cards.mapNotNull { card -> readCard(card, sourceId, endpoint, at, monday, proof) }
            .also { if (it.distinctBy(LiveOffer::id).size != it.size) invalid() }
    }

    fun decodeNational(html: String, sourceId: String, scope: LiveOfferScope, endpoint: URI, at: Instant): List<LiveOffer> {
        require(endpoint.toString() == NATIONAL_ENDPOINT && scope == LiveOfferScope(LiveRetailer.REWE,
            null, null, null, scope.purchaseOn))
        val document = parse(html)
        if (document.select("link[rel=canonical]").singleOrNull()?.attr("href") != NATIONAL_ENDPOINT ||
            document.select("[data-offer-details-deeplink-ww-ident-value]")
                .any { it.attr("data-offer-details-deeplink-ww-ident-value").isNotEmpty() }) unavailable()
        val monday = weekStart(at.atZone(zone).toLocalDate())
        val sunday = monday.plusDays(6)
        if (scope.purchaseOn !in monday..sunday) unavailable()
        val label = document.select("#sos-week-tabs__tab-0[aria-selected=true] .sos-tabs__tab-subtitle").singleOrNull()?.text()
        val dateParts = label?.let(weekLabel::matchEntire)?.groupValues?.drop(1)?.map(String::toInt)
        if (dateParts != listOf(monday.dayOfMonth, monday.monthValue, sunday.dayOfMonth, sunday.monthValue)) unavailable()
        val section = document.select("[data-tab-id=sos-week-tabs__tab-0] #sos-categories-current > section#topangebote-current-week")
            .singleOrNull() ?: invalid()
        val cards = section.select("[data-offer-id][data-offer-nan][data-category=topangebote][data-offer-week]")
        if (cards.isEmpty() || cards.size > 256) invalid()
        return cards.mapNotNull { readCard(it, sourceId, endpoint, at, monday, null) }
            .also { if (it.isEmpty() || it.distinctBy(LiveOffer::id).size != it.size) invalid() }
    }

    private fun parse(html: String): Document {
        if (html.length > 2_097_152) invalid()
        return Jsoup.parse(html).also { if (it.getAllElements().size > 50_000) invalid() }
    }

    private fun readCard(card: Element, sourceId: String, endpoint: URI, at: Instant, monday: LocalDate,
        proof: LiveStoreScopeEvidence?): LiveOffer? {
        val storeId = proof?.marketId.orEmpty()
        val week = "${monday.get(IsoFields.WEEK_BASED_YEAR)}/${monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0')}"
        if (card.attr("data-offer-wwident") != storeId || card.attr("data-offer-week") != week) return null
        val tile = card.select("article.cor-offer-renderer-tile").singleOrNull() ?: return null
        if (proof == null && conditionalPrice.containsMatchIn(tile.text())) return null
        val titleLink = tile.select(if (proof != null) "[data-offer-title][data-offer-wwident]" else "[data-offer-title]")
            .singleOrNull() ?: return null
        val title = OfferJson.text(titleLink.text()) ?: return null
        if (titleLink.attr("data-offer-title") != title || titleLink.attr("data-offer-wwident") != storeId) return null
        val id = card.attr("data-offer-id").takeIf(offerId::matches) ?: return null
        val product = card.attr("data-offer-nan").takeIf(nan::matches) ?: return null
        val priceText: String
        val crossedOut: String?
        val arrow = tile.select(".cor-offer-price-arrow")
        if (proof == null && arrow.size == 1 && tile.select(".cor-offer-price__tag").isEmpty()) {
            priceText = arrow.single().select(".cor-offer-price-arrow__new").singleOrNull()?.text() ?: return null
            crossedOut = arrow.single().select(".cor-offer-price-arrow__old s").singleOrNull()?.text()
            if (OfferJson.euroCents(crossedOut) == null) return null
        } else {
            val price = tile.select(".cor-offer-price__tag").singleOrNull() ?: return null
            val label = price.select(".cor-offer-price__tag-label").singleOrNull() ?: return null
            crossedOut = if (proof == null) label.select("s").singleOrNull()?.text() else null
            if (label.text() != "Aktion" &&
                (label.text().trimEnd('*', ' ') != crossedOut || OfferJson.euroCents(crossedOut) == null)) return null
            priceText = price.select(".cor-offer-price__tag-price").singleOrNull()?.text() ?: return null
        }
        val cents = OfferJson.euroCents(priceText) ?: return null
        val additions = tile.select(".cor-offer-information__additional").mapNotNull {
            OfferJson.text(it.text().trimStart(',', ' '))
        }
        val depositCents = additions.mapNotNull { deposit.matchEntire(it)?.groupValues?.get(1) }
            .singleOrNull()?.let(OfferJson::euroCents)
        val unitPrice = additions.singleOrNull { it.startsWith("(1 ") && it.contains('=') && it.endsWith(')') }
        val rateLabel = additions.firstOrNull(::publicQuantityRate)
        val rateText = rateLabel?.let { "$priceText $it" } ?: unitPrice
        val lowestThirtyDays = if (proof == null) tile.select(".cor-offer-information__cross-out-legal-text")
            .singleOrNull()?.text()?.trimStart('*', ' ')?.let(OfferJson::text)
            ?.takeIf { it.startsWith("Niedrigster Preis der letzten 30 Tage:") } else null
        val comparison = listOfNotNull(crossedOut?.let { "Statt $it" }, lowestThirtyDays).joinToString(" · ")
        return LiveOffer("$sourceId:$id", sourceId, LiveRetailer.REWE, title, product, null,
            cents.takeIf { rateLabel == null }, depositCents.takeIf { rateLabel == null },
            null, null, null, LiveOfferKind.PROMOTION, null, null, null, at, proof?.marketId, null, null,
            if (proof == null) LiveOfferApplicability.CHAIN_REPORTED else LiveOfferApplicability.STORE_CONFIRMED,
            if (proof == null) "Veröffentlichte Angebotswoche $monday bis ${monday.plusDays(6)}" else null,
            false, endpoint.toString(), OfferJson.text(additions.joinToString(" · ")),
            LivePriceDetails(previousDisplay = OfferJson.text(comparison), unitPriceDisplay = rateText), proof)
    }

    private fun weekStart(date: LocalDate) = date.minusDays((date.dayOfWeek.value - 1).toLong())
    private fun invalid(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
    private fun unavailable(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)
}
