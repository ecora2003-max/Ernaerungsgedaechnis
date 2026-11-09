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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutSourceWindowRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val source = FitnessHealthPolicy.SAMSUNG_PACKAGE
    private val workout = healthWorkout()
    private var clock = HEALTH_AT + 5_000
    private val types = HealthExtraSelection(WorkoutSourceWindow.TYPES)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { clock }
    }
    @After fun close() { db.close() }
    private fun rows(q: HealthExtraQuery): List<HealthExtraRecord> = listOf(
        syntheticExtraRecord(q, HealthExtraKind.HEART_RATE).copy(sourcePackage = source,
            startAt = workout.startAt - 1_000, endAt = workout.endAt + 1_000,
            samples = listOf(HealthExtraSample(workout.startAt - 1, bpm = 66), HealthExtraSample(workout.startAt, bpm = 71),
                HealthExtraSample(workout.endAt - 1, bpm = 89), HealthExtraSample(workout.endAt, bpm = 99))),
        syntheticExtraRecord(q, HealthExtraKind.DISTANCE).copy(sourcePackage = source, startAt = workout.startAt, endAt = workout.endAt),
        syntheticExtraRecord(q, HealthExtraKind.TOTAL_ENERGY).copy(sourcePackage = source,
            startAt = q.lastDayStart, endAt = q.windowEnd, scalarBits = 2100.5.toRawBits()),
        syntheticExtraRecord(q, HealthExtraKind.ACTIVE_ENERGY).copy(sourcePackage = "test.synthetic.phone"),
    ).filter { it.kind in q.selection.kinds }
    private suspend fun import(): Pair<SyntheticHealthSource, SyntheticExtraSource> {
        val core = SyntheticHealthSource(healthSnapshot(records = listOf(workout)))
        store.enableFitnessImport("core-enable", true, true); store.syncFitness("core-read", core)
        val extra = SyntheticExtraSource().also { p -> p.response = { q -> HealthExtraSnapshot(query = q, capturedAt = q.requestedAt, records = rows(q)) } }
        store.authorizeHealthExtra("extra-enable", types, extra, true); store.syncHealthExtra("extra-read", extra)
        return core to extra
    }
    private suspend fun target() = db.healthDao().latestWorkout(source, workout.externalId)!!.sourceWindowTarget()

    @Test fun sameSourceWindowIsReadOnlyKeepsWiderScalarsUnknownAndRemainsReadableAfterStop(): Unit = runBlocking {
        store.rememberProduct("food", ProductInput("Synthetic food", source = Source.USER, quality = Quality.CONFIRMED))
        val (core, extra) = import()
        val foodOps = db.foodDao().operationCount()
        core.onRead = { error("Local view must not query core source") }
        extra.onRead = { error("Local view must not query extra source") }
        store.authorizeHealthExtra("extra-stop", null, extra, true)
        val snapshot = store.watchWorkoutSourceWindow(target()).first()
        assertEquals(WorkoutSourceWindowStatus.CURRENT, snapshot.status); assertFalse(snapshot.extraState.enabled)
        assertEquals(3, snapshot.selection.matches.size)
        val heart = snapshot.selection.matches.single { it.observation.record.kind == HealthExtraKind.HEART_RATE }
        assertEquals(listOf(71L, 89L), heart.selectedSamples.map { it.bpm })
        assertEquals(4, heart.observation.record.samples.size)
        assertNull(snapshot.selection.matches.single { it.observation.record.kind == HealthExtraKind.TOTAL_ENERGY }.scalarInWindow)
        assertEquals(0L, snapshot.selection.matches.single { it.observation.record.kind == HealthExtraKind.DISTANCE }.scalarInWindow)
        assertEquals(1, core.reads.get()); assertEquals(1, extra.reads)
        assertEquals(foodOps, db.foodDao().operationCount()); assertEquals(0, db.foodDao().activeConsumptionCount())
        assertTrue(db.foodDao().purchases().isEmpty()); assertTrue(db.foodDao().planningBatches().isEmpty())
    }

    @Test fun rawCorrectionsRefreshTheBoundWindowButCorrectedWorkoutNeedsExplicitNewTarget(): Unit = runBlocking {
        val (core, extra) = import(); val selected = target()
        val first = store.watchWorkoutSourceWindow(selected).first()
        clock += 1_000
        extra.response = { q -> HealthExtraSnapshot(query = q, capturedAt = q.requestedAt, records = rows(q).map {
            if (it.kind == HealthExtraKind.DISTANCE) it.copy(scalarBits = 12.75.toRawBits(), clientRecordVersion = 2) else it
        }) }
        val update = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) {
            store.watchWorkoutSourceWindow(selected).first { s -> s.selection.matches.any {
                it.observation.record.kind == HealthExtraKind.DISTANCE && it.observation.revision == 2L
            } }
        } }
        store.syncHealthExtra("extra-corrected", extra)
        val corrected = update.await()
        assertEquals(12.75.toRawBits(), corrected.selection.matches.single { it.observation.record.kind == HealthExtraKind.DISTANCE }.scalarInWindow)
        assertEquals(first.workout, corrected.workout)
        val newWorkout = workout.copy(title = "Synthetische Quellkorrektur", clientRecordVersion = 2)
        core.snapshot = healthSnapshot(HEALTH_AT + 2_000, listOf(newWorkout))
        store.syncFitness("core-corrected", core)
        val stale = store.watchWorkoutSourceWindow(selected).first()
        assertEquals(WorkoutSourceWindowStatus.CHANGED, stale.status); assertTrue(stale.selection.matches.isEmpty())
        val fresh = store.watchWorkoutSourceWindow(target()).first()
        assertEquals(WorkoutSourceWindowStatus.CURRENT, fresh.status); assertEquals(3, fresh.selection.matches.size)
    }

    @Test fun hiddenAndPurgedWorkoutsAndDeletedExtrasImmediatelyRemoveProjectedValues(): Unit = runBlocking {
        val (core, _) = import(); val selected = target()
        store.purgeHealthExtra("extra-purge", true)
        val empty = store.watchWorkoutSourceWindow(selected).first()
        assertEquals(WorkoutSourceWindowStatus.CURRENT, empty.status); assertTrue(empty.selection.matches.isEmpty())
        core.snapshot = healthSnapshot(HEALTH_AT + 1_000, emptyList(), null)
        store.syncFitness("hidden", core)
        val hidden = store.watchWorkoutSourceWindow(selected).first()
        assertEquals(WorkoutSourceWindowStatus.NOT_VISIBLE, hidden.status); assertTrue(hidden.selection.matches.isEmpty())
        store.purgeLocalFitnessImport("global-purge", true)
        val missing = store.watchWorkoutSourceWindow(selected).first()
        assertEquals(WorkoutSourceWindowStatus.MISSING, missing.status); assertNull(missing.workout)
        assertTrue(missing.selection.matches.isEmpty())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
}
