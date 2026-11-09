package de.ernaehrung.demo.data.offers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** The existing pinned ALDI Nord/Kaufland parsers accept embedded HTML too.
 * Extract only supplied JSON literals; no script evaluation, URL/resource load,
 * cookie handling, scope claim or new permission. Existing JSON decoders still
 * validate source shape, fields, quantities/dates and store availability. */
internal object EmbeddedPublishedOfferJson {
    private fun invalid(): Nothing = throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
    private fun document(html: String): Document {
        if (html.length > 1_048_576) invalid()
        return Jsoup.parse(html).also { if (it.getAllElements().size > 50_000) invalid() }
    }
    fun aldiNord(html: String): String {
        val scripts = document(html).select("script[id=__NEXT_DATA__]")
        if (scripts.size != 1) invalid()
        return scripts.single().data().takeIf { it.isNotBlank() } ?: invalid()
    }
    fun kaufland(html: String): String {
        document(html)
        val property = Regex("(?:^|[,{])\\s*\"offerData\"\\s*:\\s*(\\{)")
        var literal: String? = null
        // The pinned reader finds the JSON literal in the supplied page, not
        // only script tags. The same published object may be in an attribute.
        for (match in property.findAll(html)) {
            if (literal != null) invalid()
            literal = objectLiteral(html, requireNotNull(match.groups[1]).range.first)
        }
        return "{\"offerData\":" + (literal ?: invalid()) + "}"
    }
    /** Match the object's end while retaining all escaped strings/braces. The
     * bounded JSON reader, rather than JavaScript, validates its contents next. */
    private fun objectLiteral(text: String, start: Int): String {
        var depth = 0; var string = false; var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (string) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> string = false
                }
            } else when (c) {
                '"' -> string = true
                '{' -> { depth++; if (depth > 24) invalid() }
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        invalid()
    }
}
