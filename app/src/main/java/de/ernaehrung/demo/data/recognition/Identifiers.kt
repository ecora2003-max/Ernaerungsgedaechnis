package de.ernaehrung.demo.data.recognition

import java.text.Normalizer
import java.util.Locale

/** Preserve leading zeroes; UPC/EAN representations share a canonical GTIN-14 lookup key. */
class Gtin private constructor(val printed: String, val canonical: String) {
    companion object {
        fun parse(text: String): Gtin {
            val code = text.trim()
            require(code.length in setOf(8, 12, 13, 14) && code.all { it in '0'..'9' }) { "EAN/GTIN mit 8, 12, 13 oder 14 Ziffern eingeben." }
            require(code.any { it != '0' }) { "Leerer Nullcode ist kein Produktbarcode." }
            val sum = code.dropLast(1).reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
            require((10 - sum % 10) % 10 == code.last() - '0') { "Barcode-Prüfziffer stimmt nicht. Ziffern erneut prüfen." }
            return Gtin(code, code.padStart(14, '0'))
        }
    }
}

object AliasText {
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun merchant(text: String?): String? = text?.let(::normalize)?.takeIf(String::isNotEmpty)
}
