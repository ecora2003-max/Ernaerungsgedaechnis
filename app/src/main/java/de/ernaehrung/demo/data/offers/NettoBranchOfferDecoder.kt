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
