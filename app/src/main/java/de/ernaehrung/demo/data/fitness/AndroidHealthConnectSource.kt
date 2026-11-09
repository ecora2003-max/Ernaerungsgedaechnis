package de.ernaehrung.demo.data.fitness

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Real Health Connect API. Samsung origin is mandatory; it is not proof that a
 * record came from Watch 4. Preserve the actual device metadata for that audit.
 * Foreground only, no history/background/write/route/nutrition permissions.
 */
internal class AndroidHealthConnectSource(context: Context,
    private val now: () -> Long = System::currentTimeMillis) : FitnessHealthSource {
    private val application = context.applicationContext
    companion object {
        const val SAMSUNG_PACKAGE = FitnessHealthPolicy.SAMSUNG_PACKAGE
    }
    override val requiredPermissions = setOf(HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class))

    override fun availability() = when (HealthConnectClient.getSdkStatus(application)) {
        HealthConnectClient.SDK_AVAILABLE -> FitnessHealthSource.Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> FitnessHealthSource.Availability.INSTALL_OR_UPDATE_REQUIRED
        else -> FitnessHealthSource.Availability.UNSUPPORTED
    }
    private fun client(): HealthConnectClient {
        check(availability() == FitnessHealthSource.Availability.AVAILABLE) { "HEALTH_SOURCE_UNAVAILABLE" }
        return HealthConnectClient.getOrCreate(application)
    }
    override suspend fun grantedPermissions(): Set<String> =
        if (availability() != FitnessHealthSource.Availability.AVAILABLE) emptySet()
        else FitnessImportDiagnostics.read(FitnessReadStage.PERMISSIONS) {
            client().permissionController.getGrantedPermissions()
        }

    override suspend fun readSnapshot(): FitnessHealthSnapshot {
        val client = FitnessImportDiagnostics.read(FitnessReadStage.PROVIDER) { client() }
        suspend fun permission() {
            val granted = FitnessImportDiagnostics.read(FitnessReadStage.PERMISSIONS) {
                client.permissionController.getGrantedPermissions()
            }
            if (!granted.containsAll(requiredPermissions)) throw FitnessPermissionMissing()
        }
        permission()
        val end = Instant.ofEpochMilli(now())
        val start = end.minus(Duration.ofDays(28))
        val origin = setOf(DataOrigin(SAMSUNG_PACKAGE))
        // Refuse a rescan whose pages/aggregate changed underneath us. A partial
        // or permission-denied response must never delete cached observations.
        val barrier = FitnessImportDiagnostics.read(FitnessReadStage.CHANGE_TOKEN) {
            client.getChangesToken(ChangesTokenRequest(
                recordTypes = setOf(StepsRecord::class, ExerciseSessionRecord::class), dataOriginFilters = origin))
        }
        val records = mutableListOf<FitnessSourceRecord>()
        var page: String? = null
        val stepPages = mutableSetOf<String>()
        do {
            permission()
            val response = FitnessImportDiagnostics.read(FitnessReadStage.STEPS) {
                client.readRecords(ReadRecordsRequest(StepsRecord::class,
                    TimeRangeFilter.between(start, end), dataOriginFilter = origin, pageSize = 1000, pageToken = page))
            }
            records += FitnessImportDiagnostics.read(FitnessReadStage.STEP_MAPPING) { response.records.map { r ->
                val m = r.metadata
                FitnessSourceRecord(m.id, FitnessRecordKind.STEPS, m.dataOrigin.packageName,
                    r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.startZoneOffset?.totalSeconds,
                    r.endZoneOffset?.totalSeconds, m.lastModifiedTime.toEpochMilli(), m.clientRecordId,
                    m.clientRecordVersion, m.device?.manufacturer, m.device?.model, m.device?.type,
                    m.recordingMethod, steps = r.count)
            } }
            page = response.pageToken
            check(records.size <= FitnessHealthPolicy.MAX_RECORDS && (page == null || stepPages.add(page!!))) { "HEALTH_IMPORT_LIMIT" }
        } while (page != null)
        val workoutPages = mutableSetOf<String>()
        do {
            permission()
            val response = FitnessImportDiagnostics.read(FitnessReadStage.WORKOUTS) {
                client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,
                    TimeRangeFilter.between(start, end), dataOriginFilter = origin, pageSize = 1000, pageToken = page))
            }
            records += FitnessImportDiagnostics.read(FitnessReadStage.WORKOUT_MAPPING) { response.records.map(::workout) }
            page = response.pageToken
            check(records.size <= FitnessHealthPolicy.MAX_RECORDS && (page == null || workoutPages.add(page!!))) { "HEALTH_IMPORT_LIMIT" }
        } while (page != null)
        permission()
        val zone = ZoneId.systemDefault()
        val day = end.atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant()
        val aggregate = FitnessImportDiagnostics.read(FitnessReadStage.DAILY_STEPS) {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),
                TimeRangeFilter.between(dayStart, end), dataOriginFilter = origin))
        }
        permission()
        val changes = FitnessImportDiagnostics.read(FitnessReadStage.CHANGE_CHECK) { client.getChanges(barrier) }
        if (changes.changesTokenExpired || changes.changes.isNotEmpty() || changes.hasMore) throw FitnessSourceChanged()
        permission()
        return FitnessHealthSnapshot(SAMSUNG_PACKAGE, start.toEpochMilli(), end.toEpochMilli(), now(),
            records, day.toString(), zone.id, dayStart.toEpochMilli(), end.toEpochMilli(), aggregate[StepsRecord.COUNT_TOTAL])
    }

    /** Explicit per-workout foreground query. No extra permission, history or
     * background read. The value remains a window aggregate, not active time
     * attributed to an individual session.
     */
    override suspend fun readWorkoutDuration(query: FitnessWorkoutDurationQuery): FitnessWorkoutDurationSnapshot {
        require(FitnessWorkoutDurationPolicy.inReadWindow(query)) { "HEALTH_WINDOW_INVALID" }
        val client = FitnessImportDiagnostics.read(FitnessReadStage.PROVIDER) { client() }
        suspend fun permission() {
            if (!FitnessImportDiagnostics.read(FitnessReadStage.PERMISSIONS) {
                client.permissionController.getGrantedPermissions()
            }.containsAll(requiredPermissions)) throw FitnessPermissionMissing()
        }
        permission()
        val origin = setOf(DataOrigin(SAMSUNG_PACKAGE))
        val window = TimeRangeFilter.between(Instant.ofEpochMilli(query.startAt), Instant.ofEpochMilli(query.endAt))
        // Record reads filter by start time; aggregates include overlaps. Scan
        // earlier starts in the SAME authorized 28-day scope before aggregating.
        val recordsWindow = TimeRangeFilter.between(
            Instant.ofEpochMilli(FitnessWorkoutDurationPolicy.recordsWindowStart(query)), Instant.ofEpochMilli(query.endAt))
        val barrier = FitnessImportDiagnostics.read(FitnessReadStage.CHANGE_TOKEN) {
            client.getChangesToken(ChangesTokenRequest(setOf(ExerciseSessionRecord::class), origin))
        }
        val records = mutableListOf<FitnessSourceRecord>()
        val pages = mutableSetOf<String>()
        var page: String? = null
        do {
            permission()
            val response = FitnessImportDiagnostics.read(FitnessReadStage.WORKOUTS) {
                client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, recordsWindow,
                    dataOriginFilter = origin, pageSize = 1000, pageToken = page))
            }
            records += FitnessImportDiagnostics.read(FitnessReadStage.WORKOUT_MAPPING) { response.records.map(::workout) }
            page = response.pageToken
            check(records.size <= FitnessHealthPolicy.MAX_RECORDS && (page == null || pages.add(page!!))) { "HEALTH_IMPORT_LIMIT" }
        } while (page != null)
        val excluded = FitnessWorkoutDurationPolicy.exclusion(query, records, now())
        permission()
        // The SDK metric is converted from integer milliseconds by its own
        // Duration converter. Never subtract optional pause/rest segments here.
        val duration = if (excluded == null) FitnessImportDiagnostics.read(FitnessReadStage.WORKOUT_DURATION) {
            client.aggregate(AggregateRequest(setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL), window,
                dataOriginFilter = origin))[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMillis()
        } else null
        permission()
        val changes = FitnessImportDiagnostics.read(FitnessReadStage.CHANGE_CHECK) { client.getChanges(barrier) }
        if (changes.changesTokenExpired || changes.changes.isNotEmpty() || changes.hasMore) throw FitnessSourceChanged()
        permission()
        return FitnessWorkoutDurationSnapshot(sourcePackage = SAMSUNG_PACKAGE, windowStart = query.startAt,
            windowEnd = query.endAt, capturedAt = now(), records = records, durationMillis = duration,
            status = excluded ?: if (duration == null) FitnessWorkoutDurationStatus.NOT_RETURNED else FitnessWorkoutDurationStatus.AVAILABLE)
    }

    private fun workout(r: ExerciseSessionRecord): FitnessSourceRecord {
        val m = r.metadata
        return FitnessSourceRecord(m.id, FitnessRecordKind.WORKOUT, m.dataOrigin.packageName,
            r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.startZoneOffset?.totalSeconds,
            r.endZoneOffset?.totalSeconds, m.lastModifiedTime.toEpochMilli(), m.clientRecordId,
            m.clientRecordVersion, m.device?.manufacturer, m.device?.model, m.device?.type,
            m.recordingMethod, exerciseType = r.exerciseType, title = r.title, notes = r.notes)
    }
}
