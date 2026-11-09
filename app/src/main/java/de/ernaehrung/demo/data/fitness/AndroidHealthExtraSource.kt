package de.ernaehrung.demo.data.fitness

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.*
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/** Foreground-only, independently consented optional types from ALL origins.
 * Display labels never manufacture a package, Watch model or measurement method.
 * Active and total energy remain different SDK record kinds; BMR remains Watts.
 */
internal class AndroidHealthExtraSource(context: Context,
    private val now: () -> Long = System::currentTimeMillis) : HealthExtraSource {
    private val application = context.applicationContext
    private val basic = AndroidHealthConnectSource(application)
    override fun availability() = basic.availability()
    private fun client(): HealthConnectClient {
        check(availability() == FitnessHealthSource.Availability.AVAILABLE) { "HEALTH_SOURCE_UNAVAILABLE" }
        return HealthConnectClient.getOrCreate(application)
    }
    override fun historyAvailable(): Boolean = availability() == FitnessHealthSource.Availability.AVAILABLE &&
        client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    private fun type(kind: HealthExtraKind): KClass<out Record> = when (kind) {
        HealthExtraKind.HEART_RATE -> HeartRateRecord::class
        HealthExtraKind.DISTANCE -> DistanceRecord::class
        HealthExtraKind.SPEED -> SpeedRecord::class
        HealthExtraKind.ACTIVE_ENERGY -> ActiveCaloriesBurnedRecord::class
        HealthExtraKind.TOTAL_ENERGY -> TotalCaloriesBurnedRecord::class
        HealthExtraKind.BODY_FAT -> BodyFatRecord::class
        HealthExtraKind.BASAL_RATE -> BasalMetabolicRateRecord::class
        HealthExtraKind.ALL_SOURCE_STEPS -> StepsRecord::class
    }
    override fun requiredPermissions(query: HealthExtraQuery): Set<String> = query.selection.kinds.map {
        HealthPermission.getReadPermission(type(it))
    }.toSet() + if (query.needsHistory) setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY) else emptySet()
    override suspend fun grantedPermissions(): Set<String> = basic.grantedPermissions()

    override suspend fun readSnapshot(query: HealthExtraQuery): HealthExtraSnapshot {
        require(query == HealthExtraKernel.plan(query.selection, query.requestedAt, query.zone)) { "HEALTH_EXTRA_SCOPE_INVALID" }
        require(requiredPermissions(query) == HealthExtraKernel.permissions(query)) { "HEALTH_PERMISSION_SCOPE" }
        if (query.needsHistory) check(historyAvailable()) { "HEALTH_EXTRA_HISTORY_UNAVAILABLE" }
        val client = FitnessImportDiagnostics.read(FitnessReadStage.PROVIDER) { client() }
        suspend fun permission() {
            val granted = FitnessImportDiagnostics.read(FitnessReadStage.PERMISSIONS) { client.permissionController.getGrantedPermissions() }
            if (!granted.containsAll(requiredPermissions(query))) throw FitnessPermissionMissing()
        }
        permission()
        val types = query.selection.kinds.map(::type).toSet()
        val barrier = FitnessImportDiagnostics.read(FitnessReadStage.CHANGE_TOKEN) {
            client.getChangesToken(ChangesTokenRequest(types))
        }
        val records = mutableListOf<HealthExtraRecord>()
        var samples = 0L
        for (kind in query.selection.kinds.sortedBy { it.name }) {
            if (kind == HealthExtraKind.ALL_SOURCE_STEPS) continue
            var page: String? = null
            val pages = mutableSetOf<String>()
            do {
                permission()
                @Suppress("UNCHECKED_CAST")
                val response = FitnessImportDiagnostics.read(FitnessReadStage.EXTRA_RECORDS) {
                    client.readRecords(ReadRecordsRequest(type(kind) as KClass<Record>,
                        TimeRangeFilter.between(Instant.ofEpochMilli(query.windowStart), Instant.ofEpochMilli(query.windowEnd)),
                        // Empty means all origins, only under the NEW explicit optional-scope consent.
                        dataOriginFilter = emptySet(), pageSize = 1000, pageToken = page))
                }
                for (r in response.records) {
                    val row = FitnessImportDiagnostics.read(FitnessReadStage.EXTRA_MAPPING) { map(kind, r) }
                    samples += row.samples.size
                    records += row
                    check(records.size <= FitnessHealthPolicy.MAX_RECORDS && samples <= HealthExtraKernel.MAX_SAMPLES) { "HEALTH_IMPORT_LIMIT" }
                }
                page = response.pageToken
                check(page == null || pages.add(page!!)) { "HEALTH_IMPORT_LIMIT" }
            } while (page != null)
        }
        val day = if (HealthExtraKind.ALL_SOURCE_STEPS in query.selection.kinds) {
            permission()
            val result = if (query.lastDayStart == query.windowEnd) null else FitnessImportDiagnostics.read(FitnessReadStage.DAILY_STEPS) {
                client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),
                    TimeRangeFilter.between(Instant.ofEpochMilli(query.lastDayStart), Instant.ofEpochMilli(query.windowEnd))))
            }
            // Provider priority/deduplication, never a sum of phone/Watch raw intervals.
            HealthExtraStepDay(query.lastDay, query.zone, query.lastDayStart, query.windowEnd,
                result?.get(StepsRecord.COUNT_TOTAL), result?.dataOrigins?.map { it.packageName }?.toSet() ?: emptySet())
        } else null
        permission()
        val changes = FitnessImportDiagnostics.read(FitnessReadStage.CHANGE_CHECK) { client.getChanges(barrier) }
        if (changes.changesTokenExpired || changes.changes.isNotEmpty() || changes.hasMore) throw FitnessSourceChanged()
        permission()
        return HealthExtraSnapshot(query = query, capturedAt = now(), records = records.toList(), stepDay = day)
    }

    private fun map(kind: HealthExtraKind, r: Record): HealthExtraRecord {
        val m = r.metadata
        // SDK's common temporal interfaces are internal. Use public record
        // properties only; no reflection or invisible API access.
        val times = when (r) {
            is HeartRateRecord -> Times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            is SpeedRecord -> Times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            is DistanceRecord -> Times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            is ActiveCaloriesBurnedRecord -> Times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            is TotalCaloriesBurnedRecord -> Times(r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset)
            is BodyFatRecord -> Times(r.time, null, r.zoneOffset, null)
            is BasalMetabolicRateRecord -> Times(r.time, null, r.zoneOffset, null)
            else -> error("HEALTH_EXTRA_SOURCE_INVALID")
        }
        val bits = when (kind) {
            HealthExtraKind.DISTANCE -> (r as DistanceRecord).distance.inMeters.toRawBits()
            HealthExtraKind.ACTIVE_ENERGY -> (r as ActiveCaloriesBurnedRecord).energy.inKilocalories.toRawBits()
            HealthExtraKind.TOTAL_ENERGY -> (r as TotalCaloriesBurnedRecord).energy.inKilocalories.toRawBits()
            HealthExtraKind.BODY_FAT -> (r as BodyFatRecord).percentage.value.toRawBits()
            HealthExtraKind.BASAL_RATE -> (r as BasalMetabolicRateRecord).basalMetabolicRate.inWatts.toRawBits()
            else -> null
        }
        val values = when (kind) {
            HealthExtraKind.HEART_RATE -> (r as HeartRateRecord).samples.map { HealthExtraSample(it.time.toEpochMilli(), bpm = it.beatsPerMinute) }
            HealthExtraKind.SPEED -> (r as SpeedRecord).samples.map { HealthExtraSample(it.time.toEpochMilli(), doubleBits = it.speed.inMetersPerSecond.toRawBits()) }
            else -> emptyList()
        }
        return HealthExtraRecord(m.dataOrigin.packageName, kind, m.id, times.start.toEpochMilli(), times.end?.toEpochMilli(),
            times.startOffset?.totalSeconds, times.endOffset?.totalSeconds,
            m.lastModifiedTime.toEpochMilli(), m.clientRecordId, m.clientRecordVersion, m.device?.manufacturer,
            m.device?.model, m.device?.type, m.recordingMethod, bits, values)
    }
    private data class Times(val start: Instant, val end: Instant?, val startOffset: ZoneOffset?, val endOffset: ZoneOffset?)
}
