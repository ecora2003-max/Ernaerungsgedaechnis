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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
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
import kotlinx.coroutines.*
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
        compose.onAllNodes(hasTestTag("live_offers_screen") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Bereit")).fetchSemanticsNodes().size == 1
    }
    @Test fun actualCredentialReviewConnectsLivePriceToKnownNeedAndComparisonWithoutAnyPurchaseAndIsLostOnReopen(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao(); val id = "synthetic-article-${UUID.randomUUID()}"
        val versionId = FoodStore(db).rememberProduct(id, ProductInput("Synthetic reviewed 250g article", packageAmount = 250_000,
            source = Source.USER, quality = Quality.CONFIRMED))
        val day = AppCalendar.today()
        val need = ShoppingNeed(versionId, "Synthetic reviewed 250g article", BaseUnit.GRAM, 600_000, 1)
        val line = ShoppingLine(need, null, emptyList(), day.toString(), day.toString(), null, null, null, null, null,
            setOf(ShoppingIssue.UNKNOWN_REQUIREMENT, ShoppingIssue.NO_OFFER), false, false, id, id)
        val report = ShoppingReport(ShoppingScope(day.toString(), day.toString(), day.toString()), Instant.now().toString(),
            listOf(line), emptyList(), emptyList(), null, null, true, ShoppingBudget(null, null, null, null))
        val before = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val paid = dao.allPaidPrices()
        val health = db.healthDao().history("com.sec.android.app.shealth"); val bodies = db.bodyDao().versions()
        val config = PennyPublicSourceFixtures.configuration(day, id)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = OfferHttp { request -> calls.incrementAndGet(); OfferHttpReply(200, mapOf("content-type" to "application/json"),
            if (request.uri.path == "/.rest/market") PennyPublicSourceFixtures.markets() else PennyPublicSourceFixtures.offers()) }
        var models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        fun mount() = compose.runOnUiThread {
            model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LiveOffersViewModel(context, http = http, sources = config) as T // Real review registry, default Keystore verifier.
            })[LiveOffersViewModel::class.java]
            compose.activity.setContent { DemoTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    LiveOffersScreen(providedModel = model, shopping = report)
                }
            } }
        }
        fun check(text: String) { finishDeviceTextEditing(compose); compose.onNodeWithText(text).performScrollTo().performClick() }
        fun authenticate(sku: String) {
            tap("live_review_$sku")
            compose.waitUntil(25_000) { model.state.value.articleCatalog.any { it.id == versionId } }
            tap("Eigene Produktfassung"); tap("choose_$versionId")
            compose.onNodeWithTag("article_review_type").performScrollTo().performTextReplacement("Selbst geprüfter synthetischer Artikel")
            tap("Bestätigte Mengenbasis"); tap("choose_NET"); tap("article_review_today")
            check("Genau diese Produktfassung und Variante im Originalangebot geprüft")
            check("Einzelmenge, Packungszahl und Mengenbasis geprüft")
            check("Preisbedingungen, Filiale und Gültigkeit für diesen Einkauf geprüft")
            tap("article_review_confirm")
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            var editor: androidx.test.uiautomator.UiObject2? = null
            compose.waitUntil(20_000) {
                if (device.currentPackageName in setOf("com.android.systemui", "com.android.settings")) {
                    editor = device.findObject(By.res("com.android.systemui", "lockPassword"))
                        ?: device.findObject(By.res("com.android.settings", "password_entry"))
                        ?: device.findObject(By.clazz("android.widget.EditText"))
                }
                editor != null
            }
            requireNotNull(editor).click(); device.waitForIdle(); device.executeShellCommand("input text 2468"); device.pressEnter()
            compose.waitUntil(25_000) { "$id:$sku" in model.state.value.reviewedOffers && !model.state.value.busy }
        }
        try {
            mount(); ready(); tap("Bestätigte Filiale oder Angebotsregion"); tap("choose_$id"); ready(); tap("live_offers_refresh"); ready()
            assertEquals(2, calls.get()); assertTrue(model.state.value.reviewedOffers.isEmpty())
            compose.onNodeWithTag("live_need_synthetic-a").assertIsNotEnabled()
            authenticate("synthetic-a")
            tap("live_need_synthetic-a"); ready()
            compose.onNodeWithTag("live_need_quantity").assertTextEquals("600 g bekannt fehlend · 3 Angebotsgebinde")
            compose.onNodeWithTag("live_need_cost").assertTextEquals("Warenkosten für diesen Bedarf: 4,77 €")
            assertEquals(150_000L, model.state.value.needPreviews.single().packs.surplus)
            authenticate("synthetic-zero")
            tap("Referenzangebot dieser Liste"); tap("choose_$id:synthetic-a")
            tap("Alternativangebot dieser Liste"); tap("choose_$id:synthetic-zero")
            check("Waren-Grundpreise geprüft vergleichen"); ready()
            compose.onNodeWithTag("live_comparison_result").assertTextEquals("Alternative hat einen geringeren Waren-Grundpreis.")
            assertEquals(before, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows()); assertEquals(paid, dao.allPaidPrices())
            assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertEquals(bodies, db.bodyDao().versions())
            assertTrue(model.state.value.result!!.batch!!.offers.all { it.quantityMilli == null && it.validFrom == null })
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }; models = ViewModelStore(); mount(); ready()
            assertTrue(model.state.value.reviewedOffers.isEmpty()); assertTrue(model.state.value.needPreviews.isEmpty())
            assertNull(model.state.value.comparison); assertNull(model.state.value.result); assertEquals(2, calls.get()); assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally { compose.runOnUiThread { compose.activity.setContent {}; models.clear() }; PrivateOfferSettings.forApp(context).clearSelection() }
    }
    @Test fun pennyCanonicalRegionAndUnconfirmedArticleDatesReachUiWithoutCachingOrActualBookings(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val before = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val prices = dao.allPaidPrices()
        val source = "synthetic-penny-${UUID.randomUUID()}"
        val config = PennyPublicSourceFixtures.configuration(AppCalendar.today(), source)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = OfferHttp { request ->
            calls.incrementAndGet()
            OfferHttpReply(200, mapOf("content-type" to "application/json"),
                if (request.uri.path == "/.rest/market") PennyPublicSourceFixtures.markets() else PennyPublicSourceFixtures.offers())
        }
        val models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        LiveOffersViewModel(context, http = http, sources = config) as T
                })[LiveOffersViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        LiveOffersScreen(providedModel = model)
                    }
                } }
            }
            ready(); tap("Bestätigte Filiale oder Angebotsregion"); tap("choose_$source"); ready()
            tap("live_offers_refresh"); ready()
            compose.waitUntil(25_000) { model.state.value.result?.status == LiveRefreshStatus.LOADED }
            compose.onNodeWithTag("live_canonical_market").assertTextEquals("Markt: Synthetic PENNY · Teststraße 1, 00000 Teststadt")
            compose.onNodeWithText("Synthetic first offer").assertExists()
            compose.onNodeWithText("Synthetic online price").assertDoesNotExist()
            assertEquals(2, calls.get()); assertEquals(3, model.state.value.result!!.batch!!.offers.size)
            assertTrue(model.state.value.result!!.batch!!.offers.all { it.validFrom == null && it.validUntil == null })
            assertFalse(model.state.value.result!!.cachePermitted)
            tap("live_offers_refresh"); ready()
            assertEquals(LiveRefreshStatus.THROTTLED, model.state.value.result?.status)
            assertNull(model.state.value.result?.batch); assertEquals(2, calls.get())
            assertEquals(before, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows())
            assertEquals(prices, dao.allPaidPrices()); assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally { compose.runOnUiThread { compose.activity.setContent {}; models.clear() }; PrivateOfferSettings.forApp(context).clearSelection() }
    }
    @Test fun reviewedHostProfilesDriveActualBranchPickerAndDefaultRuntimeAcrossReopenWithoutFactsOrNetwork(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val operations = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val paid = dao.allPaidPrices()
        val body = db.bodyDao().versions(); val health = db.healthDao().history("com.sec.android.app.shealth")
        val day = AppCalendar.today()
        val profileRun = "synthetic-profile-${UUID.randomUUID()}"
        val directory = ReviewedOfferSources.decode(ReviewedOfferSourceFixtures.document(
            ReviewedOfferSourceFixtures.row(LiveRetailer.LIDL, day, "DE123", "$profileRun-DE123"),
            ReviewedOfferSourceFixtures.row(LiveRetailer.LIDL, day, "DE456", "$profileRun-DE456")))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = OfferHttp { request ->
            val store = request.uri.path.substringAfterLast('/')
            check(store in setOf("DE123", "DE456")); calls.incrementAndGet()
            OfferHttpReply(200, mapOf("content-type" to "application/json"),
                """{"offers":[{"id":"synthetic-$store","title":"Synthetisches Angebot $store","offerType":"StoreSpecialPriceDiscount","category":"Store","priceBox":{"largePartNumeric":1.99},"startValidityDate":"$day","endValidityDate":"$day"}]}""")
        }
        var models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        fun mount() {
            compose.runOnUiThread {
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        LiveOffersViewModel(context, http = http, sources = directory) as T
                })[LiveOffersViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        LiveOffersScreen(providedModel = model)
                    }
                } }
            }
            compose.waitUntil(25_000) { model.state.value.selectionLoaded }
        }
        try {
            mount(); ready(); tap("Angebotsanbieter"); tap("choose_LIDL")
            compose.onNodeWithTag("live_offers_refresh").assertIsNotEnabled()
            tap("Bestätigte Filiale oder Angebotsregion"); tap("choose_$profileRun-DE123"); ready()
            compose.onNodeWithTag("live_store_input").assertDoesNotExist()
            tap("live_offers_refresh"); ready()
            compose.waitUntil(25_000) { model.state.value.result?.status == LiveRefreshStatus.LOADED }
            compose.onNodeWithText("Synthetisches Angebot DE123").assertExists(); assertEquals(1, calls.get())
            assertEquals("DE123", model.state.value.result!!.batch!!.offers.single().storeId)
            tap("live_automatic_toggle"); ready(); assertTrue(model.state.value.automatic)
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            models = ViewModelStore(); mount(); ready()
            compose.waitUntil(25_000) {
                compose.onAllNodes(hasTestTag("live_profile_details") and hasText("DE123", substring = true)).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("live_profile_details").assertTextEquals("Synthetische Filiale DE123 · Geltung $day bis ${day.plusDays(6)}")
            assertTrue(model.state.value.automatic); assertEquals(1, calls.get())
            tap("live_offers_refresh"); ready()
            assertEquals(LiveRefreshStatus.THROTTLED, model.state.value.result?.status)
            assertNull(model.state.value.result?.batch); assertEquals(1, calls.get())
            tap("Bestätigte Filiale oder Angebotsregion"); tap("choose_$profileRun-DE456"); ready()
            assertNull(model.state.value.result); assertFalse(model.state.value.automatic)
            tap("live_offers_refresh"); ready()
            compose.waitUntil(25_000) { model.state.value.result?.status == LiveRefreshStatus.LOADED }
            compose.onNodeWithText("Synthetisches Angebot DE456").assertExists()
            compose.onNodeWithText("Synthetisches Angebot DE123").assertDoesNotExist(); assertEquals(2, calls.get())
            val controls = File(context.noBackupFilesDir, "live-offer-controls-v1.json").readText()
            assertFalse(controls.contains("Synthetisches Angebot")); assertFalse(controls.contains("1.99"))
            assertEquals(operations, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows())
            assertEquals(paid, dao.allPaidPrices()); assertEquals(body, db.bodyDao().versions())
            assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("live-offers-flow"); throw failure }
        finally { compose.runOnUiThread { compose.activity.setContent {}; models.clear() }; PrivateOfferSettings.forApp(context).clearSelection() }
    }
    @Test fun explicitKnusprRenewalRotatesPrivateCredentialsAndLateCancelledReplyCannotReplaceANewerLogin(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val operations = dao.operationCount(); val stock = dao.stockMovementSnapshotRows(); val paid = dao.allPaidPrices()
        val health = db.healthDao().history("com.sec.android.app.shealth")
        val store = PrivateKnusprStore.forApp(context); store.clear()
        val server = KnusprAuthorizationServer(URI("https://login.example.test"), URI("https://login.example.test/authorize"),
            URI("https://login.example.test/token"), null, listOf("products:read"), URI("https://mcp.knuspr.de/"))
        val old = KnusprToken("synthetic-expired-access", "synthetic-original-refresh", Instant.now().minusSeconds(1).toEpochMilli(),
            "synthetic-public-client", server)
        store.write(PrivateKnusprStore.State(token = old))
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = OfferHttp { request ->
            check(request.uri == server.token && request.method == "POST")
            val form = String(requireNotNull(request.body)).split('&').associate {
                val field = it.split('=', limit = 2)
                java.net.URLDecoder.decode(field[0], "UTF-8") to java.net.URLDecoder.decode(field[1], "UTF-8")
            }
            check(form["resource"] == server.resource.toString())
            val number = calls.incrementAndGet()
            if (number == 1) withContext(NonCancellable) { entered.complete(Unit); release.await() }
            if (number == 3) OfferHttpReply(400, emptyMap(), """{"error":"invalid_grant"}""")
            else OfferHttpReply(200, emptyMap(), """{"token_type":"Bearer","access_token":"synthetic-access-$number","refresh_token":"synthetic-rotation-$number","expires_in":3600}""")
        }
        var models = ViewModelStore(); lateinit var model: LiveOffersViewModel
        fun mount() = compose.runOnUiThread {
            model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LiveOffersViewModel(context, http = http) as T
            })[LiveOffersViewModel::class.java]
            compose.activity.setContent { DemoTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    LiveOffersScreen(providedModel = model)
                }
            } }
        }
        try {
            mount(); ready(); tap("Angebotsanbieter"); tap("choose_KNUSPR"); ready()
            compose.waitUntil(25_000) { model.state.value.connection?.expired == true }
            compose.onNodeWithTag("knuspr_discover").assertIsNotEnabled()
            compose.runOnUiThread { assertNull(model.renewConnection(false)) }
            assertEquals(0, calls.get())
            lateinit var abandoned: Job
            compose.runOnUiThread { abandoned = requireNotNull(model.renewConnection(true)) }
            withTimeout(25_000) { entered.await() }
            compose.onNodeWithText("Abbrechen").performScrollTo().performClick(); ready()
            tap("knuspr_disconnect"); ready()
            assertNull(store.read().token)
            // A new login can even return the same old credential values. Its
            // independent renewal nonce still protects the newer rotation.
            store.write(PrivateKnusprStore.State(token = old))
            compose.runOnUiThread { model.readConnection() }
            compose.waitUntil(25_000) { model.state.value.connection?.expired == true }
            tap("knuspr_renew"); ready()
            compose.waitUntil(25_000) { model.state.value.connection?.expired == false }
            assertEquals(2, calls.get()); assertEquals("synthetic-access-2", store.read().token?.access)
            release.complete(Unit); withTimeout(25_000) { abandoned.join() }
            assertEquals("synthetic-access-2", store.read().token?.access)
            assertEquals("synthetic-rotation-2", store.read().token?.refresh)
            assertNull(store.read().completing)
            val sealed = File(context.noBackupFilesDir, "knuspr-oauth-v1.bin").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(sealed.contains("synthetic-access-2")); assertFalse(sealed.contains("synthetic-rotation-2"))
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            models = ViewModelStore(); mount(); ready()
            compose.waitUntil(25_000) { model.state.value.connection?.expired == false }
            assertEquals(2, calls.get()); assertNull(model.state.value.discovery)
            assertEquals(server.resource, store.read().token?.server?.resource)
            tap("Angebotsanbieter"); tap("choose_KNUSPR"); ready(); tap("knuspr_renew"); ready()
            compose.waitUntil(25_000) { model.state.value.connection == null }
            assertNull(store.read().token); assertEquals(3, calls.get())
            assertEquals(operations, dao.operationCount()); assertEquals(stock, dao.stockMovementSnapshotRows())
            assertEquals(paid, dao.allPaidPrices()); assertEquals(health, db.healthDao().history("com.sec.android.app.shealth"))
            assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("knuspr-renewal"); throw failure }
        finally {
            release.complete(Unit)
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            store.clear(); PrivateOfferSettings.forApp(context).clearSelection()
        }
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
    @Test fun unconfiguredRetailersStayBlockedAndPublicPennyFailsOfflineWithoutLosingFactsOrReplayingConsent(): Unit = runBlocking {
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
                if (retailer == LiveRetailer.PENNY) {
                    tap("Bestätigte Filiale oder Angebotsregion"); tap("choose_penny-4030085-top-angebote"); ready()
                }
                tap("live_offers_refresh")
                if (retailer == LiveRetailer.PENNY) compose.waitUntil(25_000) {
                    compose.onAllNodesWithText("Netzabruf konnte nicht abgeschlossen werden.").fetchSemanticsNodes().size == 1
                } else compose.waitUntil(25_000) { compose.onAllNodesWithTag("live_offers_blocked").fetchSemanticsNodes().size == 1 }
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
            val earlierRenewal = store.claimRenewal()
            assertTrue(runCatching { store.claimRenewal() }.isFailure)
            store.clear(); assertFalse(store.finishRenewal(earlierRenewal, token))
            store.write(PrivateKnusprStore.State(token = token))
            val currentRenewal = store.claimRenewal()
            val rotated = token.copy(access = "synthetic-rotated-access-$nonce", refresh = "synthetic-rotated-refresh-$nonce")
            assertFalse(store.finishRenewal(earlierRenewal, rotated))
            assertTrue(store.finishRenewal(currentRenewal, rotated))
            assertFalse(store.rejectRenewal(earlierRenewal)); store.abortRenewal(earlierRenewal)
            assertEquals(rotated, store.read().token)
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(rotated.refresh!!))
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
