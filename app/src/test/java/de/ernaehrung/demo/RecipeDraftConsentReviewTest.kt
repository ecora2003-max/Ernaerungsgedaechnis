package de.ernaehrung.demo

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.RecipeInput
import de.ernaehrung.demo.data.local.RecipeItemInput
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.screens.RecipeDraftConsentReview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeDraftConsentReviewTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun review(digest: String = "a".repeat(64)) = Gate.Review(
        status = Gate.Status.READY_FOR_EXPLICIT_SAVE,
        blockers = emptySet(), gaps = emptySet(),
        input = RecipeInput("Geprüfter Reis", 2_000L,
            listOf(RecipeItemInput("verified-version", 200_000L)), "Kochen"),
        approvalBasisDigest = digest
    )

    @Test fun onlyExplicitCheckboxAndButtonGestureEmitsBoundDigestOnce() {
        val calls = mutableListOf<String>()
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme { RecipeDraftConsentReview(review()) { digest, _ -> calls += digest } }
            }
        }
        rule.onNodeWithText("Soll-Portionen: 2").assertExists()
        rule.onNodeWithText("Artikelversion verified-version · Menge in bestätigter Basiseinheit: 200").assertExists()
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsEnabled().performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf("a".repeat(64)), calls) }
    }

    @Test fun newPreviewDigestRequiresNewExplicitGesture() {
        val current = mutableStateOf(review())
        val calls = mutableListOf<String>()
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme { RecipeDraftConsentReview(current.value) { digest, _ -> calls += digest } }
            }
        }
        rule.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsEnabled()
        rule.runOnUiThread { current.value = review("b".repeat(64)) }
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").performClick()
        rule.runOnIdle { assertEquals(listOf("b".repeat(64)), calls) }
    }

    @Test fun blockedOrUnboundPreviewCannotEmitConfirmation() {
        val current = mutableStateOf(review().copy(approvalBasisDigest = null))
        val calls = mutableListOf<String>()
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme { RecipeDraftConsentReview(current.value) { digest, _ -> calls += digest } }
            }
        }
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertDoesNotExist()
        rule.runOnUiThread {
            current.value = review().copy(status = Gate.Status.BLOCKED, blockers = setOf("MISSING_EVIDENCE"))
        }
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertDoesNotExist()
        rule.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }
    @Test fun identicalDigestInNewHostSessionRequiresFreshGesture() {
        val session = mutableStateOf("first-session")
        val calls = mutableListOf<Pair<String, String?>>()
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    RecipeDraftConsentReview(review(), sessionKey = session.value) { digest, renderedSession ->
                        calls += digest to renderedSession
                    }
                }
            }
        }
        rule.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        rule.runOnUiThread { session.value = "second-session" }
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").performClick()
        rule.runOnIdle { assertEquals(listOf("a".repeat(64) to "first-session",
            "a".repeat(64) to "second-session"), calls) }
    }
    @Test fun pendingOrRecordedHostActionDisablesConsentAndRequiresFreshGestureAfterRetry() {
        val enabled = mutableStateOf(true)
        val retry = mutableStateOf(0)
        val calls = mutableListOf<Pair<String, String?>>()
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    RecipeDraftConsentReview(review(), sessionKey = "bound-session",
                        resetVersion = retry.value, actionEnabled = enabled.value) { digest, id ->
                        calls += digest to id
                    }
                }
            }
        }
        rule.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsEnabled()
        rule.runOnUiThread { enabled.value = false } // Host transaction pending.
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("recipe_draft_consent_checkbox").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(emptyList<Pair<String, String?>>(), calls) }
        rule.runOnUiThread {
            enabled.value = true
            retry.value += 1 // Failed host write: a new explicit gesture is required.
        }
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("recipe_draft_consent_checkbox").performClick()
        rule.onNodeWithTag("recipe_draft_consent_confirm").performClick()
        rule.runOnIdle { assertEquals(listOf("a".repeat(64) to "bound-session"), calls) }
        rule.runOnUiThread { enabled.value = false } // Host recorded consent.
        rule.onNodeWithTag("recipe_draft_consent_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("recipe_draft_consent_checkbox").assertIsNotEnabled()
    }

}
