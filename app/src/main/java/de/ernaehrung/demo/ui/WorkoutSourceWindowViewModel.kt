package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.fitness.WorkoutSourceTarget
import de.ernaehrung.demo.data.local.FoodStore
import de.ernaehrung.demo.data.local.WorkoutSourceWindowSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

internal data class WorkoutSourceWindowUiState(val snapshot: WorkoutSourceWindowSnapshot? = null,
    val readError: Boolean = false)

/** Local observation only. No source SDK, OS permission or writer dependency. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WorkoutSourceWindowViewModel(store: FoodStore, target: WorkoutSourceTarget) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    val state = refresh.flatMapLatest {
        store.watchWorkoutSourceWindow(target).map { WorkoutSourceWindowUiState(it) }
            .onStart { emit(WorkoutSourceWindowUiState()) }
            .catch { emit(WorkoutSourceWindowUiState(readError = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutSourceWindowUiState())
    fun retry() { refresh.value += 1 }
}
