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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.ai.*
import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.DirectRecipeAiViewModel
import de.ernaehrung.demo.ui.screens.RecipeAiExchangePanel
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual standard UI, SDK availability and AndroidKeyStore; fresh disposable
 * emulators without downloaded Nano assets. No fake inference/model PASS. */
@RunWith(AndroidJUnit4::class)
class DirectRecipeAiOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var isolated = false
    @Before fun prepare() {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        isolated = true; prepareUnlockedTestDevice()
        PrivateGeminiSettings.forApp(compose.activity).clear()
    }
    @After fun cleanup() { if (isolated) PrivateGeminiSettings.forApp(compose.activity).clear() }
    private fun tap(tag: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun text(value: String) {
        finishDeviceTextEditing(compose)
        compose.onNodeWithText(value).performScrollTo().performClick()
    }
    private fun ready() = compose.waitUntil(45_000) {
        compose.onAllNodes(hasTestTag("direct_ai_check") and isEnabled()).fetchSemanticsNodes().size == 1
    }
    private fun openPanel() {
        text("Rezeptvorschläge und JSON-Import")
        text("Rezept mit ChatGPT oder anderer KI entwerfen")
    }
    @Test fun actualSdkAvailabilityCannotBecomeInferenceOrCloudFallbackAndModeAloneSurvivesRecreationWithoutApproval(): Unit = runBlocking {
        val context = compose.activity.applicationContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val operations = dao.operationCount(); val movements = dao.stockMovementSnapshotRows()
        val prices = dao.allPaidPrices(); val body = db.bodyDao().versions(); val health = db.healthDao().history("com.sec.android.app.shealth")
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_KITCHEN"); openPanel(); ready()
            for (mode in listOf(DirectRecipeMode.NANO3, DirectRecipeMode.NANO4_FAST)) {
                tap("Direktes Rezeptmodell"); tap("choose_${mode.name}"); ready()
                tap("direct_ai_check"); ready()
                val status = compose.onNodeWithTag("direct_ai_status").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
                assertFalse("Fresh emulator has no downloaded verified Nano model: $status", status.contains("Gewähltes Modell bereit"))
                assertEquals(mode, PrivateGeminiSettings.forApp(context).read().mode)
                assertNull(PrivateGeminiSettings.forApp(context).read().key)
            }
            compose.onNodeWithTag("recipe_ai_wish").performScrollTo().performTextReplacement("synthetic: ein neues Rezept ohne feste Vorlage")
            tap("recipe_ai_prepare"); compose.onNodeWithTag("direct_ai_generate").assertIsNotEnabled()
            tap("Direktes Rezeptmodell"); tap("choose_CLOUD")
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("direct_ai_key").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("direct_ai_check").assertIsNotEnabled()
            tap("recipe_ai_prepare"); compose.onNodeWithTag("direct_ai_generate").assertIsNotEnabled()
            text("Diesen angezeigten Wunsch und ausgewählten Kontext jetzt an Google senden; mögliche API-Kosten akzeptieren")
            compose.onNodeWithTag("direct_ai_generate").assertIsNotEnabled()
            compose.onNodeWithTag("direct_ai_key").performScrollTo().performTextReplacement("synthetic-unsaved-private-key")
            compose.activityRule.scenario.recreate(); openPanel()
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("direct_ai_key").fetchSemanticsNodes().size == 1 }
            assertEquals(DirectRecipeMode.CLOUD, PrivateGeminiSettings.forApp(context).read().mode)
            assertEquals("", compose.onNodeWithTag("direct_ai_key").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            compose.onNodeWithTag("direct_ai_generate").assertDoesNotExist()
            compose.onNodeWithTag("recipe_ai_prompt").assertDoesNotExist()
            compose.onNodeWithTag("recipe_ai_export_approval").assertDoesNotExist()
            assertEquals(operations, dao.operationCount()); assertEquals(movements, dao.stockMovementSnapshotRows())
            assertEquals(prices, dao.allPaidPrices()); assertEquals(body, db.bodyDao().versions())
            assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("direct-recipe-ai-flow"); throw failure }
    }
    @Test fun actualPrivateKeyAndModeAreEncryptedOutsideBackupAndClearingConfigurationPreservesFoodData(): Unit = runBlocking {
        val context = compose.activity.applicationContext; val db = FoodDatabase.localSandbox(context)
        val before = db.foodDao().operationCount(); val store = PrivateGeminiSettings.forApp(context)
        val secret = "synthetic-key-${UUID.randomUUID()}"
        val original = store.saveKey(secret); store.mode(DirectRecipeMode.CLOUD); store.model("models/gemini-synthetic-fixture")
        val read = store.read(); assertEquals(secret, read.key); assertEquals(DirectRecipeMode.CLOUD, read.mode)
        assertNotEquals(original.revision, read.revision); assertFalse(read.toString().contains(secret))
        val file = File(context.noBackupFilesDir, "gemini-config-v1.bin")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        val damaged = file.readBytes(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte(); file.writeBytes(damaged)
        assertTrue(runCatching { store.read() }.isFailure)
        store.clear(); assertFalse(file.exists()); assertNull(store.read().key)
        assertEquals(before, db.foodDao().operationCount())
    }
    @Test fun syntheticCloudTransportThroughActualRecipeUiRejectsLateResponseAndRequiresFreshConsentBeforePinnedImport(): Unit = runBlocking {
        // Real production component, ViewModel, private Keystore and v1 importer.
        // ONLY the external HTTP transport is synthetic; no Google/live proof.
        val context = compose.activity.applicationContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao()
        val operations = dao.operationCount(); val movements = dao.stockMovementSnapshotRows(); val prices = dao.allPaidPrices()
        val config = PrivateGeminiSettings.forApp(context)
        config.saveKey("synthetic-ui-key-${UUID.randomUUID()}"); config.mode(DirectRecipeMode.CLOUD)
        val id = "models/gemini-synthetic-ui"
        val metadata = mapOf("name" to id, "displayName" to "Synthetisches UI-Modell",
            "supportedGenerationMethods" to listOf("generateContent"))
        val posts = AtomicInteger(); val requests = CopyOnWriteArrayList<OfferHttpRequest>()
        val releaseFirst = CompletableDeferred<Unit>()
        val accepted = CopyOnWriteArrayList<RecipeDraftV1Import.Imported>()
        val http = OfferHttp { request ->
            requests += request
            val response = if (request.method == "GET") {
                if (request.uri.path.endsWith("/models")) mapOf("models" to listOf(metadata)) else metadata
            } else {
                val number = posts.incrementAndGet()
                val body = OfferJson.read(requireNotNull(request.body).toString(Charsets.UTF_8))
                val content = OfferJson.objectValue((body["contents"] as List<*>).single())!!
                val prompt = OfferJson.objectValue((content["parts"] as List<*>).single())!!["text"] as String
                val template = RecipeDraftBoundedJson.decode(prompt.substringAfter("ANTWORTVORLAGE:\n"))
                val payload = template["payload"] as Map<*, *>
                val raw = JsonOutput.encode(template + ("payload" to (payload + ("title" to "Synthetischer direkter Entwurf $number"))))
                if (number == 1) releaseFirst.await()
                mapOf("modelVersion" to "gemini-synthetic-ui-001", "candidates" to listOf(mapOf(
                    "finishReason" to "STOP", "content" to mapOf("parts" to listOf(mapOf("text" to raw))))))
            }
            OfferHttpReply(200, mapOf("content-type" to "application/json"), JsonOutput.encode(response))
        }
        val models = ViewModelStore(); lateinit var model: DirectRecipeAiViewModel
        val consent = "Diesen angezeigten Wunsch und ausgewählten Kontext jetzt an Google senden; mögliche API-Kosten akzeptieren"
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        DirectRecipeAiViewModel(context, cloud = GeminiCloudClient(http)) as T
                })[DirectRecipeAiViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        RecipeAiExchangePanel(emptyList(), 2, emptySet(), RecipeRequestPreferences(),
                            RecipeDraftHostGate.Flags(false, false, false), null, true,
                            onResponse = { _, imported -> accepted += imported }, providedDirectModel = model)
                    }
                } }
            }
            text("Rezept mit ChatGPT oder anderer KI entwerfen"); ready()
            tap("direct_ai_check")
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("Offizielles Gemini-Cloud-Modell").fetchSemanticsNodes().size == 1 }
            tap("Offizielles Gemini-Cloud-Modell"); tap("choose_$id"); ready()
            compose.onNodeWithTag("recipe_ai_wish").performScrollTo().performTextReplacement("synthetic: ein frei entworfenes Zitronengericht")
            tap("recipe_ai_prepare"); compose.onNodeWithTag("direct_ai_generate").assertIsNotEnabled()
            assertEquals(0, posts.get()); text(consent); tap("direct_ai_generate")
            compose.waitUntil(25_000) { posts.get() == 1 }
            // Same wish, new nonce: the previously approved in-flight reply may
            // finish, but cannot attach to the newly prepared request.
            tap("recipe_ai_prepare"); releaseFirst.complete(Unit); ready()
            compose.waitUntil(25_000) { model.state.value.completed == null }
            assertEquals(0, accepted.size); compose.onNodeWithTag("direct_ai_generate").assertIsNotEnabled()
            assertEquals(1, posts.get()); text(consent); tap("direct_ai_generate")
            compose.waitUntil(25_000) { accepted.size == 1 }; ready()
            assertEquals("Synthetischer direkter Entwurf 2", accepted.single().draft.title)
            assertEquals(2, accepted.single().draft.portions); assertEquals(2, posts.get())
            assertEquals(listOf("GET", "GET", "POST", "GET", "POST"), requests.map { it.method })
            compose.onNodeWithTag("direct_ai_generate").assertIsNotEnabled()
            assertEquals("", compose.onNodeWithTag("direct_ai_key").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            compose.onNodeWithTag("recipe_ai_share").assertIsNotEnabled()
            assertEquals(operations, dao.operationCount()); assertEquals(movements, dao.stockMovementSnapshotRows())
            assertEquals(prices, dao.allPaidPrices()); assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("direct-recipe-ai-flow"); throw failure }
        finally {
            releaseFirst.complete(Unit)
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
        }
    }
}
