package de.ernaehrung.demo.data.offers

import java.net.URI
import java.time.Instant
import java.net.URLDecoder
import de.ernaehrung.demo.data.integration.RetailerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact, independently configured endpoint/scope, not an URL supplied by an
 * offer or user text. Runtime authorization always precedes this HTTP adapter.
 * Public source provenance is checked by the host before any request. */
internal class PublishedOfferHttpSource(override val endpoint: URI, private val sourceId: String,
    private val exactScope: LiveOfferScope, private val format: Format, private val http: OfferHttp,
    private val availabilityEndpoint: URI? = null, private val weekStartsOn: java.time.LocalDate? = null,
    private val now: () -> Instant = Instant::now) : AuthorizedOfferSource {
    enum class Format { LIDL_STORE_V4, EDEKA_MARKET, PENNY_REGION, ALDI_NORD_NEXT_DATA, ALDI_NORD_CURRENT_DATA, KAUFLAND_STORE,
        REWE_STATIONARY, ALDI_SUED_WALK_IN, NETTO_BRANCH_HTML, PENNY_STORE_WEEK, ALDI_SUED_NUXT_PUBLIC, REWE_MARKET_HTML,
        KAUFLAND_MARKET_HTML, LIDL_PLUS_MARKET, EDEKA_JAKOBI_HIGHLIGHTS, NETTO_STORE_HTML }
    private val aldiNord get() = format == Format.ALDI_NORD_NEXT_DATA || format == Format.ALDI_NORD_CURRENT_DATA
    override val maximumOffers get() = when {
        aldiNord -> 512
        format == Format.ALDI_SUED_WALK_IN && exactScope.searchTerm != null -> 12
        else -> 256
    }
    override val capability = when (format) {
        Format.REWE_STATIONARY, Format.ALDI_SUED_WALK_IN -> RetailerPolicy.Capability.TARGETED_PRODUCT_PRICE_QUERY
        else -> RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION
    }
    override val endpoints: Set<URI> get() = setOfNotNull(endpoint, availabilityEndpoint)
    init {
        requirePublicHttps(endpoint); exactScope.validate(); require(sourceId.isNotBlank())
        require(format !in setOf(Format.PENNY_STORE_WEEK, Format.KAUFLAND_MARKET_HTML,
            Format.LIDL_PLUS_MARKET, Format.EDEKA_JAKOBI_HIGHLIGHTS)) { "Use the canonical market source" }
        require(exactScope.retailer == when (format) {
            Format.LIDL_STORE_V4, Format.LIDL_PLUS_MARKET -> LiveRetailer.LIDL
            Format.EDEKA_MARKET, Format.EDEKA_JAKOBI_HIGHLIGHTS -> LiveRetailer.EDEKA
            Format.PENNY_REGION -> LiveRetailer.PENNY
            Format.ALDI_NORD_NEXT_DATA, Format.ALDI_NORD_CURRENT_DATA -> LiveRetailer.ALDI_NORD
            Format.KAUFLAND_STORE, Format.KAUFLAND_MARKET_HTML -> LiveRetailer.KAUFLAND
            Format.REWE_STATIONARY, Format.REWE_MARKET_HTML -> LiveRetailer.REWE
            Format.ALDI_SUED_WALK_IN, Format.ALDI_SUED_NUXT_PUBLIC -> LiveRetailer.ALDI_SUED
            Format.NETTO_BRANCH_HTML, Format.NETTO_STORE_HTML -> LiveRetailer.NETTO_MD
            Format.PENNY_STORE_WEEK -> LiveRetailer.PENNY
        })
        if (format in setOf(Format.LIDL_STORE_V4, Format.EDEKA_MARKET, Format.KAUFLAND_STORE,
                Format.REWE_STATIONARY, Format.ALDI_SUED_WALK_IN)) require(!exactScope.storeId.isNullOrBlank())
        if (format == Format.PENNY_REGION) require(!exactScope.regionId.isNullOrBlank() && weekStartsOn != null)
        if (format == Format.KAUFLAND_STORE) requireNotNull(availabilityEndpoint).also(::requirePublicHttps)
        else require(availabilityEndpoint == null)
        if (format in setOf(Format.REWE_STATIONARY, Format.ALDI_SUED_WALK_IN)) validateTargetedEndpoint()
        if (exactScope.searchTerm != null) require(capability == RetailerPolicy.Capability.TARGETED_PRODUCT_PRICE_QUERY)
        if (format == Format.ALDI_NORD_CURRENT_DATA) require(endpoint.toString() == "https://www.aldi-nord.de/angebote.html" &&
            exactScope.storeId == null && exactScope.regionId == null)
        if (format == Format.NETTO_BRANCH_HTML) require(endpoint.host == "www.netto-online.de" &&
            endpoint.rawPath in setOf("/angebote/c-N07", "/lebensmittel-angebote/c-N07941") && endpoint.rawQuery == null)
        if (format == Format.NETTO_STORE_HTML) {
            require(exactScope.regionId == null)
            NettoBranchOfferDecoder.requireStoreEndpoint(endpoint, requireNotNull(exactScope.storeId))
        }
        if (format == Format.ALDI_SUED_NUXT_PUBLIC) require(endpoint.toString() == "https://www.aldi-sued.de/angebote" &&
            exactScope.storeId == null && exactScope.regionId == null && exactScope.deliveryAreaId == null)
        if (format == Format.REWE_MARKET_HTML) {
            require(exactScope.regionId == null)
            ReweMarketOfferDecoder.requireEndpoint(endpoint, requireNotNull(exactScope.storeId))
        }
    }

    /** Exactly one bounded, explicitly configured search. No private shopping
     * text, cookies, pagination, empty-query catalogue scan or discovery grant. */
    private fun validateTargetedEndpoint() {
        val pairs = requireNotNull(endpoint.rawQuery).split('&').map { part ->
            val bits = part.split('=', limit = 2); require(bits.size == 2)
            URLDecoder.decode(bits[0], "UTF-8") to URLDecoder.decode(bits[1], "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        val q = pairs.toMap()
        when (format) {
            Format.REWE_STATIONARY -> {
                require(endpoint.host == "www.rewe.de" && endpoint.rawPath == "/api/stationary-product-search/products")
                require(q.keys == setOf("query", "wwIdent") && q["wwIdent"] == exactScope.storeId &&
                    q["query"]?.let { it.isNotBlank() && it.length <= 200 } == true)
                require(exactScope.searchTerm == null || q["query"] == exactScope.searchTerm)
            }
            Format.ALDI_SUED_WALK_IN -> {
                require(endpoint.host == "api.aldi-sued.de" && endpoint.rawPath == "/v3/product-search")
                require(q.keys.containsAll(setOf("q", "servicePoint", "serviceType", "limit", "offset")) &&
                    q.keys.all { it in setOf("q", "servicePoint", "serviceType", "limit", "offset", "currency") })
                require(q["servicePoint"] == exactScope.storeId && q["serviceType"] == "walk-in" &&
                    q["q"]?.let { it.isNotBlank() && it.length <= 200 } == true && q["offset"] == "0" &&
                    q["limit"]?.toIntOrNull()?.let { it in 1..12 } == true && (q["currency"] == null || q["currency"] == "EUR"))
                require(exactScope.searchTerm == null || q["q"] == exactScope.searchTerm)
            }
            else -> error("Only targeted catalogue formats")
        }
    }
    override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
        require(scope == exactScope)
        val html = format in setOf(Format.NETTO_BRANCH_HTML, Format.NETTO_STORE_HTML,
            Format.ALDI_SUED_NUXT_PUBLIC, Format.REWE_MARKET_HTML)
        val embedded = aldiNord || format == Format.KAUFLAND_STORE
        val reply = http.execute(OfferHttpRequest(endpoint,
            headers = when { html -> mapOf("Accept" to "text/html")
                embedded -> mapOf("Accept" to "application/json, text/html")
                else -> mapOf("Accept" to "application/json") } + mapOf("User-Agent" to PUBLIC_OFFER_AGENT),
            maximumResponseBytes = if (format in setOf(Format.ALDI_SUED_NUXT_PUBLIC, Format.REWE_MARKET_HTML))
                2_097_152 else 1_048_576)); reply.requireSuccess()
        val media = reply.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        require(media in when { html -> setOf("text/html")
            embedded -> setOf("application/json", "application/ld+json", "text/html")
            else -> setOf("application/json", "application/ld+json") }) { "Unexpected published source media type" }
        val availabilityText = if (format == Format.KAUFLAND_STORE) {
            val availability = http.execute(OfferHttpRequest(requireNotNull(availabilityEndpoint)))
            availability.requireSuccess()
            require(availability.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() == "application/json")
            availability.text
        } else null
        // Both independently authorized replies are now available. Earlier
        // knowledge must not include store evidence received only afterwards.
        val at = now()
        val raw = if (media == "text/html" && embedded) withContext(Dispatchers.Default) {
            when (format) {
                Format.ALDI_NORD_NEXT_DATA, Format.ALDI_NORD_CURRENT_DATA -> EmbeddedPublishedOfferJson.aldiNord(reply.text)
                Format.KAUFLAND_STORE -> EmbeddedPublishedOfferJson.kaufland(reply.text)
                else -> error("Only embedded offer formats")
            }
        } else reply.text
        val rows = when (format) {
            Format.LIDL_STORE_V4 -> PublishedOfferDecoders.lidl(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            Format.EDEKA_MARKET -> PublishedOfferDecoders.edeka(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            Format.PENNY_REGION -> PublishedOfferDecoders.penny(reply.text, sourceId, requireNotNull(scope.regionId), requireNotNull(weekStartsOn), at, endpoint.toString())
            Format.ALDI_NORD_NEXT_DATA -> PublishedOfferDecoders.aldiNordNext(raw, sourceId, at, endpoint.toString())
            Format.ALDI_NORD_CURRENT_DATA -> PublishedOfferDecoders.aldiNordCurrent(raw, sourceId, at, endpoint.toString())
            Format.REWE_STATIONARY -> withContext(Dispatchers.Default) {
                StationaryCatalogueDecoders.rewe(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString(), scope.searchTerm)
            }
            Format.ALDI_SUED_WALK_IN -> withContext(Dispatchers.Default) {
                StationaryCatalogueDecoders.aldiSued(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            }
            Format.NETTO_BRANCH_HTML -> withContext(Dispatchers.Default) {
                NettoBranchOfferDecoder.decode(reply.text, sourceId, at, endpoint.toString())
            }
            Format.NETTO_STORE_HTML -> withContext(Dispatchers.Default) {
                NettoBranchOfferDecoder.decodeStore(reply.text, sourceId, scope, endpoint, at)
            }
            Format.ALDI_SUED_NUXT_PUBLIC -> withContext(Dispatchers.Default) {
                AldiSuedPublicOfferDecoder.decode(reply.text, sourceId, at, endpoint.toString())
            }
            Format.REWE_MARKET_HTML -> withContext(Dispatchers.Default) {
                ReweMarketOfferDecoder.decode(reply.text, sourceId, scope, endpoint, at)
            }
            Format.KAUFLAND_STORE -> {
                val array = de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson.decode("{\"rows\":${requireNotNull(availabilityText)}}",
                    de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson.Limits(maxChars = 1_048_576, maxDepth = 24, maxNodes = 50_000))["rows"] as? List<*>
                    ?: error("Expected official store availability array")
                val windows = array.map { requireNotNull(OfferJson.objectValue(it)) }
                PublishedOfferDecoders.kaufland(raw, windows, sourceId, requireNotNull(scope.storeId), scope.purchaseOn, at, endpoint.toString())
            }
            Format.PENNY_STORE_WEEK, Format.KAUFLAND_MARKET_HTML, Format.LIDL_PLUS_MARKET, Format.EDEKA_JAKOBI_HIGHLIGHTS ->
                error("Use the canonical market source")
        }
        return LiveOfferBatch(sourceId, scope, at, rows)
    }
}
