package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.fitness.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal data class FitnessCache(val state: HealthImportStateEntity, val lastSuccess: HealthImportRunEntity?,
    val records: List<HealthRecordVersionEntity>)

/** FoodStore-owned fitness ledger. No food operation, meal, stock, price or target
 * is touched. Local consent revisions use a Room CAS; OS permissions are rechecked before commit.
 */
internal class HealthImportOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.healthDao()
    private val source = FitnessHealthPolicy.SAMSUNG_PACKAGE
    private suspend fun state() = dao.state(source) ?: HealthImportStateEntity(source, false, 0L)
    private fun next(revision: Long): Long {
        require(revision in 0 until Long.MAX_VALUE) { "HEALTH_REVISION_LIMIT" }
        return revision + 1L
    }
    private fun validateKey(key: String) { require(key.isNotBlank() && key.length <= 200) { "HEALTH_OPERATION_INVALID" } }

    fun observe(): Flow<FitnessCache> = db.invalidationTracker.createFlow(
        "health_import_state", "health_import_runs", "health_source_record_versions").map {
        db.withTransaction {
            val state = state()
            FitnessCache(state, state.lastSuccessRunId?.let { dao.run(it) }, dao.latest(source))
        }
    }

    suspend fun enable(key: String, enabled: Boolean, confirmed: Boolean): Long = db.withTransaction {
        require(confirmed) { "HEALTH_EXPLICIT_ACTION_REQUIRED" }
        validateKey(key)
        val hash = FitnessImportKernel.fingerprint("HEALTH_LOCAL_AUTH_V1", source, enabled)
        dao.run(key)?.let {
            require(it.kind == "AUTH" && it.sourcePackage == source && it.fingerprint == hash) { "HEALTH_OPERATION_REUSED" }
            return@withTransaction it.authorizationRevision
        }
        val current = state()
        val revision = next(current.authorizationRevision)
        val at = now().also { require(it >= 0L) }
        dao.insertRun(HealthImportRunEntity(key, source, "AUTH", hash, at, revision))
        dao.saveState(current.copy(enabled = enabled, authorizationRevision = revision, lastErrorCode = null))
        revision
    }

    suspend fun sync(key: String, provider: FitnessHealthSource): HealthImportRunEntity {
        validateKey(key)
        require(provider.requiredPermissions == FitnessHealthPolicy.REQUIRED_PERMISSIONS) { "HEALTH_PERMISSION_SCOPE" }
        db.withTransaction { dao.run(key) }?.let {
            require(it.kind == "SYNC" && it.sourcePackage == source) { "HEALTH_OPERATION_REUSED" }
            return it // Retry never rereads the provider or produces a new event.
        }
        val initial = db.withTransaction {
            state().also { check(it.enabled) { "HEALTH_LOCAL_OPT_IN_REQUIRED" } }
        }
        val startedAt = now()
        try {
            check(provider.availability() == FitnessHealthSource.Availability.AVAILABLE) { "HEALTH_SOURCE_UNAVAILABLE" }
            if (!provider.grantedPermissions().containsAll(provider.requiredPermissions)) throw FitnessPermissionMissing()
            val snapshot = provider.readSnapshot()
            return db.withTransaction {
                dao.run(key)?.let {
                    require(it.kind == "SYNC" && it.sourcePackage == source) { "HEALTH_OPERATION_REUSED" }
                    return@withTransaction it
                }
                val current = state()
                check(current.enabled && current.authorizationRevision == initial.authorizationRevision) { "HEALTH_AUTHORIZATION_CHANGED" }
                if (!provider.grantedPermissions().containsAll(provider.requiredPermissions)) throw FitnessPermissionMissing()
                val at = now()
                val digest = FitnessImportKernel.fingerprint("HEALTH_WINDOW_V1", snapshot.sourcePackage,
                    snapshot.windowStart, snapshot.windowEnd, snapshot.capturedAt, snapshot.day,
                    snapshot.dayZone, snapshot.dayStart, snapshot.dayEnd, snapshot.aggregatedSteps,
                    snapshot.records.sortedWith(compareBy({ it.kind.name }, { it.externalId }))
                        .map(FitnessImportKernel::recordFingerprint))
                current.lastSuccessRunId?.let { dao.run(it) }?.let { previous ->
                    val captured = requireNotNull(previous.capturedAt)
                    if (snapshot.capturedAt < captured || (snapshot.capturedAt == captured && previous.fingerprint != digest))
                        throw FitnessSourceChanged()
                }
                val changes = FitnessImportKernel.changes(snapshot, dao.latest(source).map { it.stored() }, at)
                val run = HealthImportRunEntity(key, source, "SYNC", digest, at, current.authorizationRevision,
                    snapshot.capturedAt, snapshot.windowStart, snapshot.windowEnd, snapshot.day, snapshot.dayZone,
                    snapshot.dayStart, snapshot.dayEnd, snapshot.aggregatedSteps,
                    changes.count { it.visible }, changes.count { !it.visible })
                dao.insertRun(run)
                if (changes.isNotEmpty()) dao.insertVersions(changes.map { HealthRecordVersionEntity.from(it, key) })
                dao.saveState(current.copy(lastSuccessRunId = key, lastAttemptAt = at, lastErrorCode = null))
                run
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val code = FitnessImportDiagnostics.code(failure)
            recordFailure(initial.authorizationRevision, initial.lastSuccessRunId, startedAt, code)
            throw failure
        }
    }

    /** A late failed attempt cannot overwrite newer consent or successful data. */
    private suspend fun recordFailure(revision: Long, baselineSuccess: String?, startedAt: Long, code: String) = db.withTransaction {
        val current = state()
        if (current.authorizationRevision != revision) return@withTransaction
        val revoked = code == "PERMISSION_MISSING"
        // A same-millisecond success is still newer than this failed read.
        // Revoked rights stop future reads while retaining that successful cache.
        if (!revoked && (current.lastSuccessRunId != baselineSuccess ||
            (current.lastAttemptAt != null && current.lastAttemptAt > startedAt))) return@withTransaction
        dao.saveState(current.copy(enabled = if (revoked) false else current.enabled,
            authorizationRevision = if (revoked) next(current.authorizationRevision) else current.authorizationRevision,
            lastAttemptAt = now(), lastErrorCode = code))
    }

    /** Stop future reads on foreground permission discovery, without deleting cache. */
    suspend fun permissionsLost(expectedRevision: Long) = db.withTransaction {
        val current = state()
        if (!current.enabled || current.authorizationRevision != expectedRevision) return@withTransaction
        dao.saveState(current.copy(enabled = false, authorizationRevision = next(current.authorizationRevision),
            lastAttemptAt = now(), lastErrorCode = "PERMISSION_MISSING"))
    }

    suspend fun purge(key: String, confirmed: Boolean) = db.withTransaction {
        require(confirmed) { "HEALTH_EXPLICIT_ACTION_REQUIRED" }
        validateKey(key)
        val hash = FitnessImportKernel.fingerprint("HEALTH_LOCAL_PURGE_V1", source)
        dao.run(key)?.let {
            require(it.kind == "PURGE" && it.sourcePackage == source && it.fingerprint == hash) { "HEALTH_OPERATION_REUSED" }
            return@withTransaction
        }
        val revision = next(state().authorizationRevision)
        // Detach the FK before removing only this source's local health history.
        dao.saveState(HealthImportStateEntity(source, false, revision))
        dao.purgeRecords(source)
        dao.redactRuns(source)
        dao.insertRun(HealthImportRunEntity(key, source, "PURGE", hash, now(), revision))
    }

    suspend fun history(kind: String, id: String) = db.withTransaction {
        dao.historyFor(source, kind, id)
    }
}
