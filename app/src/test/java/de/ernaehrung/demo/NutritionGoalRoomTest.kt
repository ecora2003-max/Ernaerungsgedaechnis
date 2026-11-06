package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.backup.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NutritionGoalRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private var at = Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
    private val ranges = listOf(NutritionGoalRange(Nutrient.PROTEIN, "9,0000", "11.000000000000000001"))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { at }
    }
    @After fun close() { db.close() }
    private suspend fun reject(action: suspend () -> Unit) {
        try { action(); fail("Operation must fail before any partial profile change") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun draft(key: String, date: String = "2026-11-06", values: List<NutritionGoalRange> = ranges): String =
        requireNotNull(store.proposeNutritionGoals(key, NutritionGoalDraftInput(store.readNutritionGoals().latest?.id,
            date, "Synthetische eigene Vorgabe", values), true).versionId)
    private suspend fun confirm(key: String, proposal: String): String = requireNotNull(store.confirmNutritionGoals(key,
        proposal, store.readNutritionGoals().versionBinding(proposal), true).versionId)

    @Test fun proposalIsInactiveUntilSeparateExactConfirmationAndRetriesNeverBookFoodOrChangeHistory() = runBlocking {
        val before = db.foodDao().operationCount()
        val input = NutritionGoalDraftInput(null, "2026-11-06", "Eigene Vorgabe", ranges)
        reject { store.proposeNutritionGoals("p", input, false) }
        val proposal = requireNotNull(store.proposeNutritionGoals("p", input, true).versionId)
        var data = store.readNutritionGoals()
        assertNull(data.active("2026-11-06", at))
        val original = data.versions.single()
        assertEquals(ranges, data.ranges(proposal))
        reject { store.confirmNutritionGoals("c", proposal, data.versionBinding(proposal), false) }
        reject { store.confirmNutritionGoals("c", proposal, "model-claimed-digest", true) }
        val approved = confirm("c", proposal)
        data = store.readNutritionGoals()
        assertEquals(original, data.versions.first())
        assertEquals(approved, data.active("2026-11-06", at)?.id)
        assertEquals(proposal, data.latest?.proposalId)
        assertEquals(proposal, store.proposeNutritionGoals("p", input, true).versionId)
        assertEquals(approved, confirm("c", proposal))
        reject { store.proposeNutritionGoals("p", input.copy(reason = "Geändert"), true) }
        assertEquals(2, store.readNutritionGoals().versions.size)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().activeConsumptionCount())
        assertTrue(db.foodDao().planningBatches().isEmpty())
    }

    @Test fun datedFutureChangesAndExplicitEmptyWithdrawalNeverRewriteAnEarlierDayOrActivateAnUnconfirmedProposal() = runBlocking {
        val original = confirm("c1", draft("p1"))
        at++
        val future = confirm("c2", draft("p2", "2026-11-08", listOf(NutritionGoalRange(Nutrient.PROTEIN, "0", "0"))))
        at++
        val newer = draft("p3", "2026-11-06", listOf(NutritionGoalRange(Nutrient.PROTEIN, "12", null)))
        assertEquals(original, store.readNutritionGoals().active("2026-11-06", at)?.id)
        val current = confirm("c3", newer)
        assertEquals(current, store.readNutritionGoals().active("2026-11-06", at)?.id)
        assertEquals(future, store.readNutritionGoals().active("2026-11-08", at)?.id)
        assertNull(store.readNutritionGoals().active("2026-11-05", at))
        val clear = confirm("c4", draft("p4", "2026-11-08", emptyList()))
        assertEquals(clear, store.readNutritionGoals().active("2026-11-08", at)?.id)
        assertTrue(store.readNutritionGoals().ranges(clear).isEmpty())
        assertEquals(current, store.readNutritionGoals().active("2026-11-07", at)?.id)
        reject { draft("past", "2026-11-05") }
    }

    @Test fun changedProposalClockRollbackAndExpiredEffectiveDayCannotGainAStaleConfirmation() = runBlocking {
        val first = draft("p1")
        val binding = store.readNutritionGoals().versionBinding(first)
        at++
        val latest = draft("p2")
        reject { store.confirmNutritionGoals("stale", first, binding, true) }
        val count = store.readNutritionGoals().versions.size
        at -= 1000
        reject { confirm("backwards", latest) }
        at = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
        reject { confirm("expired", latest) }
        assertEquals(count, store.readNutritionGoals().versions.size)
        assertNull(store.readNutritionGoals().active("2026-11-07", at))
    }

    @Test fun explicitPrivateErasureRedactsGoalRunsAndOldRetriesCannotResurrectOrDeleteNewerGoals() = runBlocking {
        val food = store.rememberProduct("food", ProductInput("Erhaltenes Lebensmittel", source = Source.USER, quality = Quality.CONFIRMED))
        val foodCount = db.foodDao().operationCount()
        val input = NutritionGoalDraftInput(null, "2026-11-06", "Privater synthetischer Grund", ranges)
        val p = requireNotNull(store.proposeNutritionGoals("p", input, true).versionId)
        val binding = store.readNutritionGoals().versionBinding(p)
        store.confirmNutritionGoals("c", p, binding, true)
        val oldToken = store.readNutritionGoals().token
        reject { store.eraseNutritionGoals("erase", oldToken, false) }
        reject { store.eraseNutritionGoals("erase", "old-snapshot", true) }
        store.eraseNutritionGoals("erase", oldToken, true)
        assertTrue(store.readNutritionGoals().versions.isEmpty())
        assertTrue(db.nutritionGoalDao().values().isEmpty())
        assertNull(db.nutritionGoalDao().run("p")?.payloadHash)
        assertNull(db.nutritionGoalDao().run("c")?.versionId)
        assertTrue(store.proposeNutritionGoals("p", input, true).erased)
        assertTrue(store.confirmNutritionGoals("c", p, binding, true).erased)
        val fresh = draft("fresh")
        store.eraseNutritionGoals("erase", oldToken, true)
        assertEquals(fresh, store.readNutritionGoals().latest?.id)
        reject { store.eraseNutritionGoals("new-delete", oldToken, true) }
        assertNotNull(db.foodDao().version(food))
        assertEquals(foodCount, db.foodDao().operationCount())
    }

    @Test fun profileBackupScopeIsExplicitAndPublishedRoom14ArchiveAddsOnlyEmptyGoalsAndKeepsItsOriginalRetryDigest() = runBlocking {
        store.rememberProduct("food", ProductInput("Unverändertes Lebensmittel", source = Source.USER, quality = Quality.CONFIRMED))
        val beforeGoals = store.captureBackup("de.ernaehrung.demo", true, true)
        val goalNames = setOf("nutrition_goal_runs", "nutrition_goal_versions", "nutrition_goal_values")
        val old = beforeGoals.copy(databaseVersion = 14, identityHash = "adf43266f88da0de1632891687871124",
            tables = beforeGoals.tables.filterNot { it.name in goalNames })
        val p = draft("profile")
        val full = store.captureBackup("de.ernaehrung.demo", true, true)
        assertTrue(full.tables.first { it.name == "nutrition_goal_versions" }.rows.isNotEmpty())
        val privateDefault = store.captureBackup("de.ernaehrung.demo", false, true)
        goalNames.forEach { name -> assertTrue(privateDefault.tables.first { it.name == name }.rows.isEmpty()) }
        assertEquals(p, store.readNutritionGoals().latest?.id)
        val target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        try {
            val destination = FoodStore(target) { at }
            val digest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(old))
            assertEquals(BackupRestoreStatus.RESTORED, destination.restoreBackup("restore", old, digest, "de.ernaehrung.demo", true).status)
            assertTrue(destination.readNutritionGoals().versions.isEmpty())
            val fresh = requireNotNull(destination.proposeNutritionGoals("fresh", NutritionGoalDraftInput(null,
                "2026-11-06", "Neue eigene Vorgabe", ranges), true).versionId)
            assertEquals(BackupRestoreStatus.ALREADY_PRESENT, destination.restoreBackup("restore", old, digest, "de.ernaehrung.demo", true).status)
            assertEquals(fresh, destination.readNutritionGoals().latest?.id)
        } finally { target.close() }
    }

    @Test fun malformedPersonalRangeInAnOtherwiseTypedBackupRollsBackEveryRestoredFoodAndProfileRow() = runBlocking {
        store.rememberProduct("food", ProductInput("Unverändertes Lebensmittel", source = Source.USER, quality = Quality.CONFIRMED))
        draft("proposal")
        val valid = store.captureBackup("de.ernaehrung.demo", true, true)
        val malformed = valid.copy(tables = valid.tables.map { table ->
            if (table.name != "nutrition_goal_values") table else {
                val index = table.columns.indexOf("minimumText")
                require(index >= 0)
                table.copy(rows = table.rows.map { row -> row.toMutableList().apply { this[index] = BackupCell.Text("not-a-number") } })
            }
        })
        val target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        try {
            val destination = FoodStore(target) { at }
            val digest = BackupArchiveCodec.digest(BackupArchiveCodec.encode(malformed))
            reject { destination.restoreBackup("malformed", malformed, digest, "de.ernaehrung.demo", true) }
            assertEquals(0, target.foodDao().operationCount())
            assertTrue(target.foodDao().allVersions().isEmpty())
            assertTrue(target.nutritionGoalDao().versions().isEmpty())
            assertTrue(target.nutritionGoalDao().values().isEmpty())
            assertNull(target.nutritionGoalDao().run("proposal"))
        } finally { target.close() }
    }
}
