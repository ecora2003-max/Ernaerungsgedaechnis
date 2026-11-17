package de.ernaehrung.demo.data.offers

import de.ernaehrung.demo.data.integration.RetailerPolicy
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Host-only namespace. Online delivery is not added to the stationary v1 enum. */
internal enum class LiveRetailer(val title: String, val stationary: RetailerPolicy.Retailer? = null) {
    ALDI_NORD("ALDI Nord", RetailerPolicy.Retailer.ALDI_NORD), ALDI_SUED("ALDI Süd", RetailerPolicy.Retailer.ALDI_SUED),
    LIDL("Lidl", RetailerPolicy.Retailer.LIDL), REWE("REWE", RetailerPolicy.Retailer.REWE),
    PENNY("PENNY", RetailerPolicy.Retailer.PENNY), KAUFLAND("Kaufland", RetailerPolicy.Retailer.KAUFLAND),
    NETTO_MD("Netto Marken-Discount", RetailerPolicy.Retailer.NETTO_MD), EDEKA("EDEKA", RetailerPolicy.Retailer.EDEKA),
    KNUSPR("Knuspr")
}

internal data class LiveOfferScope(val retailer: LiveRetailer, val storeId: String?, val regionId: String?,
    val deliveryAreaId: String?, val purchaseOn: LocalDate) {
    fun validate() {
        require(listOfNotNull(storeId, regionId, deliveryAreaId).all { it.isNotBlank() && it.length <= 160 })
        if (retailer == LiveRetailer.KNUSPR) require(storeId == null && regionId == null) { "Knuspr-Liefergebiet ist keine stationäre Filiale oder Verkaufsregion." }
        else require(deliveryAreaId == null) { "Stationäre Filiale und Liefergebiet getrennt wählen." }
    }
}

internal enum class LiveSourceStatus { RIGHTS_UNVERIFIED, RIGHTS_RESTRICTED, CONNECTION_REQUIRED, READY, BLOCKED, DEFERRED }
internal data class LiveSourceInfo(val retailer: LiveRetailer, val sourceId: String, val status: LiveSourceStatus,
    val evidenceRef: String, val explanation: String)

/** Source states are configuration diagnostics, not universal licence requirements.
 * A real source/branch profile is still required for HTTP. GET success cannot create one. */
internal object LiveSourceCatalogue {
    const val SOURCE_EVIDENCE = "https://github.com/ecora2003-max/EG-Preisdaten/blob/5c52bf708a3bfa91e5bfd309bbddc308b3d6766a/docs/SOURCES.md"
    val sources = LiveRetailer.entries.map { retailer -> when (retailer) {
        LiveRetailer.KNUSPR -> LiveSourceInfo(retailer, "knuspr-official-mcp", LiveSourceStatus.DEFERRED,
            "https://www.knuspr.de/mcp-docs", "Für später zurückgestellt. Zuerst werden die acht stationären Händler angeschlossen. Eine vorhandene Anmeldung bleibt erhalten; es wird kein automatischer Knuspr-Abruf gestartet.")
        LiveRetailer.EDEKA -> LiveSourceInfo(retailer, "edeka-published-offers", LiveSourceStatus.BLOCKED,
            SOURCE_EVIDENCE, "Noch kein konkretes EDEKA-Quellen-/Filialprofil hinterlegt. Frühere Einschränkungen quellenbezogen beachten; keine pauschale Händlergenehmigung als universelle Voraussetzung.")
        else -> LiveSourceInfo(retailer, "${retailer.name.lowercase()}-published-offers", LiveSourceStatus.BLOCKED,
            SOURCE_EVIDENCE, "Noch kein konkretes Quellen-/Filialprofil hinterlegt. Eine ausdrückliche Händlergenehmigung ist keine pauschale technische Voraussetzung; ein realer zulässiger Endpoint und korrekter Filialbezug fehlen noch.")
    } }
    /** Browser handoff only, with no appended shopping/profile/location data.
     * This does not authorize scraping, cache or display inside this app. */
    fun officialWebsite(retailer: LiveRetailer): URI? = when (retailer) {
        LiveRetailer.ALDI_NORD -> "https://www.aldi-nord.de/"
        LiveRetailer.ALDI_SUED -> "https://www.aldi-sued.de/"
        LiveRetailer.LIDL -> "https://www.lidl.de/"
        LiveRetailer.REWE -> "https://www.rewe.de/"
        LiveRetailer.PENNY -> "https://www.penny.de/"
        LiveRetailer.KAUFLAND -> "https://filiale.kaufland.de/"
        LiveRetailer.NETTO_MD -> "https://www.netto-online.de/"
        LiveRetailer.EDEKA -> "https://www.edeka.de/"
        LiveRetailer.KNUSPR -> null
    }?.let(::URI)
}

internal enum class LiveOfferKind { PROMOTION, REGULAR, UNKNOWN }
internal enum class LiveOfferApplicability { STORE_CONFIRMED, REGION_CONFIRMED, CHAIN_REPORTED, DELIVERY_CONFIRMED, UNKNOWN }
internal enum class OfferMassUnit(val label: String) { GRAM("g"), MILLILITRE("ml") }
internal enum class OfferQuantityBasis { NET, DRAINED, GROSS }
internal data class LiveOffer(val id: String, val sourceId: String, val retailer: LiveRetailer, val title: String,
    val sourceProductId: String, val gtin: String?, val merchandiseCents: Long?, val depositCents: Long?,
    val quantityMilli: Long?, val unit: OfferMassUnit?, val quantityBasis: OfferQuantityBasis?, val kind: LiveOfferKind,
    val validFrom: LocalDate?, val validUntil: LocalDate?, val sourceObservedAt: Instant?, val fetchedAt: Instant,
    val storeId: String?, val regionId: String?, val deliveryAreaId: String?, val applicability: LiveOfferApplicability,
    val conditions: String?, val conditionsConfirmed: Boolean, val sourceUrl: String, val packageDescription: String? = null,
    val priceDetails: LivePriceDetails? = null) {
    init {
        require(id.isNotBlank() && sourceId.isNotBlank() && sourceProductId.isNotBlank() && title.isNotBlank() && title.length <= 1000)
        require(merchandiseCents == null || merchandiseCents in 0L..1_000_000_000_000L)
        require(depositCents == null || depositCents in 0L..1_000_000_000_000L)
        require(quantityMilli == null || quantityMilli in 1L..1_000_000_000_000L)
        require(validFrom == null || validUntil == null || validFrom <= validUntil)
        require(fetchedAt.toEpochMilli() >= 0L && (sourceObservedAt == null || sourceObservedAt <= fetchedAt))
        require(URI(sourceUrl).let { it.scheme == "https" && it.host != null && it.rawUserInfo == null && it.fragment == null })
    }
    val checkoutCents: Long? get() = if (merchandiseCents == null || depositCents == null) null else Math.addExact(merchandiseCents, depositCents)
    /** Display rounding only. Stored money remains integer cents; no mass/volume conversion. */
    fun unitPriceText(): String? {
        val cents = merchandiseCents ?: return null; val amount = quantityMilli ?: return null; val basis = unit ?: return null
        if (quantityBasis == null) return null
        val numerator = BigInteger.valueOf(cents).multiply(BigInteger.valueOf(1_000_000L))
        return BigDecimal(numerator).divide(BigDecimal.valueOf(amount).multiply(BigDecimal(100)), 2, RoundingMode.HALF_UP)
            .toPlainString() + if (basis == OfferMassUnit.GRAM) " €/kg" else " €/l"
    }
    fun applicable(scope: LiveOfferScope): Boolean = retailer == scope.retailer &&
        when (applicability) {
            LiveOfferApplicability.STORE_CONFIRMED -> storeId != null && storeId == scope.storeId
            LiveOfferApplicability.REGION_CONFIRMED -> regionId != null && regionId == scope.regionId
            LiveOfferApplicability.DELIVERY_CONFIRMED -> deliveryAreaId != null && deliveryAreaId == scope.deliveryAreaId
            else -> false
        } &&
        (storeId == null || storeId == scope.storeId) && (regionId == null || regionId == scope.regionId) &&
        (deliveryAreaId == null || deliveryAreaId == scope.deliveryAreaId) &&
        validFrom != null && validUntil != null && scope.purchaseOn in validFrom..validUntil
}

/** These facts come from an independent host registry, never the HTTP result. */
internal data class LiveSourceGrant(val sourceId: String, val retailer: LiveRetailer, val evidenceRef: String,
    val permittedFrom: LocalDate, val permittedUntil: LocalDate?, val allowedHosts: Set<String>,
    val cachePermitted: Boolean, val minimumRefreshSeconds: Long?, val verifiedScope: LiveOfferScope,
    val capability: RetailerPolicy.Capability = RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION,
    val hostProfileFingerprint: String? = null) {
    init { require(minimumRefreshSeconds == null || minimumRefreshSeconds > 0L) }
    /** The host registry may replace or mutate its input collection. A loaded
     * snapshot must keep the permission that actually authorized its read. */
    fun snapshot(): LiveSourceGrant = copy(allowedHosts = java.util.Collections.unmodifiableSet(HashSet(allowedHosts)))
    fun permits(scope: LiveOfferScope, endpoint: URI, at: Instant): Boolean {
        val day = at.atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
        return sourceId.isNotBlank() && evidenceRef.isNotBlank() && scope == verifiedScope && retailer == scope.retailer &&
            day >= permittedFrom && (permittedUntil == null || day <= permittedUntil) &&
            scope.purchaseOn >= permittedFrom && (permittedUntil == null || scope.purchaseOn <= permittedUntil) &&
            endpoint.scheme == "https" && endpoint.rawUserInfo == null && endpoint.fragment == null &&
            endpoint.host?.lowercase() in allowedHosts && endpoint.port in setOf(-1, 443)
    }
}
internal fun interface LivePermissionRegistry { fun grant(scope: LiveOfferScope): LiveSourceGrant? }
internal data class LiveOfferBatch(val sourceId: String, val scope: LiveOfferScope, val fetchedAt: Instant,
    val offers: List<LiveOffer>, val coverageComplete: Boolean = false)
internal interface AuthorizedOfferSource {
    val endpoint: URI
    val endpoints: Set<URI> get() = setOf(endpoint)
    val capability: RetailerPolicy.Capability get() = RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION
    suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch
}
/** Resolve the reviewed endpoint for the selected branch, region and day.
 * A retailer can have several independent branch profiles. */
internal fun interface LiveOfferSourceResolver { fun source(scope: LiveOfferScope): AuthorizedOfferSource? }
internal enum class LiveRefreshStatus { BLOCKED, LOADED, FAILED, THROTTLED }
internal data class LiveRefreshResult(val status: LiveRefreshStatus, val batch: LiveOfferBatch? = null, val code: String? = null,
    val cachePermitted: Boolean = false, val minimumRefreshSeconds: Long? = null,
    val fromCache: Boolean = false, val refreshAfter: Instant? = null,
    val authorization: LiveSourceGrant? = null)
internal enum class OfferSourceFailureCode { LOGIN_REQUIRED, ACCESS_BLOCKED, RATE_LIMITED, NETWORK_UNAVAILABLE, UNSUPPORTED_SOURCE_SCHEMA }
internal class OfferSourceFailure(val code: OfferSourceFailureCode) : Exception(code.name)

internal data class OfferRefreshAttempt(val at: Instant, val automaticPaused: Boolean)
internal interface OfferRefreshJournal {
    fun last(sourceId: String): OfferRefreshAttempt?
    fun record(sourceId: String, attempt: OfferRefreshAttempt)
}
internal class MemoryOfferRefreshJournal : OfferRefreshJournal {
    private val attempts = mutableMapOf<String, OfferRefreshAttempt>()
    override fun last(sourceId: String) = attempts[sourceId]
    override fun record(sourceId: String, attempt: OfferRefreshAttempt) { attempts[sourceId] = attempt }
}

/** No Room/actual writes. Both capability and permission are checked BEFORE I/O.
 * The adapter cannot bless its own rights, scope or stale cached data. */
internal class LiveOfferRuntime(private val sources: LiveOfferSourceResolver,
    private val permissions: LivePermissionRegistry = LivePermissionRegistry { null },
    private val journal: OfferRefreshJournal = MemoryOfferRefreshJournal(),
    private val now: () -> Instant = Instant::now) {
    constructor(sources: Map<LiveRetailer, AuthorizedOfferSource>,
        permissions: LivePermissionRegistry = LivePermissionRegistry { null },
        journal: OfferRefreshJournal = MemoryOfferRefreshJournal(), now: () -> Instant = Instant::now) :
        this(LiveOfferSourceResolver { sources[it.retailer] }, permissions, journal, now)
    private val mutex = Mutex()
    private var cached: Pair<LiveSourceGrant, LiveOfferBatch>? = null
    fun policy(scope: LiveOfferScope): LiveSourceGrant? = try {
        scope.validate()
        val source = sources.source(scope)
        val grant = permissions.grant(scope)?.snapshot()
        grant?.takeIf { source != null && it.capability == source.capability &&
            it.capability != RetailerPolicy.Capability.PUBLISHED_OFFER_CATALOG_DISCOVERY &&
            scope.retailer.stationary?.let { retailer -> RetailerPolicy.supports(retailer, it.capability) } != false &&
            source.endpoints.isNotEmpty() && source.endpoint in source.endpoints &&
            source.endpoints.all { uri -> it.permits(scope, uri, now()) } }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    /** Source ID alone cannot renew a previous observation's permissions. */
    fun permitsSnapshot(scope: LiveOfferScope, sourceId: String, authorization: LiveSourceGrant?): Boolean =
        authorization != null && authorization.sourceId == sourceId && authorization.verifiedScope == scope &&
            policy(scope) == authorization

    /** Foreground refreshes share one in-flight read and the same source floor.
     * A changed scope never evades the source's refresh interval. Cached data is
     * session-only, explicitly stale, and reauthorized on every use. */
    suspend fun refresh(scope: LiveOfferScope, automatic: Boolean = false): LiveRefreshResult = mutex.withLock {
        refreshLocked(scope, automatic)
    }
    private suspend fun refreshLocked(scope: LiveOfferScope, automatic: Boolean): LiveRefreshResult {
        scope.validate()
        val source = try { sources.source(scope) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "SOURCE_CONFIGURATION_INVALID") }
            ?: return LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "SOURCE_NOT_CONFIGURED")
        if (source.capability == RetailerPolicy.Capability.PUBLISHED_OFFER_CATALOG_DISCOVERY ||
            scope.retailer.stationary?.let { !RetailerPolicy.supports(it, source.capability) } == true)
            return LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "CAPABILITY_NOT_PERMITTED")
        val grant = try { permissions.grant(scope)?.snapshot() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } ?: return LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "APP_DISPLAY_UNVERIFIED")
        val at = now()
        if (grant.capability != source.capability || source.endpoints.isEmpty() || source.endpoint !in source.endpoints ||
            source.endpoints.any { !grant.permits(scope, it, at) })
            return LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "SOURCE_SCOPE_OR_PERMISSION_CHANGED")
        val previous = try { journal.last(grant.sourceId) } catch (_: Exception) {
            return LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "REFRESH_JOURNAL_UNAVAILABLE")
        }
        if (automatic && (grant.minimumRefreshSeconds == null || previous?.automaticPaused == true))
            return LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "AUTOMATIC_REFRESH_NOT_PERMITTED")
        // An expired/revoked/differently scoped grant cannot revive old prices.
        if (cached?.first != grant || cached?.second?.scope != scope) cached = null
        val next = previous?.at?.let { last -> grant.minimumRefreshSeconds?.let(last::plusSeconds) }
        if (next != null && at < next) return LiveRefreshResult(LiveRefreshStatus.THROTTLED,
            batch = cached?.takeIf { grant.cachePermitted }?.second, code = "REFRESH_INTERVAL",
            cachePermitted = grant.cachePermitted, minimumRefreshSeconds = grant.minimumRefreshSeconds,
            fromCache = cached != null && grant.cachePermitted, refreshAfter = next,
            authorization = cached?.first)
        // Durable before IO. A crash, cancellation or provider error pauses
        // automatic reads until another explicit successful manual read.
        try { journal.record(grant.sourceId, OfferRefreshAttempt(at, automaticPaused = true)) }
        catch (_: Exception) { return LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "REFRESH_JOURNAL_UNAVAILABLE") }
        return try {
            val data = source.fetch(scope)
            require(data.scope == scope && data.sourceId == grant.sourceId && data.fetchedAt <= now() && data.offers.size <= 256)
            require(data.offers.map { it.id }.distinct().size == data.offers.size)
            require(data.offers.all { it.sourceId == grant.sourceId && it.retailer == scope.retailer && it.fetchedAt == data.fetchedAt &&
                (source.capability != RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION || it.kind != LiveOfferKind.REGULAR) &&
                URI(it.sourceUrl).host?.lowercase() in grant.allowedHosts &&
                (it.storeId == null || it.storeId == scope.storeId) && (it.regionId == null || it.regionId == scope.regionId) &&
                (it.deliveryAreaId == null || it.deliveryAreaId == scope.deliveryAreaId) })
            if (policy(scope) != grant)
                LiveRefreshResult(LiveRefreshStatus.BLOCKED, code = "SOURCE_SCOPE_OR_PERMISSION_CHANGED")
            else {
                val accepted = data.copy(coverageComplete = false)
                cached = if (grant.cachePermitted) grant to accepted else null
                journal.record(grant.sourceId, OfferRefreshAttempt(at, automaticPaused = false))
                LiveRefreshResult(LiveRefreshStatus.LOADED, accepted,
                    cachePermitted = grant.cachePermitted, minimumRefreshSeconds = grant.minimumRefreshSeconds,
                    refreshAfter = grant.minimumRefreshSeconds?.let(at::plusSeconds), authorization = grant)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: OfferSourceFailure) {
            failed(scope, grant, failure.code.name)
        }
        catch (_: Exception) {
            failed(scope, grant, "SOURCE_READ_OR_CONTRACT_FAILED")
        }
    }
    private fun failed(scope: LiveOfferScope, grant: LiveSourceGrant, code: String): LiveRefreshResult {
        val stillAuthorized = policy(scope) == grant
        val old = cached?.takeIf { stillAuthorized && grant.cachePermitted && it.first == grant && it.second.scope == scope }?.second
        if (!stillAuthorized) cached = null
        return LiveRefreshResult(LiveRefreshStatus.FAILED, old, code, grant.cachePermitted,
            grant.minimumRefreshSeconds, fromCache = old != null, authorization = grant.takeIf { old != null })
    }
}
