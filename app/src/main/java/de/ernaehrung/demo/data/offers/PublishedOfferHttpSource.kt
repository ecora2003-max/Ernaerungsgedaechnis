package de.ernaehrung.demo.data.offers

import java.net.URI
import java.time.Instant
import java.net.URLDecoder
import de.ernaehrung.demo.data.integration.RetailerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact, independently configured endpoint/scope, not an URL supplied by an
 * offer or user text. Runtime authorization always precedes this HTTP adapter.
 * No production endpoints are activated while APP_DISPLAY is unverified. */
internal class PublishedOfferHttpSource(override val endpoint: URI, private val sourceId: String,
    private val exactScope: LiveOfferScope, private val format: Format, private val http: OfferHttp,
    private val availabilityEndpoint: URI? = null, private val weekStartsOn: java.time.LocalDate? = null,
    private val now: () -> Instant = Instant::now) : AuthorizedOfferSource {
    enum class Format { LIDL_STORE_V4, EDEKA_MARKET, PENNY_REGION, ALDI_NORD_NEXT_DATA, KAUFLAND_STORE,
        REWE_STATIONARY, ALDI_SUED_WALK_IN, NETTO_BRANCH_HTML }
    override val capability = when (format) {
        Format.REWE_STATIONARY, Format.ALDI_SUED_WALK_IN -> RetailerPolicy.Capability.TARGETED_PRODUCT_PRICE_QUERY
        else -> RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION
    }
    override val endpoints: Set<URI> get() = setOfNotNull(endpoint, availabilityEndpoint)
    init {
        requirePublicHttps(endpoint); exactScope.validate(); require(sourceId.isNotBlank())
        require(exactScope.retailer == when (format) {
            Format.LIDL_STORE_V4 -> LiveRetailer.LIDL; Format.EDEKA_MARKET -> LiveRetailer.EDEKA
            Format.PENNY_REGION -> LiveRetailer.PENNY; Format.ALDI_NORD_NEXT_DATA -> LiveRetailer.ALDI_NORD
            Format.KAUFLAND_STORE -> LiveRetailer.KAUFLAND
            Format.REWE_STATIONARY -> LiveRetailer.REWE
            Format.ALDI_SUED_WALK_IN -> LiveRetailer.ALDI_SUED
            Format.NETTO_BRANCH_HTML -> LiveRetailer.NETTO_MD
        })
        if (format in setOf(Format.LIDL_STORE_V4, Format.EDEKA_MARKET, Format.KAUFLAND_STORE,
                Format.REWE_STATIONARY, Format.ALDI_SUED_WALK_IN)) require(!exactScope.storeId.isNullOrBlank())
        if (format == Format.PENNY_REGION) require(!exactScope.regionId.isNullOrBlank() && weekStartsOn != null)
        if (format == Format.KAUFLAND_STORE) requireNotNull(availabilityEndpoint).also(::requirePublicHttps)
        else require(availabilityEndpoint == null)
        if (format in setOf(Format.REWE_STATIONARY, Format.ALDI_SUED_WALK_IN)) validateTargetedEndpoint()
        if (format == Format.NETTO_BRANCH_HTML) require(endpoint.host == "www.netto-online.de" &&
            endpoint.rawPath in setOf("/angebote/c-N07", "/lebensmittel-angebote/c-N07941") && endpoint.rawQuery == null)
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
            }
            Format.ALDI_SUED_WALK_IN -> {
                require(endpoint.host == "api.aldi-sued.de" && endpoint.rawPath == "/v3/product-search")
                require(q.keys.containsAll(setOf("q", "servicePoint", "serviceType", "limit", "offset")) &&
                    q.keys.all { it in setOf("q", "servicePoint", "serviceType", "limit", "offset", "currency") })
                require(q["servicePoint"] == exactScope.storeId && q["serviceType"] == "walk-in" &&
                    q["q"]?.let { it.isNotBlank() && it.length <= 200 } == true && q["offset"] == "0" &&
                    q["limit"]?.toIntOrNull()?.let { it in 1..12 } == true && (q["currency"] == null || q["currency"] == "EUR"))
            }
            else -> error("Only targeted catalogue formats")
        }
    }
    override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
        require(scope == exactScope)
        val html = format == Format.NETTO_BRANCH_HTML
        val reply = http.execute(OfferHttpRequest(endpoint,
            headers = if (html) mapOf("Accept" to "text/html") else emptyMap())); reply.requireSuccess()
        require(reply.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() in
            if (html) setOf("text/html") else setOf("application/json", "application/ld+json")) { "Unexpected published source media type" }
        val at = now()
        val rows = when (format) {
            Format.LIDL_STORE_V4 -> PublishedOfferDecoders.lidl(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            Format.EDEKA_MARKET -> PublishedOfferDecoders.edeka(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            Format.PENNY_REGION -> PublishedOfferDecoders.penny(reply.text, sourceId, requireNotNull(scope.regionId), requireNotNull(weekStartsOn), at, endpoint.toString())
            Format.ALDI_NORD_NEXT_DATA -> PublishedOfferDecoders.aldiNordNext(reply.text, sourceId, at, endpoint.toString())
            Format.REWE_STATIONARY -> withContext(Dispatchers.Default) {
                StationaryCatalogueDecoders.rewe(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            }
            Format.ALDI_SUED_WALK_IN -> withContext(Dispatchers.Default) {
                StationaryCatalogueDecoders.aldiSued(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            }
            Format.NETTO_BRANCH_HTML -> withContext(Dispatchers.Default) {
                NettoBranchOfferDecoder.decode(reply.text, sourceId, at, endpoint.toString())
            }
            Format.KAUFLAND_STORE -> {
                val availability = http.execute(OfferHttpRequest(requireNotNull(availabilityEndpoint)))
                availability.requireSuccess()
                require(availability.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() == "application/json")
                val array = de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson.decode("{\"rows\":${availability.text}}",
                    de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson.Limits(maxChars = 1_048_576, maxDepth = 24, maxNodes = 50_000))["rows"] as? List<*>
                    ?: error("Expected official store availability array")
                val windows = array.map { requireNotNull(OfferJson.objectValue(it)) }
                PublishedOfferDecoders.kaufland(reply.text, windows, sourceId, requireNotNull(scope.storeId), scope.purchaseOn, at, endpoint.toString())
            }
        }
        return LiveOfferBatch(sourceId, scope, at, rows)
    }
}
