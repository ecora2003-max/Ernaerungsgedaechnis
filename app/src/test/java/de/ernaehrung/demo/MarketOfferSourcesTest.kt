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

    @Test fun edekaPublicHighlightsRequireTheMatchingStoreAndBothPrintedWindowsWithoutInventingAnArticleId(): Unit = runBlocking {
        val home = URI("https://www.edeka-jakobi.de/")
        val branch = URI("https://www.edeka-jakobi.de/worms")
        val scope = LiveOfferScope(LiveRetailer.EDEKA, "JAKOBI-WORMS", null, null, day)
        val window = "Gültig von Montag, 16.11.2026 bis Samstag, 21.11.2026"
        val market = """<div id="intro"><h1>EDEKA Jakobi Worms</h1><p class="mark">Mittelochsenplatz 4 | 67547 Worms</p></div>
            <div id="angebote"></div><div id="w"></div><div><p><strong>$window im E-Center Worms:</strong></p></div>"""
        fun tile(title: String, amount: String) = """<div class="p-angebote"><p class="angebot-headline">$title</p>
            <div class="angebot-einstellig"><span class="price-first">$amount</span>
            <span class="price-komma">.</span><span class="price-second">49</span></div></div>"""
        val listing = """<div class="start-angebot">
            <div><h3>In Ihrem Markt in Bensheim, Lautertal</h3><div class="angebote">${tile("Fremder Markt", "2")}</div></div>
            <div><p>$window.</p><p><a href="angebote#bl">Fremd</a></p></div>
            <div><h3>In Ihrem Markt in Worms</h3><div class="angebote">${tile("Synthetisch, 500 g", "1")}</div></div>
            <div><p>Nur solange der Vorrat reicht.<br>$window.</p><p><a href="worms#angebote">Worms</a></p></div></div>"""
        val requests = mutableListOf<OfferHttpRequest>()
        var branchPage = market
        val reader = EdekaJakobiMarketOfferSource(home, branch, "sample-edeka", scope, OfferHttp { request ->
            requests += request
            OfferHttpReply(200, mapOf("content-type" to "text/html; charset=UTF-8"),
                if (request.uri == branch) branchPage else listing)
        }) { at }
        val result = reader.fetch(scope).offers.single()
        assertEquals(listOf(branch, home), requests.map { it.uri })
        assertEquals(setOf(branch, home), reader.endpoints)
        assertTrue(requests.all { it.method == "GET" && it.body == null && it.headers.keys == setOf("Accept", "User-Agent") })
        assertEquals("Synthetisch, 500 g", result.title)
        assertEquals(149L, result.merchandiseCents)
        assertEquals("JAKOBI-WORMS", result.storeEvidence?.marketId)
        assertEquals("2026-11-16", result.validFrom.toString()); assertEquals("2026-11-21", result.validUntil.toString())
        assertNull(result.sourceProductId); assertNull(result.quantityMilli); assertNull(result.checkoutCents)
        assertFalse(result.conditionsConfirmed)
        branchPage = market.replace("Samstag, 21.11.2026", "Freitag, 20.11.2026")
        val failure = runCatching { reader.fetch(scope) }.exceptionOrNull() as OfferSourceFailure
        assertEquals(OfferSourceFailureCode.SCOPE_UNAVAILABLE, failure.code)
    }

    @Test fun nettoCanonicalStoreUsesDatedThemeAndSeparatesAppPricePfandAndUncertainQuantity() {
        val endpoint = URI("https://www.netto-online.de/filialen/beispielstadt/beispiel-str-1/1787")
        val scope = LiveOfferScope(LiveRetailer.NETTO_MD, "1787", null, null, day)
        fun card(id: String, title: String, listedPrice: String) = """<li class="js-store-product-tile">
            <em class="tc-product-name">$title</em><span class="product__base-price">1 l<br>1.49 / l<br>zzgl. Pfand 0.25</span>
            <div class="product__price__label"><ins class="tc-product-price">$listedPrice</ins></div>
            <div class="grid-app-price"><ins class="tc-product-price">1.29*</ins></div>
            <a class="js-add-to-wishlist" href="https://www.netto-online.de/INTERSHOP/web/WFS/Plus-NettoDE-Site/de_DE/-/EUR/ViewMMPWishlist-AddStoreArticle?SKU=$id&amp;Name=$title&amp;ValidTo=2030-12-01&amp;fromStoreProductTile=true">Liste</a></li>"""
        val html = """<link rel="canonical" href="$endpoint"><a class="js-select-button" data-store-id="1787">Markt</a>
            <script type="application/ld+json">{"@type":"GroceryStore","name":"Netto Beispiel","url":"$endpoint",
            "address":{"streetAddress":"Beispielstr. 1","postalCode":"99427","addressLocality":"Beispielstadt",
            "addressCountry":{"name":"DE"}}}</script>
            <section class="store-offers"><h2 class="store-offers__header__headline">Filial-Angebote</h2>
            <div class="js-store-offers-item" data-theme="1" data-slider-headline="gültig von Montag, 16.11.26 - Samstag, 21.11.26"></div>
            <div class="js-store-offers-item" data-theme="2" data-slider-headline="gültig von Donnerstag, 19.11.26 - Samstag, 21.11.26"></div>
            <div class="js-store-offers-slides" data-theme="1"><ul>${card("401", "Getraenk", "1.49*")}</ul></div>
            <div class="js-store-offers-slides" data-theme="2"><ul>${card("402", "Spaeter", "1.99*")}</ul></div></section>
            <p class="site-footer__box__star-text">*Alle Preise in Euro</p>"""
        val rows = NettoBranchOfferDecoder.decodeStore(html, "sample-netto", scope, endpoint, at)
        val offer = rows.single()
        assertEquals("401", offer.sourceProductId)
        assertEquals(149L, offer.merchandiseCents); assertEquals(129L, offer.priceDetails?.appCents)
        assertEquals(25L, offer.depositCents); assertEquals(174L, offer.checkoutCents)
        assertEquals("1.49 / l", offer.priceDetails?.unitPriceDisplay); assertNull(offer.quantityMilli)
        assertEquals(LocalDate.parse("2026-11-21"), offer.validUntil)
        assertTrue(offer.applicable(scope)); assertFalse(offer.conditionsConfirmed)
        assertEquals(OfferSourceFailureCode.SCOPE_UNAVAILABLE, assertThrows(OfferSourceFailure::class.java) {
            NettoBranchOfferDecoder.decodeStore(html.replace("data-store-id=\"1787\"", "data-store-id=\"0178\""),
                "sample-netto", scope, endpoint, at)
        }.code)
    }

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
