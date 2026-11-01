package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.local.Amounts
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate

/**
 * Explicit UI gesture for a previously host-bound, read-only recipe preview.
 *
 * This component does not write FoodStore or attest the identity of a person.
 * Its callback must be wired to a separately revalidating, durable consent
 * checkpoint by the host. Never pass model-provided consent to that checkpoint.
 */
@Composable
internal fun RecipeDraftConsentReview(
    review: RecipeDraftHostGate.Review,
    sessionKey: String? = null,
    actionEnabled: Boolean = true,
    onConfirm: (approvalBasisDigest: String, sessionId: String?) -> Unit
) {
    val digest = review.approvalBasisDigest
    val input = review.input
    val ready = review.status == RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
        input != null && digest != null && Regex("[0-9a-f]{64}").matches(digest)
    // Any change to the host-issued approval basis invalidates the gesture.
    // A pending/recorded host action must invalidate an old checked gesture.
    var checked by remember(sessionKey, digest, input, ready, actionEnabled) { mutableStateOf(false) }
    var submitted by remember(sessionKey, digest, input, ready, actionEnabled) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().testTag("recipe_draft_consent_review")) {
        Text("Rezeptentwurf prüfen · keine Herstellung oder Verzehrbuchung")
        if (!ready) {
            Text("Nicht freigegeben: aktuelle geprüfte Rezeptvorschau erforderlich.")
            (review.blockers + review.gaps).sorted().forEach { Text(it) }
            return@Column
        }
        val recipe = requireNotNull(input)
        Text(recipe.name)
        Text("Soll-Portionen: ${Amounts.format(recipe.yieldPortions)}")
        recipe.ingredients.forEach { ingredient ->
            Text("Artikelversion ${ingredient.versionId} · Menge in bestätigter Basiseinheit: ${ingredient.amount?.let(Amounts::format) ?: "unbekannt"}")
        }
        Text(recipe.steps)
        Row {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (actionEnabled && !submitted) checked = it },
                enabled = actionEnabled && !submitted,
                modifier = Modifier.testTag("recipe_draft_consent_checkbox")
            )
            Text("Diese konkrete Rezeptvorschau und die geprüften Zutaten ausdrücklich übernehmen.",
                Modifier.padding(top = 12.dp))
        }
        Button(
            onClick = {
                if (actionEnabled && checked && !submitted) {
                    submitted = true
                    // Bind the exact session rendered for this gesture, never a later UI state.
                    onConfirm(requireNotNull(digest), sessionKey)
                }
            },
            enabled = actionEnabled && checked && !submitted,
            modifier = Modifier.testTag("recipe_draft_consent_confirm")
        ) { Text("Rezeptentwurf bestätigen") }
    }
}
