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
        assertEquals(0, bundled.invalidProfiles); assertEquals(5, bundled.profiles.size)
        val penny = bundled.profiles.single { it.retailer == LiveRetailer.PENNY }
        assertEquals(LiveRetailer.PENNY, penny.retailer); assertEquals("4030085", penny.storeId)
        assertEquals(PublishedOfferHttpSource.Format.PENNY_STORE_WEEK, penny.format)
        assertTrue(penny.grant(penny.scope(LocalDate.parse("2026-11-17"))).evidenceRef.startsWith("PUBLIC_SOURCE "))
        val national = bundled.profiles.filter { it.storeId == null }
        assertEquals(setOf(LiveRetailer.ALDI_NORD, LiveRetailer.ALDI_SUED), national.map { it.retailer }.toSet())
        assertTrue(national.all { it.storeId == null && it.regionId == null && !it.grant(it.scope(LocalDate.parse("2026-11-17"))).cachePermitted })
        assertEquals(mapOf(LiveRetailer.REWE to "1356345", LiveRetailer.KAUFLAND to "DE5220"),
            bundled.profiles.filter { it.retailer in setOf(LiveRetailer.REWE, LiveRetailer.KAUFLAND) }.associate { it.retailer to it.storeId })
        LiveRetailer.entries.filter { it != LiveRetailer.KNUSPR }.forEach {
            val url = requireNotNull(LiveSourceCatalogue.officialWebsite(it))
            assertEquals("https", url.scheme); assertNull(url.rawQuery); assertNull(url.rawUserInfo); assertNull(url.fragment)
        }
        assertEquals(LiveSourceStatus.DEFERRED, LiveSourceCatalogue.sources.single { it.retailer == LiveRetailer.KNUSPR }.status)
    }
}
