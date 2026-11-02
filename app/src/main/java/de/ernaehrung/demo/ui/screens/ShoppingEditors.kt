package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*

@Composable
internal fun ShoppingPriceEditor(data: LocalDataState, report: State<ShoppingReport>, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var previous by rememberSaveable { mutableStateOf("") }; var version by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }; var observed by rememberSaveable { mutableStateOf(AppCalendar.today().toString()) }
    var amount by rememberSaveable { mutableStateOf("") }; var price by rememberSaveable { mutableStateOf("") }
    var whole by rememberSaveable { mutableStateOf(false) }; var basis by rememberSaveable { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf("") }; var priceChecked by rememberSaveable { mutableStateOf(false) }
    var validFrom by rememberSaveable { mutableStateOf("") }; var validUntil by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    Fold("Packungsbasis und Preis speichern") {
        val prices = report.value.prices; val superseded = prices.mapNotNull { it.supersedesId }.toSet()
        IdPicker("Preisfassung bearbeiten", listOf("" to "Neue Preisangabe") + prices.filter { it.id !in superseded }.map {
            it.id to "${it.merchant ?: "Händler unbekannt"} · ${it.observedOn} · ${it.id.take(8)}"
        }, previous) { id ->
            val old = prices.find { it.id == id }; previous = id; version = old?.versionId.orEmpty()
            merchant = old?.merchant.orEmpty(); observed = old?.observedOn ?: AppCalendar.today().toString()
            amount = old?.amount?.let(Amounts::format).orEmpty(); price = old?.totalCents?.let { Money.format(it).removeSuffix(" €") }.orEmpty()
            whole = old?.wholePack == true; basis = false; priceChecked = false; description = old?.basisDescription.orEmpty()
            validFrom = old?.validFrom.orEmpty(); validUntil = old?.validUntil.orEmpty(); reason = ""
        }
        IdPicker("Artikelversion der Preisangabe", data.versions.map { it.id to "${it.name} · v${it.revision} · ${it.baseUnit.label} · ${it.id.take(8)}" }, version) {
            if (it != version) { version = it; amount = ""; price = ""; description = ""; whole = false; basis = false; priceChecked = false }
        }
        val unit = data.versions.find { it.id == version }?.baseUnit?.label ?: "Basiseinheit"
        Field("Händler der Preisangabe · optional", merchant, { merchant = it; priceChecked = false })
        Field("Beobachtet am JJJJ-MM-TT", observed, { observed = it; priceChecked = false })
        Field("Nutzbare Menge der Preisangabe in $unit · optional", amount, { amount = it; basis = false; priceChecked = false })
        Field("Preis für diese Menge in EUR · optional", price, { price = it; priceChecked = false })
        Check("Preisbezug ist genau eine ganze Kaufpackung", whole) { whole = it; basis = false; priceChecked = false }
        Field("Mengenbasis beschreiben, etwa Abtropfgewicht", description, { description = it; basis = false })
        Check("Nutzbare Packungsbasis ausdrücklich bestätigt", basis) { basis = it }
        Field("Preis gültig ab JJJJ-MM-TT · optional", validFrom, { validFrom = it; priceChecked = false })
        Field("Preis gültig bis JJJJ-MM-TT · optional", validUntil, { validUntil = it; priceChecked = false })
        Check("Preisangabe geprüft · keine bezahlte Ausgabe", priceChecked) { priceChecked = it }
        Field("Quelle / Änderungsgrund der Preisangabe", reason, { reason = it })
        Text("Leere Mengen/Preise bleiben unbekannt. Alte Mengen und Katalog-Packungsgrößen sind keine automatische Bestätigung einer nutzbaren Kaufpackung. Neue Fassung ersetzt die alte für Vorschläge und erhält die Historie.")
        SaveButton("Preisfassung lokal speichern", blocked.value) {
            val key = operation.value; val prev = previous.takeIf(String::isNotEmpty); val v = version; val shop = merchant.takeIf(String::isNotBlank)
            val date = observed; val quantity = amount; val cents = price; val pack = whole; val verified = basis; val note = description.takeIf(String::isNotBlank)
            val first = validFrom.takeIf(String::isNotBlank); val last = validUntil.takeIf(String::isNotBlank); val accepted = priceChecked; val source = reason
            model.save(key) { savePrice(key, PriceInput(v, shop, date, quantity.takeIf(String::isNotBlank)?.let(Amounts::parse),
                cents.takeIf(String::isNotBlank)?.let(Money::parse), pack, verified, note, first, last, accepted, source), prev) }
        }
    }
}

@Composable
internal fun ShoppingBudgetEditor(report: State<ShoppingReport>, blocked: State<Boolean>, operation: State<String>, model: LocalDataViewModel) {
    var previous by rememberSaveable { mutableStateOf("") }; var label by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(AppCalendar.today().toString()) }; var end by rememberSaveable { mutableStateOf(AppCalendar.today().plusDays(6).toString()) }
    var amount by rememberSaveable { mutableStateOf("") }; var confirmed by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    Fold("Budgetobergrenze speichern") {
        val budgets = report.value.budgets; val replaced = budgets.mapNotNull { it.supersedesId }.toSet()
        IdPicker("Budgetfassung bearbeiten", listOf("" to "Neues Budget") + budgets.filter { it.id !in replaced }.map {
            it.id to "${it.label ?: "Altes Budget"} · ${it.startsOn}–${it.endsOn} · ${it.id.take(8)}"
        }, previous) { id ->
            val old = budgets.find { it.id == id }; previous = id; label = old?.label.orEmpty()
            start = old?.startsOn ?: AppCalendar.today().toString(); end = old?.endsOn ?: AppCalendar.today().plusDays(6).toString()
            amount = old?.amountCents?.let { Money.format(it).removeSuffix(" €") }.orEmpty(); confirmed = false; reason = ""
        }
        Field("Budgetname", label, { label = it })
        Field("Budgetbeginn JJJJ-MM-TT", start, { start = it; confirmed = false })
        Field("Budgetende JJJJ-MM-TT", end, { end = it; confirmed = false })
        Field("Budgetobergrenze in EUR · optional", amount, { amount = it; confirmed = false })
        Check("Diese Budgetobergrenze bewusst bestätigen", confirmed) { confirmed = it }
        Field("Änderungsgrund des Budgets", reason, { reason = it })
        Text("Das ist die Obergrenze für den Zeitraum. Bereits bezahlte Ausgaben sind noch nicht vollständig abgeglichen; ein freies Restbudget wird nicht erfunden. Überlappende Budgets werden nicht addiert.")
        SaveButton("Budgetfassung lokal speichern", blocked.value) {
            val key = operation.value; val prev = previous.takeIf(String::isNotEmpty); val name = label; val first = start; val last = end
            val cents = amount; val accepted = confirmed; val note = reason
            model.save(key) { saveBudget(key, BudgetInput(name, first, last, cents.takeIf(String::isNotBlank)?.let(Money::parse), accepted, note), prev) }
        }
    }
}
