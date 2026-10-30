package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.LocalDate
import java.util.UUID

@Composable
internal fun LocalPlanningScreen(state: LocalDataState, blocked: Boolean, operationId: String, model: LocalDataViewModel) {
    // The history may be opened after a save/reset. Deferred content must read the current guard/key.
    val currentBlocked = rememberUpdatedState(blocked)
    val currentOperation = rememberUpdatedState(operationId)
    val ui by model.planning.collectAsStateWithLifecycle()
    var starts by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var horizon by rememberSaveable { mutableStateOf("7 Tage") }
    Heading("Planung & Bedarf", "Gemeinsame Einträge für Tag, Woche und acht Wochen")
    Hint("Planen verändert keinen Bestand und zählt nicht zur Ernährung. Alle aktiven Pläne teilen sich rechnerisch denselben Vorrat. Neue Versionen ersetzen nur die bisherige Planung.", Sage)
    Field("Ansicht ab JJJJ-MM-TT", starts, { starts = it })
    WrappingChoices(listOf("1 Tag", "7 Tage", "56 Tage"), horizon, "plan_horizon_") { horizon = it }
    Action("Planungszeitraum anzeigen", true) {
        model.showPlanning(starts, when (horizon) { "1 Tag" -> 1L; "56 Tage" -> 56L; else -> 7L })
    }
    ui.error?.let { Hint(it) }
    val report = ui.report
    if (report == null) {
        if (ui.error == null) CircularProgressIndicator()
        return
    }
    Text("${report.startsOn} bis ${report.endsOn} · Bestandsstand ${report.asOf}")
    Hint("Berechnet werden offene Planannahmen ab dem Bestandsstand. Vergangene Termine werden nicht mit heutigem Bestand rückgerechnet. Nach tatsächlicher Ausführung den Plan bewusst anpassen; eine automatische Erfüllungszuordnung folgt später.")
    androidx.compose.runtime.key(operationId) { PlanEditor(state, report, blocked, operationId, model) }
    if (report.hasUnknownRequirements) Hint("Teilergebnis: Ungeklärte Mengen oder Bezüge in aktiven Plänen können weiteren Bedarf verursachen. Bekannte Fehlmengen sind keine vollständige Einkaufsliste.")
    Section("Fehlmengen im angezeigten Zeitraum", "Noch keine Packungs-, Preis- oder Budgetvorschläge")
    if (report.shopping.isEmpty()) Text("Keine bekannte Produktfehlmenge. Offene Daten und geplante Portionen gesondert prüfen.")
    report.shopping.forEach { need -> Panel {
        Text(need.name, style = MaterialTheme.typography.titleMedium)
        Text(if (need.unknownRequirements > 0) "Bekannte Fehlmenge: ${Amounts.format(need.knownMissing)} ${need.unit.label} · ${need.unknownRequirements} Mengen ungeklärt"
            else "Fehlmenge: ${Amounts.format(need.knownMissing)} ${need.unit.label}", Modifier.testTag("shopping_${need.versionId}"))
        Text("Artikelversion ${need.versionId.take(8)} · Keine automatische Umrechnung oder Einkaufsbuchung.")
    } }
    Section("Geplante Vorgänge")
    if (report.entries.isEmpty()) Text("Keine aktiven zukünftigen Einträge in diesem Ausschnitt.")
    report.entries.forEach { result -> Panel {
        Section(result.entry.label ?: result.name, "${result.entry.date} · ${planKindLabel(result.entry.kind)}")
        Text("Geplant: ${result.entry.amount?.let(Amounts::format) ?: "Unbekannt"} ${result.entry.unit.label}")
        if (result.entry.preparationEntryId != null) {
            KV("Aus verknüpftem Kochplan", result.projectedPortions?.let { "${Amounts.format(it)} geplante Portionen" } ?: "Ungeklärt")
            KV("Zusätzlich fehlende Portionen", result.missingPortions?.let(Amounts::format) ?: "Ungeklärt")
        }
        result.demands.forEach { line ->
            Text(line.name, style = MaterialTheme.typography.titleSmall)
            KV("Bedarf", line.required?.let { "${Amounts.format(it)} ${line.unit.label}" } ?: "Menge ungeklärt")
            KV("Aus vorhandenem Vorrat zugeordnet", "${Amounts.format(line.covered)} ${line.unit.label}")
            Text("Fehlmenge: ${line.missing?.let { "${Amounts.format(it)} ${line.unit.label}" } ?: "Ungeklärt"}", Modifier.testTag("demand_${result.entry.id}_${line.versionId}"))
            line.excluded.groupBy { it.reason }.forEach { (reason, stocks) ->
                Text("Nicht angerechnet: ${Amounts.format(stocks.sumOf { it.amount })} ${line.unit.label} · ${reason.label}", style = MaterialTheme.typography.bodySmall)
            }
            line.issues.forEach { Text(it.label, color = MaterialTheme.colorScheme.error) }
        }
        result.issues.forEach { Text(it.label, color = if (it == PlanningIssue.PLANNED_ONLY || it == PlanningIssue.STORAGE_UNCHECKED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
    } }
    Hint("Zuordnungen sind Rechenvorschläge, keine physischen Reservierungen oder Sicherheitsfreigaben. Ein überschrittenes MHD verlangt Prüfung und bedeutet nicht automatisch ungenießbar. Vor tatsächlicher Herstellung oder Verzehr erneut prüfen.", Sage)
    Fold("Planversionen und Verwerfen") {
        report.plans.forEach { record ->
            androidx.compose.runtime.key(record.version.id) {
                PlanHistory(record, report.versions, currentBlocked, currentOperation, model)
            }
        }
    }
}

@Composable
private fun PlanHistory(record: PlanRecord, versions: List<PlanEntity>, blocked: State<Boolean>, operationId: State<String>, model: LocalDataViewModel) {
    var reason by rememberSaveable { mutableStateOf("") }
    Panel {
        Section(record.version.title ?: "Übernommener Plan", "Version ${record.version.revision} · ${record.version.status}")
        Text("${record.version.startsOn} bis ${record.version.endsOn} · ${record.entries.size} Einträge")
        versions.filter { it.seriesId == record.version.seriesId }.sortedByDescending { it.revision }.forEach {
            Text("v${it.revision} · ${it.status} · ${it.changeReason ?: "Änderungsgrund nicht überliefert"}")
        }
        if (record.version.status != EntryStatus.CANCELLED) {
            Field("Verwerfungsgrund ${record.version.title ?: record.version.id.take(8)}", reason, { reason = it })
            SaveButton("Plan verwerfen ${record.version.id.take(8)}", blocked.value || reason.isBlank()) {
                val version = record.version.id; val note = reason; val key = operationId.value
                model.save(key) { cancelPlan(key, version, note) }
            }
        }
    }
}

private fun planKindLabel(kind: PlanKind) = when (kind) {
    PlanKind.DIRECT_CONSUMPTION -> "Direkter Produktverzehr"
    PlanKind.PREPARATION -> "Zubereitung"
    PlanKind.PREPARED_PORTION -> "Fertigportionen verzehren"
}

@Composable
private fun PlanEditor(state: LocalDataState, report: PlanningReport, blocked: Boolean, operationId: String, model: LocalDataViewModel) {
    var previousId by remember { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var end by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(6).toString()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var draft by remember { mutableStateOf<List<PlanEntryInput>>(emptyList()) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var amount by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(PlanKind.DIRECT_CONSUMPTION.name) }
    var reference by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("Vorhandener Artikel") }
    var error by remember { mutableStateOf<String?>(null) }
    Fold("Plan anlegen oder überarbeiten") {
        IdPicker("Plan bearbeiten", listOf("" to "Neuer Plan") + report.plans.map {
            it.version.id to "${it.version.title ?: "Übernommener Plan"} · v${it.version.revision} · ${it.version.status}"
        }, previousId) { id ->
            val record = report.plans.find { it.version.id == id }
            previousId = id; title = record?.version?.title.orEmpty()
            start = record?.version?.startsOn ?: LocalDate.now().toString()
            end = record?.version?.endsOn ?: LocalDate.now().plusDays(6).toString()
            reason = ""; draft = record?.entries?.map(PlanEntryInput::from).orEmpty(); error = null
        }
        Field("Plantitel", title, { title = it })
        Field("Planbeginn JJJJ-MM-TT", start, { start = it })
        Field("Planende JJJJ-MM-TT", end, { end = it })
        Text("Ein Zeitraum bis 56 Tage. Die Ansichten teilen diese Einträge, ohne zusätzliche Wochen- oder Monatsmengen.")
        draft.forEachIndexed { index, entry ->
            val name = entry.label ?: state.versions.find { it.id == entry.productVersionId }?.name ?:
                state.recipes.find { it.id == entry.recipeVersionId }?.name ?: "Verknüpfte Portionen"
            Text("${index + 1}. ${entry.date} · $name · ${entry.amount?.let(Amounts::format) ?: "Menge unbekannt"} ${entry.unit.label}")
            SaveButton("Planeintrag ${index + 1} entfernen", blocked) {
                draft = draft.filter { it.key != entry.key && it.preparationKey != entry.key }
            }
        }
        Section("Eintrag zum Entwurf hinzufügen")
        Field("Geplanter Termin JJJJ-MM-TT", date, { date = it })
        Field("Bezeichnung im Plan · optional", label, { label = it })
        IdPicker("Art des Planeintrags", PlanKind.entries.map { it.name to planKindLabel(it) }, kind) { value ->
            kind = value; reference = ""
        }
        val entryKind = PlanKind.valueOf(kind)
        if (entryKind == PlanKind.PREPARED_PORTION) WrappingChoices(listOf("Vorhandener Artikel", "Geplante Zubereitung"), source, "plan_source_") {
            source = it; reference = ""
        }
        val fromPreparation = entryKind == PlanKind.PREPARED_PORTION && source == "Geplante Zubereitung"
        val options = when {
            entryKind == PlanKind.PREPARATION -> state.recipes.map { it.id to "${it.name} · v${it.revision}" }
            fromPreparation -> draft.filter { it.kind == PlanKind.PREPARATION }.map { it.key to "${it.label ?: state.recipes.find { recipe -> recipe.id == it.recipeVersionId }?.name ?: "Zubereitung"} · ${it.date}" }
            else -> state.versions.filter { entryKind != PlanKind.PREPARED_PORTION || it.baseUnit == BaseUnit.PORTION }.map {
                it.id to "${it.name} · v${it.revision} · ${it.baseUnit.label} · ${it.id.take(8)}"
            }
        }
        IdPicker("Planbezug", options, reference) { reference = it }
        val unit = if (entryKind == PlanKind.PREPARATION || fromPreparation) BaseUnit.PORTION else state.versions.find { it.id == reference }?.baseUnit
        Field("Geplante Menge · ${unit?.label ?: "Basiseinheit"} · leer bei unbekannt", amount, { amount = it })
        SaveButton("Zum Planentwurf hinzufügen", blocked || reference.isEmpty() || unit == null) {
            runCatching {
                val parsed = amount.takeIf { it.isNotBlank() }?.let(Amounts::parse)
                val entry = PlanEntryInput(UUID.randomUUID().toString(), date, entryKind,
                    productVersionId = reference.takeIf { entryKind != PlanKind.PREPARATION && !fromPreparation },
                    recipeVersionId = reference.takeIf { entryKind == PlanKind.PREPARATION }, amount = parsed, unit = requireNotNull(unit),
                    preparationKey = reference.takeIf { fromPreparation }, label = label.takeIf { it.isNotBlank() })
                draft = draft + entry; amount = ""; label = ""; error = null
            }.onFailure { error = it.message }
        }
        Text("Der Formularentwurf ist noch nicht gespeichert. Beim Verlassen dieses Bereichs kann er verloren gehen.")
        error?.let { Hint(it) }
        Field("Änderungsgrund des Plans", reason, { reason = it })
        SaveButton("Planversion lokal speichern", blocked) {
            val input = PlanInput(title, start, end, draft.toList(), reason); val previous = previousId.takeIf { it.isNotEmpty() }
            model.save(operationId) { savePlan(operationId, input, previous) }
        }
    }
}
