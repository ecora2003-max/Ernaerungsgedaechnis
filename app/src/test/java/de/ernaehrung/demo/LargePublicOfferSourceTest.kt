package de.ernaehrung.demo

import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.offers.*
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LargePublicOfferSourceTest {
    @Test fun realShapedNextWeekCollectionDecodesItsLargeEscapedEnvelopeAndRejectsAbove512Offers(): Unit = runBlocking {
        val at = Instant.parse("2026-11-17T12:00:00Z"); val day = LocalDate.parse("2026-11-23")
        val endpoint = URI("https://www.aldi-nord.de/angebote-vorschau.html")
        val scope = LiveOfferScope(LiveRetailer.ALDI_NORD, null, null, null, day)
        suspend fun read(count: Int, descriptionLength: Int): LiveRefreshResult {
            val products = (1..count).associate { index -> "synthetic-$index" to mapOf("objectID" to "synthetic-$index",
                "name" to "Synthetic published product $index", "description" to "x".repeat(descriptionLength),
                "currentPrice" to mapOf("priceValue" to "1.99",
                    "validFromLocalDate" to day.toString(), "validUntilLocalDate" to day.plusDays(5).toString())) }
            val apiData = JsonOutput.encode(listOf(listOf("OFFER_GET", mapOf(
                "req" to mapOf("week" to "next"), "res" to mapOf("algoliaDataMap" to products)))))
            if (descriptionLength > 0) assertTrue(apiData.length in 500_000..550_000)
            val body = JsonOutput.encode(mapOf("props" to mapOf("pageProps" to mapOf("apiData" to apiData))))
            val source = PublishedOfferHttpSource(endpoint, "synthetic-large-public", scope,
                PublishedOfferHttpSource.Format.ALDI_NORD_NEXT_DATA,
                OfferHttp { OfferHttpReply(200, mapOf("content-type" to "application/json"), body) }, now = { at })
            val grant = LiveSourceGrant("synthetic-large-public", scope.retailer, "synthetic-independent-public-source",
                day.minusDays(7), day.plusDays(7), setOf(endpoint.host), false, 600, scope)
            return LiveOfferRuntime(mapOf(scope.retailer to source), LivePermissionRegistry { grant }, now = { at }).refresh(scope)
        }
        val accepted = read(300, 1500)
        assertEquals(LiveRefreshStatus.LOADED, accepted.status)
        assertEquals(300, accepted.batch!!.offers.size); assertFalse(accepted.batch.coverageComplete)
        assertEquals(199L, accepted.batch.offers.first().merchandiseCents)
        assertTrue(accepted.batch.offers.all { it.storeId == null && it.applicability == LiveOfferApplicability.CHAIN_REPORTED })
        assertEquals(LiveRefreshStatus.FAILED, read(513, 0).status)
    }
}
