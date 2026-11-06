package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.backup.*
import de.ernaehrung.demo.data.local.*
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
class StorageCapacityRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private var at = Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
    private val day = "2026-11-06"
    @Before fun create() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db, now = { at })
    }
    @After fun close() = db.close()
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected a complete rejection") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun seed(): Triple<String, String, String> {
        val version = store.rememberProduct("product", ProductInput("Synthetische Kapazitätszutat", energyKcal = 0.0))
        val location = store.addLocation("location", "Synthetischer vorhandener Kühlschrank", true)
        val batch = store.receive("receive", BatchInput(version, location, 1_000_000, day,
            bestBefore = "2026-11-09", release = ReleaseStatus.RELEASED), true)
        return Triple(version, location, batch)
    }
    private suspend fun input(location: String, total: Long? = 2000, occupied: Long? = 500,
        temperature: String? = "4,500") = StorageCapacityInput(location, db.storageCapacityDao().latest(location)?.id,
            total, occupied, temperature, "Aktuelle tatsächliche Angaben vor Ort geprüft")

    @Test fun physicalChecksPreserveOriginalsKnownZeroUnknownRawTemperatureHistoryAndExactReplayWithoutFoodWrites() = runBlocking {
        val (_, location, batch) = seed()
        val original = db.foodDao().location(location)
        val before = db.foodDao().operationCount()
        assertTrue(store.watchStorageCapacity().first().history.isEmpty())
        assertEquals(before, db.foodDao().operationCount())
        val firstInput = input(location)
        val first = store.reviewStorageCapacity("check", firstInput, true)
        val row = db.storageCapacityDao().latest(location)!!
        assertEquals(first, row.id); assertEquals("4,500", row.temperatureCelsiusText)
        assertEquals(1500L, StorageCapacityRules.free(row))
        assertEquals(first, store.reviewStorageCapacity("check", firstInput, true))
        assertEquals(before + 1, db.foodDao().operationCount())
        at++
        val second = store.reviewStorageCapacity("known-zero", input(location, 0, 0, null), true)
        assertEquals(0L, StorageCapacityRules.free(db.storageCapacityDao().latest(location)!!))
        at++
        store.reviewStorageCapacity("unknown-occupancy", input(location, 0, null, null), true)
        assertNull(StorageCapacityRules.free(db.storageCapacityDao().latest(location)!!))
        assertEquals(listOf(first, second), db.storageCapacityDao().history().take(2).map { it.id })
        assertEquals(row, db.storageCapacityDao().history().first())
        assertEquals(original, db.foodDao().location(location))
        assertEquals(1_000_000L, db.foodDao().balance(batch))
        assertEquals(0, db.foodDao().activeConsumptionCount())
    }

    @Test fun consentPlannedStorageStalePredecessorChangedReplayAndInvalidUnitsNeverCreatePartialCapacityOrFoodFacts() = runBlocking {
        val (_, location, batch) = seed()
        val planned = store.addLocation("planned", "Nur geplantes Gerät", false)
        val reviewed = input(location)
        val before = db.foodDao().operationCount()
        reject { store.reviewStorageCapacity("unconfirmed", reviewed, false) }
        reject { store.reviewStorageCapacity("planned-check", reviewed.copy(locationId = planned), true) }
        reject { store.reviewStorageCapacity("negative", reviewed.copy(totalMl = -1), true) }
        reject { store.reviewStorageCapacity("too-full", reviewed.copy(totalMl = 499), true) }
        reject { store.reviewStorageCapacity("bad-temperature", reviewed.copy(temperatureCelsiusText = "-273,151"), true) }
        assertEquals(before, db.foodDao().operationCount()); assertTrue(db.storageCapacityDao().history().isEmpty())
        val first = store.reviewStorageCapacity("accepted", reviewed, true)
        reject { store.reviewStorageCapacity("stale", reviewed, true) }
        reject { store.reviewStorageCapacity("accepted", reviewed.copy(reason = "Andere Angaben"), true) }
        at--
        reject { store.reviewStorageCapacity("backdated", reviewed.copy(previousId = first), true) }
        assertEquals(before + 1, db.foodDao().operationCount())
        assertEquals(first, db.storageCapacityDao().history().single().id)
        assertEquals(1_000_000L, db.foodDao().balance(batch))
    }

    @Test fun confirmedProductionBindsTheCurrentPhysicalBasisAndAddsOutsideVolumeAtomicallyOnceButEatingNeverFreesContainers() = runBlocking {
        val (version, location, batch) = seed()
        val recipe = store.saveRecipe("recipe", RecipeInput("Synthetischer Ansatz", 1000, listOf(RecipeItemInput(version, 100_000)), "Synthetische Zubereitung"))
        val review = store.reviewStorageCapacity("check", input(location), true)
        val production = ProductionInput(recipe, 1000, listOf(Allocation(batch, 100_000)), location, day,
            true, "Tatsächliche Lagerbedingungen und zusätzliche Behälter geprüft", release = ReleaseStatus.RELEASED,
            useBy = "2026-11-08", capacityEvidence = ProductionCapacityEvidence(review, 800, true))
        val before = db.foodDao().operationCount()
        reject { store.produce("no-current-check", production.copy(capacityEvidence = production.capacityEvidence!!.copy(physicallyChecked = false)), true) }
        reject { store.produce("does-not-fit", production.copy(capacityEvidence = production.capacityEvidence!!.copy(addedOuterVolumeMl = 1501)), true) }
        assertEquals(before, db.foodDao().operationCount()); assertEquals(1, db.storageCapacityDao().history().size)
        at++
        val output = store.produce("produce", production, true)
        val added = db.storageCapacityDao().latest(location)!!
        assertEquals(1300L, added.occupiedMl); assertEquals(700L, StorageCapacityRules.free(added))
        assertEquals(StorageCapacityRules.PRODUCTION, added.basisKind); assertEquals(review, added.previousId)
        assertEquals("produce", added.operationId); assertEquals(800L, added.addedOuterVolumeMl)
        assertEquals(before + 1, db.foodDao().operationCount())
        assertEquals(900_000L, db.foodDao().balance(batch)); assertEquals(1000L, db.foodDao().balance(output))
        assertEquals(output, store.produce("produce", production, true))
        assertEquals(2, db.storageCapacityDao().history().size)
        reject { store.produce("stale-after-production", production, true) }
        // A known additional ZERO reuses already occupied containers. It is not
        // an unknown volume and must not consume invented extra space.
        at++
        store.produce("produce-existing-containers", production.copy(capacityEvidence = ProductionCapacityEvidence(added.id, 0, true)), true)
        val noExtraSpace = db.storageCapacityDao().latest(location)!!
        assertEquals(0L, noExtraSpace.addedOuterVolumeMl)
        assertEquals(added.occupiedMl, noExtraSpace.occupiedMl)
        assertEquals(700L, StorageCapacityRules.free(noExtraSpace))
        val outputVersion = db.foodDao().batch(output)!!.versionId
        store.consume("eat", ConsumptionInput(outputVersion, day, "Tatsächlicher Verzehr", 1000, listOf(Allocation(output, 1000))), true)
        assertEquals(0L, db.foodDao().balance(output))
        assertEquals(noExtraSpace, db.storageCapacityDao().latest(location))
        at++
        store.reviewStorageCapacity("actual-empty-container-removal", input(location, 2000, 0), true)
        assertEquals(2000L, StorageCapacityRules.free(db.storageCapacityDao().latest(location)!!))
        assertEquals(output, store.produce("produce", production, true))
        assertEquals(4, db.storageCapacityDao().history().size)
    }

    @Test fun recipeContextUsesOnlyCapacityKnowledgeAvailableAtItsCaptureAndEveryLaterReviewChangesTheRevision() = runBlocking {
        val (_, location, _) = seed()
        val reader = RecipeRoomStockSnapshotReader(db.foodDao())
        val beforeTime = Instant.ofEpochMilli(at)
        val before = requireNotNull(reader.read(beforeTime))
        at += 1000
        store.reviewStorageCapacity("check", input(location), true)
        assertEquals(before, reader.read(beforeTime))
        val after = requireNotNull(reader.read(Instant.ofEpochMilli(at)))
        assertNotEquals(before.revision, after.revision)
        assertEquals(before.lines, after.lines)
        at += 1000
        store.reviewStorageCapacity("same-facts-new-check", input(location), true)
        assertNotEquals(after.revision, reader.read(Instant.ofEpochMilli(at))!!.revision)
    }

    @Test fun reversedProductionInTheCapacityAncestryRequiresANewPhysicalCheckInsteadOfInventingFreeSpace() = runBlocking {
        val (version, location, batch) = seed()
        val recipe = store.saveRecipe("recipe", RecipeInput("Synthetischer Ansatz", 1000,
            listOf(RecipeItemInput(version, 100_000)), "Synthetische Zubereitung"))
        val first = store.reviewStorageCapacity("check", input(location), true)
        fun production(review: String) = ProductionInput(recipe, 1000, listOf(Allocation(batch, 100_000)), location,
            day, true, "Platz und Bedingungen tatsächlich geprüft", capacityEvidence = ProductionCapacityEvidence(review, 200, true))
        at++
        store.produce("produce-first", production(first), true)
        val second = db.storageCapacityDao().latest(location)!!
        at++
        store.produce("produce-second", production(second.id), true)
        val latest = db.storageCapacityDao().latest(location)!!
        assertEquals(1100L, StorageCapacityRules.trustedFree(latest, db.storageCapacityDao().history(), emptySet()))
        at++
        store.reverse("reverse-first", ReversalInput("produce-first", day, "Erfassung korrigiert; realen Platz neu prüfen"), true)
        val reversed = db.storageCapacityDao().reversedOperations().toSet()
        assertNull(StorageCapacityRules.trustedFree(latest, db.storageCapacityDao().history(), reversed))
        val count = db.foodDao().operationCount()
        reject { store.produce("unsafe-reuse", production(latest.id), true) }
        assertEquals(count, db.foodDao().operationCount())
        assertEquals(latest, db.storageCapacityDao().latest(location))
        at++
        val physical = store.reviewStorageCapacity("fresh-check", input(location, 2000, 700), true)
        assertEquals(1300L, StorageCapacityRules.trustedFree(db.storageCapacityDao().latest(location)!!,
            db.storageCapacityDao().history(), reversed))
        store.produce("new-actual", production(physical), true)
        assertEquals(1100L, StorageCapacityRules.trustedFree(db.storageCapacityDao().latest(location)!!,
            db.storageCapacityDao().history(), reversed))
    }

    @Test fun publishedRoom13BackupGetsOnlyEmptyNewCapacityHistoryAndKeepsItsOriginalDigestReplayAndLaterChecks() = runBlocking {
        val (_, location, _) = seed()
        val original14 = store.captureBackup("de.ernaehrung.demo", false, true)
        val original13 = original14.copy(databaseVersion = 13, identityHash = "ddbdbf0fb475ae9d3a5c9a40b4284aa3",
            tables = original14.tables.filter { it.name != "storage_capacity_reviews" })
        val digest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(original13))
        val target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        try {
            val destination = FoodStore(target, now = { at })
            assertTrue(destination.validateBackupForPreview(original13, "de.ernaehrung.demo"))
            assertFalse(destination.validateBackupForPreview(original14, "de.ernaehrung.demo"))
            reject { destination.restoreBackup("bad-schema", original13.copy(identityHash = "unknown"),
                BackupArchiveCodec.digest(BackupArchiveCodec.encode(original13.copy(identityHash = "unknown"))), "de.ernaehrung.demo", true) }
            assertEquals(0, target.foodDao().operationCount())
            val result = destination.restoreBackup("restore-13", original13, digest, "de.ernaehrung.demo", true)
            assertEquals(BackupRestoreStatus.RESTORED, result.status); assertEquals(digest, result.digest)
            assertTrue(target.storageCapacityDao().history().isEmpty())
            assertEquals(db.foodDao().allVersions(), target.foodDao().allVersions())
            assertEquals(db.foodDao().planningBatches(), target.foodDao().planningBatches())
            val check = destination.reviewStorageCapacity("new-physical-check", StorageCapacityInput(location, null, 2000, 0, "4,500", "Nach Restore vor Ort geprüft"), true)
            val count = target.foodDao().operationCount()
            assertEquals(BackupRestoreStatus.ALREADY_PRESENT,
                destination.restoreBackup("retry-13", original13, digest, "de.ernaehrung.demo", true).status)
            assertEquals(count, target.foodDao().operationCount()); assertEquals(check, target.storageCapacityDao().history().single().id)
        } finally { target.close() }
    }
}
