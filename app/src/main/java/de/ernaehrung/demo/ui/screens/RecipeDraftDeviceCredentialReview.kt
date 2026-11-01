package de.ernaehrung.demo.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
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

/** A device-authentication result is usable only for its original live UI preview. */
internal data class RecipeCredentialChallenge(val digest: String, val sessionId: String)

internal object RecipeCredentialResultGate {
    fun accepts(
        challenge: RecipeCredentialChallenge?,
        resultCode: Int,
        review: RecipeDraftHostGate.Review?,
        sessionId: String?,
        actionEnabled: Boolean
    ): Boolean = challenge != null &&
        resultCode == Activity.RESULT_OK &&
        actionEnabled &&
        sessionId != null &&
        sessionId == challenge.sessionId &&
        review?.status == RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
        review.input != null &&
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
    val latestCallback by rememberUpdatedState(onAuthenticated)
    var challenge by remember { mutableStateOf<RecipeCredentialChallenge?>(null) }
    var retryVersion by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val completed = challenge
        challenge = null // Consume every result, including cancellation and stale results.
        if (RecipeCredentialResultGate.accepts(
                completed, result.resultCode, latestReview, latestSession, latestEnabled
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
        if (renderedSession == null || intent == null ||
            !RecipeCredentialResultGate.accepts(
                RecipeCredentialChallenge(digest, renderedSession),
                Activity.RESULT_OK, review, sessionKey, actionEnabled
            )
        ) {
            error = "Eine sichere Gerätesperre und eine aktuelle Rezeptvorschau sind erforderlich."
            retryVersion++
        } else {
            challenge = RecipeCredentialChallenge(digest, renderedSession)
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
