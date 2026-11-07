package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Actual offline journal with published legacy rows and a synthetic private
 * source cache. This is a reader test, never evidence of a physical Watch.
 */
@RunWith(AndroidJUnit4::class)
class TrainingJournalOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) {
        waitTag(tag)
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test fun offlineJournalReopensLegacySetsAndVersionedSourceOverlapsWithoutFoodWritesOrAnotherImport() = runBlocking {
        assertTrue("Synthetic cleanup requires a disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Require actual network absence, not just disabled radios", connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
        assertTrue("Never replace an existing source cache", db.healthDao().history(origin).isEmpty())
        val store = FoodStore(db)
        val token = "synthetic-training-${UUID.randomUUID()}"
        val at = System.currentTimeMillis() - 3_000L
        val foodBefore = store.operations().first()
        val batchesBefore = db.foodDao().planningBatches()
        val mealsBefore = db.foodDao().activeConsumptionCount()
        try {
            // Fixture of the already published legacy schema, without adding a
            // production write bypass or claiming a new manual editor exists.
            db.openHelper.writableDatabase.execSQL("""INSERT INTO training_events(id,occurredAt,sport,durationMinutes,
                intensity,perceivedExertion,estimatedKcal,energySource,note,source,quality,recordedAt)
                VALUES (?,?,'Synthetisches Krafttraining',NULL,NULL,0,0.0,NULL,'Originalnotiz','USER','ESTIMATED',?)""",
                arrayOf(token, at - 86_400_000L, at))
            db.openHelper.writableDatabase.execSQL("""INSERT INTO training_sets(id,trainingId,exercise,position,repetitions,loadKg)
                VALUES (?,?,'Zweiter Satz',2,0,0.0),(?,?,'Erster Satz',1,NULL,NULL)""",
                arrayOf("$token-zero", token, "$token-unknown", token))
            fun workout(id: String, start: Long, end: Long) = FitnessSourceRecord(id, FitnessRecordKind.WORKOUT, origin,
                start, end, 7200, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0,
                exerciseType = 0, title = "Synthetisches Training", notes = "Lokale synthetische Quellnotiz")
            val a = workout("$token-a", at - 120_000L, at - 60_000L)
            val b = workout("$token-b", at - 90_000L, at - 30_000L)
            var records = listOf(a, b)
            var reads = 0
            val provider = object : FitnessHealthSource {
                override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
                override fun availability() = FitnessHealthSource.Availability.AVAILABLE
                override suspend fun grantedPermissions() = requiredPermissions
                override suspend fun readSnapshot(): FitnessHealthSnapshot {
                    reads++
                    val zone = ZoneId.of("Europe/Berlin"); val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                    return FitnessHealthSnapshot(origin, at - Duration.ofDays(28).toMillis(), at, at + reads - 1L, records,
                        day.toString(), zone.id, day.atStartOfDay(zone).toInstant().toEpochMilli(), at, null)
                }
            }
            store.enableFitnessImport("$token-enable", true, true); store.syncFitness("$token-sync", provider)
            store.enableFitnessImport("$token-stop", false, true)
            val legacyBefore = db.foodDao().legacyTrainingEvents(); val setsBefore = db.foodDao().legacyTrainingSets()
            val authBefore = db.healthDao().state(origin); val healthBefore = db.healthDao().history(origin)
            tap("workspace_tab_MORE"); tap("workspace_go_TRAINING"); waitTag("training_overlap_$token-a")
            compose.onNodeWithTag("training_overlap_$token-a").performScrollTo().assertTextContains("1 weiterem", substring = true)
            compose.onNodeWithTag("training_legacy_energy_$token").performScrollTo().assertTextContains("0.0 kcal", substring = true)
            compose.onNodeWithTag("training_set_$token-unknown").performScrollTo().assertTextContains("Last Unbekannt", substring = true)
            compose.onNodeWithTag("training_set_$token-zero").performScrollTo().assertTextContains("Wiederholungen 0 · Last 0.0 kg", substring = true)
            compose.activityRule.scenario.recreate(); waitTag("training_overlap_$token-a")
            assertEquals(1, reads); assertEquals(authBefore, db.healthDao().state(origin))
            assertEquals(healthBefore, db.healthDao().history(origin))
            records = listOf(a)
            store.enableFitnessImport("$token-enable-again", true, true); store.syncFitness("$token-rescan", provider)
            store.enableFitnessImport("$token-stop-again", false, true)
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("training_overlap_$token-a").fetchSemanticsNodes().isEmpty() }
            waitTag("training_missing_count")
            compose.onNodeWithTag("training_imported_count").performScrollTo().assertTextEquals("1 in der lokalen Kopie sichtbare Trainings")
            val reopened = Room.databaseBuilder(target, FoodDatabase::class.java, FoodDatabase.TEST_DATABASE_NAME)
                .addMigrations(FoodMigrations.MIGRATION_9_10, FoodMigrations.MIGRATION_10_11, FoodMigrations.MIGRATION_11_12, FoodMigrations.MIGRATION_12_13, FoodMigrations.MIGRATION_13_14, FoodMigrations.MIGRATION_14_15, FoodMigrations.MIGRATION_15_16, FoodMigrations.MIGRATION_16_17).build()
            try {
                val reread = FoodStore(reopened).trainingJournal().first()
                assertEquals(legacyBefore, reread.legacy); assertEquals(setsBefore, reread.sets)
                assertEquals(1, reread.imported.count { it.visible }); assertEquals(1, reread.imported.count { !it.visible })
            } finally { reopened.close() }
            tap("training_open_fitness"); tap("fitness_open_training"); waitTag("training_journal")
            assertEquals(2, reads); assertEquals(foodBefore, store.operations().first())
            assertEquals(batchesBefore, db.foodDao().planningBatches()); assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            assertEquals(legacyBefore, db.foodDao().legacyTrainingEvents()); assertEquals(setsBefore, db.foodDao().legacyTrainingSets())
            store.purgeLocalFitnessImport("$token-purge", true)
            compose.waitUntil(25_000) {
                compose.onAllNodes(hasTestTag("training_imported_count") and hasText("0 in der lokalen Kopie sichtbare Trainings")).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("training_imported_$token-a").assertDoesNotExist()
            compose.onNodeWithTag("training_legacy_$token").assertExists()
            assertTrue(db.healthDao().history(origin).isEmpty())
            assertEquals(legacyBefore, db.foodDao().legacyTrainingEvents()); assertEquals(setsBefore, db.foodDao().legacyTrainingSets())
            assertEquals(foodBefore, store.operations().first())
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("training-journal")
            throw failure
        } finally {
            store.purgeLocalFitnessImport("$token-cleanup", true)
            db.openHelper.writableDatabase.execSQL("DELETE FROM training_sets WHERE trainingId=?", arrayOf(token))
            db.openHelper.writableDatabase.execSQL("DELETE FROM training_events WHERE id=?", arrayOf(token))
        }
    }
}
