package de.ernaehrung.demo.data.fitness

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class BodyPlotSource(val method: BodyMethod, val methodDetail: String?, val device: String?, val model: String?,
    val conditions: String?, val quality: BodyQuality)
internal data class BodyPlotPoint(val id: String, val date: LocalDate, val rawValue: String,
    val x: Float, val yFromBottom: Float, val excluded: Boolean, val sourceIndex: Int)
internal data class BodyPlot(val first: LocalDate?, val last: LocalDate?, val minimum: BigDecimal?, val maximum: BigDecimal?,
    val points: List<BodyPlotPoint>, val sources: List<BodyPlotSource>, val unknownCount: Int, val unplottableCount: Int)

/** Rendering coordinates only: no averaging, connecting, interpolation, statistical
 * exclusion or inferred timestamp. Raw values/IDs remain in every plotted point.
 */
internal object BodyPlotProjection {
    const val VERSION = "ORIGINAL_LOCAL_DATE_SCATTER_V1"
    fun project(series: BodyRawSeries, start: LocalDate?, end: LocalDate?): BodyPlot {
        val dates = series.points.map { LocalDate.parse(it.input.localDate) }
        val first = start ?: dates.minOrNull()
        val last = end ?: dates.maxOrNull()
        val known = series.points.mapNotNull { point ->
            val raw = point.input.rawValue ?: return@mapNotNull null
            raw.trim().replace(',', '.').toBigDecimalOrNull()?.let { point to it }
        }
        val minimum = known.minOfOrNull { it.second }; val maximum = known.maxOfOrNull { it.second }
        val sources = known.map { (point, _) -> point.input.let {
            BodyPlotSource(it.method, it.methodDetail, it.device, it.model, it.conditions, it.quality)
        } }.distinct().sortedWith(compareBy<BodyPlotSource> { it.method.ordinal }.thenBy { it.methodDetail }
            .thenBy { it.device }.thenBy { it.model }.thenBy { it.conditions }.thenBy { it.quality.ordinal })
        val range = if (minimum != null && maximum != null) maximum - minimum else null
        val sourceIndices = sources.withIndex().associate { it.value to it.index }
        val days = if (first != null && last != null) ChronoUnit.DAYS.between(first, last) else 0L
        require(days >= 0)
        val points = known.map { (point, value) ->
            val input = point.input
            val date = LocalDate.parse(input.localDate)
            require(first != null && last != null && date >= first && date <= last)
            val x = if (days == 0L) 0.5f else (ChronoUnit.DAYS.between(first, date).toDouble() / days).toFloat()
            val y = if (range == null || range.signum() == 0) 0.5f
                else value.subtract(minimum).divide(range, MathContext.DECIMAL128).toFloat()
            BodyPlotPoint(point.id, date, requireNotNull(input.rawValue), x, y, input.excludedFromTrend,
                sourceIndices.getValue(BodyPlotSource(input.method, input.methodDetail, input.device, input.model, input.conditions, input.quality)))
        }
        val unknown = series.points.count { it.input.rawValue == null }
        return BodyPlot(first, last, minimum, maximum, points, sources, unknown, series.points.size - unknown - points.size)
    }
}
