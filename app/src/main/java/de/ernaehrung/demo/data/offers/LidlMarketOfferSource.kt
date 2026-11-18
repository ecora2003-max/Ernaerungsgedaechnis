package de.ernaehrung.demo.data.offers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant

/** The Lidl directory links the canonical address to the numeric store code.
 * The offer request uses that exact code and does not require a Lidl Plus login. */
internal class LidlMarketOfferSource(
    override val endpoint: URI,
    private val marketEndpoint: URI,
    private val sourceId: String,
    private val exactScope: LiveOfferScope,
    private val http: OfferHttp,
    private val now: () -> Instant
) : AuthorizedOfferSource {
    private val storeId = requireNotNull(exactScope.storeId)
    override val endpoints = setOf(endpoint, marketEndpoint)

    init {
        exactScope.validate()
        require(exactScope.retailer == LiveRetailer.LIDL && exactScope.regionId == null && exactScope.deliveryAreaId == null)
        require(exactScope.searchTerm == null && storeId.matches(Regex("DE[0-9]{3,6}")))
        requirePublicHttps(endpoint)
        require(endpoint.toString() == "https://offers.lidlplus.com/app/api/v4/DE/$storeId/offers")
        requirePublicHttps(marketEndpoint)
        require(marketEndpoint.host == "www.lidl.de" && marketEndpoint.rawQuery == null &&
            marketEndpoint.rawPath.matches(Regex("/s/de-DE/filialen/[a-z0-9-]+/[a-z0-9-]+/")))
    }

    override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
        require(scope == exactScope)
        val marketPage = read(marketEndpoint, "text/html", 524_288)
        val marketAt = now()
        val market = withContext(Dispatchers.Default) { readMarket(marketPage, marketAt) }
        val offerFeed = read(endpoint, "application/json", 262_144)
        val fetchedAt = now()
        val offers = withContext(Dispatchers.Default) {
            PublishedOfferDecoders.lidl(offerFeed, sourceId, storeId, fetchedAt, endpoint.toString(), market)
                .filter { it.applicable(scope) }
                .groupBy(LiveOffer::id).values.mapNotNull { it.distinct().singleOrNull() }
        }
        return LiveOfferBatch(sourceId, scope, fetchedAt, offers)
    }

    private suspend fun read(uri: URI, media: String, limit: Int): String {
        val reply = http.execute(OfferHttpRequest(uri, headers = mapOf("Accept" to media,
            "Accept-Language" to "de", "User-Agent" to PUBLIC_OFFER_AGENT), maximumResponseBytes = limit))
        reply.requireSuccess()
        if (reply.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != media)
            throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
        return reply.text
    }

    private fun readMarket(html: String, at: Instant): LiveStoreScopeEvidence {
        val document = Jsoup.parse(html)
        val canonical = document.select("link[rel=canonical]").singleOrNull()?.attr("href")
        val heading = document.select("h1").singleOrNull()?.text()
        val address = heading?.takeIf { it.startsWith("Lidl Filiale ") }
            ?.removePrefix("Lidl Filiale ")?.takeIf { it.isNotBlank() }
        val feedbackLink = document.select("a[href]").singleOrNull { it.text() == "Gib uns Feedback zur Filiale" }
        val feedback = feedbackLink?.let { runCatching { URI(it.attr("href")) }.getOrNull() }
        // The directory's store-specific feedback link publishes the numeric
        // code. Its address is evidence only, never a second network target.
        val numbers = feedback?.takeIf { it.scheme == "https" && it.host == "survey2.medallia.eu" }
            ?.rawQuery?.split('&')?.filter { it.startsWith("TNC=") }?.map { it.removePrefix("TNC=") }
        if (canonical != marketEndpoint.toString() || address == null || numbers != listOf(storeId.removePrefix("DE")))
            throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)
        return LiveStoreScopeEvidence(storeId, "Lidl", address, null, null,
            marketEndpoint.toString(), at, endpoint.toString())
    }
}
