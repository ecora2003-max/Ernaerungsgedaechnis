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

    @Test fun aldiNordKeepsPublishedRatesAndComparisonLabelsAndDistinguishesAbsentDepositFromZero() {
        val raw = """{"props":{"pageProps":{"apiData":[["OFFER_GET",{"req":{"week":"current"},
            "res":{"algoliaDataMap":{
                "bottle":{"name":"Synthetisches Getränk","isDepositProduct":true,"depositValue":0.25,
                    "brandName":"Testmarke","salesUnit":"0,5-L-Flasche","currentPrice":{"priceValue":0.79,
                    "basePrice":[{"basePriceValue":1.58,"basePriceScale":"Liter"}],
                    "strikePrice":{"strikePriceValue":1.20,"strikePriceLabel":"UVP"},"priceTagLabels":{"promoText1":"-34 %"}}},
                "known-zero":{"name":"Synthetische Ware","isDepositProduct":false,"depositValue":0,"currentPrice":2.49},
                "missing":{"name":"Synthetisch unbekannt","isDepositProduct":false,"currentPrice":2.49},
                "conflicting":{"name":"Widerspruch","isDepositProduct":false,"depositValue":0.25,"currentPrice":2.49}
            }}}]]}}}"""
        val rows = PublishedOfferDecoders.aldiNordCurrent(raw, "synthetic", at, "https://feed.example.test/offers")
            .associateBy { it.sourceProductId }
        val bottle = rows.getValue("bottle")
        assertEquals(79L, bottle.merchandiseCents)
        assertEquals(25L, bottle.depositCents)
        assertEquals(104L, bottle.checkoutCents)
        assertNull(bottle.quantityMilli)
        assertEquals("1,58 € / Liter", bottle.priceDetails?.unitPriceDisplay)
        assertEquals("UVP 1,20 € · -34 %", bottle.priceDetails?.previousDisplay)
        assertNull(bottle.priceDetails?.regularCents)
        assertEquals(0L, rows.getValue("known-zero").depositCents)
        assertNull(rows.getValue("missing").depositCents)
        assertNull(rows.getValue("conflicting").depositCents)
    }
}
