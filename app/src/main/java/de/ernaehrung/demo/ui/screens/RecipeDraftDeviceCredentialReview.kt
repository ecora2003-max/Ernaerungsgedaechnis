package de.ernaehrung.demo.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate
import de.ernaehrung.demo.data.local.RecipeInput

/** A device-authentication result is usable only for its original live UI preview. */
internal data class RecipeCredentialChallenge(
    val digest: String,
    val sessionId: String,
    val reviewedInput: RecipeInput,
    val resetVersion: Int,
    val issuedAtElapsedRealtimeMs: Long
)

internal object RecipeCredentialResultGate {
    fun accepts(
        challenge: RecipeCredentialChallenge?,
        resultCode: Int,
        review: RecipeDraftHostGate.Review?,
        sessionId: String?,
        resetVersion: Int,
        actionEnabled: Boolean,
        deviceSecure: Boolean,
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): Boolean = challenge != null &&
        resultCode == Activity.RESULT_OK &&
        // A successful result cannot be reused if the secure device lock was removed
        // while the system confirmation Activity was in flight.
        deviceSecure &&
        // Elapsed realtime cannot be set by a wall-clock change. A delayed OS
        // callback or a challenge from a different boot must not approve a draft.
        challenge.issuedAtElapsedRealtimeMs >= 0L &&
        nowElapsedRealtimeMs >= challenge.issuedAtElapsedRealtimeMs &&
        nowElapsedRealtimeMs - challenge.issuedAtElapsedRealtimeMs <= 300_000L &&
        actionEnabled &&
        sessionId != null &&
        sessionId == challenge.sessionId &&
        resetVersion == challenge.resetVersion &&
        review?.status == RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
        review.input != null &&
        review.input == challenge.reviewedInput &&
        review.approvalBasisDigest == challenge.digest &&
        Regex("[0-9a-f]{64}").matches(challenge.digest)
}

/**
 * The actual Android UI requires an independent system credential confirmation
 * before invoking the existing host-side consent checkpoint. A checkbox, model
 * field or caller Boolean alone never launches that checkpoint from this screen.
 *
 * The Activity result is transient and is NOT a cryptographic FoodStore
 * attestation. Durable, independently verifiable save provenance and real
 * device/privacy E2E remain separate integration gates.
 */
@Composable
internal fun RecipeDraftDeviceCredentialReview(
    review: RecipeDraftHostGate.Review,
    sessionKey: String?,
    resetVersion: Int,
    actionEnabled: Boolean,
    onAuthenticated: (String, String?) -> Unit
) {
    val context = LocalContext.current
    val latestReview by rememberUpdatedState(review)
    val latestSession by rememberUpdatedState(sessionKey)
    val latestEnabled by rememberUpdatedState(actionEnabled)
    val latestResetVersion by rememberUpdatedState(resetVersion)
    val latestCallback by rememberUpdatedState(onAuthenticated)
    // A host transition invalidates even an in-flight OS result. In particular,
    // a changed preview/session or disabled-then-reenabled action cannot reuse it.
    var challenge by remember(sessionKey, resetVersion, review, actionEnabled) {
        mutableStateOf<RecipeCredentialChallenge?>(null)
    }
    var retryVersion by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val completed = challenge
        challenge = null // Consume every result, including cancellation and stale results.
        val stillSecure = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceSecure == true
        if (RecipeCredentialResultGate.accepts(
                completed, result.resultCode, latestReview, latestSession,
                latestResetVersion, latestEnabled, stillSecure
            )) {
            error = null
            latestCallback(requireNotNull(completed).digest, completed.sessionId)
        } else {
            error = "Gerätebestätigung abgebrochen oder Rezeptvorschau inzwischen verändert."
            retryVersion++ // A retry needs a new checkbox and explicit button gesture.
        }
    }
    RecipeDraftConsentReview(
        review = review,
        sessionKey = sessionKey,
        resetVersion = resetVersion + retryVersion,
        actionEnabled = actionEnabled && challenge == null
    ) { digest, renderedSession ->
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val intent = if (keyguard?.isDeviceSecure == true) {
            keyguard.createConfirmDeviceCredentialIntent(
                "Rezeptentwurf bestätigen",
                "Gerätesperre bestätigen, um genau diese Rezeptvorschau freizugeben."
            )
        } else null
        val candidate = if (renderedSession != null && review.input != null) {
            RecipeCredentialChallenge(digest, renderedSession, review.input, resetVersion,
                SystemClock.elapsedRealtime())
        } else null
        if (intent == null ||
            !RecipeCredentialResultGate.accepts(
                candidate, Activity.RESULT_OK, review, sessionKey,
                resetVersion, actionEnabled, keyguard?.isDeviceSecure == true
            )
        ) {
            error = "Eine sichere Gerätesperre und eine aktuelle Rezeptvorschau sind erforderlich."
            retryVersion++
        } else {
            challenge = requireNotNull(candidate)
            error = null
            try {
                launcher.launch(intent)
            } catch (_: RuntimeException) {
                challenge = null
                error = "Gerätebestätigung konnte nicht gestartet werden."
                retryVersion++
            }
        }
    }
    error?.let { Text(it) }
}
