package de.ernaehrung.demo.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.backup.AndroidBackupDocuments
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.Instant

@Composable
private fun rememberBackupModel(): BackupViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BackupViewModel(FoodStore(FoodDatabase.localSandbox(context)), AndroidBackupDocuments(context.contentResolver), context.packageName) as T
    } }
    return viewModel(key = "local-encrypted-backup", factory = factory)
}

@Composable
internal fun BackupScreen(providedModel: BackupViewModel? = null, onFileWritten: (Uri) -> Unit = {}) {
    val model = providedModel ?: rememberBackupModel()
    val state by model.state.collectAsStateWithLifecycle()
    var exportPassword by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }
    var includeFitness by remember { mutableStateOf(false) }
    var exportConfirmed by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var restoreConfirmed by remember { mutableStateOf(false) }
    var restoreFitnessConfirmed by remember { mutableStateOf(false) }
    var launchedExportId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val requestId = launchedExportId; launchedExportId = null
        model.finishExport(requestId, uri, onFileWritten)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedFile = uri; importPassword = ""; restoreConfirmed = false; restoreFitnessConfirmed = false
        model.clearPreview()
    }
    LaunchedEffect(state.exportRequest?.id) {
        state.exportRequest?.let { request -> if (model.consumeExportRequest(request.id)) {
            exportPassword = ""; repeatedPassword = ""; exportConfirmed = false
            launchedExportId = request.id
            exportPicker.launch(request.suggestedName)
        } }
    }
    LaunchedEffect(state.preview?.digest) { restoreConfirmed = false; restoreFitnessConfirmed = false }
    Heading("Daten & Sicherungen", "Deine bestätigten lokalen Daten verschlüsselt aufbewahren")
    Text("Keine automatischen Backups oder Exporte. Du wählst die Datei im Android-Dateidialog; eine lokale Datei funktioniert ohne Konto. Das Sicherungspasswort ist für eine Wiederherstellung erforderlich und wird nicht gespeichert.")
    state.error?.let { Text(it, Modifier.testTag("backup_error")) }
    state.result?.let { Text(it, Modifier.testTag("backup_result")) }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("backup_busy"))
    Panel {
        Section("Verschlüsselte Sicherung erstellen")
        Text("Enthält gespeicherte Ernährung, Produkte, Chargen, Bons, Preise, Rezepte, Pläne und ihre Historien. Freitexte können persönliche Angaben enthalten. Temporäre Fotos, ungespeicherte Formulare, Berechtigungen und private Geräteschlüssel werden nicht übernommen.")
        Check("Gesundheits- und Fitnesshistorie ausdrücklich einschließen", includeFitness) {
            if (!state.busy) { includeFitness = it; exportConfirmed = false }
        }
        if (includeFitness) Text("Zusätzlich: Körpermessungen, Training und lokale Health-Connect-Kopie einschließlich Korrekturhistorien. Auch diese Daten werden verschlüsselt; wähle bewusst einen passenden Speicherort.")
        SecretField("Sicherungspasswort · 12–1024 Zeichen", "backup_export_password", exportPassword, !state.busy) {
            exportPassword = it; exportConfirmed = false
        }
        SecretField("Sicherungspasswort wiederholen", "backup_export_repeat", repeatedPassword, !state.busy) {
            repeatedPassword = it; exportConfirmed = false
        }
        Check("Diesen genauen Datenumfang verschlüsselt in einer von mir gewählten Datei speichern", exportConfirmed) {
            if (!state.busy) exportConfirmed = it
        }
        OutlinedButton({ model.export(exportPassword.toCharArray(), includeFitness, exportConfirmed) },
            enabled = !state.busy && launchedExportId == null && exportConfirmed && exportPassword.length in 12..1024 && exportPassword == repeatedPassword,
            modifier = Modifier.testTag("backup_export")) { Text("Verschlüsselt sichern · Speicherort wählen") }
    }
    Panel {
        Section("Sicherung prüfen und wiederherstellen")
        Text("Vorhandene lokale Änderungen werden nicht überschrieben. Eine abweichende Sicherung kann nur in einen leeren lokalen Datenbestand übernommen werden. Bereits vorhandene Einträge werden nicht doppelt gebucht. Die Health-Connect-Übernahme bleibt nach einer tatsächlichen Wiederherstellung ausgeschaltet und benötigt erneut deine Freigabe.")
        OutlinedButton({
            selectedFile = null; importPassword = ""; model.clearPreview(); importPicker.launch(arrayOf("application/octet-stream", "application/*"))
        }, enabled = !state.busy, modifier = Modifier.testTag("backup_choose_file")) { Text("Sicherungsdatei auswählen") }
        if (selectedFile != null) Text("Datei ausgewählt · vor der Übernahme entschlüsseln und prüfen", Modifier.testTag("backup_file_selected"))
        SecretField("Passwort der gewählten Sicherung", "backup_import_password", importPassword, !state.busy) {
            importPassword = it; restoreConfirmed = false; restoreFitnessConfirmed = false; model.clearPreview()
        }
        OutlinedButton({ selectedFile?.let { model.preview(it, importPassword.toCharArray()); importPassword = "" } },
            enabled = !state.busy && selectedFile != null && importPassword.length in 12..1024,
            modifier = Modifier.testTag("backup_preview")) { Text("Datei entschlüsseln und prüfen") }
        state.preview?.let { preview ->
            val archive = preview.archive
            Text("Geprüfte Sicherung vom ${Instant.ofEpochMilli(archive.createdAt)}", Modifier.testTag("backup_preview_ready"))
            if (preview.olderSchema) Text("Ältere Sicherung: Alle Originaldaten bleiben erhalten. Für neu hinzugekommene Lagerkapazitätsprüfungen werden keine Angaben erfunden.", Modifier.testTag("backup_older_schema"))
            fun rows(name: String) = archive.tables.find { it.name == name }?.rows?.size ?: 0
            Text("${rows("products")} Produkte · ${rows("batches")} historische Chargen · ${rows("consumption_events")} Verzehrereignisse · ${rows("purchase_events")} Kaufereignisse")
            Text(if (archive.fitnessIncluded) "Gesundheits-/Fitnesshistorie eingeschlossen" else "Gesundheits-/Fitnesshistorie ausgeschlossen")
            Text("Alle ursprünglichen IDs, Versionen, unbekannten Werte und Buchungsbezüge bleiben erhalten. Die Übernahme erzeugt keine zusätzlichen Käufe, Verzehre oder Bestandsbewegungen.")
            if (archive.fitnessIncluded) Check("Enthaltene Gesundheits-/Fitnesshistorie ausdrücklich lokal wiederherstellen", restoreFitnessConfirmed) {
                if (!state.busy) restoreFitnessConfirmed = it
            }
            Check("Genau diese geprüfte Sicherung ausdrücklich wiederherstellen", restoreConfirmed) {
                if (!state.busy) restoreConfirmed = it
            }
            OutlinedButton({ model.restore(preview.digest, restoreConfirmed, restoreFitnessConfirmed) },
                enabled = !state.busy && restoreConfirmed && (!archive.fitnessIncluded || restoreFitnessConfirmed),
                modifier = Modifier.testTag("backup_restore")) { Text("Bestätigte Sicherung übernehmen") }
        }
    }
}

@Composable
private fun SecretField(label: String, tag: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), label = { Text(label) },
        enabled = enabled, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}
