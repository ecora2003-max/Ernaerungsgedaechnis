package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.backup.*
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageLocationReviewRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private var at = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()
    private val day = "2026-11-08"
    @Before fun create() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db, now = { at })
    }
    @After fun close() = db.close()
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun input(id: String, name: String, present: Boolean = true) = StorageLocationReviewInput(id,
        db.storageLocationReviewDao().latest(id)?.id, db.storageCapacityDao().latest(id)?.id, name, present, "Vor Ort geprüft")
    private suspend fun snapshot() = store.watchStorageCapacity().first()

    @Test fun plannedPlaceBecomesTheSameRealPlaceOnlyAfterExplicitReviewAndKeepsItsOriginalHistoryAndRetryKeys() = runBlocking {
        val place = store.addLocation("plan", "Geplanter Gefrierschrank", false)
        val original = db.foodDao().originalLocations().single()
        val version = store.rememberProduct("product", ProductInput("Vorrat", energyKcal = 0.0))
        val before = db.foodDao().operationCount()
        val request = input(place, "Neuer Gefrierschrank")
        reject { store.receive("early", BatchInput(version, place, 1000, day), true) }
        reject { store.reviewStorageLocation("not-approved", request, false) }
        assertEquals(before, db.foodDao().operationCount())
        val first = store.reviewStorageLocation("confirm-place", request, true)
        assertEquals(original, db.foodDao().originalLocations().single())
        assertEquals(place, db.foodDao().location(place)!!.id)
        assertTrue(db.foodDao().location(place)!!.confirmed)
        assertEquals("Neuer Gefrierschrank", db.foodDao().allLocations().single().name)
        assertTrue(snapshot().history.isEmpty()); assertTrue(db.foodDao().planningBatches().isEmpty())
        val next = store.reviewStorageLocation("rename", input(place, "Gefrierfach Küche"), true)
        assertEquals(first, store.reviewStorageLocation("confirm-place", request, true))
        reject { store.reviewStorageLocation("stale", request, true) }
        reject { store.reviewStorageLocation("rename", input(place, "Andere Angaben"), true) }
        assertEquals(listOf(first, next), db.storageLocationReviewDao().history().map { it.id })
        val batch = store.receive("receive", BatchInput(version, place, 1000, day), true)
        val movements = db.foodDao().stockMovementSnapshotRows()
        reject { store.reviewStorageLocation("withdraw-with-stock", input(place, "Gefrierfach Küche", false), true) }
        assertEquals(1000L, db.foodDao().balance(batch)); assertEquals(movements, db.foodDao().stockMovementSnapshotRows())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun sameMillisecondLocationRevisionInvalidatesOldCapacityAndNeedsAnIndependentNewPhysicalCheck() = runBlocking {
        val place = store.addLocation("place", "Kühlschrank", true)
        val physical = StorageCapacityInput(place, null, 2000, 0, "4,000", "Vor Ort geprüft")
        val first = store.reviewStorageCapacity("capacity", physical, true)
        assertEquals(2000L, snapshot().trustedFree(db.storageCapacityDao().latest(place)!!))
        val request = input(place, "Kühlschrank Küche")
        val review = store.reviewStorageLocation("rename", request, true)
        assertNull(snapshot().trustedFree(db.storageCapacityDao().latest(place)!!))
        assertEquals(first, db.storageCapacityDao().latest(place)!!.id)
        assertEquals(0L, db.storageCapacityDao().latest(place)!!.occupiedMl)
        reject { store.reviewStorageCapacity("stale-physical", physical.copy(previousId = first), true) }
        val second = store.reviewStorageCapacity("new-physical", physical.copy(previousId = first, locationReviewId = review), true)
        assertEquals(2000L, snapshot().trustedFree(db.storageCapacityDao().latest(place)!!))
        // A concurrent physical check changes the exact review basis too.
        reject { store.reviewStorageLocation("stale-place", input(place, "Falscher Vorgänger").copy(capacityReviewId = first), true) }
        store.reviewStorageLocation("withdraw", input(place, "Abgebaut", false), true)
        assertNull(snapshot().trustedFree(db.storageCapacityDao().latest(place)!!))
        store.reviewStorageLocation("present-again", input(place, "Wieder vorhanden"), true)
        assertNull(snapshot().trustedFree(db.storageCapacityDao().latest(place)!!))
        assertEquals(listOf(first, second), snapshot().history.map { it.id })
    }

    @Test fun laterLocationKnowledgeDoesNotRewriteAnEarlierRecipeSnapshotButANewSameFactReviewInvalidatesCurrentEvidence() = runBlocking {
        val place = store.addLocation("place", "Vorrat", true)
        val version = store.rememberProduct("product", ProductInput("Ware"))
        val batch = store.receive("receive", BatchInput(version, place, 10_000, day, release = ReleaseStatus.RELEASED), true)
        val reader = RecipeRoomStockSnapshotReader(db.foodDao())
        val captured = Instant.ofEpochMilli(at)
        val before = db.withTransaction { requireNotNull(reader.read(captured)) }
        at += 1
        store.reviewStorageLocation("same-facts", input(place, "Vorrat"), true)
        assertEquals(before, db.withTransaction { reader.read(captured) })
        val after = db.withTransaction { requireNotNull(reader.read(Instant.ofEpochMilli(at))) }
        assertNotEquals(before.revision, after.revision)
        assertEquals(before.lines, after.lines)
        assertEquals(10_000L, db.foodDao().balance(batch))
        assertNull(db.foodDao().version(version)!!.energyKcal)
    }

    @Test fun manufactureCannotUseCapacityFromBeforeALocationReviewAndFreshCapacityIsAddedAtomicallyOnlyOnce() = runBlocking {
        val place = store.addLocation("place", "Kühlschrank", true)
        val version = store.rememberProduct("product", ProductInput("Kochzutat", energyKcal = 0.0))
        val batch = store.receive("receive", BatchInput(version, place, 100_000, day, release = ReleaseStatus.RELEASED), true)
        val recipe = store.saveRecipe("recipe", RecipeInput("Ansatz", 1000, listOf(RecipeItemInput(version, 10_000)), "Zubereiten"))
        val old = store.reviewStorageCapacity("capacity", StorageCapacityInput(place, null, 2000, 0, "4,0", "Geprüft"), true)
        val review = store.reviewStorageLocation("rename", input(place, "Küche"), true)
        val production = ProductionInput(recipe, 1000, listOf(Allocation(batch, 10_000)), place, day,
            true, "Lagerbedingungen geprüft", release = ReleaseStatus.RELEASED,
            capacityEvidence = ProductionCapacityEvidence(old, 500, true))
        val before = db.foodDao().operationCount()
        reject { store.produce("stale-manufacture", production, true) }
        assertEquals(before, db.foodDao().operationCount()); assertEquals(100_000L, db.foodDao().balance(batch))
        val fresh = store.reviewStorageCapacity("fresh", StorageCapacityInput(place, old, 2000, 0, "4,0", "Neu geprüft", review), true)
        val current = production.copy(capacityEvidence = ProductionCapacityEvidence(fresh, 500, true))
        val output = store.produce("make", current, true)
        assertEquals(output, store.produce("make", current, true))
        assertEquals(90_000L, db.foodDao().balance(batch)); assertEquals(1000L, db.foodDao().balance(output))
        assertEquals(1500L, snapshot().trustedFree(db.storageCapacityDao().latest(place)!!))
        assertEquals(0, db.foodDao().activeConsumptionCount())
    }

    @Test fun newHistorySurvivesLosslessBackupWhilePublishedV17RestoreAddsOnlyAnEmptyLocationHistory() = runBlocking {
        val place = store.addLocation("plan", "Geplant", false)
        val oldCurrent = store.captureBackup("de.ernaehrung.demo", false, true)
        val v17 = publishedBackupFixture(oldCurrent, 17)
        val review = store.reviewStorageLocation("review", input(place, "Vorhanden"), true)
        val current = store.captureBackup("de.ernaehrung.demo", false, true)
        assertEquals(56, current.tables.size)
        for (archive in listOf(v17, current)) {
            val target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
            try {
                val destination = FoodStore(target, now = { at })
                val digest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(archive))
                assertEquals(BackupRestoreStatus.RESTORED, destination.restoreBackup("restore", archive, digest, "de.ernaehrung.demo", true).status)
                assertEquals(db.foodDao().originalLocations(), target.foodDao().originalLocations())
                if (archive.databaseVersion == 17) {
                    assertTrue(target.storageLocationReviewDao().history().isEmpty()); assertFalse(target.foodDao().location(place)!!.confirmed)
                } else {
                    assertEquals(review, target.storageLocationReviewDao().latest(place)!!.id)
                    assertEquals(db.foodDao().allLocations(), target.foodDao().allLocations())
                    assertEquals(db.storageLocationReviewDao().history(), target.storageLocationReviewDao().history())
                }
                val count = target.foodDao().operationCount()
                assertEquals(BackupRestoreStatus.ALREADY_PRESENT, destination.restoreBackup("retry", archive, digest, "de.ernaehrung.demo", true).status)
                assertEquals(count, target.foodDao().operationCount())
                target.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { target.close() }
        }
        val damaged = current.copy(tables = current.tables.map { table ->
            if (table.name != "storage_location_reviews") table else table.copy(rows = table.rows.map { row ->
                row.toMutableList().apply { this[table.columns.indexOf("reason")] = BackupCell.Text("Unpassender geänderter Grund") }
            })
        })
        val empty = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        try {
            val digest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(damaged))
            reject { FoodStore(empty).restoreBackup("damaged", damaged, digest, "de.ernaehrung.demo", true) }
            assertEquals(0, empty.foodDao().operationCount()); assertTrue(empty.foodDao().originalLocations().isEmpty())
            assertTrue(empty.storageLocationReviewDao().history().isEmpty())
        } finally { empty.close() }
    }
}
