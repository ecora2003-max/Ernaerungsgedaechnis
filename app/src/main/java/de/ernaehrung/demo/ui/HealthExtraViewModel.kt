package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class HealthExtraUiState(val cache: HealthExtraCache? = null,
    val selection: HealthExtraSelection = HealthExtraSelection(emptySet()),
    val availability: FitnessHealthSource.Availability? = null, val historyAvailable: Boolean = false,
    val busy: Boolean = false, val awaitingPermission: Boolean = false, val permissionLaunch: String? = null,
    val requestedPermissions: Set<String> = emptySet(), val message: String? = null,
    val history: List<HealthExtraStored>? = null)

/** Selection and OS result are not consent. Only a live explicit connection
 * gesture may authorize this independent scope at the FoodStore boundary. */
internal class HealthExtraViewModel(private val store: FoodStore, private val source: HealthExtraSource,
    private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val mutable = MutableStateFlow(HealthExtraUiState())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var pending: HealthExtraSelection? = null
    private var generation = 0L
    private var job: Job? = null
    private var historyJob: Job? = null
    init { viewModelScope.launch {
        store.healthExtraCache().catch {
            mutable.update { it.copy(message = "Die zusätzliche Gesundheitskopie konnte nicht gelesen werden.") }
        }.collect { cache ->
            mutable.update { old -> old.copy(cache = cache,
                selection = if (!initialized && cache.state.kinds.isNotEmpty()) cache.state.selection() else old.selection,
                history = old.history?.takeIf { rows -> rows.isNotEmpty() &&
                    cache.state.authorizationRevision == old.cache?.state?.authorizationRevision && cache.records.any {
                        it.record.sourcePackage == rows.first().record.sourcePackage && it.record.kind == rows.first().record.kind &&
                            it.record.externalId == rows.first().record.externalId
                    } }) }
            initialized = true
        }
    } }
    private fun key() = "health-extra-${UUID.randomUUID()}"
    private fun update(at: Long, block: (HealthExtraUiState) -> HealthExtraUiState) { if (at == generation) mutable.update(block) }
    private fun action(replace: Boolean = false, block: suspend (Long) -> Unit) {
        if (!replace && (state.value.busy || state.value.awaitingPermission)) return
        if (replace) job?.cancel()
        val at = ++generation
        mutable.update { it.copy(busy = true, message = null) }
        job = viewModelScope.launch {
            try { block(at) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { update(at) { it.copy(message = FitnessImportDiagnostics.explain(FitnessImportDiagnostics.code(failure))) } }
            finally { update(at) { it.copy(busy = false) } }
        }
    }
    fun edit(selection: HealthExtraSelection) {
        if (state.value.busy || state.value.awaitingPermission) return
        initialized = true
        mutable.update { it.copy(selection = selection.copy(kinds = selection.kinds.toSet()), message = null) }
    }
    /** A display preview may discover that history is needed; it never grants it. */
    fun preview(): HealthExtraQuery? = runCatching {
        HealthExtraKernel.plan(state.value.selection.copy(historyOptIn = true), now(), ZoneId.systemDefault().id)
    }.getOrNull()
    private fun query(selection: HealthExtraSelection) = HealthExtraKernel.plan(selection, now(), ZoneId.systemDefault().id)
    private fun availability(at: Long): Boolean {
        val available = source.availability()
        val history = available == FitnessHealthSource.Availability.AVAILABLE && source.historyAvailable()
        update(at) { it.copy(availability = available, historyAvailable = history) }
        return available == FitnessHealthSource.Availability.AVAILABLE
    }
    private suspend fun granted(query: HealthExtraQuery): Boolean {
        require(source.requiredPermissions(query) == HealthExtraKernel.permissions(query)) { "HEALTH_PERMISSION_SCOPE" }
        check(!query.needsHistory || source.historyAvailable()) { "HEALTH_EXTRA_HISTORY_UNAVAILABLE" }
        return source.grantedPermissions().containsAll(HealthExtraKernel.permissions(query))
    }
    private suspend fun sync(at: Long) {
        val deferred = withContext(Dispatchers.IO) {
            store.syncHealthExtra(key(), source)
            store.healthExtraCache().first().records.count { it.reason == FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE }
        }
        update(at) { it.copy(message = "Ausgewählte Gesundheitsdaten lokal übernommen. Im lokalen Stand noch zurückgestellt: $deferred.") }
    }
    fun onForeground() {
        if (pending != null) return
        action { at ->
            if (!availability(at)) return@action
            val auth = store.healthExtraCache().first().state
            if (auth.enabled) {
                if (granted(query(auth.selection()))) sync(at)
                else {
                    store.stopHealthExtraAfterPermissionLoss(auth.authorizationRevision)
                    throw FitnessPermissionMissing()
                }
            }
        }
    }
    fun connect(confirmed: Boolean) = action { at ->
        require(confirmed) { "HEALTH_EXPLICIT_ACTION_REQUIRED" }
        val chosen = state.value.selection.copy(kinds = state.value.selection.kinds.toSet())
        val q = query(chosen)
        check(availability(at)) { "HEALTH_SOURCE_UNAVAILABLE" }
        if (granted(q)) {
            store.authorizeHealthExtra(key(), chosen, source, true)
            sync(at)
        } else {
            pending = chosen
            update(at) { it.copy(awaitingPermission = true, permissionLaunch = key(), requestedPermissions = HealthExtraKernel.permissions(q)) }
        }
    }
    fun consumePermissionLaunch(id: String): Boolean {
        if (pending == null || state.value.permissionLaunch != id) return false
        mutable.update { it.copy(permissionLaunch = null) }; return true
    }
    fun onPermissionResult() {
        val chosen = pending ?: return
        pending = null
        mutable.update { it.copy(awaitingPermission = false, permissionLaunch = null, requestedPermissions = emptySet()) }
        action { at ->
            check(availability(at)) { "HEALTH_SOURCE_UNAVAILABLE" }
            if (!granted(query(chosen))) throw FitnessPermissionMissing()
            store.authorizeHealthExtra(key(), chosen, source, true); sync(at)
        }
    }
    fun permissionLaunchFailed() {
        pending = null
        mutable.update { it.copy(awaitingPermission = false, permissionLaunch = null, requestedPermissions = emptySet(),
            message = "Die Health-Connect-Freigabe konnte nicht geöffnet werden.") }
    }
    fun resync() = action { at ->
        check(availability(at)) { "HEALTH_SOURCE_UNAVAILABLE" }
        val auth = store.healthExtraCache().first().state
        check(auth.enabled) { "HEALTH_LOCAL_OPT_IN_REQUIRED" }
        if (!granted(query(auth.selection()))) {
            store.stopHealthExtraAfterPermissionLoss(auth.authorizationRevision); throw FitnessPermissionMissing()
        }
        sync(at)
    }
    private fun cancelPending() {
        pending = null; historyJob?.cancel()
        mutable.update { it.copy(awaitingPermission = false, permissionLaunch = null, requestedPermissions = emptySet(), history = null) }
    }
    fun stop() {
        cancelPending()
        action(replace = true) { at ->
            store.authorizeHealthExtra(key(), null, source, true)
            update(at) { it.copy(message = "Zusätzliche Übernahme ausgeschaltet. Die lokale Kopie bleibt lesbar.") }
        }
    }
    fun purge(confirmed: Boolean) {
        cancelPending()
        action(replace = true) { at ->
            store.purgeHealthExtra(key(), confirmed)
            update(at) { it.copy(selection = HealthExtraSelection(emptySet()),
                message = "Die zusätzliche Gesundheitskopie wurde gelöscht. Die Basisübernahme und manuelle Messungen bleiben erhalten.") }
        }
    }
    fun showHistory(row: HealthExtraStored) {
        historyJob?.cancel()
        val at = generation; val revision = state.value.cache?.state?.authorizationRevision
        historyJob = viewModelScope.launch {
            val history = store.healthExtraHistory(row.record.sourcePackage, row.record.kind.name, row.record.externalId)
            val fresh = store.healthExtraCache().first()
            if (at == generation && fresh.state.authorizationRevision == revision && fresh.records.any {
                it.record.sourcePackage == row.record.sourcePackage && it.record.kind == row.record.kind && it.record.externalId == row.record.externalId
            }) mutable.update { it.copy(history = history) }
        }
    }
    fun closeHistory() { historyJob?.cancel(); mutable.update { it.copy(history = null) } }
}
