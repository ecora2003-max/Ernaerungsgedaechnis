package de.ernaehrung.demo

import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RetailerPolicy
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
class ReviewedOfferSourcesTest {
    private val day = LocalDate.parse("2026-11-16")
    private val at = Instant.parse("2026-11-16T12:00:00Z")
    private fun row(store: String = "DE123") = ReviewedOfferSourceFixtures.row(LiveRetailer.LIDL, day, store)
    private fun decode(vararg rows: Map<String, Any?>) = ReviewedOfferSources.decode(ReviewedOfferSourceFixtures.document(*rows))
    private fun scope(store: String) = LiveOfferScope(LiveRetailer.LIDL, store, null, null, day)
    private fun response() = """{"offers":[{"id":"synthetic-item","title":"Synthetisches Angebot","offerType":"StoreSpecialPriceDiscount","category":"Store","priceBox":{"largePartNumeric":1.99},"startValidityDate":"$day","endValidityDate":"$day"}]}"""

    @Test fun realDirectoryRuntimeResolvesTwoBranchesOfTheSameRetailerAndNeverFetchesAnUnreviewedBranch(): Unit = runBlocking {
        val directory = decode(row(), row("DE456")); val calls = mutableListOf<String>()
        val runtime = directory.runtime(OfferHttp { calls += it.uri.path; OfferHttpReply(200, mapOf("content-type" to "application/json"), response()) },
            MemoryOfferRefreshJournal()) { at }
        for (store in listOf("DE123", "DE456")) {
            val loaded = runtime.refresh(scope(store))
            assertEquals(LiveRefreshStatus.LOADED, loaded.status)
            assertEquals(store, loaded.batch!!.offers.single().storeId)
            assertTrue(loaded.authorization!!.evidenceRef.contains("APP_DISPLAY"))
        }
        assertEquals(listOf("/LIDL/DE123", "/LIDL/DE456"), calls)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(scope("DE789")).status)
        assertEquals(2, calls.size)
    }
    @Test fun allEightFormatsReachTheirExistingCapabilityWithoutKnusprOrBulkDiscovery() {
        val rows = LiveRetailer.entries.filter { it != LiveRetailer.KNUSPR }.map {
            ReviewedOfferSourceFixtures.row(it, day, if (it == LiveRetailer.EDEKA) "123" else "DE123")
        }
        val directory = decode(*rows.toTypedArray()); assertEquals(0, directory.invalidProfiles); assertEquals(8, directory.profiles.size)
        val runtime = directory.runtime(OfferHttp { error("Policy resolution must not perform HTTP") }, MemoryOfferRefreshJournal()) { at }
        directory.profiles.forEach { profile ->
            val policy = requireNotNull(runtime.policy(profile.scope(day)))
            assertEquals(if (profile.retailer in setOf(LiveRetailer.REWE, LiveRetailer.ALDI_SUED))
                RetailerPolicy.Capability.TARGETED_PRODUCT_PRICE_QUERY else RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION, policy.capability)
        }
    }
    @Test fun malformedSourceAndUnapprovedSecondaryEndpointDoNotDisableAnotherReviewedRetailer() {
        val badKauf = ReviewedOfferSourceFixtures.row(LiveRetailer.KAUFLAND, day).toMutableMap()
        badKauf["availabilityEndpoint"] = "https://different.example.test/not-reviewed"
        val directory = decode(row(), badKauf, row("DE456") + ("untrustedSupplierPermission" to true))
        assertEquals(2, directory.invalidProfiles); assertEquals(1, directory.profiles.size)
        assertNotNull(directory.profile(scope("DE123"), at)); assertNull(directory.profile(scope("DE456"), at))
    }
    @Test fun overlappingScopeAndDuplicateIdsAreDeniedIndependentlyOfDocumentOrder() {
        val first = row(); val sameScope = first + ("id" to "synthetic-second-approval")
        for (rows in listOf(arrayOf(first, sameScope), arrayOf(sameScope, first))) {
            val directory = decode(*rows); assertNull(directory.profile(scope("DE123"), at))
        }
        val directory = decode(row(), row("DE456") + ("id" to row()["id"]), row("DE789"))
        assertEquals(2, directory.invalidProfiles); assertEquals("DE789", directory.profiles.single().storeId)
    }
    @Test fun futureReviewExpiredGrantOrDifferentDayCannotActivateSource(): Unit = runBlocking {
        val directory = decode(row()); val profile = directory.profiles.single()
        assertNull(directory.profile(scope("DE123"), Instant.parse("2026-11-14T00:00:00Z")))
        assertNull(directory.profile(scope("DE123").copy(purchaseOn = day.plusDays(7)), at))
        val runtime = directory.runtime(OfferHttp { error("Expired permission must stop before I/O") }, MemoryOfferRefreshJournal()) { at.plusSeconds(7 * 86400) }
        assertNull(runtime.policy(profile.scope(day)))
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(profile.scope(day)).status)
    }
    @Test fun sourceFloorSurvivesRuntimeRecreationAndDoesNotReturnAnotherBranchesCache(): Unit = runBlocking {
        val sharedSource = "synthetic-shared-lidl"
        val directory = decode(row() + ("sourceId" to sharedSource), row("DE456") + ("sourceId" to sharedSource))
        val journal = MemoryOfferRefreshJournal(); var calls = 0
        val http = OfferHttp { calls++; OfferHttpReply(200, mapOf("content-type" to "application/json"), response()) }
        val runtime = directory.runtime(http, journal) { at }
        assertEquals(LiveRefreshStatus.LOADED, runtime.refresh(scope("DE123")).status)
        val blocked = runtime.refresh(scope("DE456")); assertEquals(LiveRefreshStatus.THROTTLED, blocked.status); assertNull(blocked.batch)
        val reopened = directory.runtime(http, journal) { at.plusSeconds(30) }
        assertEquals(LiveRefreshStatus.THROTTLED, reopened.refresh(scope("DE123")).status)
        assertEquals(1, calls)
    }
    @Test fun changingEndpointOrConditionsBindsNewPermissionToFullHostDocument() {
        val old = decode(row()).profiles.single().grant(scope("DE123"))
        val changedEndpoint = decode(row() + ("endpoint" to "https://offers.example.test/new-reviewed-route")).profiles.single().grant(scope("DE123"))
        val approval = requireNotNull(OfferJson.objectValue(row()["approval"]))
        val changedCache = decode(row() + ("approval" to (approval + ("cachePermitted" to false)))).profiles.single().grant(scope("DE123"))
        assertEquals(old.sourceId, changedEndpoint.sourceId); assertEquals(old.evidenceRef, changedEndpoint.evidenceRef)
        assertNotEquals(old, changedEndpoint); assertNotEquals(old.hostProfileFingerprint, changedEndpoint.hostProfileFingerprint)
        assertNotEquals(old, changedCache)
    }
    @Test fun publicSourceProvenanceCanBeReviewedWithoutInventingADealerPermission(): Unit = runBlocking {
        val public = row().toMutableMap()
        val approval = requireNotNull(OfferJson.objectValue(public["approval"])).toMutableMap()
        approval.remove("appDisplayEvidence")
        approval["sourceEvidence"] = "https://www.lidl.de/angebote/"
        public["approval"] = approval
        val directory = decode(public)
        assertEquals(0, directory.invalidProfiles)
        val grant = directory.profiles.single().grant(scope("DE123"))
        assertTrue(grant.evidenceRef.startsWith("PUBLIC_SOURCE "))
        assertTrue(grant.evidenceRef.contains("https://www.lidl.de/angebote/"))
        val calls = mutableListOf<String>()
        val runtime = directory.runtime(OfferHttp {
            calls += it.uri.toString()
            OfferHttpReply(200, mapOf("content-type" to "application/json"), response())
        }, MemoryOfferRefreshJournal()) { at }
        assertEquals(LiveRefreshStatus.LOADED, runtime.refresh(scope("DE123")).status)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(scope("DE999")).status)
        assertEquals(1, calls.size)
        // A source citation is not a blanket capability or a grant for other branches.
        val withBoth = approval + ("appDisplayEvidence" to "https://review.example.test/extra")
        assertEquals(1, decode(public + ("approval" to withBoth)).invalidProfiles)
        assertEquals(1, decode(public + ("approval" to (approval - "scopeEvidence"))).invalidProfiles)
    }

    @Test fun bundledProductionProfileUsesTheRealPublicCanonicalMarketAndNoSyntheticPermission() {
        val bundled = ReviewedOfferSources.bundled(ApplicationProvider.getApplicationContext())
        assertEquals(0, bundled.invalidProfiles)
        assertEquals(setOf("penny-4030085-top-angebote", "aldi-nord-public-week", "aldi-sued-public-offers",
            "aldi-sued-bl16-product-search", "lidl-de7434-plus-offers", "rewe-1356345-product-search",
            "kaufland-de5220-public", "edeka-jakobi-worms-highlights", "netto-1787-store-offers"),
            bundled.profiles.map { it.id }.toSet())
        val penny = bundled.profiles.single { it.retailer == LiveRetailer.PENNY }
        assertEquals(LiveRetailer.PENNY, penny.retailer); assertEquals("4030085", penny.storeId)
        assertEquals(PublishedOfferHttpSource.Format.PENNY_STORE_WEEK, penny.format)
        assertTrue(penny.grant(penny.scope(LocalDate.parse("2026-11-17"))).evidenceRef.startsWith("PUBLIC_SOURCE "))
        val national = bundled.profiles.filter { it.storeId == null }
        assertEquals(setOf(LiveRetailer.ALDI_NORD, LiveRetailer.ALDI_SUED), national.map { it.retailer }.toSet())
        assertTrue(national.all { it.storeId == null && it.regionId == null && !it.grant(it.scope(LocalDate.parse("2026-11-17"))).cachePermitted })
        assertEquals("DE5220", bundled.profiles.single { it.retailer == LiveRetailer.KAUFLAND }.storeId)
        val nationalDay = LocalDate.parse("2026-11-27")
        val nationalSnapshot = Instant.parse("2026-11-27T08:00:00Z")
        val nationalScope = LiveOfferScope(LiveRetailer.REWE, null, null, null, nationalDay)
        assertNull(bundled.profile(nationalScope, nationalSnapshot))
        assertNull(bundled.runtime(OfferHttp { error("Unconfigured national source") },
            MemoryOfferRefreshJournal()) { nationalSnapshot }.policy(nationalScope))
        val rewe = bundled.profiles.single { it.retailer == LiveRetailer.REWE }
        assertEquals("1356345", rewe.storeId); assertTrue(rewe.interactiveSearch)
        assertEquals(PublishedOfferHttpSource.Format.REWE_STATIONARY, rewe.format)
        val south = bundled.profiles.single { it.retailer == LiveRetailer.ALDI_SUED && it.interactiveSearch }
        assertEquals("BL16", south.storeId)
        assertEquals(PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN, south.format)
        assertEquals("1787", bundled.profiles.single { it.retailer == LiveRetailer.NETTO_MD }.storeId)
        assertEquals("JAKOBI-WORMS", bundled.profiles.single { it.retailer == LiveRetailer.EDEKA }.storeId)
        LiveRetailer.entries.filter { it != LiveRetailer.KNUSPR }.forEach {
            val url = requireNotNull(LiveSourceCatalogue.officialWebsite(it))
            assertEquals("https", url.scheme); assertNull(url.rawQuery); assertNull(url.rawUserInfo); assertNull(url.fragment)
        }
        assertEquals(LiveSourceStatus.DEFERRED, LiveSourceCatalogue.sources.single { it.retailer == LiveRetailer.KNUSPR }.status)
    }

    @Test fun interactiveReweSearchKeepsTheStoreTermAndRequestFloorBoundWithoutSavingTheTerm(): Unit = runBlocking {
        val row = ReviewedOfferSourceFixtures.row(LiveRetailer.REWE, day, "1356345").toMutableMap()
        row["interactiveSearch"] = true
        row["endpoint"] = "https://www.rewe.de/api/stationary-product-search/products"
        row["approval"] = requireNotNull(OfferJson.objectValue(row["approval"])) + ("cachePermitted" to false)
        val directory = decode(row)
        assertEquals(0, directory.invalidProfiles)
        assertEquals(1, decode(row + ("endpoint" to "https://www.rewe.de/api/stationary-product-search/products?query=ungueltig")).invalidProfiles)
        val profile = directory.profiles.single()
        val requests = mutableListOf<OfferHttpRequest>()
        val term = "Käse & Öl"
        var now = at
        var echoedTerm = term
        val runtime = directory.runtime(OfferHttp {
            requests += it
            OfferHttpReply(200, mapOf("content-type" to "application/json"),
                """{"term":"$echoedTerm","products":[{"id":101,"title":"Synthetic regular price","pricing":{"current":129,"regular":129,"refund":0,"grammage":"250 g"}}]}""")
        }, MemoryOfferRefreshJournal()) { now }
        val search = profile.scope(day, term)
        assertFalse(runtime.automaticAllowed(search))
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(search, automatic = true).status)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(profile.scope(day)).status)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(search.copy(storeId = "1356346")).status)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(search.copy(purchaseOn = day.plusDays(1))).status)
        assertTrue(requests.isEmpty())
        val result = runtime.refresh(search)
        assertEquals(LiveRefreshStatus.LOADED, result.status)
        assertEquals("query=K%C3%A4se+%26+%C3%96l&wwIdent=1356345", requests.single().uri.rawQuery)
        val offer = result.batch!!.offers.single()
        assertEquals(LiveOfferKind.REGULAR, offer.kind); assertEquals(129L, offer.merchandiseCents)
        assertEquals(0L, offer.depositCents); assertNull(offer.validFrom); assertNull(offer.quantityMilli)
        assertFalse(result.cachePermitted)
        val otherTerm = search.copy(searchTerm = "Hafer")
        assertEquals(LiveRefreshStatus.THROTTLED, runtime.refresh(otherTerm).status)
        assertEquals(1, requests.size)
        now = now.plusSeconds(3601)
        assertEquals("UNSUPPORTED_SOURCE_SCHEMA", runtime.refresh(otherTerm).code)
        assertEquals(2, requests.size)
        val settings = PrivateOfferSettings.forApp(ApplicationProvider.getApplicationContext())
        assertTrue(runCatching { settings.select(search, false) }.isFailure)
    }

    @Test fun aldiNordSelectsThePublishedWeekByBerlinPurchaseDateAtTheSameSourceFloor(): Unit = runBlocking {
        val directory = ReviewedOfferSources.bundled(ApplicationProvider.getApplicationContext())
        val profile = directory.profiles.single { it.retailer == LiveRetailer.ALDI_NORD }
        val today = LocalDate.parse("2026-11-18")
        var now = Instant.parse("2026-11-18T12:00:00Z")
        val calls = mutableListOf<String>()
        val runtime = directory.runtime(OfferHttp { request ->
            val path = request.uri.rawPath
            calls += path
            val week = when (path) {
                "/angebote.html" -> "current"
                "/angebote-vorschau.html" -> "next"
                else -> error("Unexpected endpoint")
            }
            OfferHttpReply(200, mapOf("content-type" to "application/json"),
                """{"props":{"pageProps":{"apiData":[["OFFER_GET",{"req":{"week":"$week"},"res":{"algoliaDataMap":{"item":{"name":"Synthetic $week","currentPrice":"1.99"}}}}]]}}}""")
        }, MemoryOfferRefreshJournal()) { now }
        val current = profile.scope(today)
        val next = profile.scope(today.plusWeeks(1))
        assertEquals(LiveRefreshStatus.LOADED, runtime.refresh(current).status)
        assertEquals(LiveRefreshStatus.THROTTLED, runtime.refresh(next).status)
        now = now.plusSeconds(601)
        assertEquals("Synthetic next", runtime.refresh(next).batch!!.offers.single().title)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(profile.scope(today.plusWeeks(2))).status)
        assertEquals(listOf("/angebote.html", "/angebote-vorschau.html"), calls)
    }

    @Test fun aldiSuedWalkInSearchUsesOnlyTheApprovedShopAndKeepsAnAdvertisedRateSeparate(): Unit = runBlocking {
        val directory = ReviewedOfferSources.bundled(ApplicationProvider.getApplicationContext())
        val profile = directory.profiles.single { it.retailer == LiveRetailer.ALDI_SUED && it.interactiveSearch }
        val date = LocalDate.parse("2026-11-19")
        val captured = Instant.parse("2026-11-19T09:00:00Z")
        val requests = mutableListOf<OfferHttpRequest>()
        val runtime = directory.runtime(OfferHttp { request ->
            requests += request
            OfferHttpReply(200, mapOf("content-type" to "application/json"),
                """{"data":[{"sku":"synthetic-1","name":"Synthetic item","price":{"amountRelevant":119,"bottleDeposit":0,
                    "currencyCode":"EUR","comparisonDisplay":"1,59 €/1 kg"}}]}""")
        }, MemoryOfferRefreshJournal()) { captured }
        val scope = profile.scope(date, "Brot & Käse")
        assertFalse(runtime.automaticAllowed(scope))
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(scope, automatic = true).status)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(scope.copy(storeId = "BL11")).status)
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(profile.scope(date)).status)
        assertTrue(requests.isEmpty())
        val result = runtime.refresh(scope)
        assertEquals(LiveRefreshStatus.LOADED, result.status)
        assertEquals("q=Brot+%26+K%C3%A4se&servicePoint=BL16&serviceType=walk-in&limit=12&offset=0&currency=EUR",
            requests.single().uri.rawQuery)
        assertEquals("api.aldi-sued.de", requests.single().uri.host)
        val offer = result.batch!!.offers.single()
        assertEquals("BL16", offer.storeId); assertEquals(119L, offer.merchandiseCents); assertEquals(0L, offer.depositCents)
        assertEquals("1,59 €/1 kg", offer.priceDetails?.unitPriceDisplay)
        assertNull(offer.priceDetails?.regularCents); assertNull(offer.unitPriceText()); assertNull(offer.validFrom)
        assertFalse(result.cachePermitted)
        assertEquals(LiveRefreshStatus.THROTTLED, runtime.refresh(scope.copy(searchTerm = "Toast")).status)
        assertEquals(1, requests.size)
    }

    @Test fun lidlPlusUsesTheVerifiedMarketAndKeepsAppPricesOutOfGoodsComparison(): Unit = runBlocking {
        val directory = ReviewedOfferSources.bundled(ApplicationProvider.getApplicationContext())
        val profile = directory.profiles.single { it.id == "lidl-de7434-plus-offers" }
        assertFalse(profile.allowsGoodsComparison)
        val selected = profile.scope(LocalDate.parse("2026-11-19"))
        var now = Instant.parse("2026-11-19T10:00:00Z")
        var directoryStoreNumber = "7434"
        val requests = mutableListOf<OfferHttpRequest>()
        val runtime = directory.runtime(OfferHttp { request ->
            requests += request
            when (request.uri.host) {
                "www.lidl.de" -> OfferHttpReply(200, mapOf("content-type" to "text/html"),
                    """<link rel="canonical" href="${profile.availabilityEndpoint}"><h1><span>Lidl Filiale</span>Malchower Weg 134, 13053 Berlin</h1>
                    <a href="https://survey2.medallia.eu/example?TNC=$directoryStoreNumber">Gib uns Feedback zur Filiale</a>""")
                "offers.lidlplus.com" -> OfferHttpReply(200, mapOf("content-type" to "application/json"),
                    """{"totalOffers":3,"offers":[
                    {"id":"app-1","title":"Synthetisch","offerType":"StoreSpecialPriceDiscount","category":"Store",
                     "redemptionChannel":"Store","priceBox":{"priceSymbol":"€","largePartNumeric":0,"smallPartNumeric":2.99},
                     "startValidityDate":"2026-11-19T00:00:00+00:00","endValidityDate":"2026-11-21T23:59:59+00:00",
                     "startValidityDateUTC":"2026-11-18T23:00:00Z","endValidityDateUTC":"2026-11-21T22:59:59Z",
                     "packaging":"Beispielpackung","pricePerUnit":"Quelleinheit"},
                    {"id":"web-1","title":"Online","offerType":"StoreSpecialPriceDiscount","category":"Store",
                     "redemptionChannel":"OnlineShop","priceBox":{"priceSymbol":"€","largePartNumeric":1.99},
                     "startValidityDate":"2026-11-19","endValidityDate":"2026-11-21"},
                    {"id":"expired-1","title":"Alt","offerType":"StoreSpecialPriceDiscount","category":"Store",
                     "redemptionChannel":"Store","priceBox":{"priceSymbol":"€","largePartNumeric":1.99},
                     "startValidityDate":"2026-11-16","endValidityDate":"2026-11-17"}]}""")
                else -> error("Unexpected request")
            }
        }, MemoryOfferRefreshJournal()) { now }
        assertEquals(LiveRefreshStatus.BLOCKED, runtime.refresh(selected.copy(storeId = "DE7602")).status)
        assertTrue(requests.isEmpty())
        val observation = runtime.refresh(selected)
        assertEquals(LiveRefreshStatus.LOADED, observation.status)
        assertEquals(listOf(profile.availabilityEndpoint, profile.endpoint), requests.map(OfferHttpRequest::uri))
        val offer = observation.batch!!.offers.single()
        assertEquals("Lidl", offer.storeEvidence!!.marketName)
        assertEquals("Malchower Weg 134, 13053 Berlin", offer.storeEvidence.address)
        assertEquals(0L, offer.priceDetails!!.appCents)
        assertEquals("Quelleinheit", offer.priceDetails.unitPriceDisplay)
        assertNull(offer.merchandiseCents); assertNull(offer.priceDetails.regularCents); assertNull(offer.checkoutCents)
        assertTrue(offer.applicable(selected)); assertFalse(offer.isComparisonCandidate(selected))
        assertFalse(observation.cachePermitted)
        assertEquals(LiveRefreshStatus.THROTTLED, runtime.refresh(selected).status)
        now = now.plusSeconds(601); directoryStoreNumber = "1111"
        val changed = runtime.refresh(selected)
        assertEquals("SCOPE_UNAVAILABLE", changed.code); assertNull(changed.batch)
        assertEquals(profile.availabilityEndpoint, requests.last().uri)
        assertEquals(3, requests.size)
    }
}
