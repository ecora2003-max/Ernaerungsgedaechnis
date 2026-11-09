package de.ernaehrung.demo.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.units.Power
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.FitnessPrivacyActivity
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.HealthExtraViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray

@Composable
internal fun rememberHealthExtraModel(): HealthExtraViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HealthExtraViewModel(FoodStore(FoodDatabase.localSandbox(context)), AndroidHealthExtraSource(context)) as T
    } }
    return viewModel(key = "local-health-extra", factory = factory)
}

private fun extraTime(at: Long) = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS z")
    .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
private fun extraNumber(value: Double) = value.toString().replace('.', ',')
private fun extraValue(r: HealthExtraRecord): String = when (r.kind.shape) {
    HealthExtraKind.Shape.INTEGER_SERIES -> r.samples.mapNotNull { it.bpm }.let {
        if (it.isEmpty()) "Keine Messpunkte in der Quellreihe" else "${it.size} Messpunkte · ${it.min()}–${it.max()} bpm"
    }
    HealthExtraKind.Shape.DOUBLE_SERIES -> r.samples.mapNotNull { it.doubleBits?.let(Double::fromBits) }.let {
        if (it.isEmpty()) "Keine Messpunkte in der Quellreihe" else "${it.size} Messpunkte · ${extraNumber(it.min())}–${extraNumber(it.max())} ${r.kind.unit}"
    }
    else -> r.scalarBits?.let { bits ->
        val v = Double.fromBits(bits)
        "${extraNumber(v)} ${r.kind.unit}" + if (r.kind == HealthExtraKind.BASAL_RATE)
            " · ${extraNumber(Power.watts(v).inKilocaloriesPerDay)} kcal/Tag (Quellrate)" else ""
    } ?: "Unbekannt"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HealthExtraScreen(model: HealthExtraViewModel = rememberHealthExtraModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { model.onPermissionResult() }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.onForeground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.permissionLaunch) {
        val id = state.permissionLaunch
        if (id != null && model.consumePermissionLaunch(id)) {
            try { launcher.launch(state.requestedPermissions) } catch (_: Exception) { model.permissionLaunchFailed() }
        }
    }
    var approved by remember { mutableStateOf(false) }
    var eraseApproved by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(20) }
    LaunchedEffect(state.selection) { approved = false }
    val editable = !state.busy && !state.awaitingPermission
    val selection = state.selection
    val preview = model.preview()
    Column(Modifier.fillMaxWidth().testTag("health_extra_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Weitere Gesundheitsdaten", style = MaterialTheme.typography.headlineSmall)
        Text("Wähle die Datentypen, die du zusätzlich aus Health Connect lokal übernehmen möchtest. Es werden alle von Health Connect freigegebenen Quellen gelesen – auch Telefon und Waage. Die Basisfreigabe für Samsung-Schritte und Trainings gilt hier nicht.")
        Text(when (state.availability) {
            FitnessHealthSource.Availability.AVAILABLE -> "Health Connect ist verfügbar."
            FitnessHealthSource.Availability.INSTALL_OR_UPDATE_REQUIRED -> "Health Connect muss installiert oder aktualisiert werden. Die lokale Kopie bleibt lesbar."
            FitnessHealthSource.Availability.UNSUPPORTED -> "Health Connect ist auf diesem Gerät nicht verfügbar. Die lokale Kopie bleibt lesbar."
            null -> "Health Connect wird geprüft."
        })
        val auth = state.cache?.state
        if (auth?.enabled == true) {
            Text("Gespeicherte Freigabe: ${auth.selection().kinds.joinToString { it.label }}. ${auth.windowDays} Tage, bis ${auth.endDay ?: "heute"}. Automatische Aktualisierung beim Öffnen dieser Ansicht.")
            Text("Eine geänderte Auswahl gilt erst nach „Auswahl freigeben und übernehmen“. Du kannst die gespeicherte Übernahme jederzeit ausschalten.")
            Button(model::resync, enabled = editable, modifier = Modifier.testTag("health_extra_refresh")) { Text("Gespeicherte Auswahl aktualisieren") }
            OutlinedButton(model::stop, modifier = Modifier.testTag("health_extra_stop")) { Text("Zusätzliche Übernahme ausschalten") }
        } else Text("Zusätzliche Übernahme ausgeschaltet. Gespeicherte Werte bleiben lokal lesbar.")
        Text("Auswahl", style = MaterialTheme.typography.titleMedium)
        HealthExtraKind.entries.forEach { kind ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(kind in selection.kinds, enabled = editable, onCheckedChange = { checked ->
                    model.edit(selection.copy(kinds = if (checked) selection.kinds + kind else selection.kinds - kind))
                }, modifier = Modifier.testTag("health_extra_kind_${kind.name}"))
                Text(kind.label)
            }
        }
        Text("Schritte: nur der Health-Connect-Tageswert für das gewählte Enddatum. Einzelne Telefon- und Uhrintervalle werden nicht addiert. Energiearten bleiben getrennt; Tages- und Trainingswerte werden nicht summiert oder automatisch einem Workout zugeordnet.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HealthExtraKernel.WINDOWS.sorted().forEach { days ->
                FilterChip(selected = selection.windowDays == days, enabled = editable,
                    onClick = { model.edit(selection.copy(windowDays = days)) }, label = { Text("$days T") },
                    modifier = Modifier.testTag("health_extra_days_$days"))
            }
        }
        OutlinedTextField(selection.endDay.orEmpty(), onValueChange = { model.edit(selection.copy(endDay = it.ifBlank { null })) },
            label = { Text("Enddatum JJJJ-MM-TT · leer = heute") }, enabled = editable, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("health_extra_end_day"))
        if (preview == null) Text("Wähle mindestens einen Datentyp und ein gültiges Enddatum bis heute.")
        else {
            Text("Lesefenster: ${extraTime(preview.windowStart)} bis ${extraTime(preview.windowEnd)} (${preview.zone}).")
            if (preview.needsHistory) {
                Text(if (state.historyAvailable) "Für diesen Zeitraum ist zusätzlich das Health-Connect-Leserecht für ältere Daten nötig."
                    else "Ältere Daten benötigen eine hier noch nicht verfügbare Health-Connect-Historienfunktion. Wähle einen jüngeren Zeitraum oder aktualisiere Health Connect.")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(selection.historyOptIn, onCheckedChange = { model.edit(selection.copy(historyOptIn = it)) },
                        enabled = editable && state.historyAvailable, modifier = Modifier.testTag("health_extra_history_consent"))
                    Text("Ich möchte auch ältere Quelldaten dieses Zeitraums lesen.")
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(approved, onCheckedChange = { approved = it }, enabled = editable,
                modifier = Modifier.testTag("health_extra_consent"))
            Text("Ich bestätige die lokale Übernahme dieser Auswahl und die Aktualisierung beim Öffnen dieser Ansicht.")
        }
        Button({ model.connect(approved) }, enabled = editable && approved && preview != null &&
            (!preview.needsHistory || (selection.historyOptIn && state.historyAvailable)),
            modifier = Modifier.testTag("health_extra_connect")) { Text("Auswahl freigeben und übernehmen") }
        OutlinedButton({
            try { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            catch (_: Exception) { model.permissionLaunchFailed() }
        }) { Text("Health-Connect-Rechte verwalten") }
        OutlinedButton({ context.startActivity(Intent(context, FitnessPrivacyActivity::class.java)) }) { Text("Datenschutz der Gesundheitsverbindung") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, Modifier.testTag("health_extra_message")) }
        auth?.lastErrorCode?.let { Text(FitnessImportDiagnostics.explain(it)) }
        val run = state.cache?.lastSuccess
        if (run == null) Text("Noch keine zusätzliche Gesundheitskopie übernommen.")
        else {
            Text("Letzte erfolgreiche Übernahme: ${extraTime(requireNotNull(run.capturedAt))}.")
            Text("Geprüftes Fenster: ${extraTime(requireNotNull(run.windowStart))} bis ${extraTime(requireNotNull(run.windowEnd))} (${run.zone}). Andere gespeicherte Datentypen und ältere Werte wurden dabei nicht neu geprüft.")
            if (run.day != null) {
                Text("Schritte am ${run.day} (${run.zone}): ${run.steps?.toString() ?: "unbekannt"}", Modifier.testTag("health_extra_step_day"))
                Text("Health-Connect-Tageswert · mitwirkende Quellpakete: " + run.stepSources?.let { text ->
                    val origins = JSONArray(text); (0 until origins.length()).joinToString { origins.getString(it) }.ifEmpty { "keine gemeldet" }
                })
            }
        }
        Text("Rohwerte und Quellen", style = MaterialTheme.typography.titleMedium)
        Text("Fehlende Geräteangaben bleiben unbekannt. Ein Quellpaket belegt allein kein bestimmtes Uhrenmodell. Grundumsatzrate und Energieeinträge sind Quelldaten; es wird kein TDEE berechnet und kein Ernährungsziel geändert.")
        val rows = state.cache?.records.orEmpty()
        Text("Gespeicherte Einträge: ${rows.size}. Zurückgestellte oder nicht mehr sichtbare Einträge bleiben als Historie erhalten.")
        rows.take(shown).forEach { row -> HealthExtraRecordCard(row) { model.showHistory(row) } }
        if (rows.size > shown) OutlinedButton({ shown += 20 }) { Text("Weitere Einträge anzeigen") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(eraseApproved, onCheckedChange = { eraseApproved = it }, modifier = Modifier.testTag("health_extra_erase_consent"))
            Text("Zusätzliche lokale Gesundheitskopie samt Rohwerten und Änderungshistorie löschen. Die Basisübernahme, manuelle Messungen und Daten in Health Connect bleiben erhalten.")
        }
        OutlinedButton({ model.purge(eraseApproved); eraseApproved = false }, enabled = eraseApproved,
            modifier = Modifier.testTag("health_extra_erase")) { Text("Zusätzliche lokale Kopie löschen") }
    }
    state.history?.let { history ->
        AlertDialog(onDismissRequest = model::closeHistory, title = { Text("Änderungen dieses Quelleintrags") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                history.forEach { HealthExtraRecordCard(it) }
            } }, confirmButton = { TextButton(model::closeHistory) { Text("Schließen") } })
    }
}

@Composable
private fun HealthExtraRecordCard(row: HealthExtraStored, onHistory: (() -> Unit)? = null) {
    val r = row.record
    var samplesShown by remember(row.fingerprint, row.revision) { mutableIntStateOf(0) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("${r.kind.label}: ${extraValue(r)}", style = MaterialTheme.typography.titleSmall)
        Text("${extraTime(r.startAt)}" + (r.endAt?.let { " bis ${extraTime(it)}" } ?: " · Einzelmessung"))
        Text("Quellpaket: ${r.sourcePackage}")
        Text("Hersteller: ${r.manufacturer ?: "unbekannt"} · Modell: ${r.model ?: "unbekannt"}")
        Text("Gerätetyp: ${r.deviceType?.toString() ?: "unbekannt"} · Aufzeichnungsmethode: ${r.recordingMethod}")
        fun offset(seconds: Int?) = seconds?.let { ZoneOffset.ofTotalSeconds(it).id } ?: "unbekannt"
        Text("UTC-Offset der Quelle: ${offset(r.startOffsetSeconds)}" + if (r.endAt == null) "" else " / ${offset(r.endOffsetSeconds)}")
        Text("Quelländerung: ${extraTime(r.sourceModifiedAt)} · Quellversion: ${r.clientRecordVersion} · lokale Version: ${row.revision}")
        if (!row.visible) Text(if (row.reason == FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE)
            "Zurückgestellt: Das Originalintervall endet nach dem Lesestand. Es wird nicht gekürzt oder hochgerechnet und bei späterer Übernahme erneut geprüft."
            else "Im zuletzt geprüften Fenster nicht mehr sichtbar. Historische Werte bleiben erhalten; dies belegt keine Löschung an der Quelle.")
        if (r.samples.isNotEmpty()) {
            r.samples.take(samplesShown).forEach { s -> Text("${extraTime(s.at)} · " +
                (s.bpm?.let { "$it bpm" } ?: "${s.doubleBits?.let { extraNumber(Double.fromBits(it)) } ?: "unbekannt"} ${r.kind.unit}")) }
            if (samplesShown < r.samples.size) TextButton({ samplesShown += 20 }) { Text("Messpunkte anzeigen (${r.samples.size})") }
        }
        onHistory?.let { TextButton(it) { Text("Änderungen dieses Eintrags") } }
    } }
}
