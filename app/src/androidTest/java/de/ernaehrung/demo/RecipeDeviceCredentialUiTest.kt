package de.ernaehrung.demo

import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.AndroidRecipeActionProof
import de.ernaehrung.demo.data.local.RecipeActionNonceGate
import de.ernaehrung.demo.data.local.RecipeActionProof
import de.ernaehrung.demo.data.local.RecipeInput
import de.ernaehrung.demo.data.local.RecipeItemInput
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.screens.RecipeDraftDeviceCredentialReview
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
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
        // Android's system credential Activity, not an app-owned dialog.
        device.executeShellCommand("input text 2468")
        device.pressEnter()
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
        compose.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        compose.onNodeWithTag("recipe_draft_consent_confirm").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        awaitSystemCredentialActivity(device)
        device.pressBack()
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
}
