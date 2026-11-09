package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import de.ernaehrung.demo.ui.FitnessPresentation
import de.ernaehrung.demo.ui.WorkoutSourceWindowViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun rememberWorkoutSourceWindowModel(target: WorkoutSourceTarget): WorkoutSourceWindowViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, target) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WorkoutSourceWindowViewModel(FoodStore(FoodDatabase.localSandbox(context)), target) as T
    } }
    return viewModel(key = "workout-source-${target.sourcePackage}-${target.externalId}-${target.revision}-${target.fingerprint}", factory = factory)
}

private fun windowTime(at: Long) = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS z")
    .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
private fun windowNumber(value: Double) = value.toString().replace('.', ',')

@Composable
internal fun WorkoutSourceWindowScreen(target: WorkoutSourceTarget,
    onOpenExtra: () -> Unit, onOpenTraining: () -> Unit,
    model: WorkoutSourceWindowViewModel = rememberWorkoutSourceWindowModel(target)) {
    val state by model.state.collectAsStateWithLifecycle()
    var shownMatches by remember(target) { mutableIntStateOf(20) }
    Column(Modifier.fillMaxWidth().testTag("workout_source_window"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quelldaten im Trainingszeitfenster", style = MaterialTheme.typography.headlineSmall)
        Text("Diese Ansicht liest ausschließlich die gespeicherte lokale Kopie. Sie fragt Health Connect nicht ab und fordert keine Rechte an.")
        OutlinedButton(onOpenExtra, modifier = Modifier.testTag("workout_source_open_extra")) { Text("Zusatzkopie freigeben oder aktualisieren") }
        OutlinedButton(onOpenTraining) { Text("Trainingsjournal öffnen") }
        when {
            state.readError -> {
                Text("Die lokalen Quelldaten konnten nicht gemeinsam gelesen werden.")
                OutlinedButton(model::retry, modifier = Modifier.testTag("workout_source_retry")) { Text("Lokale Kopie erneut lesen") }
            }
            state.snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                val snapshot = requireNotNull(state.snapshot)
                if (snapshot.status != WorkoutSourceWindowStatus.CURRENT) {
                    Text(when (snapshot.status) {
                        WorkoutSourceWindowStatus.CHANGED -> "Das Training wurde seit dem Öffnen korrigiert. Öffne seine aktuelle Version erneut im Trainingsjournal. Die alte Auswahl wird keiner neuen Version zugeordnet."
                        WorkoutSourceWindowStatus.NOT_VISIBLE -> "Dieses Training ist in der lokalen Kopie nicht mehr als abgeschlossen sichtbar. Es werden keine Zusatzwerte zugeordnet."
                        else -> "Dieses Training ist nicht mehr in der lokalen Kopie vorhanden. Es werden keine Zusatzwerte angezeigt."
                    }, Modifier.testTag("workout_source_stale"))
                } else {
                    val workout = requireNotNull(snapshot.workout)
                    Text(workout.title?.takeIf { it.isNotBlank() } ?: "Training aus Samsung Health", style = MaterialTheme.typography.titleMedium)
                    Text("Quellzeitfenster: ${windowTime(workout.startAt)} bis ${windowTime(workout.endAt)}")
                    Text("Quelle: ${workout.sourcePackage} · lokale Trainingsversion ${workout.revision}")
                    Text("Das Quellzeitfenster kann Pausen enthalten. Zeitliche Überschneidung und gleiches Quellpaket beweisen keine Zugehörigkeit zum Training. Es werden keine Kalorien, Distanzen oder aktiven Zeiten für das gesamte Training errechnet oder addiert.")
                    val last = snapshot.lastExtraRead
                    Text("Letzte erfolgreiche Zusatzübernahme: ${last?.capturedAt?.let(::windowTime) ?: "Noch keine"}. Einzelne behaltene Datentypen oder ältere Intervalle können außerhalb dieses letzten Leseauftrags liegen.")
                    if (!snapshot.extraState.enabled) Text("Zusätzliche Übernahme ausgeschaltet. Die gespeicherten Originale bleiben lesbar.")
                    if (snapshot.extraState.lastErrorCode != null) Text("Die letzte Zusatzübernahme war nicht erfolgreich; angezeigt wird die gespeicherte Kopie.")
                    val selected = snapshot.selection
                    Text("Passende lokale Quellenintervalle: ${selected.matches.size} · noch zurückgestellt: ${selected.deferred} · nicht mehr sichtbar: ${selected.noLongerVisible}",
                        Modifier.testTag("workout_source_counts"))
                    if (selected.matches.isEmpty()) Text("Keine abgeschlossenen passenden Zusatzdaten in der lokalen Kopie. Das bedeutet keinen Nullwert. Wähle die benötigten Typen und einen passenden Zeitraum in der Zusatzübernahme.",
                        Modifier.testTag("workout_source_empty"))
                    selected.matches.take(shownMatches).forEach { match -> WorkoutSourceMatchCard(match) }
                    if (selected.matches.size > shownMatches) OutlinedButton({ shownMatches += 20 }) { Text("Weitere Quellenintervalle anzeigen") }
                }
            }
        }
    }
}

@Composable
private fun WorkoutSourceMatchCard(match: WorkoutSourceMatch) {
    val row = match.observation; val r = row.record
    val samples = remember(match) { match.selectedSamples }
    var shown by remember(row.fingerprint) { mutableIntStateOf(0) }
    Card(Modifier.fillMaxWidth().testTag("workout_source_kind_${r.kind.name}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(r.kind.label, style = MaterialTheme.typography.titleMedium)
            Text(match.relation.label)
            Text("Originalintervall: ${windowTime(r.startAt)} bis ${windowTime(requireNotNull(r.endAt))}")
            Text("Quelle: ${r.sourcePackage} · ${r.manufacturer ?: "Hersteller unbekannt"} · ${r.model ?: "Modell unbekannt"}")
            Text("${FitnessPresentation.device(r.deviceType)} · ${FitnessPresentation.recordingMethod(r.recordingMethod)}")
            Text("Quelländerung: ${windowTime(r.sourceModifiedAt)} · lokale Zusatzversion ${row.revision}")
            if (!match.checkedInLastExtraWindow) Text("Nicht vollständig im letzten Zusatz-Leseauftrag geprüft; beibehaltener Originalstand.")
            if (r.kind.shape in setOf(HealthExtraKind.Shape.INTEGER_SERIES, HealthExtraKind.Shape.DOUBLE_SERIES)) {
                Text("${samples.size} von ${r.samples.size} Originalmesspunkten im Quellzeitfenster (Beginn eingeschlossen, Ende ausgeschlossen). Die vollständige Originalreihe bleibt erhalten.",
                    Modifier.testTag("workout_source_samples_${r.kind.name}"))
                val value = if (samples.isEmpty()) "Unbekannt · keine Messpunkte in diesem Fenster" else when (r.kind) {
                    HealthExtraKind.HEART_RATE -> samples.mapNotNull { it.bpm }.let { "${it.min()}–${it.max()} bpm · Bereich der ausgewählten Rohpunkte" }
                    else -> samples.map { Double.fromBits(requireNotNull(it.doubleBits)) }.let {
                        "${windowNumber(it.min())}–${windowNumber(it.max())} ${r.kind.unit} · Bereich der ausgewählten Rohpunkte"
                    }
                }
                Text(value, Modifier.testTag("workout_source_value_${r.kind.name}"))
                samples.take(shown).forEachIndexed { index, sample ->
                    Text("Originalpunkt ${match.sampleIndices[index] + 1}: ${windowTime(sample.at)} · " +
                        (sample.bpm?.let { "$it bpm" } ?: "${windowNumber(Double.fromBits(requireNotNull(sample.doubleBits)))} ${r.kind.unit}"))
                }
                if (shown < samples.size) TextButton({ shown += 20 }, modifier = Modifier.testTag("workout_source_expand_${r.kind.name}")) { Text("Messpunkte im Fenster anzeigen") }
            } else if (match.scalarInWindow == null) {
                Text("Kein Wert für dieses Trainingszeitfenster bestimmt. Der Originalwert gehört zu einem weiter reichenden Intervall und wird hier nicht aufgeteilt. Er bleibt in der Zusatzkopie sichtbar.",
                    Modifier.testTag("workout_source_value_${r.kind.name}"))
            } else {
                Text("${windowNumber(Double.fromBits(requireNotNull(match.scalarInWindow)))} ${r.kind.unit} · Originalwert nur für das oben angegebene Quellenintervall",
                    Modifier.testTag("workout_source_value_${r.kind.name}"))
            }
        }
    }
}
