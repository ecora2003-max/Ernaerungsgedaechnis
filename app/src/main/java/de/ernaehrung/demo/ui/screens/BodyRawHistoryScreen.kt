package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.ui.BodyMeasurementViewModel
import de.ernaehrung.demo.ui.Field
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Opens only the existing local read flow; no operation key or consent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BodyRawHistoryScreen(onOpenEditor: () -> Unit, onOpenHealthExtra: () -> Unit,
    model: BodyMeasurementViewModel = rememberBodyMeasurementModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val imported by model.importedBodyFat.collectAsStateWithLifecycle(initialValue = Result.success(emptyList()))
    val today = LocalDate.now()
    var periodName by rememberSaveable { mutableStateOf(BodyHistoryPeriod.DAYS_28.name) }
    var appliedDate by rememberSaveable { mutableStateOf(today.toString()) }
    var draftDate by rememberSaveable { mutableStateOf(appliedDate) }
    val referenceDate = LocalDate.parse(appliedDate)
    val selectableDate = runCatching { LocalDate.parse(draftDate) }.getOrNull()
        ?.takeIf { it.toString() == draftDate && it <= today }
    var shown by remember(periodName, referenceDate) { mutableIntStateOf(20) }
    val period = BodyHistoryPeriod.valueOf(periodName)
    val history = state.history
    val window = remember(history, period, referenceDate) {
        history?.let { BodyRawHistory.select(it.latest.map { row -> BodyRawObservation(row.id, row.input()) }, period, referenceDate) }
    }
    Column(Modifier.fillMaxWidth().testTag("body_raw_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Körperverlauf · Rohwerte", style = MaterialTheme.typography.headlineSmall)
        Text("Jeweils die aktuelle Korrekturversion mit Originalwert und Messbedingungen. Die vollständigen Original- und Korrekturversionen findest du in der Messhistorie.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyHistoryPeriod.entries.forEach { option ->
                FilterChip(period == option, { periodName = option.name }, label = { Text(option.label) },
                    modifier = Modifier.testTag("body_raw_period_${option.name}"))
            }
        }
        if (period.days != null) {
            Text("Anzeige-Stichtag: $referenceDate (${ZoneId.systemDefault().id})")
            Field("Stichtag JJJJ-MM-TT", draftDate, { if (it.length <= 10) draftDate = it },
                modifier = Modifier.testTag("body_raw_reference_field"))
            if (selectableDate == null) Text("Gib ein gültiges Datum bis heute ein.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ appliedDate = requireNotNull(selectableDate).toString() },
                    enabled = selectableDate != null && draftDate != appliedDate,
                    modifier = Modifier.testTag("body_raw_apply_reference")) { Text("Stichtag anzeigen") }
                if (referenceDate != today || draftDate != appliedDate) TextButton({
                    appliedDate = today.toString()
                    draftDate = appliedDate
                }, modifier = Modifier.testTag("body_raw_today")) { Text("Heute") }
            }
        }
        Text(if (period.days == null) "Alle gespeicherten Messdaten, ohne Datumsgrenze."
            else "Originales lokales Messdatum vom ${window?.start ?: referenceDate.minusDays(period.days.toLong() - 1L)} bis $referenceDate, beide Tage eingeschlossen.",
            Modifier.testTag("body_raw_range"))
        Text("Messdaten werden beim Zeitzonenwechsel nicht auf andere Kalendertage verschoben. Fehlende Werte bleiben unbekannt; Lücken werden nicht aufgefüllt.")
        when {
            state.readError -> Text("Die lokale Körpermesshistorie ist gerade nicht lesbar.")
            history == null || window == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            history.latest.isEmpty() -> Text("Noch keine eigenen Körpermessungen mit überliefertem lokalem Messdatum.", Modifier.testTag("body_raw_empty"))
            else -> {
                Text("${window.series.sumOf { it.points.size }} Messungen im Zeitraum · ${window.outsideWindowCount} außerhalb",
                    Modifier.testTag("body_raw_count"))
                window.series.forEach { series ->
                    Text("${series.measure.name ?: series.measure.metric.label} · ${series.measure.unit}",
                        style = MaterialTheme.typography.titleMedium)
                    Text("${series.points.count { it.input.rawValue != null }} bekannte Werte · ${series.points.count { it.input.rawValue == null }} unbekannte Werte · ${series.points.count { it.input.excludedFromTrend }} für spätere Trends ausgeschlossen")
                    BodyRawScatter(series, window.start, window.end)
                    series.daysWithoutKnownValue?.let { missing ->
                        Text("${missing.size} Kalendertage ohne erfassten bekannten Wert. Dies ist keine Bewertung der Messqualität.",
                            Modifier.testTag("body_raw_gaps_${series.measure.metric.name}_${series.measure.name.orEmpty()}"))
                        if (missing.isNotEmpty()) Text(missing.joinToString(", "))
                    }
                    if (series.points.isEmpty()) Text("Keine Messung dieses Körpermaßes im gewählten Zeitraum.")
                    series.points.take(shown).forEach { point ->
                        val row = history.latest.first { it.id == point.id }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BodyMeasurementDetails(row, "body_raw_value_${row.id}")
                            }
                        }
                    }
                }
                if (window.series.any { it.points.size > shown }) OutlinedButton({ shown += 20 }) { Text("Weitere Rohwerte anzeigen") }
            }
        }
        if (history?.legacy?.isNotEmpty() == true) Text("${history.legacy.size} früher erfasste Zeilen bleiben in der Messhistorie erhalten. Ihr ursprünglicher lokaler Tagesbezug ist unbekannt; sie werden hier keinem Datumsfenster zugeordnet.")
        Text("Rohwertsicht ${BodyRawHistory.METHOD_VERSION}. Geräte, Methoden und Bedingungen bleiben pro Messung sichtbar. Es wird kein statistischer Trend, Fettverlust oder Energiebedarf berechnet.")
        OutlinedButton(onOpenEditor, Modifier.testTag("body_raw_open_editor")) { Text("Messung erfassen, korrigieren oder ganze Historie verwalten") }
        if (imported.isFailure) Text("Importierte Körperfettwerte sind gerade nicht lesbar.")
        imported.getOrNull()?.takeIf { it.isNotEmpty() }?.let { records ->
            ImportedBodyFatSection(records, period, referenceDate, onOpenHealthExtra)
        }
    }
}

@Composable
private fun ImportedBodyFatSection(records: List<HealthExtraStored>, period: BodyHistoryPeriod, day: LocalDate,
    onOpenHealthExtra: () -> Unit) {
    val window = remember(records, period, day) { selectImportedBodyFat(records, period, day) }
    var shown by remember(period, day) { mutableIntStateOf(20) }
    Text("Körperfettanteil aus Health Connect", Modifier.testTag("body_raw_imported"), style = MaterialTheme.typography.titleMedium)
    Text("Bereits lokal gespeicherte Quellmessungen. Messprinzip und Messbedingungen sind unbekannt.")
    BodyRawScatter(window.series, window.start, window.end)
    if (window.samples.isEmpty()) Text("Keine importierte Körperfettmessung im gewählten Datumsfenster.")
    window.samples.take(shown).forEach { ImportedBodyFatRow(it) }
    if (window.missingSourceDay.isNotEmpty()) {
        Text("Quellmessungen ohne überlieferten lokalen Tag", style = MaterialTheme.typography.titleSmall)
        window.missingSourceDay.take(shown).forEach { ImportedBodyFatRow(it) }
    }
    if (window.samples.size > shown || window.missingSourceDay.size > shown)
        OutlinedButton({ shown += 20 }) { Text("Weitere importierte Messungen anzeigen") }
    OutlinedButton(onOpenHealthExtra) { Text("Quellen und Änderungshistorie öffnen") }
}

@Composable
private fun ImportedBodyFatRow(sample: ImportedBodyFatSample) {
    val row = sample.original
    val record = row.record
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sample.value?.let { "${it.toString().replace('.', ',')} %" } ?: "Unbekannt",
                Modifier.testTag("body_raw_health_value_${record.externalId}"), style = MaterialTheme.typography.titleSmall)
            Text("Lokale Quellzeit: ${sample.sourceTime ?: "unbekannt"}")
            Text("UTC: ${Instant.ofEpochMilli(record.startAt)}")
            Text("Quellpaket: ${record.sourcePackage}")
            Text("Hersteller: ${record.manufacturer ?: "unbekannt"} · Modell: ${record.model ?: "unbekannt"}")
            Text("Lokale Version ${row.revision} · Quellversion ${record.clientRecordVersion}")
        }
    }
}
