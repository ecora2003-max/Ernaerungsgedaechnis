package de.ernaehrung.demo.data.offers

import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Pinned catalogues/netto_branch_offers.py blob ef69a4f at EG-Preisdaten@5c52bf7.
 * Parse the already supplied branch-offer HTML, never load a link/resource.
 * Wishlist links may MUTATE state; their values only corroborate the visible
 * card. Neither that link's timestamp nor the app price becomes a shelf price. */
internal object NettoBranchOfferDecoder {
    private val localTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private fun visible(tag: Element?): Long? {
        val s = tag?.wholeText()?.replace(Regex("[\\s\\u00a0*]"), "") ?: return null
        val plain = when {
            s.matches(Regex("[0-9]{1,4}[.,][–—-]")) -> s.dropLast(1) + "00"
            s.matches(Regex("[0-9]{1,4}[.,][0-9]{2}")) -> s
            else -> return null
        }
        return OfferJson.euroCents(plain)
    }
    private fun dates(label: String?): List<LocalDate?> {
        if (label == null) return emptyList()
        return Regex("(?<![0-9])([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{4}|[0-9]{2})(?![0-9])")
            .findAll(label).map { m -> runCatching {
                val year = m.groupValues[3].toInt() + if (m.groupValues[3].length == 2) 2000 else 0
                LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
            }.getOrNull() }.toList()
    }
    private fun query(uri: URI): Map<String, String>? = runCatching {
        val pairs = uri.rawQuery.orEmpty().split('&').map { part ->
            val bits = part.split('=', limit = 2)
            require(bits.size == 2)
            URLDecoder.decode(bits[0], "UTF-8") to URLDecoder.decode(bits[1], "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        pairs.toMap()
    }.getOrNull()

    fun requireStoreEndpoint(endpoint: URI, storeId: String) {
        requirePublicHttps(endpoint)
        require(endpoint.host == "www.netto-online.de" && endpoint.rawQuery == null &&
            storeId.matches(Regex("[0-9]{1,10}")) &&
            endpoint.rawPath.matches(Regex("/filialen/[a-z0-9-]+/[a-z0-9-]+/$storeId")))
    }

    /** The canonical branch page renders its own tiles and a dated header per
     * theme. The older category reader has a different card and scope format. */
    fun decodeStore(html: String, sourceId: String, scope: LiveOfferScope, endpoint: URI, at: Instant): List<LiveOffer> {
        val storeId = requireNotNull(scope.storeId)
        requireStoreEndpoint(endpoint, storeId)
        if (html.length > 1_048_576) invalid()
        val document = Jsoup.parse(html)
        if (document.getAllElements().size > 50_000) invalid()
        if (document.select("link[rel=canonical]").singleOrNull()?.attr("href") != endpoint.toString() ||
            document.select(".js-select-button[data-store-id]").singleOrNull()?.attr("data-store-id") != storeId)
            unavailable()
        val store = document.select("script[type=application/ld+json]").mapNotNull {
            runCatching { OfferJson.read(it.data()) }.getOrNull()?.takeIf { entry -> entry["@type"] == "GroceryStore" }
        }.singleOrNull() ?: invalid()
        if (store["url"] != endpoint.toString()) unavailable()
        val name = OfferJson.text(store["name"]) ?: invalid()
        val address = OfferJson.objectValue(store["address"]) ?: invalid()
        val street = OfferJson.text(address["streetAddress"]) ?: invalid()
        val postal = OfferJson.text(address["postalCode"]) ?: invalid()
        val place = OfferJson.text(address["addressLocality"]) ?: invalid()
        if (OfferJson.objectValue(address["addressCountry"])?.get("name") != "DE" ||
            document.select(".site-footer__box__star-text").none { "Alle Preise in Euro" in it.text() }) invalid()

        val proof = LiveStoreScopeEvidence(storeId, name, "$street, $postal $place", null, null, endpoint.toString(), at)
        val section = document.select("section.store-offers").singleOrNull() ?: invalid()
        if (section.select(".store-offers__header__headline").singleOrNull()?.text() != "Filial-Angebote") invalid()
        val themes = section.select(".js-store-offers-item[data-theme][data-slider-headline]")
            .groupBy { it.attr("data-theme") }
        val datedSlides = section.select(".js-store-offers-slides[data-theme]").groupBy { it.attr("data-theme") }
            .mapNotNull { (id, slides) ->
                val date = themes[id]?.singleOrNull()?.attr("data-slider-headline")?.trim()
                    ?.let(GermanOfferWindow::read) ?: return@mapNotNull null
                slides.singleOrNull()?.takeIf { scope.purchaseOn in date }?.let { it to date }
            }
        if (datedSlides.isEmpty()) unavailable()
        val cards = datedSlides.flatMap { (slide, date) -> slide.select("li.js-store-product-tile").map { it to date } }
        if (cards.size !in 1..256) invalid()
        return cards.mapNotNull { (tile, date) -> branchCard(tile, sourceId, proof, date) }
            .groupBy(LiveOffer::id).values.mapNotNull { it.distinct().singleOrNull() }
    }

    private val skuPattern = Regex("[0-9]{1,24}")
    private val depositPattern = Regex("zzgl\\.\\s*Pfand\\s+([0-9]{1,4}[,.][0-9]{2})(?![,.0-9])", RegexOption.IGNORE_CASE)
    private val unitPrice = Regex("(?<![,.0-9])[0-9]{1,4}[,.][0-9]{2}(?:\\s*[-–]\\s*[0-9]{1,4}[,.][0-9]{2})?\\s*/\\s*(?:kg|l)\\b")

    private fun branchCard(tile: Element, sourceId: String, proof: LiveStoreScopeEvidence,
        window: ClosedRange<LocalDate>): LiveOffer? {
        val title = OfferJson.text(tile.select(".tc-product-name").singleOrNull()?.text()) ?: return null
        val link = tile.select("a.js-add-to-wishlist[href]").singleOrNull()?.attr("href") ?: return null
        // These are inert attributes in the received document. No request is made to the wishlist endpoint.
        val uri = runCatching { URI(link).also(::requirePublicHttps) }.getOrNull() ?: return null
        if (uri.host != "www.netto-online.de" ||
            uri.rawPath != "/INTERSHOP/web/WFS/Plus-NettoDE-Site/de_DE/-/EUR/ViewMMPWishlist-AddStoreArticle") return null
        val values = query(uri) ?: return null
        if (values["Name"] != title || values["fromStoreProductTile"] != "true") return null
        val sku = values["SKU"]?.takeIf(skuPattern::matches) ?: return null

        val general = tile.select(".product__price__label > ins.tc-product-price").singleOrNull()
        val app = tile.select(".grid-app-price > ins.tc-product-price").singleOrNull()
        val prices = tile.select("ins.tc-product-price")
        if (prices.any { it != general && it != app }) return null
        val goodsCents = visible(general)
        val appCents = visible(app)
        if (goodsCents == null && appCents == null) return null
        val packageText = OfferJson.text(tile.select(".product__base-price").singleOrNull()?.text())
        val deposit = packageText?.let { depositPattern.findAll(it).singleOrNull()?.groupValues?.get(1) }?.let(OfferJson::euroCents)
        val unitText = packageText?.let { unitPrice.findAll(it).singleOrNull()?.value }
        val previous = OfferJson.text(general?.parent()?.select("del")?.singleOrNull()?.text())
        return LiveOffer("$sourceId:$sku", sourceId, LiveRetailer.NETTO_MD, title, sku, null,
            goodsCents, deposit, null, null, null, LiveOfferKind.PROMOTION, window.start, window.endInclusive, null,
            proof.checkedAt, proof.marketId, null, null, LiveOfferApplicability.STORE_CONFIRMED,
            null, false, proof.scopeSourceUrl, packageText,
            LivePriceDetails(appCents = appCents, previousDisplay = previous, unitPriceDisplay = unitText),
            storeEvidence = proof, productPageUrl = proof.scopeSourceUrl)
    }

    private fun invalid(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
    private fun unavailable(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)

    fun decode(html: String, sourceId: String, at: Instant, url: String): List<LiveOffer> {
        val source = URI(url); requirePublicHttps(source)
        require(source.host == "www.netto-online.de" && html.length <= 1_048_576)
        val document = Jsoup.parse(html)
        require(document.getAllElements().size <= 50_000)
        val heading = document.select("h2").firstOrNull { it.text().trim() == "Filial-Angebote" }
            ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
        val root = heading.parents().firstOrNull { it.selectFirst(".tc-store-product-tile") != null }
            ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
        val dateList = dates(root.selectFirst(".store-offers__header__date")?.text())
        val from = dateList.firstOrNull()
        // Only an explicit second DATE in the page header establishes an end
        // date. The wishlist's local timestamp has an unverified meaning.
        val until = dateList.getOrNull(1)?.takeIf { from != null && it >= from }
        val tiles = root.select(".tc-store-product-tile"); require(tiles.size <= 256)
        return tiles.mapNotNull { tile ->
            val id = OfferJson.text(tile.attr("data-ff-id")) ?: return@mapNotNull null
            val name = OfferJson.text(tile.selectFirst(".tc-product-name")?.text()) ?: return@mapNotNull null
            val link = tile.select("a[href]").firstOrNull { it.attr("href").contains("ViewMMPWishlist-AddStoreArticle") }
                ?: return@mapNotNull null
            val uri = runCatching { URI(link.attr("href")) }.getOrNull() ?: return@mapNotNull null
            if (runCatching { requirePublicHttps(uri) }.isFailure || uri.host != source.host ||
                !uri.path.endsWith("/ViewMMPWishlist-AddStoreArticle")) return@mapNotNull null
            val q = query(uri) ?: return@mapNotNull null
            val price = OfferJson.euroCents(q["Price"]) ?: return@mapNotNull null
            val shown = visible(tile.selectFirst(".grid-top-price .product__current-price.tc-product-price"))
            if (q["SKU"] != id || q["Name"] != name || shown != price) return@mapNotNull null
            val wishlistTime = q["ValidTo"]?.takeIf { runCatching { LocalDateTime.parse(it, localTime) }.isSuccess }
                ?: return@mapNotNull null
            val appPrice = visible(tile.selectFirst(".grid-app-price .product__current-price.tc-product-price"))
            LiveOffer("$sourceId:$id", sourceId, LiveRetailer.NETTO_MD, name, id, null,
                price, null, null, null, null, LiveOfferKind.PROMOTION, from, until, null, at,
                null, null, null, LiveOfferApplicability.CHAIN_REPORTED, null, false, url,
                priceDetails = LivePriceDetails(appCents = appPrice, wishlistUntilLocal = wishlistTime,
                    unitPriceDisplay = OfferJson.text(q["BasePrice"])))
        }
    }
}
