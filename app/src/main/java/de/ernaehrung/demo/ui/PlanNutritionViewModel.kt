package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

internal data class PlanNutritionUiState(val report: PlanNutritionReport? = null, val readError: Boolean = false)
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlanNutritionViewModel(store: FoodStore, startsOn: String, endsOn: String) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    val state = refresh.flatMapLatest { store.watchPlannedNutrition(startsOn, endsOn).map { PlanNutritionUiState(it) }
        .onStart { emit(PlanNutritionUiState()) }.catch { emit(PlanNutritionUiState(readError = true)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), PlanNutritionUiState())
    fun retry() { refresh.value += 1L }
}
