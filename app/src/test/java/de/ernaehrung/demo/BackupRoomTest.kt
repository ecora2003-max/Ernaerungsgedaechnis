package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.backup.*
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoomTest {
    private lateinit var source: FoodDatabase
    private lateinit var target: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var destination: FoodStore
    private lateinit var version: String
    private lateinit var batch: String
    private val appId = "de.ernaehrung.demo"
    private val day = "2026-11-05"
    private val at = HEALTH_AT + 5000
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        source = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build()
        store = FoodStore(source) { at }; destination = FoodStore(target) { at }
        version = store.rememberProduct("product", ProductInput("Synthetische Sicherungsware", energyKcal = 0.0))
        val location = store.addLocation("location", "Synthetischer Vorrat", true)
        batch = store.receive("receive", BatchInput(version, location, 100_000, day, release = ReleaseStatus.RELEASED), true)
        store.reviewBatch("review", BatchReviewInput(batch, null, day, "2026-11-07", null, null, null,
            ReleaseStatus.RELEASED, "Geprüfte Bedingungen", "Originale behalten", true), true)
        store.consume("meal", ConsumptionInput(version, day, "Synthetisch tatsächlich erfasst", 10_000, listOf(Allocation(batch, 10_000))), true)
        store.setDayStatus("complete", day, DayStatus.COMPLETE, store.dayNutrition(day).snapshot, true)
        store.savePurchase("paid", PurchaseInput("SICHERUNGSTEST", day, 0, listOf(PurchaseLineInput(
            originalText = "Preis bekannt, Menge unbekannt", versionId = version, totalCents = 0)), "Teilwissen"), true)
        store.saveBodyMeasurement("body", BodyMeasurementInput(BodyMetric.WEIGHT, "73,0400", day), null, true)
        store.beginManualWorkout("training")
        store.saveManualWorkout("training", ManualWorkoutInput("Synthetischer Lauf", FitnessTimestampInput(day), durationMinutes = "0,50"), null, null, true)
        store.enableFitnessImport("enable", true, true)
        store.syncFitness("sync", SyntheticHealthSource(healthSnapshot(records = listOf(healthSteps(0),
            healthWorkout().copy(endAt = HEALTH_AT + 60_000)), aggregate = null)))
        Unit
    }
    @After fun close() { source.close(); target.close() }
    private fun digest(archive: BackupArchive) = BackupArchiveCodec.digest(BackupArchiveCodec.encode(archive))
    private suspend fun capture(fitness: Boolean = true) = store.captureBackup(appId, fitness, true)
    private suspend fun restore(archive: BackupArchive, key: String = "restore") = destination.restoreBackup(key, archive, digest(archive), appId, true)
    private fun emptyTarget() {
        FoodBackupStore.TABLES.forEach { table -> target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            assertTrue(it.moveToFirst()); assertEquals("No partial rows in $table", 0, it.getInt(0))
        } }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        assertTrue(runCatching { block() }.isFailure); emptyTarget()
    }
    private fun change(archive: BackupArchive, table: String, column: String, value: BackupCell) = archive.copy(tables = archive.tables.map { t ->
        if (t.name != table) t else t.copy(rows = t.rows.mapIndexed { index, row ->
            if (index != 0) row else row.toMutableList().apply { this[t.columns.indexOf(column)] = value }
        })
    })

    @Test fun fullEncryptedRestorePreservesOriginalIdsNullZeroDecimalsReviewsAndHistoryButDoesNotEnableAProvider() = runBlocking {
        val before = source.foodDao().operationCount()
        val archive = capture(); assertEquals(52, archive.tables.size)
        assertEquals(FoodBackupStore.TABLES, archive.tables.map { it.name }.toSet())
        assertEquals(before, source.foodDao().operationCount())
        val bytes = BackupArchiveCodec.encode(archive)
        val password = "synthetic-backup-password".toCharArray()
        val decoded = BackupArchiveCodec.decode(BackupEncryption().open(BackupEncryption().seal(bytes, password), password))
        assertEquals(BackupRestoreStatus.RESTORED, restore(decoded).status)
        assertEquals(before + 1, target.foodDao().operationCount())
        assertEquals(source.foodDao().allVersions(), target.foodDao().allVersions())
        assertEquals(source.foodDao().originalBatch(batch), target.foodDao().originalBatch(batch))
        assertEquals(source.foodDao().batchReviews(batch), target.foodDao().batchReviews(batch))
        assertEquals(90_000L, target.foodDao().balance(batch))
        assertEquals(store.dayNutrition(day), destination.dayNutrition(day))
        assertEquals(source.foodDao().purchases(), target.foodDao().purchases())
        val paid = target.foodDao().receipts().single(); assertEquals(0L, paid.totalCents)
        assertNull(target.foodDao().receiptLines(paid.id).single().amount)
        assertEquals(source.bodyDao().versions(), target.bodyDao().versions())
        assertEquals("73,0400", target.bodyDao().versions().single().rawValue)
        assertEquals(source.manualWorkoutDao().versions(), target.manualWorkoutDao().versions())
        val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
        assertEquals(source.healthDao().history(origin), target.healthDao().history(origin))
        assertEquals(0L, target.healthDao().latest(origin).single { it.kind == "STEPS" }.steps)
        val deferred = target.healthDao().latest(origin).single { it.kind == "WORKOUT" }
        assertFalse(deferred.visible)
        assertEquals(FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE, deferred.visibilityReason)
        assertEquals(HEALTH_AT + 60_000, deferred.endAt)
        assertFalse(target.healthDao().state(origin)!!.enabled)
        assertEquals(source.healthDao().state(origin)!!.authorizationRevision + 1, target.healthDao().state(origin)!!.authorizationRevision)
        val count = target.foodDao().operationCount()
        assertEquals(BackupRestoreStatus.ALREADY_PRESENT, restore(decoded).status)
        assertEquals(BackupRestoreStatus.ALREADY_PRESENT, restore(decoded, "different-retry-key").status)
        assertEquals(count, target.foodDao().operationCount()); assertEquals(90_000L, target.foodDao().balance(batch))
        destination.purgeBodyMeasurements("delete-restored-body", true)
        destination.purgeLocalFitnessImport("delete-restored-health", true)
        assertEquals(BackupRestoreStatus.ALREADY_PRESENT, restore(decoded, "late-after-purge").status)
        assertTrue(target.bodyDao().versions().isEmpty()); assertTrue(target.healthDao().history(origin).isEmpty())
    }

    @Test fun foodOnlyScopeExcludesEveryFitnessTableWithoutMutatingTheSourceOrGuessingUnknownValues() = runBlocking {
        val before = source.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE)
        val archive = capture(false)
        assertFalse(archive.fitnessIncluded)
        assertTrue(archive.tables.filter { it.name in FoodBackupStore.FITNESS_TABLES }.all { it.rows.isEmpty() })
        restore(archive)
        assertEquals(store.dayNutrition(day), destination.dayNutrition(day))
        assertTrue(target.bodyDao().versions().isEmpty()); assertTrue(target.manualWorkoutDao().versions().isEmpty())
        assertNull(target.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE))
        assertEquals(before, source.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE))
        assertEquals(90_000L, target.foodDao().balance(batch))
    }

    @Test fun workoutMetricBackupRequiresFitnessScopePreservesKnownZeroAndCannotResurrectAfterPurge(): Unit = runBlocking {
        val provider = SyntheticHealthSource(healthSnapshot(HEALTH_AT + 1, listOf(healthSteps(0), healthWorkout()), aggregate = null))
        store.syncFitness("completed", provider)
        val row = source.healthDao().latest(FitnessHealthPolicy.SAMSUNG_PACKAGE).single { it.kind == "WORKOUT" }
        provider.durationReply = { q -> FitnessWorkoutDurationSnapshot(sourcePackage = FitnessHealthPolicy.SAMSUNG_PACKAGE,
            windowStart = q.startAt, windowEnd = q.endAt, capturedAt = at, records = listOf(healthWorkout()),
            durationMillis = 0, status = FitnessWorkoutDurationStatus.AVAILABLE) }
        val metric = store.readFitnessWorkoutDuration("duration-zero", provider, row.durationTarget())
        val food = capture(false)
        assertTrue(food.tables.filter { it.name in FoodBackupStore.FITNESS_TABLES }.all { it.rows.isEmpty() })
        val archive = capture(true); restore(archive)
        val cache = destination.fitnessCache().first()
        assertFalse(cache.state.enabled); assertEquals(metric, cache.knownDurations.single())
        assertEquals(0L, cache.knownDurations.single().workoutDurationMillis)
        destination.purgeLocalFitnessImport("purge", true)
        assertEquals(BackupRestoreStatus.ALREADY_PRESENT, restore(archive, "retry-after-purge").status)
        assertTrue(destination.fitnessCache().first().knownDurations.isEmpty())
        val marker = target.healthDao().run("duration-zero")!!
        assertNull(marker.workoutExternalId); assertNull(marker.workoutDurationMillis); assertNull(marker.workoutDurationAttempt)
    }

    @Test fun pinnedPublishedThirteenThroughEighteenArchivesGainOnlyUnknownMetricColumnsAndKeepOriginalRetryDigest(): Unit = runBlocking {
        val full = capture()
        val pins = mapOf(13 to "ddbdbf0fb475ae9d3a5c9a40b4284aa3", 14 to "adf43266f88da0de1632891687871124",
            15 to "f0276403a772111cf77efeaf250b4b2b", 16 to "e2d5d92fe1b98be4adaf09fc57288228",
            17 to "ac53ed935c0fb8f4986777c82f6cdfb5", 18 to "ee216143184cd60023a7c45e8dd1db92")
        for ((version, pin) in pins) {
            target.close(); target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
            destination = FoodStore(target) { at }
            val missing = buildSet {
                if (version < 18) add("storage_location_reviews")
                if (version < 17) add("recipe_favorites")
                if (version < 16) add("personal_restrictions")
                if (version < 15) addAll(listOf("nutrition_goal_runs", "nutrition_goal_versions", "nutrition_goal_values"))
                if (version < 14) add("storage_capacity_reviews")
            }
            val archive = full.copy(databaseVersion = version, identityHash = pin,
                tables = full.tables.filterNot { it.name in missing }.map { table ->
                    if (table.name == "health_import_runs") table.copy(columns = table.columns.dropLast(6), rows = table.rows.map { it.dropLast(6) }) else table
                })
            assertTrue(destination.validateBackupForPreview(archive, appId))
            assertEquals(BackupRestoreStatus.RESTORED, restore(archive).status)
            assertEquals(digest(archive), target.foodDao().operation("restore")!!.resultId)
            assertEquals(BackupRestoreStatus.ALREADY_PRESENT, restore(archive, "same-original").status)
            target.healthDao().runs(FitnessHealthPolicy.SAMSUNG_PACKAGE).forEach {
                assertNull(it.workoutDurationMillis); assertNull(it.workoutExternalId); assertNull(it.workoutDurationStatus); assertNull(it.workoutDurationAttempt)
            }
            assertFalse(target.healthDao().state(FitnessHealthPolicy.SAMSUNG_PACKAGE)!!.enabled)
        }
    }

    @Test fun missingConsentChangedDigestApplicationSchemaColumnsOrFitnessScopeNeverWriteAnything() = runBlocking {
        val archive = capture()
        rejected { destination.restoreBackup("unconfirmed", archive, digest(archive), appId, false) }
        rejected { destination.restoreBackup("changed", archive, "wrong", appId, true) }
        rejected { restore(archive.copy(applicationId = "different.app")) }
        rejected { restore(archive.copy(databaseVersion = 999)) }
        rejected { restore(archive.copy(identityHash = "different-schema")) }
        rejected { restore(archive.copy(fitnessIncluded = false)) }
        rejected { restore(archive.copy(tables = archive.tables.drop(1))) }
        val table = archive.tables.first { it.name == "products" }
        rejected { restore(archive.copy(tables = archive.tables.map { if (it.name == table.name) it.copy(columns = it.columns.reversed()) else it })) }
        assertTrue(runCatching { store.captureBackup(appId, true, false) }.isFailure)
    }

    @Test fun brokenReferencesAndNegativeBalancesRollbackTheWholeCopyAndLeaveForeignKeysEnabled() = runBlocking {
        val archive = capture()
        rejected { restore(change(archive, "product_versions", "productId", BackupCell.Text("missing-product"))) }
        rejected { restore(change(archive, "stock_movements", "delta", BackupCell.Integer(-1_000_000))) }
        rejected { restore(change(archive, "product_versions", "energyKcal", BackupCell.Real(Double.NaN.toRawBits()))) }
        target.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        target.openHelper.writableDatabase.query("PRAGMA defer_foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        assertEquals(BackupRestoreStatus.RESTORED, restore(archive).status)
    }

    @Test fun anExistingOriginalNeedsNoCopyAndDivergentTargetDataIsNeverOverwrittenOrMerged() = runBlocking {
        val archive = capture(); val count = source.foodDao().operationCount()
        assertEquals(BackupRestoreStatus.ALREADY_PRESENT, store.restoreBackup("same-original", archive, digest(archive), appId, true).status)
        assertEquals(count, source.foodDao().operationCount())
        val own = destination.rememberProduct("own", ProductInput("Vorhandene andere Daten", energyKcal = null))
        val before = destination.captureBackup(appId, true, true)
        assertTrue(runCatching { restore(archive) }.isFailure)
        assertTrue(runCatching { destination.restoreBackup("own", archive, digest(archive), appId, true) }.isFailure)
        assertEquals(before, destination.captureBackup(appId, true, true))
        assertEquals(own, target.foodDao().allVersions().single().id)
        assertEquals(1, target.foodDao().operationCount())
    }
}
