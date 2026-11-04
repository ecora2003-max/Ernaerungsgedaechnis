package de.ernaehrung.demo

import android.app.KeyguardManager
import androidx.activity.compose.setContent
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
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

    private fun awaitSystemCredentialActivity(device: UiDevice) {
        val appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        // The external credential Activity starts asynchronously. Pressing Back before
        // it is foregrounded can close our own Activity and leave no Compose hierarchy.
        compose.waitUntil(15_000) {
            val foreground = device.currentPackageName
            foreground != null && foreground != appPackage
        }
        device.waitForIdle()
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
                FoodMigrations.MIGRATION_7_8).build()
        var database = open()
        var models = ViewModelStore()
        fun tap(label: String) {
            compose.waitUntil(25_000) {
                compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        fun tag(label: String) = compose.onNodeWithTag(label).performScrollTo().performClick()
        fun field(label: String, value: String) = compose.onNodeWithText(label)
            .performScrollTo().performTextReplacement(value)
        fun choose(label: String, id: String) {
            tag(label)
            tag("choose_$id")
        }
        fun mount(store: FoodStore): LocalDataViewModel {
            val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(store) as T
            })[LocalDataViewModel::class.java]
            compose.runOnUiThread {
                compose.activity.setContent {
                    DemoTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
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
            compose.onNodeWithTag("recipe_offline_restrictions").performScrollTo()
                .performTextReplacement("ALLERGEN:SOY")
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
            var netIndex = 0
            ingredients.forEachIndexed { index, ingredient ->
                compose.onAllNodesWithText("Unabhängig geprüfte Katalogversion")[index]
                    .performScrollTo().performClick()
                compose.onNode(hasText(labels[index]) and hasAnyAncestor(isPopup())).performClick()
                val position = if (ingredient.basis == "NET") netIndex++ else 0
                compose.onAllNodesWithText(ingredient.basis)[position]
                    .performScrollTo().performClick()
                val state = if (ingredient.foodTypeId.startsWith("cooked-")) "COOKED" else "RAW"
                compose.onAllNodesWithText(state)[index].performScrollTo().performClick()
                compose.onAllNodesWithText("Nachweislich nicht enthalten")[index]
                    .performScrollTo().performClick()
            }
            tag("recipe_draft_manual_review_checkbox")
            tag("recipe_draft_manual_review")
            compose.waitUntil(25_000) {
                !model.recipeDraftConsentState.value.busy && model.recipeDraftConsentState.value.review != null
            }
            assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,
                model.recipeDraftConsentState.value.review?.status)
            assertEquals(baseline, database.foodDao().operationCount())
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
                field("Tatsächliche Zutatmenge ${index + 1} in g", (ingredient.gramsPerPortion * 2).toString())
            }
            field("Tatsächlich hergestellte Portionen", "2")
            choose("Fertigportionen lagern in", location)
            tap("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft")
            val storage = "Saubere Behälter und Kühlplatz tatsächlich geprüft"
            field("Geprüfte Lagerbedingungen und Platz beschreiben", storage)
            tap("Fertigportionen für diesen Test ausdrücklich freigeben")
            tap("Herstellung buchen")
            saved(model, false)
            assertEquals(3, database.foodDao().planningBatches().size)
            tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen")
            tap("Herstellung buchen")
            saved(model)
            assertEquals(0, database.foodDao().consumptionCount())
            val output = database.foodDao().planningBatches().single { it.batch.id !in batches }.batch
            assertEquals(2_000L, database.foodDao().balance(output.id))
            val production = ProductionInput(recipe, 2_000L,
                ingredients.mapIndexed { index, ingredient -> Allocation(batches[index], ingredient.gramsPerPortion * 2_000L) },
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
        } finally {
            compose.runOnUiThread { compose.activity.setContent {}; models.clear() }
            database.close()
            target.deleteDatabase(databaseName)
        }
    }
}
