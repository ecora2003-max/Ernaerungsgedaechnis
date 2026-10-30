package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*

@Composable
internal fun PlanExecutionEditor(data: LocalDataState, report: State<PlanningReport>, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    val ui = model.execution.collectAsStateWithLifecycle()
    val selectedEntry = model.selectedExecutionEntry.collectAsStateWithLifecycle()
    var actualId by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var deviation by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Fold("Ist-Ausführung zuordnen oder zurücknehmen") {
        val currentReport = report.value
        val entries = currentReport.plans.flatMap { it.entries } + currentReport.detachedExecutionEntries
        IdPicker("Planposition für Ist-Zuordnung", entries.map { entry ->
            val title = currentReport.versions.find { it.id == entry.planId }?.title ?: "Frühere Planfassung"
            val name = entry.label ?: data.versions.find { it.id == entry.productVersionId }?.name ?:
                data.recipes.find { it.id == entry.recipeVersionId }?.name ?: "Fertigportionen"
            entry.id to "$title · ${entry.date} · $name · ${entry.id.take(8)}"
        }, selectedEntry.value) { id ->
            model.showExecution(id); actualId = ""; reason = ""; confirmed = false; deviation = false
        }
        ui.value.error?.let { Hint(it) }
        val view = ui.value.view
        if (view == null || view.entry.id != selectedEntry.value) {
            Text("Planposition wählen. Zuordnen und Zurücknehmen ändern keine Ist-Buchung.")
        } else {
            Section("Ausführungsstand", "${view.entry.date} · ${view.entry.amount?.let(Amounts::format) ?: "Ungeklärt"} ${view.entry.unit.label} geplant")
            Text("Bekannt ausgeführt: ${Amounts.format(view.status.knownExecuted)} ${view.entry.unit.label}${if (view.status.hasUnknownExecuted) " + Menge ungeklärt" else ""}", Modifier.testTag("execution_known"))
            Text("Noch offen: ${view.status.remainingAmount?.let(Amounts::format) ?: "Ungeklärt"} ${view.entry.unit.label}", Modifier.testTag("execution_remaining"))
            if (view.status.referenceConflict) Hint(PlanningIssue.EXECUTION_REFERENCE_CHANGED.label)
            if (!view.current) Hint("Frühere oder verworfene Planfassung: bestehende Zuordnungen können zurückgenommen werden. Für neue Zuordnungen die aktuelle aktive Planposition wählen.")
            if (view.current) {
                IdPicker("Bereits gebuchtes Ist-Ereignis", view.candidates.map {
                    it.operationId to "${it.date} · ${it.label} · ${it.amount?.let(Amounts::format) ?: "Menge ungeklärt"} ${it.unit.label} · ${it.operationId.take(8)}"
                }, actualId) { id -> actualId = id; confirmed = false; deviation = false }
                val actual = view.candidates.find { it.operationId == actualId }
                val differs = actual != null && (actual.date != view.entry.date || actual.amount == null || actual.amount != view.status.remainingAmount)
                if (actual != null) {
                    Text("Ist: ${actual.date} · ${actual.amount?.let(Amounts::format) ?: "Ungeklärt"} ${actual.unit.label}. Diese Buchung bleibt unverändert.")
                    if (differs) Hint("Termin oder Menge weicht ab. Teilmengen lassen einen Rest offen; unbekannte Mengen bestätigen keine vollständige Ausführung.")
                }
                Field("Begründung der Ist-Zuordnung", reason, { reason = it; confirmed = false })
                if (differs) Check("Abweichenden Termin oder Menge bewusst zuordnen", deviation) { deviation = it; confirmed = false }
                Check("Diese Ist-Buchung dieser Planposition zuordnen", confirmed) { confirmed = it }
                SaveButton("Ist-Zuordnung speichern", blocked.value || !confirmed || actual == null || reason.isBlank() || differs && !deviation) {
                    val key = operation.value
                    val input = LinkPlanExecutionInput(view.entry.id, actualId, view.status.snapshot, reason, deviation)
                    model.save(key) { linkPlanExecution(key, input, true) }
                }
                if (view.candidates.isEmpty()) Text("Keine passende unzugeordnete Ist-Buchung. Verzehr zuerst unter Ernährung oder Herstellung unter Küche tatsächlich erfassen. Artikel-/Rezeptversion und bei verknüpften Portionen der zugeordnete Kochansatz müssen passen.")
            }
            Section("Zuordnungen und Rücknahme", "${view.status.history.size} unveränderliche Zuordnungsschritte")
            view.status.events.forEach { fact ->
                androidx.compose.runtime.key(fact.link.operationId) {
                    ExecutionHistoryRow(fact, blocked, operation, model)
                }
            }
        }
    }
}

@Composable
private fun ExecutionHistoryRow(fact: PlanExecutionFact, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var reason by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Panel {
        Text("${fact.actual?.label ?: "Ist-Bezug ungeklärt"} · ${fact.actual?.date ?: "Datum ungeklärt"}")
        Text(when { !fact.link.assigned -> "Zuordnung zurückgenommen"; fact.actual?.reversed == true -> "Ist-Buchung storniert · zählt nicht als ausgeführt"; else -> "Ausdrücklich zugeordnet" })
        Text(fact.link.reason)
        if (fact.link.assigned) {
            Field("Rücknahmegrund ${fact.link.operationId.take(8)}", reason, { reason = it; confirmed = false })
            Check("Nur diese Zuordnung zurücknehmen ${fact.link.operationId.take(8)}", confirmed) { confirmed = it }
            SaveButton("Zuordnung zurücknehmen ${fact.link.operationId.take(8)}", blocked.value || !confirmed || reason.isBlank()) {
                val key = operation.value; val input = UnlinkPlanExecutionInput(fact.link.operationId, reason)
                model.save(key) { unlinkPlanExecution(key, input, true) }
            }
        }
    }
}
