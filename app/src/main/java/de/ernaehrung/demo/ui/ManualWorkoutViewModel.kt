package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.fitness.ManualWorkoutInput
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ManualWorkoutUiState(val key: String? = null, val keyChecked: Boolean = false,
    val run: ManualWorkoutRunEntity? = null, val busy: Boolean = false, val message: String? = null,
    val purgeRevision: Int = 0)

internal class ManualWorkoutViewModel(private val store: FoodStore) : ViewModel() {
    private val mutable = MutableStateFlow(ManualWorkoutUiState())
    val state = mutable.asStateFlow()
    private var action: Job? = null
    private var observer: Job? = null
    fun openOperation(key: String) {
        if (state.value.key == key && (state.value.keyChecked || state.value.busy)) return
        action?.cancel()
        observer?.cancel()
        mutable.update { it.copy(key = key, keyChecked = false, run = null, busy = true, message = null) }
        action = viewModelScope.launch {
            try {
                val run = withContext(Dispatchers.IO) { store.beginManualWorkout(key) }
                if (state.value.key == key) mutable.update { it.copy(run = run, keyChecked = true, busy = false) }
                observer = viewModelScope.launch {
                    store.watchManualWorkoutRun(key).catch {
                        if (state.value.key == key) mutable.update { it.copy(keyChecked = false, message = "Vorgangsstatus erneut prüfen.") }
                    }.collect { current ->
                        if (state.value.key == key) mutable.update { it.copy(run = current, keyChecked = current != null) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.key == key) mutable.update { it.copy(busy = false, message = "Vorgang noch nicht lesbar. Vor dem Speichern erneut prüfen.") }
            }
        }
    }
    fun save(key: String, input: ManualWorkoutInput, previousId: String?, legacyId: String?, confirmed: Boolean) {
        if (state.value.key != key || !state.value.keyChecked || state.value.busy || state.value.run?.kind != "OPEN") return
        val frozen = input.copy(sets = input.sets.toList())
        mutable.update { it.copy(busy = true, message = null) }
        action = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveManualWorkout(key, frozen, previousId, legacyId, confirmed) }
                val run = withContext(Dispatchers.IO) { store.manualWorkoutRun(key) }
                if (state.value.key == key) mutable.update { it.copy(run = run, message = "Trainingsversion lokal gespeichert.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (state.value.key == key) mutable.update { it.copy(keyChecked = false, message = if (failure is IllegalArgumentException) failure.message
                    else "Training konnte nicht gespeichert werden. Mit demselben Vorgang erneut prüfen.") }
            } finally { if (state.value.key == key) mutable.update { it.copy(busy = false) } }
        }
    }
    fun purge(confirmed: Boolean) {
        if (state.value.busy) return
        val key = "training-purge-${UUID.randomUUID()}"
        mutable.update { it.copy(busy = true, message = null) }
        action = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.purgeManualWorkouts(key, confirmed) }
                mutable.update { it.copy(run = null, key = null, keyChecked = false, purgeRevision = it.purgeRevision + 1,
                    message = "Manuelle und frühere Trainingshistorie gelöscht. Samsung-Health-Kopie bleibt erhalten.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(message = "Trainingshistorie konnte nicht gelöscht werden.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
