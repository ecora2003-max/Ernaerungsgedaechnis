package de.ernaehrung.demo

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.fitness.*
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
class BodyMeasurementUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun tag(id: String) = rule.onNodeWithTag(id)
    private fun waitTag(id: String) = rule.waitUntil(25_000) {
        rule.waitForIdle(); rule.onAllNodesWithTag(id).fetchSemanticsNodes().size == 1
    }
    private fun tap(id: String) {
        waitTag(id)
        rule.waitUntil(25_000) { rule.waitForIdle(); runCatching { tag(id).assertIsEnabled(); true }.getOrDefault(false) }
        if (id.startsWith("workspace_tab_")) tag(id).performClick() else tag(id).performScrollTo().performClick()
    }
    private fun field(id: String, value: String) {
        waitTag(id); tag(id).performScrollTo().performTextReplacement(value)
        rule.runOnUiThread {
            (rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
        }
        rule.waitForIdle()
    }
    @Test fun defaultBodyUiRequiresFreshApprovalPreservesOriginalCorrectionAndRecoversCommittedKeyAfterRecreation() = runBlocking {
        val db = FoodDatabase.localSandbox(rule.activity.applicationContext)
        assertTrue(db.bodyDao().versions().isEmpty()); assertTrue(db.bodyDao().legacy().isEmpty())
        val store = FoodStore(db)
        db.openHelper.writableDatabase.execSQL("INSERT INTO body_measurements(id,measuredAt,weightKg,method,source,quality,excludedFromTrend,recordedAt) VALUES('body-legacy-ui',1,74.0,'Frühere Methode','USER','UNKNOWN',0,1)")
        val legacyOriginal = db.bodyDao().legacy().single()
        val before = db.foodDao().operationCount()
        tap("workspace_tab_MORE"); tap("workspace_go_BODY")
        waitTag("body_save"); tag("body_save").assertIsNotEnabled()
        field("body_input_value", "73,0400")
        field("body_input_date", "2026-10-25")
        field("body_input_time", "02:30")
        field("body_input_zone", "Europe/Berlin")
        field("body_input_offset", "+02:00")
        field("body_input_device", "Synthetische Testwaage")
        field("body_input_conditions", "Morgens vor Frühstück")
        tap("body_method_SCALE"); tap("body_quality_MEASURED")
        tap("body_confirm"); tag("body_confirm").assertIsOn()
        field("body_input_value", "73,14")
        tag("body_confirm").performScrollTo().assertIsOff(); tag("body_save").assertIsNotEnabled()
        assertTrue(db.bodyDao().versions().isEmpty())
        tap("body_confirm"); tap("body_save")
        waitTag("body_saved")
        val first = db.bodyDao().versions().single()
        assertEquals("73,14", first.rawValue); assertEquals("2026-10-25T00:30:00Z", first.utc)
        assertEquals(7200, first.offsetSeconds); assertEquals("Morgens vor Frühstück", first.conditions)
        rule.activityRule.scenario.recreate()
        waitTag("body_saved")
        assertEquals(1, db.bodyDao().versions().size)
        tap("body_new")
        field("body_input_value", "999")
        tap("body_confirm")
        rule.activityRule.scenario.recreate()
        waitTag("body_input_value")
        tag("body_input_value").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        tag("body_confirm").performScrollTo().assertIsOff(); tag("body_save").assertIsNotEnabled()
        tap("body_correct_${first.id}")
        field("body_input_value", "73,24")
        field("body_input_reason", "Übertragungsfehler im Test")
        tag("body_save").performScrollTo().assertIsNotEnabled()
        tap("body_confirm"); tap("body_save"); waitTag("body_saved")
        rule.waitUntil(25_000) { runBlocking { db.bodyDao().versions().size == 2 } }
        val current = store.bodyMeasurementHistory().first().latest.single()
        assertEquals(2L, current.revision); assertEquals(first.id, current.previousId)
        tap("body_history_${current.id}")
        waitTag("body_version_${first.id}")
        tag("body_version_${first.id}").performScrollTo().assertTextContains("73,14", substring = true)
        assertEquals(first.id, store.saveBodyMeasurement(first.runId, first.input(), null, true))
        assertEquals(2, db.bodyDao().versions().size); assertEquals(before, db.foodDao().operationCount())
        tap("body_legacy_choose_body-legacy-ui"); tap("body_legacy_field_body-legacy-ui_WEIGHT")
        tag("body_input_date").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        field("body_input_date", "2026-10-25"); field("body_input_value", "75,5000")
        field("body_input_reason", "Eigenes Messdatum und Gewicht geprüft")
        tap("body_confirm"); tap("body_save"); waitTag("body_saved")
        val legacyCurrent = db.bodyDao().versions().single { it.legacyOriginId == "body-legacy-ui" }
        assertEquals("75.5", legacyCurrent.decimalValue); assertNull(legacyCurrent.utc)
        assertEquals(legacyOriginal, db.bodyDao().legacy().single())
        tap("body_history_${legacyCurrent.id}"); waitTag("body_legacy_original_body-legacy-ui")
        tag("body_legacy_original_body-legacy-ui").performScrollTo().assert(hasAnyDescendant(hasText("Gewicht: 74.0 kg", substring = true)))
        val healthBefore = db.healthDao().history("com.sec.android.app.shealth")
        val authBefore = db.healthDao().state("com.sec.android.app.shealth")
        tap("workspace_tab_MORE"); tap("workspace_go_FITNESS")
        waitTag("fitness_body_value_${current.id}")
        tag("fitness_body_value_${current.id}").performScrollTo().assertTextEquals("Gewicht: 73,24 kg")
        tag("fitness_body_value_${first.id}").assertDoesNotExist()
        assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth"))
        assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
        assertEquals(before, db.foodDao().operationCount())
        tap("fitness_open_body")
        tap("body_new"); field("body_input_value", "123")
        tag("body_delete").performScrollTo().assertIsNotEnabled()
        tap("body_delete_confirm"); tap("body_delete")
        waitTag("body_empty")
        rule.waitUntil(25_000) { runBlocking { db.bodyDao().versions().isEmpty() } }
        tag("body_input_value").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals("REDACTED", db.bodyDao().run(first.runId)!!.kind)
        assertEquals(before, db.foodDao().operationCount())
        tap("workspace_tab_MORE"); tap("workspace_go_FITNESS")
        waitTag("fitness_body_empty")
        tag("fitness_body_value_${current.id}").assertDoesNotExist()
        assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
    }
}

