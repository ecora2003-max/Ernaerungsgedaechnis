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
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Composable
private fun rememberStorageCapacityModel(): StorageCapacityViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StorageCapacityViewModel(FoodStore(FoodDatabase.localSandbox(context))) as T
    } }
    return viewModel(key = "physical-storage-capacity", factory = factory)
}

private data class CapacityEditBasis(val location: StorageLocationEntity, val latest: StorageCapacityReviewEntity?,
    val reversed: Set<String>, val locationReviewId: String?)
private data class CapacityForm(val total: String = "", val occupied: String = "", val temperature: String = "",
    val reason: String = "", val confirmed: Boolean = false)

@Composable
internal fun StorageCapacityScreen(initialLocationId: String? = null, providedModel: StorageCapacityViewModel? = null,
    onCreateLocation: (() -> Unit)? = null) {
    val model = providedModel ?: rememberStorageCapacityModel()
    val state by model.state.collectAsStateWithLifecycle()
    val saving by model.saveState.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable(initialLocationId) { mutableStateOf(initialLocationId) }
    var operationKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CapacityEditBasis?>(null) }
    var locationEditing by remember { mutableStateOf<LocationEditBasis?>(null) }
    var locationForm by remember { mutableStateOf(LocationForm()) }
    // Outside the changing fact/history layout: a newly inserted warning or
    // Room revision must neither replace nor drop the user's unsaved fields.
    var form by remember { mutableStateOf(CapacityForm()) }
    val snapshot = state.snapshot
    LaunchedEffect(snapshot?.locations) { if (selectedId == null) selectedId = snapshot?.locations?.firstOrNull()?.id }
    val location = snapshot?.locations?.find { it.id == selectedId }
    val latest = snapshot?.history?.filter { it.locationId == selectedId }?.maxByOrNull { it.revision }
    val locationReview = snapshot?.locationHistory?.filter { it.locationId == selectedId }?.maxByOrNull { it.revision }
    Column(Modifier.fillMaxWidth().testTag("storage_capacity_screen"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Lagerorte & Kapazität", "Tatsächlichen Platz vor Ort prüfen")
        Text("Nutzbare Gesamtkapazität und belegtes Behälter-Außenvolumen getrennt erfassen. Lebensmittelmasse oder gegessene Portionen sagen nicht, wie viel Platz Behälter noch belegen. Fehlende Angaben bleiben unbekannt; geplante Lagerorte schaffen keinen realen Platz.")
        if (state.readError) {
            Hint("Lagerangaben gerade nicht lesbar. Eine offene Prüfung kann bis zum erneuten Lesen nicht gespeichert werden.")
            OutlinedButton(model::retry, Modifier.testTag("storage_capacity_retry")) { Text("Lagerangaben neu lesen") }
        } else if (snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (snapshot != null && !saving.busy) IdPicker("Lagerort für Kapazitätsprüfung",
            snapshot.locations.map { it.id to "${it.name} · ${if (it.confirmed) "vorhanden" else "geplant/unbestätigt"}" }, selectedId.orEmpty()) {
            selectedId = it; editing = null; locationEditing = null; operationKey = null; model.clearResult()
        }
        if (snapshot?.locations?.isEmpty() == true) Text("Noch kein Lagerort angelegt.", Modifier.testTag("storage_capacity_empty"))
        if (onCreateLocation != null) OutlinedButton(onCreateLocation, enabled = !saving.busy) { Text("Lagerort anlegen") }
        location?.let { place ->
            val data = requireNotNull(snapshot)
            Section(place.name, if (place.confirmed) "Als tatsächlich vorhanden erfasst" else "Geplant/unbestätigt · keine nutzbare Kapazität")
            if (latest == null) {
                Text("Noch keine strukturierte physische Kapazitätsprüfung.")
                Text("Unbekannt", Modifier.testTag("storage_capacity_free"))
            } else CapacityFacts(latest, data, "storage_capacity_")
            if ((locationEditing == null && editing == null) || saving.result != null) OutlinedButton({
                model.clearResult(); editing = null; operationKey = UUID.randomUUID().toString()
                locationEditing = LocationEditBasis(place, locationReview?.id, latest?.id)
                locationForm = LocationForm(place.name, place.confirmed)
            }, enabled = !saving.busy && !state.readError, modifier = Modifier.testTag("storage_location_edit")) {
                Text("Name oder Status prüfen")
            }
            if ((editing == null && locationEditing == null) || saving.result != null) OutlinedButton({
                model.clearResult(); operationKey = UUID.randomUUID().toString()
                locationEditing = null
                editing = CapacityEditBasis(place, latest, data.reversedOperationIds, locationReview?.id)
                form = CapacityForm(latest?.totalMl?.toString().orEmpty(), latest?.occupiedMl?.toString().orEmpty(),
                    latest?.temperatureCelsiusText.orEmpty())
            }, enabled = place.confirmed && !saving.busy && !state.readError, modifier = Modifier.testTag("storage_capacity_edit")) {
                Text("Aktuelle Angaben vor Ort prüfen")
            }
            Fold("Unveränderte ursprüngliche Lagerangaben") {
                val original = data.originalLocations.find { it.id == place.id } ?: place
                Text("${original.name} · ${if (original.confirmed) "vorhanden bestätigt" else "geplant/unbestätigt"}")
                Text("Ursprüngliche nutzbare Kapazitätsangabe: ${original.usableCapacityMl?.let { "$it ml" } ?: "unbekannt"}")
                Text("Ursprüngliche Temperaturangabe: ${original.temperatureCelsius?.let { "$it °C" } ?: "unbekannt"}")
                Text("Ohne überliefertes belegtes Außenvolumen ergibt der ursprüngliche Kapazitätswert keinen bestätigten freien Platz.")
            }
        }
        saving.error?.let { Hint(it) }
        if (saving.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("storage_capacity_saving"))
        if (saving.result != null) {
            if (saving.locationSaved) Text("Name und Status dauerhaft gespeichert. Keine Menge gebucht. Freien Lagerplatz anschließend separat vor Ort prüfen.", Modifier.testTag("storage_location_saved"))
            else Text("Physische Lagerprüfung dauerhaft gespeichert. Keine Lebensmittelmenge oder Mahlzeit gebucht.", Modifier.testTag("storage_capacity_saved"))
        }
        val locationBasis = locationEditing
        if (locationBasis != null && saving.result == null && operationKey != null) key(operationKey) {
            val stale = snapshot == null || state.readError || location != locationBasis.location ||
                locationReview?.id != locationBasis.previousId || latest?.id != locationBasis.capacityId
            LocationEditor(locationBasis, locationForm, { locationForm = it }, stale || saving.busy) { input, confirmed ->
                model.saveLocation(requireNotNull(operationKey), input, confirmed)
            }
            if (stale) Hint("Lagerort oder Kapazitätsprüfung inzwischen geändert. Formular schließen und aktuellen Stand erneut prüfen.")
            OutlinedButton({ locationEditing = null; operationKey = null; model.clearResult() }, enabled = !saving.busy,
                modifier = Modifier.testTag("storage_location_cancel")) { Text("Lagerortprüfung abbrechen") }
        }
        val basis = editing
        if (basis != null && saving.result == null && operationKey != null) key(operationKey) {
            val stale = snapshot == null || state.readError || location != basis.location || locationReview?.id != basis.locationReviewId || latest?.id != basis.latest?.id ||
                snapshot.reversedOperationIds != basis.reversed || !basis.location.confirmed
            if (stale) Hint("Prüfgrundlage inzwischen geändert oder nicht lesbar. Formular schließen und aktuelle Angaben erneut prüfen.")
            CapacityEditor(basis, form, { form = it }, stale || saving.busy) { input, confirmed ->
                model.save(requireNotNull(operationKey), input, confirmed)
            }
            OutlinedButton({ editing = null; operationKey = null; model.clearResult() }, enabled = !saving.busy,
                modifier = Modifier.testTag("storage_capacity_cancel")) { Text("Prüfung abbrechen") }
        }
        snapshot?.let { data -> data.history.filter { it.locationId == selectedId }.sortedByDescending { it.revision }.forEach { row ->
            Fold("Kapazitätshistorie · Prüfung ${row.revision}") { CapacityFacts(row, data, "capacity_history_${row.id}_") }
        }
        }
        snapshot?.locationHistory?.filter { it.locationId == selectedId }?.sortedByDescending { it.revision }?.forEach { row ->
            Fold("Lagerorthistorie · Prüfung ${row.revision}") {
                Text("${row.name} · ${if (row.confirmed) "vorhanden bestätigt" else "geplant/unbestätigt"}")
                Text("${row.reason} · Nutzerangabe · ${Instant.ofEpochMilli(row.recordedAt).atZone(ZoneId.of(row.zoneId))}")
                Text("Prüfkennung ${row.id} · Vorgang ${row.operationId}")
            }
        }
        Text("Eine Mengenprüfung ersetzt keine Prüfung geeigneter Temperatur, Kühlkette oder Lebensmittelhaltbarkeit. Herstellung bleibt eine getrennte ausdrückliche Buchung; Verzehr räumt Behälter nicht selbsttätig aus.")
    }
}

@Composable
internal fun CapacityFacts(row: StorageCapacityReviewEntity, snapshot: StorageCapacitySnapshot, prefix: String) {
    val free = snapshot.trustedFree(row)
    Text("Nutzbare Gesamtkapazität: ${row.totalMl?.let { "$it ml" } ?: "unbekannt"}", Modifier.testTag("${prefix}total"))
    Text("Belegtes Behälter-Außenvolumen: ${row.occupiedMl?.let { "$it ml" } ?: "unbekannt"}", Modifier.testTag("${prefix}occupied"))
    Text(free?.let { "$it ml" } ?: "Unbekannt", Modifier.testTag("${prefix}free"))
    Text("Freier Platz laut dieser Prüfgrundlage; zwischenzeitliche Änderungen vor Ort erneut prüfen.")
    val physical = StorageCapacityRules.physicalBasis(row, snapshot.history)
    Text("Temperaturangabe: ${row.temperatureCelsiusText?.let { "$it °C" } ?: "unbekannt"}", Modifier.testTag("${prefix}temperature"))
    if (physical != null) Text("Physische Prüfgrundlage: ${Instant.ofEpochMilli(physical.recordedAt).atZone(ZoneId.of(physical.zoneId))}")
    Text(if (row.basisKind == StorageCapacityRules.MANUAL) "Vor Ort geprüft · Nutzerangabe" else
        "Aus bestätigter Herstellung fortgeschrieben · zusätzlich ${row.addedOuterVolumeMl ?: "unbekannt"} ml Außenvolumen. Die Temperaturangabe stammt aus der physischen Prüfgrundlage.")
    if (row.totalMl != null && row.occupiedMl != null && free == null) Text("Lagerort oder Prüfgrundlage inzwischen geändert, storniert oder nicht vollständig nachweisbar. Freien Platz erneut physisch prüfen.")
    Text("${row.reason} · Stand ${Instant.ofEpochMilli(row.recordedAt).atZone(ZoneId.of(row.zoneId))}")
}

@Composable
private fun CapacityEditor(basis: CapacityEditBasis, form: CapacityForm, onChange: (CapacityForm) -> Unit, blocked: Boolean,
    onSave: (StorageCapacityInput, Boolean) -> Unit) {
    LaunchedEffect(blocked) { if (blocked && form.confirmed) onChange(form.copy(confirmed = false)) }
    fun millilitres(value: String): Long? = if (value.isBlank()) null else value.trim().toLongOrNull()?.also {
        require(it >= 0L) { "Milliliter als ganze nichtnegative Zahl eingeben oder leer lassen." }
    } ?: throw IllegalArgumentException("Milliliter als ganze Zahl eingeben oder leer lassen.")
    val input = runCatching {
        val all = millilitres(form.total); val used = millilitres(form.occupied); val temp = form.temperature.takeIf { it.isNotBlank() }
        StorageCapacityRules.validate(all, used, temp)
        require(form.reason.isNotBlank() && form.reason.length <= 2000) { "Prüf-/Korrekturgrund angeben." }
        StorageCapacityInput(basis.location.id, basis.latest?.id, all, used, temp, form.reason, basis.locationReviewId)
    }
    Section("Diese tatsächlichen Angaben prüfen", "Leer bleibt unbekannt; 0 bleibt bekannte Null. Keine Schätzung aus Lebensmittelmengen.")
    Text("Ungespeicherte Eingaben werden beim Schließen oder Neustart dieser Ansicht verworfen.")
    Field("Nutzbare Gesamtkapazität · ganze ml", form.total, { onChange(form.copy(total = it, confirmed = false)) }, modifier = Modifier.testTag("storage_capacity_total_input"))
    Field("Belegtes Behälter-Außenvolumen · ganze ml", form.occupied, { onChange(form.copy(occupied = it, confirmed = false)) }, modifier = Modifier.testTag("storage_capacity_occupied_input"))
    Field("Temperatur vor Ort · °C · optional", form.temperature, { onChange(form.copy(temperature = it, confirmed = false)) }, modifier = Modifier.testTag("storage_capacity_temperature_input"))
    Field("Prüf-/Korrekturgrund", form.reason, { onChange(form.copy(reason = it, confirmed = false)) }, modifier = Modifier.testTag("storage_capacity_reason_input"))
    input.getOrNull()?.let { Text("Geprüfter freier Platz: ${StorageCapacityRules.free(it.totalMl, it.occupiedMl)?.let { amount -> "$amount ml" } ?: "unbekannt"}") }
    if (input.isFailure && (form.confirmed || form.reason.isNotBlank())) Text(input.exceptionOrNull()?.message ?: "Angaben prüfen.")
    Check("Diese aktuellen tatsächlichen Angaben vor Ort geprüft und ausdrücklich bestätigen", form.confirmed) { if (!blocked) onChange(form.copy(confirmed = it)) }
    Button({ onSave(input.getOrThrow(), form.confirmed) }, enabled = !blocked && form.confirmed && input.isSuccess,
        modifier = Modifier.testTag("storage_capacity_save")) { Text("Physische Lagerprüfung speichern") }
}

private data class LocationEditBasis(val location: StorageLocationEntity, val previousId: String?, val capacityId: String?)
private data class LocationForm(val name: String = "", val present: Boolean = false, val reason: String = "", val confirmed: Boolean = false)

@Composable
private fun LocationEditor(basis: LocationEditBasis, form: LocationForm, onChange: (LocationForm) -> Unit, blocked: Boolean,
    onSave: (StorageLocationReviewInput, Boolean) -> Unit) {
    LaunchedEffect(blocked) { if (blocked && form.confirmed) onChange(form.copy(confirmed = false)) }
    Section("Diesen Lagerort prüfen", "Identität und bisherige Historie bleiben erhalten.")
    Text("Geplant/unbestätigt schafft keinen realen Lagerplatz. Vorhandenen Bestand zuerst separat umlagern oder korrigieren, bevor ein vorhandener Ort zurückgenommen wird. Die Ortsprüfung ersetzt keine neue Kapazitätsprüfung.")
    Text("Ungespeicherte Eingaben und Bestätigung werden beim Schließen oder Neustart verworfen.")
    Field("Name des Lagerortes", form.name, { onChange(form.copy(name = it, confirmed = false)) }, modifier = Modifier.testTag("storage_location_name"))
    Check("Dieser Lagerort ist tatsächlich vorhanden", form.present) { if (!blocked) onChange(form.copy(present = it, confirmed = false)) }
    Field("Grund der Lagerortprüfung", form.reason, { onChange(form.copy(reason = it, confirmed = false)) }, modifier = Modifier.testTag("storage_location_reason"))
    Check("Name und Status geprüft und ausdrücklich bestätigen", form.confirmed) { if (!blocked) onChange(form.copy(confirmed = it)) }
    Button({ onSave(StorageLocationReviewInput(basis.location.id, basis.previousId, basis.capacityId,
        form.name, form.present, form.reason), form.confirmed) },
        enabled = !blocked && form.confirmed && form.name.isNotBlank() && form.name.length <= 200 && form.reason.isNotBlank() && form.reason.length <= 2000,
        modifier = Modifier.testTag("storage_location_save")) { Text("Lagerortprüfung speichern") }
}
