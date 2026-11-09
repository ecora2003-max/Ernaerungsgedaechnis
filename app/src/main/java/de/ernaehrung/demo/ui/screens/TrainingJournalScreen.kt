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
import de.ernaehrung.demo.ui.FitnessPresentation
import de.ernaehrung.demo.ui.TrainingJournalViewModel
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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
    model: TrainingJournalViewModel = rememberTrainingJournalModel(), onOpenExtra: (() -> Unit)? = null,
    onOpenSourceWindow: ((HealthRecordVersionEntity) -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val fitness = rememberFitnessModel()
    val health by fitness.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val writer = rememberManualWorkoutModel()
    val write by writer.state.collectAsStateWithLifecycle()
    var operation by rememberSaveable { mutableStateOf<String?>(null) }
    var correcting by remember { mutableStateOf<ManualWorkoutVersionEntity?>(null) }
    var legacyCorrection by remember { mutableStateOf<TrainingEventEntity?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(operation) { operation?.let(writer::openOperation) }
    LaunchedEffect(write.purgeRevision) {
        if (write.purgeRevision > 0) { operation = null; correcting = null; legacyCorrection = null; expanded = null }
    }
    fun begin(row: ManualWorkoutVersionEntity? = null, legacy: TrainingEventEntity? = null) {
        correcting = row; legacyCorrection = legacy; operation = "workout-${UUID.randomUUID()}"
    }
    var shown by remember { mutableIntStateOf(20) }
    val current = snapshot?.imported.orEmpty().filter { it.visible }
    val manual = snapshot?.manualLatest.orEmpty()
    val intervals = remember(current, manual) { current.map { row ->
        WorkoutInterval(WorkoutIntervalKey(row.sourcePackage, row.externalId), row.startAt, row.endAt)
    } + manual.mapNotNull { it.interval() } }
    fun sourceTime(at: Long, offset: Int?) = offset?.let {
        Instant.ofEpochMilli(at).atOffset(ZoneOffset.ofTotalSeconds(it)).toString()
    } ?: "${Instant.ofEpochMilli(at)} (UTC; Quellversatz unbekannt)"
    Column(Modifier.fillMaxWidth().testTag("training_journal"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Trainingsjournal · lokal", style = MaterialTheme.typography.headlineSmall)
        onOpenExtra?.let { open ->
            OutlinedButton(open, modifier = Modifier.testTag("journal_open_extra")) { Text("Puls, Energie & weitere Quelldaten ansehen") }
        }
        Text("Gespeicherte Trainings mit ihrer Herkunft. Zeitliche Überschneidungen sind ein Prüfhinweis; daraus folgt weder eine Zusammenführung noch eine Summe von Trainingsenergie oder Schritten.")
        write.message?.let { Text(it, Modifier.testTag("workout_message")) }
        val dataReady = snapshot != null && !state.readError
        val keyReady = operation == null || (write.key == operation && write.keyChecked)
        OutlinedButton({ begin() }, enabled = dataReady && !write.busy && keyReady,
            modifier = Modifier.testTag("workout_new")) { Text("Abgeschlossenes Training manuell erfassen") }
        operation?.let { opKey ->
            if (write.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (write.keyChecked && write.key == opKey && write.run?.kind != "OPEN") {
                Text(if (write.run?.kind == "SAVE") "Trainingsversion bereits gespeichert. Derselbe Vorgang erzeugt keine zweite Buchung."
                    else "Dieser Vorgang ist abgeschlossen oder nach einer Löschung gesperrt.", Modifier.testTag("workout_saved"))
            } else key(opKey) {
                val predecessorCurrent = (correcting == null || manual.any { it.id == correcting?.id }) &&
                    (legacyCorrection == null || snapshot?.let { s -> s.legacy.any { it.id == legacyCorrection?.id } && legacyCorrection?.id !in s.correctedLegacyIds } == true)
                if (!predecessorCurrent) Text("Dieses Training wurde inzwischen korrigiert. Aktuelle Version im Verlauf neu öffnen.")
                val original = correcting?.input(snapshot?.manualSets.orEmpty()) ?: legacyCorrection?.let { old ->
                    ManualWorkoutInput(old.sport, FitnessTimestampInput(""), old.durationMinutes?.toString(),
                        intensity = old.intensity, effort = old.perceivedExertion?.let { "$it · Skala nicht überliefert" },
                        note = old.note, sets = snapshot?.sets.orEmpty().filter { it.trainingId == old.id }.map {
                            ManualWorkoutSetInput(it.exercise, it.repetitions?.toString(), it.loadKg?.toString())
                        })
                }
                ManualWorkoutEditor(original, correcting != null || legacyCorrection != null, legacyCorrection != null,
                    !dataReady || !predecessorCurrent || write.busy || !write.keyChecked || write.key != opKey,
                    intervals, correcting?.let { WorkoutIntervalKey("MANUAL", it.id) }) { input, confirmed ->
                    writer.save(opKey, input, correcting?.id, legacyCorrection?.id, confirmed)
                }
            }
            if (!write.keyChecked && !write.busy) OutlinedButton({ writer.openOperation(opKey) },
                Modifier.testTag("workout_retry_key")) { Text("Vorgangsstatus erneut prüfen") }
            OutlinedButton({ operation = null; correcting = null; legacyCorrection = null }, enabled = !write.busy && keyReady) { Text("Formular schließen") }
        }
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
                Text(if (snapshot.importEnabled) "Automatische Übernahme ist freigegeben. Beim Öffnen liest dieses Journal nur die gespeicherte Kopie. Eine Dauerprüfung liest Health Connect erst nach deinem Druck auf die zugehörige Schaltfläche."
                    else "Automatische Übernahme ist ausgeschaltet. Die gespeicherte Kopie bleibt lesbar.")
                health.message?.let { Text(it, Modifier.testTag("training_duration_message")) }
                Text("${current.size} in der lokalen Kopie sichtbare Trainings", Modifier.testTag("training_imported_count"))
                current.take(shown).forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("training_imported_${row.externalId}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.title?.takeIf { it.isNotBlank() } ?: "Training aus Samsung Health", style = MaterialTheme.typography.titleSmall)
                            Text("Trainingsart: ${FitnessPresentation.sport(row.exerciseType)}")
                            Text("${sourceTime(row.startAt, row.startOffsetSeconds)} bis ${sourceTime(row.endAt, row.endOffsetSeconds)}")
                            Text("Zeitspanne zwischen Quellbeginn und -ende: ${FitnessPresentation.duration(row.endAt - row.startAt)}")
                            WorkoutDurationInfo(row, health.cache, health.busy || health.awaitingPermission) { fitness.readWorkoutDuration(row) }
                            Text("Quelle: ${row.sourcePackage} · ${row.manufacturer ?: "Hersteller unbekannt"} · ${row.model ?: "Modell unbekannt"} · ${FitnessPresentation.device(row.deviceType)}")
                            Text(FitnessPresentation.recordingMethod(row.recordingMethod))
                            Text("Lokale Quellversion ${row.revision} · Quelländerung ${Instant.ofEpochMilli(row.sourceModifiedAt)}")
                            row.notes?.let { Text(it) }
                            val interval = WorkoutInterval(WorkoutIntervalKey(row.sourcePackage, row.externalId), row.startAt, row.endAt)
                            val overlaps = WorkoutOverlaps.with(interval, intervals)
                            if (overlaps.isNotEmpty()) Text("Zeitliche Überschneidung mit ${overlaps.size} weiterem gespeicherten Training. Prüfe die Quellen; es wird nichts automatisch addiert oder entfernt.",
                                Modifier.testTag("training_overlap_${row.externalId}"))
                            Text("Intensität: nicht überliefert. Puls, Distanz und Energie liegen in getrennten Health-Connect-Typen und werden nicht automatisch diesem Training zugeordnet.")
                            onOpenSourceWindow?.let { open ->
                                OutlinedButton({ open(row) }, modifier = Modifier.testTag("journal_source_window_${row.externalId}")) { Text("Quelldaten im Trainingszeitfenster ansehen") }
                            }
                        }
                    }
                }
                val deferred = snapshot.imported.count {
                    !it.visible && it.visibilityReason == FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE
                }
                if (deferred > 0) Text("Zurückgestellte Quelltrainings: $deferred. Bei ihrer letzten Prüfung lag das Ende nach dem Lesestand. Sie zählen nicht als abgeschlossene Trainings. Originale und Historie sind über Fitness erreichbar.",
                    Modifier.testTag("training_deferred_count"))
                val hidden = snapshot.imported.count {
                    !it.visible && it.visibilityReason == FitnessRecordVisibility.NOT_VISIBLE_IN_WINDOW
                }
                if (hidden > 0) Text("$hidden importierte Trainings waren im vollständig abgeglichenen Fenster nicht mehr sichtbar. Ihre Quellhistorie bleibt über Fitness erreichbar.",
                    Modifier.testTag("training_missing_count"))
                Text("Manuell erfasste Trainings", style = MaterialTheme.typography.titleMedium)
                if (manual.isEmpty() && snapshot.legacy.isEmpty())
                    Text("Noch keine manuellen oder früheren Trainings gespeichert.", Modifier.testTag("workout_empty"))
                manual.take(shown).forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("training_manual_${row.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ManualWorkoutDetails(row, snapshot.manualSets)
                            row.interval()?.let { own ->
                                val overlaps = WorkoutOverlaps.with(own, intervals)
                                if (overlaps.isNotEmpty()) Text("Zeitliche Überschneidung mit ${overlaps.size} weiterem gespeicherten Training. Keine automatische Addition oder Zusammenführung.",
                                    Modifier.testTag("training_overlap_manual_${row.id}"))
                            }
                            TextButton({ expanded = if (expanded == row.seriesId) null else row.seriesId },
                                Modifier.testTag("workout_history_${row.id}")) { Text("Originale und Korrekturversionen anzeigen") }
                            if (expanded == row.seriesId) {
                                val versions = snapshot.manualVersions.filter { it.seriesId == row.seriesId }.sortedBy { it.revision }
                                versions.firstNotNullOfOrNull { it.legacyOriginId }?.let { id ->
                                    snapshot.legacy.find { it.id == id }?.let { old ->
                                        Text("Unverändertes früheres Original: ${old.sport} · ${Instant.ofEpochMilli(old.occurredAt)} UTC · Dauer ${old.durationMinutes ?: "Unbekannt"} min · ${old.source.label} · ${old.quality.label}", Modifier.testTag("workout_legacy_original_${old.id}"))
                                        Text("Originalintensität: ${old.intensity ?: "Unbekannt"} · Belastung: ${old.perceivedExertion ?: "Unbekannt"} (Skala unbekannt)")
                                        Text("Originale Energieschätzung: ${old.estimatedKcal ?: "Unbekannt"} kcal · ${old.energySource ?: "Herkunft unbekannt"}")
                                        Text("Originalnotiz: ${old.note ?: "Unbekannt"}")
                                        Text("Früher gespeichert: ${Instant.ofEpochMilli(old.recordedAt)} UTC")
                                        snapshot.sets.filter { it.trainingId == old.id }.forEach { set ->
                                            Text("Originalsatz ${set.position}: ${set.exercise} · Wiederholungen ${set.repetitions ?: "Unbekannt"} · Last ${set.loadKg ?: "Unbekannt"} kg")
                                        }
                                    }
                                }
                                versions.forEach { old -> ManualWorkoutDetails(old, snapshot.manualSets, "workout_version_${old.id}") }
                            }
                            OutlinedButton({ begin(row = row) }, enabled = !write.busy && keyReady,
                                modifier = Modifier.testTag("workout_correct_${row.id}")) { Text("Training korrigieren") }
                        }
                    }
                }
                Text("Früher erfasste Trainings", style = MaterialTheme.typography.titleMedium)
                snapshot.legacy.filterNot { it.id in snapshot.correctedLegacyIds }.take(shown).forEach { row ->
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
                            OutlinedButton({ begin(legacy = row) }, enabled = !write.busy && keyReady,
                                modifier = Modifier.testTag("workout_correct_legacy_${row.id}")) { Text("Früheres Training korrigieren") }
                        }
                    }
                }
                if (current.isEmpty() && snapshot.legacy.isEmpty() && manual.isEmpty()) Text("Noch keine lokalen Trainings gespeichert.", Modifier.testTag("training_empty"))
                if (current.size > shown || snapshot.legacy.size > shown || manual.size > shown) OutlinedButton({ shown += 20 }) { Text("Weitere Trainings anzeigen") }
                if (snapshot.manualVersions.isNotEmpty() || snapshot.legacy.isNotEmpty()) {
                    var deleteApproved by remember(snapshot.manualVersions, snapshot.manualSets, snapshot.legacy, snapshot.sets) { mutableStateOf(false) }
                    Row {
                        Checkbox(deleteApproved, { deleteApproved = it }, Modifier.testTag("workout_delete_confirm"))
                        Text("Ganze manuelle und frühere Trainingshistorie einschließlich aller Korrekturen und Kraftsätze löschen")
                    }
                    Button({ deleteApproved = false; writer.purge(true) }, enabled = deleteApproved && !write.busy && !state.readError,
                        modifier = Modifier.testTag("workout_delete")) { Text("Manuelle und frühere Trainingshistorie löschen") }
                    Text("Samsung-Health-Kopie, Körpermessungen, Ernährung und Vorräte bleiben erhalten. Gesperrte zufällige Vorgangskennungen enthalten keine Trainingswerte und verhindern spätes Wiederherstellen.")
                }
            }
        }
        Text("Der Verlauf bleibt auch bei ausgeschalteter Übernahme lokal lesbar. Fitness enthält die Einstellungen zur Gesundheitsverbindung und die Quellhistorie.")
        OutlinedButton(onOpenFitness, Modifier.testTag("training_open_fitness")) { Text("Fitness, Import und Quellhistorie öffnen") }
    }
}

private fun ManualWorkoutVersionEntity.interval(): WorkoutInterval? = utc?.let { start -> endUtc?.let { end ->
    WorkoutInterval(WorkoutIntervalKey("MANUAL", id), Instant.parse(start), Instant.parse(end))
} }

@Composable
private fun ManualWorkoutDetails(row: ManualWorkoutVersionEntity, sets: List<ManualWorkoutSetEntity>, tag: String = "workout_value_${row.id}") {
    Text("${row.sport} · Dauer ${row.rawDurationMinutes ?: "Unbekannt"} min · Version ${row.revision}", Modifier.testTag(tag), style = MaterialTheme.typography.titleSmall)
    Text("${row.localDate} · ${row.localTime ?: "Uhrzeit unbekannt"} · ${row.zoneId ?: "Zeitzone unbekannt"}")
    Text("UTC-Beginn: ${row.utc ?: "Nicht bestimmbar"} · UTC-Ende: ${row.endUtc ?: "Nicht bestimmbar"}")
    Text("Eingegebener Versatz: ${row.explicitOffset ?: "Nicht angegeben"} · bestimmter Versatz: ${row.offsetSeconds?.let { ZoneOffset.ofTotalSeconds(it) } ?: "Unbekannt"}")
    Text("Distanz: ${row.rawDistanceMetres ?: "Unbekannt"} m · Intensität: ${row.intensity ?: "Unbekannt"}")
    Text("Belastung / RPE: ${row.effort ?: "Unbekannt"} · Gerät: ${row.device ?: "Unbekannt"}")
    Text("Nutzerangabe · Bedingungen: ${row.conditions ?: "Unbekannt"} · Trainingsenergie: unbekannt")
    row.note?.let { Text(it) }; row.correctionReason?.let { Text("Korrekturgrund: $it") }
    sets.filter { it.versionId == row.id }.sortedBy { it.position }.forEach { set ->
        Text("${set.exercise}: Wiederholungen ${set.rawRepetitions ?: "Unbekannt"} · Last ${set.rawLoadKg ?: "Unbekannt"} kg · ${set.effort ?: "Belastung unbekannt"}", Modifier.testTag("workout_set_value_${set.id}"))
        set.note?.let { Text(it) }
    }
}
