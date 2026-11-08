package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import de.ernaehrung.demo.data.ai.*
import de.ernaehrung.demo.data.integration.RecipeAiExchange
import de.ernaehrung.demo.ui.*

@Composable
internal fun DirectRecipeAiControls(model: DirectRecipeAiViewModel, state: DirectRecipeAiState,
    request: RecipeAiExchange.Prepared?, currentInputBinding: String?, editingEnabled: Boolean) {
    var cloudConsent by remember(request?.requestId, currentInputBinding, state.binding, editingEnabled) { mutableStateOf(false) }
    var downloadConsent by remember(state.mode, state.info.status) { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    Section("Direkt mit Gemini entwerfen", "Lokales Modell oder ausdrücklich gewählte optionale Cloud")
    IdPicker("Direktes Rezeptmodell", DirectRecipeMode.entries.map { it.name to it.title }, state.mode.name) {
        if (!state.busy && editingEnabled) model.choose(DirectRecipeMode.valueOf(it))
    }
    Text("Nano 4 Fast fordert Preview/FAST an, Nano 3 Stable/FULL. Der tatsächlich geladene Modellname muss zur gewählten Version passen. Nicht verfügbare Varianten werden nicht still ersetzt. Kein Modell wird automatisch heruntergeladen.")
    Text("Status: ${when (state.info.status) {
        DirectModelStatus.NOT_CHECKED -> "Noch nicht geprüft"
        DirectModelStatus.UNAVAILABLE -> "Auf diesem Gerät nicht verfügbar · AICore/Preview-Freigabe prüfen"
        DirectModelStatus.DOWNLOADABLE -> "Modelldownload erforderlich"
        DirectModelStatus.DOWNLOADING -> "Systemdownload läuft"
        DirectModelStatus.READY -> "Gewähltes Modell bereit"
        DirectModelStatus.VERSION_MISMATCH -> "Tatsächliche Modellversion passt nicht zur Auswahl · Generierung gesperrt"
        DirectModelStatus.ERROR -> "Derzeit nicht verfügbar"
    }}", Modifier.testTag("direct_ai_status"))
    state.info.modelName?.let { Text("Von SDK/API gemeldeter Modellname: $it", Modifier.testTag("direct_ai_model_name")) }
    if (state.mode == DirectRecipeMode.CLOUD) {
        Text("Optionaler eigener Gemini-API-Schlüssel. Sicherer Serverproxy ist für eine veröffentlichte App vorzuziehen; ein auf dem Smartphone verwendeter Schlüssel bleibt auslesbar, wenn das Gerät oder die App kompromittiert wird. Kein gemeinsamer Schlüssel im APK. Google kann Anfragen nach deinem Tarif berechnen; das Ausgabetokenlimit ist kein Geldlimit.")
        Text(if (state.keyConfigured) "Eigener Schlüssel geschützt lokal eingerichtet" else "Gemini API nicht eingerichtet")
        OutlinedTextField(apiKey, { if (it.length <= 512) apiKey = it }, label = { Text("Eigener Gemini-API-Schlüssel") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.busy && editingEnabled, modifier = Modifier.fillMaxWidth().testTag("direct_ai_key"))
        OutlinedButton({ model.saveKey(apiKey); apiKey = "" }, enabled = apiKey.isNotBlank() && !state.busy && editingEnabled) { Text("Schlüssel privat speichern") }
        if (state.cloudModels.isNotEmpty()) IdPicker("Offizielles Gemini-Cloud-Modell", state.cloudModels.map { it.name to "${it.title} · ${it.name}" },
            state.selectedCloudModel.orEmpty()) { if (!state.busy) model.selectCloudModel(it) }
        Text("Modellliste prüfen überträgt nur deinen Schlüssel und Modellmetadaten an Google. Wunsch, Produktnamen, Ausschlüsse und ausgewählter eigener Kontext werden erst nach eigener Freigabe der vorbereiteten Anfrage gesendet. Keine Bons, Preise, Health-/Körper-/Trainingshistorie.")
    }
    OutlinedButton(model::check, enabled = state.loaded && !state.busy && editingEnabled &&
        (state.mode != DirectRecipeMode.CLOUD || state.keyConfigured), modifier = Modifier.testTag("direct_ai_check")) {
        Text(if (state.mode == DirectRecipeMode.CLOUD) "Offizielle Gemini-Modellliste prüfen" else "Gewählte lokale Modellvariante prüfen")
    }
    if (state.info.status == DirectModelStatus.DOWNLOADABLE && state.mode != DirectRecipeMode.CLOUD) {
        Check("Download dieser Modellvariante über AICore ausdrücklich beginnen", downloadConsent) { downloadConsent = it }
        OutlinedButton({ model.download(downloadConsent); downloadConsent = false }, enabled = downloadConsent && !state.busy && editingEnabled) { Text("Gewähltes lokales Modell herunterladen") }
        Text("Modelle können groß sein und Wi-Fi benötigen. Preview kann eine eigene AICore-Teilnahme erfordern. Abbrechen beendet die Beobachtung; ein bereits gestarteter Systemdownload kann weiterlaufen.")
    }
    state.downloadedBytes?.let { Text("Heruntergeladene Bytes: $it") }
    if (request != null) {
        Text("Für direkte Generierung wird genau die unten vorbereitete und angezeigte Anfrage verwendet. Die Antwort wird über dieselbe unabhängige v1-/Produktversions-/Allergenprüfung eingelesen. Speichern, Herstellung und Verzehr bleiben getrennte ausdrücklich bestätigte Schritte.")
        if (state.mode == DirectRecipeMode.CLOUD) Check("Diesen angezeigten Wunsch und ausgewählten Kontext jetzt an Google senden; mögliche API-Kosten akzeptieren", cloudConsent) { cloudConsent = it }
        Button({
            val consent = if (state.mode == DirectRecipeMode.CLOUD) GeminiCloudConsent(request.requestId, request.inputBinding,
                state.revision, state.selectedCloudModel.orEmpty(), cloudConsent) else null
            cloudConsent = false
            model.generate(request, currentInputBinding.orEmpty(), consent)
        }, enabled = editingEnabled && state.ready && currentInputBinding == request.inputBinding &&
            (state.mode != DirectRecipeMode.CLOUD || cloudConsent), modifier = Modifier.testTag("direct_ai_generate")) { Text("Mit ausgewähltem Modell direkt generieren") }
    } else Text("Wunsch, Produktauswahl und Vorgaben prüfen und die KI-Anfrage unten lokal vorbereiten.")
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.configurationBusy) OutlinedButton(model::cancelRequest) { Text("Modellvorgang abbrechen") }
    }
    state.message?.let { Text(it, Modifier.testTag("direct_ai_message")) }
    OutlinedButton(model::clearConfiguration, enabled = !state.busy, modifier = Modifier.testTag("direct_ai_forget")) { Text("Private Modellkonfiguration verwerfen") }
}
