package de.ernaehrung.demo

import android.app.KeyguardManager
import android.os.Build
import androidx.activity.compose.setContent
import android.content.Context
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.AndroidRecipeActionProof
import de.ernaehrung.demo.data.local.RecipeActionNonceGate
import de.ernaehrung.demo.data.local.RecipeActionProof
import de.ernaehrung.demo.data.local.RecipeInput
import de.ernaehrung.demo.data.local.RecipeItemInput
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.integration.RecipeOfflineDraftGenerator
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LocalDataViewModel
import de.ernaehrung.demo.ui.screens.LocalDataContent
import de.ernaehrung.demo.ui.screens.RecipeDraftDeviceCredentialReview
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the actual Android credential Activity and auth-bound Keystore key.
 * The ephemeral CI emulator must be provisioned with PIN 2468 before this suite.
 * No synthetic Activity.RESULT_OK and no test-only proof verifier are used.
 */
@RunWith(AndroidJUnit4::class)
class RecipeDeviceCredentialUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun unlockFixtureDevice() = prepareUnlockedTestDevice()

    private val digest = "a".repeat(64)
    private val ready = Gate.Review(
        Gate.Status.READY_FOR_EXPLICIT_SAVE, emptySet(), emptySet(),
        RecipeInput("Gerätegeprüftes Rezept", 2_000L,
            listOf(RecipeItemInput("device-product-version", 200_000L)), "Kochen"),
        approvalBasisDigest = digest
    )

    private fun assertSecureEmulator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertTrue("Device credential UI test requires a PIN-provisioned emulator",
            keyguard.isDeviceSecure)
    }

    private fun credentialEditorVisible(device: UiDevice): Boolean {
        val foreground = device.currentPackageName
        if (foreground != "com.android.settings" && foreground != "com.android.systemui") return false
        return device.hasObject(By.res("com.android.systemui", "lockPassword")) ||
            device.hasObject(By.res("com.android.settings", "password_entry")) ||
            device.hasObject(By.clazz("android.widget.EditText"))
    }

    private fun awaitSystemCredentialActivity(device: UiDevice) {
        // Require the actual OS credential editor, not just a translucent
        // Settings Activity or a different foreground package during launch.
        try {
            compose.waitUntil(15_000) { credentialEditorVisible(device) }
            device.waitForIdle()
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("credential-flow")
            throw failure
        }
    }

    private fun enterRealSystemPin() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        awaitSystemCredentialActivity(device)
        // Foreground package alone does not mean the SystemUI/Settings PIN editor
        // has been inflated and focused. Never send blind keystrokes to the app.
        // API 35 exposes a SystemUI ImeAwareEditText at lockPassword.
        // The editor is not a plain android.widget.EditText class.
        val pinEditor = device.wait(
            Until.findObject(By.res("com.android.systemui", "lockPassword")), 15_000L
        ) ?: device.wait(
            Until.findObject(By.clazz("android.widget.EditText")), 5_000L
        ) ?: device.wait(
            Until.findObject(By.res("com.android.settings", "password_entry")), 5_000L
        )
        assertNotNull("Real Android credential editor did not become accessible", pinEditor)
        requireNotNull(pinEditor).click()
        device.waitForIdle()
        // ADB inputs actual digits into the focused OS-owned credential editor;
        // it does not forge an Activity result or bypass Android Keystore.
        device.executeShellCommand("input text 2468")
        device.pressEnter()
    }

    private fun cancelRealSystemCredential(device: UiDevice) {
        val appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        awaitSystemCredentialActivity(device)
        // Prefer the OS cancel control. If absent, the first Back can dismiss
        // only the IME, so send a second Back only while the OS remains foreground.
        val cancel = device.findObject(By.text("Cancel"))
            ?: device.findObject(By.text("CANCEL"))
            ?: device.findObject(By.text("Abbrechen"))
        if (cancel != null) {
            cancel.click()
        } else {
            device.pressBack()
            device.waitForIdle(1_500L)
            if (device.currentPackageName != appPackage) device.pressBack()
        }
        compose.waitUntil(15_000) { device.currentPackageName == appPackage }
    }

    private fun awaitComposeReview() {
        // The API 35 software emulator can delay its first Compose surface
        // even after MainActivity reports that it has been displayed.
        compose.waitUntil(25_000) {
            try {
                compose.onNodeWithTag("recipe_draft_consent_checkbox").assertExists()
                true
            } catch (_: AssertionError) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }
    }

    @Test fun systemCredentialSuccessSignsOnlyTheLiveOperationAndNonceIsOneShot() {
        assertSecureEmulator()
        val operationKey = "device-auth-" + UUID.randomUUID()
        val nonceGate = RecipeActionNonceGate()
        val callbacks = AtomicInteger(0)
        val received = AtomicReference<RecipeActionProof?>(null)
        compose.runOnUiThread {
            compose.activity.setContent {
                DemoTheme {
                    RecipeDraftDeviceCredentialReview(
                        review = ready,
                        sessionKey = "device-session-1",
                        operationKey = operationKey,
                        requestNonce = { approved ->
                            if (approved == digest) nonceGate.issue(
                                operationKey, digest, System.currentTimeMillis()
                            ) else null
                        },
                        resetVersion = 0,
                        actionEnabled = true
                    ) { approved, session, proof ->
                        if (approved == digest && session == "device-session-1") {
                            callbacks.incrementAndGet()
                            received.set(proof)
                        }
                    }
                }
            }
        }
        awaitComposeReview()
        compose.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        compose.onNodeWithTag("recipe_draft_consent_confirm").performClick()
        enterRealSystemPin()
        compose.waitUntil(20_000) { received.get() != null }
        val proof = requireNotNull(received.get())
        assertEquals(1, callbacks.get())
        assertEquals(operationKey, proof.operationKey)
        assertEquals(digest, proof.approvalDigest)
        assertTrue(AndroidRecipeActionProof.verify(
            proof, operationKey, digest, System.currentTimeMillis()))
        assertFalse(AndroidRecipeActionProof.verify(
            proof, operationKey + "-other", digest, System.currentTimeMillis()))
        assertFalse(AndroidRecipeActionProof.verify(
            proof, operationKey, "b".repeat(64), System.currentTimeMillis()))
        assertTrue(nonceGate.consume(
            proof, operationKey, digest, System.currentTimeMillis()))
        assertFalse("A second save attempt cannot replay the same action nonce",
            nonceGate.consume(proof, operationKey, digest, System.currentTimeMillis()))
        compose.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, callbacks.get()) }
    }

    @Test fun cancelledRealSystemCredentialNeverProducesSignedApproval() {
        assertSecureEmulator()
        val callbacks = AtomicInteger(0)
        val operationKey = "device-cancel-" + UUID.randomUUID()
        compose.runOnUiThread {
            compose.activity.setContent {
                DemoTheme {
                    RecipeDraftDeviceCredentialReview(
                        review = ready,
                        sessionKey = "device-session-cancel",
                        operationKey = operationKey,
                        requestNonce = { UUID.randomUUID().toString() },
                        resetVersion = 0,
                        actionEnabled = true
                    ) { _, _, _ -> callbacks.incrementAndGet() }
                }
            }
        }
        awaitComposeReview()
        compose.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        compose.onNodeWithTag("recipe_draft_consent_confirm").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // AOSP's credential Activity can cancel during a previous prompt's
        // teardown. Exercise the real app's fresh-gesture retry once, rather
        // than treating that early cancellation as the user-cancel proof.
        compose.waitUntil(15_000) {
            credentialEditorVisible(device) || runCatching {
                compose.onAllNodesWithText("Gerätebestätigung abgebrochen", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        if (!credentialEditorVisible(device)) {
            assertEquals("An OS cancellation must never sign an approval", 0, callbacks.get())
            compose.onNodeWithTag("recipe_draft_consent_checkbox").assertIsOff()
            compose.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
            device.waitForIdle()
            compose.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
            compose.onNodeWithTag("recipe_draft_consent_confirm").performClick()
        }
        cancelRealSystemCredential(device)
        // Wait for the external Activity to finish and Compose to resume. A direct
        // assertion races the ActivityResult callback and may see no app hierarchy.
        compose.waitUntil(20_000) {
            try {
                compose.onNodeWithText("Gerätebestätigung abgebrochen", substring = true)
                    .assertExists()
                true
            } catch (_: AssertionError) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }
        compose.onNodeWithText("Gerätebestätigung abgebrochen", substring = true)
            .assertExists()
        compose.runOnIdle { assertEquals(0, callbacks.get()) }
    }

    @Test fun offlineAllergenProposalThroughRealCredentialFoodStoreCookingAndSeparateEatingUi() = runBlocking {
        assertSecureEmulator()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "recipe-full-ui-" + UUID.randomUUID() + ".db"
        fun open() = Room.databaseBuilder(target, FoodDatabase::class.java, databaseName)
            .addMigrations(FoodMigrations.MIGRATION_1_2, FoodMigrations.MIGRATION_2_3,
                FoodMigrations.MIGRATION_3_4, FoodMigrations.MIGRATION_4_5,
                FoodMigrations.MIGRATION_5_6, FoodMigrations.MIGRATION_6_7,
                FoodMigrations.MIGRATION_7_8, FoodMigrations.MIGRATION_8_9, FoodMigrations.MIGRATION_9_10).build()
        var database = open()
        var models = ViewModelStore()
        var appFocus: FocusManager? = null
        fun finishEditing() {
            // The IME is an external window: Compose idleness alone does not
            // mean that its resize animation has finished. End the text edit
            // before scrolling and tapping a review choice on the real screen.
            compose.runOnUiThread {
                appFocus?.clearFocus(force = true)
                val window = compose.activity.window
                (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(window.decorView.windowToken, 0)
            }
            compose.waitUntil(5_000) {
                Build.VERSION.SDK_INT < 30 || compose.activity.window.decorView.rootWindowInsets
                    ?.isVisible(WindowInsets.Type.ime()) != true
            }
            compose.waitForIdle()
            // Native IME/window work can outlive Compose idleness and dismiss a
            // popup opened during the old editor's hide animation.
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.waitForIdle(1_500L)
        }
        fun tap(label: String) {
            finishEditing()
            compose.waitUntil(25_000) {
                compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        fun tag(label: String, endEditing: Boolean = true) {
            if (endEditing) finishEditing()
            compose.waitUntil(25_000) {
                compose.onAllNodesWithTag(label).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag(label).performScrollTo().performClick()
        }
        fun field(label: String, value: String) = compose.onNodeWithText(label)
            .performScrollTo().performTextReplacement(value)
        fun choose(label: String, id: String) {
            tag(label)
            // Ending main-window focus after opening a popup can dismiss it.
            // Keep that live popup focused until its actual option is selected.
            // Select only an actually visible popup option. If the native IME
            // transition dismissed the menu, reopen this non-booking selector
            // once after native window idle; never retry a save/consent action.
            val visible = try {
                compose.waitUntil(3_000) { compose.onAllNodesWithTag("choose_$id").fetchSemanticsNodes().size == 1 }
                true
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) { false }
            if (!visible) {
                compose.onNodeWithTag(label).assertExists()
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1_500L)
                tag(label)
            }
            tag("choose_$id", endEditing = false)
        }
        fun mount(store: FoodStore): LocalDataViewModel {
            val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(store) as T
            })[LocalDataViewModel::class.java]
            compose.runOnUiThread {
                compose.activity.setContent {
                    appFocus = LocalFocusManager.current
                    DemoTheme {
                        Column(Modifier.systemBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
                            LocalDataContent(model, "Küche")
                        }
                    }
                }
            }
            compose.waitUntil(25_000) { !model.state.value.loading }
            return model
        }
        fun saved(model: LocalDataViewModel, expected: Boolean = true) {
            compose.waitUntil(25_000) { !model.saveState.value.busy && model.saveState.value.message != null }
            assertEquals(model.saveState.value.message, expected, model.saveState.value.succeeded)
        }
        try {
            var store = FoodStore(database) // Real Android Keystore verifier; no injected test signer.
            val day = LocalDate.now(ZoneId.of("Europe/Berlin")).toString()
            val ingredients = RecipeOfflineDraftGenerator.ingredientsFor(0)
            val versions = ingredients.mapIndexed { index, ingredient ->
                store.rememberProduct("full-product-${ingredient.foodTypeId}", ProductInput(
                    ingredient.label, source = Source.USER, quality = Quality.CONFIRMED,
                    energyKcal = listOf(350.0, 100.0, 20.0)[index]))
            }
            val labels = versions.map { id -> requireNotNull(database.foodDao().version(id)).let {
                "${it.name} · v${it.revision} · ${it.id}"
            } }
            val location = store.addLocation("full-location", "Geprüfte Küche", true)
            val batches = versions.mapIndexed { index, id ->
                store.receive("full-stock-$index", BatchInput(id, location, 500_000L,
                    day, release = ReleaseStatus.RELEASED), true)
            }
            var model = mount(store)
            val baseline = database.foodDao().operationCount()
            tap("JSON-Rezeptentwurf importieren und manuell prüfen")
            tap("Vorgaben für den Rezeptvorschlag")
            tag("recipe_pref_meal_Hauptmahlzeit")
            compose.onNodeWithTag("recipe_pref_minutes").performScrollTo().performTextReplacement("35")
            tap("Verfügbare Küchengeräte ausdrücklich angeben")
            tap("Kochstelle verfügbar"); tap("Kochtopf verfügbar")
            compose.onNodeWithTag("recipe_pref_budget").performScrollTo().performTextReplacement("0")
            compose.onNodeWithTag("recipe_pref_freeze").performScrollTo().performTextReplacement("1")
            compose.onNodeWithTag("recipe_pref_capacity").performScrollTo().performTextReplacement("2")
            tap("Eigene Nährwertbereiche je Portion")
            compose.onNodeWithTag("recipe_pref_min_energyKcal").performScrollTo().performTextReplacement("400")
            compose.onNodeWithTag("recipe_pref_max_energyKcal").performScrollTo().performTextReplacement("450")
            compose.onNodeWithTag("recipe_pref_min_proteinG").performScrollTo().performTextReplacement("0")
            tap("Eigene Nährwertbereiche je Portion")
            tap("Vorgaben für den Rezeptvorschlag")
            compose.onNodeWithTag("recipe_offline_restrictions").performScrollTo()
                .performTextReplacement("ALLERGEN:SOY, DIET:VEGAN")
            tag("recipe_offline_generate")
            compose.onNodeWithTag("recipe_draft_import_error").assertExists()
            assertEquals(baseline, database.foodDao().operationCount())
            tap("Allergennachweise für Offline-Vorschläge")
            ingredients.forEachIndexed { index, ingredient ->
                val selector = "Produktversion für Allergen-Vorprüfung: ${ingredient.label} (${ingredient.foodTypeId})"
                tap(selector)
                compose.onNode(hasText(labels[index]) and hasAnyAncestor(isPopup())).performClick()
                tag("recipe_offline_allergen_absent_${ingredient.foodTypeId}_SOY")
            }
            tag("recipe_offline_generate")
            compose.onNodeWithTag("recipe_draft_manual_review").assertIsNotEnabled()
            assertEquals(baseline, database.foodDao().operationCount())
            assertTrue(database.foodDao().observeRecipes().first().isEmpty())
            ingredients.forEachIndexed { index, ingredient ->
                fun lineField(text: String) = compose.onNode(hasText(text) and
                    hasAnyAncestor(hasTestTag("recipe_draft_line_${ingredient.foodTypeId}")))
                lineField("Unabhängig geprüfte Katalogversion")
                    .performScrollTo().performClick()
                compose.onNode(hasText(labels[index]) and hasAnyAncestor(isPopup())).performClick()
                finishEditing()
                lineField(ingredient.basis).performScrollTo().performClick().assertIsSelected()
                val state = if (ingredient.foodTypeId.startsWith("cooked-")) "COOKED" else "RAW"
                lineField(state).performScrollTo().performClick().assertIsSelected()
                lineField("Nachweislich nicht enthalten")
                    .performScrollTo().performClick()
                lineField("Nachweislich geeignet").performScrollTo().performClick().assertIsSelected()
            }
            tag("recipe_draft_manual_review_checkbox")
            tag("recipe_draft_manual_review")
            compose.onNodeWithTag("recipe_draft_import_error").assertDoesNotExist()
            compose.waitUntil(25_000) {
                !model.recipeDraftConsentState.value.busy &&
                    (model.recipeDraftConsentState.value.review != null || model.recipeDraftConsentState.value.error != null)
            }
            assertNull(model.recipeDraftConsentState.value.error)
            assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,
                model.recipeDraftConsentState.value.review?.status)
            assertEquals(baseline, database.foodDao().operationCount())
            compose.onNodeWithTag("recipe_draft_preview_ENERGY").performScrollTo()
                .assertTextContains("416,5", substring = true)
            compose.onNodeWithTag("recipe_draft_preview_PROTEIN").performScrollTo()
                .assertTextContains("Unbekannt", substring = true)
            tag("recipe_draft_consent_checkbox")
            tag("recipe_draft_consent_confirm")
            cancelRealSystemCredential(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
            awaitComposeReview()
            assertNull(model.recipeDraftConsentState.value.recordedDigest)
            assertEquals(baseline, database.foodDao().operationCount())
            tag("recipe_draft_consent_checkbox")
            tag("recipe_draft_consent_confirm")
            enterRealSystemPin()
            compose.waitUntil(25_000) {
                model.recipeDraftConsentState.value.recordedDigest != null
            }
            val reference = requireNotNull(model.recipeDraftConsentState.value.review?.referenceReport)
            assertFalse("DIET_UNKNOWN" in reference.findings)
            assertEquals(35, reference.preferences.maxMinutes)
            assertEquals(0L, reference.preferences.budgetCents)
            assertEquals(setOf("HOB", "POT"), reference.preferences.equipment)
            assertEquals("IN_RANGE", reference.targets.single { it["nutrient"] == "energyKcal" }["status"])
            assertEquals("UNKNOWN", reference.targets.single { it["nutrient"] == "proteinG" }["status"])
            val digest = requireNotNull(model.recipeDraftConsentState.value.recordedDigest)
            val consentKey = requireNotNull(store.recordedRecipeDraftV1ConsentKey(digest))
            assertEquals("RECIPE_DRAFT_ATTESTED_CONSENT_V1",
                database.foodDao().operation("RECIPE_DRAFT_CONSENT_V1:$consentKey")?.kind)
            assertTrue(database.foodDao().observeRecipes().first().isEmpty())
            tag("recipe_draft_save_confirmed")
            compose.waitUntil(25_000) { model.recipeDraftConsentState.value.savedRecipeVersionId != null }
            val recipe = requireNotNull(model.recipeDraftConsentState.value.savedRecipeVersionId)
            assertEquals(0, database.foodDao().consumptionCount())
            assertEquals(3, database.foodDao().planningBatches().size)
            tag("recipe_production_prepare")
            ingredients.forEachIndexed { index, ingredient ->
                choose("Zutatencharge ${index + 1}", batches[index])
                field("Tatsächliche Zutatmenge ${index + 1} in g", (ingredient.amountPerPortion * 2).toString())
            }
            field("Tatsächlich hergestellte Portionen", "2")
            choose("Fertigportionen lagern in", location)
            tap("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft")
            compose.onNodeWithText("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft").assertIsOn()
            val storage = "Saubere Behälter und Kühlplatz tatsächlich geprüft"
            field("Geprüfte Lagerbedingungen und Platz beschreiben", storage)
            tap("Fertigportionen für diesen Test ausdrücklich freigeben")
            compose.onNodeWithText("Fertigportionen für diesen Test ausdrücklich freigeben").assertIsOn()
            tap("Herstellung buchen")
            saved(model, false)
            assertEquals(3, database.foodDao().planningBatches().size)
            tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen")
            compose.onNodeWithText("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen").assertIsOn()
            tap("Herstellung buchen")
            saved(model)
            assertEquals(0, database.foodDao().consumptionCount())
            val output = database.foodDao().planningBatches().single { it.batch.id !in batches }.batch
            assertEquals(2_000L, database.foodDao().balance(output.id))
            val production = ProductionInput(recipe, 2_000L,
                ingredients.mapIndexed { index, ingredient -> Allocation(batches[index], ingredient.amountPerPortion * 2_000L) },
                location, day, true, storage, release = ReleaseStatus.RELEASED)
            tap("Neuen Vorgang beginnen")
            tap("Fertigportionen separat verzehren")
            tap("Charge")
            val outputName = model.state.value.batches.single { it.batch.id == output.id }.productName
            compose.onNodeWithText("$outputName · ${output.id}").performClick()
            field("Verzehrmenge in Portion", "1")
            tap("Fertigportionen-Verzehr buchen")
            saved(model, false)
            assertEquals(0, database.foodDao().consumptionCount())
            tap("Fertigportionen tatsächlich gegessen · ausdrücklich bestätigen")
            tap("Fertigportionen-Verzehr buchen")
            saved(model)
            compose.waitUntil(25_000) { model.state.value.operations.any { it.operation.kind == "CONSUMPTION" } }
            val eatKey = model.state.value.operations.single { it.operation.kind == "CONSUMPTION" }.operation.id
            assertEquals(1, database.foodDao().activeConsumptionCount())
            assertEquals(1_000L, database.foodDao().balance(output.id))
            val nutrition = store.dayNutrition(day).totals
            assertEquals(416.5, requireNotNull(nutrition[Nutrient.ENERGY].knownAmount).toDouble(), 0.0001)
            assertTrue(nutrition[Nutrient.ENERGY].complete)
            assertNull(nutrition[Nutrient.PROTEIN].knownAmount) // Unknown remains unknown.
            assertTrue(database.foodDao().purchases().isEmpty())
            assertTrue(database.foodDao().allPaidPrices().isEmpty())
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            database.close()
            database = open()
            store = FoodStore(database)
            models = ViewModelStore()
            model = mount(store)
            compose.waitUntil(25_000) { model.state.value.recipes.any { it.id == recipe } }
            assertNull(model.recipeDraftConsentState.value.review)
            assertEquals(output.id, store.produce(output.originOperationId, production, true))
            store.consume(eatKey, ConsumptionInput(output.versionId, day, "Fertigportionen",
                1_000L, listOf(Allocation(output.id, 1_000L))), true)
            assertEquals(1, database.foodDao().observeRecipes().first().size)
            assertEquals(1, database.foodDao().activeConsumptionCount())
            assertEquals(1_000L, database.foodDao().balance(output.id))
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("recipe-flow")
            throw failure
        } finally {
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            database.close()
            target.deleteDatabase(databaseName)
        }
    }
}
