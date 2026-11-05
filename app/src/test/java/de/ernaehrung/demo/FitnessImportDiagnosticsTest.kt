package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FitnessImportDiagnosticsTest {
    @Test fun publicProviderErrorIsBoundToItsReadStageWithoutRetainingHealthTextOrCause() = runBlocking {
        val secret = "PRIVATE-HEALTH-RECORD-NOT-FOR-LOGS"
        val original = IllegalArgumentException(secret)
        val failure = try {
            FitnessImportDiagnostics.read(FitnessReadStage.CHANGE_TOKEN) { throw original }
        } catch (failure: FitnessReadFailure) { failure }
        assertEquals("HEALTH_READ_CHANGE_TOKEN_BAD_REQUEST", FitnessImportDiagnostics.code(failure))
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(secret))
        assertFalse(FitnessImportDiagnostics.explain(FitnessImportDiagnostics.code(failure)).contains(secret))
    }
    @Test fun storageAndProviderMessagesNeverBecomePersistentOrDisplayedDiagnosticPayload() {
        val secret = "private steps 12345 record-id medical-note"
        assertEquals("IMPORT_FAILED", FitnessImportDiagnostics.code(IOException(secret)))
        assertEquals("IMPORT_FAILED", FitnessImportDiagnostics.safeCode(secret))
        assertEquals("IMPORT_FAILED", FitnessImportDiagnostics.safeCode("HEALTH_READ_STEPS_OTHER_HC_" + secret))
        assertFalse(FitnessImportDiagnostics.explain(secret).contains(secret))
        assertEquals("HEALTH_RECORD_AFTER_CAPTURE", FitnessImportDiagnostics.code(IllegalArgumentException("HEALTH_RECORD_AFTER_CAPTURE")))
    }
    @Test fun cancellationAndActualPermissionFailureKeepTheirOriginalControlFlow() = runBlocking {
        for (expected in listOf(CancellationException("cancel"), SecurityException("private"), FitnessPermissionMissing())) {
            try {
                FitnessImportDiagnostics.read(FitnessReadStage.STEPS) { throw expected }
                fail("Expected cancellation/permission exception")
            } catch (actual: Exception) { assertSame(expected, actual) }
        }
    }
}
