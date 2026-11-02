package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*

@Composable
internal fun LocalShoppingScreen(data: LocalDataState, blocked: Boolean, operationId: String, model: LocalDataViewModel) {
    val ui by model.shopping.collectAsStateWithLifecycle()
    val guard = rememberUpdatedState(blocked); val operation = rememberUpdatedState(operationId)
    var start by rememberSaveable { mutableStateOf(AppCalendar.today().toString()) }
    var purchase by rememberSaveable { mutableStateOf(AppCalendar.today().toString()) }
    var horizon by rememberSaveable { mutableStateOf("7 Tage") }
    var budget by rememberSaveable { mutableStateOf("") }
    Heading("Einkaufsvorschläge", "Offener Restbedarf · Packungen · Preisangaben")
    Hint("Diese Liste plant einen Einkauf. Auswahl und Abhaken buchen weder Kauf, Ausgabe noch Zugang. Abgehakte Positionen bleiben in Bedarf und Kostenschätzung enthalten.", Sage)
    Field("Bedarf ab JJJJ-MM-TT", start, { start = it })
    WrappingChoices(listOf("1 Tag", "7 Tage", "56 Tage"), horizon, "shopping_horizon_") { horizon = it }
    Field("Geplanter Einkaufstag JJJJ-MM-TT", purchase, { purchase = it })
    val report = ui.report
    val budgets = report?.budgets.orEmpty()
    val superseded = budgets.mapNotNull { it.supersedesId }.toSet()
    IdPicker("Budget für Einkaufsvergleich", listOf("" to "Kein Budget gewählt") + budgets.filter { it.id !in superseded }.map {
        it.id to "${it.label ?: "Altes Budget"} · ${it.startsOn}–${it.endsOn} · ${it.amountCents?.let { value -> if (it.currency == "EUR") Money.format(value) else "Währung ungeklärt" } ?: "Betrag ungeklärt"}"
    }, budget) { budget = it }
    Action("Einkaufsliste anzeigen", true) {
        model.showShopping(start, when (horizon) { "1 Tag" -> 1L; "56 Tage" -> 56L; else -> 7L }, purchase, budget.takeIf(String::isNotEmpty))
    }
    ui.error?.let { Hint(it) }
    if (report == null) { if (ui.error == null) CircularProgressIndicator(); return }
    Text("Angezeigt: Bedarf ${report.scope.startsOn}–${report.scope.endsOn}, Einkauf ${report.scope.purchaseOn}, Bestandsstand ${report.asOf}")
    androidx.compose.runtime.key(operationId) {
        ShoppingPriceEditor(data, rememberUpdatedState(report), guard, operation, model)
        ShoppingBudgetEditor(rememberUpdatedState(report), guard, operation, model)
    }
    Panel {
        Section("Kosten der gespeicherten Auswahl", "Geschätzte Einkaufskosten, keine bezahlten Ausgaben")
        Text("Berechnete Teilsumme: ${report.knownSubtotalCents?.let(Money::format) ?: "Zahlenbereich ungeklärt"}", Modifier.testTag("shopping_subtotal"))
        Text("Voraussichtliche Gesamtkosten: ${report.estimatedTotalCents?.let(Money::format) ?: "Ungeklärt"}", Modifier.testTag("shopping_total"))
        if (report.incomplete) Hint("Unvollständige Schätzung: fehlende Mengen, Preis-/Packungsangaben oder Terminprobleme bleiben offen.")
        KV("Bestätigte Budgetobergrenze", report.budget.usableLimitCents?.let(Money::format) ?: "Ungeklärt")
        report.budget.issue?.let { Text(it) }
        val excess = report.budget.knownCostsOverLimitCents
        if (excess != null && excess > 0) Hint("Bekannte Einkaufskosten und erfasste Ausgaben überschreiten die Budgetobergrenze um ${Money.format(excess)}.")
        KV("Erfasste Ausgaben im Budgetzeitraum bis ${report.budget.expensesThroughOn ?: "ungeklärt"}", report.budget.knownPaidCents?.let(Money::format) ?: "Ungeklärt")
        Text("Verbleibendes Budget: ${report.budget.remainingBudgetCents?.let(Money::format) ?: "ungeklärt. Ausgaben unter Käufe für den Budgetzeitraum bis heute vollständig abgleichen."}", Modifier.testTag("shopping_budget_remaining"))
        KV("Nach offenem geplantem Einkauf", report.budget.afterPlannedCents?.let(Money::format) ?: "Ungeklärt")
    }
    if (report.items.isEmpty()) Text("Keine aktuelle Produktfehlmenge in diesem Ausschnitt. Das bestätigt keine vollständig geplante Ernährung.")
    report.items.forEach { line ->
        androidx.compose.runtime.key(operationId, line.need.versionId, report.scope) {
            ShoppingItem(line, report.scope, guard, operation, model)
        }
    }
    Hint("Vor einem Kauf Haltbarkeit bis zum Bedarfstermin, tatsächlichen Lagerplatz und Überschüsse prüfen. Große Packungen sind nicht automatisch wirtschaftlicher. Keine automatische Bestellung, keine erfundene Umrechnung.", Sage)
}

@Composable
private fun ShoppingItem(line: ShoppingLine, scope: ShoppingScope, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var selected by rememberSaveable(line.snapshot) { mutableStateOf(line.offer?.id.orEmpty()) }
    var checked by rememberSaveable(line.snapshot) { mutableStateOf(line.checked) }
    val current = rememberUpdatedState(line)
    Panel {
        val item = current.value
        Section(item.need.name, "${item.firstNeededOn ?: "Termin ungeklärt"} bis ${item.lastNeededOn ?: "Termin ungeklärt"}")
        Text("Bekannte Fehlmenge: ${Amounts.format(item.need.knownMissing)} ${item.need.unit.label}${if (item.need.unknownRequirements > 0) " + weitere Menge ungeklärt" else ""}")
        Text(if (item.checked) "Gespeichert: auf Liste abgehakt · kein Kauf" else "Gespeichert: nicht abgehakt", Modifier.testTag("shopping_checked_${item.need.versionId}"))
        if (item.staleCheck) Hint("Frühere Markierung ist nach Datenänderung veraltet. Mengen und Preis erneut prüfen.")
        val paidHistory by model.historicalPaid.collectAsStateWithLifecycle()
        Action("Eigene vergangene Bezahlpreise ansehen", true) {
            model.showHistoricalPaidForShopping(item.need.versionId, scope.purchaseOn)
        }
        if (paidHistory.scope?.productVersionId == item.need.versionId &&
            paidHistory.scope?.targetPurchaseOn == scope.purchaseOn) {
            if (paidHistory.busy) Text("Private Bezahlpreishistorie wird gelesen.")
            paidHistory.error?.let { Hint(it) }
            paidHistory.report?.let { history ->
                Text(
                    "Private historische Bezahlpreise: ${history.references.size} belegte Positionen; " +
                        "niedrigster Preis je bestätigter Kaufeinheit: " +
                        (history.lowestHistoricalUnitPriceCents?.let(Money::format) ?: "unbekannt"),
                    Modifier.testTag("shopping_paid_history_${item.need.versionId}")
                )
                Hint("Nur früher tatsächlich bezahlte eigene Preise bis zum gewählten Kaufdatum. " +
                    "Kein aktuelles Angebot, keine bestätigte Filiale, kein automatischer Einkauf.")
            }
        }
        IdPicker("Packungs-/Preisfassung ${item.need.versionId.take(8)}", listOf("" to "Keine Preisfassung") + item.alternatives.map {
            it.id to "${it.merchant ?: "Händler unbekannt"} · ${it.amount?.let(Amounts::format) ?: "Menge ungeklärt"} ${item.need.unit.label} · ${it.totalCents?.let { cents -> if (it.currency == "EUR") Money.format(cents) else "Währung ungeklärt" } ?: "Preis unbekannt"} · ${it.id.take(8)}"
        }, selected) { selected = it; checked = false }
        val quote = item.quotes.find { it.offer?.id == selected }
        if (quote != null) {
            Text("Vorschau der Auswahl · erst Speichern übernimmt sie in die Gesamtsumme.")
            Text("${if (quote.exactPackCount == null) "Mindestens bekannte Packungen" else "Ganze Packungen"}: ${quote.packsForKnownNeed?.toString() ?: "Ungeklärt"}", Modifier.testTag("shopping_packs_${item.need.versionId}"))
            KV("Kaufmenge für bekannte Fehlmenge", quote.purchasedAmount?.let { "${Amounts.format(it)} ${item.need.unit.label}" } ?: "Ungeklärt")
            KV("Überschuss gegenüber bekannter Fehlmenge", quote.surplus?.let { "${Amounts.format(it)} ${item.need.unit.label}" } ?: "Ungeklärt")
            Text("Kosten dieser Auswahl: ${quote.costCents?.let(Money::format) ?: "Ungeklärt"}", Modifier.testTag("shopping_cost_${item.need.versionId}"))
            Text("Grundlage: ${quote.offer?.basisDescription ?: "Unbekannt"}; beobachtet ${quote.offer?.observedOn ?: "Unbekannt"}")
            Text("Preis gültig ${quote.offer?.validFrom ?: "Ungeklärt"} bis ${quote.offer?.validUntil ?: "Ungeklärt"}")
            quote.issues.forEach { Text(it.label, color = MaterialTheme.colorScheme.error) }
        } else Text("Packungs-/Preisfassung auswählen oder die fehlenden Angaben unten erfassen.")
        Check("Auf Einkaufsliste abhaken ${item.need.versionId.take(8)} · kein Kauf", checked) { checked = it }
        SaveButton("Auswahl / Markierung speichern ${item.need.versionId.take(8)}", blocked.value) {
            val key = operation.value
            val input = ShoppingMarkInput(scope, item.need.versionId, selected.takeIf(String::isNotEmpty), checked, item.snapshot)
            model.save(key) { markShopping(key, input) }
        }
    }
}
