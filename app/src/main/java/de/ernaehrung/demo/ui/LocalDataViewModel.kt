package de.ernaehrung.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.recognition.*
import android.net.Uri
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

data class LocalDataState(val loading: Boolean = true, val catalog: List<CatalogRow> = emptyList(),
    val batches: List<BatchRow> = emptyList(), val locations: List<StorageLocationEntity> = emptyList(),
    val movements: List<MovementRow> = emptyList(), val error: String? = null,
    val recipes: List<RecipeVersionEntity> = emptyList(), val ingredients: List<IngredientRow> = emptyList(),
    val versions: List<ProductVersionEntity> = emptyList(), val operations: List<OperationRow> = emptyList())
data class SaveState(val busy: Boolean = false, val message: String? = null, val succeeded: Boolean = false)
data class DayUiState(val date: String, val report: DayNutrition? = null, val error: String? = null)
data class RecipeNutritionUiState(val report: NutritionResult? = null, val error: String? = null)
data class PlanningUiState(val report: PlanningReport? = null, val error: String? = null)
data class ExecutionUiState(val view: PlanExecutionView? = null, val error: String? = null)
data class ShoppingUiState(val report: ShoppingReport? = null, val error: String? = null)
data class ExpenseUiState(val report: ExpenseReport? = null, val error: String? = null)
data class RecognitionUiState(val data: RecognitionData? = null, val error: String? = null)
data class AliasUiState(val busy: Boolean = false, val match: AliasMatch? = null, val error: String? = null, val queryText: String = "")
data class LookupUiState(val busy: Boolean = false, val result: ProductLookup? = null, val error: String? = null)
data class ReceiptReviewUiState(val review: ReceiptReview? = null, val resolved: Boolean = false, val error: String? = null)
/** A host-bound preview and the result of its separate, explicit UI action. */
internal data class RecipeDraftConsentUiState(
    val review: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Review? = null,
    val busy: Boolean = false,
    val recordedDigest: String? = null,
    val sessionId: String? = null,
    val attemptVersion: Int = 0,
    val error: String? = null
)

data class PhotoUiState(val busy: Boolean = false, val codes: List<String> = emptyList(), val text: PhotoText? = null,
    val method: String? = null, val message: String? = null)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalDataViewModel(
    private val store: FoodStore,
    private val productLookup: ProductLookupGateway = OpenFoodFactsClient(),
    private val pendingKeyStore: RecipeDraftPendingKeyStore = EphemeralRecipeDraftPendingKeyStore
) : ViewModel() {
    val recognition: StateFlow<RecognitionUiState> = store.watchRecognition().map { RecognitionUiState(it) }
        .catch { emit(RecognitionUiState(error = "Erkennungsdaten konnten nicht gelesen werden: ${it.message}")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecognitionUiState())
    private val selectedScan = MutableStateFlow("")
    val selectedReceiptScan = selectedScan.asStateFlow()
    val receiptReview: StateFlow<ReceiptReviewUiState> = selectedScan.flatMapLatest { id ->
        if (id.isBlank()) flowOf(ReceiptReviewUiState()) else store.watchRecognition().map { data ->
            if (data.scans.find { it.scan.operationId == id }?.resolution != null) ReceiptReviewUiState(resolved = true)
            else ReceiptReviewUiState(store.reviewReceiptScan(id))
        }.onStart { emit(ReceiptReviewUiState()) }.catch { emit(ReceiptReviewUiState(error = "Bonprüfung konnte nicht gelesen werden: ${it.message}")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptReviewUiState())
    fun showReceiptReview(id: String) { selectedScan.value = id }
    private val aliasResult = MutableStateFlow(AliasUiState())
    val aliasLookup = aliasResult.asStateFlow()
    private var aliasJob: kotlinx.coroutines.Job? = null
    fun checkAlias(text: String, merchant: String?, kind: AliasKind) {
        aliasJob?.cancel(); aliasResult.value = AliasUiState(busy = true, queryText = text.trim())
        aliasJob = viewModelScope.launch {
            try {
                val result = store.matchAlias(text, merchant, kind)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                aliasResult.value = AliasUiState(match = result, queryText = text.trim())
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                aliasResult.value = AliasUiState(error = e.message ?: "Zuordnung fehlgeschlagen.")
            }
        }
    }
    fun clearAliasResult() { aliasJob?.cancel(); aliasResult.value = AliasUiState() }
    private val lookupResult = MutableStateFlow(LookupUiState())
    val externalLookup = lookupResult.asStateFlow()
    private var lookupJob: kotlinx.coroutines.Job? = null
    fun lookupProduct(code: String) {
        lookupJob?.cancel(); lookupResult.value = LookupUiState(busy = true)
        lookupJob = viewModelScope.launch {
            try {
                val result = productLookup.lookup(Gtin.parse(code).printed)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lookupResult.value = LookupUiState(result = result)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lookupResult.value = LookupUiState(error = "Produktabfrage: ${e.message ?: "Keine Verbindung."}")
            }
        }
    }
    fun clearProductLookup() { lookupJob?.cancel(); lookupResult.value = LookupUiState() }
    private val photoResult = MutableStateFlow(PhotoUiState())
    val photo = photoResult.asStateFlow()
    fun photoError(message: String) { photoResult.value = PhotoUiState(message = message) }
    fun analyzePhoto(uri: Uri, forReceipt: Boolean, method: String, gateway: PhotoRecognitionGateway, cleanup: () -> Unit = {}) {
        if (photoResult.value.busy) { cleanup(); return }
        photoResult.value = PhotoUiState(busy = true)
        viewModelScope.launch {
            try {
                photoResult.value = if (forReceipt) PhotoUiState(text = gateway.receiptText(uri), method = method)
                else gateway.barcodes(uri).let { codes -> PhotoUiState(codes = codes,
                    message = if (codes.isEmpty()) "Kein gültiger Produktbarcode lesbar. Näher und schärfer fotografieren oder Ziffern eingeben." else "Barcode auswählen und im Katalog prüfen.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { photoResult.value = PhotoUiState(message = "Bilderkennung: ${e.message ?: "Bild nicht lesbar."}") }
            finally { cleanup() }
        }
    }
    private val expenseQuery = MutableStateFlow(java.time.LocalDate.now().let {
        ExpenseScope(it.withDayOfMonth(1).toString(), it.toString()) to 0L
    })
    val expenses: StateFlow<ExpenseUiState> = expenseQuery.flatMapLatest { (scope, _) ->
        store.watchExpenses(scope).map { ExpenseUiState(it) }.onStart { emit(ExpenseUiState()) }
            .catch { emit(ExpenseUiState(error = "Ausgaben konnten nicht gelesen werden: ${it.message}")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpenseUiState())
    fun showExpenses(start: String, end: String) {
        expenseQuery.value = ExpenseScope(start.trim(), end.trim()) to expenseQuery.value.second + 1
    }
    private val shoppingQuery = MutableStateFlow(java.time.LocalDate.now().let {
        ShoppingScope(it.toString(), it.plusDays(6).toString(), it.toString()) to 0L
    })
    val shopping: StateFlow<ShoppingUiState> = shoppingQuery.flatMapLatest { (query, _) ->
        store.watchShopping(query).map { ShoppingUiState(it) }.onStart { emit(ShoppingUiState()) }
            .catch { emit(ShoppingUiState(error = "Einkaufsliste konnte nicht gelesen werden: ${it.message}")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingUiState())
    fun showShopping(start: String, days: Long, purchase: String, budgetId: String?) {
        val first = start.trim()
        val last = runCatching { java.time.LocalDate.parse(first).plusDays(days - 1).toString() }.getOrDefault(first)
        shoppingQuery.value = ShoppingScope(first, last, purchase.trim(), budgetId) to shoppingQuery.value.second + 1
    }
    private val chosenExecutionEntry = MutableStateFlow("")
    val selectedExecutionEntry = chosenExecutionEntry.asStateFlow()
    val execution: StateFlow<ExecutionUiState> = chosenExecutionEntry.flatMapLatest { entry ->
        if (entry.isBlank()) flowOf(ExecutionUiState()) else store.watchPlanExecution(entry).map { ExecutionUiState(it) }
            .onStart { emit(ExecutionUiState()) }.catch { emit(ExecutionUiState(error = "Ist-Zuordnung konnte nicht gelesen werden: ${it.message}")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExecutionUiState())
    fun showExecution(entryId: String) { chosenExecutionEntry.value = entryId }
    private val planRange = MutableStateFlow(java.time.LocalDate.now().let { it.toString() to it.plusDays(6).toString() })
    val planning: StateFlow<PlanningUiState> = planRange.flatMapLatest { (start, end) ->
        store.watchPlanning(start, end).map { PlanningUiState(it) }.onStart { emit(PlanningUiState()) }
            .catch { emit(PlanningUiState(error = "Planung konnte nicht gelesen werden: ${it.message}")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningUiState())
    fun showPlanning(start: String, days: Long) {
        val first = start.trim()
        val end = runCatching { java.time.LocalDate.parse(first).plusDays(days - 1).toString() }.getOrDefault(first)
        planRange.value = first to end
    }
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

    // A session is created only from separately reviewed host catalog facts.
    // A model digest, a caller-supplied Boolean or a recomposition cannot create
    // a durable consent. No recipe/stock/consumption write occurs in this flow.
    private data class PendingRecipeDraft(
        val operationKey: String,
        /** Ephemeral gesture identity: never persisted or supplied by the draft. */
        val sessionId: String,
        val json: String,
        val request: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Request,
        val confirmations: Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        val reviewedStates: Map<String, de.ernaehrung.demo.data.integration.RecipeDraftHostGate.State>,
        val review: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Review
    )
    private val recipeDraftConsent = MutableStateFlow(RecipeDraftConsentUiState())
    internal val recipeDraftConsentState = recipeDraftConsent.asStateFlow()
    private var pendingRecipeDraft: PendingRecipeDraft? = null
    private var recipePreviewJob: kotlinx.coroutines.Job? = null
    private var recipeConsentJob: kotlinx.coroutines.Job? = null

    /** A changed import or independent evidence selection invalidates a previous preview.
     * An already committed consent remains in the durable ledger and cannot be undone here.
     */
    internal fun clearRecipeDraftConsent() {
        // An explicit consent already in-flight must finish or fail transactionally.
        // Editing cannot silently cancel an action that may have committed to Room.
        if (recipeConsentJob?.isActive == true) return
        recipePreviewJob?.cancel()
        pendingRecipeDraft = null
        // Explicit editing discards an unconfirmed key, not a recorded Room consent.
        pendingKeyStore.discard()
        recipeDraftConsent.value = RecipeDraftConsentUiState()
    }

    /** Caller must gather independently confirmed mapping, basis, state and allergen facts.
     * This does not accept generator-provided confirmations or auto-enable a draft.
     */
    internal fun prepareRecipeDraftConsent(
        json: String,
        request: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Request,
        confirmations: Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        reviewedStates: Map<String, de.ernaehrung.demo.data.integration.RecipeDraftHostGate.State>
    ) {
        // Never cancel a consent that may already have committed inside Room.
        // A second programmatic preview request must wait for the first transaction.
        if (recipeConsentJob?.isActive == true) return
        recipePreviewJob?.cancel()
        pendingRecipeDraft = null
        recipeDraftConsent.value = RecipeDraftConsentUiState(busy = true)
        val immutableFacts = confirmations.mapValues { (_, line) ->
            line.copy(allergens = line.allergens.toMap())
        }
        val immutableRequest = request.copy(confirmedRestrictions = request.confirmedRestrictions.toSet())
        recipePreviewJob = viewModelScope.launch {
            try {
                val review = store.previewRecipeDraftV1(json, immutableRequest,
                    immutableFacts, reviewedStates.toMap())
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val ready = review.status ==
                    de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
                    review.input != null && review.approvalBasisDigest != null
                // A fresh host revalidation may discover a durable consent from
                // before process death. Never silently record a second checkpoint.
                val recordedKey = if (ready)
                    store.recordedRecipeDraftV1ConsentKey(requireNotNull(review.approvalBasisDigest))
                else null
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (ready && recordedKey == null) {
                    // Only an opaque key survives process death. The new UI session
                    // must still require a fresh, independent explicit gesture.
                    val key = pendingKeyStore.resumeOrCreate(requireNotNull(review.approvalBasisDigest))
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    pendingRecipeDraft = PendingRecipeDraft(
                        key, java.util.UUID.randomUUID().toString(), json, immutableRequest,
                        immutableFacts, reviewedStates.toMap(), review)
                } else if (recordedKey != null) {
                    // A durable Room checkpoint wins over a stale pending-key file.
                    runCatching {
                        pendingKeyStore.finish(requireNotNull(review.approvalBasisDigest), recordedKey)
                    }
                }
                recipeDraftConsent.value = RecipeDraftConsentUiState(review = review,
                    recordedDigest = if (recordedKey != null) review.approvalBasisDigest else null,
                    sessionId = pendingRecipeDraft?.sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                recipeDraftConsent.value = RecipeDraftConsentUiState(error =
                    "Rezeptvorschau konnte nicht geprüft werden: ${e.message}")
            }
        }
    }

    /** Invoked exclusively by the explicit RecipeDraftConsentReview callback.
     * Revalidation and the durable operation ledger remain inside FoodStore.
     * This is UI-action provenance, not an authenticated human identity.
     */
    internal fun confirmRecipeDraftConsent(approvalBasisDigest: String, sessionId: String?) {
        val session = pendingRecipeDraft ?: return
        if (recipeDraftConsent.value.busy ||
            recipeDraftConsent.value.recordedDigest != null ||
            session.review.approvalBasisDigest != approvalBasisDigest ||
            session.sessionId != sessionId) return
        recipeDraftConsent.value = recipeDraftConsent.value.copy(busy = true, error = null)
        recipeConsentJob = viewModelScope.launch {
            try {
                val recorded = store.recordReviewedRecipeDraftV1Consent(
                    session.operationKey, session.json, session.request,
                    session.confirmations, session.reviewedStates, session.review, true)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // Room is authoritative. Cleanup failure cannot turn a committed
                // consent into a false UI failure or trigger an automatic retry.
                runCatching { pendingKeyStore.finish(recorded, session.operationKey) }
                if (pendingRecipeDraft === session) {
                    pendingRecipeDraft = null
                    recipeDraftConsent.value = RecipeDraftConsentUiState(
                        review = session.review, recordedDigest = recorded)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (pendingRecipeDraft === session) {
                    recipeDraftConsent.value = RecipeDraftConsentUiState(
                        review = session.review, sessionId = session.sessionId,
                        attemptVersion = recipeDraftConsent.value.attemptVersion + 1,
                        error = "Bestätigung nicht gespeichert: ${e.message}")
                }
            }
        }
    }
}
