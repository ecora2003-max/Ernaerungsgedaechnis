package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class OperationReviewRequest(val operationId: String, val requestKey: String)

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun StockHistoryScreen(state: LocalDataState,blocked: Boolean,operationKey: String,
    model: LocalDataViewModel,initialBatchId: String?) {
    var batch by rememberSaveable(initialBatchId) { mutableStateOf(initialBatchId.orEmpty()) }
    var operation by rememberSaveable { mutableStateOf("") }
    var appliedBatch by rememberSaveable(initialBatchId) { mutableStateOf(initialBatchId.orEmpty()) }
    var appliedOperation by rememberSaveable { mutableStateOf("") }
    var beforeAt by rememberSaveable(appliedBatch,appliedOperation) { mutableStateOf<Long?>(null) }
    var beforeId by rememberSaveable(appliedBatch,appliedOperation) { mutableStateOf<String?>(null) }
    val query = StockHistoryQuery(appliedBatch.takeIf(String::isNotEmpty),appliedOperation.takeIf(String::isNotEmpty),
        beforeAt?.let { at -> beforeId?.let { StockHistoryCursor(at,it) } })
    val history by model.stockHistory.collectAsStateWithLifecycle()
    LaunchedEffect(query) { model.showStockHistory(query) }
    var request by remember { mutableStateOf<OperationReviewRequest?>(null) }
    val editorPosition = remember { BringIntoViewRequester() }
    Box(Modifier.bringIntoViewRequester(editorPosition).testTag("history_review_editor")) {
        ReversalEditor(state,blocked,operationKey,model,request)
    }
    LaunchedEffect(request?.requestKey) {
        if (request != null) { withFrameNanos { }; editorPosition.bringIntoView() }
    }
    Section("Vollständige Lager- und Verzehrchronik","Erfassung absteigend · ältere Vorgänge seitenweise")
    IdPicker("Charge der Chronik",listOf("" to "Alle Chargen und Vorgänge ohne Lagerbezug") + state.batches.map {
        it.batch.id to "${it.productName} · ${it.locationName} · ${it.batch.id}"
    },batch) { batch = it }
    Field("Vorgangskennung exakt · optional",operation,{ operation = it },modifier = Modifier.testTag("history_operation_input"))
    Action("Chronik anzeigen",true,"history_apply") {
        appliedBatch = batch; appliedOperation = operation.trim(); beforeAt = null; beforeId = null
        model.showStockHistory(StockHistoryQuery(appliedBatch.takeIf(String::isNotEmpty),appliedOperation.takeIf(String::isNotEmpty)))
    }
    Text("Ausgewählte Charge: ${query.batchId ?: "alle"}",Modifier.testTag("history_applied_batch"))
    Text("Vorgangskennung: ${query.operationId ?: "alle"}")
    Text("Erfasst ist der Speicherzeitpunkt; ein unbekanntes Ereignisdatum bleibt unbekannt. Lesen, Filtern und Prüfen buchen nichts.")
    if (history.query != query || history.loading) { CircularProgressIndicator(); return }
    history.error?.let { Hint(it); return }
    val page = history.report?.takeIf { it.query == query } ?: return
    Text("${page.entries.size} Vorgänge auf dieser Seite",Modifier.testTag("history_page_count"))
    if (page.entries.isEmpty()) Text("Keine Vorgänge in dieser Auswahl.")
    page.entries.forEach { entry -> key(entry.operation.id) { Panel(Modifier.testTag("history_operation_${entry.operation.id}")) {
        Section(operationLabel(entry.operation.kind),historyTimestamp(entry.operation.recordedAt))
        Text("Vorgang: ${entry.operation.id}")
        val names = entry.movements.map { it.productName }.distinct()
        if (names.isNotEmpty()) Text("Artikel: ${names.take(3).joinToString() }${if (names.size > 3) " · ${names.size - 3} weitere" else ""}")
        if (entry.reversal != null) Text("Storniert durch ${entry.reversal.reversalOperationId}")
        if (entry.operation.kind == "REVERSAL") Text("Ursprungsvorgang: ${entry.operation.resultId}")
        entry.consumption?.let { Text("Verzehr am ${it.date} · ${it.meal}") }
        Fold("Bewegungsdetails ${entry.operation.id}") {
            KV("Erfasst (Berlin)",historyTimestamp(entry.operation.recordedAt))
            if (entry.movements.isEmpty()) Text("Kein Lagerbezug; ursprünglicher Vorgang bleibt erhalten.")
            entry.movements.forEach { row ->
                Section(row.productName,movementLabel(row.movement.kind))
                KV("Richtung",when { row.movement.delta > 0L -> "Zugang"; row.movement.delta < 0L -> "Abgang"; else -> "Keine Mengenänderung" })
                KV("Menge","${Amounts.format(row.movement.delta)} ${row.unit.label}")
                KV("Ereignisdatum",row.movement.occurredOn ?: "Unbekannt · Altbestand")
                KV("Ereigniszeitpunkt",row.movement.occurredAt?.let(::historyTimestamp) ?: "Unbekannt")
                KV("Erfasst (Berlin)",historyTimestamp(row.movement.recordedAt))
                KV("Lagerort",state.batches.firstOrNull { it.batch.id == row.movement.batchId }?.locationName ?: "Unbekannt")
                KV("Quelle",row.movement.source.label); KV("Grund",row.movement.reason)
                Text("Bewegung: ${row.movement.id}\nVorgang: ${row.movement.operationId}\nCharge: ${row.movement.batchId}")
                row.movement.reversesId?.let { Text("Gegenbuchung zu Bewegung: $it") }
            }
        }
        if (entry.operation.kind != "REVERSAL" && entry.reversal == null) {
            Action("Vorgang zur Gegenbuchung prüfen",true,"history_review_${entry.operation.id}") {
                request = OperationReviewRequest(entry.operation.id,UUID.randomUUID().toString())
            }
        }
    } } }
    if (query.before != null) Action("Neueste Vorgänge",true,"history_newest") { beforeAt = null; beforeId = null }
    page.older?.let { older -> Action("Ältere Vorgänge",true,"history_older") { beforeAt = older.recordedAt; beforeId = older.operationId } }
}

private fun historyTimestamp(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.of("Europe/Berlin"))
    .format(DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss.SSS XXX"))
