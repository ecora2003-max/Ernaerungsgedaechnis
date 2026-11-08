package de.ernaehrung.demo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.offers.*
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LiveOfferSelection(val offer: LiveOffer, val scope: LiveOfferScope)
internal data class LiveOffersUiState(val busy: Boolean = false, val scope: LiveOfferScope? = null,
    val result: LiveRefreshResult? = null, val message: String? = null, val connected: Boolean = false,
    val discovery: KnusprToolDiscovery? = null, val authorizationUrl: URI? = null,
    val automatic: Boolean = false, val selectionLoaded: Boolean = false, val comparison: LivePriceComparison? = null,
    val reference: LiveOfferSelection? = null, val authorizedEvidenceRef: String? = null)

/** No FoodStore/Room writer and no health/profile export. HTTP happens only on
 * explicit connection/selection; unapproved catalogue sources never start IO. */
internal class LiveOffersViewModel(context: Context, private val runtime: LiveOfferRuntime = LiveOfferRuntime(emptyMap(),
    journal = PrivateOfferSettings.forApp(context)),
    private val http: OfferHttp = HttpsOfferHttp(), private val comparison: LiveOfferComparison = LiveOfferComparison(
        sourceStillPermitted = { scope, offer -> runtime.policy(scope)?.sourceId == offer.sourceId })) : ViewModel() {
    private val app = context.applicationContext
    private val privateStore = PrivateKnusprStore.forApp(app)
    private val settings = PrivateOfferSettings.forApp(app)
    private val mutable = MutableStateFlow(LiveOffersUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var authTicket: Long? = null
    init {
        val expected = generation
        viewModelScope.launch {
            val selection = withContext(Dispatchers.IO) { runCatching { settings.selection() }.getOrNull() }
            mutable.value = if (generation == expected) mutable.value.copy(scope = selection?.scope,
                automatic = selection?.automatic == true, selectionLoaded = true)
                else mutable.value.copy(selectionLoaded = true)
        }
    }
    fun selectProvider() {
        work?.cancel(); val expected = ++generation; val ticket = authTicket; authTicket = null
        mutable.value = mutable.value.copy(scope = null, result = null, discovery = null, message = null,
            automatic = false, comparison = null, authorizationUrl = null, busy = true, authorizedEvidenceRef = null)
        work = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { ticket?.let(privateStore::cancelAttempt); settings.clearSelection() } }
            finally { if (generation == expected) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun refresh(scope: LiveOfferScope, automatic: Boolean = false) {
        if (automatic && (mutable.value.busy || !mutable.value.automatic)) return
        work?.cancel(); val expected = ++generation
        val keepAutomatic = mutable.value.scope == scope && mutable.value.automatic
        mutable.value = mutable.value.copy(scope = scope, busy = true, result = null, message = null, automatic = keepAutomatic,
            comparison = null, authorizedEvidenceRef = null)
        work = viewModelScope.launch {
            try {
                val (result, evidence) = withContext(Dispatchers.IO) {
                    if (!automatic) settings.select(scope, keepAutomatic)
                    val result = runtime.refresh(scope, automatic).also {
                        if (it.status in setOf(LiveRefreshStatus.BLOCKED, LiveRefreshStatus.FAILED)) settings.select(scope, false)
                    }
                    result to runtime.policy(scope)?.evidenceRef
                }
                if (generation == expected) mutable.value = mutable.value.copy(busy = false, result = result,
                    authorizedEvidenceRef = evidence,
                    automatic = keepAutomatic && result.status !in setOf(LiveRefreshStatus.BLOCKED, LiveRefreshStatus.FAILED),
                    message = when {
                        result.fromCache -> "Letzter erlaubter Stand aus dieser Sitzung. Keine erfolgreiche neue Abfrage; Gültigkeit und Preisstand prüfen."
                        result.status == LiveRefreshStatus.LOADED -> "Abfrage abgeschlossen. Keine Buchung erzeugt."
                        result.status == LiveRefreshStatus.THROTTLED -> "Erlaubter Abrufabstand noch nicht erreicht. Nächster möglicher Abruf: ${result.refreshAfter}."
                        else -> "Keine freigegebene aktuelle Angebotsübernahme. Vorhandene Lebensmittelbuchungen bleiben erhalten."
                    })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false, message = "Auswahl oder Quelle konnte nicht geprüft werden.") }
        }
    }
    fun setAutomatic(enabled: Boolean) {
        if (mutable.value.busy) return
        val scope = mutable.value.scope ?: return
        val expected = ++generation
        mutable.value = mutable.value.copy(busy = true)
        work = viewModelScope.launch {
            try {
                val accepted = withContext(Dispatchers.IO) {
                    val allowed = !enabled || runtime.policy(scope)?.minimumRefreshSeconds != null
                    if (allowed) settings.select(scope, enabled)
                    allowed
                }
                if (generation == expected) mutable.value = mutable.value.copy(busy = false,
                    automatic = enabled && accepted, message = when {
                        !accepted -> "Automatik gesperrt: freigegebene Quelle, genauer Geltungsbereich und erlaubter Abrufrhythmus fehlen. Kein Netzabruf gestartet."
                        enabled -> "Automatische Aktualisierung bei sichtbarer Angebotsseite eingeschaltet. Keine Hintergrundabfragen."
                        else -> "Automatische Aktualisierung ausgeschaltet."
                    })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false, automatic = false,
                message = "Aktualisierungseinstellung konnte nicht sicher gespeichert werden.") }
        }
    }
    suspend fun automaticTick() {
        val expected = generation; val value = mutable.value
        if (!value.selectionLoaded || value.busy) return
        // Reauthorize visible prices/reference even with automatic HTTP disabled.
        val validBatch = withContext(Dispatchers.IO) {
            value.result?.batch?.let { runtime.policy(it.scope)?.sourceId == it.sourceId } ?: true
        }
        val validReference = withContext(Dispatchers.IO) {
            value.reference?.let { runtime.policy(it.scope)?.let { grant -> grant.cachePermitted && grant.sourceId == it.offer.sourceId } == true } ?: true
        }
        if (expected != generation || mutable.value.busy) return
        if (!validBatch || !validReference) mutable.value = mutable.value.copy(
            result = value.result.takeIf { validBatch }, reference = value.reference.takeIf { validReference }, comparison = null,
            authorizedEvidenceRef = value.authorizedEvidenceRef.takeIf { validBatch },
            message = "Quellfreigabe oder Geltungsbereich geändert. Nicht mehr freigegebene Sitzungsdaten verworfen.")
        val current = mutable.value
        if (current.result?.refreshAfter?.let { java.time.Instant.now() < it } == true) return
        if (current.automatic) current.scope?.let { refresh(it, automatic = true) }
    }
    fun compare(targetId: String, alternativeId: String) {
        val value = mutable.value
        val scope = value.scope ?: return
        val batch = value.result?.batch ?: return
        if (value.busy) return
        val target = batch.offers.singleOrNull { it.id == targetId } ?: return
        val alternative = batch.offers.singleOrNull { it.id == alternativeId } ?: return
        mutable.value = value.copy(comparison = comparison.compare(target, scope, alternative, scope, java.time.Instant.now()))
    }
    fun rememberReference(id: String) {
        val value = mutable.value
        val scope = value.scope ?: return
        val offer = value.result?.batch?.offers?.singleOrNull { it.id == id } ?: return
        if (value.result.cachePermitted != true) {
            mutable.value = value.copy(message = "Die Quelle erlaubt kein Behalten für einen Anbieterwechsel. Vergleich innerhalb der geladenen Liste bleibt möglich.")
            return
        }
        mutable.value = value.copy(reference = LiveOfferSelection(offer, scope), comparison = null,
            message = "Referenz nur für diese Sitzung gewählt. Bei jedem Vergleich werden Quelle, Gültigkeit und Artikel erneut geprüft.")
    }
    fun forgetReference() { mutable.value = mutable.value.copy(reference = null, comparison = null) }
    fun compareWithReference(alternativeId: String) {
        val value = mutable.value
        val target = value.reference ?: return
        val scope = value.scope ?: return
        val alternative = value.result?.batch?.offers?.singleOrNull { it.id == alternativeId } ?: return
        if (!value.busy) mutable.value = value.copy(comparison = comparison.compare(target.offer, target.scope, alternative, scope, java.time.Instant.now()))
    }
    fun connect(confirmed: Boolean) {
        if (!confirmed || mutable.value.busy) return
        val expected = ++generation
        mutable.value = mutable.value.copy(busy = true, authorizationUrl = null, message = null)
        work = viewModelScope.launch {
            try {
                val ticket = withContext(Dispatchers.IO) { privateStore.beginAttempt() }
                authTicket = ticket
                val oauth = KnusprOAuth(http)
                val server = oauth.discover()
                val (pending, authorization) = oauth.begin(app.packageName, server)
                val retained = withContext(Dispatchers.IO) { privateStore.stage(ticket, pending) }
                if (generation == expected && retained) mutable.value = mutable.value.copy(busy = false, connected = false, authorizationUrl = authorization)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: OfferSourceFailure) { if (generation == expected) mutable.value = mutable.value.copy(busy = false,
                message = when (failure.code) {
                    OfferSourceFailureCode.ACCESS_BLOCKED -> "Knuspr blockiert diesen Zugriff. Kein automatischer erneuter Versuch; keine Umgehung. Anmeldung später über den autorisierten Geräteweg prüfen."
                    OfferSourceFailureCode.RATE_LIMITED -> "Knuspr begrenzt die Abfragen. Später erneut versuchen."
                    else -> "Knuspr-Verbindung nicht möglich. Es wurden keine Angebote übernommen."
                }) }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false,
                message = "Offizieller OAuth-/PKCE-Zugang derzeit nicht vollständig verfügbar. Keine andere Anmeldemethode und keine verdeckte Ersatzquelle verwendet.") }
        }
    }
    fun authorizationOpened() { authTicket = null; mutable.value = mutable.value.copy(authorizationUrl = null) }
    fun readConnection() {
        if (mutable.value.busy) return
        val expected = generation
        viewModelScope.launch {
            val connected = withContext(Dispatchers.IO) { runCatching { privateStore.read().token != null }.getOrDefault(false) }
            if (generation == expected && !mutable.value.busy) mutable.value = mutable.value.copy(connected = connected)
        }
    }
    fun discoverTools() {
        if (mutable.value.busy) return
        val expected = ++generation
        mutable.value = mutable.value.copy(busy = true, discovery = null, message = null)
        work = viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { requireNotNull(privateStore.read().token) }
                val discovery = KnusprMcp(http, token).discover()
                if (generation == expected) mutable.value = mutable.value.copy(busy = false, connected = true, discovery = discovery,
                    message = "Tatsächliche Lesefunktionen geprüft. Preise benötigen zusätzlich freigegebenes Antwortformat, Quelle und Liefergebiet.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false,
                message = "Lesefunktionen konnten nicht bestätigt werden. Keine Angebotsdaten abgerufen.") }
        }
    }
    fun disconnect() {
        work?.cancel(); val expected = ++generation
        mutable.value = LiveOffersUiState(busy = true, selectionLoaded = true)
        work = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { privateStore.clear(); settings.clearSelection() }
                if (generation == expected) mutable.value = LiveOffersUiState(selectionLoaded = true,
                    message = "Knuspr-Verbindung getrennt. Keine Lebensmittel- oder Einkaufshistorie gelöscht.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false,
                message = "Lokale Verbindungseinstellung konnte nicht sicher verworfen werden.") }
        }
    }
    fun cancel() {
        work?.cancel(); val expected = ++generation; val ticket = authTicket; authTicket = null
        mutable.value = mutable.value.copy(busy = true, authorizationUrl = null, message = "Abgebrochen. Keine Buchung erzeugt.")
        work = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { ticket?.let(privateStore::cancelAttempt) } }
            finally { if (generation == expected) mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
