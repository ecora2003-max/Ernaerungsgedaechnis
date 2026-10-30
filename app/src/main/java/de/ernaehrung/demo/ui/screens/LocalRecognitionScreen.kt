package de.ernaehrung.demo.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.recognition.*
import de.ernaehrung.demo.ui.*

@Composable
internal fun LocalRecognitionScreen(data: LocalDataState, blocked: Boolean, operationId: String,
    model: LocalDataViewModel, initialMode: String) {
    val ui by model.recognition.collectAsStateWithLifecycle()
    val guard = rememberUpdatedState(blocked); val operation = rememberUpdatedState(operationId)
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    Heading("Erkennung", "Barcode · Produktquelle · Bonfoto · Aliase")
    Hint("Lokale Testdaten. Ein Scan liefert Vorschläge. Produkt speichern, Zahlung bestätigen und Lagerzugang buchen bleiben eigene Schritte.", Sage)
    WrappingChoices(listOf("Barcode & Produkt", "Bon & OCR", "Aliase"), mode, "recognition_mode_") { mode = it }
    ui.error?.let { Hint(it) }
    val recognition = ui.data
    if (recognition == null) { if (ui.error == null) CircularProgressIndicator(); return }
    when (mode) {
        "Barcode & Produkt" -> BarcodeProductEditor(data, guard, operation, model)
        "Bon & OCR" -> ReceiptRecognitionEditor(data, recognition, guard, operation, model)
        "Aliase" -> key(operationId) { AliasEditor(data, recognition, guard, operation, model) }
    }
}

private class ScopedTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = ClipData.newRawUri("capture", input)
    }
}

@Composable
internal fun PhotoControls(forReceipt: Boolean, blocked: State<Boolean>, model: LocalDataViewModel) {
    val context = LocalContext.current.applicationContext
    val files = remember(context) { CaptureFiles(context) }
    val gateway = remember(context) { AndroidPhotoRecognition(context) }
    val photo by model.photo.collectAsStateWithLifecycle()
    var pendingCamera by rememberSaveable { mutableStateOf("") }
    var cameraReceipt by rememberSaveable { mutableStateOf(false) }
    var galleryReceipt by rememberSaveable { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(remember { ScopedTakePicture() }) { success ->
        val name = pendingCamera; pendingCamera = ""
        if (name.isNotEmpty()) {
            if (success) model.analyzePhoto(files.uri(name), cameraReceipt, "CAMERA_OCR", gateway) { files.remove(name) }
            else { files.remove(name); model.photoError("Aufnahme abgebrochen oder kein Bild gespeichert. Manuelle Eingabe bleibt möglich.") }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.analyzePhoto(uri, galleryReceipt, "GALLERY_OCR", gateway)
    }
    SaveButton(if (forReceipt) "Bon fotografieren · lokal" else "Barcode fotografieren", blocked.value || photo.busy) {
        runCatching {
            cameraReceipt = forReceipt; pendingCamera = files.create(); camera.launch(files.uri(pendingCamera))
        }.onFailure {
            if (pendingCamera.isNotEmpty()) files.remove(pendingCamera)
            pendingCamera = ""; model.photoError("Kamera konnte nicht geöffnet werden. System-Fotoauswahl oder manuelle Eingabe verwenden.")
        }
    }
    SaveButton(if (forReceipt) "Bonbild auswählen" else "Barcodebild auswählen", blocked.value || photo.busy) {
        runCatching { galleryReceipt = forReceipt; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { model.photoError("Fotoauswahl nicht verfügbar. Text beziehungsweise Barcode manuell eingeben.") }
    }
    if (photo.busy) { CircularProgressIndicator(); Text("Bild wird auf diesem Gerät ausgewertet …") }
    photo.message?.let { Hint(it) }
    Text("Fotos werden auf dem Gerät ausgewertet. Kamera über die installierte Kamera-App; Auswahl über den Systemdialog. Temporäre Kamerabilder werden nach Verarbeitung entfernt.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AliasEditor(data: LocalDataState, recognition: RecognitionData, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var kind by rememberSaveable { mutableStateOf("Bonbezeichnung") }
    var text by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf("") }
    var previous by rememberSaveable { mutableStateOf("") }
    var active by rememberSaveable { mutableStateOf(true) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    val currentData = rememberUpdatedState(data); val currentAliases = rememberUpdatedState(recognition)
    Heading("Bestätigte Zuordnungen", "Originalbezeichnung und Händler bleiben erhalten")
    Text("Nur eindeutig bestätigte aktuelle Versionen werden wieder vorgeschlagen. Andere Händler, Varianten, Mengen und mehrdeutige Zuordnungen bleiben prüfpflichtig.")
    Fold("Alias anlegen / berichtigen / zurücknehmen") {
        val histories = currentAliases.value.aliases
        IdPicker("Vorhandene Aliasentscheidung", listOf("" to "Neuer Alias") + histories.filter { !it.replaced }.map {
            it.alias.id to "${it.alias.text} · ${it.alias.merchant ?: "ohne Händler"} · ${it.alias.id.take(8)}"
        }, previous) { id ->
            previous = id; val old = histories.find { it.alias.id == id }
            text = old?.alias?.text.orEmpty(); merchant = old?.alias?.merchant.orEmpty(); version = old?.alias?.versionId.orEmpty()
            kind = if (old?.alias?.barcode != null) "Barcode" else "Bonbezeichnung"
            active = true; confirmed = false; reason = ""
        }
        if (previous.isEmpty()) WrappingChoices(listOf("Bonbezeichnung", "Barcode"), kind, "alias_kind_") {
            kind = it; merchant = ""; confirmed = false
        }
        Field("Aliastext / Barcode", text, { text = it; confirmed = false })
        if (kind == "Bonbezeichnung") Field("Händlerbindung des Alias · optional", merchant, { merchant = it; confirmed = false })
        IdPicker("Artikelversion des Alias", currentData.value.versions.map { it.id to "${it.name} · v${it.revision} · ${it.id.take(8)}" }, version) { version = it; confirmed = false }
        if (previous.isNotEmpty()) Check("Diese Zuordnung weiter verwenden", active) { active = it; confirmed = false }
        Field("Grund der Aliasentscheidung", reason, { reason = it; confirmed = false })
        Check("Diese genaue Produktzuordnung ausdrücklich bestätigen", confirmed) { confirmed = it }
        SaveButton("Aliasentscheidung speichern", blocked.value) {
            val key = operation.value; val accepted = confirmed; val prior = previous.takeIf(String::isNotEmpty); val enabled = active
            val input = AliasInput(version, text.trim(), merchant.trim().takeIf(String::isNotEmpty), if (kind == "Barcode") AliasKind.BARCODE else AliasKind.RECEIPT, reason = reason)
            model.save(key) { saveAlias(key, input, prior, accepted, enabled) }
        }
    }
    recognition.aliases.filter { !it.replaced }.forEach { row -> Panel {
        Section(row.alias.text, row.alias.merchant ?: "Ohne Händlerbindung")
        KV("Zuordnung", data.versions.find { it.id == row.alias.versionId }?.let { "${it.name} · v${it.revision}" } ?: "Version ungeklärt")
        KV("Status", if (row.decision?.active == false) "Zurückgenommen" else row.alias.quality.label)
        Text("${row.alias.source.label} · ${row.alias.id}")
    } }
}

internal fun matchLabel(status: AliasMatchStatus) = when (status) {
    AliasMatchStatus.CONFIRMED -> "Eindeutig bestätigter Alias · aktuelle Artikelversion"
    AliasMatchStatus.REVIEW -> "Bekannt, aber Version oder Bestätigung prüfen"
    AliasMatchStatus.AMBIGUOUS -> "Mehrdeutig · keine automatische Zuordnung"
    AliasMatchStatus.SUGGESTED -> "Ähnlicher Katalogeintrag · nur Vorschlag"
    AliasMatchStatus.UNKNOWN -> "Keine passende Zuordnung bekannt"
}
