package de.ernaehrung.demo.data.fitness

import java.time.LocalDate

internal enum class BodyHistoryPeriod(val days: Int?, val label: String) {
    DAYS_7(7, "7 Tage"), DAYS_14(14, "14 Tage"), DAYS_28(28, "28 Tage"),
    DAYS_42(42, "42 Tage"), DAYS_90(90, "90 Tage"), ALL(null, "Alle")
}

internal data class BodyRawObservation(val id: String, val input: BodyMeasurementInput)
internal data class BodyRawMeasure(val metric: BodyMetric, val name: String?, val unit: String)
internal data class BodyRawSeries(val measure: BodyRawMeasure, val points: List<BodyRawObservation>,
    val daysWithoutKnownValue: List<LocalDate>?)
internal data class BodyRawWindow(val start: LocalDate?, val end: LocalDate?,
    val series: List<BodyRawSeries>, val outsideWindowCount: Int)

/** Read-only calendar selection of current immutable versions. Original local
 * measurement dates are never shifted into the display timezone. Date-only
 * observations remain date-only. No interpolation, outlier removal, averaging,
 * source merging or energy estimation takes place.
 */
internal object BodyRawHistory {
    const val METHOD_VERSION = "ORIGINAL_LOCAL_DATE_V1"

    fun select(current: List<BodyRawObservation>, period: BodyHistoryPeriod, referenceDate: LocalDate): BodyRawWindow {
        val start = period.days?.let { referenceDate.minusDays(it.toLong() - 1L) }
        val selected = current.filter {
            val date = LocalDate.parse(it.input.localDate)
            start == null || (date >= start && date <= referenceDate)
        }.sortedWith(compareByDescending<BodyRawObservation> { it.input.localDate }
            .thenByDescending { it.input.localTime }.thenBy { it.id })
        val dates = if (start == null) null else List(requireNotNull(period.days)) { start.plusDays(it.toLong()) }
        // Known measures remain visible even when the selected window is empty.
        // Different custom names/units never become a single measure.
        val measures = current.map { row -> BodyRawMeasure(row.input.metric, row.input.name,
            row.input.customUnit ?: row.input.metric.unit) }.distinct()
            .sortedWith(compareBy<BodyRawMeasure> { it.metric.ordinal }.thenBy { it.name }.thenBy { it.unit })
        val series = measures.map { measure ->
            val points = selected.filter { it.input.metric == measure.metric && it.input.name == measure.name &&
                (it.input.customUnit ?: it.input.metric.unit) == measure.unit }
            val knownDates = points.filter { it.input.rawValue != null }.map { LocalDate.parse(it.input.localDate) }.toSet()
            BodyRawSeries(measure, points, dates?.filterNot { it in knownDates })
        }
        return BodyRawWindow(start, referenceDate.takeIf { start != null }, series, current.size - selected.size)
    }
}
