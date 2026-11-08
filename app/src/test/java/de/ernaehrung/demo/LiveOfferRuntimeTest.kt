package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveOfferRuntimeTest {
    private val at = Instant.parse("2026-11-08T10:00:00Z")
    private val day = LocalDate.parse("2026-11-08")
    private val scope = LiveOfferScope(LiveRetailer.LIDL, "DE123", "region", null, day)
    private val grant get() = LiveSourceGrant("synthetic", LiveRetailer.LIDL, "synthetic-independent-host-evidence", day,
        day.plusDays(1), setOf("feed.example.test"), false, 3600, scope)
    private fun offer() = LiveOffer("synthetic:one", "synthetic", LiveRetailer.LIDL, "Synthetischer Artikel", "one", null,
        199, null, 500_000, OfferMassUnit.GRAM, OfferQuantityBasis.NET, LiveOfferKind.PROMOTION,
        day, day, at, at, "DE123", "region", null, LiveOfferApplicability.STORE_CONFIRMED, null, false, "https://feed.example.test/offers")
    private class Source(val action: suspend () -> LiveOfferBatch) : AuthorizedOfferSource {
        override val endpoint = URI("https://feed.example.test/offers")
        var calls = 0
        override suspend fun fetch(scope: LiveOfferScope): LiveOfferBatch { calls++; return action() }
    }
    @Test fun missingOrBrokenIndependentPermissionDoesNotMakeAnySupplierCall() = runBlocking {
        val source = Source { error("Must not fetch") }
        for (registry in listOf(LivePermissionRegistry { null }, LivePermissionRegistry { error("Registry unavailable") })) {
            val result = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), registry) { at }.refresh(scope)
            assertEquals(LiveRefreshStatus.BLOCKED, result.status); assertEquals(0, source.calls)
        }
    }
    @Test fun sourceCannotAssertItsOwnScopePermissionOrMarketCoverageAndRevocationDuringFetchWins() = runBlocking {
        var approved: LiveSourceGrant? = grant
        val source = Source { approved = null; LiveOfferBatch("synthetic", scope, at, listOf(offer()), true) }
        val result = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { approved }) { at }.refresh(scope)
        assertEquals(LiveRefreshStatus.BLOCKED, result.status); assertNull(result.batch); assertEquals(1, source.calls)
    }
    @Test fun authorizedBoundedSnapshotKeepsOriginalValuesButDoesNotClaimMarketCompleteness() = runBlocking {
        val row = offer()
        val source = Source { LiveOfferBatch("synthetic", scope, at, listOf(row), true) }
        val result = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { grant }) { at }.refresh(scope)
        assertEquals(LiveRefreshStatus.LOADED, result.status); assertEquals(row, result.batch!!.offers.single())
        assertFalse(result.batch.coverageComplete); assertFalse(result.cachePermitted); assertEquals(3600L, result.minimumRefreshSeconds)
    }
    @Test fun wrongStoreDuplicateIdentityForeignSourceAndFutureCaptureFailClosed() = runBlocking {
        val cases = listOf(listOf(offer().copy(storeId = "DE999")), listOf(offer(), offer()),
            listOf(offer().copy(sourceUrl = "https://other.example.test/offers")))
        for (rows in cases) {
            val source = Source { LiveOfferBatch("synthetic", scope, at, rows) }
            assertEquals(LiveRefreshStatus.FAILED, LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { grant }) { at }.refresh(scope).status)
        }
        val future = Source { LiveOfferBatch("synthetic", scope, at.plusSeconds(1), emptyList()) }
        assertEquals(LiveRefreshStatus.FAILED, LiveOfferRuntime(mapOf(LiveRetailer.LIDL to future), LivePermissionRegistry { grant }) { at }.refresh(scope).status)
    }
    @Test fun nullAndZeroDepositMerchandiseQuantityAndBasisRemainDistinctWithSingleRounding() {
        assertNull(offer().checkoutCents); assertEquals(199L, offer().copy(depositCents = 0).checkoutCents)
        assertNull(offer().copy(merchandiseCents = null).unitPriceText())
        assertNull(offer().copy(quantityBasis = null).unitPriceText())
        assertEquals("0.00 €/kg", offer().copy(merchandiseCents = 0).unitPriceText())
        assertEquals("1.23 €/kg", offer().copy(merchandiseCents = 123499, quantityMilli = 1_000_000_000).unitPriceText())
        assertNull(OfferJson.euroCents("ab 1,99")); assertEquals(0L, OfferJson.euroCents("0,00 €"))
        assertNull(OfferJson.euroCents(true)); assertNull(OfferJson.euroCents(null))
    }
    @Test fun chainOrNationalFallbackIsNotAConfirmedStoreQuoteAndDeliveryIsNotAStore() {
        assertTrue(offer().applicable(scope))
        assertFalse(offer().copy(storeId = null, applicability = LiveOfferApplicability.CHAIN_REPORTED).applicable(scope))
        assertFalse(offer().copy(applicability = LiveOfferApplicability.UNKNOWN).applicable(scope))
        assertTrue(runCatching { scope.copy(retailer = LiveRetailer.KNUSPR).validate() }.isFailure)
    }
    @Test fun sourceAdapterRetainsUnknownDepositConditionsAndNoNationalStorePromotion() {
        val edeka = PublishedOfferDecoders.edeka("""{"national":true,"gueltig_von":"2026-11-08","gueltig_bis":"2026-11-09","docs":[{"angebotid":"one","titel":"Synthetisch","preis":"0,00"}]}""",
            "synthetic-edeka", "123456", at, "https://edeka.example.test/offers").single()
        assertEquals(0L, edeka.merchandiseCents); assertNull(edeka.depositCents); assertNull(edeka.quantityMilli)
        assertEquals(LiveOfferApplicability.CHAIN_REPORTED, edeka.applicability); assertNull(edeka.storeId)
        assertFalse(edeka.conditionsConfirmed)
    }
    @Test fun cancellationIsNeverReportedAsAnEmptySuccessfulMarket() = runBlocking {
        val source = Source { throw CancellationException("synthetic cancellation") }
        try { LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { grant }) { at }.refresh(scope); fail("Must cancel") }
        catch (_: CancellationException) { assertEquals(1, source.calls) }
    }
    @Test fun manualAndAutomaticReadsShareDurableSourceIntervalAcrossNewRuntimeAndScope() = runBlocking {
        var clock = at
        val journal = MemoryOfferRefreshJournal()
        val source = Source { LiveOfferBatch("synthetic", scope, clock, listOf(offer().copy(fetchedAt = clock))) }
        fun runtime() = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { grant }, journal) { clock }
        assertEquals(LiveRefreshStatus.LOADED, runtime().refresh(scope).status)
        clock = at.plusSeconds(3599)
        assertEquals(LiveRefreshStatus.THROTTLED, runtime().refresh(scope, automatic = true).status)
        assertEquals(LiveRefreshStatus.THROTTLED, runtime().refresh(scope).status)
        assertEquals(1, source.calls)
        clock = at.plusSeconds(3600)
        assertEquals(LiveRefreshStatus.LOADED, runtime().refresh(scope, automatic = true).status)
        assertEquals(2, source.calls)
    }
    @Test fun failurePausesAutomaticReadsEvenAfterRestartAndOfflineCacheRequiresTheSameStillValidGrant() = runBlocking {
        var clock = at; var broken = false; var current: LiveSourceGrant? = grant.copy(cachePermitted = true)
        val journal = MemoryOfferRefreshJournal()
        val source = Source {
            if (broken) throw OfferSourceFailure(OfferSourceFailureCode.ACCESS_BLOCKED)
            LiveOfferBatch("synthetic", scope, clock, listOf(offer().copy(fetchedAt = clock)))
        }
        fun runtime() = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { current }, journal) { clock }
        val session = runtime()
        session.refresh(scope); broken = true; clock = at.plusSeconds(3600)
        val failed = session.refresh(scope, automatic = true)
        assertEquals(LiveRefreshStatus.FAILED, failed.status); assertTrue(failed.fromCache)
        assertEquals(at, failed.batch!!.fetchedAt)
        clock = at.plusSeconds(7200)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime().refresh(scope, automatic = true).status)
        assertEquals(2, source.calls)
        current = null
        assertNull(session.refresh(scope).batch); assertEquals(2, source.calls)
    }
    @Test fun automaticReadWithoutVerifiedRhythmAndUnavailableJournalNeverCallSource() = runBlocking {
        val source = Source { error("No network allowed") }
        val noRhythm = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { grant.copy(minimumRefreshSeconds = null) }) { at }
        assertEquals(LiveRefreshStatus.BLOCKED, noRhythm.refresh(scope, automatic = true).status)
        val brokenJournal = object : OfferRefreshJournal {
            override fun last(sourceId: String): OfferRefreshAttempt? = error("Disk unavailable")
            override fun record(sourceId: String, attempt: OfferRefreshAttempt) = error("Disk unavailable")
        }
        val noJournal = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { grant }, brokenJournal) { at }
        assertEquals(LiveRefreshStatus.BLOCKED, noJournal.refresh(scope).status); assertEquals(0, source.calls)
    }
    @Test fun authorizedConcreteHttpReadPreservesSourceScopeZeroUnknownsAndRejectsUnexpectedHtml() = runBlocking {
        var calls = 0; var html = false
        val http = OfferHttp {
            calls++
            OfferHttpReply(200, mapOf("content-type" to if (html) "text/html" else "application/json; charset=utf-8"),
                """{"offers":[{"id":"one","title":"Synthetisch","offerType":"StoreSpecialPriceDiscount","category":"Store","priceBox":{"largePartNumeric":0},"startValidityDate":"2026-11-08","endValidityDate":"2026-11-09"}]}""")
        }
        val source = PublishedOfferHttpSource(URI("https://feed.example.test/offers"), "synthetic", scope,
            PublishedOfferHttpSource.Format.LIDL_STORE_V4, http) { at }
        val runtime = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { grant.copy(minimumRefreshSeconds = null) }) { at }
        val result = runtime.refresh(scope)
        assertEquals(0L, result.batch!!.offers.single().merchandiseCents)
        assertNull(result.batch.offers.single().depositCents)
        html = true; assertEquals(LiveRefreshStatus.FAILED, runtime.refresh(scope).status); assertEquals(2, calls)
    }
}
