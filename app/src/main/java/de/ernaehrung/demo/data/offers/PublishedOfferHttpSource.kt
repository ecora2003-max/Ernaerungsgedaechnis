package de.ernaehrung.demo.data.offers

import java.net.URI
import java.time.Instant

/** Exact, independently configured endpoint/scope, not an URL supplied by an
 * offer or user text. Runtime authorization always precedes this HTTP adapter.
 * No production endpoints are activated while APP_DISPLAY is unverified. */
internal class PublishedOfferHttpSource(override val endpoint: URI, private val sourceId: String,
    private val exactScope: LiveOfferScope, private val format: Format, private val http: OfferHttp,
    private val availabilityEndpoint: URI? = null, private val weekStartsOn: java.time.LocalDate? = null,
    private val now: () -> Instant = Instant::now) : AuthorizedOfferSource {
    enum class Format { LIDL_STORE_V4, EDEKA_MARKET, PENNY_REGION, ALDI_NORD_NEXT_DATA, KAUFLAND_STORE }
    override val endpoints: Set<URI> get() = setOfNotNull(endpoint, availabilityEndpoint)
    init {
        requirePublicHttps(endpoint); exactScope.validate(); require(sourceId.isNotBlank())
        require(exactScope.retailer == when (format) {
            Format.LIDL_STORE_V4 -> LiveRetailer.LIDL; Format.EDEKA_MARKET -> LiveRetailer.EDEKA
            Format.PENNY_REGION -> LiveRetailer.PENNY; Format.ALDI_NORD_NEXT_DATA -> LiveRetailer.ALDI_NORD
            Format.KAUFLAND_STORE -> LiveRetailer.KAUFLAND
        })
        if (format in setOf(Format.LIDL_STORE_V4, Format.EDEKA_MARKET, Format.KAUFLAND_STORE)) require(!exactScope.storeId.isNullOrBlank())
        if (format == Format.PENNY_REGION) require(!exactScope.regionId.isNullOrBlank() && weekStartsOn != null)
        if (format == Format.KAUFLAND_STORE) requireNotNull(availabilityEndpoint).also(::requirePublicHttps)
        else require(availabilityEndpoint == null)
    }
    override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
        require(scope == exactScope)
        val reply = http.execute(OfferHttpRequest(endpoint)); reply.requireSuccess()
        require(reply.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() in
            setOf("application/json", "application/ld+json")) { "Expected actual published JSON" }
        val at = now()
        val rows = when (format) {
            Format.LIDL_STORE_V4 -> PublishedOfferDecoders.lidl(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            Format.EDEKA_MARKET -> PublishedOfferDecoders.edeka(reply.text, sourceId, requireNotNull(scope.storeId), at, endpoint.toString())
            Format.PENNY_REGION -> PublishedOfferDecoders.penny(reply.text, sourceId, requireNotNull(scope.regionId), requireNotNull(weekStartsOn), at, endpoint.toString())
            Format.ALDI_NORD_NEXT_DATA -> PublishedOfferDecoders.aldiNordNext(reply.text, sourceId, at, endpoint.toString())
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
