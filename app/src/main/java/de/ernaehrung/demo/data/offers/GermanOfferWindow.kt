package de.ernaehrung.demo.data.offers

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The weekday and both dates must agree with the merchant's printed weekly interval. */
internal object GermanOfferWindow {
    private const val DAY = "([\\p{L}]+), ([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{4}|[0-9]{2})"
    private val window = Regex("Gültig von $DAY (?:bis|[-–]) $DAY", RegexOption.IGNORE_CASE)

    fun read(text: String): ClosedRange<LocalDate>? {
        val fields = window.matchEntire(text)?.groupValues ?: return null
        val start = date(fields, 1) ?: return null
        val end = date(fields, 5) ?: return null
        return (start..end).takeIf { ChronoUnit.DAYS.between(start, end) in 0..6 }
    }

    private fun date(fields: List<String>, first: Int): LocalDate? {
        val year = fields[first + 3]
        return runCatching {
            LocalDate.of(if (year.length == 2) 2000 + year.toInt() else year.toInt(),
                fields[first + 2].toInt(), fields[first + 1].toInt())
        }.getOrNull()?.takeIf {
            it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMANY).equals(fields[first], ignoreCase = true)
        }
    }
}
