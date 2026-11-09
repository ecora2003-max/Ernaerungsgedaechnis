package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
class TrainingJournalUiTest {
    @get:Rule(order = 0) val database: org.junit.rules.TestRule = IsolatedWorkspaceDatabase()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.waitForIdle(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) {
        waitTag(tag)
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test fun defaultJournalExposesPreservedLegacySetsAndLatestSourceOverlapsWithoutWritingOrInventingLegacyIntervals() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val at = System.currentTimeMillis() - 3_000L
        val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
        db.openHelper.writableDatabase.execSQL("""INSERT INTO training_events (id,occurredAt,sport,durationMinutes,
            intensity,perceivedExertion,estimatedKcal,energySource,note,source,quality,recordedAt)
            VALUES ('journal-legacy',?,'Synthetisches Krafttraining',NULL,NULL,0,0.0,NULL,'Originalnotiz','USER','ESTIMATED',?)""", arrayOf(at - 86_400_000L, at))
        db.openHelper.writableDatabase.execSQL("""INSERT INTO training_sets(id,trainingId,exercise,position,repetitions,loadKg)
            VALUES ('journal-set-zero','journal-legacy','Zweiter Satz',2,0,0.0),
            ('journal-set-unknown','journal-legacy','Erster Satz',1,NULL,NULL)""")
        fun workout(id: String, start: Long, end: Long) = FitnessSourceRecord(id, FitnessRecordKind.WORKOUT, origin,
            start, end, 7200, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0,
            exerciseType = 0, title = "Synthetisches Training $id", notes = "Lokale synthetische Quellnotiz")
        val a = workout("journal-a", at - 120_000L, at - 60_000L)
        val b = workout("journal-b", at - 90_000L, at - 30_000L)
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
            override suspend fun readWorkoutDuration(query: FitnessWorkoutDurationQuery) = FitnessWorkoutDurationSnapshot(
                sourcePackage = origin, windowStart = query.startAt, windowEnd = query.endAt, capturedAt = System.currentTimeMillis(),
                records = listOf(a), durationMillis = 0, status = FitnessWorkoutDurationStatus.AVAILABLE)
        }
        store.enableFitnessImport("journal-enable", true, true); store.syncFitness("journal-sync", provider)
        store.enableFitnessImport("journal-stop", false, true)
        val foodBefore = store.operations().first()
        val legacyBefore = db.foodDao().legacyTrainingEvents(); val setsBefore = db.foodDao().legacyTrainingSets()
        val authBefore = db.healthDao().state(origin); val healthBefore = db.healthDao().history(origin)
        tap("workspace_tab_MORE"); tap("workspace_go_TRAINING")
        waitTag("training_overlap_journal-a")
        compose.onNodeWithTag("training_overlap_journal-a").performScrollTo().assertTextContains("1 weiterem", substring = true)
        compose.onNodeWithTag("training_legacy_energy_journal-legacy").performScrollTo().assertTextContains("0.0 kcal", substring = true)
        compose.onNodeWithTag("training_set_journal-set-unknown").performScrollTo().assertTextContains("Last Unbekannt", substring = true)
        compose.onNodeWithTag("training_set_journal-set-zero").performScrollTo().assertTextContains("Wiederholungen 0 · Last 0.0 kg", substring = true)
        assertEquals(listOf("journal-set-unknown", "journal-set-zero"), setsBefore.map { it.id })
        compose.activityRule.scenario.recreate(); waitTag("training_overlap_journal-a")
        assertEquals(1, reads); assertEquals(foodBefore, store.operations().first())
        assertEquals(authBefore, db.healthDao().state(origin)); assertEquals(healthBefore, db.healthDao().history(origin))
        records = listOf(a)
        store.enableFitnessImport("journal-enable-again", true, true); store.syncFitness("journal-rescan", provider)
        store.readFitnessWorkoutDuration("journal-duration", provider, db.healthDao().latestWorkout(origin, a.externalId)!!.durationTarget())
        store.enableFitnessImport("journal-stop-again", false, true)
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("fitness_workout_duration_journal-a") and hasText("Trainingszeit im Quellzeitfenster laut Health Connect: 0 s")).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("fitness_workout_duration_read_journal-a").performScrollTo().assertIsNotEnabled()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("training_overlap_journal-a").fetchSemanticsNodes().isEmpty() }
        waitTag("training_missing_count")
        compose.onNodeWithTag("training_imported_count").performScrollTo().assertTextEquals("1 in der lokalen Kopie sichtbare Trainings")
        assertEquals(legacyBefore, db.foodDao().legacyTrainingEvents()); assertEquals(setsBefore, db.foodDao().legacyTrainingSets())
        assertEquals(foodBefore, store.operations().first()); assertEquals(2, reads)
        tap("training_open_fitness"); tap("fitness_open_training"); waitTag("training_journal")
        assertEquals(2, reads)
        store.purgeLocalFitnessImport("journal-purge", true)
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("training_imported_count") and hasText("0 in der lokalen Kopie sichtbare Trainings")).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("training_imported_journal-a").assertDoesNotExist()
        compose.onNodeWithTag("training_legacy_journal-legacy").assertExists()
        assertTrue(db.healthDao().history(origin).isEmpty())
        assertEquals(legacyBefore, db.foodDao().legacyTrainingEvents()); assertEquals(setsBefore, db.foodDao().legacyTrainingSets())
        assertEquals(foodBefore, store.operations().first())
    }
}
