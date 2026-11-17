package de.ernaehrung.demo.data.offers

import java.net.URI
import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Public /angebote HTML observed 17 Nov 2026. This is a NEW source format,
 * separate from the pinned walk-in search API. Its Nuxt price integers are
 * CENTS (179 corroborated by rendered/display "1,79 €"), not that API's euro
 * values. Read flat indexed JSON and visible product tiles only; execute no JS,
 * hydrate no graph and fetch no scripts/images, cookies or private context. */
internal object AldiSuedPublicOfferDecoder {
    private fun invalid(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
    fun decode(html: String, sourceId: String, fetchedAt: Instant, sourceUrl: String): List<LiveOffer> {
        require(sourceUrl == "https://www.aldi-sued.de/angebote")
        if (html.length > 2_097_152) invalid()
        val document = Jsoup.parse(html)
        if (document.getAllElements().size > 50_000) invalid()
        val scripts = document.select("script[id=__NUXT_DATA__][type=application/json]")
        if (scripts.size != 1) invalid()
        val table = OfferJson.read("{\"table\":" + scripts.single().data() + "}")["table"] as? List<*> ?: invalid()
        if (table.isEmpty() || table.size > 20_000) invalid()
        fun ref(index: Any?): Any? {
            val number = OfferJson.integer(index) ?: invalid()
            if (number == -1L) return null // Nuxt/devalue's explicit undefined sentinel.
            if (number !in 0 until table.size.toLong()) invalid()
            return table[number.toInt()]
        }
        fun field(row: Map<String, Any?>, key: String): Any? = if (key in row) ref(row[key]) else null
        val candidates = table.mapNotNull(OfferJson::objectValue).filter { it.keys.containsAll(setOf("sku", "name", "price")) }
        if (candidates.size > 512) invalid()
        val tiles = document.select(".product-teaser-list .product-tile")
        if (tiles.size > 256) invalid()
        val result = linkedMapOf<String, LiveOffer>()
        for (tile in tiles) {
            val links = tile.select("a.product-tile__link[href]")
            if (links.size != 1) invalid()
            val path = links.single().attr("href")
            if (!path.startsWith("/produkt/") || path.startsWith("//")) invalid()
            val page = URI("https://www.aldi-sued.de").resolve(path)
            if (page.host != "www.aldi-sued.de" || page.scheme != "https" || page.rawUserInfo != null || page.fragment != null || page.rawQuery != null) invalid()
            val name = OfferJson.text(tile.selectFirst(".product-tile__name")?.text()) ?: invalid()
            val matches = candidates.filter { row ->
                val sku = OfferJson.text(field(row, "sku"))
                sku != null && sku.matches(Regex("[A-Za-z0-9-]{1,80}")) && path.endsWith("-$sku") &&
                    OfferJson.text(field(row, "name")) == name
            }
            if (matches.size != 1) invalid() // No name-only match or arbitrary graph scan result.
            val row = matches.single()
            // Dropshipping/abstract records are not independently selected
            // shelf variants. notForSale is ONLINE status here, not evidence
            // that a national in-store advertisement is unavailable locally.
            if (field(row, "isDropship") == true || field(row, "isAbstract") == true) continue
            val sku = requireNotNull(OfferJson.text(field(row, "sku")))
            val price = OfferJson.objectValue(field(row, "price")) ?: invalid()
            val euro = field(price, "currencyCode") == "EUR"
            val number = field(price, if ("amountRelevant" in price) "amountRelevant" else "amount")
            val cents = if (euro && number != null) OfferJson.integer(number)?.takeIf { it in 0L..1_000_000_000_000L } ?: invalid() else null
            val display = OfferJson.text(field(price, "amountRelevantDisplay"))
            if (cents != null && (visibleCents(tile) != cents || (display != null && OfferJson.euroCents(display) != cents))) invalid()
            val depositNumber = field(price, "bottleDeposit")
            val deposit = if (euro && depositNumber != null) OfferJson.integer(depositNumber)?.takeIf { it in 0L..1_000_000_000_000L } ?: invalid() else null
            val depositDisplay = OfferJson.text(field(price, "bottleDepositDisplay"))
            if (deposit != null && depositDisplay != null && OfferJson.euroCents(depositDisplay) != deposit) invalid()
            val offer = LiveOffer("$sourceId:$sku", sourceId, LiveRetailer.ALDI_SUED, name, sku, null, cents, deposit,
                null, null, null, LiveOfferKind.UNKNOWN, null, null, null, fetchedAt, null, null, null,
                LiveOfferApplicability.CHAIN_REPORTED,
                "Öffentliche Angebotsseite; konkrete Filiale, Verfügbarkeit und Artikelgültigkeit ungeprüft. Keine Onlinebestellung.",
                false, sourceUrl, OfferJson.text(field(row, "sellingSize")),
                LivePriceDetails(brand = OfferJson.text(field(row, "brandName")),
                    previousDisplay = OfferJson.text(field(price, "wasPriceDisplay")),
                    unitPriceDisplay = OfferJson.text(field(price, "comparisonDisplay"))), productPageUrl = page.toString())
            if (sku in result && result[sku] != offer) invalid()
            result[sku] = offer
        }
        return result.values.toList()
    }
    private fun visibleCents(tile: Element): Long? {
        val discounted = tile.select("ins.base-price__discounted").filter { it.text().isNotBlank() }
        val prices = if (discounted.isNotEmpty()) discounted else tile.select("span.base-price__regular").filter { it.text().isNotBlank() }
        if (prices.size != 1) invalid()
        // The source renders its footnote marker inside the price span. Remove
        // only identified DOM footnote elements, never digits from a price.
        val price = prices.single().clone()
        price.select(".base-price__superscript").remove()
        return OfferJson.euroCents(price.text())
    }
}
