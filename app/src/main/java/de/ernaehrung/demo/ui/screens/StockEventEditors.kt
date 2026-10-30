package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

@Composable
internal fun StockEventEditor(state: LocalDataState, blocked: Boolean, key: String, model: LocalDataViewModel) {
    var kind by rememberSaveable { mutableStateOf("Umlagern") }
    var selected by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var expected by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var conditions by rememberSaveable { mutableStateOf(false) }
    Fold("Umlagern, entsorgen oder zählen") {
        WrappingChoices(listOf("Umlagern", "Entsorgen", "Istzählung"), kind, "stock_action_") { kind = it; confirmed = false }
        val batches = state.batches.filter { !it.originReversed }
        val batch = batches.find { it.batch.id == selected }
        IdPicker("Quellcharge", batches.map { it.batch.id to "${it.productName} · ${it.locationName} · ${Amounts.format(it.stockAmount)} ${it.unit.label} · ${it.batch.id.take(8)}" }, selected) {
            selected = it; expected = batches.first { row -> row.batch.id == it }.stockAmount.toString(); confirmed = false
        }
        batch?.let { KV("Aktueller physischer Bestand", "${Amounts.format(it.stockAmount)} ${it.unit.label}") }
        if (kind == "Istzählung") {
            expected.toLongOrNull()?.let { KV("Beim Auswählen geprüfter Bestand", "${Amounts.format(it)} ${batch?.unit?.label.orEmpty()}") }
            if (batch != null && expected.toLongOrNull() != batch.stockAmount) Hint("Bestand inzwischen geändert. Die Charge erneut auswählen und die Zählung prüfen.")
        }
        Field(if (kind == "Istzählung") "Tatsächlich gezählter Restbestand · 0 erlaubt" else "Menge für Bestandsvorgang", quantity, { quantity = it; confirmed = false })
        if (kind == "Umlagern") {
            IdPicker("Ziellager", state.locations.filter { it.confirmed && it.id != batch?.batch?.locationId }.map { it.id to it.name }, target) { target = it; confirmed = false }
            Check("Geeignete Zielbedingungen geprüft · bestehende Freigabe beibehalten", conditions) { conditions = it; confirmed = false }
            Text("Ohne diese Prüfung wird eine bestehende Freigabe nicht übernommen. Gesperrte/ungeklärte Chargen bleiben gesperrt/ungeklärt.")
        }
        Field("Datum des Bestandsvorgangs", date, { date = it; confirmed = false })
        Field("Begründung des Bestandsvorgangs", reason, { reason = it; confirmed = false })
        Check("Diesen tatsächlichen Bestandsvorgang bestätigen", confirmed) { confirmed = it }
        SaveButton("Bestandsvorgang buchen", blocked || batch == null) {
            val source = selected; val location = target; val amount = quantity; val day = date
            val note = reason; val accept = confirmed; val check = conditions; val action = kind; val before = expected.toLongOrNull()
            model.save(key) { when (action) {
                "Umlagern" -> transfer(key, TransferInput(source, location, Amounts.parse(amount), day, note, check), accept)
                "Entsorgen" -> waste(key, WasteInput(source, Amounts.parse(amount), day, note), accept)
                else -> correctStock(key, StockCountInput(source, requireNotNull(before) { "Charge erneut wählen." }, Amounts.parseCount(amount), day, note), accept)
            } }
        }
    }
}

@Composable
internal fun ReversalEditor(state: LocalDataState, blocked: Boolean, key: String, model: LocalDataViewModel) {
    var selected by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<OperationDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val reversal = state.operations.find { it.operation.id == selected }?.reversedBy
    LaunchedEffect(selected, reversal) {
        details = null; error = null; confirmed = false
        if (selected.isNotEmpty()) try { details = model.operationDetails(selected) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "Vorschau konnte nicht geladen werden." }
    }
    Fold("Vorgang prüfen und stornieren") {
        val options = state.operations.filter { it.operation.kind != "REVERSAL" && it.reversedBy == null }
        IdPicker("Ursprungsvorgang · letzte 30", options.map { it.operation.id to "${operationLabel(it.operation.kind)} · ${it.operation.id.take(8)}" }, selected) { selected = it }
        error?.let { Hint(it) }
        details?.let { preview ->
            Section("Vorgesehene Gegenbuchungen", operationLabel(preview.operation.kind))
            if (preview.reversal != null) Hint("Dieser Vorgang ist bereits storniert.")
            preview.movements.forEach { movement -> KV(movement.productName, "${Amounts.format(-movement.movement.delta)} ${movement.unit.label}") }
            if (preview.consumption != null) Text("Der ursprüngliche Verzehr bleibt historisch erhalten, zählt nach dem Storno aber nicht mehr zur aktiven Ernährung.")
            if (preview.movements.isEmpty()) Text("Kein Lagerbezug: Es wird kein Bestand verändert.")
            Text("Ursprüngliche Einträge werden nicht gelöscht. Abhängige Buchungen müssen zuerst storniert werden.")
        }
        Field("Stornodatum JJJJ-MM-TT", date, { date = it; confirmed = false })
        Field("Stornogrund", reason, { reason = it; confirmed = false })
        Check("Diese vollständige Gegenbuchung ausdrücklich bestätigen", confirmed) { confirmed = it }
        SaveButton("Vorgang stornieren", blocked || details == null || details?.reversal != null) {
            val original = selected; val day = date; val note = reason; val accepted = confirmed
            model.save(key) { reverse(key, ReversalInput(original, day, note), accepted) }
        }
    }
}

@Composable
internal fun IdPicker(label: String, items: List<Pair<String, String>>, selected: String, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().testTag(label)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(items.find { it.first == selected }?.second ?: "Bitte wählen")
            }
        }
        DropdownMenu(open, { open = false }) { items.forEach { item ->
            DropdownMenuItem(text = { Text(item.second) }, onClick = { onChoose(item.first); open = false }, modifier = Modifier.testTag("choose_${item.first}"))
        } }
    }
}
internal fun operationLabel(kind: String): String = when (kind) {
    "RECEIPT" -> "Zugang"; "CONSUMPTION" -> "Verzehr"; "TRANSFER" -> "Umlagerung"
    "WASTE" -> "Entsorgung"; "STOCK_COUNT" -> "Istzählung"; "PRODUCTION" -> "Herstellung"; "REVERSAL" -> "Storno"
    else -> kind
}
internal fun movementLabel(kind: MovementKind): String = when (kind) {
    MovementKind.RECEIPT -> "Zugang"; MovementKind.CONSUMPTION -> "Verzehr"; MovementKind.WASTE -> "Entsorgung"
    MovementKind.PRODUCTION_INPUT -> "Zutat für Herstellung"; MovementKind.PRODUCTION_OUTPUT -> "Hergestellte Portionen"
    MovementKind.TRANSFER -> "Umlagerung"; MovementKind.CORRECTION -> "Korrektur / Gegenbuchung"
}
