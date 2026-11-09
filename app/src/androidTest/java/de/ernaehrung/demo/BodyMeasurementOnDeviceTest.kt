package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual default Android form/SQLite/restart/privacy with synthetic manual body
 * values. This is explicitly NOT a physical Watch/Health Connect acceptance.
 */
@RunWith(AndroidJUnit4::class)
class BodyMeasurementOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun finishEditing() = finishDeviceTextEditing(compose)
    private fun tap(tag: String) {
        finishEditing(); waitTag(tag)
        compose.waitUntil(25_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false) }
        val node = compose.onNodeWithTag(tag)
        if (tag.startsWith("workspace_tab_")) node.performClick() else node.performScrollTo().performClick()
    }
    private fun field(tag: String, value: String) {
        waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        finishEditing()
    }

    @Test fun offlineDefaultBodyFormCorrectionRestartRetryAndExplicitDeletionPreserveFoodAndOriginals() = runBlocking {
        assertTrue("Synthetic cleanup requires a disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Require actual network absence, not just disabled radios", connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        assertTrue("Never overwrite an existing body history", db.bodyDao().versions().isEmpty())
        assertTrue("Never overwrite a legacy body history", db.bodyDao().legacy().isEmpty())
        val store = FoodStore(db)
        val token = "synthetic-body-${UUID.randomUUID()}"
        val foodBefore = store.operations().first()
        val batchesBefore = db.foodDao().planningBatches()
        val mealsBefore = db.foodDao().activeConsumptionCount()
        val healthBefore = db.healthDao().history("com.sec.android.app.shealth")
        try {
            db.openHelper.writableDatabase.execSQL("INSERT INTO body_measurements(id,measuredAt,weightKg,method,source,quality,excludedFromTrend,recordedAt) VALUES(?,1,74.0,'Frühere Methode','USER','UNKNOWN',0,1)", arrayOf("$token-legacy"))
            val legacyOriginal = db.bodyDao().legacy().single()
            tap("workspace_tab_MORE"); tap("workspace_go_BODY")
            waitTag("body_save"); compose.onNodeWithTag("body_save").assertIsNotEnabled()
            field("body_input_value", "73,0400")
            field("body_input_date", "2026-10-25")
            field("body_input_time", "02:30")
            field("body_input_zone", "Europe/Berlin")
            field("body_input_offset", "+02:00")
            field("body_input_device", "Synthetische CI-Waage")
            field("body_input_model", "Kein Watch-Beleg")
            field("body_input_conditions", "Morgens vor Frühstück · synthetisch")
            tap("body_method_SCALE"); tap("body_quality_MEASURED")
            tap("body_confirm"); compose.onNodeWithTag("body_confirm").assertIsOn()
            field("body_input_value", "73,14")
            compose.onNodeWithTag("body_confirm").performScrollTo().assertIsOff()
            compose.onNodeWithTag("body_save").assertIsNotEnabled()
            assertTrue(db.bodyDao().versions().isEmpty())
            tap("body_confirm"); tap("body_save"); waitTag("body_saved")
            val first = db.bodyDao().versions().single()
            assertEquals("73,14", first.rawValue); assertEquals("73.14", first.decimalValue)
            assertEquals("2026-10-25T00:30:00Z", first.utc); assertEquals(7200, first.offsetSeconds)
            assertEquals("MINUTE", first.timePrecision)
            compose.activityRule.scenario.recreate(); waitTag("body_saved")
            assertEquals(1, db.bodyDao().versions().size)
            tap("body_new"); field("body_input_value", "999")
            tap("body_confirm")
            compose.activityRule.scenario.recreate(); waitTag("body_input_value")
            compose.onNodeWithTag("body_input_value").performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("body_confirm").performScrollTo().assertIsOff()
            compose.onNodeWithTag("body_save").assertIsNotEnabled()
            tap("body_correct_${first.id}")
            field("body_input_value", "73,24")
            field("body_input_reason", "Synthetischen Übertragungsfehler korrigiert")
            tap("body_confirm"); tap("body_save"); waitTag("body_saved")
            compose.waitUntil(25_000) { runBlocking { db.bodyDao().versions().size == 2 } }
            val current = store.bodyMeasurementHistory().first().latest.single()
            assertEquals(first.id, current.previousId); assertEquals(2L, current.revision)
            tap("body_history_${current.id}"); waitTag("body_version_${first.id}")
            compose.onNodeWithTag("body_version_${first.id}").performScrollTo()
                .assertTextContains("73,14", substring = true)
            val reopened = Room.databaseBuilder(target, FoodDatabase::class.java, FoodDatabase.TEST_DATABASE_NAME)
                .addMigrations(FoodMigrations.MIGRATION_9_10, FoodMigrations.MIGRATION_10_11, FoodMigrations.MIGRATION_11_12, FoodMigrations.MIGRATION_12_13, FoodMigrations.MIGRATION_13_14, FoodMigrations.MIGRATION_14_15, FoodMigrations.MIGRATION_15_16, FoodMigrations.MIGRATION_16_17).build()
            try {
                assertEquals(19, reopened.openHelper.readableDatabase.version)
                assertEquals(first, reopened.bodyDao().version(first.id))
                assertEquals(first.id, FoodStore(reopened).saveBodyMeasurement(first.runId, first.input(), null, true))
                assertEquals(2, reopened.bodyDao().versions().size)
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { reopened.close() }
            compose.activityRule.scenario.recreate(); waitTag("body_saved")
            tap("body_legacy_choose_$token-legacy"); tap("body_legacy_field_${token}-legacy_WEIGHT")
            compose.onNodeWithTag("body_input_date").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            field("body_input_date", "2026-10-25"); field("body_input_value", "75,5000")
            field("body_input_reason", "Eigenes Messdatum und Gewicht geprüft")
            tap("body_confirm"); tap("body_save"); waitTag("body_saved")
            val legacyCurrent = db.bodyDao().versions().single { it.legacyOriginId == "$token-legacy" }
            assertEquals("75.5", legacyCurrent.decimalValue); assertNull(legacyCurrent.utc)
            assertEquals(legacyOriginal, db.bodyDao().legacy().single())
            tap("body_history_${legacyCurrent.id}"); waitTag("body_legacy_original_$token-legacy")
            compose.onNodeWithTag("body_legacy_original_$token-legacy").performScrollTo()
                .assert(hasAnyDescendant(hasText("Gewicht: 74.0 kg", substring = true)))
            val authBeforeOverview = db.healthDao().state("com.sec.android.app.shealth")
            tap("workspace_tab_MORE"); tap("workspace_go_FITNESS")
            waitTag("fitness_body_value_${current.id}")
            compose.onNodeWithTag("fitness_body_value_${current.id}").performScrollTo().assertTextEquals("Gewicht: 73,24 kg")
            compose.onNodeWithTag("fitness_body_value_${first.id}").assertDoesNotExist()
            assertEquals(authBeforeOverview, db.healthDao().state("com.sec.android.app.shealth"))
            assertEquals(foodBefore, store.operations().first())
            tap("fitness_open_body")
            compose.onNodeWithTag("body_delete").performScrollTo().assertIsNotEnabled()
            tap("body_delete_confirm"); tap("body_delete"); waitTag("body_empty")
            assertTrue(db.bodyDao().versions().isEmpty())
            assertEquals("REDACTED", db.bodyDao().run(first.runId)!!.kind)
            try {
                store.saveBodyMeasurement(first.runId, first.input(), null, true)
                fail("A deleted operation must not restore old health values")
            } catch (_: IllegalArgumentException) { }
            assertEquals(foodBefore, store.operations().first()); assertEquals(batchesBefore, db.foodDao().planningBatches())
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth"))
            tap("workspace_tab_MORE"); tap("workspace_go_FITNESS"); waitTag("fitness_body_empty")
            compose.onNodeWithTag("fitness_body_value_${current.id}").assertDoesNotExist()
            assertEquals(authBeforeOverview, db.healthDao().state("com.sec.android.app.shealth"))
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("body-flow")
            throw failure
        } finally { store.purgeBodyMeasurements("$token-cleanup", true) }
    }
}
