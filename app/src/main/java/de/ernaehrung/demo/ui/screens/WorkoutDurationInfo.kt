package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.fitness.FitnessWorkoutDurationStatus
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.FitnessPresentation
import java.time.Instant

internal fun workoutDurationStatus(status: String?): String = when (status) {
    FitnessWorkoutDurationStatus.AVAILABLE.name -> "Trainingsdauer von Health Connect geliefert."
    FitnessWorkoutDurationStatus.NOT_RETURNED.name -> "Health Connect hat keine Trainingsdauer geliefert."
    FitnessWorkoutDurationStatus.SOURCE_CHANGED.name -> "Die Quelle hat sich geändert. Zuerst die Gesundheitskopie erneut synchronisieren."
    FitnessWorkoutDurationStatus.SOURCE_MISSING.name -> "Dieses Training war im gelesenen Quellfenster nicht sichtbar. Zuerst erneut synchronisieren."
    FitnessWorkoutDurationStatus.OVERLAPPING_WORKOUTS.name -> "Mehrere Quelltrainings überschneiden sich. Für dieses Quellzeitfenster wird kein Zeitwert übernommen."
    FitnessWorkoutDurationStatus.OUTSIDE_READ_WINDOW.name -> "Das Training liegt außerhalb des erlaubten Lesefensters. Es wurde kein erweitertes Leserecht angefordert."
    FitnessWorkoutDurationStatus.READ_FAILED.name -> "Die Dauer konnte nicht geprüft werden. Gespeicherte Angaben bleiben erhalten."
    else -> "Für diese Quellversion noch nicht geprüft."
}

@Composable
internal fun WorkoutDurationEvidence(run: HealthImportRunEntity, includeValue: Boolean = true) {
    if (includeValue) Text("Trainingszeit im Quellzeitfenster laut Health Connect: ${run.workoutDurationMillis?.let(FitnessPresentation::duration) ?: "Unbekannt"}")
    Text("Quelle: Samsung Health über Health Connect · Quellversion ${run.workoutRecordRevision}")
    if (run.windowStart != null && run.windowEnd != null)
        Text("Geprüftes Zeitfenster: ${Instant.ofEpochMilli(run.windowStart)} bis ${Instant.ofEpochMilli(run.windowEnd)}")
    run.capturedAt?.let { Text("Quellstand gelesen: ${Instant.ofEpochMilli(it)}") }
}

/** Existing SQLite values render without permission or provider access. */
@Composable
internal fun WorkoutDurationInfo(row: HealthRecordVersionEntity, cache: FitnessCache?, busy: Boolean, onRead: () -> Unit) {
    fun matches(run: HealthImportRunEntity) = run.workoutExternalId == row.externalId &&
        run.workoutRecordRevision == row.revision && run.workoutRecordFingerprint == row.fingerprint
    val latest = cache?.durationAttempts?.find(::matches)
    val known = cache?.knownDurations?.find(::matches)
    Text("Trainingszeit im Quellzeitfenster laut Health Connect: ${latest?.workoutDurationMillis?.let(FitnessPresentation::duration) ?: "Unbekannt"}",
        Modifier.testTag("fitness_workout_duration_${row.externalId}"))
    Text(workoutDurationStatus(latest?.workoutDurationStatus), Modifier.testTag("fitness_workout_duration_status_${row.externalId}"))
    if (latest != null) {
        Text("Letzte Dauerprüfung: ${Instant.ofEpochMilli(latest.recordedAt)}")
        if (latest.workoutDurationMillis != null) WorkoutDurationEvidence(latest, includeValue = false)
    }
    if (known != null && known.id != latest?.id) {
        Text("Früher gelieferter Wert dieser Quellversion, aktuell nicht bestätigt:", Modifier.testTag("fitness_workout_duration_previous_${row.externalId}"))
        WorkoutDurationEvidence(known)
    }
    Text("Health Connect aggregiert Samsung-Health-Trainings im gewählten Quellzeitfenster. Dieser Zeitwert kann von dessen Länge abweichen. Er ist keine separat überlieferte aktive Dauer dieses einzelnen Trainings; ältere Einheiten außerhalb des erlaubten Lesefensters lassen sich nicht ausschließen. Pausen und Belastungszeiten werden nicht geschätzt.")
    OutlinedButton(onRead, enabled = cache?.state?.enabled == true && !busy && row.visible,
        modifier = Modifier.testTag("fitness_workout_duration_read_${row.externalId}")) {
        Text("Trainingszeit im Quellzeitfenster prüfen")
    }
}
