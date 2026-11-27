package de.ernaehrung.demo.data.offers

import java.net.URI
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** The merchant publishes a small selection in HTML and lists the same
 * advertising window and market address on its Worms page. No flyer assets,
 * scripts, analytics, account or offer link is loaded. */
internal class EdekaJakobiMarketOfferSource(override val endpoint: URI, private val marketEndpoint: URI,
    private val sourceId: String, private val exactScope: LiveOfferScope, private val http: OfferHttp,
    private val now: () -> Instant = Instant::now) : AuthorizedOfferSource {
    override val endpoints = setOf(endpoint, marketEndpoint)
    override val maximumOffers = 32

    init {
        exactScope.validate()
        require(endpoint.toString() == "https://www.edeka-jakobi.de/" &&
            marketEndpoint.toString() == "https://www.edeka-jakobi.de/worms")
        require(exactScope.retailer == LiveRetailer.EDEKA && exactScope.storeId == "JAKOBI-WORMS" &&
            exactScope.regionId == null && exactScope.searchTerm == null)
    }

    override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
        require(scope == exactScope)
        val marketHtml = read(marketEndpoint)
        val listingHtml = read(endpoint)
        val at = now()
        return withContext(Dispatchers.Default) {
            val storeId = requireNotNull(scope.storeId)
            val market = Jsoup.parse(marketHtml)
            val name = market.select("#intro h1").singleOrNull()?.text()?.takeIf { it == "EDEKA Jakobi Worms" }
                ?: unavailable()
            val address = market.select("#intro .mark").singleOrNull()?.text()
                ?.takeIf { it == "Mittelochsenplatz 4 | 67547 Worms" }?.replace(" | ", ", ") ?: unavailable()
            val marketWeek = market.select("#angebote + #w + div p strong").singleOrNull()?.text()
                ?.takeIf { it.endsWith(" im E-Center Worms:") }?.removeSuffix(" im E-Center Worms:")
                ?.let(GermanOfferWindow::read) ?: invalid()
            if (scope.purchaseOn !in marketWeek) unavailable()

            val listing = Jsoup.parse(listingHtml)
            val block = listing.select(".start-angebot > div > h3").singleOrNull {
                it.text() == "In Ihrem Markt in Worms"
            }?.parent() ?: unavailable()
            val terms = block.nextElementSibling()?.takeIf {
                it.select("a[href]").count { link -> link.attr("href") == "worms#angebote" } == 1
            }?.select("p")?.firstOrNull()?.text() ?: unavailable()
            val dayText = terms.substringAfterLast("Gültig von ", "").removeSuffix(".")
            val listingWeek = GermanOfferWindow.read("Gültig von $dayText") ?: invalid()
            if (marketWeek != listingWeek) unavailable()
            val scopeProof = LiveStoreScopeEvidence(storeId, name, address, null, null,
                marketEndpoint.toString(), at, endpoint.toString())
            val tiles = block.select(".angebote > .p-angebote")
            if (tiles.size !in 1..maximumOffers) invalid()
            val offers = tiles.mapIndexedNotNull { slot, tile ->
                val title = OfferJson.text(tile.select(".angebot-headline").singleOrNull()?.text())
                    ?: return@mapIndexedNotNull null
                val price = tile.children().singleOrNull {
                    it.hasClass("angebot-einstellig") || it.hasClass("angebot-zweistellig")
                } ?: return@mapIndexedNotNull null
                val major = price.select(".price-first").singleOrNull()?.text()
                val minor = price.select(".price-second").singleOrNull()?.text()
                val separator = price.select(".price-komma").singleOrNull()?.text()
                if (price.childrenSize() != 3 || price.ownText().isNotEmpty() ||
                    major?.matches(Regex("[0-9]{1,4}")) != true || minor?.matches(Regex("[0-9]{2}")) != true ||
                    separator !in setOf(".", ",")) return@mapIndexedNotNull null
                val cents = OfferJson.euroCents("$major.$minor") ?: return@mapIndexedNotNull null
                LiveOffer("$sourceId:${marketWeek.start}:$slot", sourceId, scope.retailer, title, null, null,
                    cents, null, null, null, null, LiveOfferKind.PROMOTION, marketWeek.start, marketWeek.endInclusive,
                    null, at, storeId, null, null, LiveOfferApplicability.STORE_CONFIRMED,
                    OfferJson.text(terms.substringBeforeLast("Gültig von ")), false, endpoint.toString(),
                    storeEvidence = scopeProof, productPageUrl = marketEndpoint.toString())
            }
            LiveOfferBatch(sourceId, scope, at, offers)
        }
    }

    private suspend fun read(uri: URI): String {
        val reply = http.execute(OfferHttpRequest(uri, headers = mapOf(
            "Accept" to "text/html", "User-Agent" to PUBLIC_OFFER_AGENT), maximumResponseBytes = 262_144))
        reply.requireSuccess()
        if (reply.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "text/html" ||
            reply.text.length > 262_144) invalid()
        return reply.text
    }

    private fun unavailable(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)
    private fun invalid(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
}
