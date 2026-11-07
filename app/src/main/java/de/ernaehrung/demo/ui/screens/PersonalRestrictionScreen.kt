package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.util.UUID

private data class RestrictionForm(val previousId: String?, val key: String, val text: String = "", val reason: String = "", val attempted: Boolean = false, val sourceBinding: String? = null)
private data class RestrictionConfirmation(val id: String, val binding: String, val key: String)
private data class RestrictionErasure(val token: String, val key: String)

@Composable
internal fun PersonalRestrictionScreen(host: LocalDataViewModel) {
    val factory = remember(host) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = host.personalRestrictionEditor() as T
    } }
    val model: PersonalRestrictionViewModel = viewModel(key = "personal-restrictions",factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val saving by model.saveState.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf<RestrictionForm?>(null) }
    var confirmation by remember { mutableStateOf<RestrictionConfirmation?>(null) }
    var erasure by remember { mutableStateOf<RestrictionErasure?>(null) }
    LaunchedEffect(saving.outcome) {
        if (saving.outcome != null) { form = null; confirmation = null; erasure = null }
    }
    val data = state.snapshot
    var personalSourceSeen by remember { mutableStateOf(false) }
    var lastPersonalBinding by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(data != null, data?.binding) {
        if (data != null) {
            if (personalSourceSeen && lastPersonalBinding != data.binding) {
                form = null; confirmation = null; erasure = null
            }
            personalSourceSeen = true; lastPersonalBinding = data.binding
        }
    }
    Heading("Persönliche Ausschlüsse", "Eigene Angaben für neue Rezeptvorschläge")
    Text("Vorschläge bleiben inaktiv. Erst die getrennte Bestätigung macht die Vorgaben für neue Offline- und KI-Rezeptprüfungen verbindlich. Jede Zutat wird weiterhin unabhängig geprüft. Eine leere Vorgabe ist kein Nachweis von Allergenfreiheit.")
    if (state.readError) {
        Hint("Eigene Vorgaben gerade nicht lesbar."); OutlinedButton(model::retry) { Text("Vorgaben neu lesen") }
    } else if (data == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    saving.error?.let { Text(it, Modifier.testTag("personal_restrictions_error")) }
    if (data != null) {
        Panel {
            Section("Bestätigte eigene Rezeptvorgaben")
            val active = data.active
            if (active == null) Text("Keine bestätigten eigenen Vorgaben.", Modifier.testTag("personal_restrictions_active_empty"))
            else {
                Text("Fassung ${active.revision}", Modifier.testTag("personal_restrictions_active"))
                Text(active.rawText.orEmpty().ifBlank { "Keine Ausschlusskennungen vorgegeben." }, Modifier.testTag("personal_restrictions_active_text"))
                Text("Eigene Eingabe · ${active.zoneId}")
            }
        }
        Button(enabled = !saving.busy, modifier = Modifier.testTag("personal_restrictions_new"),onClick = {
            model.clearResult()
            form = RestrictionForm(data.latest?.id,UUID.randomUUID().toString(),data.latest?.rawText.orEmpty(),sourceBinding = data.binding)
        }) { Text("Neuen unbestätigten Vorschlag bearbeiten") }
        form?.takeIf { it.sourceBinding == data.binding }?.let { current ->
            val changed = current.previousId != data.latest?.id
            if (changed) Hint("Profilstand geändert. Unveränderten bereits gestarteten Vorgang erneut prüfen oder den aktuellen Stand neu öffnen.")
            OutlinedTextField(current.text,{ if (!saving.busy) form = current.copy(text = it,key = UUID.randomUUID().toString(),attempted = false) },
                Modifier.fillMaxWidth().testTag("personal_restrictions_text"),enabled = !saving.busy,
                label = { Text("Kennungen, durch Komma getrennt") },minLines = 2,maxLines = 4)
            Text("Beispiele für Kennungen: ALLERGEN:SOY, DIET:VEGAN, FOOD:food-id. FOOD benötigt die genaue Lebensmitteltyp-ID; ein Produktname ist keine automatische Zuordnung.")
            OutlinedTextField(current.reason,{ if (!saving.busy) form = current.copy(reason = it,key = UUID.randomUUID().toString(),attempted = false) },
                Modifier.fillMaxWidth().testTag("personal_restrictions_reason"),enabled = !saving.busy,label = { Text("Grund für diese eigene Vorgabe") })
            Text("Ein leerer neuer Vorschlag hebt bisherige Vorgaben erst nach gesonderter Bestätigung auf.")
            Button(enabled = !saving.busy && (!changed || current.attempted),modifier = Modifier.testTag("personal_restrictions_propose"),onClick = {
                form = current.copy(attempted = true)
                model.propose(current.key,PersonalRestrictionInput(current.previousId,current.text,current.reason))
            }) { Text(if (current.attempted) "Unveränderten Vorgang erneut prüfen" else "Nur als unbestätigten Vorschlag speichern") }
            OutlinedButton(enabled = !saving.busy,onClick = { form = null }) { Text("Ungespeicherte Eingabe verwerfen") }
        }
        data.latest?.takeIf { it.status == PersonalRestrictionRules.PROPOSED }?.let { proposal ->
            Panel {
                Section("Noch nicht bestätigter Vorschlag", "Fassung ${proposal.revision}")
                Text(proposal.rawText.orEmpty().ifBlank { "Keine Ausschlusskennungen vorgeschlagen." })
                Button(enabled = !saving.busy,modifier = Modifier.testTag("personal_restrictions_review"),onClick = {
                    confirmation = RestrictionConfirmation(proposal.id,data.versionBinding(proposal.id),UUID.randomUUID().toString())
                }) { Text("Diesen Vorschlag getrennt prüfen") }
            }
        }
        Fold("Privater Vorgabenverlauf") {
            val entries = data.history.filter { it.status != PersonalRestrictionRules.ERASED }
            if (entries.isEmpty()) Text("Keine erhaltenen privaten Vorgaben.")
            entries.asReversed().forEach { row -> Panel {
                Section("Fassung ${row.revision}",if (row.status == PersonalRestrictionRules.CONFIRMED) "Ausdrücklich bestätigt" else "Unbestätigter Vorschlag")
                Text(row.rawText.orEmpty().ifBlank { "Keine Ausschlusskennungen." }); Text(row.reason.orEmpty())
                Text("${java.time.Instant.ofEpochMilli(row.recordedAt).atZone(java.time.ZoneId.of(row.zoneId))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))} · ${row.zoneId}")
            } }
        }
        OutlinedButton(enabled = !saving.busy && data.history.any { it.status != PersonalRestrictionRules.ERASED },
            modifier = Modifier.testTag("personal_restrictions_erase_review"),onClick = {
                erasure = RestrictionErasure(data.token,UUID.randomUUID().toString())
            }) { Text("Privaten Vorgabenverlauf löschen …") }
    }
    confirmation?.let { review ->
        val proposal = data?.latest?.takeIf { it.id == review.id && it.status == PersonalRestrictionRules.PROPOSED }
        val valid = proposal != null && !state.readError && data!!.versionBinding(review.id) == review.binding
        var checked by remember(review.key) { mutableStateOf(false) }
        LaunchedEffect(valid) { if (!valid) checked = false }
        AlertDialog(onDismissRequest = { if (!saving.busy) confirmation = null },title = { Text("Eigene Vorgaben bestätigen") },text = {
            Column {
                if (valid) Text(proposal!!.rawText.orEmpty().ifBlank { "Keine Ausschlusskennungen vorgegeben." })
                else Text("Prüfstand geändert. Schließen und neu prüfen.")
                Row { Checkbox(checked,{ checked = it },enabled = valid && !saving.busy,modifier = Modifier.testTag("personal_restrictions_confirm_checked"))
                    Text("Diese Vorgaben für neue Rezeptprüfungen verbindlich nutzen") }
            }
        },confirmButton = {
            Button(enabled = valid && checked && !saving.busy,modifier = Modifier.testTag("personal_restrictions_confirm"),onClick = {
                model.confirm(review.key,review.id,review.binding,checked)
            }) { Text("Vorgaben ausdrücklich bestätigen") }
        },dismissButton = { TextButton(enabled = !saving.busy,onClick = { confirmation = null }) { Text("Abbrechen") } })
    }
    erasure?.let { review ->
        val valid = data?.token == review.token && !state.readError
        var checked by remember(review.key) { mutableStateOf(false) }
        LaunchedEffect(valid) { if (!valid) checked = false }
        AlertDialog(onDismissRequest = { if (!saving.busy) erasure = null },title = { Text("Privaten Vorgabenverlauf löschen") },text = {
            Column {
                Text("Löschumfang: eigene Ausschlusskennungen, Gründe und private Verlaufswerte. Wertfreie Vorgangsmarker schützen gegen Wiederbelebung durch alte Wiederholungen. Ernährung, Vorrat und gespeicherte Rezeptfassungen bleiben erhalten.")
                if (!valid) Text("Löschumfang geändert. Schließen und neu prüfen.")
                Row { Checkbox(checked,{ checked = it },enabled = valid && !saving.busy,modifier = Modifier.testTag("personal_restrictions_erase_checked"))
                    Text("Diesen privaten Vorgabenverlauf ausdrücklich löschen") }
            }
        },confirmButton = {
            Button(enabled = valid && checked && !saving.busy,modifier = Modifier.testTag("personal_restrictions_erase"),onClick = {
                model.erase(review.key,review.token,checked)
            }) { Text("Vorgabenverlauf löschen") }
        },dismissButton = { TextButton(enabled = !saving.busy,onClick = { erasure = null }) { Text("Abbrechen") } })
    }
}
