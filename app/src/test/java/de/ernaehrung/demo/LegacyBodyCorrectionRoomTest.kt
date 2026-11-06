package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyBodyCorrectionRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { Instant.parse("2026-11-05T12:00:00Z").toEpochMilli() }
    }
    @After fun close() { db.close() }
    private fun legacy() {
        db.openHelper.writableDatabase.execSQL("""INSERT INTO body_measurements(id,measuredAt,weightKg,waistCm,bodyFatPercent,muscleKg,
            waterPercent,device,method,conditions,source,quality,excludedFromTrend,recordedAt,correctsId)
            VALUES('legacy',1,73.04,NULL,0,32.0,0,'Frühere Testwaage','BIA','Originalbedingungen','USER','SOURCED',0,1,NULL)""")
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected legacy body correction") } catch (_: IllegalArgumentException) { }
    }
    @Test fun independentLegacyFieldsPreserveEveryOriginalAndEnforceMetricUnitAndSingleClaim() = runBlocking {
        legacy(); val original = db.bodyDao().legacy().single()
        val weight = BodyMeasurementInput(BodyMetric.WEIGHT, "73,1400", "2026-10-25", correctionReason = "Datum und Gewicht geprüft")
        store.beginBodyMeasurement("weight")
        val id = store.saveBodyMeasurement("weight", weight, null, true, "legacy", LegacyBodyField.WEIGHT)
        assertEquals("legacy", db.bodyDao().version(id)!!.legacyOriginId)
        assertEquals("WEIGHT", db.bodyDao().version(id)!!.legacyField); assertNull(db.bodyDao().version(id)!!.utc)
        assertEquals(id, store.saveBodyMeasurement("weight", weight, null, true, "legacy", LegacyBodyField.WEIGHT))
        val waist = weight.copy(metric = BodyMetric.WAIST, rawValue = "85,0")
        store.saveBodyMeasurement("waist", waist, null, true, "legacy", LegacyBodyField.WAIST)
        val water = weight.copy(metric = BodyMetric.OTHER, rawValue = "0,000", name = "Körperwasseranteil", customUnit = "%")
        store.saveBodyMeasurement("water", water, null, true, "legacy", LegacyBodyField.WATER)
        assertEquals(original, db.bodyDao().legacy().single())
        assertEquals(3, db.bodyDao().versions().size)
        assertEquals("0", db.bodyDao().versions().single { it.legacyField == "WATER" }.decimalValue)
        rejected { store.saveBodyMeasurement("duplicate", weight, null, true, "legacy", LegacyBodyField.WEIGHT) }
        rejected { store.saveBodyMeasurement("wrong-unit", weight, null, true, "legacy", LegacyBodyField.BODY_FAT) }
        assertEquals(3, db.bodyDao().versions().size); assertEquals(0, db.foodDao().operationCount())
        store.purgeBodyMeasurements("purge", true)
        assertTrue(db.bodyDao().legacy().isEmpty()); assertTrue(db.bodyDao().versions().isEmpty())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
    @Test fun anAlreadySupersededLegacyOriginalAndConcurrentFieldClaimsCannotOverwriteHistory() = runBlocking {
        legacy()
        db.openHelper.writableDatabase.execSQL("""INSERT INTO body_measurements(id,measuredAt,weightKg,method,source,quality,
            excludedFromTrend,recordedAt,correctsId) VALUES('legacy-child',2,74,'Unbekannt','USER','UNKNOWN',0,2,'legacy')""")
        val input = BodyMeasurementInput(BodyMetric.WEIGHT, "75", "2026-10-25", correctionReason = "Früheren Fehler geprüft")
        rejected { store.saveBodyMeasurement("ancestor", input, null, true, "legacy", LegacyBodyField.WEIGHT) }
        val results = listOf("a", "b").map { key -> async(Dispatchers.IO) {
            runCatching { store.saveBodyMeasurement(key, input, null, true, "legacy-child", LegacyBodyField.WEIGHT) }
        } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess }); assertEquals(1, results.count { it.isFailure })
        assertEquals(2, db.bodyDao().legacy().size); assertEquals(1, db.bodyDao().versions().size)
        assertEquals(0, db.foodDao().operationCount())
    }
    @Test fun bodyIntentPurgeBlocksLateFirstSaveWhileOriginalV1ReplayAndOtherFitnessRemainIntact() = runBlocking {
        store.beginBodyMeasurement("pending")
        val body = BodyMeasurementInput(BodyMetric.WEIGHT, "73,0400", "2026-10-25")
        val id = store.saveBodyMeasurement("old-direct-api", body, null, true)
        val hash = store.bodyMeasurementRun("old-direct-api")!!.fingerprint
        assertEquals("4154720f01a02f1e100d88ecee4248695133e10560738401ef03f352a972537d", hash)
        assertEquals(id, store.saveBodyMeasurement("old-direct-api", body, null, true))
        assertEquals(hash, store.bodyMeasurementRun("old-direct-api")!!.fingerprint)
        store.beginManualWorkout("training")
        val training = store.saveManualWorkout("training", ManualWorkoutInput("Laufen", FitnessTimestampInput("2026-10-25")), null, null, true)
        store.purgeBodyMeasurements("purge", true)
        assertEquals("REDACTED", store.beginBodyMeasurement("pending").kind)
        rejected { store.saveBodyMeasurement("pending", body, null, true) }
        assertTrue(db.bodyDao().versions().isEmpty())
        val fresh = store.saveBodyMeasurement("fresh", body, null, true)
        store.purgeBodyMeasurements("purge", true)
        assertEquals(fresh, db.bodyDao().versions().single().id)
        assertEquals(training, db.manualWorkoutDao().versions().single().id); assertEquals(0, db.foodDao().operationCount())
    }
}
