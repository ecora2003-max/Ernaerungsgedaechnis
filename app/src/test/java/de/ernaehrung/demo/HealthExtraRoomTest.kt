package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.backup.*
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import java.time.LocalDate
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
class HealthExtraRoomTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val selection = HealthExtraSelection(HealthExtraKind.entries.toSet())
    private var clock = Instant.parse("2025-04-04T14:00:00Z").toEpochMilli()
    private fun open() = Room.databaseBuilder(context, FoodDatabase::class.java, name)
        .addMigrations(FoodMigrations.MIGRATION_19_20).build()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); name = "synthetic-extra-${UUID.randomUUID()}.db"
        db = open(); store = FoodStore(db) { clock }
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private suspend fun reject(action: suspend () -> Unit) {
        try { action(); fail("Expected rejected extra health action") } catch (_: Exception) { }
    }
    private suspend fun enable(p: SyntheticExtraSource, key: String = "enable", s: HealthExtraSelection = selection) =
        store.authorizeHealthExtra(key, s, p, true)

    @Test fun exactScalarsSeriesUnitsSourcesProviderAggregateAndFoodSurviveRestartAndRetry(): Unit = runBlocking {
        val version = store.rememberProduct("food", ProductInput("Synthetic food", energyKcal = 0.0, source = Source.USER, quality = Quality.CONFIRMED))
        val foodOps = db.foodDao().operationCount()
        val p = SyntheticExtraSource(); enable(p)
        val first = store.syncHealthExtra("read", p)
        val cached = store.healthExtraCache().first()
        assertEquals(7, cached.records.size); assertEquals(0L, first.steps)
        assertEquals(setOf("test.synthetic.phone", "test.synthetic.wearable"),
            (0 until org.json.JSONArray(first.stepSources).length()).map { org.json.JSONArray(first.stepSources).getString(it) }.toSet())
        cached.records.forEach { row ->
            assertEquals(syntheticExtraRecord(p.lastQuery!!, row.record.kind), row.record)
            assertEquals(1L, row.revision); assertTrue(row.visible)
        }
        assertEquals(4, db.healthExtraDao().latestSamples().size)
        assertEquals("W", db.healthExtraDao().latest().single { it.kind == "BASAL_RATE" }.unit)
        assertNull(db.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE)) // Core consent is independent.
        db.close(); db = open(); store = FoodStore(db) { clock }
        assertEquals(cached, store.healthExtraCache().first())
        p.onRead = { error("Retry must not read SDK") }
        assertEquals(first, store.syncHealthExtra("read", p)); assertEquals(1, p.reads)
        assertNotNull(db.foodDao().version(version)); assertEquals(foodOps, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().activeConsumptionCount()); assertTrue(db.foodDao().purchases().isEmpty())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun consentPermissionHistoryAndChangedScopeAreVerifiedAtFoodStoreBoundary(): Unit = runBlocking {
        val p = SyntheticExtraSource()
        store.enableFitnessImport("old-core", true, true)
        reject { store.syncHealthExtra("old-is-not-extra", p) }; assertEquals(0, p.reads)
        reject { store.authorizeHealthExtra("not-confirmed", selection, p, false) }
        val older = selection.copy(windowDays = 90)
        reject { enable(p, "old-without-consent", older) }
        p.history = false
        reject { enable(p, "unsupported-history", older.copy(historyOptIn = true)) }
        p.history = true; p.grants -= HealthExtraKernel.HISTORY_PERMISSION
        reject { enable(p, "missing-history-grant", older.copy(historyOptIn = true)) }
        assertNull(db.healthExtraDao().state())
        p.grants += HealthExtraKernel.HISTORY_PERMISSION
        enable(p, s = older.copy(historyOptIn = true))
        p.onRead = { p.grants = emptySet() }
        reject { store.syncHealthExtra("revoked-after-read", p) }
        assertTrue(db.healthExtraDao().latest().isEmpty()); assertFalse(store.healthExtraCache().first().state.enabled)
        assertEquals("PERMISSION_MISSING", store.healthExtraCache().first().state.lastErrorCode)
        assertTrue(db.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE)!!.enabled)
    }

    @Test fun correctedDeferredMissingAndUnselectedRowsKeepVersionedOriginals(): Unit = runBlocking {
        val p = SyntheticExtraSource(); enable(p)
        val future = clock + 10_000
        p.response = { q -> syntheticExtraSnapshot(q).let { s -> s.copy(records = s.records.map {
            if (it.kind == HealthExtraKind.DISTANCE) it.copy(endAt = future) else it
        }) } }
        store.syncHealthExtra("deferred", p)
        val pending = store.healthExtraCache().first().records.single { it.record.kind == HealthExtraKind.DISTANCE }
        assertFalse(pending.visible); assertEquals(future, pending.record.endAt)
        val originals = store.healthExtraCache().first().records.map { it.record }
        clock += 20_000
        p.response = { q -> syntheticExtraSnapshot(q).let { s -> s.copy(records = originals.map {
            if (it.kind == HealthExtraKind.DISTANCE) pending.record else it
        }) } }
        store.syncHealthExtra("released", p)
        val released = store.healthExtraCache().first().records.single { it.record.kind == HealthExtraKind.DISTANCE }
        assertTrue(released.visible); assertEquals(2L, released.revision); assertEquals(pending.record, released.record)
        val scope = HealthExtraSelection(setOf(HealthExtraKind.BODY_FAT))
        enable(p, "body-only", scope)
        p.response = { q -> syntheticExtraSnapshot(q).copy(records = emptyList()) }
        store.syncHealthExtra("body-missing", p)
        val rows = store.healthExtraCache().first().records
        assertFalse(rows.single { it.record.kind == HealthExtraKind.BODY_FAT }.visible)
        assertEquals(released, rows.single { it.record.kind == HealthExtraKind.DISTANCE })
        clock += 1_000
        p.response = { q -> syntheticExtraSnapshot(q).let { s -> s.copy(records = s.records.map {
            it.copy(scalarBits = (-0.0).toRawBits(), clientRecordVersion = 2)
        }) } }
        store.syncHealthExtra("body-corrected", p)
        val history = store.healthExtraHistory("test.synthetic.scale", "BODY_FAT", "synthetic-BODY_FAT")
        assertEquals(3, history.size); assertEquals((-0.0).toRawBits(), history.last().record.scalarBits)
        assertTrue(history.last().visible)
    }

    @Test fun energyCoverageReadsConfirmedDiaryAndCurrentLocalSourcesAcrossTheShortBerlinDay(): Unit = runBlocking {
        val end = LocalDate.parse("2025-04-04")
        val shortDay = LocalDate.parse("2025-03-30")
        val previousDay = shortDay.minusDays(1)
        val nextDay = shortDay.plusDays(1)
        val shortStart = AppCalendar.startOfDayMillis(shortDay)
        val shortEnd = AppCalendar.startOfDayMillis(nextDay)
        val knownZero = store.rememberProduct("zero-product", ProductInput("Synthetic zero energy", energyKcal = 0.0))
        store.consume("zero-meal", ConsumptionInput(knownZero, previousDay.toString(), "Synthetic meal", 100_000), true)
        store.consume("partial-day", ConsumptionInput(knownZero, shortDay.toString(), "Synthetic meal", 100_000), true)
        for (date in listOf(previousDay, end)) {
            val dateText = date.toString()
            store.setDayStatus("complete-$date", dateText, DayStatus.COMPLETE, store.dayNutrition(dateText).snapshot, true)
        }
        val weight = BodyMeasurementInput(BodyMetric.WEIGHT, "72.5", previousDay.toString(), method = BodyMethod.SCALE)
        val prior = store.saveBodyMeasurement("weight", weight, null, true)
        store.saveBodyMeasurement("corrected-weight", weight.copy(localDate = shortDay.toString(),
            correctionReason = "Correct measurement date"), prior, true)
        store.saveBodyMeasurement("excluded-weight", weight.copy(excludedFromTrend = true), null, true)
        store.saveBodyMeasurement("unknown-weight", weight.copy(rawValue = null), null, true)

        val extra = SyntheticExtraSource()
        val selected = HealthExtraSelection(setOf(HealthExtraKind.ACTIVE_ENERGY, HealthExtraKind.TOTAL_ENERGY))
        extra.response = { q ->
            val active = syntheticExtraRecord(q, HealthExtraKind.ACTIVE_ENERGY)
            val total = syntheticExtraRecord(q, HealthExtraKind.TOTAL_ENERGY)
            HealthExtraSnapshot(query = q, capturedAt = q.requestedAt, records = listOf(
                active.copy(startAt = shortStart - 60_000, endAt = shortStart + 60_000),
                active.copy(externalId = "same-source-second-record", clientRecordId = null,
                    startAt = shortEnd - 60_000, endAt = shortEnd),
                total.copy(startAt = shortEnd, endAt = shortEnd + 60_000)))
        }
        enable(extra, s = selected)
        store.syncHealthExtra("coverage-source", extra)
        val operationsBefore = store.operations().first()
        val coverage = store.watchFitnessEnergyCoverage(end, EnergyCoveragePeriod.DAYS_14)
        val reports = coverage.first()
        assertEquals(List(14) { end.minusDays(it.toLong()) }, reports.map { it.date })
        val byDay = reports.associateBy { it.date }
        assertEquals(listOf(previousDay), reports.filter { it.completeIntake }.map { it.date })
        assertEquals(0, byDay.getValue(previousDay).foodEnergy.knownAmount?.signum())
        assertNull(byDay.getValue(end).foodEnergy.knownAmount)
        assertEquals(DayStatus.COMPLETE, byDay.getValue(end).diaryStatus)
        assertEquals(1, byDay.getValue(shortDay).weightMeasurements)
        assertEquals(0, byDay.getValue(previousDay).weightMeasurements)
        val activeDays = mapOf(previousDay to setOf("test.synthetic.phone"), shortDay to setOf("test.synthetic.phone"))
        assertEquals(activeDays, reports.filter { it.activitySources.isNotEmpty() }.associate { it.date to it.activitySources })

        clock += 1_000
        extra.response = { q -> syntheticExtraSnapshot(q).copy(records = listOf(syntheticExtraRecord(q, HealthExtraKind.TOTAL_ENERGY))) }
        store.syncHealthExtra("coverage-source-removed", extra)
        assertTrue(coverage.first().all { it.activitySources.isEmpty() })
        assertEquals(operationsBefore, store.operations().first())
        assertEquals(2, extra.reads)
    }

    @Test fun stoppedOrChangedConsentDuringReadRejectsLatePayloadAndNewerSuccessSurvivesFailure(): Unit = runBlocking {
        val p = SyntheticExtraSource(); enable(p)
        p.onRead = { store.authorizeHealthExtra("stop", null, p, true) }
        reject { store.syncHealthExtra("late-read", p) }
        assertTrue(db.healthExtraDao().latest().isEmpty()); assertFalse(store.healthExtraCache().first().state.enabled)
        p.onRead = {}; enable(p, "reenable")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val slow = SyntheticExtraSource().also { it.onRead = { entered.complete(Unit); release.await(); error("private failure not for UI") } }
        val read = async { runCatching { store.syncHealthExtra("slow", slow) } }
        entered.await(); val newer = store.syncHealthExtra("newer", p); release.complete(Unit); read.await()
        val cache = store.healthExtraCache().first()
        assertEquals(newer.id, cache.lastSuccess!!.id); assertNull(cache.state.lastErrorCode)
    }

    @Test fun globalAndExtraOnlyPurgeAreAtomicRetiredAndCannotEraseLaterImportsOnRetry(): Unit = runBlocking {
        val p = SyntheticExtraSource(); enable(p); store.syncHealthExtra("first", p)
        store.purgeLocalFitnessImport("global-purge", true)
        assertTrue(db.healthExtraDao().latest().isEmpty()); assertTrue(db.healthExtraDao().latestSamples().isEmpty())
        reject { store.syncHealthExtra("first", p) }
        enable(p, "after-purge"); clock += 1_000; store.syncHealthExtra("second", p)
        val cached = store.healthExtraCache().first()
        store.purgeLocalFitnessImport("global-purge", true)
        assertEquals(cached, store.healthExtraCache().first())
        store.purgeHealthExtra("extra-purge", true)
        val archive = store.captureBackup("test.app", true, true)
        val encoded = String(BackupArchiveCodec.encode(archive), Charsets.UTF_8)
        assertFalse(encoded.contains("test.synthetic")); assertFalse(encoded.contains("synthetic-BODY_FAT"))
        assertTrue(db.healthExtraDao().latest().isEmpty())
        // A core purge made before Room20 has no extra marker. Its retry still cannot erase new extras.
        db.healthDao().insertRun(HealthImportRunEntity("old-core-purge", FitnessHealthPolicy.SAMSUNG_PACKAGE,
            "PURGE", FitnessImportKernel.fingerprint("HEALTH_LOCAL_PURGE_V1", FitnessHealthPolicy.SAMSUNG_PACKAGE), 0, 1))
        enable(p, "after-extra"); clock += 1_000; store.syncHealthExtra("third", p)
        val latest = store.healthExtraCache().first()
        store.purgeLocalFitnessImport("old-core-purge", true)
        assertEquals(latest, store.healthExtraCache().first())
    }

    @Test fun backupScopeDisabledRestoreAndPublishedRoom19BridgePreserveExactValues(): Unit = runBlocking {
        val p = SyntheticExtraSource(); enable(p); store.syncHealthExtra("copy", p)
        val full = store.captureBackup("test.app", true, true)
        val plain = store.captureBackup("test.app", false, true)
        assertTrue(plain.tables.filter { it.name.startsWith("health_extra_") }.all { it.rows.isEmpty() })
        val original = store.healthExtraCache().first()
        db.close(); context.deleteDatabase(name); db = open(); store = FoodStore(db) { clock }
        val bytes = BackupArchiveCodec.encode(full)
        store.restoreBackup("restore", full, BackupArchiveCodec.digest(bytes), "test.app", true)
        val restored = store.healthExtraCache().first()
        assertFalse(restored.state.enabled); assertEquals(original.records, restored.records)
        assertEquals(original.lastSuccess, restored.lastSuccess)
        reject { store.syncHealthExtra("auto-after-restore", p) }
        store.purgeHealthExtra("purge-restored", true)
        store.restoreBackup("restore", full, BackupArchiveCodec.digest(bytes), "test.app", true)
        assertTrue(store.healthExtraCache().first().records.isEmpty())
        val old = publishedBackupFixture(full, 19)
        db.close(); context.deleteDatabase(name); db = open(); store = FoodStore(db) { clock }
        store.restoreBackup("old-19", old, BackupArchiveCodec.digest(BackupArchiveCodec.encode(old)), "test.app", true)
        assertTrue(store.healthExtraCache().first().records.isEmpty())
    }

    @Test fun corruptedBackupUnitsRawNumbersAndSampleOrderRollbackTheWholeRestore(): Unit = runBlocking {
        val p = SyntheticExtraSource(); enable(p); store.syncHealthExtra("original", p)
        val full = store.captureBackup("test.app", true, true)
        db.close(); context.deleteDatabase(name); db = open(); store = FoodStore(db) { clock }
        for ((tableName, column, replacement) in listOf(
            Triple("health_extra_versions", "unit", BackupCell.Text("invented-unit")),
            Triple("health_extra_versions", "scalarBits", BackupCell.Integer(Double.NaN.toRawBits())),
            Triple("health_extra_samples", "position", BackupCell.Integer(8)))) {
            val bad = full.copy(tables = full.tables.map { table ->
                if (table.name != tableName) table else table.copy(rows = table.rows.mapIndexed { index, row ->
                    if (index != 0) row else row.toMutableList().apply { this[table.columns.indexOf(column)] = replacement }
                })
            })
            reject { store.restoreBackup("invalid-$column", bad, BackupArchiveCodec.digest(BackupArchiveCodec.encode(bad)), "test.app", true) }
            assertNull(db.healthExtraDao().state()); assertTrue(db.healthExtraDao().versions().isEmpty())
            assertTrue(db.healthExtraDao().runs().isEmpty()); assertEquals(0, db.foodDao().operationCount())
        }
    }
}
