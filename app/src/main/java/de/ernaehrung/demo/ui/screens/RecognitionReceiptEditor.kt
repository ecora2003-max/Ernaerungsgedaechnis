package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.fillMaxWidth
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
internal fun ReceiptRecognitionEditor(data: LocalDataState, recognition: RecognitionData, blocked: State<Boolean>,
    operation: State<String>, model: LocalDataViewModel) {
    val photo by model.photo.collectAsStateWithLifecycle()
    val reviewUi by model.receiptReview.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf("") }
    PhotoControls(true, blocked, model)
    photo.text?.let { result ->
        Fold("Erkannten Bontext ansehen") { SelectionContainer { Text(result.text) } }
        SaveButton("Erkannten Text als Bonentwurf speichern", blocked.value || photo.busy) {
            val key = operation.value; val method = photo.method ?: "GALLERY_OCR"
            model.save(key) { saveReceiptScan(key, result.text, method, result.imageHash).also(model::showReceiptReview) }
        }
    }
    Fold("Bontext manuell eingeben / einfügen") {
        Text("Ohne Foto oder bei schlechter Erkennung: tatsächlich lesbaren Bontext eingeben. Es wird zunächst nur ein Entwurf gespeichert.")
        OutlinedTextField(manual, { manual = it }, Modifier.fillMaxWidth().testTag("receipt_manual_text"),
            label = { Text("Lesbarer Bontext · mehrere Zeilen möglich") }, minLines = 4, maxLines = 12)
        SaveButton("Manuellen Bonentwurf speichern", blocked.value) {
            val key = operation.value; val text = manual
            model.save(key) { saveReceiptScan(key, text, "MANUAL_TEXT").also(model::showReceiptReview) }
        }
    }
    Text("Bonentwürfe sind keine Zahlungen. Derselbe Bildinhalt wird über seinen Prüfwert wiedergefunden; ein bereits übernommener Entwurf erzeugt keinen zweiten Kauf.")
    recognition.scans.forEach { draft -> Panel {
        val parsed = runCatching { ReceiptParser.parse(draft.scan.rawText) }.getOrNull()
        Section(parsed?.merchant ?: "Händler ungeklärt", "${captureLabel(draft.scan.method)} · ${draft.scan.operationId.take(8)}")
        Text("${parsed?.purchasedOn ?: "Datum ungeklärt"} · Vorschlag ${parsed?.totalCents?.let(Money::format) ?: "Betrag ungeklärt"}")
        if (draft.resolution == null) Action("Bonentwurf prüfen ${draft.scan.operationId.take(8)}", true) { model.showReceiptReview(draft.scan.operationId) }
        else Text(if (draft.resolution.receiptId != null) "Bereits als Kauf übernommen · ${draft.resolution.receiptId}" else "Verworfen · keine Zahlung")
    } }
    reviewUi.error?.let { Hint(it) }
    if (reviewUi.resolved) Hint("Dieser Bonentwurf wurde bereits übernommen oder verworfen. Gespeicherte Käufe stehen unter Käufe; Lagerzugänge dort separat bestätigen.", Sage)
    reviewUi.review?.let { report -> key(operation.value, report.draft.scan.operationId, report.snapshot) {
        ReceiptReviewEditor(report, data, blocked, operation, model)
    } }
}

@Composable
private fun ReceiptReviewEditor(report: ReceiptReview, data: LocalDataState, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    val current = rememberUpdatedState(report); val catalog = rememberUpdatedState(data)
    var merchant by rememberSaveable { mutableStateOf(report.parsed.merchant.orEmpty()) }
    var day by rememberSaveable { mutableStateOf(report.parsed.purchasedOn.orEmpty()) }
    var total by rememberSaveable { mutableStateOf(report.parsed.totalCents?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString().replace('.', ',') }.orEmpty()) }
    var originals by rememberSaveable { mutableStateOf(report.parsed.lines.map { it.originalText }) }
    var names by rememberSaveable { mutableStateOf(report.parsed.lines.map { it.name }) }
    var prices by rememberSaveable { mutableStateOf(report.parsed.lines.map { it.totalCents?.let { cents -> java.math.BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',') }.orEmpty() }) }
    var versions by rememberSaveable { mutableStateOf(report.matches.map { it.reusableVersionId.orEmpty() }) }
    var manualVersions by rememberSaveable { mutableStateOf(report.matches.map { false }) }
    var include by rememberSaveable { mutableStateOf(report.parsed.lines.map { true }) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var distinct by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    Panel {
        Section("Bonvorschläge prüfen", "Entwurf ${report.draft.scan.operationId.take(8)} · ${captureLabel(report.draft.scan.method)}")
        report.parsed.warnings.forEach { Hint(it) }
        Text("Vorbefüllte Angaben sind Vorschläge. Leere Felder bleiben unbekannt. Ändern sich Alias-/Produktdaten, wird diese Prüfung aus dem Originaltext neu aufgebaut.")
        Fold("Unveränderten Ausgangstext ansehen") { SelectionContainer { Text(current.value.draft.scan.rawText) } }
        Field("Geprüfter Bon-Händler · optional", merchant, {
            merchant = it; confirmed = false; distinct = false
            versions = versions.mapIndexed { i, value -> if (manualVersions[i]) value else "" }
        })
        Field("Geprüftes Kaufdatum JJJJ-MM-TT · optional", day, { day = it; confirmed = false; distinct = false })
        Field("Geprüfter bezahlter Gesamtbetrag in EUR · optional", total, { total = it; confirmed = false; distinct = false })
        Text("Den wirklich bezahlten Betrag prüfen. Bar gegeben, Rückgeld, Steuer und Positionssumme ersetzen ihn nicht. Eine abfotografierte Rechnung beweist noch keine Zahlung.")
        originals.indices.forEach { index ->
            val match = current.value.matches.getOrNull(index)
            Fold("Bonposition ${index + 1} prüfen") {
                Check("Position ${index + 1} übernehmen", include[index]) { enabled -> include = include.toMutableList().apply { this[index] = enabled }; confirmed = false }
                Text(if (AliasText.merchant(merchant) != AliasText.merchant(current.value.parsed.merchant) || names[index] != current.value.parsed.lines.getOrNull(index)?.name)
                    "Aliasgrundlage geändert · Produktzuordnung neu prüfen" else match?.let { matchLabel(it.status) } ?: "Manuell ergänzte Position")
                Field("Gelesener Originaltext ${index + 1} · optional", originals[index], { text ->
                    originals = originals.toMutableList().apply { this[index] = text }; confirmed = false
                    if (!manualVersions[index]) versions = versions.toMutableList().apply { this[index] = "" }
                })
                Field("Geprüfte Positionsbezeichnung ${index + 1}", names[index], { text ->
                    names = names.toMutableList().apply { this[index] = text }; confirmed = false
                    if (!manualVersions[index]) versions = versions.toMutableList().apply { this[index] = "" }
                })
                Field("Geprüfter Positionsbetrag ${index + 1} in EUR · optional", prices[index], { text -> prices = prices.toMutableList().apply { this[index] = text }; confirmed = false })
                IdPicker("Produktvorschlag der Bonposition ${index + 1}", listOf("" to "Produktversion ungeklärt") + catalog.value.versions.map {
                    it.id to "${it.name} · v${it.revision} · ${it.id.take(8)}"
                }, versions[index]) { id ->
                    versions = versions.toMutableList().apply { this[index] = id }
                    manualVersions = manualVersions.toMutableList().apply { this[index] = true }; confirmed = false
                }
                val oldMatch = current.value.matches.getOrNull(index)?.reusableVersionId
                if (oldMatch != null && versions[index] != oldMatch) Text("Automatischen Aliasvorschlag manuell geändert. Neue dauerhafte Aliasentscheidung getrennt unter Aliase bestätigen.")
                current.value.parsed.lines.getOrNull(index)?.quantityText?.let { Text("Gedruckte Mengenangabe: $it · Lagerbasis bleibt ungeklärt") }
                Text("Die Produktzuordnung erzeugt hier keinen Lagerzugang. Nutzbare Menge, Einheit, Haltbarkeit und Ort später unter Käufe prüfen.")
            }
        }
        Action("Geprüfte Bonposition ergänzen", true) {
            if (originals.size < 200) {
                originals = originals + ""; names = names + ""; prices = prices + ""; versions = versions + ""; manualVersions = manualVersions + false; include = include + true; confirmed = false
            }
        }
        Field("Grund / Herkunft der Bonbestätigung", reason, { reason = it; confirmed = false; discard = false })
        Check("Falls ein passender Kauf existiert: eigenständigen weiteren realen Einkauf bestätigen", distinct) { distinct = it; confirmed = false }
        Check("Bonangaben geprüft und diesen Einkauf tatsächlich bezahlt · kein Lagerzugang", confirmed) { confirmed = it }
        SaveButton("Geprüften Bon als bezahlten Einkauf übernehmen", blocked.value) {
            val key = operation.value; val raw = originals.toList(); val labels = names.toList(); val costs = prices.toList()
            val selected = versions.toList(); val kept = include.toList(); val shop = merchant.trim().takeIf(String::isNotEmpty)
            val purchasedOn = day.trim().takeIf(String::isNotEmpty); val paid = total; val why = reason; val accepted = confirmed; val separate = distinct
            val source = current.value
            model.save(key) {
                val lines = raw.indices.filter { kept[it] }.map { index -> PurchaseLineInput(raw[index].takeIf(String::isNotBlank),
                    labels[index].takeIf(String::isNotBlank), selected[index].takeIf(String::isNotEmpty),
                    totalCents = costs[index].takeIf(String::isNotBlank)?.let(Money::parse)) }
                acceptReceiptScan(key, source.draft.scan.operationId, PurchaseInput(shop, purchasedOn,
                    paid.takeIf(String::isNotBlank)?.let(Money::parse), lines, why, Source.USER), source.snapshot, accepted, separate)
            }
        }
        Fold("Diesen Bonentwurf verwerfen") {
            Text("Nur den Entwurf schließen. Keine Zahlung stornieren und keine Bestandsbewegung erzeugen.")
            Check("Bonentwurf ausdrücklich verwerfen", discard) { discard = it }
            SaveButton("Bonentwurf verwerfen", blocked.value) {
                val key = operation.value; val id = current.value.draft.scan.operationId; val why = reason; val accepted = discard
                model.save(key) { discardReceiptScan(key, id, why, accepted) }
            }
        }
    }
}

private fun captureLabel(method: String) = when (method) {
    "CAMERA_OCR" -> "Kamerafoto · Texterkennung"
    "GALLERY_OCR" -> "Ausgewähltes Bild · Texterkennung"
    "MANUAL_TEXT" -> "Manuelle Texteingabe"
    else -> "Herkunft ungeklärt"
}
