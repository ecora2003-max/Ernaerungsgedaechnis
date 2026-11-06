package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class StorageCapacityUiState(val snapshot: StorageCapacitySnapshot? = null, val readError: Boolean = false)
internal data class StorageCapacitySaveState(val busy: Boolean = false, val result: String? = null, val error: String? = null)

/** SavedState never receives fields, confirmation or a snapshot. The screen
 * retains only opaque location/operation IDs and freezes a RAM-only edit basis.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StorageCapacityViewModel(private val store: FoodStore) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    val state = refresh.flatMapLatest {
        store.watchStorageCapacity().map { StorageCapacityUiState(it) }.onStart { emit(StorageCapacityUiState()) }
            .catch { emit(StorageCapacityUiState(readError = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageCapacityUiState())
    private val saving = MutableStateFlow(StorageCapacitySaveState())
    val saveState = saving.asStateFlow()
    fun retry() { refresh.value += 1L }
    fun clearResult() { if (!saving.value.busy) saving.value = StorageCapacitySaveState() }
    fun save(key: String, input: StorageCapacityInput, confirmed: Boolean) {
        if (saving.value.busy || saving.value.result != null) return
        saving.value = StorageCapacitySaveState(busy = true)
        viewModelScope.launch {
            try { saving.value = StorageCapacitySaveState(result = store.reviewStorageCapacity(key, input, confirmed)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalArgumentException) { saving.value = StorageCapacitySaveState(error = failure.message ?: "Angaben erneut prüfen.") }
            catch (_: Exception) { saving.value = StorageCapacitySaveState(error = "Lagerprüfung konnte nicht gespeichert werden. Vorhandene Daten bleiben erhalten; erneut versuchen.") }
        }
    }
}
