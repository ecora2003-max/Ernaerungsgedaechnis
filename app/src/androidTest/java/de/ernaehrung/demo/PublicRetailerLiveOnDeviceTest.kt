package de.ernaehrung.demo

import android.net.TrafficStats
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LiveOffersViewModel
import de.ernaehrung.demo.ui.screens.LiveOffersScreen
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Explicitly selected live job only. Uses the shipped source profile and
 * DEFAULT production HTTP/runtime, no injected fixture, grant or canned reply.
 * Never runs on a physical/personal installation or in the offline suite. */
@RunWith(AndroidJUnit4::class)
class PublicRetailerLiveOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun publicAldiNordOffersReachTheAndroidUi(): Unit = runBlocking {
        publicChainLiveFlow(compose, LiveRetailer.ALDI_NORD, expectedStore = null)
    }
    @Test fun publicAldiSuedOffersReachTheAndroidUi(): Unit = runBlocking {
        publicChainLiveFlow(compose, LiveRetailer.ALDI_SUED, expectedStore = null)
    }
    @Test fun publicAldiSuedSearchShowsPricesForTheChosenWalkInMarket(): Unit = runBlocking {
        publicChainLiveFlow(compose, LiveRetailer.ALDI_SUED, expectedStore = "BL16", searchTerm = "Sandwichtoast")
    }
    @Test fun publicKauflandOffersAndExactMarketValidityReachTheAndroidUi(): Unit = runBlocking {
        publicChainLiveFlow(compose, LiveRetailer.KAUFLAND, expectedStore = "DE5220")
    }
    @Test fun publicReweSearchShowsCurrentAndRegularPricesForTheChosenMarket(): Unit = runBlocking {
        publicChainLiveFlow(compose, LiveRetailer.REWE, expectedStore = "1356345", searchTerm = "Haferflocken")
    }
    @Test fun publicLidlAppPricesStaySeparateInTheConfirmedMarket(): Unit = runBlocking {
        publicChainLiveFlow(compose, LiveRetailer.LIDL, expectedStore = "DE7434")
    }
    @Test fun publicPennyMarketAndActualOffersReachTheProductionUiWithoutAnyActualFoodOrHealthWrite(): Unit = runBlocking {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        assertEquals("true", InstrumentationRegistry.getArguments().getString("eg_live_source"))
        val context = compose.activity.applicationContext
        val device = preparePublicRetailerLiveNetwork(context)
        val config = ReviewedOfferSources.bundled(context)
        assertEquals(0, config.invalidProfiles)
        val profile = config.profiles.single { it.id == "penny-4030085-top-angebote" }
        assertEquals("4030085", profile.storeId)
        assertFalse(profile.grant(profile.scope(AppCalendar.today())).cachePermitted)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val before = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val paid = dao.allPaidPrices()
        val health = db.healthDao().history("com.sec.android.app.shealth"); val body = db.bodyDao().versions()
        val settings = PrivateOfferSettings.forApp(context); settings.clearSelection()
        val rxBefore = TrafficStats.getUidRxBytes(context.applicationInfo.uid)
        val txBefore = TrafficStats.getUidTxBytes(context.applicationInfo.uid)
        assertTrue(rxBefore >= 0 && txBefore >= 0)
        val models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        try {
            compose.runOnUiThread {
                // Same constructor/factory path as the standard app. In
                // particular HttpsOfferHttp and source configuration stay real.
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        LiveOffersViewModel(context) as T
                })[LiveOffersViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        LiveOffersScreen(providedModel = model)
                    }
                } }
            }
            compose.waitUntil(25_000) { model.state.value.selectionLoaded }
            compose.onNodeWithTag("Bestätigte Filiale oder Angebotsregion").performScrollTo().performClick()
            compose.onNodeWithTag("choose_${profile.id}").performScrollTo().performClick()
            compose.waitUntil(25_000) { !model.state.value.busy }
            compose.onNodeWithTag("live_offers_refresh").performScrollTo().performClick()
            compose.waitUntil(90_000) { !model.state.value.busy && model.state.value.result != null }
            val result = requireNotNull(model.state.value.result)
            assertEquals("Actual source failure: ${result.code}/${result.transportDiagnostic}", LiveRefreshStatus.LOADED, result.status)
            val batch = requireNotNull(result.batch)
            assertTrue(batch.offers.isNotEmpty()); assertTrue(batch.offers.size <= 256)
            assertTrue(batch.offers.any { it.merchandiseCents != null })
            assertTrue(batch.offers.all { it.retailer == LiveRetailer.PENNY && it.storeId == "4030085" &&
                it.sourceUrl.startsWith("https://www.penny.de/.rest/offers/by-category/") &&
                it.storeEvidence?.scopeSourceUrl == "https://www.penny.de/.rest/market" })
            val proof = requireNotNull(batch.offers.first().storeEvidence)
            assertEquals("4030085", proof.marketId); assertTrue(proof.sourceRegionId?.isNotBlank() == true)
            compose.onNodeWithTag("live_canonical_market").assertTextEquals("Markt: ${proof.marketName} · ${proof.address}")
            compose.onNodeWithText(batch.offers.first().title).assertExists()
            assertEquals(before, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows())
            assertEquals(paid, dao.allPaidPrices()); assertEquals(health, db.healthDao().history("com.sec.android.app.shealth"))
            assertEquals(body, db.bodyDao().versions())
            val rxDelta = TrafficStats.getUidRxBytes(context.applicationInfo.uid) - rxBefore
            val txDelta = TrafficStats.getUidTxBytes(context.applicationInfo.uid) - txBefore
            assertTrue("A real live read must receive actual app-UID traffic", rxDelta > 0)
            assertTrue("A real live read must send actual app-UID traffic", txDelta > 0)
            val digest = MessageDigest.getInstance("SHA-256").digest(batch.offers.joinToString("\n") {
                "${it.id}|${it.merchandiseCents}|${it.sourceUrl}|${it.fetchedAt}"
            }.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val sourceCommit = requireNotNull(InstrumentationRegistry.getArguments().getString("source_sha"))
            require(sourceCommit.matches(Regex("[0-9a-f]{40}")))
            val report = JSONObject().put("format", "EG_REAL_PUBLIC_RETAILER_UI_V1").put("status", "PASS").put("sourceCommit", sourceCommit)
                .put("actualSdk", Build.VERSION.SDK_INT).put("sourceId", batch.sourceId).put("storeId", proof.marketId)
                .put("regionId", proof.sourceRegionId).put("offerCount", batch.offers.size).put("observedAt", batch.fetchedAt.toString())
                .put("offerFingerprint", digest).put("sourceHost", "www.penny.de").put("actualNetworkValidated", true)
                .put("uidRxDelta", rxDelta).put("uidTxDelta", txDelta)
                .put("defaultProductionHttp", true).put("sourceRepliesInjected", false).put("actualFactsUnchanged", true)
            // connectedAndroidTest uninstalls its disposable subject before the
            // workflow can pull app-owned files. Use the shell-owned RW pipe
            // exercised by the existing device diagnostic harness, metadata
            // only. No app storage permission or personal device is involved.
            check(Build.VERSION.SDK_INT >= 31) { "The explicit API35 live harness requires shell RW pipes" }
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8).also { require(it.size <= 16_384) }
            device.executeShellCommand("mkdir -p /sdcard/Download/eg-public-retailer-live")
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw(
                "dd of=/sdcard/Download/eg-public-retailer-live/proof.json")
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(bytes) }
                ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            } finally { pipes.forEach { runCatching { it.close() } } }
            // UiAutomation executes an argv command, not a shell script: &&
            // must never be passed as though it were interpreted by sh.
            val retained = device.executeShellCommand("wc -c /sdcard/Download/eg-public-retailer-live/proof.json")
                .trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.toIntOrNull()
            assertEquals("Public live metadata byte count differs from the emitted proof", bytes.size, retained)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally { compose.runOnUiThread { compose.activity.setContent {}; models.clear() }; settings.clearSelection() }
    }
}
