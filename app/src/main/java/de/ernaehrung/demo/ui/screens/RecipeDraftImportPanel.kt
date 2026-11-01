package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.integration.RecipeDraftManualReview
import de.ernaehrung.demo.data.integration.RecipeDraftV1Import
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.RecipeDraftEncryptedImportCheckpoint
import de.ernaehrung.demo.data.local.PrivateRecipeDraftEncryptedImportCheckpoint
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import de.ernaehrung.demo.ui.Choices
import de.ernaehrung.demo.ui.Fold
import de.ernaehrung.demo.ui.Picker
import java.time.OffsetDateTime

/**
 * Manual UI acquisition of independent catalog/mapping/state/basis/allergen facts.
 * JSON remains untrusted and in memory. No model flag can create consent or a booking.
 * The final consent is a separate gesture in RecipeDraftConsentReview.
 */
@Composable
internal fun RecipeDraftImportPanel(
    catalog: List<ProductVersionEntity>,
    editingEnabled: Boolean = true,
    recordedDigest: String? = null,
    checkpointStore: RecipeDraftEncryptedImportCheckpoint? = null,
    onInvalidate: () -> Unit,
    onDiscardPending: () -> Unit,
    onReview: (String, Gate.Request,
        Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        Map<String, Gate.State>) -> Unit
) {
    val context = LocalContext.current
    val checkpoint = checkpointStore ?: remember(context) {
        PrivateRecipeDraftEncryptedImportCheckpoint(context)
    }
    // A durable consent is authoritative; no longer retain a draft checkpoint.
    LaunchedEffect(recordedDigest) { if (recordedDigest != null) checkpoint.discard() }
    var json by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf<RecipeDraftV1Import.Imported?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var versionIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var bases by remember { mutableStateOf<Map<String, Gate.Basis>>(emptyMap()) }
    var states by remember { mutableStateOf<Map<String, Gate.State>>(emptyMap()) }
    var allergenAnswers by remember { mutableStateOf<Map<Pair<String, String>, Boolean?>>(emptyMap()) }
    var restrictionsText by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    fun invalidate() { reviewed = false; onInvalidate() }
    val eligible = catalog.filter {
        it.source != Source.DEMO && it.quality in setOf(Quality.CONFIRMED, Quality.SOURCED)
    }
    val labels = eligible.associate { it.id to "${it.name} · v${it.revision} · ${it.id}" }

    Fold("JSON-Rezeptentwurf importieren und manuell prüfen") {
        if (!editingEnabled) {
            Text("Eine Rezeptbestätigung wird gerade verarbeitet. Änderungen erst nach Abschluss möglich.")
            return@Fold
        }
        Text("Nur lokale Vorschau. Modellangaben sind keine Produkt-, Mengen-, Zustands- oder Allergennachweise. Es wird nichts gebucht.")
        Text("Ohne ausdrückliche Aktion wird kein JSON gespeichert. Optional nur JSON für 24 Stunden verschlüsselt im privaten No-Backup-Speicher ablegen; Katalog-, Zustands- und Allergennachweise werden nie übernommen und müssen erneut geprüft werden.")
        Row {
            Button(
                enabled = json.isNotBlank(),
                modifier = Modifier.testTag("recipe_draft_encrypted_save"),
                onClick = {
                    try {
                        checkpoint.save(json)
                        error = "JSON verschlüsselt vorgemerkt (maximal 24 Stunden). Kein Prüfbeleg und keine Bestätigung."
                    } catch (_: Exception) {
                        error = "Verschlüsselte Speicherung nicht möglich. Kein unverschlüsselter Ersatz."
                    }
                }
            ) { Text("JSON verschlüsselt merken") }
            Button(
                modifier = Modifier.testTag("recipe_draft_encrypted_restore"),
                onClick = {
                    val saved = checkpoint.load()
                    if (saved == null) {
                        error = "Kein gültiger verschlüsselter Entwurf vorhanden."
                    } else {
                        invalidate()
                        json = saved
                        imported = null
                        versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
                        allergenAnswers = emptyMap(); restrictionsText = ""; reviewed = false
                        error = "JSON wiederhergestellt. Alle Nachweise neu prüfen und erneut ausdrücklich bestätigen."
                    }
                }
            ) { Text("Gemerkten JSON-Entwurf laden") }
        }
        Button(
            onClick = {
                invalidate()
                json = ""
                imported = null
                error = null
                versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
                allergenAnswers = emptyMap(); restrictionsText = ""
                checkpoint.discard()
                onDiscardPending()
            },
            modifier = Modifier.testTag("recipe_draft_discard_pending")
        ) { Text("Unbestätigten Rezeptvorgang verwerfen") }
        OutlinedTextField(
            value = json,
            onValueChange = { next ->
                checkpoint.discard()
                invalidate()
                imported = null
                error = null
                versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
                allergenAnswers = emptyMap()
                if (next.length <= 262_144) json = next
                else error = "JSON zu groß (maximal 262144 Zeichen)."
            },
            modifier = Modifier.fillMaxWidth().testTag("recipe_draft_json"),
            label = { Text("recipe.draft v1 JSON einfügen") },
            minLines = 3, maxLines = 6
        )
        Button(
            onClick = {
                invalidate()
                imported = null
                error = null
                try {
                    imported = RecipeDraftV1Import.read(json, emptyMap(), OffsetDateTime.now())
                } catch (_: IllegalArgumentException) {
                    error = "Ungültiger, veralteter oder nicht unterstützter Rezeptentwurf."
                }
            },
            enabled = json.isNotBlank(),
            modifier = Modifier.testTag("recipe_draft_json_inspect")
        ) { Text("JSON-Entwurf lokal prüfen") }
        error?.let { Text(it, Modifier.testTag("recipe_draft_import_error")) }
        imported?.let { parsed ->
            val draft = parsed.draft
            Text("Entwurf: ${draft.title} · ${draft.portions} Soll-Portionen")
            Text("Entwurfs-ID: ${parsed.draftId} · ${draft.lines.size} Zutaten")
            val contextRequested = draft.flags.considerStock || draft.flags.considerShopping ||
                draft.flags.considerOffers
            if (contextRequested) {
                Text("Kontextflags aktiv: Ohne unabhängig geprüfte Bestands-, Listen- und Angebots-Snapshots ist die Übernahme gesperrt.")
            }
            OutlinedTextField(
                restrictionsText, { invalidate(); restrictionsText = it },
                Modifier.fillMaxWidth().testTag("recipe_draft_restrictions"),
                label = { Text("Bestätigte Ausschlüsse, z. B. ALLERGEN:SOY, FOOD:milk") },
                singleLine = false, maxLines = 2
            )
            val restrictions = restrictionsText.split(",").map { it.trim() }
                .filter { it.isNotEmpty() }.toSet()
            val allergens = restrictions.filter { it.startsWith("ALLERGEN:") && it.length > 9 }
                .map { it.removePrefix("ALLERGEN:") }.toSet()
            Text("Leeres Ausschlussfeld bedeutet keine erfassten Ausschlüsse, nicht geprüfte Allergenfreiheit.")
            draft.lines.forEach { line ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${line.label} (${line.foodTypeId}) · ${line.amount ?: "Menge unbekannt"} ${line.unit} · Modellbasis ${line.basis}")
                    Picker(
                        "Unabhängig geprüfte Katalogversion",
                        listOf("Nicht zugeordnet") + labels.values,
                        versionIds[line.foodTypeId]?.let { labels[it] } ?: "Nicht zugeordnet"
                    ) { selected ->
                        invalidate()
                        versionIds = versionIds + (line.foodTypeId to
                            (labels.entries.firstOrNull { it.value == selected }?.key ?: ""))
                    }
                    Text("Mengenbasis separat am Produkt prüfen")
                    Choices(listOf("Ungeprüft", line.basis.name),
                        bases[line.foodTypeId]?.name ?: "Ungeprüft") { selected ->
                        invalidate()
                        bases = if (selected == "Ungeprüft") bases - line.foodTypeId
                        else bases + (line.foodTypeId to line.basis)
                    }
                    Text("Zubereitungszustand separat prüfen")
                    Choices(listOf("Ungeprüft", "RAW", "COOKED"),
                        states[line.foodTypeId]?.name ?: "Ungeprüft") { selected ->
                        invalidate()
                        states = if (selected == "Ungeprüft") states - line.foodTypeId
                        else states + (line.foodTypeId to Gate.State.valueOf(selected))
                    }
                    allergens.forEach { allergen ->
                        val key = line.foodTypeId to allergen
                        val selected = when (allergenAnswers[key]) {
                            true -> "Nachweislich enthalten"
                            false -> "Nachweislich nicht enthalten"
                            null -> "Unbekannt"
                        }
                        Text("Allergen ${allergen}: unabhängige Prüfung dieser Zutat")
                        Choices(listOf("Unbekannt", "Nachweislich enthalten",
                            "Nachweislich nicht enthalten"), selected) { answer ->
                            invalidate()
                            allergenAnswers = allergenAnswers + (key to when (answer) {
                                "Nachweislich enthalten" -> true
                                "Nachweislich nicht enthalten" -> false
                                else -> null
                            })
                        }
                    }
                }
            }
            Row {
                Checkbox(checked = reviewed, onCheckedChange = { reviewed = it },
                    modifier = Modifier.testTag("recipe_draft_manual_review_checkbox"))
                Text("Ich habe Katalogzuordnung, Basis, Zustand und angegebene Ausschlüsse unabhängig vom JSON geprüft.",
                    Modifier.padding(top = 10.dp))
            }
            Button(
                enabled = reviewed && !contextRequested,
                modifier = Modifier.testTag("recipe_draft_manual_review"),
                onClick = {
                    try {
                        val choices = draft.lines.associate { line ->
                            line.foodTypeId to RecipeDraftManualReview.Choice(
                                versionIds[line.foodTypeId].orEmpty(),
                                bases[line.foodTypeId], states[line.foodTypeId],
                                allergens.associateWith { allergen ->
                                    allergenAnswers[line.foodTypeId to allergen]
                                }
                            )
                        }
                        val prepared = RecipeDraftManualReview.prepare(
                            parsed, catalog, choices, restrictions, reviewed,
                            System.currentTimeMillis()
                        )
                        error = null
                        reviewed = false
                        onReview(json, prepared.request, prepared.confirmations,
                            prepared.reviewedStates)
                    } catch (_: IllegalArgumentException) {
                        error = "Manuelle Prüfung unvollständig oder widersprüchlich: Version, Mengenbasis, Zustand und Ausschlüsse prüfen."
                    }
                }
            ) { Text("Geprüfte Vorschau zur separaten Bestätigung") }
            Text("Die anschließende Bestätigung speichert höchstens einen Consent-Checkpoint, kein Rezept und keinen Verzehr. Keine Identitätsauthentifizierung.")
        }
    }
}
