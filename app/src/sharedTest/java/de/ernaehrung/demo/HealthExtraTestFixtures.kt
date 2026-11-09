package de.ernaehrung.demo

import de.ernaehrung.demo.data.fitness.*

/** Synthetic source; never physical Health Connect, Samsung, or watch evidence. */
internal class SyntheticExtraSource : HealthExtraSource {
    var available = FitnessHealthSource.Availability.AVAILABLE
    var history = true
    var grants = HealthExtraKind.entries.map { "android.permission.health.${it.permission}" }.toSet() + HealthExtraKernel.HISTORY_PERMISSION
    var reads = 0
    var lastQuery: HealthExtraQuery? = null
    var onRead: suspend (HealthExtraQuery) -> Unit = {}
    var response: (HealthExtraQuery) -> HealthExtraSnapshot = { q -> syntheticExtraSnapshot(q) }
    override fun availability() = available
    override fun historyAvailable() = history
    override fun requiredPermissions(query: HealthExtraQuery) = HealthExtraKernel.permissions(query)
    override suspend fun grantedPermissions() = grants
    override suspend fun readSnapshot(query: HealthExtraQuery): HealthExtraSnapshot {
        reads++; lastQuery = query; onRead(query); return response(query)
    }
}
internal fun syntheticExtraRecord(q: HealthExtraQuery, kind: HealthExtraKind): HealthExtraRecord {
    val point = q.windowEnd - 20_000L
    val source = when (kind) {
        HealthExtraKind.BODY_FAT, HealthExtraKind.BASAL_RATE -> "test.synthetic.scale"
        HealthExtraKind.ACTIVE_ENERGY -> "test.synthetic.phone"
        else -> "test.synthetic.wearable"
    }
    return HealthExtraRecord(source, kind, "synthetic-${kind.name}", point,
        if (kind.shape == HealthExtraKind.Shape.INSTANT) null else q.windowEnd - 1,
        0, if (kind.shape == HealthExtraKind.Shape.INSTANT) null else 0,
        q.requestedAt - 1, "test-client-${kind.name}", 1, null, null, null, 0,
        if (kind.shape in setOf(HealthExtraKind.Shape.INSTANT, HealthExtraKind.Shape.INTERVAL)) 0.0.toRawBits() else null,
        when (kind) {
            HealthExtraKind.HEART_RATE -> listOf(HealthExtraSample(point + 1, bpm = 71), HealthExtraSample(point + 2, bpm = 89))
            HealthExtraKind.SPEED -> listOf(HealthExtraSample(point + 1, doubleBits = 0.0.toRawBits()),
                HealthExtraSample(point + 2, doubleBits = 1.125.toRawBits()))
            else -> emptyList()
        })
}
internal fun syntheticExtraSnapshot(q: HealthExtraQuery) = HealthExtraSnapshot(query = q, capturedAt = q.requestedAt,
    records = q.selection.kinds.filter { it.shape != HealthExtraKind.Shape.AGGREGATE }.map { syntheticExtraRecord(q, it) },
    stepDay = if (HealthExtraKind.ALL_SOURCE_STEPS in q.selection.kinds) HealthExtraStepDay(q.lastDay, q.zone,
        q.lastDayStart, q.windowEnd, 0L, setOf("test.synthetic.phone", "test.synthetic.wearable")) else null)
