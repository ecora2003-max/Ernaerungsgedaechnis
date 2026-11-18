package de.ernaehrung.demo.data.offers

import android.content.Context
import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson
import de.ernaehrung.demo.data.local.AppCalendar
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.Collections

/** Release-owned source configuration, independent of supplier replies and UI input.
 * A public-source provenance assessment does not require an invented vendor licence.
 * Parsing a document never grants third-party rights or overrides access restrictions. */
internal class ReviewedOfferSources private constructor(
    val profiles: List<Profile>, val invalidProfiles: Int
) {
    internal class Profile internal constructor(
        val id: String, val label: String, val retailer: LiveRetailer,
        val storeId: String?, val regionId: String?,
        val permittedFrom: LocalDate, val permittedUntil: LocalDate,
        val reviewedAt: Instant, val format: PublishedOfferHttpSource.Format,
        val endpoint: URI, val availabilityEndpoint: URI?, val weekStartsOn: LocalDate?,
        private val sourceId: String, private val evidence: String, private val fingerprint: String,
        private val hosts: Set<String>, private val cachePermitted: Boolean,
        private val minimumRefreshSeconds: Long?, val interactiveSearch: Boolean, private val nextWeekEndpoint: URI?
    ) {
        fun scope(day: LocalDate, searchTerm: String? = null) = LiveOfferScope(retailer, storeId, regionId, null, day, searchTerm)
        fun supports(scope: LiveOfferScope, at: Instant): Boolean {
            val today = AppCalendar.today(at)
            val monday = today.with(DayOfWeek.MONDAY)
            return scope.retailer == retailer && scope.storeId == storeId && scope.regionId == regionId &&
                scope.deliveryAreaId == null && scope.purchaseOn in permittedFrom..permittedUntil && at >= reviewedAt &&
                interactiveSearch == (scope.searchTerm != null) && (!interactiveSearch || scope.purchaseOn == today) &&
                (nextWeekEndpoint == null || scope.purchaseOn in monday..monday.plusDays(13)) &&
                (weekStartsOn == null || scope.purchaseOn in weekStartsOn..weekStartsOn.plusDays(6))
        }
        fun source(scope: LiveOfferScope, http: OfferHttp, now: () -> Instant): AuthorizedOfferSource = when (format) {
            PublishedOfferHttpSource.Format.PENNY_STORE_WEEK ->
                PennyStoreOfferSource(endpoint, requireNotNull(availabilityEndpoint), sourceId, scope, http, now)
            PublishedOfferHttpSource.Format.KAUFLAND_MARKET_HTML ->
                KauflandMarketOfferSource(endpoint, requireNotNull(availabilityEndpoint), sourceId, scope, http, now)
            else -> {
                val nextWeek = nextWeekEndpoint != null && scope.purchaseOn.with(DayOfWeek.MONDAY) ==
                    AppCalendar.today(now()).with(DayOfWeek.MONDAY).plusWeeks(1)
                val request = when {
                    interactiveSearch -> {
                        val encoded = URLEncoder.encode(requireNotNull(scope.searchTerm), "UTF-8")
                        val parameters = when (format) {
                            PublishedOfferHttpSource.Format.REWE_STATIONARY -> "query=$encoded&wwIdent=${scope.storeId}"
                            PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN ->
                                "q=$encoded&servicePoint=${scope.storeId}&serviceType=walk-in&limit=12&offset=0&currency=EUR"
                            else -> error("No interactive price query for this source")
                        }
                        URI("$endpoint?$parameters")
                    }
                    nextWeek -> requireNotNull(nextWeekEndpoint)
                    else -> endpoint
                }
                val reader = if (nextWeek) PublishedOfferHttpSource.Format.ALDI_NORD_NEXT_DATA else format
                PublishedOfferHttpSource(request, sourceId, scope, reader, http, availabilityEndpoint, weekStartsOn, now)
            }
        }
        fun grant(scope: LiveOfferScope) = LiveSourceGrant(sourceId, retailer, evidence, permittedFrom,
            permittedUntil, hosts, cachePermitted, minimumRefreshSeconds, scope,
            when (format) {
                PublishedOfferHttpSource.Format.REWE_STATIONARY, PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN ->
                    de.ernaehrung.demo.data.integration.RetailerPolicy.Capability.TARGETED_PRODUCT_PRICE_QUERY
                else -> de.ernaehrung.demo.data.integration.RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION
            }, fingerprint)
    }

    /** Ambiguous branch approvals never depend on list order. Other branches
     * remain usable. Every source/grant resolution uses the same exact scope. */
    fun profile(scope: LiveOfferScope, at: Instant): Profile? = profiles.filter { it.supports(scope, at) }.singleOrNull()
    fun runtime(http: OfferHttp, journal: OfferRefreshJournal, now: () -> Instant = Instant::now) = LiveOfferRuntime(
        LiveOfferSourceResolver { scope -> profile(scope, now())?.source(scope, http, now) },
        LivePermissionRegistry { scope -> profile(scope, now())?.grant(scope) }, journal, now)

    companion object {
        const val ASSET = "reviewed-live-offer-sources.v1.json"
        private val noHttp = OfferHttp { error("Configuration validation must never perform I/O") }
        fun bundled(context: Context): ReviewedOfferSources = try {
            val bytes = context.applicationContext.assets.open(ASSET).use { input ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(data.size() + count <= 65_536)
                    data.write(buffer, 0, count)
                }
                data.toByteArray()
            }
            decode(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        } catch (_: Exception) { ReviewedOfferSources(emptyList(), 1) }

        fun decode(text: String): ReviewedOfferSources {
            val root = RecipeDraftBoundedJson.decode(text,
                RecipeDraftBoundedJson.Limits(maxChars = 65_536, maxDepth = 8, maxNodes = 4096))
            require(root.keys == setOf("format", "profiles") && root["format"] == "eg-reviewed-published-sources-1")
            val rows = root["profiles"] as? List<*> ?: error("Missing host profiles")
            require(rows.size <= 64)
            var invalid = 0
            val parsed = rows.mapNotNull { row ->
                try { decodeProfile(requireNotNull(OfferJson.objectValue(row))) }
                catch (_: Exception) { invalid++; null }
            }
            val duplicates = parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys
            invalid += parsed.count { it.id in duplicates }
            return ReviewedOfferSources(Collections.unmodifiableList(parsed.filterNot { it.id in duplicates }), invalid)
        }
        private fun decodeProfile(row: Map<String, Any?>): Profile {
            val fields = setOf("id", "revision", "label", "retailer", "storeId", "regionId", "format",
                "sourceId", "endpoint", "availabilityEndpoint", "weekStartsOn", "approval")
            require(row.keys.containsAll(fields) && (row.keys - fields).all { it in setOf("interactiveSearch", "nextWeekEndpoint") })
            fun text(key: String) = requireNotNull(OfferJson.text(row[key]))
            fun optional(key: String): String? = row[key]?.let { requireNotNull(OfferJson.text(it)) }
            val approval = requireNotNull(OfferJson.objectValue(row["approval"]))
            val common = setOf("scopeEvidence", "reviewedAt", "from", "until",
                "allowedHosts", "cachePermitted", "minimumRefreshSeconds")
            val legacy = common + "appDisplayEvidence"
            val publicSource = common + "sourceEvidence"
            require(approval.keys == legacy || approval.keys == publicSource)
            fun proof(key: String): String = requireNotNull(OfferJson.text(approval[key])).also { requirePublicHttps(URI(it)) }
            val sourceEvidence = if (approval.keys == publicSource) proof("sourceEvidence") else proof("appDisplayEvidence")
            val scopeEvidence = proof("scopeEvidence")
            require(OfferJson.integer(row["revision"])?.let { it > 0 } == true)
            val hosts = (approval["allowedHosts"] as? List<*>)?.map {
                val host = it as? String ?: error("Host must be a string")
                require(host.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")) && '.' in host)
                host
            }?.toSet() ?: error("Missing host allowlist")
            require(hosts.isNotEmpty() && hosts.size <= 8)
            val from = requireNotNull(OfferJson.date(approval["from"]))
            val until = requireNotNull(OfferJson.date(approval["until"]))
            require(until >= from && approval["cachePermitted"] is Boolean)
            val floor = approval["minimumRefreshSeconds"]?.let { requireNotNull(OfferJson.integer(it)).also { seconds -> require(seconds > 0) } }
            val week = optional("weekStartsOn")?.let { requireNotNull(OfferJson.date(it)) }
            if (week != null) require(week.dayOfWeek == java.time.DayOfWeek.MONDAY)
            val search = if ("interactiveSearch" in row) row["interactiveSearch"] as? Boolean ?: error("Invalid search flag") else false
            val nextWeek = optional("nextWeekEndpoint")?.let(::URI)
            val digest = MessageDigest.getInstance("SHA-256").digest(JsonOutput.encode(row).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val result = Profile(text("id"), text("label"), LiveRetailer.valueOf(text("retailer")),
                optional("storeId"), optional("regionId"), from, until,
                requireNotNull(OfferJson.instant(approval["reviewedAt"])),
                PublishedOfferHttpSource.Format.valueOf(text("format")), URI(text("endpoint")),
                optional("availabilityEndpoint")?.let(::URI), week, text("sourceId"),
                (if (approval.keys == publicSource) "PUBLIC_SOURCE" else "APP_DISPLAY") +
                    " $sourceEvidence · Scope $scopeEvidence", digest,
                Collections.unmodifiableSet(hosts), approval["cachePermitted"] as Boolean, floor, search, nextWeek)
            require(result.retailer != LiveRetailer.KNUSPR)
            if (search) {
                val validSearch = when (result.format) {
                    PublishedOfferHttpSource.Format.REWE_STATIONARY ->
                        result.endpoint.toString() == "https://www.rewe.de/api/stationary-product-search/products" &&
                            result.storeId?.matches(Regex("[0-9]{4,10}")) == true
                    PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN ->
                        result.endpoint.toString() == "https://api.aldi-sued.de/v3/product-search" &&
                            result.storeId?.matches(Regex("[A-Z]{1,3}[0-9]{2,6}")) == true
                    else -> false
                }
                require(validSearch && result.regionId == null && approval["cachePermitted"] == false && floor != null)
            }
            if (nextWeek != null) require(result.format == PublishedOfferHttpSource.Format.ALDI_NORD_CURRENT_DATA &&
                result.endpoint.toString() == "https://www.aldi-nord.de/angebote.html" &&
                nextWeek.toString() == "https://www.aldi-nord.de/angebote-vorschau.html" && nextWeek.host in hosts &&
                result.storeId == null && result.regionId == null)
            val scope = result.scope(from, "example".takeIf { search })
            val source = result.source(scope, noHttp) { result.reviewedAt }
            require(source.endpoints.all { endpoint -> endpoint.host?.lowercase() in hosts })
            require(result.format == PublishedOfferHttpSource.Format.PENNY_REGION || week == null)
            return result
        }
    }
}
