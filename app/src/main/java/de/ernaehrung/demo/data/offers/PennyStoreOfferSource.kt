package de.ernaehrung.demo.data.offers

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Public, bounded two-source read. The canonical market catalogue supplies
 * store→selling-region membership; the separate offer response cannot assert
 * that membership or authorize a different endpoint. No cookies or pagination. */
internal class PennyStoreOfferSource(override val endpoint: URI, private val marketEndpoint: URI,
    private val sourceId: String, private val exactScope: LiveOfferScope, private val http: OfferHttp,
    private val now: () -> Instant = Instant::now) : AuthorizedOfferSource {
    override val endpoints = setOf(endpoint, marketEndpoint)
    private val headers = mapOf("Accept" to "application/json", "User-Agent" to
        "Ernaehrungsgedaechtnis public-offers (+https://github.com/ecora2003-max/Ernaerungsgedaechnis)")
    init {
        require(endpoint.toString() == "https://www.penny.de/.rest/offers/by-category/")
        require(marketEndpoint.toString() == "https://www.penny.de/.rest/market")
        require(exactScope.retailer == LiveRetailer.PENNY && exactScope.storeId?.matches(Regex("[0-9]{1,16}")) == true &&
            exactScope.regionId == null && exactScope.deliveryAreaId == null && sourceId.isNotBlank())
    }
    override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch {
        require(scope == exactScope)
        val today = now().atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
        val currentWeek = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val week = scope.purchaseOn.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        if (week != currentWeek && week != currentWeek.plusWeeks(1))
            throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)
        val markets = http.execute(OfferHttpRequest(marketEndpoint, headers = headers, maximumResponseBytes = 6_291_456))
        markets.requireSuccess(); requireJson(markets)
        val market = withContext(Dispatchers.Default) { market(markets.text, requireNotNull(scope.storeId)) }
        val region = if (week == currentWeek) market.currentRegion else market.nextRegion
        if (region == null) throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)
        val scopedAt = now()
        val offerEndpoint = URI(endpoint.toString() + week.get(IsoFields.WEEK_BASED_YEAR) + "-" +
            week.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0') +
            "/top-angebote?region=" + URLEncoder.encode(region, "UTF-8"))
        val offers = http.execute(OfferHttpRequest(offerEndpoint, headers = headers)); offers.requireSuccess(); requireJson(offers)
        val fetchedAt = now()
        val evidence = LiveStoreScopeEvidence(requireNotNull(scope.storeId), market.name, market.address,
            region, week, marketEndpoint.toString(), scopedAt)
        val rows = withContext(Dispatchers.Default) {
            decodeOffers(offers.text, sourceId, fetchedAt, offerEndpoint.toString(), evidence)
        }
        return LiveOfferBatch(sourceId, scope, fetchedAt, rows)
    }
    private fun requireJson(reply: OfferHttpReply) {
        require(reply.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() == "application/json")
    }
    internal data class Market(val name: String, val address: String, val currentRegion: String?, val nextRegion: String?)
    companion object {
        /** The country directory is ~3.8 MB, not a product catalogue. Streaming
         * selects one exact ID; unrelated records/images are not retained. */
        internal fun market(raw: String, id: String): Market {
            require(raw.length <= 6_291_456)
            val wanted = setOf("wwIdent", "marketName", "streetWithHouseNumber", "zipCode", "city", "sellingRegion", "nextWeekSellingRegion")
            var count = 0; var nodes = 0; var match: Market? = null
            fun bounded() { require(++nodes <= 500_000) }
            fun skip(reader: JsonReader, depth: Int) {
                bounded(); require(depth <= 16)
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while (reader.hasNext()) skip(reader, depth + 1); reader.endArray() }
                    JsonToken.BEGIN_OBJECT -> { reader.beginObject(); while (reader.hasNext()) { reader.nextName(); skip(reader, depth + 1) }; reader.endObject() }
                    else -> reader.skipValue()
                }
            }
            JsonReader(StringReader(raw)).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    require(++count <= 6000); reader.beginObject()
                    val fields = mutableMapOf<String, String?>(); val names = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        bounded(); val key = reader.nextName(); require(names.add(key) && names.size <= 160)
                        if (key in wanted) {
                            fields[key] = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null }
                                else { require(reader.peek() == JsonToken.STRING); reader.nextString().also { require(it.length <= 512) } }
                        } else skip(reader, 0)
                    }
                    reader.endObject()
                    if (fields["wwIdent"] == id) {
                        require(match == null) { "Ambiguous canonical market" }
                        fun text(key: String) = requireNotNull(fields[key]).trim().also { require(it.isNotEmpty()) }
                        fun region(key: String) = fields[key]?.trim()?.takeIf { it.isNotEmpty() }?.also {
                            require(it.matches(Regex("[A-Za-z0-9-]{1,100}")))
                        }
                        match = Market(text("marketName"), text("streetWithHouseNumber") + ", " + text("zipCode") + " " + text("city"),
                            region("sellingRegion"), region("nextWeekSellingRegion"))
                    }
                }
                reader.endArray(); require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            return match ?: throw OfferSourceFailure(OfferSourceFailureCode.SCOPE_UNAVAILABLE)
        }
        internal fun decodeOffers(raw: String, sourceId: String, at: Instant, url: String,
            evidence: LiveStoreScopeEvidence): List<LiveOffer> {
            val rows = OfferJson.read(raw)["offerTiles"] as? List<*>
                ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
            require(rows.size <= 256)
            return rows.mapNotNull { value ->
                val row = OfferJson.objectValue(value) ?: return@mapNotNull null
                if (row["onlyOnline"] == true) return@mapNotNull null
                val id = OfferJson.text(row["uuid"]) ?: return@mapNotNull null
                val title = OfferJson.text(row["title"]) ?: return@mapNotNull null
                val page = (row["linkHref"] as? String)?.takeIf { it.startsWith("/angebote/") && !it.startsWith("//") }
                    ?.let { runCatching { URI("https://www.penny.de").resolve(it) }.getOrNull() }
                    ?.takeIf { it.host == "www.penny.de" && it.scheme == "https" && it.rawUserInfo == null && it.fragment == null }?.toString()
                val conditions = buildList {
                    if (row["multiBuy"] == true) add("Mehrkaufbedingung der Quelle; Details im Originalangebot prüfen.")
                    if (row["priceFrom"] == true) add("Ab-Preis der Quelle; konkrete Variante ungeprüft.")
                    OfferJson.text(row["benefitPrice"])?.let(::add)
                }.takeIf { it.isNotEmpty() }?.joinToString(" ")
                LiveOffer("$sourceId:$id", sourceId, LiveRetailer.PENNY, title, id, null,
                    OfferJson.euroCents(row["price"]), null, null, null, null, LiveOfferKind.PROMOTION,
                    null, null, null, at, evidence.marketId, null, null, LiveOfferApplicability.STORE_CONFIRMED,
                    conditions, false, url, OfferJson.text(row["quantity"]),
                    LivePriceDetails(previousDisplay = OfferJson.text(row["originalPrice"])?.let {
                        (OfferJson.text(row["originalPriceType"]) ?: "Preisart ungeklärt") + ": " + it
                    }, unitPriceDisplay = OfferJson.text(row["basePrice"])), evidence, page)
            }
        }
    }
}
