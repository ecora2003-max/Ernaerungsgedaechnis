package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.time.Instant
import java.time.LocalDate

class MarketOfferSourcesTest {
    private val at = Instant.parse("2026-11-18T12:00:00Z")
    private val day = LocalDate.parse("2026-11-18")
    private val rewe = URI("https://www.rewe.de/angebote/beispielstadt/1356345/rewe-beispielstrasse/")
    private val marketPage = URI("https://filiale.kaufland.de/service/filiale/beispielstadt-5220.html")
    private val availability = URI("https://filiale.kaufland.de/.kloffers.storeName=DE5220.json")
    private val storeLookup = URI("https://filiale.kaufland.de/.klstorebygeo.storeName=DE5220.json")
    private val marketScope = LiveOfferScope(LiveRetailer.KAUFLAND, "DE5220", null, null, day)
    private val marketJson = """{"n":"DE5220","cn":"Kaufland Beispielstadt","sn":"Beispielstraße 1","pc":"12345","t":"Beispielstadt"}"""

    @Test fun reweUsesRenderedActionPricesForExactMarketAndWeekWhileKeepingPfandAndWeightSeparate() {
        val scope = LiveOfferScope(LiveRetailer.REWE, "1356345", null, null, day)
        val html = """<link rel="canonical" href="$rewe">
            <script type="application/ld+json">{"@type":"GroceryStore","name":"REWE Beispielmarkt",
                "address":{"streetAddress":"Beispielstraße 2","postalCode":"12345","addressLocality":"Beispielstadt"}}</script>
            <div data-offer-details-deeplink-ww-ident-value="1356345"></div>
            <div id="sos-categories-current"><section id="markt-topangebote-current-week">
            ${reweCard("0000000000000001", "Getränk", "1356345", "2026/47", "Aktion", "je 1-l-Fl.", ", zzgl. 0,25 € Pfand")}
            ${reweCard("0000000000000002", "Lose Ware", "1356345", "2026/47", "Aktion", "je 100 g")}
            ${reweCard("0000000000000003", "Anderer Markt", "1356000", "2026/47", "Aktion", "je Packung")}
            ${reweCard("0000000000000004", "Bonus", "1356345", "2026/47", "REWE Bonus", "je Packung")}
            ${reweCard("0000000000000005", "Andere Woche", "1356345", "2026/48", "Aktion", "je Packung")}
            </section></div>"""
        val rows = ReweMarketOfferDecoder.decode(html, "synthetic-rewe", scope, rewe, at)
        assertEquals(listOf("Getränk", "Lose Ware"), rows.map(LiveOffer::title))
        assertEquals(149L, rows[0].merchandiseCents)
        assertEquals(25L, rows[0].depositCents)
        assertEquals(174L, rows[0].checkoutCents)
        assertNull(rows[0].validFrom)
        assertNull(rows[0].validUntil)
        assertNull(rows[0].quantityMilli)
        assertEquals("1356345", rows[0].storeEvidence?.marketId)
        assertEquals("2026-11-16", rows[0].storeEvidence?.weekStartsOn.toString())
        assertNull(rows[1].merchandiseCents)
        assertEquals("1,49 € je 100 g", rows[1].priceDetails?.unitPriceDisplay)
        assertEquals(OfferSourceFailureCode.SCOPE_UNAVAILABLE, assertThrows(OfferSourceFailure::class.java) {
            ReweMarketOfferDecoder.decode(html.replace("ww-ident-value=\"1356345\"", "ww-ident-value=\"\""),
                "synthetic-rewe", scope, rewe, at)
        }.code)
    }

    @Test fun kauflandReadsIndependentMarketAndAvailabilityAndNeverMergesAmbiguousWindowsOrCardPrices(): Unit = runBlocking {
        val visited = mutableListOf<URI>()
        val html = """<link rel="canonical" href="$marketPage"><div data-t-name="StoreDetails" data-force-store-change="DE5220"></div>
            ${kauflandCard("00100001", "Flasche", "je Flasche", "1.49", "1.29")}
            ${kauflandCard("00100002", "Lose Ware", "je kg", "3.99")}
            ${kauflandCard("00100003", "Mehrdeutiger Zeitraum", "je Packung", "2.00")}
            ${kauflandCard("00100004", "Kein Filialnachweis", "je Packung", "2.00")}"""
        val dates = """[${window("00100001")},${window("00100002")},${window("00100003")},
            ${window("00100003", "2026-11-17")},${window("00100004", "2026-11-19")}]"""
        val http = OfferHttp { request ->
            visited += request.uri
            assertTrue(request.headers.keys.none { it.equals("cookie", ignoreCase = true) })
            when (request.uri) {
                storeLookup -> OfferHttpReply(200, mapOf("content-type" to "application/json"), marketJson)
                marketPage -> OfferHttpReply(200, mapOf("content-type" to "text/html; charset=UTF-8"), html)
                availability -> OfferHttpReply(200, mapOf("content-type" to "application/json"), dates)
                else -> error("Unexpected request")
            }
        }
        val source = KauflandMarketOfferSource(marketPage, availability, "synthetic-kaufland", marketScope, http) { at }
        assertEquals(setOf(storeLookup, marketPage, availability), source.endpoints)
        val rows = source.fetch(marketScope).offers
        assertEquals(listOf(storeLookup, marketPage, availability), visited)
        assertEquals(listOf("00100001", "00100002"), rows.map(LiveOffer::sourceProductId))
        val bottle = rows.first()
        assertEquals("DE5220", bottle.storeId)
        assertEquals(availability.toString(), bottle.storeEvidence?.availabilitySourceUrl)
        assertEquals(149L, bottle.merchandiseCents)
        assertEquals(129L, bottle.priceDetails?.appCents)
        assertNull(bottle.priceDetails?.regularCents)
        assertNull(bottle.checkoutCents)
        assertEquals(LocalDate.parse("2026-11-16"), bottle.validFrom)
        assertEquals(LocalDate.parse("2026-11-21"), bottle.validUntil)
        assertNull(rows[1].merchandiseCents)
        assertEquals("3.99 € je kg", rows[1].priceDetails?.unitPriceDisplay)
    }

    private fun reweCard(id: String, name: String, store: String, week: String, label: String, unit: String, extra: String = "") =
        """<div data-offer-id="$id" data-offer-nan="99001" data-offer-wwident="$store" data-offer-week="$week">
            <article class="cor-offer-renderer-tile"><a data-offer-title="$name" data-offer-wwident="$store">$name</a>
            <span class="cor-offer-information__additional">$unit</span><span class="cor-offer-information__additional">$extra</span>
            <div class="cor-offer-price__tag"><span class="cor-offer-price__tag-label">$label</span>
            <span class="cor-offer-price__tag-price">1,49 €</span></div></article></div>"""

    private fun kauflandCard(id: String, name: String, unit: String, price: String, cardPrice: String? = null) =
        """<a class="k-product-tile k-product-tile--slider" href="/angebote/uebersicht.html?kloffer-articleID=$id">
            <div class="k-product-tile__title">$name</div><div class="k-product-tile__unit-price">$unit</div>
            <div class="k-product-tile__pricetags-normal"><span class="k-price-tag__price">$price</span></div>
            ${cardPrice?.let { """<div class="k-product-tile__pricetags-loyalty"><span class="k-price-tag__price">$it</span></div>""" }.orEmpty()}
            </a>"""

    private fun window(id: String, from: String = "2026-11-16") =
        """{"klNr":"$id","dateFrom":"$from","dateTo":"2026-11-21"}"""
}
