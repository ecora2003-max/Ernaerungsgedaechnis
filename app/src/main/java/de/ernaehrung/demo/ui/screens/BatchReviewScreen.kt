package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.Instant
import java.util.UUID

@Composable
private fun rememberBatchReviewModel(id: String): BatchReviewViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, id) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BatchReviewViewModel(FoodStore(FoodDatabase.localSandbox(context)), id) as T
    } }
    return viewModel(key = "batch-review:$id", factory = factory)
}

@Composable
internal fun BatchReviewScreen(id: String) {
    val model = rememberBatchReviewModel(id)
    val state by model.state.collectAsStateWithLifecycle()
    val saving by model.saveState.collectAsStateWithLifecycle()
    // Values and confirmation deliberately do not enter SavedState.
    var editing by remember { mutableStateOf<BatchReviewSnapshot?>(null) }
    var operationKey by remember { mutableStateOf("") }
    val snapshot = state.snapshot
    Heading("Chargenangaben", "Prüfhistorie ohne Mengen- oder Identitätsänderung")
    Text("Eine Prüfung ersetzt keine tatsächliche Lagerkontrolle. Bestand, Lagerort, Artikelversion und ursprüngliche Angaben bleiben erhalten.")
    when {
        state.readError -> {
            Hint("Charge konnte nicht vollständig gelesen werden. Eine Bestätigung bleibt gesperrt.")
            OutlinedButton({ model.retry() }, Modifier.testTag("batch_review_retry")) { Text("Charge erneut lesen") }
        }
        snapshot == null -> LinearProgressIndicator()
        else -> {
            Text(snapshot.product.name, Modifier.testTag("batch_review_product"), style = MaterialTheme.typography.titleLarge)
            Text("${snapshot.location.name} · ${Amounts.format(snapshot.amount)} ${snapshot.product.baseUnit.label}", Modifier.testTag("batch_review_balance"))
            Text("Charge ${snapshot.original.id} · Artikelversion ${snapshot.original.versionId}")
            if (snapshot.originReversed) Hint("Herkunft storniert. Diese Charge bleibt nur als unveränderte Historie lesbar.")
            Panel {
                Section("Aktuell geprüfte Angaben", "Prüfversion ${snapshot.latest?.revision ?: 0}")
                BatchFacts(snapshot.current, "batch_review_current_")
                snapshot.latest?.let { Text("${it.source.label} · ${it.quality.label} · ${Instant.ofEpochMilli(it.recordedAt)}") }
                    ?: Text("Ursprüngliche Chargenangaben; noch keine zusätzliche Prüfung.")
            }
            OutlinedButton({ model.clearResult(); editing = snapshot; operationKey = UUID.randomUUID().toString() },
                enabled = !saving.busy && !snapshot.originReversed, modifier = Modifier.testTag("batch_review_edit")) {
                Text("Angaben neu prüfen")
            }
        }
    }
    saving.error?.let { Hint(it) }
    saving.result?.let {
        Text("Chargenprüfung dauerhaft gespeichert. Keine Bestandsbewegung erzeugt.", Modifier.testTag("batch_review_saved"))
    }
    val form = editing
    if (form != null && saving.result == null) key(operationKey) {
        val stale = snapshot == null || snapshot.originReversed || snapshot.latest?.id != form.latest?.id
        if (stale) Hint("Prüfstand geändert oder nicht lesbar. Formular schließen, Charge neu lesen und erneut prüfen.")
        BatchReviewEditor(form, stale || saving.busy, onSave = { input, confirmed -> model.save(operationKey, input, confirmed) })
        OutlinedButton({ editing = null; model.clearResult() }, enabled = !saving.busy) { Text("Prüfung abbrechen") }
    }
    snapshot?.let { data ->
        Fold("Unverändertes Chargenoriginal") { BatchFacts(data.original, "batch_review_original_") }
        data.history.sortedByDescending { it.revision }.forEach { review ->
            Fold("Prüfung ${review.revision} · ${review.id.take(8)}") {
                Text("${review.reason} · ${review.source.label} · ${review.quality.label}")
                Text("Gespeichert ${Instant.ofEpochMilli(review.recordedAt)} · Vorgang ${review.operationId}")
                BatchFacts(data.original.withReview(review), "batch_review_history_${review.id}_")
                Text("Originaleingabe Öffnen: ${review.openedAtText ?: "unbekannt"}")
                Text("Originaleingabe Auftauen: ${review.thawedAtText ?: "unbekannt"}")
                Text(if (review.conditionsConfirmed) "Bedingungen/Eignung ausdrücklich geprüft" else "Keine zusätzliche Eignungsbestätigung")
                Text("Vorige Prüfung: ${review.previousId ?: "ursprüngliche Charge"}")
            }
        }
    }
}

@Composable
private fun BatchFacts(batch: BatchEntity, tag: String) {
    Text("Zugang: ${batch.acquiredOn ?: "Unbekannt"}", Modifier.testTag("${tag}acquired"))
    Text("MHD: ${batch.bestBefore ?: "Unbekannt"}", Modifier.testTag("${tag}best_before"))
    Text("Verbrauchsdatum: ${batch.useBy ?: "Unbekannt"}", Modifier.testTag("${tag}use_by"))
    Text("Öffnen (UTC): ${batch.openedAt?.let(Instant::ofEpochMilli) ?: "Unbekannt"}")
    Text("Auftauen (UTC): ${batch.thawedAt?.let(Instant::ofEpochMilli) ?: "Unbekannt"}")
    Text(when (batch.release) {
        ReleaseStatus.UNCHECKED -> "Verwendung ungeklärt"
        ReleaseStatus.BLOCKED -> "Verwendung gesperrt"
        ReleaseStatus.RELEASED -> "Vom Nutzer ausdrücklich freigegeben"
    }, Modifier.testTag("${tag}release"))
    Text("Lager-/Prüfnotiz: ${batch.safetyNote ?: "Unbekannt"}")
}

@Composable
private fun BatchReviewEditor(snapshot: BatchReviewSnapshot, blocked: Boolean,
    onSave: (BatchReviewInput, Boolean) -> Unit) {
    var acquired by remember { mutableStateOf(snapshot.current.acquiredOn.orEmpty()) }
    var bestBefore by remember { mutableStateOf(snapshot.current.bestBefore.orEmpty()) }
    var useBy by remember { mutableStateOf(snapshot.current.useBy.orEmpty()) }
    var opened by remember { mutableStateOf(snapshot.latest?.openedAtText ?: BatchReviewTimes.format(snapshot.current.openedAt)) }
    var thawed by remember { mutableStateOf(snapshot.latest?.thawedAtText ?: BatchReviewTimes.format(snapshot.current.thawedAt)) }
    var release by remember { mutableStateOf(snapshot.current.release.name) }
    var note by remember { mutableStateOf(snapshot.current.safetyNote.orEmpty()) }
    var reason by remember { mutableStateOf("") }
    var conditions by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    // Every input change revokes the previous confirmation and safety check.
    fun changed() { conditions = false; confirmed = false }
    Text("Leer bedeutet ausdrücklich unbekannt. MHD und Verbrauchsdatum sind unterschiedliche Angaben. Eine Freigabe wird niemals aus einem Datum abgeleitet.")
    Field("Geprüftes Zugangsdatum · optional", acquired, { acquired = it; changed() })
    Field("Geprüftes MHD · optional", bestBefore, { bestBefore = it; changed() })
    Field("Geprüftes Verbrauchsdatum · optional", useBy, { useBy = it; changed() })
    Text("Öffnen/Auftauen nur mit bekannter Uhrzeit und explizitem UTC-Versatz, z. B. 2026-10-25T08:30:00+01:00. Alte gespeicherte Zeitpunkte sind in UTC dargestellt; ihr ursprünglicher Ortsversatz ist nicht überliefert. Keine Datums-zu-Mitternacht-Erfindung.")
    Field("Öffnen mit UTC-Versatz · optional", opened, { opened = it; changed() })
    Field("Auftauen mit UTC-Versatz · optional", thawed, { thawed = it; changed() })
    Picker("Geprüfter Verwendungsstatus", ReleaseStatus.entries.map(::reviewLabel), reviewLabel(ReleaseStatus.valueOf(release))) { label ->
        release = ReleaseStatus.entries.first { reviewLabel(it) == label }.name; changed()
    }
    Field("Geprüfte Lager-/Prüfnotiz · optional", note, { note = it; changed() })
    Field("Grund dieser Chargenprüfung", reason, { reason = it; changed() })
    if (release == ReleaseStatus.RELEASED.name) Check("Tatsächliche Bedingungen und Eignung für diese Freigabe geprüft", conditions) { conditions = it; confirmed = false }
    Check("Diese genauen Chargenangaben ausdrücklich bestätigen", confirmed) { confirmed = it }
    OutlinedButton({ onSave(BatchReviewInput(snapshot.original.id, snapshot.latest?.id,
        acquired.takeIf(String::isNotBlank), bestBefore.takeIf(String::isNotBlank), useBy.takeIf(String::isNotBlank),
        opened.takeIf(String::isNotBlank), thawed.takeIf(String::isNotBlank), ReleaseStatus.valueOf(release),
        note.takeIf(String::isNotBlank), reason, conditions), confirmed) },
        enabled = !blocked && confirmed && reason.isNotBlank() && (release != ReleaseStatus.RELEASED.name || conditions),
        modifier = Modifier.testTag("batch_review_save")) { Text("Geprüfte Chargenangaben speichern") }
}

private fun reviewLabel(value: ReleaseStatus) = when (value) {
    ReleaseStatus.UNCHECKED -> "Ungeklärt"
    ReleaseStatus.BLOCKED -> "Gesperrt"
    ReleaseStatus.RELEASED -> "Ausdrücklich freigegeben"
}
