package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Actual Room/SQLite with a synthetic metric source; never a Watch proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FitnessWorkoutDurationRoomTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val name = "synthetic-duration-${UUID.randomUUID()}.db"
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private var clock = HEALTH_AT + 5_000
    private val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
    private val provider = SyntheticHealthSource()
    private fun open() = Room.databaseBuilder(context, FoodDatabase::class.java, name).build()
    private suspend fun target() = db.healthDao().latest(origin).single { it.kind == "WORKOUT" }.durationTarget()
    private fun response(q: FitnessWorkoutDurationQuery, value: Long? = 51_125,
        records: List<FitnessSourceRecord> = listOf(healthWorkout()),
        status: FitnessWorkoutDurationStatus = if (value == null) FitnessWorkoutDurationStatus.NOT_RETURNED else FitnessWorkoutDurationStatus.AVAILABLE) =
        FitnessWorkoutDurationSnapshot(sourcePackage = origin, windowStart = q.startAt, windowEnd = q.endAt,
            capturedAt = clock, records = records, durationMillis = value, status = status)
    @Before fun setup(): Unit = runBlocking {
        db = open(); store = FoodStore(db) { clock }
        store.enableFitnessImport("enable", true, true); store.syncFitness("sync", provider)
        provider.durationReply = { response(it) }
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private suspend fun reject(block: suspend () -> Unit) { assertTrue(runCatching { block() }.isFailure) }
    private suspend fun read(key: String) = store.readFitnessWorkoutDuration(key, provider, target())

    @Test fun exactMetricSurvivesDatabaseReopenAndRetryWithoutChangingRawFoodOrWindowState(): Unit = runBlocking {
        val raw = db.healthDao().history(origin); val auth = db.healthDao().state(origin)
        val before = store.captureBackup("synthetic", false, true)
        val expected = target(); val first = read("duration")
        assertEquals(51_125L, first.workoutDurationMillis)
        assertEquals(expected.revision, first.workoutRecordRevision)
        db.close(); db = open(); store = FoodStore(db) { clock }
        provider.durationReply = { error("Retry must not reread") }
        assertEquals(first, store.readFitnessWorkoutDuration("duration", provider, expected))
        assertEquals(1, provider.durationReads.get())
        assertEquals(first, store.fitnessCache().first().knownDurations.single())
        assertEquals(raw, db.healthDao().history(origin)); assertEquals(auth, db.healthDao().state(origin))
        assertEquals(before, store.captureBackup("synthetic", false, true))
        reject { store.readFitnessWorkoutDuration("duration", provider, expected.copy(fingerprint = "0".repeat(64))) }
        reject { store.syncFitness("duration", provider) }
    }
    @Test fun missingAndFailedReadsPreserveDatedKnownValueWhileKnownZeroRemainsKnown(): Unit = runBlocking {
        val known = read("known"); clock++
        provider.durationReply = { response(it, null) }
        assertNull(read("absent").workoutDurationMillis)
        assertEquals(known, store.fitnessCache().first().knownDurations.single())
        clock++; provider.durationReply = { error("Private provider message must never persist") }
        assertEquals("READ_FAILED", read("failed").workoutDurationStatus)
        assertEquals(known, store.fitnessCache().first().knownDurations.single())
        assertTrue(db.healthDao().runs(origin).none { it.toString().contains("Private provider") })
        clock++; provider.durationReply = { response(it, 0) }
        assertEquals(0L, read("zero").workoutDurationMillis)
        assertEquals(0L, store.fitnessCache().first().knownDurations.single().workoutDurationMillis)
        // The same clock millisecond must not order random UUIDs as chronology.
        provider.durationReply = { response(it, null) }; val later = read("a-same-millisecond-later")
        assertEquals(later, store.fitnessCache().first().durationAttempts.single())
        assertEquals(0L, store.fitnessCache().first().knownDurations.single().workoutDurationMillis)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.fitnessWorkoutDurationHistory(healthWorkout().externalId).map { it.workoutDurationAttempt })
    }
    @Test fun correctedOrDisappearedRawVersionsNeverInheritAnEarlierMetric(): Unit = runBlocking {
        read("known"); val previous = target(); clock++
        provider.snapshot = healthSnapshot(HEALTH_AT + 1, listOf(healthWorkout().copy(title = "Synthetic correction")))
        store.syncFitness("correct", provider)
        assertTrue(store.fitnessCache().first().knownDurations.isEmpty())
        assertEquals(1, store.fitnessWorkoutDurationHistory(previous.externalId).size)
        reject { store.readFitnessWorkoutDuration("stale-ui", provider, previous) }
        assertNull(db.healthDao().run("stale-ui"))
        provider.snapshot = healthSnapshot(HEALTH_AT + 2, emptyList()); store.syncFitness("missing", provider)
        reject { read("hidden") }
        assertNull(db.healthDao().run("hidden"))
    }
    @Test fun malformedOrAmbiguousProviderResultsNeverCreateAKnownDuration(): Unit = runBlocking {
        val cases = listOf<(FitnessWorkoutDurationQuery) -> FitnessWorkoutDurationSnapshot>(
            { response(it, -1) }, { response(it, 0, emptyList()) },
            { response(it, 0, listOf(healthWorkout(), healthWorkout().copy(externalId = "overlap"))) },
            { response(it).copy(formatVersion = 2) }, { response(it).copy(capturedAt = clock + 1) })
        cases.forEachIndexed { i, reply -> provider.durationReply = { q -> reply(q) }; assertEquals("READ_FAILED", read("bad-$i").workoutDurationStatus) }
        assertTrue(store.fitnessCache().first().knownDurations.isEmpty())
    }
    @Test fun sourceChangesOrMissingSessionsAreExplicitAndDoNotReconcileTheCoreWindow(): Unit = runBlocking {
        val core = db.healthDao().state(origin)
        provider.durationReply = { response(it, null, listOf(healthWorkout().copy(title = "Changed")), FitnessWorkoutDurationStatus.SOURCE_CHANGED) }
        assertEquals("SOURCE_CHANGED", read("changed").workoutDurationStatus)
        provider.durationReply = { response(it, null, emptyList(), FitnessWorkoutDurationStatus.SOURCE_MISSING) }
        assertEquals("SOURCE_MISSING", read("not-found").workoutDurationStatus)
        assertEquals(core, db.healthDao().state(origin)); assertEquals(2, db.healthDao().history(origin).size)
    }
    @Test fun oldSessionAndStoppedImportDoNotReadTheProviderOrRequestExtraPermissions(): Unit = runBlocking {
        clock += FitnessHealthPolicy.MAX_WINDOW_MILLIS
        assertEquals("OUTSIDE_READ_WINDOW", read("old").workoutDurationStatus)
        assertEquals(0, provider.durationReads.get())
        store.enableFitnessImport("stop", false, true)
        reject { read("stopped") }; assertNull(db.healthDao().run("stopped"))
    }
    @Test fun stopDuringReadAndPermissionLossAfterReadRejectLateWrites(): Unit = runBlocking {
        provider.durationReply = { q -> store.enableFitnessImport("stop", false, true); response(q) }
        reject { read("late-stop") }; assertNull(db.healthDao().run("late-stop"))
        store.enableFitnessImport("reenable", true, true)
        provider.durationReply = { q -> provider.permissions = emptySet(); response(q) }
        reject { read("revoked") }; assertNull(db.healthDao().run("revoked"))
        assertFalse(db.healthDao().state(origin)!!.enabled)
        assertEquals("PERMISSION_MISSING", db.healthDao().state(origin)!!.lastErrorCode)
    }
    @Test fun rawCorrectionDuringReadRejectsTheStaleResult(): Unit = runBlocking {
        provider.durationReply = { q ->
            provider.snapshot = healthSnapshot(HEALTH_AT + 1, listOf(healthWorkout().copy(notes = "Corrected during read")))
            store.syncFitness("concurrent-raw", provider); response(q)
        }
        reject { read("late-raw") }; assertNull(db.healthDao().run("late-raw"))
        assertTrue(store.fitnessCache().first().knownDurations.isEmpty())
    }
    @Test fun purgeRedactsEveryMetricFieldAndRejectsRetiredRetryWithoutResurrection(): Unit = runBlocking {
        val savedTarget = target(); read("known")
        provider.durationReply = { q -> store.purgeLocalFitnessImport("purge", true); response(q) }
        reject { read("late-purge") }; assertNull(db.healthDao().run("late-purge"))
        assertTrue(store.fitnessCache().first().durationAttempts.isEmpty())
        assertTrue(store.fitnessWorkoutDurationHistory(savedTarget.externalId).isEmpty())
        db.healthDao().run("known")!!.let {
            assertEquals("REDACTED", it.kind); assertEquals("", it.fingerprint)
            assertNull(it.workoutExternalId); assertNull(it.workoutRecordRevision); assertNull(it.workoutRecordFingerprint)
            assertNull(it.workoutDurationMillis); assertNull(it.workoutDurationStatus); assertNull(it.capturedAt)
            assertNull(it.workoutDurationAttempt)
        }
        reject { store.readFitnessWorkoutDuration("known", provider, savedTarget) }
        assertTrue(db.healthDao().history(origin).isEmpty())
    }
    @Test fun cancellationAndExtraPermissionsNeverBecomeSuccessfulOrFailedMetricWrites(): Unit = runBlocking {
        provider.durationReply = { throw CancellationException() }
        reject { read("cancelled") }; assertNull(db.healthDao().run("cancelled"))
        val wider = object : FitnessHealthSource by provider {
            override val requiredPermissions = provider.requiredPermissions + "android.permission.health.READ_HEART_RATE"
        }
        reject { store.readFitnessWorkoutDuration("wider", wider, target()) }
        assertNull(db.healthDao().run("wider"))
    }
}
