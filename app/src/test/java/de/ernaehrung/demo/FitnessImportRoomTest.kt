package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real local SQLite/transactions with an explicitly synthetic source, no HC/Watch proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FitnessImportRoomTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var version: String
    private var clock = HEALTH_AT + 5_000L
    private val sourceId = FitnessHealthPolicy.SAMSUNG_PACKAGE
    private fun open() = Room.databaseBuilder(context, FoodDatabase::class.java, name)
        .addMigrations(FoodMigrations.MIGRATION_8_9, FoodMigrations.MIGRATION_9_10, FoodMigrations.MIGRATION_10_11).build()
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        name = "synthetic-health-${UUID.randomUUID()}.db"
        db = open()
        store = FoodStore(db) { clock }
        version = store.rememberProduct("food-product", ProductInput("Testlebensmittel",
            energyKcal = 0.0, source = Source.USER, quality = Quality.CONFIRMED))
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private suspend fun rejected(block: suspend () -> Unit): Exception {
        try { block(); fail("Expected atomic health import rejection") }
        catch (failure: Exception) { return failure }
        error("unreachable")
    }

    @Test fun fullLifecycleKeepsProviderAggregateVersionHistoryRestartAndFoodIsolation() = runBlocking {
        val location = store.addLocation("food-location", "Vorrat", true)
        val batch = store.receive("food-stock", BatchInput(version, location, 100_000L,
            "2026-11-05", release = ReleaseStatus.RELEASED), true)
        val foodOperations = db.foodDao().operationCount()
        val foodSnapshot = store.dayNutrition("2026-11-05").snapshot
        val overlap = healthSteps(80L, "synthetic-overlap")
        val provider = SyntheticHealthSource(healthSnapshot(records = listOf(healthSteps(), overlap, healthWorkout()), aggregate = 120L))
        store.enableFitnessImport("enable", true, true)
        val first = store.syncFitness("first", provider)
        assertEquals(120L, first.aggregatedSteps) // Raw steps overlap: 100 + 80 is not the aggregate.
        assertEquals(3, db.healthDao().history(sourceId).size)
        assertEquals(180L, db.healthDao().latest(sourceId).mapNotNull { it.steps }.sum())
        db.close(); db = open(); store = FoodStore(db) { clock }
        assertEquals("first", store.fitnessCache().first().lastSuccess?.id)
        provider.onRead = { error("An idempotent retry must not reread the provider") }
        assertEquals(first, store.syncFitness("first", provider))
        provider.onRead = {}
        store.syncFitness("same-data-new-request", provider)
        assertEquals(3, db.healthDao().history(sourceId).size)
        provider.snapshot = healthSnapshot(HEALTH_AT + 1_000L, listOf(healthSteps(0), overlap), aggregate = null)
        store.syncFitness("change-and-hide", provider)
        assertNull(store.fitnessCache().first().lastSuccess?.aggregatedSteps)
        assertEquals(5, db.healthDao().history(sourceId).size)
        assertEquals(0L, db.healthDao().latest(sourceId).single { it.externalId == healthSteps().externalId }.steps)
        val hidden = db.healthDao().latest(sourceId).single { it.kind == "WORKOUT" }
        assertFalse(hidden.visible); assertEquals("NOT_VISIBLE_IN_WINDOW", hidden.visibilityReason)
        provider.snapshot = healthSnapshot(HEALTH_AT + 2_000L, listOf(healthSteps(0), overlap, healthWorkout()), aggregate = 0L)
        store.syncFitness("reappeared", provider)
        assertEquals(0L, store.fitnessCache().first().lastSuccess?.aggregatedSteps)
        assertEquals(6, db.healthDao().history(sourceId).size)
        assertEquals(3L, db.healthDao().latest(sourceId).single { it.kind == "WORKOUT" }.revision)
        assertEquals(foodOperations, db.foodDao().operationCount())
        assertEquals(100_000L, db.foodDao().balance(batch))
        assertEquals(foodSnapshot, store.dayNutrition("2026-11-05").snapshot)
        assertEquals(0, db.foodDao().activeConsumptionCount())
        assertTrue(db.foodDao().purchases().isEmpty()); assertTrue(db.foodDao().allPaidPrices().isEmpty())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun noLocalConsentOrDeniedPermissionCanCreateAnImport() = runBlocking {
        val provider = SyntheticHealthSource()
        rejected { store.enableFitnessImport("not-consented", true, false) }
        rejected { store.syncFitness("without-opt-in", provider) }
        assertEquals(0, provider.reads.get())
        assertTrue(db.healthDao().runs(sourceId).isEmpty())
        store.enableFitnessImport("enable", true, true)
        provider.permissions = emptySet()
        rejected { store.syncFitness("without-permission", provider) }
        assertEquals(0, provider.reads.get())
        assertTrue(db.healthDao().history(sourceId).isEmpty())
        assertEquals(false, db.healthDao().state(sourceId)?.enabled)
        assertEquals("PERMISSION_MISSING", db.healthDao().state(sourceId)?.lastErrorCode)
        assertEquals(1, db.foodDao().operationCount())
    }

    @Test fun permissionRevokedAfterReadingRollsBackAllSourceDataAndStopsFutureReads() = runBlocking {
        val provider = SyntheticHealthSource()
        store.enableFitnessImport("enable", true, true)
        provider.permissionReply = { if (it == 1) provider.requiredPermissions else emptySet() }
        rejected { store.syncFitness("revoked-before-commit", provider) }
        assertEquals(1, provider.reads.get())
        assertTrue(db.healthDao().history(sourceId).isEmpty())
        assertNull(db.healthDao().run("revoked-before-commit"))
        assertEquals(false, db.healthDao().state(sourceId)?.enabled)
        assertEquals(2L, db.healthDao().state(sourceId)?.authorizationRevision)
        rejected { store.syncFitness("stopped", provider) }
        assertEquals(1, provider.reads.get())
    }

    @Test fun malformedFullSnapshotCannotPartiallyInsertOrHidePreviouslyValidRecords() = runBlocking {
        val provider = SyntheticHealthSource()
        store.enableFitnessImport("enable", true, true); store.syncFitness("valid", provider)
        val before = db.healthDao().history(sourceId)
        provider.snapshot = healthSnapshot(HEALTH_AT + 1_000L,
            listOf(healthSteps(200L), healthWorkout().copy(sourcePackage = "unapproved.source")))
        rejected { store.syncFitness("invalid", provider) }
        assertEquals(before, db.healthDao().history(sourceId))
        assertNull(db.healthDao().run("invalid"))
        assertEquals("valid", db.healthDao().state(sourceId)?.lastSuccessRunId)
        assertEquals(1, db.foodDao().operationCount())
    }

    @Test fun anOldReadCannotCrossDisableAndNewConsentEvenWithIdenticalOwnerAndData() = runBlocking {
        store.enableFitnessImport("enable", true, true)
        val provider = SyntheticHealthSource()
        provider.onRead = {
            store.enableFitnessImport("stop", false, true)
            store.enableFitnessImport("new-consent", true, true)
        }
        rejected { store.syncFitness("old-read", provider) }
        assertEquals(3L, db.healthDao().state(sourceId)?.authorizationRevision)
        assertEquals(true, db.healthDao().state(sourceId)?.enabled)
        assertTrue(db.healthDao().history(sourceId).isEmpty())
        assertNull(db.healthDao().state(sourceId)?.lastErrorCode)
        assertNull(db.healthDao().run("old-read"))
    }

    @Test fun aLateOldSnapshotCannotOverwriteANewerSuccessfulReadOrItsStatus() = runBlocking {
        store.enableFitnessImport("enable", true, true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = SyntheticHealthSource().apply { onRead = { entered.complete(Unit); release.await() } }
        val slow = async(Dispatchers.IO) { runCatching { store.syncFitness("old", old) } }
        entered.await()
        val newer = SyntheticHealthSource(healthSnapshot(HEALTH_AT + 1_000L, listOf(healthSteps(200L))))
        store.syncFitness("new", newer)
        release.complete(Unit)
        assertTrue(slow.await().isFailure)
        assertEquals("new", db.healthDao().state(sourceId)?.lastSuccessRunId)
        assertNull(db.healthDao().state(sourceId)?.lastErrorCode)
        assertEquals(200L, db.healthDao().latest(sourceId).single().steps)
        assertNull(db.healthDao().run("old"))
    }

    @Test fun explicitLocalPurgeIsScopedIdempotentAndKeepsFoodAndProviderUntouched() = runBlocking {
        val provider = SyntheticHealthSource()
        store.enableFitnessImport("enable", true, true); store.syncFitness("valid", provider)
        rejected { store.purgeLocalFitnessImport("purge", false) }
        assertEquals(2, db.healthDao().history(sourceId).size)
        store.purgeLocalFitnessImport("purge", true)
        val state = db.healthDao().state(sourceId)
        store.purgeLocalFitnessImport("purge", true)
        assertEquals(state, db.healthDao().state(sourceId))
        assertEquals(false, state?.enabled); assertNull(state?.lastSuccessRunId)
        assertTrue(db.healthDao().history(sourceId).isEmpty())
        val runs = db.healthDao().runs(sourceId)
        assertEquals(1, runs.count { it.kind == "PURGE" })
        assertEquals(2, runs.count { it.kind == "REDACTED" })
        runs.filter { it.kind == "REDACTED" }.forEach { marker ->
            assertEquals(HealthImportRunEntity(marker.id, sourceId, "REDACTED", "", 0L, 0L), marker)
        }
        assertNotNull(db.foodDao().version(version)); assertEquals(1, db.foodDao().operationCount())
        assertEquals(1, provider.reads.get())
        assertEquals(2, provider.snapshot.records.size)
    }
    @Test fun anOldPurgeOrImportRequestCannotEraseOrRecreateDataAfterLaterPurgeCyclesAndRestart() = runBlocking {
        val provider = SyntheticHealthSource()
        store.enableFitnessImport("enable-one", true, true)
        store.syncFitness("sync-one", provider)
        store.purgeLocalFitnessImport("purge-one", true)
        store.enableFitnessImport("enable-two", true, true)
        store.syncFitness("sync-two", provider)
        store.purgeLocalFitnessImport("purge-two", true)
        store.enableFitnessImport("enable-three", true, true)
        store.syncFitness("sync-three", provider)
        db.close(); db = open(); store = FoodStore(db) { clock }
        val current = store.fitnessCache().first()
        val runs = db.healthDao().runs(sourceId)
        store.purgeLocalFitnessImport("purge-one", true)
        assertEquals("A delayed old deletion must not erase a newer cache", current, store.fitnessCache().first())
        store.purgeLocalFitnessImport("purge-two", true)
        assertEquals(current, store.fitnessCache().first())
        rejected { store.enableFitnessImport("enable-one", true, true) }
        rejected { store.syncFitness("sync-one", provider) }
        rejected { store.enableFitnessImport("enable-two", false, true) }
        rejected { store.syncFitness("sync-two", provider) }
        assertEquals(runs, db.healthDao().runs(sourceId))
        assertEquals(current, store.fitnessCache().first())
        assertEquals(3, provider.reads.get())
        assertEquals(1, db.foodDao().operationCount())
    }

}
