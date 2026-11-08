package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ernaehrung.demo.data.local.FoodDatabase
import de.ernaehrung.demo.data.offers.*
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
    @Test fun eightRetailersStayIndependentlyBlockedAndKnusprConsentDoesNotSurviveRecreationWithoutAnyActualBooking() = runBlocking {
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
}
