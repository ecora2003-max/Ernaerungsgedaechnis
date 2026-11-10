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
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LiveOffersViewModel
import de.ernaehrung.demo.ui.screens.LiveOffersScreen
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
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
    @Test fun embeddedNordAndKauflandHtmlReachActualUiOnlyAfterBothIndependentEndpointsArePermitted(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val food = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val paid = dao.allPaidPrices()
        val health = db.healthDao().history("com.sec.android.app.shealth")
        val day = AppCalendar.today(); val prefix = "synthetic-embedded-${UUID.randomUUID()}"
        val nord = URI("https://www.aldi-nord.de/synthetic-next-offers")
        val kauf = URI("https://filiale.kaufland.de/synthetic-offers")
        val availability = URI("https://availability.example.test/synthetic-store")
        fun payload(file: String): String {
            val text = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("published_sources/$file").bufferedReader().use { it.readText() }
            return text.replace(Regex("2026-11-(09|14)")) { match ->
                if (match.groupValues[1] == "09") day.toString() else day.plusDays(5).toString()
            }
        }
        val requests = mutableListOf<OfferHttpRequest>()
        val http = OfferHttp { request -> requests += request
            when (request.uri) {
                nord -> OfferHttpReply(200, mapOf("content-type" to "text/html"), payload("aldi_nord.html"))
                kauf -> OfferHttpReply(200, mapOf("content-type" to "text/html"), payload("kaufland.html"))
                availability -> OfferHttpReply(200, mapOf("content-type" to "application/json"), payload("kaufland_availability.json"))
                else -> error("No resource/script/other request allowed")
            }
        }
        val nordScope = LiveOfferScope(LiveRetailer.ALDI_NORD, null, null, null, day)
        val kaufScope = LiveOfferScope(LiveRetailer.KAUFLAND, "DE1234", null, null, day)
        val sources = mapOf(
            LiveRetailer.ALDI_NORD to PublishedOfferHttpSource(nord, "$prefix-nord", nordScope,
                PublishedOfferHttpSource.Format.ALDI_NORD_NEXT_DATA, http),
            LiveRetailer.KAUFLAND to PublishedOfferHttpSource(kauf, "$prefix-kauf", kaufScope,
                PublishedOfferHttpSource.Format.KAUFLAND_STORE, http, availabilityEndpoint = availability))
        var permitStoreEndpoint = false
        val registry = LivePermissionRegistry { scope -> when (scope) {
            nordScope -> LiveSourceGrant("$prefix-nord", scope.retailer, "synthetic-independent-nord-review", day, day,
                setOf(nord.host), true, null, scope)
            kaufScope -> LiveSourceGrant("$prefix-kauf", scope.retailer, "synthetic-independent-store-review", day, day,
                setOfNotNull(kauf.host, availability.host.takeIf { permitStoreEndpoint }), true, null, scope)
            else -> null
        } }
        val models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        LiveOffersViewModel(context, LiveOfferRuntime(sources, registry), http) as T
                })[LiveOffersViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        LiveOffersScreen(providedModel = model)
                    }
                } }
            }
            ready(); tap("Angebotsanbieter"); tap("choose_ALDI_NORD"); ready(); tap("live_offers_refresh")
            compose.waitUntil(25_000) { model.state.value.result?.status == LiveRefreshStatus.LOADED && !model.state.value.busy }
            compose.onNodeWithText("Synthetisches ALDI-Nord-Angebot").performScrollTo().assertExists()
            compose.onNodeWithText("Kettenweite Angabe · Filiale ungeprüft · keine genaue Zuordnung").performScrollTo().assertExists()
            assertNull(model.state.value.result!!.batch!!.offers.single().quantityMilli)
            assertEquals(listOf(nord), requests.map { it.uri })
            tap("Angebotsanbieter"); tap("choose_KAUFLAND"); ready()
            compose.onNodeWithTag("live_store_input").performScrollTo().performTextReplacement("DE1234")
            tap("live_offers_refresh")
            compose.waitUntil(25_000) { model.state.value.result?.status == LiveRefreshStatus.BLOCKED && !model.state.value.busy }
            assertNull(model.state.value.result!!.batch); assertEquals(1, requests.size)
            compose.onNodeWithText("Synthetisches Kaufland-Angebot } {").assertDoesNotExist()
            permitStoreEndpoint = true; tap("live_offers_refresh")
            compose.waitUntil(25_000) { model.state.value.result?.status == LiveRefreshStatus.LOADED && !model.state.value.busy }
            compose.onNodeWithText("Synthetisches Kaufland-Angebot } {").performScrollTo().assertExists()
            compose.onNodeWithText("Filialbezug bestätigt · DE1234").performScrollTo().assertExists()
            assertEquals(listOf(nord, kauf, availability), requests.map { it.uri })
            requests.forEach { assertEquals("GET", it.method); assertNull(it.body) }
            assertNull(model.state.value.result!!.batch!!.offers.single().quantityMilli)
            assertEquals(food, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows()); assertEquals(paid, dao.allPaidPrices())
            assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally {
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            PrivateOfferSettings.forApp(context).clearSelection()
        }
    }
    @Test fun suppliedReweAldiAndNettoDocumentsReachActualPriceUiWithSeparateReferenceAppDepositAndAccessFailure(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val food = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val paid = dao.allPaidPrices()
        val bodies = db.bodyDao().versions(); val health = db.healthDao().history("com.sec.android.app.shealth")
        val day = AppCalendar.today(); val prefix = "synthetic-published-${UUID.randomUUID()}"
        data class Source(val retailer: LiveRetailer, val store: String?, val url: String,
            val format: PublishedOfferHttpSource.Format, val file: String)
        val known = listOf(
            Source(LiveRetailer.REWE, "000001", "https://www.rewe.de/api/stationary-product-search/products?query=Synthetisch&wwIdent=000001",
                PublishedOfferHttpSource.Format.REWE_STATIONARY, "rewe.json"),
            Source(LiveRetailer.ALDI_SUED, "B000", "https://api.aldi-sued.de/v3/product-search?q=Synthetisch&servicePoint=B000&serviceType=walk-in&limit=12&offset=0&currency=EUR",
                PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN, "aldi_sued.json"),
            Source(LiveRetailer.NETTO_MD, null, "https://www.netto-online.de/angebote/c-N07",
                PublishedOfferHttpSource.Format.NETTO_BRANCH_HTML, "netto.html"))
        val requests = mutableListOf<OfferHttpRequest>(); var denyHtml = false
        val http = OfferHttp { request ->
            requests += request
            val match = known.single { it.url == request.uri.toString() }
            if (denyHtml && match.retailer == LiveRetailer.NETTO_MD) OfferHttpReply(403, emptyMap(), "synthetic access denied")
            else {
                val payload = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                    .open("published_sources/${match.file}").bufferedReader().use { it.readText() }
                OfferHttpReply(200, mapOf("content-type" to if (match.file.endsWith("html")) "text/html" else "application/json"), payload)
            }
        }
        val scopes = known.associate { it.retailer to LiveOfferScope(it.retailer, it.store, null, null, day) }
        val sources = known.associate { source -> source.retailer to PublishedOfferHttpSource(URI(source.url),
            "$prefix-${source.retailer}", scopes.getValue(source.retailer), source.format, http) }
        val grants = known.associate { source -> source.retailer to LiveSourceGrant("$prefix-${source.retailer}",
            source.retailer, "synthetic-independent-host-review-not-a-live-permission", day, day, setOf(URI(source.url).host),
            true, null, scopes.getValue(source.retailer), sources.getValue(source.retailer).capability) }
        val runtime = LiveOfferRuntime(sources, LivePermissionRegistry { scope -> grants[scope.retailer]?.takeIf { it.verifiedScope == scope } })
        val models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        LiveOffersViewModel(context, runtime, http) as T
                })[LiveOffersViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        LiveOffersScreen(providedModel = model)
                    }
                } }
            }
            for (source in known) {
                ready(); tap("Angebotsanbieter"); tap("choose_${source.retailer}"); ready()
                compose.onNodeWithTag("live_store_input").performScrollTo().performTextReplacement(source.store.orEmpty())
                tap("live_offers_refresh")
                compose.waitUntil(25_000) { model.state.value.result?.status == LiveRefreshStatus.LOADED &&
                    model.state.value.scope == scopes[source.retailer] && !model.state.value.busy }
                when (source.retailer) {
                    LiveRetailer.REWE -> {
                        compose.onNodeWithTag("live_regular_101").performScrollTo().assertTextContains("1,89 €", substring = true)
                        compose.onNodeWithTag("live_regular_zero").performScrollTo().assertTextContains("0,00 €", substring = true)
                        compose.onNodeWithText("Warenpreis: 1,29 € · Pfand: 0,25 € · zusammen: 1,54 €").performScrollTo().assertExists()
                    }
                    LiveRetailer.ALDI_SUED -> {
                        compose.onNodeWithTag("live_regular_primary").performScrollTo().assertTextContains("unbekannt", substring = true)
                        compose.onNodeWithTag("live_previous_primary").performScrollTo().assertTextContains("1,99 €", substring = true)
                        compose.onNodeWithText("Warenpreis: 0,00 € · Pfand: 0,00 € · zusammen: 0,00 €").performScrollTo().assertExists()
                        assertNull(model.state.value.result!!.batch!!.offers.first().priceDetails!!.regularCents)
                    }
                    LiveRetailer.NETTO_MD -> {
                        compose.onNodeWithTag("live_app_price_n-offer").performScrollTo().assertTextContains("0,79 €", substring = true)
                        compose.onNodeWithTag("live_unit_text_n-offer").performScrollTo().assertTextContains("2,00 €/kg", substring = true)
                        compose.onNodeWithTag("live_app_price_n-zero").performScrollTo().assertTextContains("0,00 €", substring = true)
                        compose.onNodeWithText("Warenpreis: 1,00 € · Pfand: unbekannt · zusammen: unbekannt").performScrollTo().assertExists()
                        assertTrue(model.state.value.result!!.batch!!.offers.all { it.storeId == null &&
                            it.applicability == LiveOfferApplicability.CHAIN_REPORTED && it.quantityMilli == null })
                    }
                    else -> error("Only these three synthetic source shapes")
                }
            }
            assertEquals(3, requests.size)
            denyHtml = true; tap("live_offers_refresh")
            compose.waitUntil(25_000) { model.state.value.result?.status == LiveRefreshStatus.FAILED && !model.state.value.busy }
            assertTrue(model.state.value.result!!.fromCache)
            compose.onNodeWithText("Anbieter sperrt den Zugriff. Kein automatischer Wiederholungsversuch.").performScrollTo().assertExists()
            compose.onNodeWithTag("live_app_price_n-offer").performScrollTo().assertTextContains("0,79 €", substring = true)
            assertEquals(4, requests.size)
            requests.forEach { assertEquals("GET", it.method); assertNull(it.body)
                assertFalse(it.uri.path.contains("Wishlist")); assertTrue(it.headers.keys.none { key -> key.equals("Authorization", true) }) }
            assertEquals(food, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows())
            assertEquals(paid, dao.allPaidPrices()); assertEquals(bodies, db.bodyDao().versions())
            assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally {
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            PrivateOfferSettings.forApp(context).clearSelection()
        }
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
    @Test fun authorizedSyntheticHttpPricesUseRealCatalogReviewsAndDiscardReplacedGrantsWithoutBooking(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val day = AppCalendar.today(); val id = "synthetic-ui-${UUID.randomUUID()}"
        // Use the actual catalog editor/FoodStore quantity convention. The old
        // lambda-only registry concealed a 1000x whole-unit/milliunit mismatch.
        val catalog = mapOf("a" to "500", "b" to "1000").mapValues { (sku, pack) ->
            val version = FoodStore(db).rememberProduct("$id-catalog-$sku", ProductInput(
                "Synthetic independently reviewed $sku", packageAmount = Amounts.parse(pack),
                source = Source.USER, quality = Quality.CONFIRMED))
            requireNotNull(dao.version(version))
        }
        assertEquals(500_000L, catalog.getValue("a").packageAmount)
        assertEquals(1_000_000L, catalog.getValue("b").packageAmount)
        val operations = dao.operationCount(); val movements = dao.stockMovementSnapshotRows(); val prices = dao.allPaidPrices()
        val health = db.healthDao().history("com.sec.android.app.shealth")
        val selection = LiveOfferScope(LiveRetailer.LIDL, "DE123", null, null, day)
        val endpoint = URI("https://offers.example.test/selected-store")
        val grant = AtomicReference(LiveSourceGrant(id, LiveRetailer.LIDL, "synthetic-independent-host-fixture-v1", day, day,
            setOf("offers.example.test"), true, 3600, selection))
        var calls = 0
        val http = OfferHttp { request ->
            check(request.uri == endpoint); calls++
            OfferHttpReply(200, mapOf("content-type" to "application/json"),
                """{"offers":[{"id":"a","title":"Synthetische Referenz","offerType":"StoreSpecialPriceDiscount","category":"Store","priceBox":{"largePartNumeric":1.99},"startValidityDate":"$day","endValidityDate":"$day"},{"id":"b","title":"Synthetische Alternative","offerType":"StoreSpecialPriceDiscount","category":"Store","priceBox":{"largePartNumeric":2.99},"startValidityDate":"$day","endValidityDate":"$day"}]}""")
        }
        val source = PublishedOfferHttpSource(endpoint, id, selection, PublishedOfferHttpSource.Format.LIDL_STORE_V4, http)
        val runtime = LiveOfferRuntime(mapOf(LiveRetailer.LIDL to source), LivePermissionRegistry { if (it == selection) grant.get() else null },
            PrivateOfferSettings.forApp(context))
        val reviewed = mapOf("a" to 500L, "b" to 1000L).mapValues { (sku, amount) ->
            LiveArticleReview(catalog.getValue(sku).id, ShoppingComparabilityKernel.Article("synthetic-reviewed-food",
                quantity = ShoppingUnitRateKernel.Quantity(amount, "g", "NET", 1, true)), null, true, true, true)
        }
        val confirmedAt = Instant.now().toEpochMilli()
        val confirmations = reviewed.map { (sku, review) ->
            val version = catalog.getValue(sku)
            version.id to HostCatalogArticleRegistry.ConfirmedArticle(version.id, version.revision,
                version.recordedAt, confirmedAt, review.article, review.organic)
        }.toMap()
        val comparison = LiveOfferComparison(LiveArticleReviewRegistry { offer, _, _ -> reviewed[offer.sourceProductId] },
            HostCatalogArticleRegistry(dao.allVersions(), confirmations),
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
            tap("live_remember_$id:a")
            tap("live_reference_compare_$id:b")
            compose.onNodeWithTag("live_comparison_result").assertTextEquals("Alternative hat einen geringeren Waren-Grundpreis.")
            // A new independent review keeps the source ID but removes the
            // automatic-read rhythm. Returning to the real Activity must remove
            // old observations/reference/comparison, without fetching again.
            grant.set(grant.get().copy(evidenceRef = "synthetic-independent-host-fixture-v2", minimumRefreshSeconds = null))
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(25_000) {
                compose.onAllNodesWithText("Synthetische Alternative").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("live_session_reference").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("live_comparison_result").fetchSemanticsNodes().isEmpty()
            }
            compose.waitUntil(25_000) { PrivateOfferSettings.forApp(context).selection()?.automatic == false }
            assertEquals(1, calls); assertFalse(model.state.value.automatic)
            // A replacement grant authorizes a genuinely new explicit read,
            // not a relabelled old response. Cache withdrawal is checked also
            // at the action boundary, before waiting for another timer tick.
            tap("live_offers_refresh"); ready()
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Synthetische Alternative").fetchSemanticsNodes().size == 1 }
            assertEquals(2, calls)
            assertEquals("synthetic-independent-host-fixture-v2", model.state.value.authorizedEvidenceRef)
            compose.onAllNodesWithTag("live_session_reference").assertCountEquals(0)
            tap("live_remember_$id:a")
            compose.runOnUiThread {
                grant.set(grant.get().copy(cachePermitted = false))
                model.compareWithReference("$id:b")
            }
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Synthetische Alternative").fetchSemanticsNodes().isEmpty() }
            compose.onAllNodesWithTag("live_session_reference").assertCountEquals(0)
            compose.onAllNodesWithTag("live_comparison_result").assertCountEquals(0)
            assertEquals(2, calls)
            tap("live_offers_refresh"); ready()
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Synthetische Alternative").fetchSemanticsNodes().size == 1 }
            tap("live_remember_$id:a")
            compose.onAllNodesWithTag("live_session_reference").assertCountEquals(0)
            assertEquals(3, calls)
            assertEquals(operations, dao.operationCount()); assertEquals(movements, dao.stockMovementSnapshotRows())
            assertEquals(prices, dao.allPaidPrices())
            assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally {
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            PrivateOfferSettings.forApp(context).clearSelection()
        }
    }
}
