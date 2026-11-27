package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal enum class EnergyCoveragePeriod(val days: Int) {
    DAYS_14(14), DAYS_28(28), DAYS_42(42)
}

internal data class FitnessEnergyDay(
    val date: LocalDate,
    val diaryStatus: DayStatus,
    val foodEnergy: NutrientResult,
    val weightMeasurements: Int,
    val activitySources: Set<String>
) {
    val completeIntake get() =
        diaryStatus == DayStatus.COMPLETE && foodEnergy.complete && foodEnergy.knownAmount != null
}

/** The recorded input coverage, read from a single local database snapshot. */
internal class FitnessEnergyCoverageReader(private val db: FoodDatabase, private val nutrition: NutritionReader) {
    fun watch(end: LocalDate, period: EnergyCoveragePeriod) =
        nutrition.invalidations("body_measurement_versions", "health_extra_versions")
            .map { read(end, period) }
            .distinctUntilChanged()

    private suspend fun read(end: LocalDate, period: EnergyCoveragePeriod): List<FitnessEnergyDay> = db.withTransaction {
        val start = end.minusDays(period.days.toLong() - 1L)
        val weightCounts = db.bodyDao().recordedWeightDays(start.toString(), end.toString())
            .associate { it.localDate to it.count }
        val intervals = db.healthExtraDao().activeEnergyIntervals(
            AppCalendar.startOfDayMillis(start), AppCalendar.startOfDayMillis(end.plusDays(1)))
        val dates = List(period.days) { end.minusDays(it.toLong()) }
        nutrition.days(dates.map(LocalDate::toString)).zip(dates).map { (record, date) ->
            val startsAt = AppCalendar.startOfDayMillis(date)
            val endsAt = AppCalendar.startOfDayMillis(date.plusDays(1))
            val sources = intervals.asSequence().filter { it.startAt < endsAt && it.endAt > startsAt }
                .map { it.sourcePackage }.toSortedSet()
            FitnessEnergyDay(date, record.status, record.totals[Nutrient.ENERGY],
                weightCounts[record.date] ?: 0, sources)
        }
    }
}
