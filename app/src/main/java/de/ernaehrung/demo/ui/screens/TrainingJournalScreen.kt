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
import de.ernaehrung.demo.ui.TrainingJournalViewModel
import java.time.Instant
import java.time.ZoneOffset

@Composable
private fun rememberTrainingJournalModel(): TrainingJournalViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TrainingJournalViewModel(FoodStore(FoodDatabase.localSandbox(context))) as T
    } }
    return viewModel(key = "local-training-journal", factory = factory)
}

/** Reader of published legacy tables and the latest private HC cache. */
@Composable
internal fun TrainingJournalScreen(onOpenFitness: () -> Unit,
    model: TrainingJournalViewModel = rememberTrainingJournalModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    var shown by remember { mutableIntStateOf(20) }
    val current = snapshot?.imported.orEmpty().filter { it.visible }
    val intervals = remember(current) { current.map { row ->
        WorkoutInterval(WorkoutIntervalKey(row.sourcePackage, row.externalId), row.startAt, row.endAt)
    } }
    fun sourceTime(at: Long, offset: Int?) = offset?.let {
        Instant.ofEpochMilli(at).atOffset(ZoneOffset.ofTotalSeconds(it)).toString()
    } ?: "${Instant.ofEpochMilli(at)} (UTC; Quellversatz unbekannt)"
    Column(Modifier.fillMaxWidth().testTag("training_journal"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Trainingsjournal · lokal", style = MaterialTheme.typography.headlineSmall)
        Text("Gespeicherte Trainings mit ihrer Herkunft. Zeitliche Überschneidungen sind ein Prüfhinweis; daraus folgt weder eine Zusammenführung noch eine Summe von Trainingsenergie oder Schritten.")
        when {
            state.readError -> {
                Text("Das lokale Trainingsjournal ist gerade nicht lesbar.")
                OutlinedButton({ model.retry() }) { Text("Lokalen Verlauf erneut lesen") }
            }
            snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                Text("Samsung Health · lokale Kopie", style = MaterialTheme.typography.titleMedium)
                Text("Letzter erfolgreicher Import: ${snapshot.lastSyncAt?.let { Instant.ofEpochMilli(it).toString() } ?: "Noch keiner"}")
                if (snapshot.lastWindowStart != null && snapshot.lastWindowEnd != null)
                    Text("Zuletzt vollständig abgeglichen: ${Instant.ofEpochMilli(snapshot.lastWindowStart)} bis ${Instant.ofEpochMilli(snapshot.lastWindowEnd)}. Ältere Einträge bleiben erhalten und wurden außerhalb dieses Fensters nicht neu abgeglichen.")
                Text(if (snapshot.importEnabled) "Automatische Übernahme ist freigegeben. Dieses Journal liest nur die gespeicherte Kopie."
                    else "Automatische Übernahme ist ausgeschaltet. Die gespeicherte Kopie bleibt lesbar.")
                Text("${current.size} in der lokalen Kopie sichtbare Trainings", Modifier.testTag("training_imported_count"))
                current.take(shown).forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("training_imported_${row.externalId}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.title?.takeIf { it.isNotBlank() } ?: "Training aus Samsung Health", style = MaterialTheme.typography.titleSmall)
                            Text("Trainingsart: ${FitnessPresentation.sport(row.exerciseType)}")
                            Text("${sourceTime(row.startAt, row.startOffsetSeconds)} bis ${sourceTime(row.endAt, row.endOffsetSeconds)}")
                            Text("Dauer: ${FitnessPresentation.duration(row.endAt - row.startAt)}")
                            Text("Quelle: ${row.sourcePackage} · ${row.manufacturer ?: "Hersteller unbekannt"} · ${row.model ?: "Modell unbekannt"} · ${FitnessPresentation.device(row.deviceType)}")
                            Text(FitnessPresentation.recordingMethod(row.recordingMethod))
                            Text("Lokale Quellversion ${row.revision} · Quelländerung ${Instant.ofEpochMilli(row.sourceModifiedAt)}")
                            row.notes?.let { Text(it) }
                            val interval = WorkoutInterval(WorkoutIntervalKey(row.sourcePackage, row.externalId), row.startAt, row.endAt)
                            val overlaps = WorkoutOverlaps.with(interval, intervals)
                            if (overlaps.isNotEmpty()) Text("Zeitliche Überschneidung mit ${overlaps.size} weiterem importierten Training. Prüfe die Quellen; es wird nichts automatisch addiert oder entfernt.",
                                Modifier.testTag("training_overlap_${row.externalId}"))
                            Text("Distanz, Intensität, Herzfrequenz und Trainingsenergie: im aktuellen Import nicht überliefert.")
                        }
                    }
                }
                val hidden = snapshot.imported.count { !it.visible }
                if (hidden > 0) Text("$hidden importierte Trainings waren im vollständig abgeglichenen Fenster nicht mehr sichtbar. Ihre Quellhistorie bleibt über Fitness erreichbar.",
                    Modifier.testTag("training_missing_count"))
                Text("Früher erfasste Trainings", style = MaterialTheme.typography.titleMedium)
                snapshot.legacy.take(shown).forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("training_legacy_${row.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.sport, style = MaterialTheme.typography.titleSmall)
                            Text("Überlieferter Ereigniszeitpunkt: ${Instant.ofEpochMilli(row.occurredAt)} (UTC)")
                            Text("Dauer: ${row.durationMinutes?.toString() ?: "Unbekannt"} min")
                            Text("Intensität: ${row.intensity ?: "Unbekannt"} · Belastungsangabe: ${row.perceivedExertion?.toString() ?: "Unbekannt"} (Skala nicht überliefert)")
                            Text("Überlieferte Energieschätzung: ${row.estimatedKcal?.toString() ?: "Unbekannt"} kcal · Herkunft: ${row.energySource ?: "Unbekannt"}",
                                Modifier.testTag("training_legacy_energy_${row.id}"))
                            Text("Quelle: ${row.source.label} · Qualität: ${row.quality.label}")
                            Text("Gerät, ursprünglicher lokaler Tagesbezug und genaue Start-/Endsemantik sind nicht überliefert. Überlappungen und aktive gegenüber Gesamtenergie sind damit nicht bestimmbar.")
                            row.note?.let { Text(it) }
                            snapshot.sets.filter { it.trainingId == row.id }.forEach { set ->
                                Text("${set.exercise}: Wiederholungen ${set.repetitions?.toString() ?: "Unbekannt"} · Last ${set.loadKg?.toString() ?: "Unbekannt"} kg",
                                    Modifier.testTag("training_set_${set.id}"))
                            }
                        }
                    }
                }
                if (current.isEmpty() && snapshot.legacy.isEmpty()) Text("Noch keine lokalen Trainings gespeichert.", Modifier.testTag("training_empty"))
                if (current.size > shown || snapshot.legacy.size > shown) OutlinedButton({ shown += 20 }) { Text("Weitere Trainings anzeigen") }
            }
        }
        Text("Der Verlauf bleibt auch bei ausgeschalteter Übernahme lokal lesbar. Fitness enthält die Einstellungen zur Gesundheitsverbindung und die Quellhistorie.")
        OutlinedButton(onOpenFitness, Modifier.testTag("training_open_fitness")) { Text("Fitness, Import und Quellhistorie öffnen") }
    }
}
