package de.ernaehrung.demo

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson
import de.ernaehrung.demo.data.local.*
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Default MainActivity throughout: actual Android share -> synthetic TEST APK
 * reply -> manual evidence -> real system PIN/Keystore -> persisted recipe ->
 * explicitly confirmed production -> separately confirmed eating -> reopen.
 * The synthetic reply is NOT a live model or external provider acceptance.
 */
@RunWith(AndroidJUnit4::class)
class RecipeAiLifecycleOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tag(tag: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun tap(text: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun field(text: String, value: String) = compose.onNodeWithText(text).performScrollTo().performTextReplacement(value)
    private fun awaitRows(check: suspend () -> Boolean) = compose.waitUntil(25_000) { runBlocking { check() } }
    private fun choose(selector: String, id: String) {
        tag(selector)
        // This is a non-booking selector, not a save or consent retry.
        waitTag("choose_$id")
        compose.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }
    private fun enterActualSystemPin(device: UiDevice) {
        fun editorVisible(): Boolean = device.currentPackageName in setOf("com.android.settings", "com.android.systemui") &&
            (device.hasObject(By.res("com.android.systemui", "lockPassword")) ||
                device.hasObject(By.res("com.android.settings", "password_entry")) || device.hasObject(By.clazz("android.widget.EditText")))
        compose.waitUntil(15_000) { editorVisible() }
        val editor = device.wait(Until.findObject(By.res("com.android.systemui", "lockPassword")), 15_000L)
            ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000L)
            ?: device.wait(Until.findObject(By.res("com.android.settings", "password_entry")), 5_000L)
        requireNotNull(editor) { "Actual OS-owned credential editor unavailable" }.click()
        device.waitForIdle()
        // CI emulator PIN only. No injected Activity result, attestation or signer.
        device.executeShellCommand("input text 2468")
        device.pressEnter()
    }

    @Test fun offlineSharedAiReplyThroughRealDeviceApprovalProductionAndSeparateEatingPersistsWithoutDuplicateBookings() = runBlocking {
        assertTrue("Disposable emulator only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue((target.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure)
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Actual absence of a default network", connectivity.activeNetwork)
        val resolver = target.contentResolver
        val capture = Uri.parse("content://${instrumentation.context.packageName}.recipe-ai-synthetic")
        val device = UiDevice.getInstance(instrumentation)
        val db = FoodDatabase.localSandbox(target)
        val dao = db.foodDao()
        val store = FoodStore(db)
        val id = UUID.randomUUID().toString()
        val profileProposal = requireNotNull(store.proposePersonalRestrictions("$id-profile-proposal",PersonalRestrictionInput(
            store.readPersonalRestrictions().latest?.id,"","synthetic-device: explicit empty profile for device-scope proof"),true).versionId)
        val profileVersion = requireNotNull(store.confirmPersonalRestrictions("$id-profile-confirm",profileProposal,
            store.readPersonalRestrictions().versionBinding(profileProposal),true).versionId)
        assertNotNull("Real device approval must bind a confirmed profile identity",store.readPersonalRestrictions().binding)
        val day = AppCalendar.today().toString()
        val food = "ai-device-ingredient"
        val title = "Synthetisches KI-Gericht $id"
        val version = store.rememberProduct("$id-product", ProductInput("Synthetische KI-Basis $id",
            source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0, proteinGrams = 10.0))
        val location = store.addLocation("$id-location", "Synthetischer KI-Kühlschrank $id", true)
        val batch = store.receive("$id-stock", BatchInput(version, location, 500_000, day, release = ReleaseStatus.RELEASED), true)
        val before = dao.operationCount()
        val mealsBefore = dao.activeConsumptionCount()
        val batchesBefore = dao.planningBatches().map { it.batch.id }.toSet()
        resolver.call(capture, "clear", null, null)
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_KITCHEN")
            tap("Rezeptvorschläge und JSON-Import"); tap("Rezept mit ChatGPT oder anderer KI entwerfen")
            compose.onNodeWithTag("recipe_ai_wish").performScrollTo().performTextReplacement("synthetic-device: lifecycle $id")
            tag("recipe_ai_prepare")
            compose.onNodeWithTag("recipe_ai_share").assertIsNotEnabled()
            tag("recipe_ai_export_approval"); tag("recipe_ai_share")
            val receiver = device.wait(Until.findObject(By.textContains("Synthetische Rezept-KI")), 15_000L)
            requireNotNull(receiver) { "Actual Android chooser must offer the synthetic TEST APK receiver" }.click()
            var prompt: String? = null
            compose.waitUntil(25_000) {
                prompt = resolver.call(capture, "peek", null, null)?.getString("prompt")
                prompt != null && device.currentPackageName == target.packageName
            }
            val template = RecipeDraftBoundedJson.decode(requireNotNull(prompt).substringAfter("ANTWORTVORLAGE:\n"))
            val payload = template["payload"] as Map<*, *>
            val reply = JsonOutput.encode(template + mapOf("payload" to payload + mapOf("title" to title,
                "ingredients" to listOf(mapOf("foodTypeId" to food, "label" to "Synthetische KI-Zutat",
                    "quantity" to mapOf("amount" to 160, "unit" to "g", "basis" to "NET", "confirmed" to false),
                    "sourceStatus" to "UNKNOWN")))))
            compose.onNodeWithTag("recipe_ai_reply").performScrollTo().performTextReplacement(reply)
            tag("recipe_ai_inspect"); waitTag("recipe_draft_line_$food")
            compose.onNodeWithTag("recipe_draft_manual_review").assertIsNotEnabled()
            compose.onNodeWithTag("recipe_draft_save_confirmed").assertDoesNotExist()
            assertEquals(before, dao.operationCount())
            val label = requireNotNull(dao.version(version)).let { "${it.name} · v${it.revision} · ${it.id}" }
            fun line(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("recipe_draft_line_$food")))
            line("Unabhängig geprüfte Katalogversion").performScrollTo().performClick()
            compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
            finishDeviceTextEditing(compose)
            line("NET").performScrollTo().performClick().assertIsSelected()
            line("RAW").performScrollTo().performClick().assertIsSelected()
            tag("recipe_draft_manual_review_checkbox"); tag("recipe_draft_manual_review")
            waitTag("recipe_draft_consent_checkbox")
            assertEquals(before, dao.operationCount())
            compose.onNodeWithTag("recipe_draft_consent_checkbox").assertIsOff()
            tag("recipe_draft_consent_checkbox"); tag("recipe_draft_consent_confirm")
            enterActualSystemPin(device)
            waitTag("recipe_draft_save_confirmed")
            assertEquals(before + 1, dao.operationCount()) // Attested consent only, not a recipe or meal.
            assertFalse(dao.observeRecipes().first().any { it.name == title })
            tag("recipe_draft_save_confirmed")
            awaitRows { dao.observeRecipes().first().any { it.name == title } }
            val recipe = dao.observeRecipes().first().single { it.name == title }
            assertEquals(before + 2, dao.operationCount())
            assertEquals(mealsBefore, dao.activeConsumptionCount())
            assertEquals(500_000L, dao.balance(batch))
            tag("recipe_production_prepare")
            choose("Zutatencharge 1", batch); field("Tatsächliche Zutatmenge 1 in g", "160")
            field("Tatsächlich hergestellte Portionen", "2"); choose("Fertigportionen lagern in", location)
            compose.onNodeWithText("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft").assertIsOff()
            tap("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft")
            field("Geprüfte Lagerbedingungen und Platz beschreiben", "Synthetische saubere Behälter und Kühlplatz geprüft")
            tap("Fertigportionen für diesen Test ausdrücklich freigeben")
            compose.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertIsOff()
            tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen")
            tap("Herstellung buchen")
            awaitRows { dao.planningBatches().any { it.batch.id !in batchesBefore } }
            val output = dao.planningBatches().single { it.batch.id !in batchesBefore }.batch
            assertEquals(before + 3, dao.operationCount())
            assertEquals(340_000L, dao.balance(batch)); assertEquals(2000L, dao.balance(output.id))
            assertEquals(mealsBefore, dao.activeConsumptionCount())
            tap("Neuen Vorgang beginnen"); tap("Fertigportionen separat verzehren"); tap("Charge")
            val outputName = requireNotNull(dao.version(output.versionId)).name
            compose.onNodeWithText("$outputName · ${output.id}").performClick()
            field("Verzehrmenge in Portion", "1")
            compose.onNodeWithText("Fertigportionen tatsächlich gegessen · ausdrücklich bestätigen").assertIsOff()
            tap("Fertigportionen tatsächlich gegessen · ausdrücklich bestätigen")
            tap("Fertigportionen-Verzehr buchen")
            awaitRows { dao.activeConsumptionCount() == mealsBefore + 1 }
            assertEquals(before + 4, dao.operationCount()); assertEquals(1000L, dao.balance(output.id))
            val meal = store.dayNutrition(day).meals.single { it.event.productVersionId == output.versionId }
            assertEquals(0, BigDecimal("80").compareTo(requireNotNull(meal.nutrition[Nutrient.ENERGY].knownAmount)))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("workspace_page_KITCHEN").assertExists()
            assertEquals(before + 4, dao.operationCount())
            // Independent SQLite connection, preserving the shared synthetic fixture and its other users.
            val reopened = Room.databaseBuilder(target, FoodDatabase::class.java, requireNotNull(db.openHelper.databaseName)).build()
            try {
                assertNotNull(reopened.foodDao().recipeVersion(recipe.id))
                assertEquals(before + 4, reopened.foodDao().operationCount())
                assertEquals(1000L, reopened.foodDao().balance(output.id))
                assertEquals(mealsBefore + 1, reopened.foodDao().activeConsumptionCount())
            } finally { reopened.close() }
            assertNull("Network absence persists through chooser, credentials and restart", connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("recipe-ai-lifecycle-flow"); throw failure
        } finally {
            try {
                val profile = store.readPersonalRestrictions()
                if (profile.active?.id == profileVersion) store.erasePersonalRestrictions("$id-profile-cleanup",profile.token,true)
            } finally { resolver.call(capture, "clear", null, null) }
        }
    }
}
