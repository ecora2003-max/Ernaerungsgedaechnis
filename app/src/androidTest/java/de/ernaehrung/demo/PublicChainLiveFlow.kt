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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Explicit live class only: real default HTTP/UI, no fixture replies or
 * independently forged article confirmations. National advertising remains
 * national; this cannot prove a local branch price or complete market coverage. */
internal suspend fun publicChainLiveFlow(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
    check(Build.VERSION.SDK_INT == 35)
    check(InstrumentationRegistry.getArguments().getString("eg_live_source") == "true")
    prepareUnlockedTestDevice()
    val context = compose.activity.applicationContext
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    device.executeShellCommand("cmd connectivity airplane-mode disable")
    device.executeShellCommand("svc wifi enable"); device.executeShellCommand("svc data disable")
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
    val config = ReviewedOfferSources.bundled(context); assertEquals(0, config.invalidProfiles)
    val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
    val before = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val paid = dao.allPaidPrices()
    val health = db.healthDao().history("com.sec.android.app.shealth"); val body = db.bodyDao().versions()
    val settings = PrivateOfferSettings.forApp(context)
    val proofs = JSONArray()
    for (retailer in listOf(LiveRetailer.ALDI_NORD, LiveRetailer.NETTO_MD, LiveRetailer.ALDI_SUED)) {
        val profile = config.profiles.single { it.retailer == retailer }
        assertNull(profile.storeId); assertNull(profile.regionId)
        settings.clearSelection()
        val models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        val rx = TrafficStats.getUidRxBytes(context.applicationInfo.uid); val tx = TrafficStats.getUidTxBytes(context.applicationInfo.uid)
        assertTrue(rx >= 0 && tx >= 0)
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
            tap("live_offers_refresh")
            compose.waitUntil(90_000) { !model.state.value.busy && model.state.value.result != null }
            val result = requireNotNull(model.state.value.result)
            assertEquals("${retailer.name}: ${result.code}/${result.transportDiagnostic}", LiveRefreshStatus.LOADED, result.status)
            val batch = requireNotNull(result.batch)
            assertTrue(batch.offers.isNotEmpty()); assertTrue(batch.offers.any { it.merchandiseCents != null })
            assertTrue(batch.offers.size <= if (retailer == LiveRetailer.ALDI_NORD) 512 else 256)
            assertFalse(batch.coverageComplete); assertFalse(result.fromCache); assertFalse(result.cachePermitted)
            assertTrue(batch.offers.all { it.retailer == retailer && it.applicability == LiveOfferApplicability.CHAIN_REPORTED &&
                it.storeId == null && it.regionId == null && URI(it.sourceUrl).host == profile.endpoint.host })
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
                "${it.id}|${it.merchandiseCents}|${it.sourceUrl}|${it.fetchedAt}"
            }.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            proofs.put(JSONObject().put("retailer", retailer.name).put("sourceId", batch.sourceId).put("sourceHost", profile.endpoint.host)
                .put("offerCount", batch.offers.size).put("offerFingerprint", digest).put("uidRxDelta", received).put("uidTxDelta", sent)
                .put("branchConfirmed", false).put("defaultProductionHttp", true).put("sourceRepliesInjected", false)
                .put("observedAt", batch.fetchedAt.toString()))
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally { compose.runOnUiThread { compose.activity.setContent {}; models.clear() }; settings.clearSelection() }
    }
    assertEquals(before, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows()); assertEquals(paid, dao.allPaidPrices())
    assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertEquals(body, db.bodyDao().versions())
    val source = requireNotNull(InstrumentationRegistry.getArguments().getString("source_sha")).also { require(it.matches(Regex("[0-9a-f]{40}"))) }
    val proof = JSONObject().put("format", "EG_REAL_PUBLIC_CHAIN_UI_V1").put("status", "PASS").put("sourceCommit", source)
        .put("actualSdk", Build.VERSION.SDK_INT).put("actualNetworkValidated", true).put("actualFactsUnchanged", true).put("sources", proofs)
    val bytes = proof.toString(2).toByteArray(Charsets.UTF_8).also { require(it.size <= 16_384) }
    device.executeShellCommand("mkdir -p /sdcard/Download/eg-public-retailer-live")
    val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/sdcard/Download/eg-public-retailer-live/chain-proof.json")
    try {
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
    } finally { pipes.forEach { runCatching { it.close() } } }
    val retained = device.executeShellCommand("wc -c /sdcard/Download/eg-public-retailer-live/chain-proof.json")
        .trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.toIntOrNull()
    assertEquals(bytes.size, retained)
}
