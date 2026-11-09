package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmbeddedPublishedOfferSourceTest {
    private val day = LocalDate.parse("2026-11-09")
    private val at = Instant.parse("2026-11-09T10:00:00Z")
    private fun fixture(file: String) = javaClass.getResourceAsStream("/published_sources/$file")!!.bufferedReader().use { it.readText() }
    private fun expected(kind: String, row: LiveOffer) {
        val e = requireNotNull(OfferJson.objectValue(OfferJson.read(fixture("embedded_reference_expected.json"))[kind]))
        assertEquals(e["id"], row.sourceProductId); assertEquals(OfferJson.integer(e["current"]), row.merchandiseCents)
        assertEquals(e["quantityText"], row.packageDescription)
        assertEquals(e["from"], row.validFrom?.toString()); assertEquals(e["until"], row.validUntil?.toString())
        assertEquals(e["scope"], row.applicability.name); assertEquals(e["store"], row.storeId)
        assertNull(row.quantityMilli); assertNull(row.depositCents); assertFalse(row.conditionsConfirmed)
    }
    @Test fun actualNordHtmlPassesTheExistingDecoderWithoutAnyScriptOrResourceRequest(): Unit = runBlocking {
        val endpoint = URI("https://www.aldi-nord.de/synthetic-next-offers")
        val scope = LiveOfferScope(LiveRetailer.ALDI_NORD, null, null, null, day)
        val requests = mutableListOf<OfferHttpRequest>()
        val source = PublishedOfferHttpSource(endpoint, "synthetic", scope, PublishedOfferHttpSource.Format.ALDI_NORD_NEXT_DATA,
            OfferHttp { requests += it; OfferHttpReply(200, mapOf("content-type" to "text/html; charset=UTF-8"), fixture("aldi_nord.html")) }) { at }
        val grant = LiveSourceGrant("synthetic", scope.retailer, "synthetic-independent-host-only", day, day,
            setOf(endpoint.host), true, null, scope)
        val result = LiveOfferRuntime(mapOf(scope.retailer to source), LivePermissionRegistry { grant }) { at }.refresh(scope)
        assertEquals(LiveRefreshStatus.LOADED, result.status)
        expected("aldi_nord", result.batch!!.offers.single())
        assertEquals(1, requests.size); assertEquals(endpoint, requests.single().uri)
        assertEquals("GET", requests.single().method); assertNull(requests.single().body)
        assertFalse(result.batch.coverageComplete)
    }
    @Test fun kauflandHtmlNeedsBothAuthorizedRepliesAndItsTimestampIncludesTheLaterStoreReply(): Unit = runBlocking {
        val endpoint = URI("https://filiale.kaufland.de/synthetic-offers")
        val availability = URI("https://filiale.kaufland.de/synthetic-store-availability")
        val scope = LiveOfferScope(LiveRetailer.KAUFLAND, "DE1234", null, null, day)
        var clock = at; var failStore = false
        val requests = mutableListOf<URI>()
        val source = PublishedOfferHttpSource(endpoint, "synthetic", scope, PublishedOfferHttpSource.Format.KAUFLAND_STORE,
            OfferHttp { request ->
                clock = clock.plusSeconds(1); requests += request.uri
                when (request.uri) {
                    endpoint -> OfferHttpReply(200, mapOf("content-type" to "text/html"),
                        fixture("kaufland.html").let { if (failStore) it.replace("1,99", "2,99") else it })
                    availability -> if (failStore) OfferHttpReply(503, emptyMap(), "synthetic store error")
                        else OfferHttpReply(200, mapOf("content-type" to "application/json"), fixture("kaufland_availability.json"))
                    else -> error("No other request allowed")
                }
            }, availabilityEndpoint = availability, now = { clock })
        val grant = LiveSourceGrant("synthetic", scope.retailer, "synthetic-independent-host-only", day, day,
            setOf(endpoint.host), true, null, scope)
        val runtime = LiveOfferRuntime(mapOf(scope.retailer to source), LivePermissionRegistry { grant }) { clock }
        val first = runtime.refresh(scope)
        assertEquals(LiveRefreshStatus.LOADED, first.status)
        expected("kaufland", first.batch!!.offers.single())
        assertEquals("Synthetisches Kaufland-Angebot } {", first.batch.offers.single().title)
        assertEquals(at.plusSeconds(2), first.batch.fetchedAt)
        assertEquals(first.batch.fetchedAt, first.batch.offers.single().fetchedAt)
        assertEquals(listOf(endpoint, availability), requests)
        failStore = true
        val failed = runtime.refresh(scope)
        assertEquals(LiveRefreshStatus.FAILED, failed.status); assertTrue(failed.fromCache)
        assertEquals(first.batch, failed.batch); assertEquals(199L, failed.batch!!.offers.single().merchandiseCents)
    }
    @Test fun missingOrAmbiguousScriptsAndUnapprovedSecondEndpointCannotProducePartialPrices(): Unit = runBlocking {
        assertTrue(runCatching { EmbeddedPublishedOfferJson.aldiNord("<html>Missing script</html>") }.isFailure)
        assertTrue(runCatching { EmbeddedPublishedOfferJson.aldiNord(fixture("aldi_nord.html") + fixture("aldi_nord.html")) }.isFailure)
        assertTrue(runCatching { EmbeddedPublishedOfferJson.kaufland("<script>window.x = {};</script>") }.isFailure)
        assertTrue(runCatching { EmbeddedPublishedOfferJson.kaufland(fixture("kaufland.html") + fixture("kaufland.html")) }.isFailure)
        val attribute = fixture("kaufland.html").replace("<script>window.syntheticPage = ", "<div data-state='")
            .replace(";</script>", "'></div>")
        assertEquals(EmbeddedPublishedOfferJson.kaufland(fixture("kaufland.html")), EmbeddedPublishedOfferJson.kaufland(attribute))
        val endpoint = URI("https://filiale.kaufland.de/synthetic-offers")
        val extra = URI("https://unapproved.example.test/store")
        val scope = LiveOfferScope(LiveRetailer.KAUFLAND, "DE1234", null, null, day)
        var calls = 0
        val source = PublishedOfferHttpSource(endpoint, "synthetic", scope, PublishedOfferHttpSource.Format.KAUFLAND_STORE,
            OfferHttp { calls++; error("All source endpoints must be checked first") }, availabilityEndpoint = extra) { at }
        val grant = LiveSourceGrant("synthetic", scope.retailer, "synthetic-independent-host-only", day, day,
            setOf(endpoint.host), true, null, scope)
        val result = LiveOfferRuntime(mapOf(scope.retailer to source), LivePermissionRegistry { grant }) { at }.refresh(scope)
        assertEquals(LiveRefreshStatus.BLOCKED, result.status); assertNull(result.batch); assertEquals(0, calls)
    }
}
