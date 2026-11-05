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
class BodyMeasurementRoomTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val at = Instant.parse("2026-11-05T14:00:00Z").toEpochMilli()
    private fun open() = Room.databaseBuilder(context, FoodDatabase::class.java, name)
        .addMigrations(FoodMigrations.MIGRATION_9_10).build()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); name = "synthetic-body-${UUID.randomUUID()}.db"
        db = open(); store = FoodStore(db) { at }
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun input(value: String? = "073,0400") = BodyMeasurementInput(BodyMetric.WEIGHT, value, "2026-10-25",
        "02:30", "Europe/Berlin", "+02:00", device = "Testwaage", model = "Synthetisch",
        method = BodyMethod.SCALE, conditions = "Morgens, vor Frühstück", quality = BodyQuality.MEASURED)
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected body write rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun confirmedVersionsAndOriginalRetrySurviveDiskReopenAndNeverTouchFoodOrHealth() = runBlocking {
        val food = store.rememberProduct("food", ProductInput("Testtofu", energyKcal = 0.0))
        val loc = store.addLocation("loc", "Vorrat", true)
        val batch = store.receive("stock", BatchInput(food, loc, 100_000L, "2026-10-25", release = ReleaseStatus.RELEASED), true)
        val foodBefore = db.foodDao().operationCount()
        val nutritionBefore = store.dayNutrition("2026-10-25").snapshot
        rejected { store.saveBodyMeasurement("save", input(), null, false) }
        assertNull(db.bodyDao().run("save")); assertTrue(db.bodyDao().versions().isEmpty())
        val first = store.saveBodyMeasurement("save", input(), null, true)
        val correction = input("73,14").copy(correctionReason = "Übertragungsfehler korrigiert")
        val second = store.saveBodyMeasurement("correct", correction, first, true)
        assertEquals(first, store.saveBodyMeasurement("save", input(), null, true))
        assertEquals(second, store.saveBodyMeasurement("correct", correction, first, true))
        val history = store.bodyMeasurementHistory().first()
        assertEquals(2, history.versions.size); assertEquals(second, history.latest.single().id)
        val original = history.versions.single { it.id == first }
        assertEquals("073,0400", original.rawValue); assertEquals("73.04", original.decimalValue)
        assertEquals("2026-10-25T00:30:00Z", original.utc); assertEquals(7200, original.offsetSeconds)
        assertEquals("Morgens, vor Frühstück", original.conditions)
        db.close(); db = open(); store = FoodStore(db) { at }
        assertEquals(second, store.bodyMeasurementHistory().first().latest.single().id)
        assertEquals(first, store.saveBodyMeasurement("save", input(), null, true))
        rejected { store.saveBodyMeasurement("late-correction", correction, first, true) }
        rejected { store.saveBodyMeasurement("correct", correction.copy(rawValue = "80"), first, true) }
        assertNull(db.bodyDao().run("late-correction"))
        assertEquals(foodBefore, db.foodDao().operationCount()); assertEquals(100_000L, db.foodDao().balance(batch))
        assertEquals(nutritionBefore, store.dayNutrition("2026-10-25").snapshot)
        assertTrue(db.healthDao().history(FitnessHealthPolicy.SAMSUNG_PACKAGE).isEmpty())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
    @Test fun unknownAndKnownZeroAreSeparateMeasurementsAndInvalidCorrectionsRollBackTheirKeys() = runBlocking {
        val unknown = input(null).copy(localTime = null, zoneId = null, explicitOffset = null)
        val missing = store.saveBodyMeasurement("missing", unknown, null, true)
        val zero = store.saveBodyMeasurement("zero", unknown.copy(rawValue = "0,00"), null, true)
        assertNull(db.bodyDao().version(missing)!!.decimalValue)
        assertNull(db.bodyDao().version(missing)!!.utc)
        assertEquals("0", db.bodyDao().version(zero)!!.decimalValue)
        rejected { store.saveBodyMeasurement("missing-reason", unknown.copy(rawValue = "1"), missing, true) }
        rejected { store.saveBodyMeasurement("wrong-metric", unknown.copy(metric = BodyMetric.WAIST, correctionReason = "Andere Messung"), missing, true) }
        assertNull(db.bodyDao().run("missing-reason")); assertNull(db.bodyDao().run("wrong-metric"))
        assertEquals(2, db.bodyDao().versions().size)
    }
    @Test fun concurrentCorrectionsUseTheExpectedImmutableVersionAtomically() = runBlocking {
        val id = store.saveBodyMeasurement("first", input(), null, true)
        val replies = (1..2).map { n -> async(Dispatchers.IO) {
            runCatching { store.saveBodyMeasurement("race-$n", input("$n").copy(correctionReason = "Testkorrektur $n"), id, true) }
        } }.awaitAll()
        assertEquals(1, replies.count { it.isSuccess }); assertEquals(1, replies.count { it.isFailure })
        assertEquals(2, db.bodyDao().versions().size)
        assertEquals(2L, store.bodyMeasurementHistory().first().latest.single().revision)
    }
    @Test fun biaDeviceEstimateCanReplayFromItsPersistedOriginalAfterRestart() = runBlocking {
        val bia = input("31,200").copy(metric = BodyMetric.MUSCLE_MASS,
            method = BodyMethod.BIA, methodDetail = "Skelettmuskelmasse laut synthetischem Gerät",
            quality = BodyQuality.MEASURED)
        val id = store.saveBodyMeasurement("bia-original", bia, null, true)
        db.close(); db = open(); store = FoodStore(db) { at }
        val persisted = db.bodyDao().version(id)!!
        assertEquals("31,200", persisted.rawValue)
        assertEquals(BodyQuality.DEVICE_ESTIMATE.name, persisted.quality)
        assertEquals(id, store.saveBodyMeasurement("bia-original", persisted.input(), null, true))
        assertEquals(1, db.bodyDao().versions().size)
    }
    @Test fun privacyDeletionRetiresOldKeysAcrossMultiplePurgeCyclesAndProtectsLaterData() = runBlocking {
        val first = store.saveBodyMeasurement("private-first", input(), null, true)
        store.saveBodyMeasurement("private-correction", input("70").copy(correctionReason = "Privater Testgrund"), first, true)
        rejected { store.purgeBodyMeasurements("purge-1", false) }
        assertEquals(2, db.bodyDao().versions().size)
        // Published legacy body rows remain readable until this explicit purge.
        // Seed a real old correction chain to exercise RESTRICT deletion order.
        db.openHelper.writableDatabase.execSQL("""INSERT INTO body_measurements(id,measuredAt,weightKg,method,source,quality,excludedFromTrend,recordedAt)
            VALUES('legacy-root',1,70.0,'SCALE','USER','UNKNOWN',0,1)""")
        db.openHelper.writableDatabase.execSQL("""INSERT INTO body_measurements(id,measuredAt,weightKg,method,source,quality,excludedFromTrend,recordedAt,correctsId)
            VALUES('legacy-correction',1,71.0,'SCALE','USER','UNKNOWN',0,2,'legacy-root')""")
        assertEquals(2, store.bodyMeasurementHistory().first().legacy.size)
        store.purgeBodyMeasurements("purge-1", true)
        assertTrue(db.bodyDao().versions().isEmpty()); assertTrue(db.bodyDao().legacy().isEmpty())
        for (key in listOf("private-first", "private-correction")) {
            val run = db.bodyDao().run(key)!!
            assertEquals("REDACTED", run.kind); assertEquals("", run.fingerprint)
            assertNull(run.resultId); assertEquals(0L, run.recordedAt)
        }
        rejected { store.saveBodyMeasurement("private-first", input(), null, true) }
        store.saveBodyMeasurement("second-cycle", input("72"), null, true)
        store.purgeBodyMeasurements("purge-2", true)
        val newest = store.saveBodyMeasurement("newest", input("71"), null, true)
        store.purgeBodyMeasurements("purge-1", true)
        store.purgeBodyMeasurements("purge-2", true)
        assertEquals(newest, db.bodyDao().versions().single().id)
        rejected { store.saveBodyMeasurement("second-cycle", input("72"), null, true) }
        assertEquals(0, db.foodDao().operationCount())
    }
}
