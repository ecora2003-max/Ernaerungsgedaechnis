package de.ernaehrung.demo.data.fitness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** HEALTH_EXTRA_WINDOW_V1. Raw source observations, never nutrition/stock,
 * single-workout attribution, sums of sources, calibration or TDEE estimates. */
internal object HealthExtraKernel {
    const val HISTORY_PERMISSION = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    const val MAX_SAMPLES = 200_000
    val WINDOWS = setOf(7, 14, 28, 42, 90)
    private val baseHistory = Duration.ofDays(30).toMillis()
    fun kindsText(kinds: Set<HealthExtraKind>) = kinds.map { it.name }.sorted().joinToString(",")
    fun parseKinds(text: String): Set<HealthExtraKind> = text.split(',').filter { it.isNotEmpty() }
        .map(HealthExtraKind::valueOf).toSet().also { require(kindsText(it) == text) { "HEALTH_EXTRA_SCOPE_INVALID" } }

    fun plan(selection: HealthExtraSelection, at: Long, zoneId: String): HealthExtraQuery {
        require(at >= 0 && selection.kinds.isNotEmpty() && selection.windowDays in WINDOWS) { "HEALTH_EXTRA_SCOPE_INVALID" }
        val zone = ZoneId.of(zoneId)
        val today = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val endDay = selection.endDay?.let { LocalDate.parse(it).also { d -> require(d.toString() == it) } } ?: today
        require(!endDay.isAfter(today)) { "HEALTH_EXTRA_SCOPE_INVALID" }
        val start = endDay.minusDays(selection.windowDays - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = if (endDay == today) at else endDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayStart = endDay.atStartOfDay(zone).toInstant().toEpochMilli()
        require(start >= 0 && start < end && end <= at) { "HEALTH_EXTRA_SCOPE_INVALID" }
        val oldestRead = if (selection.kinds.all { it == HealthExtraKind.ALL_SOURCE_STEPS }) dayStart else start
        val history = oldestRead < (at - baseHistory).coerceAtLeast(0)
        require(!history || selection.historyOptIn) { "HEALTH_EXTRA_HISTORY_CONSENT_REQUIRED" }
        return HealthExtraQuery(selection.copy(kinds = java.util.Collections.unmodifiableSet(selection.kinds.toSet())), at, start, end,
            zone.id, endDay.toString(), dayStart, history)
    }

    fun permissions(query: HealthExtraQuery) = query.selection.kinds.map { "android.permission.health.${it.permission}" }.toSet() +
        if (query.needsHistory) setOf(HISTORY_PERMISSION) else emptySet()
    private fun key(r: HealthExtraRecord) = Triple(r.sourcePackage, r.kind, r.externalId)
    fun recordFingerprint(r: HealthExtraRecord) = FitnessImportKernel.fingerprint("HEALTH_EXTRA_RECORD_V1",
        r.sourcePackage, r.kind.name, r.kind.unit, r.externalId, r.startAt, r.endAt, r.startOffsetSeconds, r.endOffsetSeconds,
        r.sourceModifiedAt, r.clientRecordId, r.clientRecordVersion, r.manufacturer, r.model,
        r.deviceType, r.recordingMethod, r.scalarBits, r.samples.map { listOf(it.at, it.doubleBits, it.bpm) })
    fun fingerprint(s: HealthExtraSnapshot) = FitnessImportKernel.fingerprint("HEALTH_EXTRA_WINDOW_V1", s.formatVersion,
        kindsText(s.query.selection.kinds), s.query.selection.windowDays, s.query.selection.endDay,
        s.query.selection.historyOptIn, s.query.requestedAt, s.query.windowStart, s.query.windowEnd, s.query.zone,
        s.query.lastDay, s.query.lastDayStart, s.query.needsHistory, s.capturedAt,
        s.records.sortedWith(compareBy({ it.sourcePackage }, { it.kind.name }, { it.externalId })).map(::recordFingerprint),
        s.stepDay?.let { listOf(it.day, it.zone, it.startAt, it.endAt, it.steps, it.sourcePackages.sorted()) })

    private fun number(bits: Long?): Double = requireNotNull(bits) { "HEALTH_EXTRA_VALUE_INVALID" }.let {
        Double.fromBits(it).also { n -> require(n.isFinite() && n >= 0.0) { "HEALTH_EXTRA_VALUE_INVALID" } }
    }
    fun validate(snapshot: HealthExtraSnapshot, expected: HealthExtraQuery, now: Long) {
        require(snapshot.formatVersion == 1 && snapshot.query == expected &&
            expected == plan(expected.selection, expected.requestedAt, expected.zone)) { "HEALTH_EXTRA_SCOPE_INVALID" }
        require(snapshot.capturedAt in expected.requestedAt..now) { "HEALTH_EXTRA_CAPTURE_INVALID" }
        val rows = snapshot.records
        require(rows.size <= FitnessHealthPolicy.MAX_RECORDS && rows.sumOf { it.samples.size.toLong() } <= MAX_SAMPLES) { "HEALTH_IMPORT_LIMIT" }
        require(rows.map(::key).toSet().size == rows.size) { "HEALTH_DUPLICATE_SOURCE_ID" }
        rows.forEach { r ->
            require(r.kind in expected.selection.kinds && r.kind.shape != HealthExtraKind.Shape.AGGREGATE &&
                r.sourcePackage.isNotBlank() && r.externalId.isNotBlank()) { "HEALTH_EXTRA_SOURCE_INVALID" }
            require(r.sourceModifiedAt in 0L..snapshot.capturedAt && r.clientRecordVersion >= 0) { "HEALTH_SOURCE_VERSION_INVALID" }
            require(r.recordingMethod >= 0 && (r.deviceType == null || r.deviceType >= 0) &&
                listOf(r.startOffsetSeconds, r.endOffsetSeconds).all { it == null || it in -64_800..64_800 }) { "HEALTH_METADATA_INVALID" }
            if (r.kind.shape == HealthExtraKind.Shape.INSTANT) {
                require(r.endAt == null && r.endOffsetSeconds == null && r.startAt >= expected.windowStart &&
                    r.startAt < expected.windowEnd) { "HEALTH_RECORD_OUTSIDE_WINDOW" }
            } else require(r.startAt >= 0 && r.endAt != null && r.endAt > r.startAt &&
                r.startAt < expected.windowEnd && r.endAt > expected.windowStart) { "HEALTH_RECORD_INTERVAL_INVALID" }
            when (r.kind.shape) {
                HealthExtraKind.Shape.INTEGER_SERIES -> {
                    require(r.scalarBits == null) { "HEALTH_EXTRA_VALUE_INVALID" }
                    r.samples.forEach { require(it.doubleBits == null && it.bpm in 1L..300L) { "HEALTH_EXTRA_VALUE_INVALID" } }
                }
                HealthExtraKind.Shape.DOUBLE_SERIES -> {
                    require(r.scalarBits == null) { "HEALTH_EXTRA_VALUE_INVALID" }
                    r.samples.forEach { require(it.bpm == null && number(it.doubleBits) <= 1_000_000.0) { "HEALTH_EXTRA_VALUE_INVALID" } }
                }
                else -> {
                    require(r.samples.isEmpty()) { "HEALTH_EXTRA_VALUE_INVALID" }
                    val value = number(r.scalarBits)
                    val max = when (r.kind) {
                        HealthExtraKind.BODY_FAT -> 100.0
                        // SDK1.1.0 Power: 10,000 kcal/day maximum in canonical Watts.
                        HealthExtraKind.BASAL_RATE -> 10_000.0 * 0.0484259259
                        else -> 1_000_000.0
                    }
                    require(value <= max) { "HEALTH_EXTRA_VALUE_INVALID" }
                }
            }
            r.samples.forEach { require(it.at >= r.startAt && it.at <= requireNotNull(r.endAt)) { "HEALTH_EXTRA_SAMPLE_TIME_INVALID" } }
        }
        if (HealthExtraKind.ALL_SOURCE_STEPS in expected.selection.kinds) {
            val day = requireNotNull(snapshot.stepDay) { "HEALTH_EXTRA_STEP_DAY_INVALID" }
            require(day.day == expected.lastDay && day.zone == expected.zone && day.startAt == expected.lastDayStart &&
                day.endAt == expected.windowEnd && day.endAt >= day.startAt &&
                (day.steps == null || day.steps >= 0) && day.sourcePackages.all { it.isNotBlank() } &&
                (day.endAt != day.startAt || (day.steps == null && day.sourcePackages.isEmpty()))) { "HEALTH_EXTRA_STEP_DAY_INVALID" }
        } else require(snapshot.stepDay == null) { "HEALTH_EXTRA_STEP_DAY_INVALID" }
    }

    fun changes(snapshot: HealthExtraSnapshot, query: HealthExtraQuery, old: List<HealthExtraStored>, now: Long): List<HealthExtraChange> {
        validate(snapshot, query, now)
        require(old.map { key(it.record) }.toSet().size == old.size && old.all {
            it.revision > 0 && it.fingerprint == recordFingerprint(it.record)
        }) { "HEALTH_CACHE_INVALID" }
        val previous = old.associateBy { key(it.record) }
        val incoming = snapshot.records.associateBy(::key)
        val result = mutableListOf<HealthExtraChange>()
        fun next(row: HealthExtraStored?) = if (row == null) 1L else {
            require(row.revision < Long.MAX_VALUE) { "HEALTH_REVISION_LIMIT" }; row.revision + 1
        }
        incoming.forEach { (id, r) ->
            val before = previous[id]; val fp = recordFingerprint(r)
            val visible = r.endAt == null || r.endAt <= snapshot.capturedAt
            val reason = if (visible) FitnessRecordVisibility.SOURCE_READ else FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE
            if (before == null || before.fingerprint != fp || before.visible != visible || before.reason != reason)
                result += HealthExtraChange(r, next(before), visible, reason, fp)
        }
        previous.forEach { (id, row) ->
            val r = row.record
            val covered = if (r.endAt == null) r.startAt >= query.windowStart && r.startAt < query.windowEnd
                else r.startAt >= query.windowStart && r.endAt <= query.windowEnd
            if (r.kind in query.selection.kinds && covered && id !in incoming &&
                row.reason != FitnessRecordVisibility.NOT_VISIBLE_IN_WINDOW)
                result += HealthExtraChange(r, next(row), false, FitnessRecordVisibility.NOT_VISIBLE_IN_WINDOW, row.fingerprint)
        }
        return result.sortedWith(compareBy({ it.record.sourcePackage }, { it.record.kind.name }, { it.record.externalId }))
    }
}
