package de.ernaehrung.demo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.ai.*
import de.ernaehrung.demo.data.integration.RecipeAiExchange
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class DirectRecipeCompletion(val id: String, val requestId: String, val inputBinding: String,
    val configurationBinding: String, val reply: DirectModelReply)
internal data class DirectRecipeAiState(val loaded: Boolean = false, val mode: DirectRecipeMode = DirectRecipeMode.NANO3,
    val revision: String = "unconfigured", val keyConfigured: Boolean = false, val selectedCloudModel: String? = null,
    val cloudModels: List<GeminiCloudModel> = emptyList(), val info: DirectModelInfo = DirectModelInfo(DirectModelStatus.NOT_CHECKED),
    val busy: Boolean = false, val configurationBusy: Boolean = false, val downloadedBytes: Long? = null, val message: String? = null,
    val completed: DirectRecipeCompletion? = null) {
    val binding: String get() = "$mode:$revision:${selectedCloudModel.orEmpty()}"
    val ready get() = !busy && info.status == DirectModelStatus.READY
}

/** Private control state only, never a FoodStore writer or source authorization.
 * All clients are called on demand, and an old result cannot cross a mode/key/
 * request change. Activity recreation retains no prompt, approval or result. */
internal class DirectRecipeAiViewModel(context: Context,
    private val nano: NanoRecipeEngine = NanoRecipeEngine(), private val cloud: GeminiCloudClient = GeminiCloudClient()) : ViewModel() {
    private val store = PrivateGeminiSettings.forApp(context)
    private val mutable = MutableStateFlow(DirectRecipeAiState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    init {
        val expected = generation
        viewModelScope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { store.read() }
                if (generation == expected) applySettings(settings)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(loaded = true,
                message = "Lokale Modellkonfiguration nicht lesbar. Konfiguration verwerfen und neu einrichten.") }
        }
    }
    private fun applySettings(value: PrivateGeminiSettings.Settings) {
        mutable.value = DirectRecipeAiState(loaded = true, mode = value.mode, revision = value.revision,
            keyConfigured = value.key != null, selectedCloudModel = value.model)
    }
    private fun action(configuration: Boolean = false, block: suspend (Long) -> Unit) {
        work?.cancel(); val expected = ++generation
        mutable.value = mutable.value.copy(busy = true, configurationBusy = configuration, message = null, completed = null)
        work = viewModelScope.launch {
            try { block(expected) }
            catch (_: TimeoutCancellationException) { if (generation == expected) fail("MODEL_TIMEOUT") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: DirectModelFailure) { if (generation == expected) fail(failure.code) }
            catch (_: Exception) { if (generation == expected) fail("MODEL_OR_CONFIGURATION_FAILED") }
            finally { if (generation == expected) mutable.value = mutable.value.copy(busy = false, configurationBusy = false) }
        }
    }
    private fun fail(code: String) { mutable.value = mutable.value.copy(info = DirectModelInfo(DirectModelStatus.ERROR, code = code),
        message = "Modellaufruf nicht abgeschlossen ($code). Keine automatische Wiederholung oder andere Modellwahl, nichts gespeichert oder gebucht.") }
    fun choose(mode: DirectRecipeMode) = action(configuration = true) { expected ->
        val settings = withContext(Dispatchers.IO) { store.mode(mode) }
        if (generation == expected) applySettings(settings)
    }
    fun saveKey(key: String) = action(configuration = true) { expected ->
        val settings = withContext(Dispatchers.IO) { store.saveKey(key) }
        if (generation == expected) applySettings(settings)
    }
    fun clearConfiguration() = action(configuration = true) { expected ->
        val settings = withContext(Dispatchers.IO) { store.clear() }
        if (generation == expected) applySettings(settings)
    }
    fun selectCloudModel(id: String) {
        if (mutable.value.cloudModels.none { it.name == id }) return
        val models = mutable.value.cloudModels
        action(configuration = true) { expected ->
            val settings = withContext(Dispatchers.IO) { store.model(id) }
            if (generation == expected) {
                applySettings(settings)
                mutable.value = mutable.value.copy(cloudModels = models,
                    info = DirectModelInfo(DirectModelStatus.READY, modelName = id))
            }
        }
    }
    fun check() = action { expected ->
        val selected = mutable.value.mode
        if (selected != DirectRecipeMode.CLOUD) {
            val info = withTimeout(30_000) { nano.check(selected) }
            if (generation == expected) mutable.value = mutable.value.copy(info = info)
        } else {
            val config = withContext(Dispatchers.IO) { store.read() }
            val key = config.key ?: throw DirectModelFailure("CLOUD_KEY_REQUIRED")
            val models = withTimeout(30_000) { cloud.listModels(key) }
            if (generation == expected) {
                val id = config.model?.takeIf { saved -> models.any { it.name == saved } }
                mutable.value = mutable.value.copy(cloudModels = models, selectedCloudModel = id,
                    info = DirectModelInfo(if (id == null) DirectModelStatus.NOT_CHECKED else DirectModelStatus.READY, modelName = id),
                    message = if (id == null) "Offizielle Modellliste geladen. Gewünschtes Gemini-Modell ausdrücklich auswählen." else null)
            }
        }
    }
    fun download(approved: Boolean) {
        val mode = mutable.value.mode
        if (!approved || mode == DirectRecipeMode.CLOUD || mutable.value.info.status != DirectModelStatus.DOWNLOADABLE) return
        action { expected ->
            mutable.value = mutable.value.copy(info = DirectModelInfo(DirectModelStatus.DOWNLOADING))
            nano.download(mode) { bytes -> viewModelScope.launch {
                if (generation == expected) mutable.value = mutable.value.copy(downloadedBytes = bytes.coerceAtLeast(0))
            } }
            val info = nano.check(mode)
            if (generation == expected) mutable.value = mutable.value.copy(info = info, downloadedBytes = null)
        }
    }
    fun generate(request: RecipeAiExchange.Prepared, inputBinding: String, consent: GeminiCloudConsent?) {
        val current = mutable.value
        if (!current.ready || current.busy || request.inputBinding != inputBinding) return
        action { expected ->
            val reply = if (current.mode == DirectRecipeMode.CLOUD) {
                val config = withContext(Dispatchers.IO) { store.read() }
                if (config.revision != current.revision || config.model != current.selectedCloudModel) throw DirectModelFailure("CLOUD_CONFIGURATION_CHANGED")
                withTimeout(120_000) { cloud.generate(requireNotNull(config.key), request, inputBinding, config.revision,
                    requireNotNull(config.model), consent ?: throw DirectModelFailure("CLOUD_CONSENT_REQUIRED")) }
            } else nano.generate(current.mode, request.prompt)
            if (generation == expected && mutable.value.binding == current.binding) mutable.value = mutable.value.copy(
                completed = DirectRecipeCompletion(UUID.randomUUID().toString(), request.requestId, inputBinding, current.binding, reply),
                message = "Antwort vom ausgewählten Modell erhalten. Die unabhängige v1-Prüfung und Zutatenfreigabe folgen; keine Buchung.")
        }
    }
    fun consume() { mutable.value = mutable.value.copy(completed = null) }
    fun cancelRequest() {
        // Typing/closing a prompt cannot strand the initial settings read or
        // cancel an already requested atomic mode/key change halfway through.
        if (!mutable.value.loaded || mutable.value.configurationBusy) return
        work?.cancel(); generation++
        mutable.value = mutable.value.copy(busy = false, completed = null, downloadedBytes = null)
    }
}
