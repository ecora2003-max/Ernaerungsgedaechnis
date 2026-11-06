package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.DataQualitySnapshot
import de.ernaehrung.demo.data.local.FoodStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

internal data class DataQualityUiState(val snapshot: DataQualitySnapshot? = null, val readError: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
internal class DataQualityViewModel(store: FoodStore) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    val state = refresh.flatMapLatest {
        store.dataQuality().map { DataQualityUiState(it) }
            .onStart { emit(DataQualityUiState()) }
            .catch { emit(DataQualityUiState(readError = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataQualityUiState())
    fun refresh() { refresh.value += 1L }
}
