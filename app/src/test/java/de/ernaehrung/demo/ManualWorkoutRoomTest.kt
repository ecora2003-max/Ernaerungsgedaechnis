package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualWorkoutRoomTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val at = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var name: String
    @Before fun setup() { name = "workout-${UUID.randomUUID()}.db"; reopen() }
    private fun reopen() { db = Room.databaseBuilder(context, FoodDatabase::class.java, name)
        .addMigrations(FoodMigrations.MIGRATION_10_11).build(); store = FoodStore(db) { at } }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun input() = ManualWorkoutInput("Krafttraining", FitnessTimestampInput("2026-10-25", "02:30", "Europe/Berlin", "+02:00"),
        "45,5000", "0,000", effort = "7 / 10 selbst bewertet", sets = listOf(ManualWorkoutSetInput("Kniebeuge", "008", "40,0000"), ManualWorkoutSetInput("Unbekannter Satz")))
    private suspend fun save(key: String, input: ManualWorkoutInput = input(), previous: String? = null, legacy: String? = null): String {
        store.beginManualWorkout(key)
        return store.saveManualWorkout(key, input, previous, legacy, true)
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected training operation") } catch (_: IllegalArgumentException) { }
    }
    @Test fun explicitIntentAndApprovalCommitOneExactVersionAndRetrySurvivesSqliteReopen() = runBlocking {
        val i = input(); store.beginManualWorkout("save")
        rejected { store.saveManualWorkout("save", i, null, null, false) }
        assertTrue(db.manualWorkoutDao().versions().isEmpty()); assertEquals("OPEN", store.manualWorkoutRun("save")!!.kind)
        val id = store.saveManualWorkout("save", i, null, null, true)
        val row = db.manualWorkoutDao().version(id)!!; val sets = db.manualWorkoutDao().sets()
        assertEquals("45,5000", row.rawDurationMinutes); assertEquals("45.5", row.durationMinutes)
        assertEquals("2026-10-25T01:15:30Z", row.endUtc); assertEquals("0", row.distanceMetres)
        assertEquals("008", sets.first().rawRepetitions); assertEquals(8L, sets.first().repetitions)
        db.close(); reopen()
        assertEquals(id, store.saveManualWorkout("save", row.input(sets, true), null, null, true))
        assertEquals(listOf(row), db.manualWorkoutDao().versions()); assertEquals(sets, db.manualWorkoutDao().sets())
        assertEquals(0, db.foodDao().operationCount())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
    @Test fun concurrentCorrectionsUseTheCurrentPredecessorAndPreserveOriginalAndReplay() = runBlocking {
        val first = save("first"); val i = input().copy(durationMinutes = "46", correctionReason = "Dauer korrigiert")
        store.beginManualWorkout("a"); store.beginManualWorkout("b")
        val results = listOf("a", "b").map { key -> async(Dispatchers.IO) { runCatching { store.saveManualWorkout(key, i, first, null, true) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess }); assertEquals(1, results.count { it.isFailure })
        val rows = db.manualWorkoutDao().versions(); assertEquals(2, rows.size)
        assertEquals("45,5000", db.manualWorkoutDao().version(first)!!.rawDurationMinutes)
        val latest = rows.maxBy { it.revision }; assertEquals(first, latest.previousId); assertEquals(2L, latest.revision)
        assertEquals(first, store.saveManualWorkout("first", input(), null, null, true))
        store.beginManualWorkout("stale")
        rejected { store.saveManualWorkout("stale", i, first, null, true) }
        assertEquals("OPEN", store.manualWorkoutRun("stale")!!.kind); assertEquals(2, db.manualWorkoutDao().versions().size)
    }
    @Test fun legacyCorrectionRetainsEveryOriginalAndCannotBeClaimedByTwoSeries() = runBlocking {
        db.openHelper.writableDatabase.execSQL("INSERT INTO training_events(id,occurredAt,sport,source,quality,recordedAt,note) VALUES('legacy',1,'Früher','USER','UNKNOWN',1,'Originalnotiz')")
        db.openHelper.writableDatabase.execSQL("INSERT INTO training_sets(id,trainingId,exercise,position,repetitions,loadKg) VALUES('legacy-set','legacy','Kniebeuge',0,8,40.0)")
        val old = db.foodDao().legacyTrainingEvents(); val oldSets = db.foodDao().legacyTrainingSets()
        val i = input().copy(correctionReason = "Ursprüngliches Datum und Dauer geprüft")
        val corrected = save("legacy-correction", i, legacy = "legacy")
        assertEquals("legacy", db.manualWorkoutDao().version(corrected)!!.legacyOriginId)
        assertEquals(old, db.foodDao().legacyTrainingEvents()); assertEquals(oldSets, db.foodDao().legacyTrainingSets())
        rejected { save("other-series", i, legacy = "legacy") }
        val next = save("next", i.copy(sport = "Korrigierte Sportart"), corrected)
        val snapshot = store.trainingJournal().first()
        assertEquals(setOf("legacy"), snapshot.correctedLegacyIds); assertEquals(next, snapshot.manualLatest.single().id)
        assertEquals(old, snapshot.legacy); assertEquals(oldSets, snapshot.sets)
    }
    @Test fun privacyPurgeRetiresEvenUncommittedIntentsAndOldPurgeCannotEraseLaterData() = runBlocking {
        val body = store.saveBodyMeasurement("body", BodyMeasurementInput(BodyMetric.WEIGHT, "73", "2026-10-25"), null, true)
        db.openHelper.writableDatabase.execSQL("INSERT INTO training_events(id,occurredAt,sport,source,quality,recordedAt) VALUES('legacy-purge',1,'Alt','USER','UNKNOWN',1)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO training_sets(id,trainingId,exercise,position) VALUES('legacy-purge-set','legacy-purge','Alt',0)")
        store.beginManualWorkout("not-yet-saved"); save("saved", input().copy(correctionReason = "Original geprüft"), legacy = "legacy-purge")
        store.purgeManualWorkouts("purge-one", true)
        assertTrue(db.manualWorkoutDao().versions().isEmpty()); assertTrue(db.manualWorkoutDao().sets().isEmpty())
        assertTrue(db.foodDao().legacyTrainingEvents().isEmpty()); assertTrue(db.foodDao().legacyTrainingSets().isEmpty())
        for (key in listOf("saved", "not-yet-saved")) {
            val run = store.manualWorkoutRun(key)!!
            assertEquals("REDACTED", run.kind); assertEquals("", run.fingerprint); assertNull(run.resultId); assertEquals(0L, run.recordedAt)
            store.beginManualWorkout(key)
            rejected { store.saveManualWorkout(key, input(), null, null, true) }
        }
        val fresh = save("fresh"); store.purgeManualWorkouts("purge-one", true)
        assertEquals(fresh, db.manualWorkoutDao().versions().single().id)
        store.purgeManualWorkouts("purge-two", true); val newest = save("newest")
        store.purgeManualWorkouts("purge-one", true); store.purgeManualWorkouts("purge-two", true)
        assertEquals(newest, db.manualWorkoutDao().versions().single().id)
        assertEquals(body, db.bodyDao().versions().single().id); assertEquals(0, db.foodDao().operationCount())
    }
    @Test fun invalidSetsOrChangedRetryNeverPartiallyWriteAndAnUnopenedKeyCannotBook() = runBlocking {
        rejected { store.saveManualWorkout("never-opened", input(), null, null, true) }
        assertNull(store.manualWorkoutRun("never-opened"))
        store.beginManualWorkout("bad")
        rejected { store.saveManualWorkout("bad", input().copy(sets = input().sets + ManualWorkoutSetInput("")), null, null, true) }
        assertEquals("OPEN", store.manualWorkoutRun("bad")!!.kind); assertTrue(db.manualWorkoutDao().versions().isEmpty())
        save("good")
        rejected { store.saveManualWorkout("good", input().copy(sets = input().sets.reversed()), null, null, true) }
        assertEquals(1, db.manualWorkoutDao().versions().size); assertEquals(2, db.manualWorkoutDao().sets().size)
        assertEquals(0, db.foodDao().operationCount())
    }
}
