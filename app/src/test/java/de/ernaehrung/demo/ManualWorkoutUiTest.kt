package de.ernaehrung.demo

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.first
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
class ManualWorkoutUiTest {
    @get:Rule(order = 0) val database: org.junit.rules.TestRule = IsolatedWorkspaceDatabase()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.waitForIdle(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) {
        waitTag(tag)
        compose.waitUntil(25_000) {
            compose.waitForIdle(); runCatching { compose.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false)
        }
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun field(tag: String, value: String) {
        waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        compose.runOnUiThread { (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0) }
        compose.waitForIdle()
    }
    @Test fun actualManualTrainingFormBindsApprovalPreservesSetsCorrectsVersionsAndPurgesWithoutTouchingBodyFoodOrHealth() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val body = store.saveBodyMeasurement("workout-body-guard", BodyMeasurementInput(BodyMetric.WEIGHT, "73", "2026-10-25"), null, true)
        db.openHelper.writableDatabase.execSQL("INSERT INTO training_events(id,occurredAt,sport,source,quality,recordedAt,note,estimatedKcal) VALUES('workout-legacy-ui',1,'Früheres Training','USER','UNKNOWN',1,'Unveränderte Originalnotiz',0.0)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO training_sets(id,trainingId,exercise,position,repetitions) VALUES('workout-legacy-set','workout-legacy-ui','Ursprünglicher Satz',0,0)")
        val legacyOriginal = db.foodDao().legacyTrainingEvents().single()
        val foodBefore = store.operations().first(); val authBefore = db.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE)
        val healthBefore = db.healthDao().history(FitnessHealthPolicy.SAMSUNG_PACKAGE)
        tap("workspace_tab_MORE"); tap("workspace_go_TRAINING"); tap("workout_new")
        waitTag("workout_save"); compose.onNodeWithTag("workout_save").assertIsNotEnabled()
        field("workout_sport", "Synthetisches Krafttraining")
        field("workout_date", "2026-10-25"); field("workout_time", "02:30")
        field("workout_zone", "Europe/Berlin"); field("workout_offset", "+02:00")
        field("workout_duration", "45,5000"); field("workout_distance", "0,000")
        tap("workout_add_set"); field("workout_set_0_exercise", "Kniebeuge")
        field("workout_set_0_reps", "008"); field("workout_set_0_load", "40,0000")
        tap("workout_confirm"); compose.onNodeWithTag("workout_confirm").assertIsOn()
        field("workout_duration", "45,6000")
        compose.onNodeWithTag("workout_confirm").performScrollTo().assertIsOff()
        compose.onNodeWithTag("workout_save").assertIsNotEnabled()
        assertTrue(db.manualWorkoutDao().versions().isEmpty())
        tap("workout_confirm"); tap("workout_save"); waitTag("workout_saved")
        val first = db.manualWorkoutDao().versions().single()
        assertEquals("45,6000", first.rawDurationMinutes); assertEquals("45.6", first.durationMinutes)
        assertEquals("2026-10-25T00:30:00Z", first.utc); assertEquals("0", first.distanceMetres)
        assertEquals("008", db.manualWorkoutDao().sets().single().rawRepetitions)
        compose.activityRule.scenario.recreate(); waitTag("workout_saved")
        assertEquals(1, db.manualWorkoutDao().versions().size)
        tap("workout_new"); field("workout_sport", "Ungespeicherter privater Entwurf")
        tap("workout_confirm"); compose.activityRule.scenario.recreate(); waitTag("workout_sport")
        compose.onNodeWithTag("workout_sport").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("workout_confirm").performScrollTo().assertIsOff()
        tap("workout_correct_${first.id}"); field("workout_duration", "46,2500")
        field("workout_reason", "Synthetische Dauer korrigiert")
        tap("workout_confirm"); tap("workout_save"); waitTag("workout_saved")
        val current = store.trainingJournal().first().manualLatest.single()
        assertEquals(2L, current.revision); assertEquals(first.id, current.previousId)
        tap("workout_history_${current.id}"); waitTag("workout_version_${first.id}")
        compose.onNodeWithTag("workout_version_${first.id}").performScrollTo().assertTextContains("45,6000", substring = true)
        assertEquals(2, db.manualWorkoutDao().sets().size)
        tap("workout_correct_legacy_workout-legacy-ui"); waitTag("workout_date")
        compose.onNodeWithTag("workout_date").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("workout_save").assertIsNotEnabled()
        field("workout_date", "2026-10-25"); field("workout_duration", "0,000")
        field("workout_reason", "Tagesbezug selbst geprüft; Ortszeit unbekannt")
        tap("workout_confirm"); tap("workout_save"); waitTag("workout_saved")
        val legacyCurrent = store.trainingJournal().first().manualLatest.single { it.legacyOriginId == "workout-legacy-ui" }
        assertNull(legacyCurrent.utc); assertEquals("0", legacyCurrent.durationMinutes)
        assertEquals(legacyOriginal, db.foodDao().legacyTrainingEvents().single())
        tap("workout_history_${legacyCurrent.id}"); waitTag("workout_legacy_original_workout-legacy-ui")
        compose.onNodeWithTag("workout_legacy_original_workout-legacy-ui").performScrollTo().assertTextContains("Früheres Training", substring = true)
        compose.onNodeWithTag("workout_delete").performScrollTo().assertIsNotEnabled()
        tap("workout_delete_confirm"); tap("workout_delete"); waitTag("workout_empty")
        assertTrue(db.manualWorkoutDao().versions().isEmpty()); assertTrue(db.manualWorkoutDao().sets().isEmpty())
        assertEquals("REDACTED", store.manualWorkoutRun(first.runId)!!.kind)
        compose.onNodeWithTag("workout_sport").assertDoesNotExist()
        assertEquals(body, db.bodyDao().versions().single().id)
        assertEquals(foodBefore, store.operations().first()); assertEquals(authBefore, db.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE))
        assertEquals(healthBefore, db.healthDao().history(FitnessHealthPolicy.SAMSUNG_PACKAGE))
    }
}
