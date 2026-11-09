package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class FitnessUiState(val cache: FitnessCache? = null,
    val availability: FitnessHealthSource.Availability? = null, val hasPermissions: Boolean = false,
    val busy: Boolean = false, val awaitingPermission: Boolean = false, val permissionLaunch: String? = null,
    val message: String? = null, val history: List<HealthRecordVersionEntity>? = null)

/** Permission result is only a trigger to re-read actual OS grants. A pending
 * local connection gesture lives in this process, never in saved state or JSON.
 */
internal class FitnessViewModel(private val store: FoodStore, private val source: FitnessHealthSource) : ViewModel() {
    private val mutable = MutableStateFlow(FitnessUiState())
    val state: StateFlow<FitnessUiState> = mutable.asStateFlow()
    val requiredPermissions get() = source.requiredPermissions
    private var pendingConnection = false
    private var generation = 0L
    private var actionJob: Job? = null
    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            store.fitnessCache().catch { mutable.update { it.copy(message = "Lokale Fitnessdaten konnten nicht gelesen werden.") } }
                .collect { cache -> mutable.update { it.copy(cache = cache,
                    history = it.history?.takeIf { rows -> rows.isNotEmpty() && cache.records.any { r ->
                        r.kind == rows.first().kind && r.externalId == rows.first().externalId } }) } }
        }
    }
    private fun key(prefix: String) = "$prefix-${UUID.randomUUID()}"
    private fun update(at: Long, block: (FitnessUiState) -> FitnessUiState) {
        if (at == generation) mutable.update(block)
    }
    private fun action(replace: Boolean = false, block: suspend (Long) -> Unit) {
        if (!replace && (state.value.busy || state.value.awaitingPermission)) return
        if (replace) actionJob?.cancel()
        val at = ++generation
        mutable.update { it.copy(busy = true, message = null) }
        actionJob = viewModelScope.launch {
            try { block(at) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val text = FitnessImportDiagnostics.explain(FitnessImportDiagnostics.code(failure))
                update(at) { it.copy(message = text) }
            } finally { update(at) { it.copy(busy = false) } }
        }
    }
    private suspend fun grants(at: Long): Boolean {
        val available = source.availability()
        val granted = available == FitnessHealthSource.Availability.AVAILABLE &&
            source.grantedPermissions().containsAll(source.requiredPermissions)
        update(at) { it.copy(availability = available, hasPermissions = granted) }
        return granted
    }
    private suspend fun sync(at: Long) {
        val deferred = withContext(Dispatchers.IO) {
            store.syncFitness(key("health-sync"), source)
            store.fitnessCache().first().records.count {
                it.visibilityReason == FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE
            }
        }
        update(at) { it.copy(message = if (deferred == 0) "Samsung-Health-Daten lokal übernommen."
            else "Lesestand lokal übernommen. Noch zurückgestellte Quellintervalle: $deferred.") }
    }

    /** Foreground only; no background/history permission and no timer/service. */
    fun onForeground() {
        if (pendingConnection) return
        action { at ->
            val granted = grants(at)
            val cache = store.fitnessCache().first()
            if (cache.state.enabled) {
                if (!granted) {
                    store.stopFitnessAfterPermissionLoss(cache.state.authorizationRevision)
                    update(at) { it.copy(message = "Übernahme gestoppt: Leserechte fehlen. Vorhandene Daten bleiben lokal.") }
                } else sync(at)
            }
        }
    }
    fun connect() = action { at ->
        if (grants(at)) {
            store.enableFitnessImport(key("health-enable"), true, true)
            sync(at)
        } else if (state.value.availability == FitnessHealthSource.Availability.AVAILABLE) {
            pendingConnection = true
            update(at) { it.copy(awaitingPermission = true, permissionLaunch = key("health-permission")) }
        }
    }
    fun consumePermissionLaunch(id: String): Boolean {
        if (!pendingConnection || state.value.permissionLaunch != id) return false
        mutable.update { it.copy(permissionLaunch = null) }
        return true
    }
    fun onPermissionResult() {
        if (!pendingConnection) return
        pendingConnection = false
        mutable.update { it.copy(awaitingPermission = false, permissionLaunch = null) }
        action { at ->
            if (!grants(at)) throw FitnessPermissionMissing()
            store.enableFitnessImport(key("health-enable"), true, true)
            sync(at)
        }
    }
    fun permissionLaunchFailed() {
        pendingConnection = false
        mutable.update { it.copy(awaitingPermission = false, permissionLaunch = null,
            message = "Die Health-Connect-Freigabe konnte nicht geöffnet werden.") }
    }
    fun resync() = action { at ->
        if (!grants(at)) {
            val cache = store.fitnessCache().first()
            if (cache.state.enabled) store.stopFitnessAfterPermissionLoss(cache.state.authorizationRevision)
            throw FitnessPermissionMissing()
        }
        sync(at)
    }
    fun stop() {
        pendingConnection = false
        mutable.update { it.copy(awaitingPermission = false, permissionLaunch = null) }
        action(replace = true) { at ->
            store.enableFitnessImport(key("health-stop"), false, true)
            update(at) { it.copy(message = "Automatische Übernahme ausgeschaltet. Lokale Kopie bleibt lesbar.") }
        }
    }
    fun purge(confirmed: Boolean) {
        pendingConnection = false
        historyJob?.cancel()
        mutable.update { it.copy(awaitingPermission = false, permissionLaunch = null, history = null) }
        action(replace = true) { at ->
            store.purgeLocalFitnessImport(key("health-purge"), confirmed)
            update(at) { it.copy(message = "Lokale Samsung-Health-Kopie gelöscht und Übernahme ausgeschaltet.") }
        }
    }
    fun showHistory(record: HealthRecordVersionEntity) {
        historyJob?.cancel()
        val at = generation
        historyJob = viewModelScope.launch {
            val rows = store.fitnessRecordHistory(record.kind, record.externalId)
            if (at == generation) mutable.update { it.copy(history = rows) }
        }
    }
    fun closeHistory() { historyJob?.cancel(); mutable.update { it.copy(history = null) } }
    fun providerInstallUnavailable() { mutable.update { it.copy(message = "Health Connect konnte nicht im Store geöffnet werden. Du kannst es selbst im Play Store suchen; die lokale App bleibt nutzbar.") } }
    fun settingsUnavailable() { mutable.update { it.copy(message = "Health-Connect-Einstellungen sind auf diesem Gerät nicht erreichbar.") } }
}
