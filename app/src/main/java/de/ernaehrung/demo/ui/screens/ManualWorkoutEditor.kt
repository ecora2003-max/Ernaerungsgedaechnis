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
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.ManualWorkoutViewModel
import java.time.*

@Composable
internal fun rememberManualWorkoutModel(): ManualWorkoutViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ManualWorkoutViewModel(FoodStore(FoodDatabase.localSandbox(context))) as T
    } }
    return viewModel(key = "manual-workout-write", factory = factory)
}

@Composable
internal fun ManualWorkoutEditor(original: ManualWorkoutInput?, correcting: Boolean, legacy: Boolean,
    blocked: Boolean, intervals: List<WorkoutInterval>, excludedKey: WorkoutIntervalKey?,
    save: (ManualWorkoutInput, Boolean) -> Unit) {
    var sport by remember { mutableStateOf(original?.sport.orEmpty()) }
    var date by remember { mutableStateOf(original?.time?.localDate ?: LocalDate.now().toString()) }
    var time by remember { mutableStateOf(original?.time?.localTime.orEmpty()) }
    var zone by remember { mutableStateOf(original?.time?.zoneId ?: if (original == null) ZoneId.systemDefault().id else "") }
    var offset by remember { mutableStateOf(original?.time?.explicitOffset.orEmpty()) }
    var duration by remember { mutableStateOf(original?.durationMinutes.orEmpty()) }
    var distance by remember { mutableStateOf(original?.distanceMetres.orEmpty()) }
    var intensity by remember { mutableStateOf(original?.intensity.orEmpty()) }
    var effort by remember { mutableStateOf(original?.effort.orEmpty()) }
    var device by remember { mutableStateOf(original?.device.orEmpty()) }
    var conditions by remember { mutableStateOf(original?.conditions.orEmpty()) }
    var note by remember { mutableStateOf(original?.note.orEmpty()) }
    var sets by remember { mutableStateOf(original?.sets.orEmpty()) }
    var reason by remember { mutableStateOf("") }
    fun optional(text: String) = text.takeIf(String::isNotBlank)
    val input = ManualWorkoutInput(sport, FitnessTimestampInput(date, optional(time), optional(zone), optional(offset)),
        optional(duration), optional(distance), optional(intensity), optional(effort), optional(device), optional(conditions),
        optional(note), sets, optional(reason).takeIf { correcting })
    fun Modifier.field(tag: String) = fillMaxWidth().testTag(tag)
    Text(if (correcting) "Trainingskorrektur · Original bleibt erhalten" else "Abgeschlossenes Training manuell erfassen",
        Modifier.testTag("workout_editor_title"), style = MaterialTheme.typography.titleMedium)
    if (legacy) Text("Ursprüngliche Ortszeit ist nicht überliefert. Messdatum und Zeitbezug selbst prüfen; es wird kein Datum aus UTC vorbelegt.")
    OutlinedTextField(sport, { sport = it }, label = { Text("Sportart / Training") }, modifier = Modifier.field("workout_sport"))
    OutlinedTextField(date, { date = it }, label = { Text("Trainingsdatum JJJJ-MM-TT") }, modifier = Modifier.field("workout_date"), singleLine = true)
    OutlinedTextField(time, { time = it }, label = { Text("Beginn HH:MM[:SS] · leer = unbekannt") }, modifier = Modifier.field("workout_time"), singleLine = true)
    OutlinedTextField(zone, { zone = it }, label = { Text("Zeitzone · leer = unbekannt") }, modifier = Modifier.field("workout_zone"), singleLine = true)
    OutlinedTextField(offset, { offset = it }, label = { Text("UTC-Versatz bei doppelter Ortszeit, z.B. +01:00") }, modifier = Modifier.field("workout_offset"), singleLine = true)
    OutlinedTextField(duration, { duration = it }, label = { Text("Dauer in Minuten · leer = unbekannt") }, modifier = Modifier.field("workout_duration"), singleLine = true)
    OutlinedTextField(distance, { distance = it }, label = { Text("Distanz in Metern · leer = unbekannt") }, modifier = Modifier.field("workout_distance"), singleLine = true)
    OutlinedTextField(intensity, { intensity = it }, label = { Text("Intensität in eigenen Worten · optional") }, modifier = Modifier.field("workout_intensity"))
    OutlinedTextField(effort, { effort = it }, label = { Text("Belastung / RPE mit verwendeter Skala · optional") }, modifier = Modifier.field("workout_effort"))
    OutlinedTextField(device, { device = it }, label = { Text("Verwendetes Gerät · optional") }, modifier = Modifier.field("workout_device"))
    OutlinedTextField(conditions, { conditions = it }, label = { Text("Bedingungen · optional") }, modifier = Modifier.field("workout_conditions"))
    OutlinedTextField(note, { note = it }, label = { Text("Trainingsnotiz · optional") }, modifier = Modifier.field("workout_note"))
    sets.forEachIndexed { index, set ->
        fun update(next: ManualWorkoutSetInput) { sets = sets.toMutableList().also { it[index] = next } }
        Text("Kraftsatz ${index + 1}", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(set.exercise, { update(set.copy(exercise = it)) }, label = { Text("Übung") }, modifier = Modifier.field("workout_set_${index}_exercise"))
        OutlinedTextField(set.repetitions.orEmpty(), { update(set.copy(repetitions = optional(it))) }, label = { Text("Wiederholungen · leer = unbekannt") }, modifier = Modifier.field("workout_set_${index}_reps"))
        OutlinedTextField(set.loadKg.orEmpty(), { update(set.copy(loadKg = optional(it))) }, label = { Text("Last in kg · leer = unbekannt") }, modifier = Modifier.field("workout_set_${index}_load"))
        OutlinedTextField(set.effort.orEmpty(), { update(set.copy(effort = optional(it))) }, label = { Text("Belastung / RPE mit Skala") }, modifier = Modifier.field("workout_set_${index}_effort"))
        OutlinedTextField(set.note.orEmpty(), { update(set.copy(note = optional(it))) }, label = { Text("Satznotiz") }, modifier = Modifier.field("workout_set_${index}_note"))
        TextButton({ sets = sets.filterIndexed { i, _ -> i != index } }) { Text("Satz ${index + 1} entfernen") }
    }
    OutlinedButton({ sets = sets + ManualWorkoutSetInput("") }, enabled = sets.size < 100,
        modifier = Modifier.testTag("workout_add_set")) { Text("Kraftsatz hinzufügen") }
    if (correcting) OutlinedTextField(reason, { reason = it }, label = { Text("Grund der Korrektur") }, modifier = Modifier.field("workout_reason"))
    val validation = runCatching { ManualWorkoutRules.normalize(input, Instant.now()) }
    val normalized = validation.getOrNull()
    val interval = normalized?.let { n -> n.time.utc?.let { start -> n.endUtc?.let { end ->
        WorkoutInterval(WorkoutIntervalKey("MANUAL_PREVIEW", "draft"), Instant.parse(start), Instant.parse(end))
    } } }
    val overlaps = interval?.let { WorkoutOverlaps.with(it, intervals.filterNot { row -> row.key == excludedKey }) }.orEmpty()
    if (overlaps.isNotEmpty()) Text("Mögliche zeitliche Überschneidung mit ${overlaps.size} gespeicherten Trainings. Quellen und Zeitangaben prüfen. Einträge werden nicht automatisch zusammengeführt oder addiert.",
        Modifier.testTag("workout_preview_overlap"))
    if (interval == null) Text("Ohne belegten Beginn und Dauer ist kein UTC-Ende oder Überschneidungscheck bestimmbar.")
    if (sport.isNotBlank() && date.isNotBlank()) validation.exceptionOrNull()?.message?.let { Text(it) }
    Text("Nutzerangabe · keine Übernahme von Uhrdaten. Fehlende Angaben bleiben unbekannt; dieses Training ändert keine Ernährung, Vorräte oder Ziele.")
    key(input, overlaps, excludedKey) {
        var approved by remember { mutableStateOf(false) }
        Row { Checkbox(approved, { approved = it }, Modifier.testTag("workout_confirm")); Text("Dieses abgeschlossene Training mit den angezeigten Angaben ausdrücklich bestätigen") }
        Button({ val accepted = approved; approved = false; save(input, accepted) }, enabled = approved && !blocked && normalized != null && (!correcting || reason.isNotBlank()),
            modifier = Modifier.testTag("workout_save")) { Text(if (correcting) "Korrekturversion speichern" else "Training speichern") }
    }
}
