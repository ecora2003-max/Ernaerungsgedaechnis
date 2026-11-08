package de.ernaehrung.demo

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson
import de.ernaehrung.demo.data.local.FoodDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual default navigation, Android chooser -> separate TEST APK recipient ->
 * actual SAF document -> independent review. A synthetic response is explicitly
 * NOT a real LLM result. No booking, HTTP client, injected activity result or mock.
 */
@RunWith(AndroidJUnit4::class)
class RecipeAiExchangeOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun tap(tag: String) {
        finishDeviceTextEditing(compose)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun openDocument(name: String, device: UiDevice) {
        val packages = setOf("com.google.android.documentsui", "com.android.documentsui")
        compose.waitUntil(25_000) { device.currentPackageName in packages }
        var file = device.wait(Until.findObject(By.text(name)), 5_000L)
        if (file == null) {
            requireNotNull(device.findObject(By.desc("Show roots"))).click()
            val root = if (Build.VERSION.SDK_INT >= 29) "Downloads" else SyntheticRecipeDocuments.TITLE
            requireNotNull(device.wait(Until.findObject(By.text(root)), 10_000L)).click()
            file = device.wait(Until.findObject(By.text(name)), 15_000L)
        }
        assertNotNull("Actual user-selected JSON document must exist", file)
        requireNotNull(file).click()
    }

    @Test fun offlineActualChooserAndSafReplyCannotBookOrRestoreExportConsentAfterRecreation() = runBlocking {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val resolver = target.contentResolver
        val capture = Uri.parse("content://${instrumentation.context.packageName}.recipe-ai-synthetic")
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Actual network absence, not radio settings alone", connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val before = db.foodDao().operationCount()
        val batchesBefore = db.foodDao().planningBatches()
        val consumptionBefore = db.foodDao().activeConsumptionCount()
        resolver.call(capture, "clear", null, null)
        val fileName = "synthetic-recipe-ai-${UUID.randomUUID()}.json"
        var document: Uri? = null
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick()
            tap("workspace_go_KITCHEN")
            compose.onNodeWithText("Rezeptvorschläge und JSON-Import").performScrollTo().performClick()
            compose.onNodeWithText("Rezept mit ChatGPT oder anderer KI entwerfen").performScrollTo().performClick()
            compose.onNodeWithTag("recipe_ai_wish").performScrollTo().performTextReplacement("synthetic-device: ein neues Zitronengericht")
            tap("recipe_ai_prepare")
            compose.onNodeWithTag("recipe_ai_share").assertIsNotEnabled()
            assertNull(resolver.call(capture, "peek", null, null)?.getString("prompt"))
            tap("recipe_ai_export_approval"); tap("recipe_ai_share")
            val device = UiDevice.getInstance(instrumentation)
            val receiver = device.wait(Until.findObject(By.textContains("Synthetische Rezept-KI")), 15_000L)
            assertNotNull("Real Android share chooser must offer the explicit synthetic test recipient", receiver)
            requireNotNull(receiver).click()
            var prompt: String? = null
            compose.waitUntil(25_000) {
                prompt = resolver.call(capture, "peek", null, null)?.getString("prompt")
                prompt != null && device.currentPackageName == target.packageName
            }
            val template = RecipeDraftBoundedJson.decode(requireNotNull(prompt).substringAfter("ANTWORTVORLAGE:\n"))
            val payload = template["payload"] as Map<*, *>
            val raw = JsonOutput.encode(template + mapOf("payload" to payload + mapOf(
                "title" to "Synthetische neue Zitronenpfanne", "ingredients" to listOf(mapOf(
                    "foodTypeId" to "synthetic-novel-food", "label" to "Synthetische Zutat",
                    "quantity" to mapOf("amount" to 160, "unit" to "g", "basis" to "NET", "confirmed" to false),
                    "sourceStatus" to "UNKNOWN")))))
            if (Build.VERSION.SDK_INT >= 29) {
              document = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName); put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }))
            requireNotNull(resolver.openOutputStream(document)).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
            resolver.update(document, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } else {
                // Same real ACTION_OPEN_DOCUMENT contract, on a real local
                // DocumentsProvider. No broad storage rights or injected URI.
                resolver.call(capture, "stage_document", fileName, Bundle().apply { putString("raw", raw) })
            }
            // A real cancellation must release the pending request for a retry,
            // without manufacturing a URI result or losing the prepared request.
            tap("recipe_ai_open_document")
            compose.waitUntil(25_000) { device.currentPackageName in setOf("com.google.android.documentsui", "com.android.documentsui") }
            device.pressBack()
            compose.waitUntil(25_000) { device.currentPackageName == target.packageName }
            compose.onNodeWithTag("recipe_ai_open_document").assertIsEnabled()
            compose.onNodeWithTag("recipe_draft_manual_review").assertDoesNotExist()
            assertEquals(before, db.foodDao().operationCount())
            tap("recipe_ai_open_document")
            openDocument(fileName, device)
            compose.waitUntil(25_000) {
                compose.onAllNodesWithText("Entwurf: Synthetische neue Zitronenpfanne · 2 Soll-Portionen").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("recipe_draft_manual_review").assertIsNotEnabled()
            compose.onNodeWithTag("recipe_draft_manual_review_checkbox").assertIsOff()
            compose.onNodeWithTag("recipe_draft_save_confirmed").assertDoesNotExist()
            assertEquals(before, db.foodDao().operationCount())
            assertEquals(batchesBefore, db.foodDao().planningBatches())
            assertEquals(consumptionBefore, db.foodDao().activeConsumptionCount())
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("workspace_page_KITCHEN").assertExists()
            compose.onNodeWithText("Rezeptvorschläge und JSON-Import").performScrollTo().performClick()
            compose.onNodeWithText("Rezept mit ChatGPT oder anderer KI entwerfen").performScrollTo().performClick()
            compose.onNodeWithTag("recipe_ai_prompt").assertDoesNotExist()
            compose.onNodeWithTag("recipe_ai_export_approval").assertDoesNotExist()
            assertEquals("", compose.onNodeWithTag("recipe_ai_wish").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals("", compose.onNodeWithTag("recipe_draft_json").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals(before, db.foodDao().operationCount())
            assertNull("Network absence must still hold after external Activities", connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("recipe-ai-flow")
            throw failure
        } finally {
            if (Build.VERSION.SDK_INT >= 29) document?.let { resolver.delete(it, null, null) }
            else resolver.call(capture, "clear_document", fileName, null)
            resolver.call(capture, "clear", null, null)
        }
    }
}
