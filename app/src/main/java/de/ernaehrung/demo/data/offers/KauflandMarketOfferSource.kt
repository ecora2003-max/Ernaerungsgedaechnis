package de.ernaehrung.demo.data.offers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalDate

/** The official market page renders a bounded selection of its offers. Its
 * article references link to the separate exact market/date applicability feed. */
internal class KauflandMarketOfferSource(
    override val endpoint: URI,
    private val availabilityEndpoint: URI,
    private val sourceId: String,
    private val exactScope: LiveOfferScope,
    private val http: OfferHttp,
    private val now: () -> Instant
) : AuthorizedOfferSource {
    private val storeId = requireNotNull(exactScope.storeId)
    private val marketEndpoint = URI("$ORIGIN/.klstorebygeo.storeName=$storeId.json")
    override val endpoints = setOf(endpoint, availabilityEndpoint, marketEndpoint)

    init {
        require(exactScope.retailer == LiveRetailer.KAUFLAND && exactScope.regionId == null && exactScope.deliveryAreaId == null)
        require(storeId.matches(Regex("DE[0-9]{3,5}")))
        requirePublicHttps(endpoint)
        require(endpoint.host == "filiale.kaufland.de" && endpoint.rawQuery == null &&
            endpoint.rawPath.matches(Regex("/service/filiale/[a-z0-9-]+-${storeId.removePrefix("DE")}\\.html")))
        require(availabilityEndpoint.toString() == "$ORIGIN/.kloffers.storeName=$storeId.json")
    }

    override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
        require(scope == exactScope)
        val market = read(marketEndpoint, "application/json", 32_768)
        val marketFacts = OfferJson.read(market)
        if (OfferJson.text(marketFacts["n"]) != storeId) unavailable()
        val html = read(endpoint, "text/html", 1_048_576)
        val availability = read(availabilityEndpoint, "application/json", 262_144)
        val observedAt = now()
        val offers = withContext(Dispatchers.Default) {
            decode(html, availability, marketFacts, sourceId, scope, endpoint, availabilityEndpoint, observedAt)
        }
        return LiveOfferBatch(sourceId, scope, observedAt, offers)
    }

    private suspend fun read(uri: URI, type: String, limit: Int): String {
        val reply = http.execute(OfferHttpRequest(uri, headers = mapOf("Accept" to type, "User-Agent" to PUBLIC_OFFER_AGENT),
            maximumResponseBytes = limit))
        reply.requireSuccess()
        if (reply.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != type) invalid()
        return reply.text
    }

    companion object {
        private const val ORIGIN = "https://filiale.kaufland.de"
        private val article = Regex("[0-9]{6,16}")

        fun decode(html: String, availability: String, market: Map<String, Any?>, sourceId: String,
            scope: LiveOfferScope, endpoint: URI, availabilityEndpoint: URI, at: Instant): List<LiveOffer> {
            if (html.length > 1_048_576 || availability.length > 262_144) invalid()
            val storeId = requireNotNull(scope.storeId)
            val document = Jsoup.parse(html)
            if (document.getAllElements().size > 50_000) invalid()
            val canonical = document.select("link[rel=canonical]").singleOrNull()?.attr("href")
            val markedStore = document.select("[data-t-name=StoreDetails][data-force-store-change]")
                .singleOrNull()?.attr("data-force-store-change")
            if (canonical != endpoint.toString() || markedStore != storeId || market["n"] != storeId) unavailable()
            fun marketText(key: String) = OfferJson.text(market[key]) ?: invalid()
            val proof = LiveStoreScopeEvidence(storeId, marketText("cn"),
                "${marketText("sn")}, ${marketText("pc")} ${marketText("t")}", null, null, endpoint.toString(), at,
                availabilityEndpoint.toString())
            val dates = applicableWindows(availability, scope.purchaseOn)
            val tiles = document.select("a.k-product-tile--slider[href]")
            if (tiles.size > 256) invalid()
            return tiles.mapNotNull { tile -> readCard(tile, sourceId, endpoint, at, proof, dates) }
                .groupBy(LiveOffer::id).values.mapNotNull { rows -> rows.distinct().singleOrNull() }
        }

        private fun applicableWindows(raw: String, day: LocalDate): Map<String, Pair<LocalDate, LocalDate>> {
            val rows = OfferJson.read("{\"rows\":$raw}")["rows"] as? List<*> ?: invalid()
            if (rows.size > 1024) invalid()
            return rows.mapNotNull { value ->
                val row = OfferJson.objectValue(value) ?: return@mapNotNull null
                val id = OfferJson.text(row["klNr"])?.takeIf(article::matches) ?: return@mapNotNull null
                val start = OfferJson.date(row["dateFrom"]) ?: return@mapNotNull null
                val end = OfferJson.date(row["dateTo"]) ?: return@mapNotNull null
                if (day !in start..end) return@mapNotNull null
                id to (start to end)
            }.groupBy({ it.first }, { it.second }).mapNotNull { (id, windows) ->
                windows.distinct().singleOrNull()?.let { id to it }
            }.toMap()
        }

        private fun readCard(tile: Element, sourceId: String, endpoint: URI, at: Instant,
            proof: LiveStoreScopeEvidence, windows: Map<String, Pair<LocalDate, LocalDate>>): LiveOffer? {
            val link = runCatching { endpoint.resolve(tile.attr("href")) }.getOrNull() ?: return null
            if (link.scheme != "https" || link.host != endpoint.host || link.port != -1 || link.rawUserInfo != null ||
                link.rawPath != "/angebote/uebersicht.html" || link.fragment != null) return null
            val ids = link.rawQuery.orEmpty().split('&').filter { it.startsWith("kloffer-articleID=") }
            val id = ids.singleOrNull()?.substringAfter('=')?.takeIf(article::matches) ?: return null
            val (from, until) = windows[id] ?: return null
            val title = OfferJson.text(tile.select(".k-product-tile__title").singleOrNull()?.text()) ?: return null
            val subtitle = OfferJson.text(tile.select(".k-product-tile__subtitle").singleOrNull()?.text())
            val name = OfferJson.text(listOfNotNull(title, subtitle).joinToString(" ")) ?: return null
            val normal = tile.select(".k-product-tile__pricetags-normal").singleOrNull() ?: return null
            val rawPrice = normal.select(".k-price-tag__price").singleOrNull()?.text() ?: return null
            val price = OfferJson.euroCents(rawPrice.trimEnd('*', ' ')) ?: return null
            val unit = OfferJson.text(tile.select(".k-product-tile__unit-price").singleOrNull()?.text())
            val rateOnly = publicQuantityRate(unit)
            val appPrice = OfferJson.euroCents(tile.select(".k-product-tile__pricetags-loyalty .k-price-tag__price")
                .singleOrNull()?.text()?.trimEnd('*', ' '))
            val old = OfferJson.text(normal.select(".k-price-tag__old-price").singleOrNull()?.text())
            val rate = OfferJson.text(tile.select(".k-product-tile__base-price").singleOrNull()?.text())
            val conditions = OfferJson.text(normal.select(".k-price-tag__bby-message").text())
            return LiveOffer("$sourceId:$id", sourceId, LiveRetailer.KAUFLAND, name, id, null,
                price.takeUnless { rateOnly }, null, null, null, null, LiveOfferKind.PROMOTION, from, until, null, at,
                proof.marketId, null, null, LiveOfferApplicability.STORE_CONFIRMED, conditions, false, endpoint.toString(), unit,
                LivePriceDetails(previousDisplay = old, appCents = appPrice?.takeUnless { rateOnly },
                    unitPriceDisplay = if (rateOnly) "$rawPrice € $unit" else rate), proof, link.toString())
        }

        private fun invalid(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
        private fun unavailable(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)
    }
}
