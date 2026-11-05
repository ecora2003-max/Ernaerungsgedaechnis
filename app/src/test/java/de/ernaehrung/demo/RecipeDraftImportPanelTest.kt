package de.ernaehrung.demo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.key
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import de.ernaehrung.demo.data.local.RecipeDraftEvidenceForm
import de.ernaehrung.demo.data.local.RecipeDraftEncryptedEvidenceCheckpoint
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
        rule.onNodeWithTag("recipe_draft_restrictions").performTextInput("ALLERGEN:SOY")
        rule.onNodeWithTag("recipe_draft_restrictions").assertTextContains("ALLERGEN:SOY")
        // A changed JSON draft cannot inherit manually entered exclusions from
        // the previous draft, even when the new JSON is still schema-valid.
        rule.onNodeWithTag("recipe_draft_json").performTextInput(" ")
        rule.onNodeWithTag("recipe_draft_json_inspect").performClick()
        rule.onNodeWithTag("recipe_draft_restrictions").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.onNodeWithTag("recipe_draft_manual_review_checkbox").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_manual_review").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_import_error").assertExists()
        rule.runOnIdle { assertEquals(0, reviews) }
    }

    @Test fun encryptedEvidenceResumeIsOptInDraftBoundAndRequiresNewManualReview() {
        val checkpoint = object : RecipeDraftEncryptedEvidenceCheckpoint {
            var saved: RecipeDraftEvidenceForm? = null
            override fun save(form: RecipeDraftEvidenceForm) { saved = form }
            override fun load(): RecipeDraftEvidenceForm? = saved
            override fun discard() { saved = null }
        }
        val now = OffsetDateTime.now().toString()
        val json = """
            {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
             "meta":{"producer":"synthetic","traceId":"evidence-compose","createdAt":"$now"},
             "payload":{"draftId":"draft-evidence","title":"Reis","portionCount":2,
               "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"CONFIRMED",
                 "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":true}}],
               "steps":["Kochen"],"flags":{"considerStock":false,
                 "considerShopping":false,"considerOffers":false},
               "warnings":[],"status":"DRAFT"}}
        """.trimIndent()
        var reviews = 0
        var epoch = 0
        fun render() {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        key(epoch) {
                            RecipeDraftImportPanel(emptyList(), evidenceCheckpointStore = checkpoint,
                                onInvalidate = {}, onDiscardPending = {},
                                onReview = { _, _, _, _ -> reviews++ })
                        }
                    }
                }
            }
        }
        rule.runOnUiThread { render() }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_draft_json").performTextInput(json)
        rule.onNodeWithTag("recipe_draft_json_inspect").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_restrictions").performTextInput("ALLERGEN:SOY")
        rule.onNodeWithTag("recipe_draft_evidence_save").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals("ALLERGEN:SOY", checkpoint.saved?.restrictionsText)
            assertEquals(0, reviews)
        }

        rule.runOnUiThread { epoch++; render() }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_draft_json").performTextInput(json)
        rule.onNodeWithTag("recipe_draft_json_inspect").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_evidence_restore").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_restrictions").assertTextContains("ALLERGEN:SOY")
        rule.onNodeWithTag("recipe_draft_manual_review").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, reviews) }

        // Any accepted draft edit revokes the encrypted unconfirmed form.
        rule.onNodeWithTag("recipe_draft_json").performTextInput(" ")
        rule.runOnIdle { assertEquals(null, checkpoint.saved) }
    }

    @Test fun selectingRoomContextRequiresIndependentSnapshotBeforeGeneration() {
        var requested = 0
        var lastFlags: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Flags? = null
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(emptyList(),
                            onInvalidate = {},
                            onDiscardPending = {},
                            onRequestContext = { flags -> requested++; lastFlags = flags },
                            onReview = { _, _, _, _ -> })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_context_stock").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("recipe_context_refresh").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, requested)
            assertEquals(true, lastFlags?.considerStock)
            assertEquals(false, lastFlags?.considerShopping)
            assertEquals(false, lastFlags?.considerOffers)
        }
        // No fake empty context is produced when the host has not returned one.
        rule.onNodeWithTag("recipe_offline_generate").assertIsNotEnabled()
    }

    @Test fun offlineGeneratorCreatesVisibleV1DraftButNeverConfirmsOrBooks() {
        var invalidations = 0
        var reviews = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(emptyList(),
                            onInvalidate = { invalidations++ },
                            onDiscardPending = {},
                            onReview = { _, _, _, _ -> reviews++ })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_json").assertExists()
        rule.onNodeWithText("Entwurfs-ID:", substring = true).assertExists()
        rule.runOnIdle {
            assertEquals(1, invalidations)
            assertEquals(0, reviews)
        }
        // A second generation replaces the draft and invalidates the old review.
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(2, invalidations)
            assertEquals(0, reviews)
        }
    }


    @Test fun boundRoomContextDoesNotEnableRankingWithoutExplicitProductMappings() {
        val at = java.time.Instant.now()
        val flags = de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Flags(
            true, false, false
        )
        val snapshot = de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot(
            de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Source.STOCK,
            "room-stock", "revision-1", at, emptyList()
        )
        val context = de.ernaehrung.demo.data.integration.RecipeDraftHostGate.typedContext(
            flags, at,
            de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Evidence(snapshot, true),
            null, null
        )
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(emptyList(),
                            contextState = de.ernaehrung.demo.ui.RecipeDraftContextUiState(
                                flags, context
                            ),
                            onInvalidate = {}, onDiscardPending = {},
                            onReview = { _, _, _, _ -> })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_context_stock").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_context_mapping_reviewed").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("recipe_draft_manual_review").assertDoesNotExist()
    }

    @Test fun offlineFoodExclusionChangesTheVisibleSuggestionButDoesNotConfirmIt() {
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
        rule.onNodeWithTag("recipe_offline_restrictions").performScrollTo()
            .performTextInput("FOOD:dry-rice")
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_json").assertTextContains("dry-quinoa", substring = true)
        rule.onNodeWithTag("recipe_draft_restrictions").assertTextContains("FOOD:dry-rice")
        rule.onNodeWithTag("recipe_draft_manual_review").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, reviews) }
    }

    @Test fun changingPortionsRevokesGeneratedDraftAndOptInCheckpoint() {
        val checkpoint = object : RecipeDraftEncryptedImportCheckpoint {
            var saved: String? = null
            override fun save(json: String) { saved = json }
            override fun load(): String? = saved
            override fun discard() { saved = null }
        }
        var invalidations = 0
        var reviews = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(emptyList(), checkpointStore = checkpoint,
                            onInvalidate = { invalidations++ },
                            onDiscardPending = {},
                            onReview = { _, _, _, _ -> reviews++ })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_manual_review").assertExists()
        rule.onNodeWithTag("recipe_draft_encrypted_save").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, invalidations)
            assertEquals(true, checkpoint.saved?.contains("recipe.draft"))
        }

        // Explicitly replacing the field avoids cursor-position ambiguity in Compose.
        // No previous draft, review or opt-in encrypted JSON may remain valid.
        rule.onNodeWithTag("recipe_offline_portions").performScrollTo()
            .performTextReplacement("23")
        rule.onNodeWithTag("recipe_draft_manual_review").assertDoesNotExist()
        rule.onNodeWithTag("recipe_draft_json").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString("")
            )
        )
        rule.runOnIdle {
            assertEquals(2, invalidations)
            assertEquals(null, checkpoint.saved)
            assertEquals(0, reviews)
        }
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_json").assertTextContains(
            "\"portionCount\":23", substring = true
        )
        rule.runOnIdle { assertEquals(3, invalidations) }
    }

    @Test fun unknownAllergenExclusionNeverProducesAnUnverifiedOfflineDraft() {
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
        rule.onNodeWithTag("recipe_offline_restrictions").performScrollTo()
            .performTextInput("ALLERGEN:SOY")
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_import_error").assertExists()
        rule.onNodeWithTag("recipe_draft_manual_review").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, reviews) }
    }

    @Test fun allergenProposalRequiresASeparateFullManualReviewBeforeHostPreview() {
        fun version(id: String, name: String) = ProductVersionEntity(
            id = id, productId = id.substringBefore("-v"), revision = 1,
            name = name, brand = null, category = null, barcode = null,
            baseUnit = BaseUnit.GRAM, packageAmount = null,
            nutritionBasis = 100_000L, energyKcal = null, proteinGrams = null,
            fatGrams = null, carbsGrams = null, source = Source.USER,
            sourceDetail = null, quality = Quality.CONFIRMED,
            recordedAt = System.currentTimeMillis() - 60_000L,
            changeReason = "independent manual evidence fixture"
        )
        val catalog = listOf(
            version("rice-v1", "Reis geprüft"),
            version("beans-v1", "Bohnen geprüft"),
            version("tomato-v1", "Tomaten geprüft")
        )
        var reviews = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecipeDraftImportPanel(catalog, onInvalidate = {},
                            onDiscardPending = {},
                            onReview = { _, _, _, _ -> reviews++ })
                    }
                }
            }
        }
        rule.onNodeWithText("JSON-Rezeptentwurf importieren und manuell prüfen").performClick()
        rule.onNodeWithTag("recipe_offline_restrictions").performScrollTo()
            .performTextReplacement("ALLERGEN:SOY")
        rule.onNodeWithText("Allergennachweise für Offline-Vorschläge")
            .performScrollTo().performClick()
        val choices = listOf(
            Triple("Reis (dry-rice)", "Reis geprüft · v1 · rice-v1", "dry-rice"),
            Triple("gegarte Bohnen (cooked-beans)",
                "Bohnen geprüft · v1 · beans-v1", "cooked-beans"),
            Triple("Tomaten (tomato)", "Tomaten geprüft · v1 · tomato-v1", "tomato")
        )
        for ((label, selected, food) in choices) {
            val checkbox = rule.onNodeWithTag("recipe_offline_allergen_absent_${food}_SOY")
            checkbox.performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText("Produktversion für Allergen-Vorprüfung: $label")
                .performScrollTo().performClick()
            rule.onNodeWithText(selected).performClick()
            rule.onNodeWithTag("recipe_offline_allergen_absent_${food}_SOY")
                .performScrollTo().performClick()
        }
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_json")
            .assertTextContains("dry-rice", substring = true)
        rule.onNodeWithTag("recipe_draft_manual_review").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, reviews) }

        choices.forEach { (_, selected, food) ->
            fun field(text: String) = rule.onNode(hasText(text) and
                hasAnyAncestor(hasTestTag("recipe_draft_line_$food")))
            field("Unabhängig geprüfte Katalogversion")
                .performScrollTo().performClick()
            rule.onNode(hasText(selected) and hasAnyAncestor(isPopup())).performClick()
            val basis = if (food == "cooked-beans") "DRAINED" else "NET"
            field(basis).performScrollTo().performClick().assertIsSelected()
            field(if (food == "cooked-beans") "COOKED" else "RAW")
                .performScrollTo().performClick().assertIsSelected()
            field("Nachweislich nicht enthalten")
                .performScrollTo().performClick()
        }
        rule.onNodeWithTag("recipe_draft_manual_review_checkbox").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_manual_review").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_import_error").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, reviews) }

        // Changing the allergen clears every prior product-bound absence.
        rule.onNodeWithTag("recipe_offline_restrictions").performScrollTo()
            .performTextReplacement("ALLERGEN:PEANUT")
        rule.onNodeWithTag("recipe_draft_manual_review").assertDoesNotExist()
        rule.onNodeWithTag("recipe_offline_generate").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_draft_import_error").assertExists()
        rule.runOnIdle { assertEquals(1, reviews) }
    }

}
