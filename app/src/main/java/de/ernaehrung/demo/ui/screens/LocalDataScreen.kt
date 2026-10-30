package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.LocalDate
import java.util.UUID

@Composable
fun LocalDataScreen() {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(FoodStore(FoodDatabase.localSandbox(context))) as T
    } }
    val model: LocalDataViewModel = viewModel(factory = factory)
    LocalDataContent(model)
}

/** Lives inside the existing scroll container on M03, without adding a main navigation tab. */
@Composable
fun LocalDataContent(model: LocalDataViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val save by model.saveState.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf("Katalog") }
    var operationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val blocked = save.busy || save.succeeded

    Heading("Lokaler Testbereich", "Datenfundament · Room / SQLite")
    Hint("Nur Testdaten eingeben. Diese Daten bleiben auf diesem Gerät und nach App-Neustart erhalten. Die übrigen Ansichten zeigen weiterhin feste Demodaten. Keine Verbindung zu Sheets oder persönlichen Daten.", Sage)
    state.error?.let { Hint(it); return }
    if (state.loading) { CircularProgressIndicator(); return }
    save.message?.let { Hint(it, if (save.succeeded) Sage else Amber) }
    if (save.succeeded) Action("Neuen Vorgang beginnen", true) {
        operationId = UUID.randomUUID().toString()
        model.newOperation()
    }
    LocalSections(mode) { mode = it }

    when (mode) {
        "Katalog" -> {
            Text("${state.catalog.size} gespeicherte Produkte · unabhängig vom Bestand")
            ProductEditor(state, blocked, operationId, model)
            if (state.catalog.isEmpty()) Hint("Noch keine Produkte gespeichert. Der Katalog startet bewusst leer.", Sage)
            state.catalog.forEach { row ->
                Panel {
                    Section(row.version.name, "Version ${row.version.revision} · ${row.version.brand ?: "Marke unbekannt"}")
                    KV("Physischer Bestand", "${Amounts.format(row.stockAmount)} ${row.version.baseUnit.label}")
                    KV("Energie", row.version.energyKcal?.let { "$it kcal" } ?: "Unbekannt")
                    KV("Nährwertbasis", "${Amounts.format(row.version.nutritionBasis)} ${row.version.baseUnit.label}")
                    KV("Qualität / Quelle", "${row.version.quality} / ${row.version.source}")
                    Text("Produkt: ${row.version.productId}\nVersion: ${row.version.id}", style = MaterialTheme.typography.labelSmall)
                    Text("Speichern oder Korrigieren erzeugt keinen Zugang.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        "Lager" -> {
            LocationEditor(blocked, operationId, model)
            if (state.locations.isNotEmpty() && state.catalog.isNotEmpty()) ReceiptEditor(state, blocked, operationId, model)
            else Hint("Für einen Zugang zuerst ein Produkt im Katalog und einen bestätigten Lagerort anlegen.", Sage)
            state.batches.forEach { row ->
                Panel {
                    Section(row.productName, row.locationName)
                    KV("Physisch vorhanden", "${Amounts.format(row.stockAmount)} ${row.unit.label}")
                    KV("Verwendungsstatus", when (row.batch.release) {
                        ReleaseStatus.UNCHECKED -> "Ungeklärt · keine automatische Freigabe"
                        ReleaseStatus.BLOCKED -> "Gesperrt"
                        ReleaseStatus.RELEASED -> "Vom Nutzer für diesen Test freigegeben"
                    })
                    KV("MHD", row.batch.bestBefore ?: "Unbekannt")
                    KV("Verbrauchsdatum", row.batch.useBy ?: "Unbekannt")
                    Text("Charge: ${row.batch.id}", style = MaterialTheme.typography.labelSmall)
                    if (row.originReversed) Badge("Herkunft storniert · nur Historie", Amber)
                }
            }
            if (state.batches.any { it.stockAmount > 0 }) ConsumptionEditor(state, blocked, operationId, model)
            StockEventEditor(state, blocked, operationId, model)
        }
        "Ernährung" -> NutritionScreen(state, blocked, operationId, model)
        "Planung" -> LocalPlanningScreen(state, blocked, operationId, model)
        "Küche" -> KitchenEditor(state, blocked, operationId, model)
        "Chronik" -> {
            ReversalEditor(state, blocked, operationId, model)
            Hint("Hier stehen die letzten 30 Bewegungen. Stornos ergänzen Gegenbewegungen; die ursprünglichen Ereignisse bleiben in der Datenbank. Kochen ist kein Verzehr.", Sage)
            if (state.movements.isEmpty()) Text("Noch keine Bestandsbewegungen.")
            state.movements.forEach { row -> Panel {
                Section(row.productName, movementLabel(row.movement.kind))
                KV("Menge", "${Amounts.format(row.movement.delta)} ${row.unit.label}")
                KV("Ereignisdatum", row.movement.occurredOn ?: "Nicht gesondert erfasst · Altbestand")
                Text("Vorgang: ${row.movement.operationId}\nCharge: ${row.movement.batchId}", style = MaterialTheme.typography.labelSmall)
                Text(row.movement.reason)
            } }
        }
    }
}

@Composable
private fun LocalSections(selected: String, onSelect: (String) -> Unit) =
    WrappingChoices(listOf("Katalog", "Lager", "Ernährung", "Küche", "Planung", "Chronik"), selected, "local_section_", onSelect)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WrappingChoices(options: List<String>, selected: String, tagPrefix: String, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { section ->
            FilterChip(selected == section, { onSelect(section) }, label = { Text(section) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix$section"),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Forest, selectedLabelColor = Color.White))
        }
    }
}

@Composable
private fun ProductEditor(state: LocalDataState, busy: Boolean, operationId: String, model: LocalDataViewModel) {
    var selected by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var unitName by rememberSaveable { mutableStateOf(BaseUnit.GRAM.name) }
    var nutrientTexts by rememberSaveable { mutableStateOf<List<String>>(Nutrient.entries.map { "" }) }
    var reason by rememberSaveable { mutableStateOf("") }
    Fold("Produkt speichern / neue Version") {
        Picker("Produkt", listOf("Neues Produkt") + state.catalog.map { it.version.name + " · " + it.version.productId },
            state.catalog.find { it.version.productId == selected }?.version?.name ?: "Neues Produkt") { choice ->
            val row = state.catalog.find { it.version.name + " · " + it.version.productId == choice }
            selected = row?.version?.productId.orEmpty(); name = row?.version?.name.orEmpty()
            brand = row?.version?.brand.orEmpty(); unitName = (row?.version?.baseUnit ?: BaseUnit.GRAM).name
            nutrientTexts = Nutrient.entries.map { nutrient -> row?.version?.nutrient(nutrient)?.toString()?.replace('.', ',').orEmpty() }
            reason = ""
        }
        Field("Produktname · lokal", name, { name = it })
        Field("Marke · optional", brand, { brand = it })
        if (selected.isEmpty()) Picker("Basiseinheit", BaseUnit.entries.map { it.label }, BaseUnit.valueOf(unitName).label) { label -> unitName = BaseUnit.entries.first { it.label == label }.name }
        val unit = BaseUnit.valueOf(unitName)
        val previous = state.catalog.find { it.version.productId == selected }?.version
        val basis = previous?.nutritionBasis ?: if (unit == BaseUnit.PORTION) 1000L else 100_000L
        Text("Nährwerte je ${Amounts.format(basis)} ${unit.label}. Leer bedeutet unbekannt, 0 bedeutet bekannte Null.")
        Nutrient.entries.take(5).forEach { nutrient ->
            Field("${nutrient.label} in ${nutrient.unit} · optional", nutrientTexts[nutrient.ordinal], { text ->
                nutrientTexts = nutrientTexts.toMutableList().apply { this[nutrient.ordinal] = text }
            })
        }
        Fold("Weitere Produktnährstoffe") {
            Nutrient.entries.drop(5).forEach { nutrient ->
                Field("${nutrient.label} in ${nutrient.unit} · optional", nutrientTexts[nutrient.ordinal], { text ->
                    nutrientTexts = nutrientTexts.toMutableList().apply { this[nutrient.ordinal] = text }
                })
            }
        }
        Field("Änderungsgrund", reason, { reason = it })
        SaveButton("Produkt lokal speichern", busy) {
            // Capture this form before launching; edits during a save cannot change its request.
            val productName = name; val productBrand = brand; val values = nutrientTexts.toList()
            val productId = selected.takeIf { it.isNotEmpty() }; val change = reason
            val base = previous?.let(ProductInput::from) ?: ProductInput(productName, unit = unit, nutritionBasis = basis)
            model.save(operationId) {
                val parsed = Nutrient.entries.associateWith { optionalNutrient(values[it.ordinal]) }
                rememberProduct(operationId, base.copy(name = productName, brand = productBrand, source = Source.USER,
                    quality = Quality.UNKNOWN, sourceDetail = previous?.let { "Manuelle Bearbeitung; vorige Version ${it.id}" })
                    .withNutrients(parsed), productId, change)
            }
        }
    }
}

@Composable
private fun LocationEditor(busy: Boolean, operationId: String, model: LocalDataViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Fold("Lagerort anlegen") {
        Field("Lagername", name, { name = it })
        Check("Ort ist tatsächlich vorhanden · keine Kapazitätsfreigabe", confirmed) { confirmed = it }
        SaveButton("Lagerort lokal speichern", busy) {
            val locationName = name; val exists = confirmed
            model.save(operationId) { addLocation(operationId, locationName, exists) }
        }
    }
}

@Composable
private fun ReceiptEditor(state: LocalDataState, busy: Boolean, operationId: String, model: LocalDataViewModel) {
    var versionId by rememberSaveable { mutableStateOf("") }
    var locationId by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var acquired by rememberSaveable { mutableStateOf("") }
    var useBy by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var released by rememberSaveable { mutableStateOf(false) }
    Fold("Testzugang bestätigen") {
        val product = state.catalog.find { it.version.id == versionId }
        Picker("Produktversion", state.catalog.map { "${it.version.name} · ${it.version.id}" }, product?.version?.name ?: "Bitte wählen") { label -> versionId = state.catalog.first { label.endsWith(it.version.id) }.version.id }
        Picker("Lagerort", state.locations.map { "${it.name} · ${it.id}" }, state.locations.find { it.id == locationId }?.name ?: "Bitte wählen") { label -> locationId = state.locations.first { label.endsWith(it.id) }.id }
        Field("Zugangsmenge in ${product?.version?.baseUnit?.label ?: "Basiseinheiten"}", quantity, { quantity = it })
        Field("Zugangsdatum JJJJ-MM-TT · optional", acquired, { acquired = it })
        Field("Verbrauchsdatum JJJJ-MM-TT · optional", useBy, { useBy = it })
        Check("Charge für diesen Test ausdrücklich freigeben", released) { released = it }
        Hint("Ohne Freigabe bleibt die Charge ungeklärt. Aus Lagerort oder Datum folgt keine automatische Lebensmittelsicherheit.")
        Check("Diesen Testzugang buchen · keine Ausgabe erfassen", confirmed) { confirmed = it }
        SaveButton("Testzugang buchen", busy) {
            val version = versionId; val location = locationId; val amount = quantity
            val day = acquired.takeIf { it.isNotBlank() }; val expiry = useBy.takeIf { it.isNotBlank() }
            val release = if (released) ReleaseStatus.RELEASED else ReleaseStatus.UNCHECKED; val accepted = confirmed
            model.save(operationId) { receive(operationId, BatchInput(version, location, Amounts.parse(amount), day, useBy = expiry, release = release), accepted) }
        }
    }
}

@Composable
private fun ConsumptionEditor(state: LocalDataState, busy: Boolean, operationId: String, model: LocalDataViewModel) {
    var batchId by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Fold("Testverzehr aus einer Charge") {
        val batches = state.batches.filter { it.stockAmount > 0 }
        val batch = batches.find { it.batch.id == batchId }
        Picker("Charge", batches.map { "${it.productName} · ${it.batch.id}" }, batch?.productName ?: "Bitte wählen") { label -> batchId = batches.first { label.endsWith(it.batch.id) }.batch.id }
        Field("Verzehrmenge in ${batch?.unit?.label ?: "Basiseinheiten"}", amount, { amount = it })
        Field("Verzehrdatum JJJJ-MM-TT", day, { day = it })
        Check("Tatsächlich gegessen · als Testereignis bestätigen", confirmed) { confirmed = it }
        SaveButton("Testverzehr buchen", busy) {
            val selected = batch; val quantity = amount; val date = day; val accepted = confirmed
            model.save(operationId) {
                requireNotNull(selected) { "Charge wählen." }
                val parsed = Amounts.parse(quantity)
                consume(operationId, ConsumptionInput(selected.batch.versionId, date, "Testmahlzeit", parsed,
                    listOf(Allocation(selected.batch.id, parsed))), accepted)
            }
        }
    }
}

@Composable internal fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange)) {
        Checkbox(checked, null); Text(label, Modifier.weight(1f))
    }
}
@Composable internal fun SaveButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().testTag(label), enabled = !busy) { Text(label) }
}
private fun optionalNutrient(value: String): Double? {
    if (value.isBlank()) return null
    return value.trim().replace(',', '.').toDoubleOrNull()?.also { require(it.isFinite() && it >= 0) { "Nährwert ungültig." } }
        ?: throw IllegalArgumentException("Nährwert als Zahl eingeben oder leer lassen.")
}
