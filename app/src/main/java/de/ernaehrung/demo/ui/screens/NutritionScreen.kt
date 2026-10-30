package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Composable
internal fun NutritionScreen(state: LocalDataState, blocked: Boolean, operationId: String, model: LocalDataViewModel) {
    val requested by model.selectedDay.collectAsStateWithLifecycle()
    val daily by model.daily.collectAsStateWithLifecycle()
    var draftDate by rememberSaveable { mutableStateOf(requested) }
    Heading("Deine lokale Ernährung", "Nur tatsächlich erfasster Verzehr zählt.")
    Field("Tag JJJJ-MM-TT", draftDate, { draftDate = it })
    Action("Tag anzeigen", true) { model.showDay(draftDate) }
    daily.error?.let { Hint(it) }
    val report = daily.report
    if (report == null || daily.date != requested) {
        if (daily.error == null) CircularProgressIndicator()
        return
    }
    Panel(color = Sage) {
        Section(report.date, "Tageserfassung und Nährwertabdeckung sind getrennte Angaben.")
        Text(dayStatusLabel(report.status), Modifier.testTag("day_status"))
        Text("${report.meals.size} aktive Verzehre", Modifier.testTag("active_meal_count"))
        if (report.meals.isEmpty()) Text("Keine Verzehre gespeichert. Es werden keine fehlenden Angaben als Kalorien-Nullwerte ausgegeben.")
        else NutrientLines(report.totals, "day")
    }
    if (report.meals.isNotEmpty()) {
        Fold("Weitere Tagesnährwerte & Datenqualität") { NutrientLines(report.totals, "day_extra", extra = true); QualityNotes(report.totals) }
    }
    DayConfirmation(report, blocked, operationId, model)
    LocalMealEditor(state, report.date, blocked, operationId, model)
    Section("Tatsächlich erfasst", "Fehlerhafte Einträge stornieren und anschließend korrekt neu erfassen.")
    var reverseId by rememberSaveable { mutableStateOf<String?>(null) }
    report.meals.forEach { meal -> key(meal.event.id) {
        Panel(Modifier.testTag("meal_${meal.event.id}")) {
            Section(meal.event.meal, "${meal.productName} · Version ${meal.revision ?: "unbekannt"}")
            KV("Menge", meal.event.amount?.let { "${Amounts.format(it)} ${meal.unit.label}" } ?: "Unbekannt")
            meal.event.note?.takeIf { it.isNotBlank() }?.let { Text(it) }
            NutrientLines(meal.nutrition, "meal_${meal.event.id}")
            Action("Eintrag stornieren", true, "storno_meal_${meal.event.id}") { reverseId = meal.event.operationId }
        }
    } }
    reverseId?.let { original ->
        var reason by rememberSaveable(original) { mutableStateOf("") }
        var date by rememberSaveable(original) { mutableStateOf(LocalDate.now().toString()) }
        AlertDialog(onDismissRequest = { reverseId = null }, modifier = Modifier.testTag("nutrition_storno_dialog"),
            title = { Text("Verzehr stornieren") }, text = { Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .5f).dp).verticalScroll(rememberScrollState())) {
                Text("Verzehr aus der aktiven Bilanz entfernen und zugehörige Entnahmen gemeinsam zurückbuchen. Die Historie bleibt erhalten.")
                Field("Stornodatum JJJJ-MM-TT", date, { date = it }); Field("Stornogrund", reason, { reason = it })
            } }, confirmButton = { TextButton(enabled = !blocked && reason.isNotBlank(), onClick = {
                val note = reason; val day = date; reverseId = null
                model.save(operationId) { reverse(operationId, ReversalInput(original, day, note), true) }
            }, modifier = Modifier.testTag("confirm_meal_storno")) { Text("Storno bestätigen") } },
            dismissButton = { TextButton({ reverseId = null }) { Text("Abbrechen") } })
    }
}

@Composable
private fun DayConfirmation(report: DayNutrition, blocked: Boolean, key: String, model: LocalDataViewModel) {
    var token by rememberSaveable { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf(DayStatus.COMPLETE.name) }
    var date by rememberSaveable { mutableStateOf(report.date) }
    val complete = report.status == DayStatus.COMPLETE
    SaveButton(if (complete) "Tag wieder öffnen" else "Erfassung als vollständig bestätigen", blocked) {
        token = report.snapshot; date = report.date; target = if (complete) DayStatus.OPEN.name else DayStatus.COMPLETE.name
    }
    token?.let { snapshot ->
        var accepted by rememberSaveable(snapshot) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { token = null }, modifier = Modifier.testTag("day_status_dialog"),
            title = { Text(if (target == DayStatus.COMPLETE.name) "Tageserfassung bestätigen" else "Tag wieder öffnen") },
            text = { Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .5f).dp).verticalScroll(rememberScrollState())) {
                Text("$date: Vollständig heißt, dass alle tatsächlichen Mahlzeiten erfasst sind. Unbekannte Mengen oder Nährwerte bleiben dennoch Datenlücken. Spätere Einträge heben die Bestätigung auf.")
                Check(if (target == DayStatus.COMPLETE.name) "Alle Mahlzeiten dieses Tages sind erfasst" else "Vollständigkeitsbestätigung zurücknehmen", accepted) { accepted = it }
            } }, confirmButton = { TextButton(enabled = accepted && !blocked, modifier = Modifier.testTag("confirm_day_status"), onClick = {
                val status = DayStatus.valueOf(target); val day = date; token = null
                model.save(key) { setDayStatus(key, day, status, snapshot, true) }
            }) { Text("Bestätigen") } }, dismissButton = { TextButton({ token = null }) { Text("Abbrechen") } })
    }
}

@Composable
private fun LocalMealEditor(state: LocalDataState, date: String, blocked: Boolean, key: String, model: LocalDataViewModel) {
    var mode by rememberSaveable { mutableStateOf("Ohne Lagerbezug") }
    var versionId by rememberSaveable { mutableStateOf("") }
    var batchId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Fold("Tatsächlichen Verzehr erfassen") {
        WrappingChoices(listOf("Ohne Lagerbezug", "Aus Charge"), mode, "meal_source_") { mode = it; confirmed = false }
        Text("Datum: $date. Einen anderen Tag oben auswählen.")
        val batch = state.batches.find { it.batch.id == batchId }
        val version = if (mode == "Aus Charge") state.versions.find { it.id == batch?.batch?.versionId } else state.versions.find { it.id == versionId }
        if (mode == "Aus Charge") {
            IdPicker("Verzehrcharge", state.batches.filter { !it.originReversed && it.stockAmount > 0 }.map {
                it.batch.id to "${it.productName} · ${it.locationName} · ${Amounts.format(it.stockAmount)} ${it.unit.label} · ${it.batch.id.take(8)}"
            }, batchId) { batchId = it; confirmed = false }
            Text("Freigabe, Verbrauchsdatum und ausreichender Bestand werden vor der Entnahme geprüft.")
        } else {
            IdPicker("Verzehr-Artikelversion", state.versions.map { it.id to "${it.name} · v${it.revision} · ${it.baseUnit.label} · ${it.id.take(8)}" }, versionId) { versionId = it; confirmed = false }
            Text("Zum Beispiel unterwegs gegessen: keine fiktive Lagerentnahme. Ohne bekannte Menge das Mengenfeld leer lassen.")
        }
        Field("Mahlzeit / Beschreibung", name, { name = it; confirmed = false })
        Field("Gegessene Menge in ${version?.baseUnit?.label ?: "Basiseinheit"}", amount, { amount = it; confirmed = false })
        Field("Verzehrnotiz · optional", note, { note = it; confirmed = false })
        Check("Tatsächlich gegessen · lokal speichern", confirmed) { confirmed = it }
        SaveButton("Verzehr lokal buchen", blocked || version == null) {
            val selected = version; val selectedBatch = batch; val modeValue = mode; val quantity = amount
            val title = name; val notes = note.takeIf { it.isNotBlank() }; val accept = confirmed
            model.save(key) {
                requireNotNull(selected) { "Artikelversion wählen." }
                val parsed = quantity.takeIf { it.isNotBlank() }?.let(Amounts::parse)
                val allocations = if (modeValue == "Aus Charge") listOf(Allocation(requireNotNull(selectedBatch) { "Charge wählen." }.batch.id,
                    requireNotNull(parsed) { "Für Lagerentnahmen ist eine genaue Menge erforderlich." })) else emptyList()
                consume(key, ConsumptionInput(selected.id, date, title, parsed, allocations, note = notes), accept)
            }
        }
    }
}

@Composable
internal fun NutrientLines(result: NutritionResult, prefix: String, extra: Boolean = false) {
    val nutrients = if (extra) Nutrient.entries.drop(5) else Nutrient.entries.take(5)
    nutrients.forEach { nutrient ->
        val value = result[nutrient]
        Column(Modifier.testTag("${prefix}_${nutrient.name}").semantics(mergeDescendants = true) {}) {
            KV(nutrient.label, nutrientText(nutrient, value))
        }
    }
}
@Composable
internal fun QualityNotes(result: NutritionResult) {
    result.values.values.flatMap { it.gaps }.toSet().forEach { Text(gapLabel(it)) }
    if (result.values.values.any { it.qualities.any { q -> q != Quality.CONFIRMED } }) Text("Berechnung enthält noch nicht vollständig quellenbestätigte Angaben.")
    Text("Werte werden für die Anzeige gerundet. Eine Teilsumme beschreibt nur bekannte Beiträge, keinen vollständig erfassten Nährstoff.")
}
internal fun nutrientText(nutrient: Nutrient, value: NutrientResult): String {
    val known = value.knownAmount ?: return "Unbekannt"
    val places = when (nutrient) { Nutrient.ENERGY -> 1; Nutrient.SALT, Nutrient.IODINE, Nutrient.B12, Nutrient.VITAMIN_D -> 3; else -> 2 }
    val rounded = known.setScale(places, RoundingMode.HALF_UP).stripTrailingZeros()
    val text = if (known.signum() > 0 && rounded.signum() == 0) "< ${BigDecimal.ONE.movePointLeft(places).toPlainString()}" else rounded.toPlainString()
    return (if (value.missingCount > 0) "Teilsumme: " else "") + text.replace('.', ',') + " ${nutrient.unit}"
}
private fun dayStatusLabel(status: DayStatus) = when (status) {
    DayStatus.OPEN -> "Erfassung offen"; DayStatus.PARTIAL -> "Erfassung teilweise"; DayStatus.COMPLETE -> "Erfassung vollständig bestätigt"
}
private fun gapLabel(gap: NutritionGap) = when (gap) {
    NutritionGap.UNKNOWN_AMOUNT -> "Mindestens eine Menge ist ungeklärt."
    NutritionGap.MISSING_VALUE -> "Mindestens ein Nährwert fehlt."
    NutritionGap.MISSING_VERSION -> "Eine benötigte Artikelversion fehlt."
    NutritionGap.UNIT_MISMATCH -> "Eine Einheit passt nicht zur gespeicherten Mengenbasis."
    NutritionGap.INVALID_PRODUCTION -> "Eine Herstellung ist nicht eindeutig auswertbar."
    NutritionGap.REVERSED_PRODUCTION -> "Eine zugrunde liegende Herstellung wurde storniert."
    NutritionGap.CYCLE -> "Widersprüchliche Herstellungsbezüge verhindern eine vollständige Berechnung."
}
