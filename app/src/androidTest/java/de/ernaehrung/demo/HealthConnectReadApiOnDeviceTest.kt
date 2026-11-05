package de.ernaehrung.demo

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.FoodDatabase
import kotlinx.coroutines.runBlocking
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
        try {
            grantedHere.forEach { instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it) }
            assertTrue(source.grantedPermissions().containsAll(source.requiredPermissions))
            val snapshot = source.readSnapshot()
            FitnessImportKernel.validate(snapshot, System.currentTimeMillis())
            assertTrue("No synthetic Samsung application/data is inserted", snapshot.records.isEmpty())
            // Preserve whichever absence representation the actual provider
            // supplies; a known zero must never be rewritten into null.
            assertTrue(snapshot.aggregatedSteps == null || snapshot.aggregatedSteps == 0L)
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
