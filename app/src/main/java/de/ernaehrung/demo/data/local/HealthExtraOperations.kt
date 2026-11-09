package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.fitness.*
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import org.json.JSONArray

internal data class HealthExtraCache(val state: HealthExtraStateEntity, val lastSuccess: HealthExtraRunEntity?,
    val records: List<HealthExtraStored>)

/** Independent, read-only observations. No food, stock, manual measurement,
 * workout, goal, or older Samsung import is produced or modified by a sync. */
internal class HealthExtraOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.healthExtraDao()
    private suspend fun state() = dao.state() ?: HealthExtraStateEntity()
    private fun next(value: Long): Long {
        require(value in 0 until Long.MAX_VALUE) { "HEALTH_REVISION_LIMIT" }; return value + 1
    }
    private fun key(value: String) { require(value.isNotBlank() && value.length <= 200) { "HEALTH_OPERATION_INVALID" } }
    private fun expected(selection: HealthExtraSelection) = HealthExtraKernel.plan(selection, now(), ZoneId.systemDefault().id)
    private suspend fun permissions(provider: HealthExtraSource, query: HealthExtraQuery) {
        check(provider.availability() == FitnessHealthSource.Availability.AVAILABLE) { "HEALTH_SOURCE_UNAVAILABLE" }
        require(provider.requiredPermissions(query) == HealthExtraKernel.permissions(query)) { "HEALTH_PERMISSION_SCOPE" }
        check(!query.needsHistory || provider.historyAvailable()) { "HEALTH_EXTRA_HISTORY_UNAVAILABLE" }
        if (!provider.grantedPermissions().containsAll(HealthExtraKernel.permissions(query))) throw FitnessPermissionMissing()
    }
    private fun assemble(rows: List<HealthExtraVersionEntity>, samples: List<HealthExtraSampleEntity>): List<HealthExtraStored> {
        val grouped = samples.groupBy { listOf(it.sourcePackage, it.kind, it.externalId, it.revision) }
        return rows.map { row -> row.stored(grouped[listOf(row.sourcePackage, row.kind, row.externalId, row.revision)]
            .orEmpty().sortedBy { it.position }.mapIndexed { index, sample ->
                check(sample.position == index) { "HEALTH_CACHE_INVALID" }; sample.sample()
            }) }
    }
    fun observe() = db.invalidationTracker.createFlow("health_extra_state", "health_extra_runs",
        "health_extra_versions", "health_extra_samples").map {
        readCache()
    }
    suspend fun readCache(): HealthExtraCache = db.withTransaction {
        val auth = state()
        HealthExtraCache(auth, auth.lastSuccessRunId?.let { dao.run(it) }, assemble(dao.latest(), dao.latestSamples()))
    }
    suspend fun history(source: String, kind: String, id: String) = db.withTransaction {
        assemble(dao.history(source, kind, id), dao.historySamples(source, kind, id))
    }

    /** null selection explicitly stops future reads, retaining existing observations. */
    suspend fun authorize(operation: String, selection: HealthExtraSelection?, provider: HealthExtraSource,
        confirmed: Boolean): Long {
        require(confirmed) { "HEALTH_EXPLICIT_ACTION_REQUIRED" }; key(operation)
        val chosen = selection?.copy(kinds = selection.kinds.toSet())
        val hash = FitnessImportKernel.fingerprint("HEALTH_EXTRA_AUTH_V1", chosen?.let { HealthExtraKernel.kindsText(it.kinds) },
            chosen?.windowDays, chosen?.endDay, chosen?.historyOptIn)
        fun replay(row: HealthExtraRunEntity): Long {
            require(row.kind == "AUTH" && row.fingerprint == hash) { "HEALTH_OPERATION_REUSED" }; return row.authorizationRevision
        }
        db.withTransaction { dao.run(operation) }?.let { return replay(it) }
        val query = chosen?.let(::expected)
        if (query != null) permissions(provider, query)
        return db.withTransaction {
            dao.run(operation)?.let { return@withTransaction replay(it) }
            if (query != null) permissions(provider, query)
            val previous = state(); val revision = next(previous.authorizationRevision); val at = now()
            require(at >= 0)
            dao.insertRun(HealthExtraRunEntity(operation, "AUTH", hash, at, revision))
            dao.saveState(previous.copy(enabled = chosen != null, authorizationRevision = revision,
                kinds = chosen?.let { HealthExtraKernel.kindsText(it.kinds) } ?: previous.kinds,
                windowDays = chosen?.windowDays ?: previous.windowDays, endDay = if (chosen != null) chosen.endDay else previous.endDay,
                historyOptIn = chosen?.historyOptIn ?: previous.historyOptIn, lastErrorCode = null))
            revision
        }
    }

    suspend fun sync(operation: String, provider: HealthExtraSource): HealthExtraRunEntity {
        key(operation)
        fun replay(row: HealthExtraRunEntity): HealthExtraRunEntity {
            require(row.kind == "SYNC") { "HEALTH_OPERATION_REUSED" }; return row
        }
        db.withTransaction { dao.run(operation) }?.let { return replay(it) }
        val initial = db.withTransaction { state().also { check(it.enabled) { "HEALTH_LOCAL_OPT_IN_REQUIRED" } } }
        val started = now()
        try {
            val query = expected(initial.selection())
            permissions(provider, query)
            val raw = provider.readSnapshot(query)
            // Freeze caller-owned collections before validating and committing. No untrusted
            // provider may substitute its own window, consent, unit, or later-mutated samples.
            val snapshot = raw.copy(query = raw.query.copy(selection = raw.query.selection.copy(kinds = raw.query.selection.kinds.toSet())),
                records = raw.records.map { it.copy(samples = it.samples.toList()) },
                stepDay = raw.stepDay?.copy(sourcePackages = raw.stepDay.sourcePackages.toSet()))
            return db.withTransaction {
                dao.run(operation)?.let { return@withTransaction replay(it) }
                val current = state()
                check(current.enabled && current.authorizationRevision == initial.authorizationRevision &&
                    current.selection() == initial.selection()) { "HEALTH_AUTHORIZATION_CHANGED" }
                permissions(provider, query)
                val at = now()
                HealthExtraKernel.validate(snapshot, query, at)
                val digest = HealthExtraKernel.fingerprint(snapshot)
                current.lastSuccessRunId?.let { dao.run(it) }?.let { old ->
                    if (snapshot.capturedAt < requireNotNull(old.capturedAt) ||
                        (snapshot.capturedAt == old.capturedAt && old.authorizationRevision == current.authorizationRevision && old.fingerprint != digest))
                        throw FitnessSourceChanged()
                }
                val changes = HealthExtraKernel.changes(snapshot, query, assemble(dao.latest(), dao.latestSamples()), at)
                val day = snapshot.stepDay
                val run = HealthExtraRunEntity(operation, "SYNC", digest, at, current.authorizationRevision,
                    current.kinds, current.windowDays, current.endDay, current.historyOptIn, query.requestedAt, snapshot.capturedAt,
                    query.windowStart, query.windowEnd, query.zone, day?.day, day?.startAt, day?.endAt, day?.steps,
                    day?.let { JSONArray(it.sourcePackages.sorted()).toString() }, changes.count { it.visible }, changes.count { !it.visible })
                dao.insertRun(run)
                if (changes.isNotEmpty()) {
                    dao.insertVersions(changes.map { HealthExtraVersionEntity.from(it, operation) })
                    val samples = changes.flatMap { c -> c.record.samples.mapIndexed { index, sample ->
                        HealthExtraSampleEntity(c.record.sourcePackage, c.record.kind.name, c.record.externalId,
                            c.revision, index, sample.at, sample.doubleBits, sample.bpm)
                    } }
                    if (samples.isNotEmpty()) dao.insertSamples(samples)
                }
                dao.saveState(current.copy(lastSuccessRunId = operation, lastAttemptAt = at, lastErrorCode = null))
                run
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            recordFailure(initial, started, FitnessImportDiagnostics.code(failure)); throw failure
        }
    }

    private suspend fun recordFailure(initial: HealthExtraStateEntity, started: Long, code: String) = db.withTransaction {
        val current = state()
        if (current.authorizationRevision != initial.authorizationRevision) return@withTransaction
        val revoked = code == "PERMISSION_MISSING"
        if (!revoked && (current.lastSuccessRunId != initial.lastSuccessRunId ||
            (current.lastAttemptAt != null && current.lastAttemptAt > started))) return@withTransaction
        dao.saveState(current.copy(enabled = if (revoked) false else current.enabled,
            authorizationRevision = if (revoked) next(current.authorizationRevision) else current.authorizationRevision,
            lastAttemptAt = now(), lastErrorCode = code))
    }
    suspend fun permissionsLost(expectedRevision: Long) = db.withTransaction {
        val current = state()
        if (!current.enabled || current.authorizationRevision != expectedRevision) return@withTransaction
        dao.saveState(current.copy(enabled = false, authorizationRevision = next(current.authorizationRevision),
            lastAttemptAt = now(), lastErrorCode = "PERMISSION_MISSING"))
    }

    suspend fun purge(operation: String, confirmed: Boolean): Unit = db.withTransaction {
        require(confirmed) { "HEALTH_EXPLICIT_ACTION_REQUIRED" }; key(operation)
        val hash = FitnessImportKernel.fingerprint("HEALTH_EXTRA_PURGE_V1")
        dao.run(operation)?.let {
            require(it.kind == "PURGE" && it.fingerprint == hash) { "HEALTH_OPERATION_REUSED" }; return@withTransaction
        }
        val revision = next(state().authorizationRevision)
        dao.saveState(HealthExtraStateEntity(authorizationRevision = revision))
        dao.purgeSamples(); dao.purgeVersions(); dao.redactRuns()
        dao.insertRun(HealthExtraRunEntity(operation, "PURGE", hash, now(), revision))
    }

    /** Backups retain typed payload, not arbitrary database strings masquerading
     * as verified observations. Check raw units/bits/series/scope and lineage
     * before a capture or restore can commit. Never contact the provider here. */
    suspend fun validateHistory() {
        val auth = state()
        check(auth.authorizationRevision >= 0 && auth.windowDays in HealthExtraKernel.WINDOWS &&
            (!auth.enabled || auth.kinds.isNotEmpty())) { "HEALTH_CACHE_INVALID" }
        HealthExtraKernel.parseKinds(auth.kinds)
        auth.endDay?.let { check(java.time.LocalDate.parse(it).toString() == it) { "HEALTH_CACHE_INVALID" } }
        val runs = dao.runs().associateBy { it.id }
        fun window(run: HealthExtraRunEntity): HealthExtraSnapshot {
            check(run.authorizationRevision > 0 && run.changedRecords >= 0 && run.hiddenRecords >= 0 &&
                run.fingerprint.matches(Regex("[0-9a-f]{64}"))) { "HEALTH_CACHE_INVALID" }
            val selection = HealthExtraSelection(HealthExtraKernel.parseKinds(requireNotNull(run.kinds)),
                requireNotNull(run.windowDays), run.endDay, requireNotNull(run.historyOptIn))
            val q = HealthExtraKernel.plan(selection, requireNotNull(run.requestedAt), requireNotNull(run.zone))
            check(q.windowStart == run.windowStart && q.windowEnd == run.windowEnd) { "HEALTH_CACHE_INVALID" }
            val day = if (HealthExtraKind.ALL_SOURCE_STEPS in selection.kinds) {
                val sources = JSONArray(requireNotNull(run.stepSources))
                val packages = (0 until sources.length()).map { sources.getString(it) }
                check(packages == packages.distinct().sorted()) { "HEALTH_CACHE_INVALID" }
                HealthExtraStepDay(requireNotNull(run.day), q.zone, requireNotNull(run.dayStart), requireNotNull(run.dayEnd), run.steps, packages.toSet())
            } else {
                check(listOf(run.day, run.dayStart, run.dayEnd, run.steps, run.stepSources).all { it == null }) { "HEALTH_CACHE_INVALID" }
                null
            }
            return HealthExtraSnapshot(query = q, capturedAt = requireNotNull(run.capturedAt), records = emptyList(), stepDay = day)
                .also { HealthExtraKernel.validate(it, q, run.recordedAt) }
        }
        val windows = runs.values.filter { it.kind == "SYNC" }.associate { it.id to window(it) }
        check(auth.lastSuccessRunId == null || windows.containsKey(auth.lastSuccessRunId)) { "HEALTH_CACHE_INVALID" }
        val entities = dao.versions()
        val rows = assemble(entities, dao.samples())
        val previous = mutableMapOf<Triple<String, String, String>, Long>()
        entities.zip(rows).sortedWith(compareBy({ it.first.sourcePackage }, { it.first.kind }, { it.first.externalId }, { it.first.revision }))
            .forEach { (entity, row) ->
                val id = Triple(entity.sourcePackage, entity.kind, entity.externalId)
                val old = previous[id] ?: 0L
                check(old < Long.MAX_VALUE && row.revision == old + 1 && row.fingerprint == HealthExtraKernel.recordFingerprint(row.record)) { "HEALTH_CACHE_INVALID" }
                previous[id] = row.revision
                val run = requireNotNull(runs[entity.runId]) { "HEALTH_CACHE_INVALID" }
                val snapshot = requireNotNull(windows[run.id]) { "HEALTH_CACHE_INVALID" }.copy(records = listOf(row.record))
                val q = snapshot.query
                HealthExtraKernel.validate(snapshot, q, run.recordedAt)
                check(when (row.reason) {
                    FitnessRecordVisibility.SOURCE_READ -> row.visible && (row.record.endAt == null || row.record.endAt <= snapshot.capturedAt)
                    FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE -> !row.visible && row.record.endAt != null && row.record.endAt > snapshot.capturedAt
                    FitnessRecordVisibility.NOT_VISIBLE_IN_WINDOW -> !row.visible && row.record.startAt >= q.windowStart &&
                        (row.record.endAt ?: row.record.startAt) <= q.windowEnd
                    else -> false
                }) { "HEALTH_CACHE_INVALID" }
            }
    }
}
