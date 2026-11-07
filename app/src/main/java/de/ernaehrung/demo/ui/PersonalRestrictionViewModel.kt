package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class PersonalRestrictionUiState(val snapshot: PersonalRestrictionSnapshot? = null, val readError: Boolean = false)
internal data class PersonalRestrictionSaveState(val busy: Boolean = false, val outcome: PersonalRestrictionOutcome? = null,
    val kind: String? = null, val error: String? = null)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class PersonalRestrictionViewModel(private val store: FoodStore,
    private val committed: (String,PersonalRestrictionOutcome) -> Unit = { _,_ -> }) : ViewModel() {
    private val reading = MutableStateFlow(PersonalRestrictionUiState())
    val state = reading.asStateFlow()
    private val saving = MutableStateFlow(PersonalRestrictionSaveState())
    val saveState = saving.asStateFlow()
    private var revisionFloor = 0
    private val refresh = MutableStateFlow(0L)
    init { viewModelScope.launch { refresh.flatMapLatest {
        store.watchPersonalRestrictions().map { PersonalRestrictionUiState(it) }
            .onStart { emit(PersonalRestrictionUiState()) }
            .catch { emit(PersonalRestrictionUiState(readError = true)) }
    }.collect { if (it.snapshot != null) accept(it.snapshot) else reading.value = it } } }
    private fun accept(data: PersonalRestrictionSnapshot) {
        val revision = data.latest?.revision ?: 0
        if (revision >= revisionFloor) { revisionFloor = revision; reading.value = PersonalRestrictionUiState(data) }
    }
    fun retry() { refresh.value += 1L }
    fun clearResult() { if (!saving.value.busy) saving.value = PersonalRestrictionSaveState() }
    private fun save(kind: String, action: suspend () -> PersonalRestrictionOutcome) {
        if (saving.value.busy) return
        saving.value = PersonalRestrictionSaveState(busy = true)
        viewModelScope.launch {
            try {
                val result = action()
                revisionFloor = maxOf(revisionFloor,result.revision)
                if (kind != PersonalRestrictionRules.PROPOSE) reading.value = PersonalRestrictionUiState()
                committed(kind,result)
                // A completed write stays successful even if the subsequent read fails.
                saving.value = PersonalRestrictionSaveState(outcome = result,kind = kind)
                retry()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalArgumentException) { saving.value = PersonalRestrictionSaveState(error = failure.message ?: "Vorgaben prüfen.") }
            catch (_: Exception) { saving.value = PersonalRestrictionSaveState(error = "Vorgang gerade nicht abschließend möglich. Unverändert mit derselben Kennung erneut prüfen.") }
        }
    }
    fun propose(key: String, input: PersonalRestrictionInput) = save(PersonalRestrictionRules.PROPOSE) { store.proposePersonalRestrictions(key,input,true) }
    fun confirm(key: String, id: String, binding: String, confirmed: Boolean) = save(PersonalRestrictionRules.CONFIRM) {
        store.confirmPersonalRestrictions(key,id,binding,confirmed)
    }
    fun erase(key: String, token: String, confirmed: Boolean) = save(PersonalRestrictionRules.ERASE) { store.erasePersonalRestrictions(key,token,confirmed) }
}
