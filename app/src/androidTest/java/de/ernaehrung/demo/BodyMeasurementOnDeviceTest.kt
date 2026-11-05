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
    private fun finishEditing() {
        compose.runOnUiThread {
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(5_000) {
            Build.VERSION.SDK_INT < 30 || compose.activity.window.decorView.rootWindowInsets
                ?.isVisible(WindowInsets.Type.ime()) != true
        }
        compose.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1_500L)
    }
    private fun tap(tag: String) {
        finishEditing(); waitTag(tag)
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
                .addMigrations(FoodMigrations.MIGRATION_9_10).build()
            try {
                assertEquals(10, reopened.openHelper.readableDatabase.version)
                assertEquals(first, reopened.bodyDao().version(first.id))
                assertEquals(first.id, FoodStore(reopened).saveBodyMeasurement(first.runId, first.input(), null, true))
                assertEquals(2, reopened.bodyDao().versions().size)
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { reopened.close() }
            compose.activityRule.scenario.recreate(); waitTag("body_saved")
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
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("body-flow")
            throw failure
        } finally { store.purgeBodyMeasurements("$token-cleanup", true) }
    }
}
