package de.ernaehrung.demo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalRestrictionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(tag: String) {
        waitTag(tag)
        if (tag in setOf("personal_restrictions_confirm_checked","personal_restrictions_confirm","personal_restrictions_erase_checked","personal_restrictions_erase"))
            compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    @Test fun defaultNavigationKeepsProposalInactiveAndBoundAiExportIsRevokedEvenWhenOnlyTheConfirmedProfileIdentityChanges() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val before = db.foodDao().operationCount()
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_PERSONAL_RESTRICTIONS")
        waitTag("personal_restrictions_new"); tap("personal_restrictions_new")
        compose.onNodeWithTag("personal_restrictions_text").performScrollTo().performTextReplacement("ALLERGEN:SOY")
        compose.onNodeWithTag("personal_restrictions_reason").performScrollTo().performTextReplacement("Synthetische eigene Vorgabe")
        tap("personal_restrictions_propose"); waitTag("personal_restrictions_review")
        assertNull(store.readPersonalRestrictions().active)
        tap("personal_restrictions_review")
        compose.onNodeWithTag("personal_restrictions_confirm").assertIsNotEnabled()
        tap("personal_restrictions_confirm_checked"); tap("personal_restrictions_confirm")
        waitTag("personal_restrictions_active_text")
        compose.onNodeWithTag("personal_restrictions_active_text").assertTextEquals("ALLERGEN:SOY")
        val originalBinding = store.readPersonalRestrictions().binding
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_KITCHEN")
        waitTag("recipe_personal_restrictions")
        compose.onNodeWithTag("recipe_personal_restrictions").assertTextContains("ALLERGEN:SOY",substring = true)
        compose.onNodeWithText("Rezeptvorschläge und JSON-Import").performScrollTo().performClick()
        compose.onNodeWithText("Rezept mit ChatGPT oder anderer KI entwerfen").performScrollTo().performClick()
        compose.onNodeWithTag("recipe_ai_wish").performScrollTo().performTextReplacement("synthetic-device: Profilbindung")
        tap("recipe_ai_prepare"); waitTag("recipe_ai_prompt")
        val prompt = compose.onNodeWithTag("recipe_ai_prompt").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertTrue(prompt.contains("ALLERGEN:SOY"))
        tap("recipe_ai_export_approval"); compose.onNodeWithTag("recipe_ai_export_approval").assertIsOn()
        val data = store.readPersonalRestrictions()
        val proposal = requireNotNull(store.proposePersonalRestrictions("ui-same-tags",PersonalRestrictionInput(data.latest?.id,
            "ALLERGEN:SOY","Neu bestätigte eigene Grundlage"),true).versionId)
        store.confirmPersonalRestrictions("ui-same-tags-confirm",proposal,store.readPersonalRestrictions().versionBinding(proposal),true)
        assertNotEquals(originalBinding,store.readPersonalRestrictions().binding)
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("recipe_ai_prompt").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("recipe_ai_export_approval").assertDoesNotExist()
        assertEquals(before,db.foodDao().operationCount())
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_PERSONAL_RESTRICTIONS")
        tap("personal_restrictions_erase_review"); tap("personal_restrictions_erase_checked"); tap("personal_restrictions_erase")
        waitTag("personal_restrictions_active_empty")
        assertTrue(store.readPersonalRestrictions().history.all { it.rawText == null && it.reason == null && it.payloadHash == null })
        assertEquals(before,db.foodDao().operationCount())
    }
}
