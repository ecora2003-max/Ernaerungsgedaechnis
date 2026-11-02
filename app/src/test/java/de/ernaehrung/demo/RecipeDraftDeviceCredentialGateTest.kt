package de.ernaehrung.demo

import android.app.Activity
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.RecipeInput
import de.ernaehrung.demo.data.local.RecipeItemInput
import de.ernaehrung.demo.ui.screens.RecipeCredentialChallenge
import de.ernaehrung.demo.ui.screens.RecipeCredentialResultGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeDraftDeviceCredentialGateTest {
    private val digest = "a".repeat(64)
    private val ready = Gate.Review(
        Gate.Status.READY_FOR_EXPLICIT_SAVE, emptySet(), emptySet(),
        RecipeInput("Reis", 2_000L, listOf(RecipeItemInput("version", 200_000L)), "Kochen"),
        approvalBasisDigest = digest
    )
    private val challenge get() = RecipeCredentialChallenge(digest, "session-1",
        requireNotNull(ready.input), 7, 1_000L)

    private fun accepts(
        code: Int = Activity.RESULT_OK,
        current: Gate.Review? = ready,
        session: String? = "session-1",
        resetVersion: Int = 7,
        enabled: Boolean = true,
        deviceSecure: Boolean = true,
        pending: RecipeCredentialChallenge? = challenge,
        nowElapsedRealtimeMs: Long = 1_000L
    ) = RecipeCredentialResultGate.accepts(pending, code, current, session,
        resetVersion, enabled, deviceSecure, nowElapsedRealtimeMs)

    @Test fun successfulSystemResultMatchesOnlyLiveBoundSession() {
        assertTrue(accepts())
    }

    @Test fun credentialResultExpiresAfterFiveMinutesOfMonotonicTime() {
        assertTrue(accepts(nowElapsedRealtimeMs = 301_000L))
        assertFalse(accepts(nowElapsedRealtimeMs = 301_001L))
        assertFalse(accepts(nowElapsedRealtimeMs = 999L))
        assertFalse(accepts(nowElapsedRealtimeMs = -1L))
    }

    @Test fun wallClockOrForgedChallengeAgeCannotExtendApproval() {
        assertFalse(accepts(pending = challenge.copy(issuedAtElapsedRealtimeMs = -1L)))
        assertFalse(accepts(pending = challenge.copy(
            issuedAtElapsedRealtimeMs = 2_000L)))
        assertFalse(accepts(nowElapsedRealtimeMs = Long.MAX_VALUE))
    }

    @Test fun removingSecureDeviceLockWhileCredentialDialogIsOpenInvalidatesResult() {
        assertTrue(accepts(deviceSecure = true))
        assertFalse(accepts(deviceSecure = false))
    }

    @Test fun cancelledOrAbsentSystemResultNeverAuthorizesConsent() {
        assertFalse(accepts(code = Activity.RESULT_CANCELED))
        assertFalse(accepts(code = 123))
        assertFalse(accepts(pending = null))
    }

    @Test fun changedSessionOrPreviewInvalidatesLateAuthenticationResult() {
        assertFalse(accepts(session = "session-2"))
        assertFalse(accepts(session = null))
        assertFalse(accepts(current = ready.copy(approvalBasisDigest = "b".repeat(64))))
        assertFalse(accepts(current = ready.copy(input = null)))
        assertFalse(accepts(current = ready.copy(input =
            requireNotNull(ready.input).copy(name = "Anderes Rezept"))))
        assertFalse(accepts(current = ready.copy(status = Gate.Status.REVIEW_REQUIRED)))
        assertFalse(accepts(current = null))
    }

    @Test fun busyOrAlreadyRecordedHostActionCannotReuseSystemResult() {
        assertFalse(accepts(enabled = false))
        assertFalse(accepts(resetVersion = 8))
        assertFalse(accepts(pending = challenge.copy(resetVersion = 6)))
        assertFalse(accepts(pending = challenge.copy(reviewedInput =
            requireNotNull(ready.input).copy(steps = "Andere Schritte"))))
        assertFalse(accepts(pending = challenge.copy(digest = "not-a-digest")))
    }
}
