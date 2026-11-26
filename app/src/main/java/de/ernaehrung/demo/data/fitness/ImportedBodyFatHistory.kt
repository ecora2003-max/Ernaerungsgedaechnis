package de.ernaehrung.demo.data.fitness

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal data class ImportedBodyFatSample(val original: HealthExtraStored, val sourceTime: OffsetDateTime?) {
    val id = with(original.record) { "${sourcePackage.length}:$sourcePackage$externalId" }
    val value get() = original.record.scalarBits?.let(Double::fromBits)

    fun plotPoint(): BodyRawObservation? = sourceTime?.let { at ->
        val record = original.record
        BodyRawObservation(id, BodyMeasurementInput(BodyMetric.BODY_FAT, value?.toString(), at.toLocalDate().toString(),
            localTime = at.toLocalTime().toString(), explicitOffset = at.offset.id, device = record.manufacturer,
            model = record.model, methodDetail = record.sourcePackage))
    }
}

internal data class ImportedBodyFatWindow(val series: BodyRawSeries, val start: LocalDate?, val end: LocalDate?,
    val samples: List<ImportedBodyFatSample>, val missingSourceDay: List<ImportedBodyFatSample>)

/** Projects the local source copy into the same raw calendar as manual body
 * history. The original offset is required to assign a day. */
internal fun selectImportedBodyFat(records: List<HealthExtraStored>, period: BodyHistoryPeriod,
    referenceDate: LocalDate): ImportedBodyFatWindow {
    val samples = records.filter { it.visible && it.record.kind == HealthExtraKind.BODY_FAT }.map { row ->
        val time = row.record.startOffsetSeconds?.let { seconds ->
            Instant.ofEpochMilli(row.record.startAt).atOffset(ZoneOffset.ofTotalSeconds(seconds))
        }
        ImportedBodyFatSample(row, time)
    }
    val window = BodyRawHistory.select(samples.mapNotNull(ImportedBodyFatSample::plotPoint), period, referenceDate)
    val label = BodyRawMeasure(BodyMetric.BODY_FAT, "Health Connect", HealthExtraKind.BODY_FAT.unit)
    val series = window.series.singleOrNull()?.copy(measure = label)
        ?: BodyRawSeries(label, emptyList(), null)
    val byId = samples.associateBy(ImportedBodyFatSample::id)
    return ImportedBodyFatWindow(series, window.start, window.end,
        series.points.map { byId.getValue(it.id) }, samples.filter { it.sourceTime == null })
}
