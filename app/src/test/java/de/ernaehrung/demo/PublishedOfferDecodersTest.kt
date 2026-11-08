package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Synthetic shapes from pinned EG-Preisdaten@5c52bf7, no live-source proof. */
class PublishedOfferDecodersTest {
    private val at = Instant.parse("2026-11-08T10:00:00Z")
    private val day = LocalDate.parse("2026-11-08")
    @Test fun pennyRegionWeekRouteNeverInventsIndividualExpiryPackageOrAppPrice() {
        val row = PublishedOfferDecoders.penny("""{"offerTiles":[{"uuid":"one","title":"Synthetisch","price":"1,99","quantity":"je 500-g-Packung","benefitPrice":"Mit App 1,49"}]}""",
            "synthetic", "region", day, at, "https://feed.example.test/offers").single()
        assertEquals(199L, row.merchandiseCents); assertEquals(day, row.validFrom); assertNull(row.validUntil)
        assertNull(row.quantityMilli); assertEquals("je 500-g-Packung", row.packageDescription)
        assertFalse(row.conditionsConfirmed); assertEquals("region", row.regionId); assertNull(row.storeId)
        assertFalse(row.applicable(LiveOfferScope(LiveRetailer.PENNY, "store", "region", null, day)))
    }
    @Test fun aldiNordNextRetainsActualDateWindowAndChainScopeWithoutStoreOrRegularPriceClaim() {
        val raw = """{"props":{"pageProps":{"apiData":[["OFFER_GET",{"req":{"week":"next"},"res":{"algoliaDataMap":{"one":{"name":"Synthetisch","currentPrice":{"priceValue":1.99,"validFromLocalDate":"2026-11-09","validUntilLocalDate":"2026-11-14"},"salesUnit":"500 g"}}}}]]}}}"""
        val row = PublishedOfferDecoders.aldiNordNext(raw, "synthetic", at, "https://feed.example.test/offers").single()
        assertEquals(199L, row.merchandiseCents); assertEquals(day.plusDays(1), row.validFrom)
        assertEquals(LiveOfferApplicability.CHAIN_REPORTED, row.applicability); assertNull(row.storeId)
        assertEquals(LiveOfferKind.PROMOTION, row.kind); assertNull(row.quantityBasis)
        assertTrue(runCatching { PublishedOfferDecoders.aldiNordNext(raw.replace("\"week\":\"next\"", "\"week\":\"next\",\"storeId\":\"injected\""), "synthetic", at, "https://feed.example.test/offers") }.isFailure)
    }
    @Test fun kauflandStoreAvailabilityNeverBridgesARealGapOrUsesPrepublicationDatesAsSalesDates() {
        val raw = """{"offerData":{"cycles":[{"categories":[{"offers":[{"klNr":"one","title":"Synthetisch","price":"1,99","unit":"500 g","salesFrom":"2026-11-01","salesTo":"2026-11-14"}]}]}]}}"""
        val windows = listOf(mapOf("klNr" to "one", "dateFrom" to "2026-11-01", "dateTo" to "2026-11-06"),
            mapOf("klNr" to "one", "dateFrom" to "2026-11-09", "dateTo" to "2026-11-14"))
        assertTrue(PublishedOfferDecoders.kaufland(raw, windows, "synthetic", "DE1234", day, at, "https://feed.example.test/offers").isEmpty())
        val row = PublishedOfferDecoders.kaufland(raw, windows, "synthetic", "DE1234", day.plusDays(1), at, "https://feed.example.test/offers").single()
        assertEquals(day.plusDays(1), row.validFrom); assertEquals("DE1234", row.storeId)
        assertNull(row.depositCents); assertFalse(row.conditionsConfirmed)
    }
}
