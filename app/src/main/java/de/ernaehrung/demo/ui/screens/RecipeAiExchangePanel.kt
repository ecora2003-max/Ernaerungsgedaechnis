package de.ernaehrung.demo.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.integration.RecipeAiDocuments
import de.ernaehrung.demo.data.integration.RecipeAiExchange
import de.ernaehrung.demo.data.integration.RecipeDraftV1Import
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.integration.RecipeRequestPreferences
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.ui.Fold
import de.ernaehrung.demo.ui.Picker
import de.ernaehrung.demo.ui.RecipeTypedContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.util.UUID

/** Only the opaque outstanding document request ID survives recreation.
 * Prompt, preferences, products, reply and export approval remain in RAM.
 */
@Composable
internal fun RecipeAiExchangePanel(
    catalog: List<ProductVersionEntity>, portions: Int?, restrictions: Set<String>,
    preferences: RecipeRequestPreferences?, flags: Gate.Flags, typedContext: RecipeTypedContext?,
    editingEnabled: Boolean, onResponse: (String, RecipeDraftV1Import.Imported) -> Unit
) {
    val androidContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var wish by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selected = catalog.filter { it.id in selectedIds }.sortedBy { it.id }
    val input = if (portions != null && preferences != null && selected.size == selectedIds.size)
        RecipeAiExchange.Input(wish, portions, restrictions, preferences,
            selected.map { RecipeAiExchange.Product(it.id, it.name, it.baseUnit.label) }, flags, typedContext)
    else null
    val inputResult = runCatching { RecipeAiExchange.inputBinding(requireNotNull(input) {
        "Portionen, Vorgaben und aktuelle Produktversionen prüfen."
    }) }
    val inputKey = inputResult.getOrNull()
    var prepared by remember(inputKey, editingEnabled) { mutableStateOf<RecipeAiExchange.Prepared?>(null) }
    var approved by remember(inputKey, editingEnabled) { mutableStateOf(false) }
    var reply by remember(inputKey) { mutableStateOf("") }
    var error by remember(inputKey) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    val latestPrepared by rememberUpdatedState(prepared)
    val latestKey by rememberUpdatedState(inputKey)
    val latestEnabled by rememberUpdatedState(editingEnabled)
    val latestResponse by rememberUpdatedState(onResponse)
    fun accept(raw: String, request: RecipeAiExchange.Prepared, key: String) {
        val inspected = RecipeAiExchange.inspectResponse(request, key, raw, OffsetDateTime.now())
        latestResponse(raw, inspected)
        error = "Antwort lokal eingelesen. Jede Zutat jetzt unabhängig prüfen; es wurde nichts gespeichert oder gebucht."
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requested = pendingDocumentId
        pendingDocumentId = null
        val current = latestPrepared
        val key = latestKey
        if (uri != null && requested != null && current?.requestId == requested &&
            key == current.inputBinding && latestEnabled) {
            busy = true
            scope.launch {
                try {
                    val raw = withContext(Dispatchers.IO) { RecipeAiDocuments.read(androidContext.contentResolver, uri) }
                    // Source/selection changes during a slow provider read revoke the handoff.
                    require(latestPrepared?.requestId == requested && latestKey == key && latestEnabled)
                    accept(raw, current, key)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    error = "Antwortdatei nicht übernommen. UTF-8, Größe, aktuellen Anfragebezug und v1-JSON prüfen."
                } finally { busy = false }
            }
        } else if (uri != null) {
            error = "Anfrage nicht mehr vorhanden oder geändert. Neue Anfrage vorbereiten; keine alte Antwort übernommen."
        }
    }
    Fold("Rezept mit ChatGPT oder anderer KI entwerfen") {
        Text("Freier Rezeptwunsch statt fester Vorlage. Die App bereitet eine JSON-Anfrage vor, die du gezielt mit einer KI-App deiner Wahl teilen kannst. Kein API-Schlüssel und kein kostenpflichtiger Pflichtdienst. Ohne Teilen wird nichts übertragen. Der Offline-Generator bleibt weiter unten verfügbar.")
        Text("Portionen, Ausschlüsse und Vorgaben oben einstellen. Anfragen und Antworten bleiben hier nur im Arbeitsspeicher; nach Neustart oder Prozessverlust neu beginnen.")
        OutlinedTextField(wish, { if (it.length <= 2_000 && !busy) wish = it },
            enabled = editingEnabled && !busy, modifier = Modifier.fillMaxWidth().testTag("recipe_ai_wish"),
            label = { Text("Was möchtest du kochen?") }, minLines = 2, maxLines = 4)
        Text("Optional nur benötigte Produktversionen auswählen. Standardmäßig werden weder Katalog noch Bestandsdaten geteilt. Bei gewähltem Bestand/Einkaufsbedarf werden ausschließlich Zeilen der hier ausgewählten Versionen übermittelt; ausgelassene Zeilen bleiben unbekannt. Bons, Preise, Gesundheitsdaten und Zugangsdaten werden nicht angehängt.")
        if (editingEnabled && !busy) Picker("Produktversion für KI-Anfrage hinzufügen",
            listOf("Keine weitere Auswahl") + catalog.filter { it.id !in selectedIds }.map { "${it.name} · ${it.id}" },
            "Keine weitere Auswahl") { label ->
            catalog.firstOrNull { "${it.name} · ${it.id}" == label }?.let {
                if (selectedIds.size < 32) selectedIds = selectedIds + it.id
            }
        }
        selected.forEach { product ->
            Row {
                Checkbox(true, { selectedIds = selectedIds - product.id }, enabled = editingEnabled && !busy,
                    modifier = Modifier.testTag("recipe_ai_product_${product.id}"))
                Text("${product.name} · ${product.id}")
            }
        }
        if (selected.size != selectedIds.size) Button(onClick = { selectedIds = emptySet() }, enabled = !busy) {
            Text("Veraltete Produktauswahl entfernen")
        }
        Button(enabled = editingEnabled && !busy && inputKey != null,
            modifier = Modifier.testTag("recipe_ai_prepare"), onClick = {
                try {
                    prepared = RecipeAiExchange.prepare(requireNotNull(input), "ai-" + UUID.randomUUID(), OffsetDateTime.now())
                    approved = false; reply = ""; error = null
                } catch (failure: IllegalArgumentException) { error = failure.message ?: "Anfrage nicht verfügbar." }
            }) { Text("KI-Anfrage lokal vorbereiten") }
        if (wish.isNotBlank() && inputResult.isFailure) Text(inputResult.exceptionOrNull()?.message ?: "Vorgaben prüfen.",
            Modifier.testTag("recipe_ai_request_error"))
        prepared?.let { request ->
            Text("Genau dieser Text wird beim Teilen übergeben. Rezeptwunsch und Ausschlüsse können persönliche Angaben enthalten. Die gewählte KI-App verarbeitet sie nach ihren eigenen Bedingungen. Deine Freigabe gilt nur für diese Anfrage und ist keine Rezept- oder Kaufbestätigung.")
            OutlinedTextField(request.prompt, {}, readOnly = true,
                modifier = Modifier.fillMaxWidth().testTag("recipe_ai_prompt"), label = { Text("Anfrage vor dem Teilen prüfen") },
                minLines = 4, maxLines = 10)
            Row {
                Checkbox(approved, { approved = it }, enabled = editingEnabled && !busy,
                    modifier = Modifier.testTag("recipe_ai_export_approval"))
                Text("Ich möchte diesen geprüften Text über Android an die von mir gewählte App weitergeben.")
            }
            Button(enabled = editingEnabled && !busy && approved,
                modifier = Modifier.testTag("recipe_ai_share"), onClick = {
                    try {
                        androidContext.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, request.prompt)
                        }, "Rezeptanfrage teilen"))
                        error = "Freigabeauswahl geöffnet. Das bestätigt weder eine Übertragung noch eine erzeugte Antwort."
                    } catch (_: Exception) { error = "Keine passende App zum Teilen verfügbar. Anfrage bleibt lokal." }
                }) { Text("Anfrage mit KI-App teilen") }
            Text("Lass die KI ausschließlich das Antwort-JSON liefern. Füge es unten ein oder wähle eine selbst gespeicherte UTF-8-JSON-Datei. Anfragenummer, Zeitpunkt, Portionen und Kontext müssen unverändert bleiben. Eine Antwort ist immer ein ungeprüfter Entwurf.")
            OutlinedTextField(reply, { if (it.length <= RecipeAiDocuments.MAX_BYTES && !busy) reply = it },
                enabled = editingEnabled && !busy, modifier = Modifier.fillMaxWidth().testTag("recipe_ai_reply"),
                label = { Text("Antwort-JSON der KI einfügen") }, minLines = 3, maxLines = 6)
            Button(enabled = editingEnabled && !busy && reply.isNotBlank(),
                modifier = Modifier.testTag("recipe_ai_inspect"), onClick = {
                    try { accept(reply, request, requireNotNull(inputKey))
                    } catch (_: IllegalArgumentException) {
                        error = "Antwort nicht übernommen. Nur gültiges v1-JSON zur aktuellen Anfrage ohne Markdown einfügen."
                    }
                }) { Text("KI-Antwort lokal prüfen") }
            Button(enabled = editingEnabled && !busy && pendingDocumentId == null,
                modifier = Modifier.testTag("recipe_ai_open_document"), onClick = {
                    try {
                        pendingDocumentId = request.requestId
                        documentPicker.launch(arrayOf("application/json", "text/plain"))
                    } catch (_: Exception) { pendingDocumentId = null; error = "Android-Dateiauswahl nicht verfügbar." }
                }) { Text("KI-Antwortdatei auswählen") }
        }
        if (busy) Text("Ausgewählte Antwortdatei wird lokal geprüft.")
        error?.let { Text(it, Modifier.testTag("recipe_ai_result")) }
    }
}
