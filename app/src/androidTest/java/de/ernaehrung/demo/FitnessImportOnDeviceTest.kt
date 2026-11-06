package de.ernaehrung.demo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real default Android UI/SQLite/offline proof with a SYNTHETIC read-only source.
 * Not an actual Health Connect permission/record or physical Watch-4 test.
 */
@RunWith(AndroidJUnit4::class)
class FitnessImportOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()

    @Test fun syntheticCacheAppearsInDefaultFitnessUiSurvivesRecreationAndDeletesWithoutFoodWrites() = runBlocking {
        assertTrue("Synthetic cleanup requires a disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull((target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val store = FoodStore(db)
        val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
        assertNull("Refuse to overwrite any existing health consent", db.healthDao().state(origin))
        assertTrue("Use an empty synthetic health sandbox", db.healthDao().history(origin).isEmpty())
        val token = "synthetic-${UUID.randomUUID()}"
        val before = store.operations().first()
        val stockBefore = db.foodDao().planningBatches()
        val mealsBefore = db.foodDao().activeConsumptionCount()
        val at = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val records = listOf(
            FitnessSourceRecord("$token-steps", FitnessRecordKind.STEPS, origin, at - 60_000L,
                at - 1L, null, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0, steps = 500L),
            FitnessSourceRecord("$token-workout", FitnessRecordKind.WORKOUT, origin, at - 1_800_000L,
                at - 1L, null, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0,
                exerciseType = 0, title = "Synthetisches Testtraining"),
            FitnessSourceRecord("$token-missing", FitnessRecordKind.WORKOUT, origin, at - 3_600_000L,
                at - 1_800_001L, null, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0,
                exerciseType = 0, title = "Synthetisches verschwundenes Training"))
        var currentRecords = records
        var reads = 0
        val provider = object : FitnessHealthSource {
            override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
            override fun availability() = FitnessHealthSource.Availability.AVAILABLE
            override suspend fun grantedPermissions() = requiredPermissions
            override suspend fun readSnapshot(): FitnessHealthSnapshot {
                reads++
                return FitnessHealthSnapshot(origin, at - Duration.ofDays(28).toMillis(), at, at + reads - 1L,
                    currentRecords, day.toString(), zone.id, day.atStartOfDay(zone).toInstant().toEpochMilli(), at, 321L)
            }
        }
        fun waitTag(tag: String) = compose.waitUntil(25_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        try {
            store.enableFitnessImport("$token-enable", true, true)
            store.syncFitness("$token-sync", provider)
            store.syncFitness("$token-sync", provider)
            assertEquals(1, reads)
            currentRecords = records.filterNot { it.externalId == "$token-missing" }
            store.syncFitness("$token-rescan", provider)
            // Only the local synthetic cache is tested. Do not enable the real
            // provider when the default screen resumes on this CI emulator.
            store.enableFitnessImport("$token-stop", false, true)
            waitTag("workspace_tab_MORE")
            compose.onNodeWithTag("workspace_tab_MORE").performClick()
            compose.onNodeWithTag("workspace_go_FITNESS").performScrollTo().performClick()
            waitTag("fitness_daily_steps")
            compose.onNodeWithTag("fitness_daily_steps").performScrollTo().assertTextContains("321", substring = true)
            compose.onNodeWithTag("fitness_record_counts").assertTextContains("Trainings: 1", substring = true)
            compose.onNodeWithTag("fitness_record_counts").assertTextContains("Schrittintervalle: 1", substring = true)
            compose.onNodeWithTag("fitness_sport_$token-workout").performScrollTo()
                .assertTextEquals("Trainingsart: Sonstiges Training")
            compose.onNodeWithTag("fitness_duration_WORKOUT_$token-workout").performScrollTo()
                .assertTextContains("29 min 59,999 s", substring = true)
            compose.onNodeWithTag("fitness_missing_records").performScrollTo().assertTextContains("1", substring = true)
            compose.onNodeWithTag("fitness_missing_$token-missing").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Gespeicherte Änderungshistorie").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Gespeicherte Änderungshistorie").performScrollTo().assertIsDisplayed()
            assertEquals(2, db.healthDao().historyFor(origin, FitnessRecordKind.WORKOUT.name, "$token-missing").size)
            assertEquals(2, reads)
            compose.activityRule.scenario.recreate()
            waitTag("fitness_daily_steps")
            compose.onNodeWithTag("fitness_daily_steps").performScrollTo().assertTextContains("321", substring = true)
            val reopened = Room.databaseBuilder(target, FoodDatabase::class.java, FoodDatabase.TEST_DATABASE_NAME)
                .addMigrations(FoodMigrations.MIGRATION_8_9, FoodMigrations.MIGRATION_9_10, FoodMigrations.MIGRATION_10_11, FoodMigrations.MIGRATION_11_12, FoodMigrations.MIGRATION_12_13).build()
            try {
                assertEquals(2, reopened.healthDao().latest(origin).count { it.visible })
                assertEquals(false, reopened.healthDao().state(origin)?.enabled)
            } finally { reopened.close() }
            compose.onNodeWithTag("fitness_delete").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("fitness_delete_opt_in").performScrollTo().performClick()
            compose.onNodeWithTag("fitness_delete").performScrollTo().performClick()
            waitTag("fitness_no_cache")
            assertTrue(db.healthDao().history(origin).isEmpty())
            assertEquals(before, store.operations().first())
            assertEquals(stockBefore, db.foodDao().planningBatches())
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            assertEquals(0, target.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
            assertNull((target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork)
            assertEquals(2, reads)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("fitness-flow")
            throw failure
        } finally {
            // We refused any pre-existing health state above. Never purge food.
            store.purgeLocalFitnessImport("$token-cleanup", true)
        }
    }
}
