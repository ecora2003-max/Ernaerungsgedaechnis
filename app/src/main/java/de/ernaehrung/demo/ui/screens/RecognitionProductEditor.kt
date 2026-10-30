package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.recognition.*
import de.ernaehrung.demo.ui.*

@Composable
internal fun BarcodeProductEditor(data: LocalDataState, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    var review by rememberSaveable { mutableStateOf("") }
    val photo by model.photo.collectAsStateWithLifecycle()
    val alias by model.aliasLookup.collectAsStateWithLifecycle()
    val external by model.externalLookup.collectAsStateWithLifecycle()
    fun changeCode(value: String) { code = value; review = ""; model.clearAliasResult(); model.clearProductLookup() }
    PhotoControls(false, blocked, model)
    photo.codes.forEach { value -> Action("Erkannten Barcode wählen: $value", true) { changeCode(value) } }
    Field("EAN / GTIN · führende Nullen erhalten", code, { changeCode(it) })
    SaveButton("Barcode im lokalen Katalog prüfen", alias.busy || blocked.value) { model.checkAlias(code, null, AliasKind.BARCODE) }
    alias.error?.let { Hint(it) }
    alias.match?.takeIf { alias.queryText == code.trim() }?.let { match ->
        Text(matchLabel(match.status), Modifier.testTag("barcode_match_status"))
        match.candidates.forEach { candidate -> Panel {
            Section(candidate.version.name, "Version ${candidate.version.revision} · ${candidate.version.brand ?: "Marke unbekannt"}")
            Text("Quelle ${candidate.version.source.label} · ${candidate.version.quality.label}")
            Text("${candidate.version.id} · ${if (candidate.currentVersion) "aktuelle" else "historische"} Version")
            Text("Nährwertbasis: ${Amounts.format(candidate.version.nutritionBasis)} ${candidate.version.baseUnit.label}")
            Action("Katalogprodukt prüfen ${candidate.version.id.take(8)}", true) {
                review = data.catalog.find { it.version.productId == candidate.version.productId }?.version?.id.orEmpty()
            }
        } }
    }
    Text("Optional: Nur dieser Barcode wird zur kostenlosen Produktdatenquelle Open Food Facts gesendet. Keine Fotos, Bons, Vorräte oder persönlichen Angaben. Gespeicherte Produkte bleiben offline nutzbar.")
    SaveButton("Produktdaten bei Open Food Facts abfragen", external.busy || blocked.value) { review = ""; model.lookupProduct(code) }
    if (external.busy) CircularProgressIndicator()
    external.error?.let { Hint(it) }
    val displayedLookup = external.result?.takeIf { result ->
        val lookedUp = when (result) { is ProductLookup.Found -> result.draft.barcode; is ProductLookup.NotFound -> result.barcode }
        runCatching { Gtin.parse(lookedUp).canonical == Gtin.parse(code).canonical }.getOrDefault(false)
    }
    when (val result = displayedLookup) {
        is ProductLookup.NotFound -> Hint("Barcode ${result.barcode}: Produkt nicht gefunden. Manuell anlegen oder offline im Katalog suchen.")
        is ProductLookup.Found -> Panel {
            Section(result.draft.name ?: "Produktname unbekannt", result.draft.brand ?: "Marke unbekannt")
            Text("Open Food Facts · ${result.draft.sourceUrl}")
            Text("Packungsangabe: ${result.draft.quantityText ?: "Unbekannt"}")
            result.draft.warnings.forEach { Text(it) }
            Action("Quellenwerte prüfen und bearbeiten", true) { review = "EXTERNAL" }
        }
        null -> Unit
    }
    Action("Produkt manuell erfassen", true) { review = "MANUAL" }
    val draft = (displayedLookup as? ProductLookup.Found)?.draft.takeIf { review == "EXTERNAL" }
    val existing = data.versions.find { it.id == review }
    if (review == "MANUAL" || draft != null || existing != null) key(operation.value, review, draft?.fetchedAt) {
        RecognitionProductReview(data, draft, existing, code, blocked, operation, model)
    }
    Text("Produktdaten: Open Food Facts und Mitwirkende, Open Database License (ODbL). Die Quelle liefert Vorschläge; Lebensmittel-/Allergenfreigaben werden daraus nicht abgeleitet.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RecognitionProductReview(data: LocalDataState, draft: ExternalProductDraft?, existing: ProductVersionEntity?,
    scannedCode: String, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var target by rememberSaveable { mutableStateOf(existing?.productId.orEmpty()) }
    var expected by rememberSaveable { mutableStateOf(existing?.id.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(draft?.name ?: existing?.name.orEmpty()) }
    var brand by rememberSaveable { mutableStateOf(draft?.brand ?: existing?.brand.orEmpty()) }
    var barcode by rememberSaveable { mutableStateOf(draft?.barcode ?: existing?.barcode ?: scannedCode) }
    var unit by rememberSaveable { mutableStateOf((draft?.suggestedUnit ?: existing?.baseUnit)?.name.orEmpty()) }
    var basis by rememberSaveable { mutableStateOf(existing?.nutritionBasis?.let(Amounts::format) ?: "100") }
    var pack by rememberSaveable { mutableStateOf((draft?.packageAmount ?: existing?.packageAmount)?.let(Amounts::format).orEmpty()) }
    var category by rememberSaveable { mutableStateOf(existing?.category.orEmpty()) }
    var ingredients by rememberSaveable { mutableStateOf(draft?.ingredients ?: existing?.ingredients.orEmpty()) }
    var allergens by rememberSaveable { mutableStateOf(draft?.allergens ?: existing?.allergens.orEmpty()) }
    var values by rememberSaveable { mutableStateOf(Nutrient.entries.map { n -> (draft?.nutrients?.get(n) ?: existing?.nutrient(n))?.toString()?.replace('.', ',').orEmpty() }) }
    var basisConfirmed by rememberSaveable { mutableStateOf(false) }
    var barcodeConfirmed by rememberSaveable { mutableStateOf(false) }
    var labelChecked by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    val current = rememberUpdatedState(data)
    Panel {
        Section("Produktangaben prüfen", if (draft != null) "Externe Vorschläge · noch nicht gespeichert" else "Manuelle Angaben")
        IdPicker("Produktidentität für geprüfte Angaben", listOf("" to "Neues Produkt") + current.value.catalog.map { it.version.productId to "${it.version.name} · v${it.version.revision}" }, target) {
            target = it; expected = current.value.catalog.find { row -> row.version.productId == it }?.version?.id.orEmpty()
            basisConfirmed = false; barcodeConfirmed = false; labelChecked = false; confirmed = false
        }
        Field("Geprüfter Produktname", name, { name = it; confirmed = false; labelChecked = false; barcodeConfirmed = false; basisConfirmed = false })
        Field("Geprüfte Marke · optional", brand, { brand = it; confirmed = false; labelChecked = false; barcodeConfirmed = false; basisConfirmed = false })
        Field("Geprüfter Barcode · optional", barcode, { barcode = it; barcodeConfirmed = false; confirmed = false; labelChecked = false; basisConfirmed = false })
        IdPicker("Geprüfte Basiseinheit", BaseUnit.entries.map { it.name to it.label }, unit) { unit = it; basisConfirmed = false; labelChecked = false; confirmed = false }
        Field("Nährwert-Bezugsmenge in Basiseinheiten", basis, { basis = it; basisConfirmed = false; labelChecked = false; confirmed = false })
        Field("Katalog-Packungsmenge · optional", pack, { pack = it; confirmed = false; labelChecked = false })
        Text("Quellenwerte unten gelten für die dort genannte Basis. Ein Wechsel zwischen g, ml oder Portion erfordert geklärte Werte; keine automatische Dichte-/Portionsumrechnung. Die Katalogmenge beweist keine nutzbare Kaufpackung.")
        Nutrient.entries.take(4).forEach { nutrient ->
            Field("Geprüft: ${nutrient.label} in ${nutrient.unit} · optional", values[nutrient.ordinal], { value ->
                values = values.toMutableList().apply { this[nutrient.ordinal] = value }; labelChecked = false; confirmed = false
            })
        }
        Fold("Weitere geprüfte Nährstoffe und Zutaten") {
            Nutrient.entries.drop(4).forEach { nutrient ->
                Field("Geprüft: ${nutrient.label} in ${nutrient.unit} · optional", values[nutrient.ordinal], { value ->
                    values = values.toMutableList().apply { this[nutrient.ordinal] = value }; labelChecked = false; confirmed = false
                })
            }
            Field("Geprüfte Kategorie · optional", category, { category = it; confirmed = false })
            Field("Geprüfte Zutatenliste · optional", ingredients, { ingredients = it; labelChecked = false; confirmed = false })
            Field("Geprüfte Allergene · optional", allergens, { allergens = it; labelChecked = false; confirmed = false })
        }
        Check("Produktvariante und Bezugsmenge/Einheit ausdrücklich geprüft", basisConfirmed) { basisConfirmed = it; confirmed = false }
        if (barcode.isNotBlank()) Check("Diesen Barcode ausdrücklich dieser Artikelversion zuordnen", barcodeConfirmed) { barcodeConfirmed = it; confirmed = false }
        Check("Angaben zusätzlich am tatsächlichen Etikett geprüft", labelChecked) { labelChecked = it; confirmed = false }
        Field("Grund / Quelle der geprüften Produktangaben", reason, { reason = it; confirmed = false })
        Check("Diese Produktangaben speichern · kein Einkauf und kein Lagerzugang", confirmed) { confirmed = it }
        SaveButton("Geprüftes Produkt speichern", blocked.value) {
            val key = operation.value; val accepted = confirmed; val old = target.takeIf(String::isNotEmpty); val previous = expected.takeIf(String::isNotEmpty)
            val rawValues = values.toList(); val baseText = basis; val packText = pack; val chosenUnit = unit
            val p = ProductInput(name, brand.takeIf(String::isNotBlank), source = if (labelChecked) Source.LABEL else if (draft != null) Source.OPEN_FOOD_FACTS else Source.USER,
                quality = if (labelChecked) Quality.CONFIRMED else if (draft != null) Quality.SOURCED else Quality.UNKNOWN,
                sourceDetail = draft?.let(OpenFoodFactsClient::sourceDetail) ?: "Manuell geprüft${existing?.let { "; vorige Version ${it.id}" }.orEmpty()}",
                metadata = ProductMetadataInput(barcode.trim().takeIf(String::isNotEmpty), category.takeIf(String::isNotBlank), ingredients.takeIf(String::isNotBlank), allergens.takeIf(String::isNotBlank)))
            val why = reason; val checkedBasis = basisConfirmed; val checkedBarcode = barcodeConfirmed
            model.save(key) {
                require(chosenUnit.isNotEmpty()) { "Basiseinheit ausdrücklich wählen." }
                val parsed = Nutrient.entries.associateWith { nutrient -> rawValues[nutrient.ordinal].takeIf(String::isNotBlank)?.let { raw ->
                    raw.trim().replace(',', '.').toDoubleOrNull()?.also { require(it.isFinite() && it >= 0) { "Ungültiger Nährwert." } }
                        ?: throw IllegalArgumentException("Nährwert als Zahl eingeben oder leer lassen.")
                } }
                rememberRecognizedProduct(key, RecognizedProductInput(p.copy(unit = BaseUnit.valueOf(chosenUnit), nutritionBasis = Amounts.parse(baseText),
                    packageAmount = packText.takeIf(String::isNotBlank)?.let(Amounts::parse)).withNutrients(parsed), old, previous, checkedBasis, checkedBarcode, why), accepted)
            }
        }
    }
}
