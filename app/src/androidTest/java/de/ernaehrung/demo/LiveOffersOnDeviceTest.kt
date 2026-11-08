package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ernaehrung.demo.data.local.FoodDatabase
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.local.AppCalendar
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LiveOffersViewModel
import de.ernaehrung.demo.ui.screens.LiveOffersScreen
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real standard UI / AndroidKeyStore / private AtomicFile on disposable
 * emulators. No supplier request, live OAuth or invented source grant. */
@RunWith(AndroidJUnit4::class)
class LiveOffersOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        prepareUnlockedTestDevice()
        PrivateOfferSettings.forApp(compose.activity).clearSelection()
    }
    private fun tap(tag: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun ready() = compose.waitUntil(25_000) {
        compose.onAllNodes(hasTestTag("live_offers_refresh") and isEnabled()).fetchSemanticsNodes().size == 1
    }
    @Test fun eightRetailersStayIndependentlyBlockedAndKnusprConsentDoesNotSurviveRecreationWithoutAnyActualBooking(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val operations = dao.operationCount(); val movements = dao.stockMovementSnapshotRows()
        val prices = dao.allPaidPrices(); val bodies = db.bodyDao().versions(); val health = db.healthDao().history("com.sec.android.app.shealth")
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_SHOPPING")
            tap("shopping_open_live_offers"); ready()
            for (retailer in LiveRetailer.entries.filter { it != LiveRetailer.KNUSPR }) {
                tap("Angebotsanbieter"); tap("choose_${retailer.name}"); ready()
                tap("live_offers_refresh")
                compose.waitUntil(25_000) { compose.onAllNodesWithTag("live_offers_blocked").fetchSemanticsNodes().size == 1 }
                tap("live_automatic_toggle"); ready()
                compose.onNodeWithTag("live_automatic_status").assertTextEquals("Automatik ausgeschaltet")
            }
            tap("Angebotsanbieter"); tap("choose_KNUSPR"); ready()
            compose.onNodeWithTag("knuspr_connect").assertIsNotEnabled()
            compose.onNodeWithText("Diese Knuspr-Verbindung ausdrücklich beginnen").performScrollTo().performClick()
            compose.onNodeWithTag("knuspr_connect").assertIsEnabled()
            compose.activityRule.scenario.recreate(); ready()
            // Unapplied provider selection is UI state; no transient browser
            // consent is restored and no OAuth request is launched by recreation.
            compose.onNodeWithTag("knuspr_connect").assertIsNotEnabled()
            assertEquals(operations, dao.operationCount()); assertEquals(movements, dao.stockMovementSnapshotRows())
            assertEquals(prices, dao.allPaidPrices()); assertEquals(bodies, db.bodyDao().versions())
            assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertNull(connectivity.activeNetwork)
            tap("live_open_shopping")
            compose.onNodeWithTag("workspace_page_SHOPPING").assertExists()
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally { PrivateOfferSettings.forApp(context).clearSelection() }
    }
    @Test fun realPrivateKeystoreRejectsReplayTamperAndLateLogoutResponseAndPersistsRefreshFloorWithoutPriceData() {
        val context = compose.activity.applicationContext
        val store = PrivateKnusprStore.forApp(context); store.clear()
        val nonce = UUID.randomUUID().toString()
        val server = KnusprAuthorizationServer(URI("https://login.example.test"), URI("https://login.example.test/authorize"),
            URI("https://login.example.test/token"), null, listOf("products:read"))
        val pending = KnusprPendingAuthorization(server, "synthetic-client", nonce, "synthetic-verifier-$nonce",
            "${context.packageName}://knuspr-oauth", Instant.now().toEpochMilli())
        val token = KnusprToken("synthetic-token-$nonce", "synthetic-refresh-$nonce", null, "synthetic-client", server)
        val file = File(context.noBackupFilesDir, "knuspr-oauth-v1.bin")
        try {
            val old = store.beginAttempt(); val current = store.beginAttempt()
            assertFalse(store.stage(old, pending)); assertTrue(store.stage(current, pending))
            assertEquals(pending, store.read().pending)
            val sealed = file.readBytes()
            assertFalse(sealed.toString(Charsets.ISO_8859_1).contains(nonce))
            assertEquals(pending, store.claim(nonce))
            assertTrue(runCatching { store.claim(nonce) }.isFailure)
            store.clear(); assertFalse(store.finish(nonce, token)); assertNull(store.read().token)
            assertTrue(store.stage(store.beginAttempt(), pending)); store.claim(nonce); assertTrue(store.finish(nonce, token))
            assertEquals(token, store.read().token)
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(token.access))
            val damaged = file.readBytes(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte(); file.writeBytes(damaged)
            assertTrue(runCatching { store.read() }.isFailure)
            store.clear(); assertFalse(file.exists()); assertNull(store.read().token)
            val settings = PrivateOfferSettings.forApp(context)
            val at = Instant.parse("2026-11-08T10:00:00Z")
            settings.record("synthetic-device", OfferRefreshAttempt(at, true))
            assertEquals(OfferRefreshAttempt(at, true), settings.last("synthetic-device"))
            assertNull(settings.selection())
            assertFalse(File(context.noBackupFilesDir, "live-offer-controls-v1.json").readText().contains(token.access))
        } finally { store.clear() }
    }
    @Test fun authorizedSyntheticHttpPricesRenderUnknownSourceQuantitiesAndUseSeparateReviewedCatalogPackagesForComparisonWithoutBooking(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val operations = dao.operationCount(); val movements = dao.stockMovementSnapshotRows(); val prices = dao.allPaidPrices()
        val day = AppCalendar.today(); val id = "synthetic-ui-${UUID.randomUUID()}"
        val selection = LiveOfferScope(LiveRetailer.LIDL, "DE123", null, null, day)
        val endpoint = URI("https://offers.example.test/selected-store")
        val grant = LiveSourceGrant(id, LiveRetailer.LIDL, "synthetic-independent-host-fixture", day, day,
            setOf("offers.example.test"), true, 3600, selection)
        var calls = 0
        val http = OfferHttp { request ->
            check(request.uri == endpoint); calls++
            OfferHttpReply(200, mapOf("content-type" to "application/json"),
                """{"offers":[{"id":"a","title":"Synthetische Referenz","offerType":"StoreSpecialPriceDiscount","category":"Store","priceBox":{"largePartNumeric":1.99},"startValidityDate":"$day","endValidityDate":"$day"},{"id":"b","title":"Synthetische Alternative","offerType":"StoreSpecialPriceDiscount","category":"Store","priceBox":{"largePartNumeric":2.99},"startValidityDate":"$day","endValidityDate":"$day"}]}""")
        }
        val source = PublishedOfferHttpSource(endpoint, id, selection, PublishedOfferHttpSource.Format.LIDL_STORE_V4, http)
        val runtime = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { if (it == selection) grant else null },
            PrivateOfferSettings.forApp(context))
        val reviewed = mapOf("a" to 500L, "b" to 1000L).mapValues { (sku, amount) ->
            LiveArticleReview("synthetic-version-$sku", ShoppingComparabilityKernel.Article("synthetic-reviewed-food",
                quantity = ShoppingUnitRateKernel.Quantity(amount, "g", "NET", 1, true)), null, true, true, true)
        }
        val comparison = LiveOfferComparison(LiveArticleReviewRegistry { offer, _, _ -> reviewed[offer.sourceProductId] },
            ShoppingPriceComparisonGate.ConfirmedArticleRegistry { version, article, _, _ -> reviewed.values.any { it.productVersionId == version && it.article == article } },
            sourceStillPermitted = { scope, offer -> runtime.policy(scope)?.sourceId == offer.sourceId })
        val models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        LiveOffersViewModel(context, runtime, http, comparison) as T
                })[LiveOffersViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) { LiveOffersScreen(providedModel = model) }
                } }
            }
            ready(); tap("Angebotsanbieter"); tap("choose_LIDL"); ready()
            compose.onNodeWithTag("live_store_input").performScrollTo().performTextReplacement("DE123")
            tap("live_offers_refresh")
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Synthetische Alternative").fetchSemanticsNodes().size == 1 }
            assertEquals(1, calls)
            compose.onAllNodesWithText("Grundpreis: unbekannt").assertCountEquals(2)
            compose.onAllNodesWithText("Menge: unbekannt  · Basis unbekannt").assertCountEquals(2)
            tap("Referenzangebot dieser Liste"); tap("choose_$id:a")
            tap("Alternativangebot dieser Liste"); tap("choose_$id:b")
            compose.onNodeWithText("Waren-Grundpreise geprüft vergleichen").performScrollTo().performClick()
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("live_comparison_result").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("live_comparison_result").assertTextEquals("Alternative hat einen geringeren Waren-Grundpreis.")
            compose.onNodeWithText("Referenz: 3,98 €/kg · NET").assertExists()
            compose.onNodeWithText("Alternative: 2,99 €/kg · NET").assertExists()
            tap("live_automatic_toggle"); ready()
            compose.onNodeWithTag("live_automatic_status").assertTextEquals("Automatik für diese angewendete Auswahl eingeschaltet")
            tap("live_offers_refresh"); ready(); assertEquals(1, calls) // same durable source interval
            assertEquals(operations, dao.operationCount()); assertEquals(movements, dao.stockMovementSnapshotRows())
            assertEquals(prices, dao.allPaidPrices())
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally {
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            PrivateOfferSettings.forApp(context).clearSelection()
        }
    }
}
