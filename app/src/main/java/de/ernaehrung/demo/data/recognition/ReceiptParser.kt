package de.ernaehrung.demo.data.recognition

import de.ernaehrung.demo.data.local.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class ReceiptLineSuggestion(val originalText: String, val name: String, val totalCents: Long?,
    val quantityText: String? = null)
data class ReceiptSuggestion(val merchant: String?, val purchasedOn: String?, val totalCents: Long?,
    val lines: List<ReceiptLineSuggestion>, val warnings: List<String>)

/** Conservative German receipt parser. Ambiguity produces a gap, never an invented paid amount or quantity. */
object ReceiptParser {
    const val VERSION = "de-receipt-1"
    const val MAX_TEXT = 30_000
    private val price = Regex("(?<![\\d.,])([0-9]+[,.][0-9]{2})\\s*(?:€|EUR)?\\s*(?:[A-D])?\\s*$", RegexOption.IGNORE_CASE)
    private val totalLabel = Regex("^(?:gesamt(?:betrag)?|summe|endsumme|zu zahlen|total)\\b", RegexOption.IGNORE_CASE)
    private val excluded = Regex("^(?:bar(?:zahlung)?|gegeben|r(?:ü|ue|u)ckgeld|wechselgeld|kartenzahlung|ec[- ]?karte|girocard|visa|mastercard|mwst|ust|steuer|netto|brutto|rabatt|pfand|retoure|storno|gutschrift|ersparnis|zwischensumme)\\b", RegexOption.IGNORE_CASE)
    private val date = Regex("(?<!\\d)([0-9]{2}\\.[0-9]{2}\\.[0-9]{4}|[0-9]{4}-[0-9]{2}-[0-9]{2})(?!\\d)")
    private val quantity = Regex("\\b(?:[0-9]+(?:[,.][0-9]+)?\\s*(?:kg|g|ml|l)|[0-9]+\\s*[x×]\\s*[0-9]+[,.][0-9]{2})\\b", RegexOption.IGNORE_CASE)
    private val malformedPrice = Regex("\\s+(?:[0-9]{3,}\\s*[A-D]|[0-9]+[,.][0-9]{1,3}\\s*[A-D]?)\\s*$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ReceiptSuggestion {
        require(raw.isNotBlank() && raw.length <= MAX_TEXT) { "Bontext fehlt oder ist zu lang." }
        val lines = raw.lines().map(String::trim).filter(String::isNotBlank)
        val warnings = mutableListOf("OCR und Textanalyse liefern ungeprüfte Vorschläge. Beträge, Datum und Positionen am Original prüfen.")
        val dates = date.findAll(raw).mapNotNull { match ->
            runCatching {
                if (match.value.contains('.')) LocalDate.parse(match.value, DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT))
                else LocalDate.parse(match.value)
            }.getOrNull()?.toString()
        }.distinct().toList()
        if (dates.size != 1) warnings += if (dates.isEmpty()) "Kaufdatum nicht eindeutig lesbar." else "Mehrere Datumsangaben; Kaufdatum auswählen."
        val totalRows = lines.filter { totalLabel.containsMatchIn(it) }
        val parsedTotals = totalRows.mapNotNull { line ->
            price.find(line)?.takeUnless { negativePrefix(line.substring(0, it.range.first)) }
                ?.groupValues?.get(1)?.let { runCatching { Money.parse(it) }.getOrNull() }
        }
        val totals = parsedTotals.distinct()
        val total = totals.singleOrNull()?.takeIf { parsedTotals.size == totalRows.size }
        if (total == null) warnings += if (totals.size <= 1) "Kein eindeutiger Gesamtbetrag; nicht aus Einzelpositionen ableiten." else "Widersprüchliche Gesamtbeträge."
        var skipped = 0
        val suggestions = lines.mapNotNull { line ->
            if (totalLabel.containsMatchIn(line) || excluded.containsMatchIn(line) || date.containsMatchIn(line) || line.startsWith("#")) return@mapNotNull null
            val match = price.find(line)
            if (match == null) {
                val uncertain = malformedPrice.find(line) ?: return@mapNotNull null
                val name = line.substring(0, uncertain.range.first).trim()
                if (name.none(Char::isLetter) || name.contains('%')) return@mapNotNull null
                warnings += "Mindestens ein Positionspreis ist nicht sicher lesbar; Betrag bleibt unbekannt."
                return@mapNotNull ReceiptLineSuggestion(line, name, null, quantity.find(line)?.value)
            }
            val name = line.substring(0, match.range.first).trim()
            // Negative amounts / taxes / discounts must not silently become positive merchandise.
            if (negativePrefix(name) || name.isBlank() || name.none(Char::isLetter) || name.contains('%') ||
                Regex("\\b(?:UST|MWST|EUR|SUMME|RABATT|PFAND)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
                skipped++; return@mapNotNull null
            }
            val amount = runCatching { Money.parse(match.groupValues[1]) }.getOrNull()
            ReceiptLineSuggestion(line, name, amount, quantity.find(line)?.value)
        }.take(200)
        if (skipped > 0 || lines.any { excluded.containsMatchIn(it) }) warnings += "Zahlungs-/Steuer-/Pfand-/Rabattzeilen werden nicht als normale Ware interpretiert; Gesamtbetrag und fehlende Positionen prüfen."
        if (suggestions.isEmpty()) warnings += "Keine sicheren Warenzeilen erkannt; Positionen manuell ergänzen."
        if (suggestions.any { it.quantityText != null }) warnings += "Gedruckte Mengen sind Rohangaben, keine bestätigte nutzbare Lagermenge."
        val merchant = lines.take(3).firstOrNull { line ->
            line.length in 3..100 && line.any(Char::isLetter) && line.none(Char::isDigit) &&
                !date.containsMatchIn(line) && !totalLabel.containsMatchIn(line) && !excluded.containsMatchIn(line) &&
                !line.contains(Regex("(?i)kassenbon|beleg|testbon|willkommen|vielen dank|rechnung"))
        }
        return ReceiptSuggestion(merchant, dates.singleOrNull(), total, suggestions, warnings)
    }
    private fun negativePrefix(text: String) = text.trimEnd().lastOrNull() in setOf('-', '−', '–')
}
