package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RetailerPolicy
import de.ernaehrung.demo.data.offers.*
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PublishedSourceHttpScopeTest {
    private data class Case(val retailer: LiveRetailer, val uri: URI, val format: PublishedOfferHttpSource.Format,
        val file: String, val store: String)
    private val at = Instant.parse("2026-11-09T10:00:00Z")
    private val day = LocalDate.parse("2026-11-09")
    private fun fixture(file: String) = javaClass.getResourceAsStream("/published_sources/$file")!!.bufferedReader().use { it.readText() }
    private val rewe = URI("https://www.rewe.de/api/stationary-product-search/products?query=Synthetisch&wwIdent=000001")
    private val aldi = URI("https://api.aldi-sued.de/v3/product-search?q=Synthetisch&servicePoint=B000&serviceType=walk-in&limit=12&offset=0&currency=EUR")

    @Test fun targetedCatalogueReadsNeedTheirOwnCapabilityAndIndependentGrantBeforeAnyRequest(): Unit = runBlocking {
        for ((retailer, uri, format, file, store) in listOf(
            Case(LiveRetailer.REWE, rewe, PublishedOfferHttpSource.Format.REWE_STATIONARY, "rewe.json", "000001"),
            Case(LiveRetailer.ALDI_SUED, aldi, PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN, "aldi_sued.json", "B000"))) {
            val selected = LiveOfferScope(retailer, store, null, null, day)
            var calls = 0
            val source = PublishedOfferHttpSource(uri, "synthetic", selected, format,
                OfferHttp { request ->
                    calls++; assertEquals(uri, request.uri); assertEquals("GET", request.method); assertNull(request.body)
                    OfferHttpReply(200, mapOf("content-type" to "application/json"), fixture(file))
                }) { at }
            val grant = LiveSourceGrant("synthetic", retailer, "synthetic-independent-host-only", day, day,
                setOf(uri.host), true, null, selected, source.capability)
            assertEquals(RetailerPolicy.Capability.TARGETED_PRODUCT_PRICE_QUERY, source.capability)
            assertEquals(LiveRefreshStatus.BLOCKED, LiveOfferRuntime(mapOf(retailer to source)).refresh(selected).status)
            assertEquals(LiveRefreshStatus.BLOCKED, LiveOfferRuntime(mapOf(retailer to source), LivePermissionRegistry {
                grant.copy(capability = RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION)
            }) { at }.refresh(selected).status)
            assertEquals(0, calls)
            val result = LiveOfferRuntime(mapOf(retailer to source), LivePermissionRegistry { grant }) { at }.refresh(selected)
            assertEquals(LiveRefreshStatus.LOADED, result.status); assertEquals(1, calls)
            assertEquals(4, result.batch!!.offers.size); assertFalse(result.batch.coverageComplete)
        }
    }
    @Test fun configuredSearchCannotChangeStoreUseDiscoveryOrPaginateOrReachWishlistActions() {
        val http = OfferHttp { error("Invalid constructor must not perform IO") }
        val reweScope = LiveOfferScope(LiveRetailer.REWE, "000001", null, null, day)
        val aldiScope = LiveOfferScope(LiveRetailer.ALDI_SUED, "B000", null, null, day)
        val invalid = listOf(
            Triple(rewe.toString().replace("wwIdent=000001", "wwIdent=000002"), reweScope, PublishedOfferHttpSource.Format.REWE_STATIONARY),
            Triple(rewe.toString() + "&wwIdent=000001", reweScope, PublishedOfferHttpSource.Format.REWE_STATIONARY),
            Triple(rewe.toString().replace("query=Synthetisch", "query="), reweScope, PublishedOfferHttpSource.Format.REWE_STATIONARY),
            Triple(aldi.toString().replace("walk-in", "delivery"), aldiScope, PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN),
            Triple(aldi.toString().replace("offset=0", "offset=12"), aldiScope, PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN),
            Triple(aldi.toString().replace("limit=12", "limit=4000"), aldiScope, PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN),
            Triple(aldi.toString().replace("q=Synthetisch", "q="), aldiScope, PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN),
            Triple("https://www.netto-online.de/INTERSHOP/ViewMMPWishlist-AddStoreArticle?SKU=one",
                LiveOfferScope(LiveRetailer.NETTO_MD, null, null, null, day), PublishedOfferHttpSource.Format.NETTO_BRANCH_HTML))
        invalid.forEach { (url, scope, format) -> assertTrue(url,
            runCatching { PublishedOfferHttpSource(URI(url), "synthetic", scope, format, http) { at } }.isFailure) }
    }
    @Test fun authorizedNettoReadsOnlyCategoryHtmlAndKeepsAllowedStaleCopyAfterAccessFailure(): Unit = runBlocking {
        val uri = URI("https://www.netto-online.de/angebote/c-N07")
        val scope = LiveOfferScope(LiveRetailer.NETTO_MD, null, null, null, day)
        val requests = mutableListOf<OfferHttpRequest>(); var fail = false
        val source = PublishedOfferHttpSource(uri, "synthetic", scope, PublishedOfferHttpSource.Format.NETTO_BRANCH_HTML,
            OfferHttp { request -> requests += request
                if (fail) OfferHttpReply(403, emptyMap(), "blocked")
                else OfferHttpReply(200, mapOf("content-type" to "text/html; charset=UTF-8"), fixture("netto.html"))
            }) { at }
        val grant = LiveSourceGrant("synthetic", LiveRetailer.NETTO_MD, "synthetic-independent-host-only", day, day,
            setOf(uri.host), true, null, scope)
        val runtime = LiveOfferRuntime(mapOf(LiveRetailer.NETTO_MD to source), LivePermissionRegistry { grant }) { at }
        val first = runtime.refresh(scope)
        assertEquals(LiveRefreshStatus.LOADED, first.status); assertEquals(2, first.batch!!.offers.size)
        fail = true
        val failed = runtime.refresh(scope)
        assertEquals(LiveRefreshStatus.FAILED, failed.status); assertEquals("ACCESS_BLOCKED", failed.code)
        assertTrue(failed.fromCache); assertEquals(first.batch, failed.batch)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(scope, automatic = true).status)
        assertEquals(2, requests.size)
        requests.forEach { assertEquals(uri, it.uri); assertEquals("GET", it.method); assertNull(it.body); assertEquals("text/html", it.headers["Accept"]) }
    }
}
