package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.LocalDate

@Composable
internal fun LocalPurchaseScreen(data: LocalDataState, blocked: Boolean, operationId: String, model: LocalDataViewModel) {
    val ui by model.expenses.collectAsStateWithLifecycle()
    val guard = rememberUpdatedState(blocked); val operation = rememberUpdatedState(operationId)
    var start by rememberSaveable { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var end by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    Heading("Käufe und Ausgaben", "Manuelle Testereignisse · EUR")
    Hint("Ein bestätigter bezahlter Einkauf zählt einmal als Ausgabe. Belegpositionen werden getrennt ins Lager gebucht. Leere Beträge und Kaufdaten bleiben unbekannt.", Sage)
    key(operationId) { PurchaseEditor(guard, operation, model) }
    Field("Ausgaben ab JJJJ-MM-TT", start, { start = it })
    Field("Ausgaben bis JJJJ-MM-TT", end, { end = it })
    Action("Ausgabenzeitraum anzeigen", true) { model.showExpenses(start, end) }
    ui.error?.let { Hint(it) }
    val report = ui.report
    if (report == null) { if (ui.error == null) CircularProgressIndicator(); return }
    Panel {
        Section("Ausgabenabgleich", "${report.scope.startsOn}–${report.scope.endsOn}")
        Text("Erfasste Teilsumme: ${report.knownSubtotalCents?.let(Money::format) ?: "Zahlenbereich ungeklärt"}", Modifier.testTag("expense_subtotal"))
        Text("Vollständig abgeglichene Ausgaben: ${report.totalCents?.let(Money::format) ?: "Ungeklärt"}", Modifier.testTag("expense_total"))
        Text("${report.unknownPurchases} Einkäufe mit ungeklärtem Datum/Betrag/Währung; ${report.unconfirmedReceipts.size} Belege ohne Zahlungsbestätigung.")
        if (report.staleConfirmation) Hint("Früherer Ausgabenabgleich ist nach einer Datenänderung veraltet.")
        Hint("Auch 0 € ist erst nach ausdrücklichem vollständigem Abgleich eine Gesamtausgabe. Undatierte Einkäufe bleiben in jedem Zeitraum prüfpflichtig. Nur den Betrag bestätigen, der tatsächlich zu diesem Ausgabenbereich gehört.", Sage)
        key(operationId, report.snapshot) { ExpenseStatusEditor(rememberUpdatedState(report), guard, operation, model) }
    }
    report.unconfirmedReceipts.forEach { receipt -> key(operationId, receipt.id) {
        LegacyPaymentEditor(receipt, guard, operation, model)
    } }
    if (report.purchases.isEmpty()) Text("Keine bestätigten Einkaufsereignisse in diesem Zeitraum erfasst.")
    report.purchases.forEach { purchase -> key(operationId, purchase.event.operationId) {
        PurchaseHistory(purchase, data, guard, operation, model)
    } }
}

@Composable
private fun PurchaseEditor(blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var merchant by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var total by rememberSaveable { mutableStateOf("") }
    var originals by rememberSaveable { mutableStateOf(listOf("")) }
    var names by rememberSaveable { mutableStateOf(listOf("")) }
    var prices by rememberSaveable { mutableStateOf(listOf("")) }
    var reason by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Fold("Bezahlten Einkauf erfassen") {
        Field("Händler · optional", merchant, { merchant = it; confirmed = false })
        Field("Tatsächliches Kaufdatum JJJJ-MM-TT · optional", date, { date = it; confirmed = false })
        Field("Tatsächlich bezahlt in EUR · optional", total, { total = it; confirmed = false })
        Text("Betrag für diesen Ausgabenbereich eingeben. Nicht aus Positionspreisen oder geschätzten Einkaufskosten ableiten.")
        originals.indices.forEach { index ->
            Text("Belegposition ${index + 1}")
            Field("Originalbontext ${index + 1} · nur wenn lesbar", originals[index], { text -> originals = originals.toMutableList().apply { this[index] = text }; confirmed = false })
            Field("Eigene Bezeichnung ${index + 1} · optional", names[index], { text -> names = names.toMutableList().apply { this[index] = text }; confirmed = false })
            Field("Positionsbetrag ${index + 1} in EUR · optional", prices[index], { text -> prices = prices.toMutableList().apply { this[index] = text }; confirmed = false })
        }
        Action("Weitere Belegposition", true) {
            if (originals.size < 200) { originals = originals + ""; names = names + ""; prices = prices + ""; confirmed = false }
        }
        Text("Leere Positionsfelder erzeugen keine Position. Produkt, nutzbare Menge und Lagerort können später geklärt werden.")
        Field("Grund / Herkunft der Kauferfassung", reason, { reason = it; confirmed = false })
        Check("Tatsächlich bezahlten Einkauf mit diesen Angaben bestätigen · noch kein Lagerzugang", confirmed) { confirmed = it }
        SaveButton("Bezahlten Einkauf speichern", blocked.value) {
            val key = operation.value; val accepted = confirmed
            val shop = merchant.trim().takeIf(String::isNotEmpty); val day = date.trim().takeIf(String::isNotEmpty)
            val paid = total; val why = reason; val raw = originals.toList(); val labels = names.toList(); val costs = prices.toList()
            model.save(key) {
                val lines = raw.indices.filter { raw[it].isNotBlank() || labels[it].isNotBlank() || costs[it].isNotBlank() }.map {
                    PurchaseLineInput(raw[it].takeIf(String::isNotBlank), labels[it].takeIf(String::isNotBlank), totalCents = optionalCents(costs[it]))
                }
                savePurchase(key, PurchaseInput(shop, day, optionalCents(paid), lines, why), accepted)
            }
        }
    }
}

@Composable
private fun ExpenseStatusEditor(report: State<ExpenseReport>, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var complete by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    Fold("Ausgaben vollständig abgleichen / wieder öffnen") {
        Check("Alle Ausgaben im angezeigten Zeitraum vollständig erfasst und geprüft", complete) { complete = it; confirmed = false }
        Text("Ohne Vollständigkeit bleibt der Zeitraum offen. Beim Budgetvergleich gilt der Budgetbeginn bis zum heutigen Tag beziehungsweise Budgetende.")
        Field("Grund des Ausgabenabgleichs", reason, { reason = it; confirmed = false })
        Check("Diesen Erfassungsstatus ausdrücklich bestätigen", confirmed) { confirmed = it }
        SaveButton("Ausgabenstatus speichern", blocked.value) {
            val current = report.value; val key = operation.value; val accepted = confirmed
            val input = ExpenseStatusInput(current.scope, complete, current.snapshot, reason)
            model.save(key) { setExpenseStatus(key, input, accepted) }
        }
    }
}

@Composable
private fun LegacyPaymentEditor(receipt: ReceiptEntity, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    Fold("Alten Beleg prüfen · ${receipt.id.take(8)}") {
        Text("${receipt.merchant ?: "Händler unbekannt"} · ${receipt.purchasedOn ?: "Kaufdatum unbekannt"} · ${receipt.totalCents?.let(Money::format) ?: "Betrag unbekannt"}")
        Text("Dieser Altbeleg wurde bisher nicht als bezahlter Einkauf bestätigt. Alte Originaldaten bleiben erhalten.")
        Field("Grund der Zahlungsbestätigung", reason, { reason = it; confirmed = false })
        Check("Dieser Beleg beschreibt einen tatsächlich bezahlten Einkauf in EUR", confirmed) { confirmed = it }
        SaveButton("Alten Beleg als bezahlt bestätigen ${receipt.id.take(8)}", blocked.value) {
            val key = operation.value; val accepted = confirmed; val why = reason
            model.save(key) { confirmReceiptPayment(key, receipt.id, why, accepted) }
        }
    }
}

@Composable
private fun PurchaseHistory(purchase: PurchaseView, data: LocalDataState, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    val current = rememberUpdatedState(purchase)
    Panel {
        val value = current.value
        Section(value.receipt.merchant ?: "Händler unbekannt", value.receipt.purchasedOn ?: "Kaufdatum unbekannt")
        Text("Bezahlter Betrag: ${value.receipt.totalCents?.let(Money::format) ?: "Ungeklärt"}")
        Text("Quelle: ${value.receipt.source} · Beleg ${value.receipt.id}\nKaufvorgang: ${value.event.operationId}")
        if (value.cancellation != null) Text("Fehlerhafte Kaufbuchung storniert · bleibt in Historie")
        if (value.lines.isEmpty()) Text("Keine einzelnen Belegpositionen erfasst.")
        value.lines.forEach { line -> key(line.line.id) {
            Text("${line.line.interpretedName ?: "Bezeichnung unbekannt"} · Position ${line.line.id.take(8)}")
            line.line.originalText?.let { Text("Original: $it") }
            Text("Positionsbetrag: ${line.line.totalCents?.let(Money::format) ?: "Ungeklärt"} · wird nicht zusätzlich als Ausgabe gezählt")
            val stockId = line.booking?.operationId ?: line.line.stockOperationId
            if (stockId != null) {
                Text("Lagerzugang: $stockId${if (line.reversedBy != null) " · storniert, keine erneute Buchung dieser Position" else " · bereits gebucht"}")
                if (line.reversedBy == null) PurchaseReversalEditor(stockId, false, blocked, operation, model)
            } else if (value.cancellation == null) PurchaseStockEditor(line.line, value.receipt.purchasedOn, data, blocked, operation, model)
        } }
        if (value.cancellation == null) PurchaseReversalEditor(value.event.operationId, true, blocked, operation, model)
    }
}

@Composable
private fun PurchaseStockEditor(line: ReceiptLineEntity, purchasedOn: String?, data: LocalDataState,
    blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var version by rememberSaveable { mutableStateOf(line.versionId.orEmpty()) }
    var location by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf(line.amount?.let(Amounts::format).orEmpty()) }
    var basis by rememberSaveable { mutableStateOf("") }
    var bestBefore by rememberSaveable { mutableStateOf("") }
    var useBy by rememberSaveable { mutableStateOf("") }
    var released by rememberSaveable { mutableStateOf(false) }
    var basisConfirmed by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    val dataState = rememberUpdatedState(data)
    Fold("Position ins Lager buchen ${line.id.take(8)}") {
        if (purchasedOn == null) Hint("Kaufdatum fehlt. Einkauf bleibt gespeichert; für einen Lagerzugang zuerst eine korrekte datierte Kaufbuchung erstellen.")
        val versions = dataState.value.versions
        val selected = versions.find { it.id == version }
        IdPicker("Kauf-Produktversion ${line.id.take(8)}", versions.map { it.id to "${it.name} · v${it.revision} · ${it.baseUnit.label} · ${it.id.take(8)}" }, version) {
            version = it; amount = ""; basisConfirmed = false; confirmed = false; released = false
        }
        IdPicker("Kauf-Lagerort ${line.id.take(8)}", dataState.value.locations.filter { it.confirmed }.map { it.id to it.name }, location) { location = it; confirmed = false; released = false }
        Field("Nutzbarer Zugang in ${selected?.baseUnit?.label ?: "Basiseinheiten"} ${line.id.take(8)}", amount, { amount = it; basisConfirmed = false; confirmed = false })
        Field("Mengenbasis der Position ${line.id.take(8)}", basis, { basis = it; basisConfirmed = false; confirmed = false })
        Text("Nutzbare Gramm, Milliliter oder Portionen klären. Brutto-, Abtropf- und Stückgewicht sind nicht automatisch gleich. Zugang am ${purchasedOn ?: "ungeklärten Kaufdatum"}.")
        Field("MHD der Kaufposition ${line.id.take(8)} · optional", bestBefore, { bestBefore = it; confirmed = false; released = false })
        Field("Verbrauchsdatum der Kaufposition ${line.id.take(8)} · optional", useBy, { useBy = it; confirmed = false; released = false })
        Check("Nutzbare Menge und Einheit dieser Position geprüft ${line.id.take(8)}", basisConfirmed) { basisConfirmed = it; confirmed = false }
        Check("Charge dieser Kaufposition ausdrücklich freigeben ${line.id.take(8)}", released) { released = it; confirmed = false }
        Field("Grund des Positionszugangs ${line.id.take(8)}", reason, { reason = it; confirmed = false })
        Check("Diesen tatsächlichen Lagerzugang bestätigen ${line.id.take(8)}", confirmed) { confirmed = it }
        SaveButton("Kaufposition buchen ${line.id.take(8)}", blocked.value) {
            val key = operation.value; val accepted = confirmed; val quantity = amount
            val batch = BatchInput(version, location, 1, purchasedOn, bestBefore.trim().takeIf(String::isNotEmpty),
                useBy.trim().takeIf(String::isNotEmpty), if (released) ReleaseStatus.RELEASED else ReleaseStatus.UNCHECKED)
            val checked = basisConfirmed; val description = basis; val why = reason
            model.save(key) { bookPurchaseLine(key, PurchaseStockInput(line.id, batch.copy(amount = Amounts.parse(quantity)), checked, description, why), accepted) }
        }
    }
}

@Composable
private fun PurchaseReversalEditor(original: String, payment: Boolean, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var day by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val name = if (payment) "Kaufbuchung" else "Positionszugang"
    Fold("Fehlerhafte Buchung prüfen · $name ${original.take(8)}") {
        Text(if (payment) "Korrigiert eine fehlerhafte Erfassung. Tatsächliche Rückgaben/Rückerstattungen werden hier nicht erfasst. Aktive Lagerzugänge samt abhängigen Vorgängen zuerst stornieren. Danach gegebenenfalls korrekt neu erfassen."
            else "Der ursprüngliche Zugang bleibt erhalten; eine Gegenbewegung hebt ihn auf. Abhängige Verbräuche/Umlagerungen zuerst prüfen. Diese Position bleibt gegen erneutes Buchen gesperrt.")
        Field("Stornodatum $original", day, { day = it; confirmed = false })
        Field("Stornogrund $original", reason, { reason = it; confirmed = false })
        Check("Fehlerhafte Buchung ausdrücklich stornieren $original", confirmed) { confirmed = it }
        SaveButton("$name stornieren $original", blocked.value) {
            val key = operation.value; val input = ReversalInput(original, day.trim(), reason); val accepted = confirmed
            model.save(key) { if (payment) cancelPurchase(key, input, accepted) else reverse(key, input, accepted) }
        }
    }
}

private fun optionalCents(text: String): Long? = text.takeIf(String::isNotBlank)?.let(Money::parse)
