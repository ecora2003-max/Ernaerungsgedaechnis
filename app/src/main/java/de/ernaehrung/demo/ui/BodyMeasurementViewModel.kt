package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.fitness.BodyMeasurementInput
import de.ernaehrung.demo.data.fitness.LegacyBodyField
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class BodyMeasurementUiState(val history: BodyMeasurementHistory? = null,
    val key: String? = null, val keyChecked: Boolean = false, val purgeRevision: Int = 0, val run: BodyMeasurementRunEntity? = null, val busy: Boolean = false,
    val message: String? = null, val readError: Boolean = false)

/** Drafts remain in the UI process. Only its opaque operation key is restored;
 * committed results are reread from Room before any possible repeat action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BodyMeasurementViewModel(private val store: FoodStore) : ViewModel() {
    private val mutable = MutableStateFlow(BodyMeasurementUiState())
    val state = mutable.asStateFlow()
    private var action: Job? = null
    private var observer: Job? = null
    private val historyRefresh = MutableStateFlow(0L)
    init {
        viewModelScope.launch {
            historyRefresh.flatMapLatest {
                store.bodyMeasurementHistory().catch {
                    mutable.update { it.copy(readError = true, message = "Körpermesshistorie konnte nicht gelesen werden.") }
                }
            }.collect { rows -> mutable.update { it.copy(history = rows, readError = false) } }
        }
    }
    fun retryHistory() { historyRefresh.value += 1L }
    fun openOperation(key: String) {
        if (state.value.key == key && (state.value.keyChecked || state.value.busy)) return
        action?.cancel()
        observer?.cancel()
        mutable.update { it.copy(key = key, keyChecked = false, run = null, busy = true, message = null) }
        action = viewModelScope.launch {
            try {
                val run = withContext(Dispatchers.IO) { store.beginBodyMeasurement(key) }
                if (state.value.key == key) mutable.update { it.copy(run = run, keyChecked = true, busy = false,
                    message = if (run?.kind == "SAVE") "Diese Messversion wurde bereits gespeichert."
                        else if (run.kind != "OPEN") "Dieser Vorgang ist abgeschlossen. Für eine neue Messung neu beginnen." else null) }
                observer = viewModelScope.launch {
                    store.watchBodyMeasurementRun(key).catch {
                        if (state.value.key == key) mutable.update { it.copy(keyChecked = false, message = "Vorgangsstatus erneut prüfen.") }
                    }.collect { current ->
                        if (state.value.key == key) mutable.update { it.copy(run = current, keyChecked = current != null) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.key == key) mutable.update { it.copy(busy = false,
                    message = "Vorgangsstatus nicht lesbar. Nochmals öffnen, bevor du speicherst.") }
            }
        }
    }
    fun save(key: String, input: BodyMeasurementInput, expectedPreviousId: String?, confirmed: Boolean,
        legacyId: String? = null, legacyField: LegacyBodyField? = null) {
        if (state.value.key != key || !state.value.keyChecked || state.value.busy || state.value.run?.kind != "OPEN" || state.value.readError) return
        mutable.update { it.copy(busy = true, message = null) }
        action = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveBodyMeasurement(key, input, expectedPreviousId, confirmed, legacyId, legacyField) }
                val run = withContext(Dispatchers.IO) { store.bodyMeasurementRun(key) }
                if (state.value.key == key) mutable.update { it.copy(run = run, message = "Messversion lokal gespeichert.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (state.value.key == key) mutable.update { it.copy(keyChecked = false, message = if (failure is IllegalArgumentException) failure.message
                    else "Messung konnte nicht gespeichert werden. Mit demselben Vorgang erneut prüfen.") }
            } finally { if (state.value.key == key) mutable.update { it.copy(busy = false) } }
        }
    }
    fun purge(confirmed: Boolean) {
        if (state.value.busy || state.value.readError) return
        val key = "body-purge-${UUID.randomUUID()}"
        mutable.update { it.copy(busy = true, message = null) }
        action = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.purgeBodyMeasurements(key, confirmed) }
                val run = state.value.key?.let { withContext(Dispatchers.IO) { store.bodyMeasurementRun(it) } }
                mutable.update { it.copy(run = run, purgeRevision = it.purgeRevision + 1, message = "Lokale Körpermesshistorie einschließlich Korrekturen gelöscht.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(message = "Körpermesshistorie konnte nicht gelöscht werden.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
