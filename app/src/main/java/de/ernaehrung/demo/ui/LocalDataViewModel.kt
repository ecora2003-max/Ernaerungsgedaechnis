package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LocalDataState(val loading: Boolean = true, val catalog: List<CatalogRow> = emptyList(),
    val batches: List<BatchRow> = emptyList(), val locations: List<StorageLocationEntity> = emptyList(),
    val movements: List<MovementRow> = emptyList(), val error: String? = null,
    val recipes: List<RecipeVersionEntity> = emptyList(), val ingredients: List<IngredientRow> = emptyList(),
    val versions: List<ProductVersionEntity> = emptyList(), val operations: List<OperationRow> = emptyList())
data class SaveState(val busy: Boolean = false, val message: String? = null, val succeeded: Boolean = false)
data class DayUiState(val date: String, val report: DayNutrition? = null, val error: String? = null)
data class RecipeNutritionUiState(val report: NutritionResult? = null, val error: String? = null)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalDataViewModel(private val store: FoodStore) : ViewModel() {
    private val chosenDay = MutableStateFlow(java.time.LocalDate.now().toString())
    val selectedDay = chosenDay.asStateFlow()
    val daily: StateFlow<DayUiState> = chosenDay.flatMapLatest { date ->
        store.watchDay(date).map { DayUiState(date, it) }.onStart { emit(DayUiState(date)) }
            .catch { emit(DayUiState(date, error = "Tag konnte nicht gelesen werden: ${it.message}")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(chosenDay.value))
    fun showDay(date: String) { chosenDay.value = date.trim() }
    private val recipeReports = mutableMapOf<String, StateFlow<RecipeNutritionUiState>>()
    fun recipeNutrition(id: String): StateFlow<RecipeNutritionUiState> = recipeReports.getOrPut(id) {
        store.watchRecipeNutrition(id).map { RecipeNutritionUiState(it) }
            .catch { emit(RecipeNutritionUiState(error = "Rezept konnte nicht berechnet werden: ${it.message}")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeNutritionUiState())
    }
    private val inventory = combine(store.catalog(), store.batches(), store.locations(), store.movements()) { products, batches, locations, movements ->
        LocalDataState(false, products, batches, locations, movements)
    }
    val state: StateFlow<LocalDataState> = combine(inventory, store.recipes(), store.recipeIngredients(), store.versions(), store.operations()) { stock, recipes, ingredients, versions, operations ->
        stock.copy(recipes = recipes, ingredients = ingredients, versions = versions, operations = operations)
    }.catch { emit(LocalDataState(loading = false, error = "Datenbank konnte nicht gelesen werden. Keine Neuinitialisierung oder Löschung: ${it.message}")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDataState())
    private val saving = MutableStateFlow(SaveState())
    val saveState = saving.asStateFlow()
    fun newOperation() { if (!saving.value.busy) saving.value = SaveState() }
    suspend fun operationDetails(id: String) = store.operationDetails(id)

    fun save(operationId: String, action: suspend FoodStore.() -> String) {
        if (saving.value.busy || saving.value.succeeded) return
        saving.value = SaveState(busy = true)
        viewModelScope.launch {
            try {
                val result = store.action()
                saving.value = SaveState(message = "Gespeichert im lokalen Testbereich.\nVorgang: $operationId\nReferenz: $result", succeeded = true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saving.value = SaveState(message = "Nicht gespeichert. ${error.message}\nVorgang: $operationId")
            }
        }
    }
}
