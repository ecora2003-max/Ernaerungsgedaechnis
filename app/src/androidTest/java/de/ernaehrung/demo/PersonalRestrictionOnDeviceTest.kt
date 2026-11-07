package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PersonalRestrictionOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(tag: String) {
        finishDeviceTextEditing(compose); waitTag(tag)
        if (tag in setOf("personal_restrictions_confirm_checked","personal_restrictions_confirm","personal_restrictions_erase_checked","personal_restrictions_erase"))
            compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun field(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun more() { compose.onNodeWithTag("workspace_tab_MORE").performClick() }

    @Test fun offlineProfileSurvivesRestartNeverActivatesAProposalRevokesBoundExportAndErasesOnlyCurrentPrivateValues() = runBlocking {
        assertTrue("Disposable emulator only",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Actual network absence",connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val store = FoodStore(db)
        val id = UUID.randomUUID().toString()
        val before = db.foodDao().operationCount()
        val batches = db.foodDao().planningBatches()
        val meals = db.foodDao().activeConsumptionCount()
        try {
            more(); tap("workspace_go_PERSONAL_RESTRICTIONS"); tap("personal_restrictions_new")
            field("personal_restrictions_text","ALLERGEN:SOY")
            field("personal_restrictions_reason","synthetic-device: eigene Vorgabe $id")
            tap("personal_restrictions_propose"); waitTag("personal_restrictions_review")
            assertNull(store.readPersonalRestrictions().active)
            tap("personal_restrictions_review")
            compose.onNodeWithTag("personal_restrictions_confirm").assertIsNotEnabled()
            tap("personal_restrictions_confirm_checked")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("workspace_page_PERSONAL_RESTRICTIONS").assertExists()
            compose.onNodeWithTag("personal_restrictions_confirm").assertDoesNotExist()
            assertNull(store.readPersonalRestrictions().active)
            tap("personal_restrictions_review")
            compose.onNodeWithTag("personal_restrictions_confirm_checked").assertIsOff()
            tap("personal_restrictions_confirm_checked"); tap("personal_restrictions_confirm")
            waitTag("personal_restrictions_active_text")
            compose.onNodeWithTag("personal_restrictions_active_text").assertTextEquals("ALLERGEN:SOY")
            compose.activityRule.scenario.recreate(); waitTag("personal_restrictions_active_text")
            compose.onNodeWithTag("personal_restrictions_active_text").assertTextEquals("ALLERGEN:SOY")
            val oldBinding = store.readPersonalRestrictions().binding
            more(); tap("workspace_go_KITCHEN"); waitTag("recipe_personal_restrictions")
            compose.onNodeWithTag("recipe_personal_restrictions").assertTextContains("ALLERGEN:SOY",substring = true)
            compose.onNodeWithText("Rezeptvorschläge und JSON-Import").performScrollTo().performClick()
            compose.onNodeWithText("Rezept mit ChatGPT oder anderer KI entwerfen").performScrollTo().performClick()
            field("recipe_ai_wish","synthetic-device: Profil verwenden")
            tap("recipe_ai_prepare"); waitTag("recipe_ai_prompt")
            val prompt = compose.onNodeWithTag("recipe_ai_prompt").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            assertTrue(prompt.contains("ALLERGEN:SOY"))
            tap("recipe_ai_export_approval"); compose.onNodeWithTag("recipe_ai_export_approval").assertIsOn()
            val proposal = requireNotNull(store.proposePersonalRestrictions("$id-propose-again",PersonalRestrictionInput(
                store.readPersonalRestrictions().latest?.id,"ALLERGEN:SOY","Neue synthetische Grundlage"),true).versionId)
            store.confirmPersonalRestrictions("$id-confirm-again",proposal,store.readPersonalRestrictions().versionBinding(proposal),true)
            assertNotEquals(oldBinding,store.readPersonalRestrictions().binding)
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("recipe_ai_prompt").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("recipe_ai_export_approval").assertDoesNotExist()
            more(); tap("workspace_go_PERSONAL_RESTRICTIONS"); tap("personal_restrictions_erase_review")
            tap("personal_restrictions_erase_checked")
            store.proposePersonalRestrictions("$id-erase-scope-changed",PersonalRestrictionInput(
                store.readPersonalRestrictions().latest?.id,"DIET:VEGAN","Noch inaktiver synthetischer Vorschlag"),true)
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Löschumfang geändert. Schließen und neu prüfen.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("personal_restrictions_erase").assertIsNotEnabled()
            compose.onNodeWithTag("personal_restrictions_erase_checked").assertIsOff()
            compose.onNodeWithText("Abbrechen").performClick()
            tap("personal_restrictions_erase_review"); tap("personal_restrictions_erase_checked"); tap("personal_restrictions_erase")
            waitTag("personal_restrictions_active_empty")
            val erased = store.readPersonalRestrictions()
            assertNull(erased.active)
            assertTrue(erased.history.all { it.rawText == null && it.reason == null && it.zoneId == null && it.payloadHash == null })
            more(); tap("workspace_go_KITCHEN"); waitTag("recipe_personal_restrictions")
            compose.onNodeWithTag("recipe_personal_restrictions").assertTextContains("Keine eigenen bestätigten",substring = true)
            compose.activityRule.scenario.recreate(); waitTag("recipe_personal_restrictions")
            compose.onNodeWithTag("recipe_personal_restrictions").assertTextContains("Keine eigenen bestätigten",substring = true)
            assertEquals(before,db.foodDao().operationCount())
            assertEquals(batches,db.foodDao().planningBatches()); assertEquals(meals,db.foodDao().activeConsumptionCount())
            assertNull("No default network after restart and private erasure",connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("personal-restrictions-flow"); throw failure
        }
    }
}
