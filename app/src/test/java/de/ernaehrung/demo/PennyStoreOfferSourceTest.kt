package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PennyStoreOfferSourceTest {
    private val day = LocalDate.parse("2026-11-17")
    private val at = Instant.parse("2026-11-17T12:00:00Z")
    private val scope = LiveOfferScope(LiveRetailer.PENNY, PennyPublicSourceFixtures.STORE, null, null, day)
    private fun reply(text: String) = OfferHttpReply(200, mapOf("content-type" to "application/json;charset=UTF-8"), text)
    @Test fun actualMarketRegionPrecedesOneWeekQueryAndPreservesUnknownValidityMoneyAndSourceDetails(): Unit = runBlocking {
        val requests = mutableListOf<OfferHttpRequest>(); var clock = at
        val directory = PennyPublicSourceFixtures.configuration(day)
        val runtime = directory.runtime(OfferHttp { request ->
            requests += request; clock = clock.plusSeconds(1)
            reply(if (requests.size == 1) PennyPublicSourceFixtures.markets() else PennyPublicSourceFixtures.offers())
        }, MemoryOfferRefreshJournal()) { clock }
        val result = runtime.refresh(scope)
        assertEquals(LiveRefreshStatus.LOADED, result.status); assertFalse(result.cachePermitted)
        assertEquals(listOf("https://www.penny.de/.rest/market", "https://www.penny.de/.rest/offers/by-category/2026-47/top-angebote?region=synthetic-current"), requests.map { it.uri.toString() })
        assertEquals(6_291_456, requests.first().maximumResponseBytes); assertEquals(1_048_576, requests.last().maximumResponseBytes)
        val rows = result.batch!!.offers; assertEquals(3, rows.size)
        assertEquals(159L, rows[0].merchandiseCents); assertEquals(0L, rows[1].merchandiseCents); assertNull(rows[2].merchandiseCents)
        assertTrue(rows.all { it.validFrom == null && it.validUntil == null && it.depositCents == null && it.quantityMilli == null && !it.conditionsConfirmed })
        assertTrue(rows.all { it.storeId == scope.storeId && it.regionId == null && it.applicability == LiveOfferApplicability.STORE_CONFIRMED })
        val proof = rows[0].storeEvidence!!; assertEquals("synthetic-current", proof.sourceRegionId)
        assertEquals(LocalDate.parse("2026-11-16"), proof.weekStartsOn)
        assertEquals(at.plusSeconds(1), proof.checkedAt); assertEquals(at.plusSeconds(2), result.batch!!.fetchedAt)
        assertEquals("uvp: 3.29", rows[0].priceDetails?.previousDisplay); assertNull(rows[0].priceDetails?.regularCents)
        assertEquals("https://www.penny.de/angebote/top-angebote/synthetic-first", rows[0].productPageUrl)
        assertFalse(rows[0].applicable(scope)) // Advertised week is not individual validity.
        val throttled = runtime.refresh(scope); assertEquals(LiveRefreshStatus.THROTTLED, throttled.status)
        assertNull(throttled.batch); assertEquals(2, requests.size)
    }
    @Test fun nextWeekNeedsItsOwnPublishedRegionAndNoOfferReadStartsWhenItIsMissing(): Unit = runBlocking {
        val calls = mutableListOf<OfferHttpRequest>()
        val directory = PennyPublicSourceFixtures.configuration(day)
        fun runtime(next: String) = directory.runtime(OfferHttp { calls += it; reply(PennyPublicSourceFixtures.markets(next)) }, MemoryOfferRefreshJournal()) { at }
        val result = runtime("").refresh(scope.copy(purchaseOn = LocalDate.parse("2026-11-23")))
        assertEquals("SCOPE_UNAVAILABLE", result.code); assertEquals(1, calls.size)
        calls.clear()
        val unrelatedWeek = runtime("synthetic-next").refresh(scope.copy(purchaseOn = day.minusWeeks(1)))
        assertEquals(LiveRefreshStatus.BLOCKED, unrelatedWeek.status); assertEquals(0, calls.size)
    }
    @Test fun independentlySelectedNextWeekUsesTheNextRegionRatherThanTodaysRegion(): Unit = runBlocking {
        val calls = mutableListOf<OfferHttpRequest>()
        val directory = PennyPublicSourceFixtures.configuration(day)
        val runtime = directory.runtime(OfferHttp { request ->
            calls += request; reply(if (calls.size == 1) PennyPublicSourceFixtures.markets() else PennyPublicSourceFixtures.offers())
        }, MemoryOfferRefreshJournal()) { at }
        // This fixture's host window covers seven days: Monday of next week is still in it.
        val result = runtime.refresh(scope.copy(purchaseOn = LocalDate.parse("2026-11-23")))
        assertEquals(LiveRefreshStatus.LOADED, result.status)
        assertTrue(calls.last().uri.toString().endsWith("2026-48/top-angebote?region=synthetic-next"))
    }
    @Test fun missingDuplicateOrMalformedCanonicalMarketNeverReachesThePriceEndpoint(): Unit = runBlocking {
        val good = PennyPublicSourceFixtures.markets()
        for (bad in listOf("[]", good.dropLast(1) + "," + good.drop(1), good.replace("synthetic-current", "invalid/region"))) {
            var count = 0
            val runtime = PennyPublicSourceFixtures.configuration(day).runtime(OfferHttp { count++; reply(bad) }, MemoryOfferRefreshJournal()) { at }
            assertEquals(LiveRefreshStatus.FAILED, runtime.refresh(scope).status); assertEquals(1, count)
        }
    }
    @Test fun source403And429StopTheReadAndCannotProduceAnInventedEmptySuccessfulList(): Unit = runBlocking {
        for ((status, code) in listOf(403 to "ACCESS_BLOCKED", 429 to "RATE_LIMITED")) {
            var calls = 0
            val runtime = PennyPublicSourceFixtures.configuration(day).runtime(OfferHttp { calls++; OfferHttpReply(status, emptyMap(), "") }, MemoryOfferRefreshJournal()) { at }
            val result = runtime.refresh(scope)
            assertEquals(LiveRefreshStatus.FAILED, result.status); assertEquals(code, result.code); assertNull(result.batch); assertEquals(1, calls)
        }
    }
}
