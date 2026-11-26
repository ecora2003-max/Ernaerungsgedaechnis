package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LiveOffersViewModel
import de.ernaehrung.demo.ui.screens.LiveOffersScreen
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*

/** Used only by the explicitly selected live suite on disposable emulators. */
internal suspend fun preparePublicRetailerLiveNetwork(context: Context): UiDevice {
    check(Build.VERSION.SDK_INT == 35)
    check(InstrumentationRegistry.getArguments().getString("eg_live_source") == "true")
    // The runner enabled Wi-Fi before the suite. Repeated airplane-mode
    // toggles could leave an old validated network visible during DNS teardown.
    prepareUnlockedTestDevice(offline = false)
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    var scanned = false; var joined = false
    withTimeout(45_000) {
        while (true) {
            val cap = network.activeNetwork?.let { network.getNetworkCapabilities(it) }
            if (cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) break
            if (!scanned && device.executeShellCommand("cmd wifi status").lineSequence().any { it.trim() == "Wifi is enabled" }) {
                device.executeShellCommand("cmd wifi start-scan"); scanned = true
            }
            if (scanned && !joined && device.executeShellCommand("cmd wifi list-scan-results").contains("VirtWifi")) {
                device.executeShellCommand("cmd wifi connect-network VirtWifi open"); joined = true
            }
            delay(250)
        }
    }
    return device
}

/** Read one listing through the production Android UI. Each provider has its
 * own test and proof, so a blocked source cannot suppress the other results. */
internal suspend fun publicChainLiveFlow(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    retailer: LiveRetailer, expectedStore: String?, searchTerm: String? = null) {
    val context = compose.activity.applicationContext
    val device = preparePublicRetailerLiveNetwork(context)
    val config = ReviewedOfferSources.bundled(context); assertEquals(0, config.invalidProfiles)
    val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
    val before = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val paid = dao.allPaidPrices()
    val health = db.healthDao().history("com.sec.android.app.shealth"); val body = db.bodyDao().versions()
    val settings = PrivateOfferSettings.forApp(context)
    val profile = config.profiles.single { it.retailer == retailer && it.storeId == expectedStore }
    assertEquals(expectedStore, profile.storeId); assertNull(profile.regionId)
    assertEquals(searchTerm != null, profile.interactiveSearch)
    settings.clearSelection()
    val models = ViewModelStore(); lateinit var model: LiveOffersViewModel
    val rx = TrafficStats.getUidRxBytes(context.applicationInfo.uid); val tx = TrafficStats.getUidTxBytes(context.applicationInfo.uid)
    assertTrue(rx >= 0 && tx >= 0)
    suspend fun verifyLocalFacts() {
        assertEquals(before, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows()); assertEquals(paid, dao.allPaidPrices())
        assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertEquals(body, db.bodyDao().versions())
    }
    fun sourceCommit() = requireNotNull(InstrumentationRegistry.getArguments().getString("source_sha"))
        .also { require(it.matches(Regex("[0-9a-f]{40}"))) }
    fun ready() = compose.waitUntil(25_000) { model.state.value.selectionLoaded && !model.state.value.busy }
    fun tap(tag: String) {
        finishDeviceTextEditing(compose)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    try {
        compose.runOnUiThread {
            model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = LiveOffersViewModel(context) as T
            })[LiveOffersViewModel::class.java]
            compose.activity.setContent { DemoTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    LiveOffersScreen(providedModel = model)
                }
            } }
        }
        ready(); tap("Angebotsanbieter"); tap("choose_${retailer.name}"); ready()
        tap("Bestätigte Filiale oder Angebotsregion"); tap("choose_${profile.id}"); ready()
        if (searchTerm != null) {
            compose.onNodeWithTag("live_offers_refresh").assertIsNotEnabled()
            compose.onNodeWithTag("live_search_term").performScrollTo().performTextInput(searchTerm)
        }
        tap("live_offers_refresh")
        compose.waitUntil(90_000) { !model.state.value.busy && model.state.value.result != null }
        val result = requireNotNull(model.state.value.result)
        if (profile.id == "netto-1787-store-offers" && result.code == "ACCESS_BLOCKED") {
            assertEquals(LiveRefreshStatus.FAILED, result.status)
            assertNull(result.batch); assertFalse(result.fromCache); assertFalse(result.cachePermitted)
            compose.onNodeWithTag("live_offer_page").assertDoesNotExist()
            tap("live_automatic_toggle"); ready()
            assertFalse(model.state.value.automatic)
            compose.onNodeWithTag("live_automatic_status").assertTextEquals("Automatik ausgeschaltet")
            assertEquals(false, settings.selection()?.automatic)
            val received = TrafficStats.getUidRxBytes(context.applicationInfo.uid) - rx
            val sent = TrafficStats.getUidTxBytes(context.applicationInfo.uid) - tx
            assertTrue(received > 0 && sent > 0)
            verifyLocalFacts()
            val proof = JSONObject().put("format", "EG_PUBLIC_RETAILER_REFUSAL_V1")
                .put("status", "ACCESS_BLOCKED").put("httpStatus", 403).put("profileId", profile.id)
                .put("sourceHost", profile.endpoint.host).put("storeId", expectedStore)
                .put("retailer", retailer.name).put("sourceCommit", sourceCommit()).put("actualSdk", Build.VERSION.SDK_INT)
                .put("actualNetworkValidated", true).put("defaultProductionHttp", true).put("sourceRepliesInjected", false)
                .put("actualFactsUnchanged", true).put("automaticPaused", true).put("shownOffers", 0)
                .put("uidRxDelta", received).put("uidTxDelta", sent)
            writePublicChainProof(device, proof, profile.id)
            return
        }
        assertEquals("${retailer.name}: ${result.code}/${result.transportDiagnostic}", LiveRefreshStatus.LOADED, result.status)
        val batch = requireNotNull(result.batch)
        assertTrue(batch.offers.isNotEmpty())
        val onlyAppPrices = retailer == LiveRetailer.LIDL
        if (onlyAppPrices) {
            assertTrue(batch.offers.all { it.merchandiseCents == null && it.checkoutCents == null &&
                it.priceDetails?.appCents != null && !it.conditionsConfirmed && it.applicable(batch.scope) })
            val first = batch.offers.first()
            compose.onNodeWithTag("live_app_price_only").assertExists()
            compose.onNodeWithTag("live_app_price_${first.sourceProductId}").assertExists()
            compose.onNodeWithTag("live_review_${first.sourceProductId}").assertDoesNotExist()
            compose.onNodeWithTag("live_load_comparison").assertDoesNotExist()
        } else {
            assertTrue(batch.offers.any { it.merchandiseCents != null })
        }
        assertTrue(batch.offers.size <= if (retailer == LiveRetailer.ALDI_NORD) 512 else 256)
        assertFalse(batch.coverageComplete); assertFalse(result.fromCache); assertFalse(result.cachePermitted)
        val expectedScope = if (expectedStore == null) LiveOfferApplicability.CHAIN_REPORTED else LiveOfferApplicability.STORE_CONFIRMED
        assertTrue(batch.offers.all { it.retailer == retailer && it.applicability == expectedScope &&
            it.storeId == expectedStore && it.regionId == null && URI(it.sourceUrl).host == profile.endpoint.host })
        if (searchTerm != null) {
            assertEquals(searchTerm, batch.scope.searchTerm)
            assertNull(settings.selection())
            assertFalse(model.state.value.automatic)
            val rewe = retailer == LiveRetailer.REWE
            val price = batch.offers.first {
                val details = it.priceDetails
                val hasPublishedDetail = if (rewe) details?.regularCents != null else details?.unitPriceDisplay != null
                it.merchandiseCents != null && hasPublishedDetail
            }
            assertEquals(if (rewe) "/api/stationary-product-search/products" else "/v3/product-search", URI(price.sourceUrl).rawPath)
            assertNull(price.validFrom); assertNull(price.validUntil)
            compose.onNodeWithTag("live_applied_search").assertTextEquals("Geladene Suche · $searchTerm")
            compose.onNodeWithTag("live_profile_details").assertTextContains(profile.label, substring = true)
            val priceField = if (rewe) "regular" else "unit_text"
            compose.onNodeWithTag("live_${priceField}_${price.sourceProductId}").assertExists()
        } else if (expectedStore != null) {
            val market = requireNotNull(batch.offers.first().storeEvidence)
            assertEquals(expectedStore, market.marketId)
            compose.onNodeWithTag("live_canonical_market").assertTextEquals("Markt: ${market.marketName} · ${market.address}")
            if (retailer == LiveRetailer.KAUFLAND) {
                assertTrue(batch.offers.all { it.validFrom != null && it.validUntil != null && it.applicable(batch.scope) })
            }
        }
        if (retailer == LiveRetailer.ALDI_NORD) {
            assertTrue(batch.offers.all { URI(it.sourceUrl).rawPath == "/angebote.html" })
            assertTrue(batch.offers.any { row -> row.validFrom?.let { it <= batch.scope.purchaseOn } == true &&
                row.validUntil?.let { batch.scope.purchaseOn <= it } == true && row.merchandiseCents != null })
        }
        if (retailer in setOf(LiveRetailer.EDEKA, LiveRetailer.NETTO_MD)) {
            assertTrue(batch.offers.all { it.applicable(batch.scope) && !it.conditionsConfirmed && it.quantityMilli == null })
            if (retailer == LiveRetailer.EDEKA) assertTrue(batch.offers.all { it.sourceProductId == null && it.gtin == null })
            else assertTrue(batch.offers.all { !it.sourceProductId.isNullOrBlank() })
            tap("live_automatic_toggle"); ready()
            assertTrue(model.state.value.automatic)
            compose.onNodeWithTag("live_automatic_status").assertTextEquals("Automatik für diese angewendete Auswahl eingeschaltet")
        }
        val pages = (batch.offers.size + 24) / 25
        compose.onNodeWithTag("live_offer_page").assertTextEquals("${batch.offers.size} geladene Treffer · Seite 1/$pages")
        assertTrue(compose.onAllNodesWithText(batch.offers.first().title).fetchSemanticsNodes().isNotEmpty())
        if (batch.offers.size > 25) {
            tap("live_offer_next")
            compose.onNodeWithTag("live_offer_page").assertTextEquals("${batch.offers.size} geladene Treffer · Seite 2/$pages")
            assertTrue(compose.onAllNodesWithText(batch.offers[25].title).fetchSemanticsNodes().isNotEmpty())
        }
        val received = TrafficStats.getUidRxBytes(context.applicationInfo.uid) - rx
        val sent = TrafficStats.getUidTxBytes(context.applicationInfo.uid) - tx
        assertTrue(received > 0 && sent > 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(batch.offers.joinToString("\n") {
            "${it.id}|${it.merchandiseCents}|${it.priceDetails?.appCents}|${it.sourceUrl}|${it.fetchedAt}"
        }.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val proof = JSONObject().put("retailer", retailer.name).put("profileId", profile.id)
            .put("sourceId", batch.sourceId).put("sourceHost", profile.endpoint.host)
            .put("offerCount", batch.offers.size).put("offerFingerprint", digest).put("uidRxDelta", received).put("uidTxDelta", sent)
            .put("branchConfirmed", expectedStore != null).put("storeId", expectedStore ?: JSONObject.NULL)
            .put("productSearch", searchTerm != null).put("appPricesNotUsedForComparison", onlyAppPrices)
            .put("defaultProductionHttp", true).put("sourceRepliesInjected", false)
            .put("observedAt", batch.fetchedAt.toString())
        verifyLocalFacts()
        proof.put("format", "EG_REAL_PUBLIC_CHAIN_UI_V1").put("status", "PASS").put("sourceCommit", sourceCommit())
            .put("actualSdk", Build.VERSION.SDK_INT).put("actualNetworkValidated", true).put("actualFactsUnchanged", true)
        writePublicChainProof(device, proof, profile.id)
    } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
    finally { compose.runOnUiThread { compose.activity.setContent {}; models.clear() }; settings.clearSelection() }
}

private fun writePublicChainProof(device: UiDevice, proof: JSONObject, profileId: String) {
    require(profileId.matches(Regex("[a-z0-9-]{1,160}")))
    val path = "/sdcard/Download/eg-public-retailer-live/market-$profileId.json"
    val bytes = proof.toString(2).toByteArray(Charsets.UTF_8).also { require(it.size <= 16_384) }
    device.executeShellCommand("mkdir -p /sdcard/Download/eg-public-retailer-live")
    val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=$path")
    try {
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
    } finally { pipes.forEach { runCatching { it.close() } } }
    val retained = device.executeShellCommand("wc -c $path")
        .trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.toIntOrNull()
    assertEquals(bytes.size, retained)
}
