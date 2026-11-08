package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ernaehrung.demo.data.ai.*
import de.ernaehrung.demo.data.local.*
import java.io.File
import java.util.UUID
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
}
