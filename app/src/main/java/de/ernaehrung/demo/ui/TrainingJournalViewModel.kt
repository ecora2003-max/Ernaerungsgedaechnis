package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.FoodStore
import de.ernaehrung.demo.data.local.TrainingJournalSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

internal data class TrainingJournalUiState(val snapshot: TrainingJournalSnapshot? = null, val readError: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
internal class TrainingJournalViewModel(store: FoodStore) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    val state = refresh.flatMapLatest {
        store.trainingJournal().map { TrainingJournalUiState(it) }
            .onStart { emit(TrainingJournalUiState()) }
            .catch { emit(TrainingJournalUiState(readError = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainingJournalUiState())
    fun retry() { refresh.value += 1L }
}
