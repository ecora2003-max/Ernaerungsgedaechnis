package de.ernaehrung.demo

import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.FoodDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Instant
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real API35 system provider with no personal records. Permission grants here
 * are instrumentation grants, NOT a physical Watch or real-user dialog proof.
 * Never run this permission-changing fixture on a personal phone.
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectReadApiOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    @Test fun actualSystemProviderSupportsTheSamsungFilteredReadPipelineWithoutAnySourceRecords() = runBlocking {
        assertTrue("Disposable emulator only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        compose.waitForIdle() // Reads require actual foreground; no background permission.
        val source = AndroidHealthConnectSource(target)
        assertEquals("Actual system provider is required; never substitute a fake", FitnessHealthSource.Availability.AVAILABLE, source.availability())
        val initial = source.grantedPermissions()
        val food = FoodDatabase.localSandbox(target).foodDao().operationCount()
        val grantedHere = source.requiredPermissions - initial
        var readinessRetries = 0
        try {
            grantedHere.forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
            assertTrue(source.grantedPermissions().containsAll(source.requiredPermissions))
            // A fresh instrumentation grant can become visible to PackageManager
            // before the system health provider/AppOps accepts it (observed on
            // API35). Only our own fresh grant gets this bounded readiness wait.
            // Every attempt reads the ACTUAL provider; no data/consent is written
            // or substituted, and an existing/revoked permission still fails.
            val snapshot = withTimeout(15_000) {
                val deadline = SystemClock.elapsedRealtime() + 14_000L
                var result: FitnessHealthSnapshot? = null
                while (result == null) {
                    try { result = source.readSnapshot() }
                    catch (failure: SecurityException) {
                        if (grantedHere.isEmpty() || SystemClock.elapsedRealtime() >= deadline ||
                            !source.grantedPermissions().containsAll(source.requiredPermissions)) throw failure
                        readinessRetries++
                        delay(200)
                    }
                }
                result
            }
            println("HEALTH_PROVIDER_FRESH_GRANT_READINESS retries=$readinessRetries")
            FitnessImportKernel.validate(snapshot, System.currentTimeMillis())
            assertTrue("No synthetic Samsung application/data is inserted", snapshot.records.isEmpty())
            // Preserve whichever absence representation the actual provider
            // supplies; a known zero must never be rewritten into null.
            assertTrue(snapshot.aggregatedSteps == null || snapshot.aggregatedSteps == 0L)
            val at = System.currentTimeMillis()
            val targetId = FitnessWorkoutDurationTarget("synthetic-absent-system-session", 1, "0".repeat(64))
            val durationQuery = FitnessWorkoutDurationQuery(targetId, at - 60_000, at - 1, at)
            val duration = source.readWorkoutDuration(durationQuery)
            FitnessWorkoutDurationPolicy.validate(durationQuery, duration, System.currentTimeMillis())
            assertEquals(FitnessWorkoutDurationStatus.SOURCE_MISSING, duration.status)
            assertNull(duration.durationMillis); assertTrue(duration.records.isEmpty())
            // Real empty system-provider metric capability, no invented Samsung
            // session and no forced 0 for absence. Production skips attribution
            // when the selected source record is missing, as checked above.
            val aggregate = HealthConnectClient.getOrCreate(target).aggregate(AggregateRequest(
                setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL),
                TimeRangeFilter.between(Instant.ofEpochMilli(at - 60_000), Instant.ofEpochMilli(at - 1)),
                dataOriginFilter = setOf(DataOrigin(FitnessHealthPolicy.SAMSUNG_PACKAGE))))
            val exerciseMillis = aggregate[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMillis()
            assertTrue(exerciseMillis == null || exerciseMillis == 0L)
            // Actual reads of every optional type, with minimal selected read
            // rights and NO older-history request on unsupported system builds.
            val extra = AndroidHealthExtraSource(target)
            val query = HealthExtraKernel.plan(HealthExtraSelection(HealthExtraKind.entries.toSet()),
                System.currentTimeMillis(), java.time.ZoneId.systemDefault().id)
            assertFalse(query.needsHistory)
            assertFalse(HealthExtraKernel.HISTORY_PERMISSION in extra.requiredPermissions(query))
            val extraGrants = extra.requiredPermissions(query) - extra.grantedPermissions()
            extraGrants.forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
            val extras = withTimeout(15_000) {
                val deadline = SystemClock.elapsedRealtime() + 14_000L
                var result: HealthExtraSnapshot? = null
                while (result == null) {
                    try { result = extra.readSnapshot(query) }
                    catch (failure: SecurityException) {
                        if (extraGrants.isEmpty() || SystemClock.elapsedRealtime() >= deadline ||
                            !extra.grantedPermissions().containsAll(extra.requiredPermissions(query))) throw failure
                        delay(200)
                    }
                }
                result
            }
            HealthExtraKernel.validate(extras, query, System.currentTimeMillis())
            assertTrue(extras.records.isEmpty())
            assertTrue(extras.stepDay!!.steps == null || extras.stepDay.steps == 0L)
            println("HEALTH_EXTRA_REAL_EMPTY_PROVIDER_PASS: seven record types and all-source step-day; historyAvailable=${extra.historyAvailable()}; no older-history query")
            assertEquals(food, FoodDatabase.localSandbox(target).foodDao().operationCount())
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("health-api-flow")
            throw failure
        }
        // The runner's disposable emulator is destroyed after this suite. Do
        // not revoke from the target instrumentation process: Android may kill
        // that process and suppress all remaining tests/results on revocation.
        // No local import consent or provider record is created by this test.
    }
}
