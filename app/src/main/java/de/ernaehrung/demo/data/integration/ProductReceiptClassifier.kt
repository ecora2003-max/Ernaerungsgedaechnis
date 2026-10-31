package de.ernaehrung.demo.data.integration

import java.text.Normalizer
import java.util.Locale

/**
 * Read-only Kotlin port of EG-Produktdaten's classify_receipt_line (v1.0.0-draft).
 * Reference: EG-Produktdaten@1f85b17ad5a112e8aa9cc91c13b0dff4064f5f37.
 * This proposes a receipt-line kind and a parsed amount. It NEVER confirms FOOD,
 * a payable amount, a product version, or a stock/purchase/price-history event.
 */
object ProductReceiptClassifier {
    enum class Kind { FOOD, NON_FOOD, DEPOSIT, DISCOUNT, RETURN, UNKNOWN }
    data class Proposal(val rawText: String, val kind: Kind, val amountCents: Long?,
                        val warnings: List<String>, val confirmed: Boolean)
    private val returns = Regex("""\b(retoure|rückgabe|rueckgabe|storno|gutschrift)\b""")
    private val deposit = Regex("""\b(pfand|leergut)\b""")
    private val discount = Regex("""\b(rabatt|coupon|gutschein|nachlass)\b""")
    private val nonFood = Regex("""\b(tasche|waschmittel|spülmittel|spuelmittel)\b""")
    private val payment = Regex("""\b(rückgeld|rueckgeld|gegeben|barzahlung|kartenzahlung|zwischensumme|summe|gesamt|mwst|ust)\b""")
    private val price = Regex("""(?<![0-9.,])([+\-−–]?\d+[,.]\d{2})\s*(?:€|EUR)?\s*(?:[A-D])?\s*$""", RegexOption.IGNORE_CASE)
    private val damaged = Regex("""\b\d{2,}[A-Z]\s*$""")
    private const val MAX_MONEY_CENTS = 1_000_000_000_000L

    fun propose(rawText: String, providedKind: Kind = Kind.UNKNOWN, amountCents: Long? = null,
                confirmed: Boolean = false): Proposal {
        require(rawText.length <= 4_096) { "Receipt line exceeds host size limit" }
        amountCents?.let { require(it in -MAX_MONEY_CENTS..MAX_MONEY_CENTS) { "Receipt amount outside host range" } }
        val head = Normalizer.normalize(rawText, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace("ß", "ss").trim().replace(Regex("""\s+"""), " ")
        val warnings = mutableListOf<String>()
        var kind = providedKind
        if (kind == Kind.UNKNOWN) {
            kind = when {
                returns.containsMatchIn(head) -> Kind.RETURN
                deposit.containsMatchIn(head) -> Kind.DEPOSIT
                discount.containsMatchIn(head) -> Kind.DISCOUNT
                nonFood.containsMatchIn(head) -> Kind.NON_FOOD
                payment.containsMatchIn(head) -> {
                    warnings += "payment_or_total_row"; Kind.UNKNOWN
                }
                else -> Kind.UNKNOWN
            }
        }

        var amount = amountCents
        if (amount == null) {
            val result = price.find(rawText)
            if (result != null) {
                val match = checkNotNull(result.groups[1])
                var token = match.value.replace('−', '-').replace('–', '-')
                if (token.first().isDigit() &&
                    rawText.substring(0, match.range.first).trimEnd().endsWithAny("-", "−", "–")) token = "-$token"
                amount = token.replace(",", "").replace(".", "").toLongOrNull()
                    ?.takeIf { it in -MAX_MONEY_CENTS..MAX_MONEY_CENTS }
                if (amount == null) warnings += "amount_outside_host_range"
            } else if (damaged.containsMatchIn(rawText)) {
                warnings += "malformed_ocr_price"
            }
        }
        if (amount != null && amount < 0 && kind != Kind.RETURN && kind != Kind.DISCOUNT) {
            warnings += "negative_non_return_amount"
            kind = Kind.UNKNOWN
        }
        if (!confirmed) warnings += "unconfirmed_ocr"
        return Proposal(rawText, kind, amount, warnings, confirmed)
    }

    private fun String.endsWithAny(vararg values: String): Boolean = values.any { endsWith(it) }
}
