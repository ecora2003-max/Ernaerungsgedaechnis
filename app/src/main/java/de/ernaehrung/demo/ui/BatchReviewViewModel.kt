package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class BatchReviewUiState(val snapshot: BatchReviewSnapshot? = null, val readError: Boolean = false)
internal data class BatchReviewSaveState(val busy: Boolean = false, val result: String? = null, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
internal class BatchReviewViewModel(private val store: FoodStore, id: String) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    val state = refresh.flatMapLatest {
        store.watchBatchReview(id).map { BatchReviewUiState(it) }.onStart { emit(BatchReviewUiState()) }
            .catch { emit(BatchReviewUiState(readError = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatchReviewUiState())
    private val saving = MutableStateFlow(BatchReviewSaveState())
    val saveState = saving.asStateFlow()
    fun retry() { refresh.value += 1L }
    fun clearResult() { if (!saving.value.busy) saving.value = BatchReviewSaveState() }
    fun save(key: String, input: BatchReviewInput, confirmed: Boolean) {
        if (saving.value.busy || saving.value.result != null) return
        saving.value = BatchReviewSaveState(busy = true)
        viewModelScope.launch {
            try { saving.value = BatchReviewSaveState(result = store.reviewBatch(key, input, confirmed)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { saving.value = BatchReviewSaveState(error = failure.message ?: "Chargenprüfung konnte nicht gespeichert werden.") }
        }
    }
}
