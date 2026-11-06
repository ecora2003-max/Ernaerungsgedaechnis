package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class NutritionGoalUiState(val snapshot: NutritionGoalSnapshot? = null, val readError: Boolean = false)
internal data class NutritionGoalSaveState(val busy: Boolean = false, val outcome: NutritionGoalOutcome? = null,
    val error: String? = null, val action: String? = null)
@OptIn(ExperimentalCoroutinesApi::class)
internal class NutritionGoalViewModel(private val store: FoodStore) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    val state = refresh.flatMapLatest { store.watchNutritionGoals().map { NutritionGoalUiState(it) }
        .onStart { emit(NutritionGoalUiState()) }.catch { emit(NutritionGoalUiState(readError = true)) } }
        // A suspended diary observer must not replay personal values erased on
        // another screen. Release its private snapshot once sharing stops.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), NutritionGoalUiState())
    private val saving = MutableStateFlow(NutritionGoalSaveState())
    val saveState = saving.asStateFlow()
    fun retry() { refresh.value += 1L }
    fun clearResult() { if (!saving.value.busy) saving.value = NutritionGoalSaveState() }
    private fun save(kind: String, action: suspend () -> NutritionGoalOutcome) {
        if (saving.value.busy) return
        saving.value = NutritionGoalSaveState(busy = true)
        viewModelScope.launch {
            try { saving.value = NutritionGoalSaveState(outcome = action(), action = kind) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalArgumentException) { saving.value = NutritionGoalSaveState(error = failure.message ?: "Zielangaben prüfen.") }
            catch (_: Exception) { saving.value = NutritionGoalSaveState(error = "Zielangaben gerade nicht speicherbar. Vorhandene Daten bleiben erhalten.") }
        }
    }
    fun propose(key: String, input: NutritionGoalDraftInput) = save("PROPOSE") { store.proposeNutritionGoals(key, input, true) }
    fun confirm(key: String, proposalId: String, binding: String, confirmed: Boolean) = save("CONFIRM") {
        store.confirmNutritionGoals(key, proposalId, binding, confirmed)
    }
    fun erase(key: String, token: String, confirmed: Boolean) = save("ERASE") { store.eraseNutritionGoals(key, token, confirmed) }
}
