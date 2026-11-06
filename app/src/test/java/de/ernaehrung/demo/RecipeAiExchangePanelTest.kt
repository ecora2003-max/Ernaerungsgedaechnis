package de.ernaehrung.demo

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.screens.RecipeDraftImportPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeAiExchangePanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun mount() {
        rule.runOnUiThread { rule.activity.setContent { DemoTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RecipeDraftImportPanel(emptyList(), onInvalidate = {}, onDiscardPending = {}, onReview = { _, _, _, _ -> fail("AI response alone cannot request a save") })
            }
        } } }
        rule.onNodeWithText("Rezeptvorschläge und JSON-Import").performClick()
        rule.onNodeWithText("Rezept mit ChatGPT oder anderer KI entwerfen").performScrollTo().performClick()
    }
    private fun tap(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()
    private fun type(tag: String, value: String) = rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun prepare() {
        type("recipe_ai_wish", "Erfinde eine neue Zitronenpfanne")
        tap("recipe_ai_prepare")
    }
    private fun prompt(): String = rule.onNodeWithTag("recipe_ai_prompt").fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    @Test fun preparationIsLocalAndAndroidChooserRequiresFreshExplicitApprovalAfterEachRequestChange() {
        mount(); prepare()
        rule.onNodeWithTag("recipe_ai_share").assertIsNotEnabled()
        assertNull(Shadows.shadowOf(rule.activity).nextStartedActivity)
        tap("recipe_ai_export_approval"); tap("recipe_ai_share")
        val chooser = Shadows.shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(prompt(), send.getStringExtra(Intent.EXTRA_TEXT))
        type("recipe_ai_wish", "Ein anderes Rezept")
        rule.onNodeWithTag("recipe_ai_prompt").assertDoesNotExist()
        tap("recipe_ai_prepare")
        rule.onNodeWithTag("recipe_ai_share").assertIsNotEnabled()
        rule.onNodeWithTag("recipe_draft_manual_review").assertDoesNotExist()
        tap("recipe_ai_export_approval")
        tap("recipe_draft_discard_pending")
        rule.onNodeWithTag("recipe_ai_prompt").assertDoesNotExist()
        rule.onNodeWithText("Rezept mit ChatGPT oder anderer KI entwerfen").performScrollTo().performClick()
        rule.onNodeWithTag("recipe_ai_wish").assertTextEquals("Was möchtest du kochen?", "")
    }

    @Test fun novelModelReplyEntersExistingIndependentReviewAndWrongRequestNeverReplacesPreview() {
        mount(); prepare()
        val template = RecipeDraftBoundedJson.decode(prompt().substringAfter("ANTWORTVORLAGE:\n"))
        val payload = template["payload"] as Map<*, *>
        val reply = template + mapOf("payload" to payload + mapOf("title" to "Neue synthetische Zitronenpfanne",
            "ingredients" to listOf(mapOf("foodTypeId" to "novel-food", "label" to "Neue Zutat",
                "quantity" to mapOf("amount" to 160, "unit" to "g", "basis" to "NET", "confirmed" to false),
                "sourceStatus" to "UNKNOWN"))))
        type("recipe_ai_reply", JsonOutput.encode(reply)); tap("recipe_ai_inspect")
        rule.onNodeWithText("Entwurf: Neue synthetische Zitronenpfanne · 2 Soll-Portionen").assertExists()
        rule.onNodeWithTag("recipe_draft_manual_review_checkbox").assertIsOff()
        rule.onNodeWithTag("recipe_draft_manual_review").assertIsNotEnabled()
        val wrong = reply + mapOf("payload" to payload + mapOf("draftId" to "old-request", "title" to "Falsche alte Antwort"))
        type("recipe_ai_reply", JsonOutput.encode(wrong)); tap("recipe_ai_inspect")
        rule.onNodeWithTag("recipe_ai_result").assertTextContains("Antwort nicht übernommen", substring = true)
        rule.onNodeWithText("Entwurf: Neue synthetische Zitronenpfanne · 2 Soll-Portionen").assertExists()
        rule.onNodeWithText("Entwurf: Falsche alte Antwort · 2 Soll-Portionen").assertDoesNotExist()
    }
}
