package de.ernaehrung.demo.data.offers

import android.content.Context
import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson
import java.net.URI
import java.security.MessageDigest
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
        private val minimumRefreshSeconds: Long?
    ) {
        fun scope(day: LocalDate) = LiveOfferScope(retailer, storeId, regionId, null, day)
        fun supports(scope: LiveOfferScope, at: Instant) = scope.retailer == retailer &&
            scope.storeId == storeId && scope.regionId == regionId && scope.deliveryAreaId == null &&
            scope.purchaseOn in permittedFrom..permittedUntil && at >= reviewedAt &&
            (weekStartsOn == null || scope.purchaseOn in weekStartsOn..weekStartsOn.plusDays(6))
        fun source(scope: LiveOfferScope, http: OfferHttp, now: () -> Instant): AuthorizedOfferSource = when (format) {
            PublishedOfferHttpSource.Format.PENNY_STORE_WEEK ->
                PennyStoreOfferSource(endpoint, requireNotNull(availabilityEndpoint), sourceId, scope, http, now)
            PublishedOfferHttpSource.Format.KAUFLAND_MARKET_HTML ->
                KauflandMarketOfferSource(endpoint, requireNotNull(availabilityEndpoint), sourceId, scope, http, now)
            else -> PublishedOfferHttpSource(endpoint, sourceId, scope, format, http, availabilityEndpoint, weekStartsOn, now)
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
            require(row.keys == setOf("id", "revision", "label", "retailer", "storeId", "regionId", "format",
                "sourceId", "endpoint", "availabilityEndpoint", "weekStartsOn", "approval"))
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
            val digest = MessageDigest.getInstance("SHA-256").digest(JsonOutput.encode(row).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val result = Profile(text("id"), text("label"), LiveRetailer.valueOf(text("retailer")),
                optional("storeId"), optional("regionId"), from, until,
                requireNotNull(OfferJson.instant(approval["reviewedAt"])),
                PublishedOfferHttpSource.Format.valueOf(text("format")), URI(text("endpoint")),
                optional("availabilityEndpoint")?.let(::URI), week, text("sourceId"),
                (if (approval.keys == publicSource) "PUBLIC_SOURCE" else "APP_DISPLAY") +
                    " $sourceEvidence · Scope $scopeEvidence", digest,
                Collections.unmodifiableSet(hosts), approval["cachePermitted"] as Boolean, floor)
            require(result.retailer != LiveRetailer.KNUSPR)
            val scope = result.scope(from)
            val source = result.source(scope, noHttp) { result.reviewedAt }
            require(source.endpoints.all { endpoint -> endpoint.host?.lowercase() in hosts })
            require(result.format == PublishedOfferHttpSource.Format.PENNY_REGION || week == null)
            return result
        }
    }
}
