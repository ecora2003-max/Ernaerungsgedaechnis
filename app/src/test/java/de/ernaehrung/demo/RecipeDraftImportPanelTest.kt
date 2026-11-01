package de.ernaehrung.demo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.key
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import de.ernaehrung.demo.data.local.RecipeDraftEncryptedImportCheckpoint
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.screens.RecipeDraftImportPanel
import java.time.OffsetDateTime
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
class RecipeDraftImportPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun explicitDiscardClearsUnconfirmedFormAndInvokesHostForget() {
        var invalidations = 0
        var discards = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(emptyList(),
                            onInvalidate = { invalidations++ },
                            onDiscardPending = { discards++ },
                            onReview = { _, _, _, _ -> })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_draft_json").performTextInput("{draft}")
        rule.onNodeWithTag("recipe_draft_discard_pending").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, discards)
            assertEquals(2, invalidations)
        }
    }

    @Test fun encryptedDraftRequiresExplicitSaveAndRestoreDoesNotReplayEvidenceOrConsent() {
        val checkpoint = object : RecipeDraftEncryptedImportCheckpoint {
            var saved: String? = null
            override fun save(json: String) { saved = json }
            override fun load(): String? = saved
            override fun discard() { saved = null }
        }
        var reviews = 0
        var epoch = 0
        fun render() {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        key(epoch) {
                            RecipeDraftImportPanel(emptyList(), checkpointStore = checkpoint,
                                onInvalidate = {}, onDiscardPending = {},
                                onReview = { _, _, _, _ -> reviews++ })
                        }
                    }
                }
            }
        }
        rule.runOnUiThread { render() }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_draft_json").performTextInput("{draft}")
        rule.runOnIdle { assertEquals(null, checkpoint.saved) }
        rule.onNodeWithTag("recipe_draft_encrypted_save").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("{draft}", checkpoint.saved) }

        // A fresh composition must not restore or confirm anything automatically.
        rule.runOnUiThread { epoch++; render() }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_draft_encrypted_restore").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_json").assertTextContains("{draft}")
        rule.onNodeWithTag("recipe_draft_manual_review").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, reviews) }
        rule.onNodeWithTag("recipe_draft_discard_pending").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(null, checkpoint.saved) }
    }

    @Test fun oversizedPasteDoesNotDiscardEncryptedDraftOrInvalidateCurrentReview() {
        val checkpoint = object : RecipeDraftEncryptedImportCheckpoint {
            var saved: String? = null
            override fun save(json: String) { saved = json }
            override fun load(): String? = saved
            override fun discard() { saved = null }
        }
        var invalidations = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(emptyList(), checkpointStore = checkpoint,
                            onInvalidate = { invalidations++ },
                            onDiscardPending = {}, onReview = { _, _, _, _ -> })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_draft_json").performTextInput("{draft}")
        rule.onNodeWithTag("recipe_draft_encrypted_save").performScrollTo().performClick()
        val before = invalidations
        rule.onNodeWithTag("recipe_draft_json").performTextInput("x".repeat(262_145))
        rule.onNodeWithTag("recipe_draft_json").assertTextContains("{draft}")
        rule.onNodeWithTag("recipe_draft_import_error").assertExists()
        rule.runOnIdle {
            assertEquals("{draft}", checkpoint.saved)
            assertEquals(before, invalidations)
        }
        // A real accepted edit still revokes the old saved checkpoint.
        rule.onNodeWithTag("recipe_draft_json").performTextInput("x")
        rule.runOnIdle {
            assertEquals(null, checkpoint.saved)
            assertEquals(before + 1, invalidations)
        }
    }

    @Test fun malformedJsonNeverOpensEvidenceOrConsent() {
        var reviews = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(emptyList(), onInvalidate = {},
                            onDiscardPending = {}, onReview = { _, _, _, _ -> reviews++ })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_draft_json").performTextInput("{invalid")
        rule.onNodeWithTag("recipe_draft_json_inspect").performClick()
        rule.onNodeWithTag("recipe_draft_import_error").assertExists()
        rule.onNodeWithTag("recipe_draft_manual_review").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, reviews) }
    }

    @Test fun parsedModelClaimCannotBypassMissingIndependentCatalogEvidence() {
        val now = OffsetDateTime.now().toString()
        val json = """
            {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
             "meta":{"producer":"synthetic","traceId":"compose","createdAt":"$now"},
             "payload":{"draftId":"draft-compose","title":"Reis","portionCount":2,
               "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"CONFIRMED",
                 "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":true}}],
               "steps":["Kochen"],"flags":{"considerStock":false,
                 "considerShopping":false,"considerOffers":false},
               "warnings":[],"status":"DRAFT"}}
        """.trimIndent()
        var reviews = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(emptyList(), onInvalidate = {},
                            onDiscardPending = {}, onReview = { _, _, _, _ -> reviews++ })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_draft_json").performTextInput(json)
        rule.onNodeWithTag("recipe_draft_json_inspect").performClick()
        rule.onNodeWithTag("recipe_draft_manual_review").assertExists()
        rule.onNodeWithTag("recipe_draft_manual_review_checkbox").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_manual_review").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_import_error").assertExists()
        rule.runOnIdle { assertEquals(0, reviews) }
    }
}
