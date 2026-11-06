package de.ernaehrung.demo.data.fitness

import java.math.BigDecimal
import java.time.*

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

/** Exact named columns of the published multi-value legacy observation. */
internal enum class LegacyBodyField(val metric: BodyMetric, val customName: String? = null, val unit: String) {
    WEIGHT(BodyMetric.WEIGHT, unit = "kg"), WAIST(BodyMetric.WAIST, unit = "cm"),
    BODY_FAT(BodyMetric.BODY_FAT, unit = "%"), MUSCLE_MASS(BodyMetric.MUSCLE_MASS, unit = "kg"),
    WATER(BodyMetric.OTHER, "Körperwasseranteil", "%")
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
        val time = FitnessTimestampRules.normalize(FitnessTimestampInput(input.localDate, input.localTime,
            input.zoneId, input.explicitOffset), now)
        return NormalizedBodyMeasurement(input, value, unit, time.utc, time.offsetSeconds, time.precision,
            if (input.method == BodyMethod.BIA) BodyQuality.DEVICE_ESTIMATE else input.quality)
    }
}
