package de.ernaehrung.demo.data.fitness

import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeParseException

internal enum class BodyMetric(val label: String, val unit: String) {
    WEIGHT("Gewicht", "kg"), BODY_FAT("Körperfettanteil", "%"),
    MUSCLE_MASS("Muskelmasse", "kg"), MUSCLE_PERCENT("Muskelanteil", "%"),
    WAIST("Taillenumfang", "cm"), OTHER("Weiteres Körpermaß", "")
}
internal enum class BodyMethod(val label: String) {
    SCALE("Waage"), BIA("Bioimpedanz · Geräteschätzung"), TAPE("Maßband"),
    OTHER("Andere Methode"), UNKNOWN("Messprinzip unbekannt")
}
internal enum class BodyQuality(val label: String) {
    UNKNOWN("Qualität unbekannt"), MEASURED("Manuelle Messung"),
    DEVICE_ESTIMATE("Geräteschätzung"), USER_ESTIMATE("Eigene Schätzung")
}

/** Original input is retained alongside exact decimal/temporal normalization.
 * One metric per observation prevents inventing same-time sibling values.
 */
internal data class BodyMeasurementInput(
    val metric: BodyMetric, val rawValue: String?, val localDate: String,
    val localTime: String? = null, val zoneId: String? = null, val explicitOffset: String? = null,
    val name: String? = null, val customUnit: String? = null,
    val device: String? = null, val model: String? = null,
    val method: BodyMethod = BodyMethod.UNKNOWN, val methodDetail: String? = null,
    val conditions: String? = null, val quality: BodyQuality = BodyQuality.UNKNOWN,
    val excludedFromTrend: Boolean = false, val correctionReason: String? = null
)

internal data class NormalizedBodyMeasurement(val input: BodyMeasurementInput, val value: String?,
    val unit: String, val utc: String?, val offsetSeconds: Int?, val timePrecision: String,
    val quality: BodyQuality)

internal object BodyMeasurementRules {
    private fun optional(value: String?, max: Int = 1000) {
        require(value == null || (value.isNotBlank() && value.length <= max)) { "Leere Zusatzangaben weglassen; Text ist zu lang." }
    }
    fun normalize(input: BodyMeasurementInput, now: Instant): NormalizedBodyMeasurement {
        listOf(input.device, input.model, input.methodDetail, input.conditions, input.correctionReason,
            input.name).forEach { optional(it) }
        optional(input.customUnit, 40)
        val unit = if (input.metric == BodyMetric.OTHER) {
            require(!input.name.isNullOrBlank() && !input.customUnit.isNullOrBlank()) { "Weiteres Maß eindeutig benennen und Einheit angeben." }
            input.customUnit
        } else {
            require(input.name == null && input.customUnit == null) { "Für dieses Körpermaß ist die Einheit festgelegt." }
            input.metric.unit
        }
        if (input.metric in setOf(BodyMetric.MUSCLE_MASS, BodyMetric.MUSCLE_PERCENT))
            require(!input.methodDetail.isNullOrBlank()) { "Muskelmaß laut Gerät eindeutig definieren, z.B. Skelettmuskelmasse. Unklare Definition als solche angeben." }
        if (input.method == BodyMethod.OTHER) require(!input.methodDetail.isNullOrBlank()) { "Andere Messmethode beschreiben." }
        val value = input.rawValue?.let { raw ->
            require(raw.length <= 80 && raw.trim().matches(Regex("[+]?[0-9]+([.,][0-9]+)?"))) { "Wert als Dezimalzahl eingeben oder ausdrücklich unbekannt wählen." }
            val decimal = BigDecimal(raw.trim().replace(',', '.'))
            require(decimal.signum() >= 0) { "Körperwert darf nicht negativ sein." }
            if (unit == "%") require(decimal <= BigDecimal(100)) { "Prozentwert muss zwischen 0 und 100 liegen." }
            decimal.stripTrailingZeros().toPlainString()
        }
        try {
            val date = LocalDate.parse(input.localDate)
            require(date.toString() == input.localDate) { "Datum als JJJJ-MM-TT eingeben." }
            val zone = input.zoneId?.let { require(it.length <= 100); ZoneId.of(it) }
            val time = input.localTime?.let {
                require(it.matches(Regex("[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]{1,9})?)?"))) { "Uhrzeit als HH:MM oder HH:MM:SS eingeben." }
                LocalTime.parse(it)
            }
            val chosenOffset = input.explicitOffset?.let { ZoneOffset.of(it) }
            require(time != null || chosenOffset == null) { "Ohne Uhrzeit ist kein UTC-Versatz bestimmbar." }
            val local = time?.let { LocalDateTime.of(date, it) }
            val offsets = if (local != null && zone != null) zone.rules.getValidOffsets(local) else emptyList()
            if (local != null && zone != null) {
                require(offsets.isNotEmpty()) { "Diese Ortszeit existiert wegen der Zeitumstellung nicht." }
                require(chosenOffset == null || chosenOffset in offsets) { "UTC-Versatz passt nicht zu Ortszeit und Zeitzone." }
                require(offsets.size == 1 || chosenOffset != null) { "Diese Uhrzeit kommt zweimal vor. UTC-Versatz ausdrücklich wählen." }
            }
            val offset = chosenOffset ?: offsets.singleOrNull()
            val utc = if (local != null && offset != null) local.toInstant(offset) else null
            require(utc == null || utc <= now) { "Messzeitpunkt liegt in der Zukunft." }
            if (zone != null) require(date <= now.atZone(zone).toLocalDate()) { "Messdatum liegt in der Zukunft." }
            val precision = when {
                input.localTime == null -> "DATE_ONLY"
                '.' in input.localTime -> "FRACTION_${input.localTime.substringAfter('.').length}"
                input.localTime.length == 5 -> "MINUTE"
                else -> "SECOND"
            }
            return NormalizedBodyMeasurement(input, value, unit, utc?.toString(), offset?.totalSeconds, precision,
                if (input.method == BodyMethod.BIA) BodyQuality.DEVICE_ESTIMATE else input.quality)
        } catch (_: DateTimeParseException) { throw IllegalArgumentException("Messdatum, Uhrzeit oder UTC-Versatz ist ungültig.") }
        catch (_: DateTimeException) { throw IllegalArgumentException("Zeitzone ist ungültig. Beispiel: Europe/Berlin.") }
    }
}
