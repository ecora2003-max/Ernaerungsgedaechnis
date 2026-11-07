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
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual offline manual form, corrections and SQLite. Synthetic HC cache is
 * only a privacy/overlap fixture, never evidence of physical Watch import.
 */
@RunWith(AndroidJUnit4::class)
class ManualWorkoutOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun finishEditing() = finishDeviceTextEditing(compose)
    private fun tap(tag: String) {
        finishEditing(); waitTag(tag)
        compose.waitUntil(25_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false)
        }
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun field(tag: String, value: String) {
        waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value); finishEditing()
    }
    @Test fun offlineActualManualTrainingCorrectionRecreationAndPrivacyPurgePreserveBodyFoodAndImportedWorkout() = runBlocking {
        assertTrue("Synthetic cleanup requires a disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Require actual network absence", connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
        assertTrue(db.manualWorkoutDao().versions().isEmpty()); assertTrue(db.foodDao().legacyTrainingEvents().isEmpty())
        assertTrue(db.bodyDao().versions().isEmpty()); assertTrue(db.bodyDao().legacy().isEmpty())
        assertTrue(db.healthDao().history(origin).isEmpty())
        val store = FoodStore(db); val token = "synthetic-manual-${UUID.randomUUID()}"
        val foodBefore = store.operations().first(); val batchesBefore = db.foodDao().planningBatches()
        val mealsBefore = db.foodDao().activeConsumptionCount()
        try {
            db.openHelper.writableDatabase.execSQL("INSERT INTO training_events(id,occurredAt,sport,source,quality,recordedAt,note,estimatedKcal) VALUES(?,1,'Früheres Training','USER','UNKNOWN',1,'Unveränderte Originalnotiz',0.0)", arrayOf("$token-legacy"))
            db.openHelper.writableDatabase.execSQL("INSERT INTO training_sets(id,trainingId,exercise,position,repetitions) VALUES(?,?,'Ursprünglicher Satz',0,0)", arrayOf("$token-legacy-set", "$token-legacy"))
            val legacyOriginal = db.foodDao().legacyTrainingEvents().single()
            val body = store.saveBodyMeasurement("$token-body", BodyMeasurementInput(BodyMetric.WEIGHT, "73", "2026-10-25"), null, true)
            val at = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
            val record = FitnessSourceRecord("$token-cache", FitnessRecordKind.WORKOUT, origin,
                Instant.parse("2026-10-25T00:35:00Z").toEpochMilli(), Instant.parse("2026-10-25T00:50:00Z").toEpochMilli(),
                null, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0,
                exerciseType = 0, title = "Synthetic cached workout")
            val provider = object : FitnessHealthSource {
                override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
                override fun availability() = FitnessHealthSource.Availability.AVAILABLE
                override suspend fun grantedPermissions() = requiredPermissions
                override suspend fun readSnapshot(): FitnessHealthSnapshot {
                    val zone = ZoneId.of("Europe/Berlin"); val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                    return FitnessHealthSnapshot(origin, at - Duration.ofDays(28).toMillis(), at, at, listOf(record),
                        day.toString(), zone.id, day.atStartOfDay(zone).toInstant().toEpochMilli(), at, null)
                }
            }
            store.enableFitnessImport("$token-enable", true, true); store.syncFitness("$token-cache-sync", provider)
            store.enableFitnessImport("$token-stop", false, true)
            val authBefore = db.healthDao().state(origin); val healthBefore = db.healthDao().history(origin)
            tap("workspace_tab_MORE"); tap("workspace_go_TRAINING"); tap("workout_new")
            waitTag("workout_save"); compose.onNodeWithTag("workout_save").assertIsNotEnabled()
            field("workout_sport", "Synthetisches Krafttraining")
            field("workout_date", "2026-10-25"); field("workout_time", "02:30")
            field("workout_zone", "Europe/Berlin"); field("workout_offset", "+02:00")
            field("workout_duration", "45,5000"); field("workout_distance", "0,000")
            tap("workout_add_set"); field("workout_set_0_exercise", "Kniebeuge")
            field("workout_set_0_reps", "008"); field("workout_set_0_load", "40,0000")
            waitTag("workout_preview_overlap")
            tap("workout_confirm"); compose.onNodeWithTag("workout_confirm").assertIsOn()
            field("workout_duration", "45,6000")
            compose.onNodeWithTag("workout_confirm").performScrollTo().assertIsOff()
            compose.onNodeWithTag("workout_save").assertIsNotEnabled(); assertTrue(db.manualWorkoutDao().versions().isEmpty())
            tap("workout_confirm"); tap("workout_save"); waitTag("workout_saved")
            val first = db.manualWorkoutDao().versions().single()
            assertEquals("45,6000", first.rawDurationMinutes); assertEquals("2026-10-25T00:30:00Z", first.utc)
            assertEquals("0", first.distanceMetres); assertEquals("008", db.manualWorkoutDao().sets().single().rawRepetitions)
            compose.activityRule.scenario.recreate(); waitTag("workout_saved")
            assertEquals(1, db.manualWorkoutDao().versions().size)
            tap("workout_new"); field("workout_sport", "Nicht gespeicherter privater Entwurf")
            tap("workout_confirm"); compose.activityRule.scenario.recreate(); waitTag("workout_sport")
            compose.onNodeWithTag("workout_sport").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("workout_confirm").performScrollTo().assertIsOff()
            tap("workout_correct_${first.id}"); field("workout_duration", "46,2500")
            field("workout_reason", "Synthetische Dauer korrigiert")
            tap("workout_confirm"); tap("workout_save"); waitTag("workout_saved")
            val current = store.trainingJournal().first().manualLatest.single()
            assertEquals(first.id, current.previousId); assertEquals(2L, current.revision)
            tap("workout_history_${current.id}"); waitTag("workout_version_${first.id}")
            compose.onNodeWithTag("workout_version_${first.id}").performScrollTo().assertTextContains("45,6000", substring = true)
            val reopened = Room.databaseBuilder(target, FoodDatabase::class.java, FoodDatabase.TEST_DATABASE_NAME)
                .addMigrations(FoodMigrations.MIGRATION_10_11, FoodMigrations.MIGRATION_11_12, FoodMigrations.MIGRATION_12_13, FoodMigrations.MIGRATION_13_14, FoodMigrations.MIGRATION_14_15, FoodMigrations.MIGRATION_15_16).build()
            try {
                assertEquals(16, reopened.openHelper.readableDatabase.version)
                val sets = reopened.manualWorkoutDao().sets()
                assertEquals(first.id, FoodStore(reopened).saveManualWorkout(first.runId, first.input(sets, true), null, null, true))
                assertEquals(2, reopened.manualWorkoutDao().versions().size)
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { reopened.close() }
            tap("workout_correct_legacy_$token-legacy"); waitTag("workout_date")
            compose.onNodeWithTag("workout_date").performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("workout_save").assertIsNotEnabled()
            field("workout_date", "2026-10-25"); field("workout_duration", "0,000")
            field("workout_reason", "Tagesbezug selbst geprüft; Ortszeit unbekannt")
            tap("workout_confirm"); tap("workout_save"); waitTag("workout_saved")
            val legacyCurrent = store.trainingJournal().first().manualLatest.single { it.legacyOriginId == "$token-legacy" }
            assertNull(legacyCurrent.utc); assertEquals("0", legacyCurrent.durationMinutes)
            assertEquals(legacyOriginal, db.foodDao().legacyTrainingEvents().single())
            tap("workout_history_${legacyCurrent.id}"); waitTag("workout_legacy_original_$token-legacy")
            compose.onNodeWithTag("workout_legacy_original_$token-legacy").performScrollTo().assertTextContains("Früheres Training", substring = true)
            compose.onNodeWithTag("workout_delete").performScrollTo().assertIsNotEnabled()
            tap("workout_delete_confirm"); tap("workout_delete"); waitTag("workout_empty")
            assertTrue(db.manualWorkoutDao().versions().isEmpty()); assertTrue(db.manualWorkoutDao().sets().isEmpty())
            assertEquals("REDACTED", store.manualWorkoutRun(first.runId)!!.kind)
            compose.onNodeWithTag("workout_sport").assertDoesNotExist()
            assertEquals(body, db.bodyDao().versions().single().id)
            assertEquals(healthBefore, db.healthDao().history(origin)); assertEquals(authBefore, db.healthDao().state(origin))
            assertEquals(foodBefore, store.operations().first()); assertEquals(batchesBefore, db.foodDao().planningBatches())
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount()); assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("manual-workout")
            throw failure
        } finally {
            store.purgeManualWorkouts("$token-cleanup", true)
            store.purgeLocalFitnessImport("$token-health-cleanup", true)
            store.purgeBodyMeasurements("$token-body-cleanup", true)
        }
    }
}
