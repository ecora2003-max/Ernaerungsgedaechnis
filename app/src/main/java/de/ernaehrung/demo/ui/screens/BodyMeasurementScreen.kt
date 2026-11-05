package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.BodyMeasurementViewModel
import java.time.*
import java.util.UUID

@Composable
private fun rememberBodyMeasurementModel(): BodyMeasurementViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BodyMeasurementViewModel(FoodStore(FoodDatabase.localSandbox(context))) as T
    } }
    return viewModel(key = "local-body-measurements", factory = factory)
}

/** Read-only entry point. Merely showing local body values never creates an
 * operation key, import consent, provider read or nutrition/stock write.
 */
@Composable
internal fun BodyMeasurementSummary(onOpen: () -> Unit,
    model: BodyMeasurementViewModel = rememberBodyMeasurementModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val history = state.history
    OutlinedCard(Modifier.fillMaxWidth().testTag("fitness_body_summary")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Manuelle Körperwerte", style = MaterialTheme.typography.titleMedium)
            when {
                state.readError -> Text("Die lokale Körpermesshistorie ist gerade nicht lesbar.")
                history == null -> Text("Lokale Körpermessungen werden gelesen …")
                history.latest.isEmpty() && history.legacy.isEmpty() ->
                    Text("Noch keine eigenen Körpermessungen.", Modifier.testTag("fitness_body_empty"))
                history.latest.isEmpty() -> Text("Früher erfasste Körperwerte sind in deiner Messhistorie verfügbar.")
                else -> {
                    Text("Aus deiner Messhistorie · jeweils aktueller Korrekturstand")
                    history.latest.take(3).forEach { row ->
                        Text("${row.name ?: BodyMetric.valueOf(row.metric).label}: ${row.rawValue ?: "Unbekannt"} ${row.unit}",
                            Modifier.testTag("fitness_body_value_${row.id}"))
                        Text("Originales Messdatum: ${row.localDate} · ${row.localTime ?: "Uhrzeit unbekannt"} · ${row.zoneId ?: "Zeitzone unbekannt"}")
                        Text("Manuell · ${row.device ?: "Gerät unbekannt"} · ${BodyMethod.valueOf(row.method).label} · ${BodyQuality.valueOf(row.quality).label}")
                        row.methodDetail?.let { Text("Definition / Methode: $it") }
                        Text("Messbedingungen: ${row.conditions ?: "Unbekannt"}")
                        if (row.excludedFromTrend) Text("Für spätere Trends ausgeschlossen")
                    }
                }
            }
            Text("Diese Werte bleiben lokal und benötigen keine Health-Connect-Rechte. Es werden keine Ernährungsziele berechnet oder geändert.")
            OutlinedButton(onOpen, Modifier.testTag("fitness_open_body")) { Text("Körpermessungen erfassen und Verlauf öffnen") }
        }
    }
}

@Composable
internal fun BodyMeasurementScreen(model: BodyMeasurementViewModel = rememberBodyMeasurementModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    // No health value, draft or consent is retained in saved instance state.
    // This random key recovers an already committed result after recreation.
    var operation by rememberSaveable { mutableStateOf("body-${UUID.randomUUID()}") }
    var correcting by remember { mutableStateOf<BodyMeasurementVersionEntity?>(null) }
    var showHistory by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableIntStateOf(20) }
    LaunchedEffect(operation) { model.openOperation(operation) }
    LaunchedEffect(state.purgeRevision) {
        if (state.purgeRevision > 0) { correcting = null; showHistory = null; operation = "body-${UUID.randomUUID()}" }
    }
    Column(Modifier.fillMaxWidth().testTag("body_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Körpermessungen", style = MaterialTheme.typography.headlineSmall)
        Text("Manuelle Werte bleiben auf diesem Gerät. Messungen und Geräteschätzungen sind getrennt gekennzeichnet. Möglichst unter vergleichbaren Bedingungen messen; Morgen- und Abendwerte können abweichen.")
        Text("Diese Angaben ändern keine Ernährungsziele, Verzehre oder Vorräte.")
        state.message?.let { Text(it, Modifier.testTag("body_message")) }
        if (state.busy || state.history == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        val run = state.run
        if (run != null) {
            Text(if (run.kind == "SAVE") "Vorgang gespeichert. Ein erneuter Aufruf erzeugt keine zweite Messung."
                else "Abgeschlossener Vorgang ohne gespeicherte Messwerte.", Modifier.testTag("body_saved"))
        } else key(operation) {
            val predecessorCurrent = correcting == null || state.history?.latest?.any { it.id == correcting?.id } == true
            if (!predecessorCurrent) Text("Diese Messung wurde inzwischen korrigiert. Öffne die aktuelle Version in der Historie.")
            BodyMeasurementEditor(correcting, state.busy || !state.keyChecked || !predecessorCurrent || state.readError || state.key != operation || state.history == null) { input, confirmed ->
                model.save(operation, input, correcting?.id, confirmed)
            }
        }
        if (!state.keyChecked && !state.busy) OutlinedButton({ model.openOperation(operation) }) { Text("Vorgangsstatus erneut prüfen") }
        OutlinedButton({ correcting = null; operation = "body-${UUID.randomUUID()}" }, enabled = !state.busy,
            modifier = Modifier.testTag("body_new")) { Text("Neue Messung beginnen") }
        val history = state.history
        Text("Messhistorie", style = MaterialTheme.typography.titleMedium)
        if (history != null && history.latest.isEmpty() && history.legacy.isEmpty())
            Text("Noch keine eigenen Körpermessungen.", Modifier.testTag("body_empty"))
        history?.latest?.take(shown)?.forEach { row ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("body_row_${row.id}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BodyMeasurementDetails(row)
                    TextButton({ showHistory = if (showHistory == row.seriesId) null else row.seriesId },
                        Modifier.testTag("body_history_${row.id}")) { Text("Korrekturhistorie anzeigen") }
                    if (showHistory == row.seriesId) history.versions.filter { it.seriesId == row.seriesId }.sortedBy { it.revision }.forEach { old ->
                        BodyMeasurementDetails(old, "body_version_${old.id}")
                    }
                    OutlinedButton({ correcting = row; operation = "body-${UUID.randomUUID()}" }, enabled = !state.busy,
                        modifier = Modifier.testTag("body_correct_${row.id}")) { Text("Diese Messung korrigieren") }
                }
            }
        }
        if ((history?.latest?.size ?: 0) > shown) OutlinedButton({ shown += 20 }) { Text("Weitere Messungen") }
        history?.legacy?.take(shown)?.forEach { row ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("Früher erfasste Messung · ${Instant.ofEpochMilli(row.measuredAt)} (UTC)")
                Text("Gewicht: ${row.weightKg?.toString() ?: "Unbekannt"} kg · Taille: ${row.waistCm?.toString() ?: "Unbekannt"} cm")
                Text("Körperfett: ${row.bodyFatPercent?.toString() ?: "Unbekannt"} % · Muskelmasse: ${row.muscleKg?.toString() ?: "Unbekannt"} kg · Wasser: ${row.waterPercent?.toString() ?: "Unbekannt"} %")
                Text("Quelle: ${row.source} · Gerät: ${row.device ?: "Unbekannt"} · Methode: ${row.method} · Qualität: ${row.quality}")
                Text("Bedingungen: ${row.conditions ?: "Unbekannt"}. Ursprünglicher lokaler Tages-/Zeitzonenbezug und genaue Muskeldefinition sind nicht überliefert; es wird nichts ergänzt.")
            } }
        }
        if ((history?.legacy?.size ?: 0) > shown) OutlinedButton({ shown += 20 }) { Text("Weitere frühere Messungen") }
        if (history != null && (history.latest.isNotEmpty() || history.legacy.isNotEmpty())) {
            var deleteApproved by remember(history.versions, history.legacy) { mutableStateOf(false) }
            Row {
                Checkbox(deleteApproved, { deleteApproved = it }, Modifier.testTag("body_delete_confirm"))
                Text("Gesamte lokale Körpermesshistorie einschließlich Korrekturen löschen")
            }
            Button({ deleteApproved = false; model.purge(true) }, enabled = deleteApproved && !state.busy && !state.readError,
                modifier = Modifier.testTag("body_delete")) { Text("Körpermesshistorie löschen") }
            Text("Die Löschung betrifft Körperwerte auf diesem Gerät. Ernährung, Vorräte und Samsung Health bleiben erhalten. Zufällige gesperrte Vorgangskennungen verhindern spätere Wiederholungen; sie enthalten keine Messwerte.")
        }
    }
}

@Composable
private fun BodyMeasurementDetails(row: BodyMeasurementVersionEntity, tag: String = "body_value_${row.id}") {
    Text("${row.name ?: BodyMetric.valueOf(row.metric).label}: ${row.rawValue ?: "Unbekannt"} ${row.unit} · Version ${row.revision}", Modifier.testTag(tag),
        style = MaterialTheme.typography.titleSmall)
    Text("${row.localDate} · ${row.localTime ?: "Uhrzeit unbekannt"} · Zeitzone: ${row.zoneId ?: "Unbekannt"}")
    Text("UTC: ${row.utc ?: "Nicht bestimmbar"} · Versatz: ${row.offsetSeconds?.let { ZoneOffset.ofTotalSeconds(it).toString() } ?: "Unbekannt"}")
    Text("Quelle: manuell · Gerät: ${row.device ?: "Unbekannt"} · Modell: ${row.model ?: "Unbekannt"}")
    Text("${BodyMethod.valueOf(row.method).label} · ${BodyQuality.valueOf(row.quality).label}")
    row.methodDetail?.let { Text("Definition / Messmethode: $it") }
    Text("Messbedingungen: ${row.conditions ?: "Unbekannt"}")
    if (row.excludedFromTrend) Text("Von künftigen Trends ausdrücklich ausgeschlossen")
    row.correctionReason?.let { Text("Korrekturgrund: $it") }
}

@Composable
private fun BodyMeasurementEditor(previous: BodyMeasurementVersionEntity?, blocked: Boolean,
    save: (BodyMeasurementInput, Boolean) -> Unit) {
    val original = previous?.input()
    var metric by remember { mutableStateOf(original?.metric ?: BodyMetric.WEIGHT) }
    var raw by remember { mutableStateOf(original?.rawValue ?: "") }
    var unknown by remember { mutableStateOf(original != null && original.rawValue == null) }
    var date by remember { mutableStateOf(original?.localDate ?: LocalDate.now().toString()) }
    var time by remember { mutableStateOf(original?.localTime ?: "") }
    var zone by remember { mutableStateOf(original?.zoneId ?: if (original == null) ZoneId.systemDefault().id else "") }
    var offset by remember { mutableStateOf(original?.explicitOffset ?: "") }
    var name by remember { mutableStateOf(original?.name ?: "") }
    var unit by remember { mutableStateOf(original?.customUnit ?: "") }
    var device by remember { mutableStateOf(original?.device ?: "") }
    var model by remember { mutableStateOf(original?.model ?: "") }
    var method by remember { mutableStateOf(original?.method ?: BodyMethod.UNKNOWN) }
    var methodDetail by remember { mutableStateOf(original?.methodDetail ?: "") }
    var conditions by remember { mutableStateOf(original?.conditions ?: "") }
    var quality by remember { mutableStateOf(original?.quality ?: BodyQuality.UNKNOWN) }
    var excluded by remember { mutableStateOf(original?.excludedFromTrend ?: false) }
    var reason by remember { mutableStateOf("") }
    fun optional(text: String) = text.takeIf(String::isNotBlank)
    val input = BodyMeasurementInput(metric, if (unknown) null else raw, date, optional(time), optional(zone), optional(offset),
        if (metric == BodyMetric.OTHER) optional(name) else null, if (metric == BodyMetric.OTHER) optional(unit) else null,
        optional(device), optional(model), method, optional(methodDetail), optional(conditions),
        if (method == BodyMethod.BIA) BodyQuality.DEVICE_ESTIMATE else quality, excluded,
        optional(reason).takeIf { previous != null })
    Text(if (previous == null) "Neue manuelle Messung" else "Korrektur zu Version ${previous.revision}",
        Modifier.testTag("body_editor_title"), style = MaterialTheme.typography.titleMedium)
    if (previous == null) BodyMetric.entries.forEach { candidate ->
        FilterChip(metric == candidate, { metric = candidate }, label = { Text(candidate.label) },
            modifier = Modifier.testTag("body_metric_${candidate.name}"))
    } else Text("${previous.name ?: metric.label} · ${previous.unit} · Originalversion bleibt erhalten")
    fun Modifier.field(tag: String) = fillMaxWidth().testTag(tag)
    OutlinedTextField(raw, { raw = it }, enabled = !unknown, label = { Text("Originalwert in ${if (metric == BodyMetric.OTHER) unit else metric.unit}") },
        modifier = Modifier.field("body_input_value"), singleLine = true)
    Row { Checkbox(unknown, { unknown = it }, Modifier.testTag("body_value_unknown")); Text("Wert ausdrücklich unbekannt") }
    if (metric == BodyMetric.OTHER && previous == null) {
        OutlinedTextField(name, { name = it }, label = { Text("Körpermaß eindeutig benennen") }, modifier = Modifier.field("body_input_name"))
        OutlinedTextField(unit, { unit = it }, label = { Text("Originaleinheit") }, modifier = Modifier.field("body_input_unit"))
    }
    OutlinedTextField(date, { date = it }, label = { Text("Messdatum JJJJ-MM-TT") }, modifier = Modifier.field("body_input_date"), singleLine = true)
    OutlinedTextField(time, { time = it }, label = { Text("Uhrzeit HH:MM[:SS] · leer = unbekannt") }, modifier = Modifier.field("body_input_time"), singleLine = true)
    OutlinedTextField(zone, { zone = it }, label = { Text("Zeitzone · leer = unbekannt") }, modifier = Modifier.field("body_input_zone"), singleLine = true)
    OutlinedTextField(offset, { offset = it }, label = { Text("UTC-Versatz bei doppelter Uhrzeit, z.B. +01:00") }, modifier = Modifier.field("body_input_offset"), singleLine = true)
    Text("Ohne bekannte Uhrzeit und Zeitzone oder Versatz wird kein UTC-Zeitpunkt erfunden. Bei der herbstlichen Zeitumstellung wähle den tatsächlich geltenden Versatz.")
    OutlinedTextField(device, { device = it }, label = { Text("Messgerät / Hersteller · optional") }, modifier = Modifier.field("body_input_device"))
    OutlinedTextField(model, { model = it }, label = { Text("Gerätemodell · optional") }, modifier = Modifier.field("body_input_model"))
    Text("Messprinzip")
    BodyMethod.entries.forEach { candidate -> FilterChip(method == candidate, { method = candidate },
        label = { Text(candidate.label) }, modifier = Modifier.testTag("body_method_${candidate.name}")) }
    OutlinedTextField(methodDetail, { methodDetail = it }, label = { Text("Messmethode / genaue Muskeldefinition") }, modifier = Modifier.field("body_input_method_detail"))
    OutlinedTextField(conditions, { conditions = it }, label = { Text("Messbedingungen, z.B. morgens vor Frühstück") }, modifier = Modifier.field("body_input_conditions"))
    Text("Qualität")
    BodyQuality.entries.forEach { candidate -> FilterChip(quality == candidate, { quality = candidate },
        label = { Text(candidate.label) }, modifier = Modifier.testTag("body_quality_${candidate.name}")) }
    if (method == BodyMethod.BIA) Text("BIA wird unabhängig von dieser Auswahl als Geräteschätzung gespeichert.")
    Row { Checkbox(excluded, { excluded = it }, Modifier.testTag("body_exclude_trend")); Text("Für spätere Trends ausschließen") }
    if (previous != null) OutlinedTextField(reason, { reason = it }, label = { Text("Grund der Korrektur") }, modifier = Modifier.field("body_input_reason"))
    // Keep fields stable while editing; only the approval subtree changes key.
    // Every input change and immutable predecessor requires a fresh gesture.
    key(input, previous?.id) {
        var approved by remember { mutableStateOf(false) }
        Row { Checkbox(approved, { approved = it }, Modifier.testTag("body_confirm")); Text("Diese Messung mit den angezeigten Angaben ausdrücklich bestätigen") }
        Button({ val accepted = approved; approved = false; save(input, accepted) }, enabled = approved && !blocked,
            modifier = Modifier.testTag("body_save")) { Text(if (previous == null) "Messung speichern" else "Korrekturversion speichern") }
    }
}
