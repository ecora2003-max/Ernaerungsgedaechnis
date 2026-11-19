package de.ernaehrung.demo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LiveOfferSelection(val offer: LiveOffer, val scope: LiveOfferScope, val authorization: LiveSourceGrant)
internal data class LiveNeedPreview(val offerId: String, val report: ShoppingReport,
    val line: ShoppingLine, val packs: PackCalculation)
internal data class LiveComparisonSource(val scope: LiveOfferScope, val result: LiveRefreshResult? = null)
internal data class LiveOffersUiState(val busy: Boolean = false, val scope: LiveOfferScope? = null,
    val result: LiveRefreshResult? = null, val message: String? = null, val connection: KnusprConnectionInfo? = null,
    val discovery: KnusprToolDiscovery? = null, val authorizationUrl: URI? = null,
    val automatic: Boolean = false, val selectionLoaded: Boolean = false, val comparison: LivePriceComparison? = null,
    val reference: LiveOfferSelection? = null, val authorizedEvidenceRef: String? = null,
    val articleCatalog: List<ProductVersionEntity> = emptyList(), val reviewedOffers: Set<String> = emptySet(),
    val needPreviews: List<LiveNeedPreview> = emptyList(), val comparisonSource: LiveComparisonSource? = null,
    val comparedOfferIds: Pair<String, String>? = null) {
    val connected: Boolean get() = connection != null
    val batches: List<LiveOfferBatch> get() = listOfNotNull(result?.batch, comparisonSource?.result?.batch)
}

/** No FoodStore/Room writer and no health/profile export. HTTP happens only on
 * explicit connection/selection; unapproved catalogue sources never start IO. */
internal class LiveOffersViewModel(context: Context, runtime: LiveOfferRuntime? = null,
    private val http: OfferHttp = HttpsOfferHttp(), comparison: LiveOfferComparison? = null,
    private val sources: ReviewedOfferSources = ReviewedOfferSources.bundled(context),
    private val readCatalog: suspend () -> List<ProductVersionEntity> = {
        FoodDatabase.localSandbox(context.applicationContext).foodDao().allVersions()
    }, proofVerifier: OfferArticleProofVerifier = AndroidOfferArticleProof) : ViewModel() {
    private val app = context.applicationContext
    private val runtime = runtime ?: sources.runtime(http, PrivateOfferSettings.forApp(app))
    private val suppliedComparison = comparison
    private val articles = LiveArticleReviewSession({ scope, id, grant -> this.runtime.permitsSnapshot(scope, id, grant) }, proofVerifier)
    private var pendingArticle: Pair<String, Long>? = null
    val configuredSources: List<ReviewedOfferSources.Profile> get() = sources.profiles
    val invalidSourceProfiles: Int get() = sources.invalidProfiles
    private val privateStore = PrivateKnusprStore.forApp(app)
    private val settings = PrivateOfferSettings.forApp(app)
    private val mutable = MutableStateFlow(LiveOffersUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    @Volatile private var generation = 0L
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
    override fun onCleared() { generation++; articles.clear(); super.onCleared() }

    private fun cancelArticlePrompt() { pendingArticle = null; articles.cancel() }
    fun cancelArticleReview(challengeId: String) {
        if (pendingArticle?.first == challengeId) cancelArticlePrompt()
    }
    private fun eligibleCatalog(versions: List<ProductVersionEntity>): List<ProductVersionEntity> {
        val now = System.currentTimeMillis()
        return versions.filter { version ->
            version.source != Source.DEMO && version.quality in setOf(Quality.CONFIRMED, Quality.SOURCED) &&
                version.baseUnit in setOf(BaseUnit.GRAM, BaseUnit.MILLILITRE) && version.recordedAt <= now &&
                versions.filter { it.productId == version.productId && it.recordedAt <= now }
                    .let { known -> known.filter { it.revision == known.maxOfOrNull { row -> row.revision } }.singleOrNull()?.id == version.id }
        }.sortedBy { it.name }
    }
    private fun reviewedIds(value: LiveOffersUiState, versions: List<ProductVersionEntity>): Set<String> =
        value.batches.flatMap { batch -> batch.offers.filter { articles.reviewed(it, batch.scope, versions) != null } }
            .mapTo(mutableSetOf()) { it.id }

    private fun selection(value: LiveOffersUiState, offerId: String): LiveOfferSelection? =
        listOfNotNull(value.result, value.comparisonSource?.result).mapNotNull { result ->
            val batch = result.batch ?: return@mapNotNull null
            val authorization = result.authorization ?: return@mapNotNull null
            batch.offers.singleOrNull { it.id == offerId }?.let { LiveOfferSelection(it, batch.scope, authorization) }
        }.singleOrNull()

    private fun retainDisplayedArticles(value: LiveOffersUiState) {
        val ids = value.batches.flatMap { it.offers }.mapTo(mutableSetOf()) { it.id }
        value.reference?.let { ids.add(it.offer.id) }
        cancelArticlePrompt()
        articles.retainOnly(ids)
    }

    fun loadArticleCatalog() {
        if (mutable.value.busy) return
        val expected = generation
        viewModelScope.launch {
            val versions = withContext(Dispatchers.IO) { runCatching { readCatalog() }.getOrNull() }
            if (expected == generation && versions != null) mutable.value = mutable.value.copy(
                articleCatalog = eligibleCatalog(versions), reviewedOffers = reviewedIds(mutable.value, versions))
            else if (expected == generation) mutable.value = mutable.value.copy(message = "Eigener Katalog konnte nicht gelesen werden. Keine Artikelzuordnung bestätigt.")
        }
    }
    fun prepareArticleReview(offerId: String, draft: LiveArticleReviewDraft,
        onPrepared: (LiveArticleReviewSession.Challenge) -> Unit) {
        val value = currentForAction() ?: return
        val (offer, scope, grant) = selection(value, offerId) ?: return
        cancelArticlePrompt(); val expected = ++generation
        mutable.value = value.copy(busy = true, comparison = null, needPreviews = emptyList())
        work = viewModelScope.launch {
            try {
                val versions = withContext(Dispatchers.IO) { readCatalog() }
                if (generation != expected) return@launch
                val challenge = articles.prepare(offer, scope, grant, draft, versions)
                pendingArticle = challenge.id to expected
                publishValidated(mutable.value.copy(busy = false, articleCatalog = eligibleCatalog(versions)))
                if (pendingArticle?.first == challenge.id) onPrepared(challenge)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == expected) {
                cancelArticlePrompt()
                mutable.value = mutable.value.copy(busy = false,
                    message = "Artikelreview nicht bereit: ${failure.message?.take(240) ?: "Katalog, Gebinde oder Quelle erneut prüfen."}")
            } }
        }
    }
    fun acceptArticleReview(challengeId: String, proof: OfferArticleProof) {
        val expected = generation
        if (pendingArticle != (challengeId to expected) || mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        work = viewModelScope.launch {
            try {
                val versions = withContext(Dispatchers.IO) { readCatalog() }
                if (generation != expected || pendingArticle != (challengeId to expected)) return@launch
                articles.accept(challengeId, proof, versions); pendingArticle = null
                publishValidated(mutable.value.copy(busy = false, articleCatalog = eligibleCatalog(versions),
                    reviewedOffers = reviewedIds(mutable.value, versions), comparison = null, needPreviews = emptyList(),
                    message = "Genau dieser Artikel und dieses Gebinde gerätegebunden für die Sitzung bestätigt. Keine Buchung erzeugt."))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) {
                cancelArticlePrompt(); mutable.value = mutable.value.copy(busy = false,
                    message = "Gerätebestätigung, Produktfassung oder Angebotsstand nicht mehr gültig. Artikel erneut prüfen.")
            } }
        }
    }
    fun previewShoppingNeed(offerId: String, report: ShoppingReport) {
        val value = currentForAction() ?: return
        val (offer, scope) = selection(value, offerId) ?: return
        if (report.scope.purchaseOn != scope.purchaseOn.toString()) {
            mutable.value = value.copy(needPreviews = emptyList(), message = "Einkaufsliste und Angebot müssen denselben Einkaufstag verwenden."); return
        }
        val expected = generation; mutable.value = value.copy(busy = true)
        work = viewModelScope.launch {
            try {
                val versions = withContext(Dispatchers.IO) { readCatalog() }
                if (generation != expected) return@launch
                val quotes = report.items.mapNotNull { line -> articles.previewNeed(offer, scope, line.need, versions)
                    ?.let { LiveNeedPreview(offerId, report, line, it) } }
                publishValidated(mutable.value.copy(busy = false, needPreviews = quotes,
                    reviewedOffers = reviewedIds(mutable.value, versions),
                    message = if (quotes.isEmpty()) "Kein passender bestätigter Artikel mit bekanntem Bedarf. Produktfassung, Einheit und Datum prüfen."
                        else "Live-Warenpreis auf den bekannten Bedarf angewendet. Teilvorschau, keine Kauf- oder Budgetbuchung."))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false,
                needPreviews = emptyList(), message = "Aktueller Bedarfsabgleich nicht möglich; keine Einkaufsposition geändert.") }
        }
    }

    /** Keep the exact independent authorization of each loaded observation.
     * Replacements with the same source ID do not reauthorize old prices. */
    private fun validated(value: LiveOffersUiState): LiveOffersUiState {
        fun permitted(result: LiveRefreshResult?): Boolean = result?.batch?.let {
            runtime.permitsSnapshot(it.scope, it.sourceId, result.authorization)
        } ?: true
        val validBatch = permitted(value.result)
        val validOther = validBatch && permitted(value.comparisonSource?.result)
        val validReference = value.reference?.let {
            it.authorization.cachePermitted && runtime.permitsSnapshot(it.scope, it.offer.sourceId, it.authorization)
        } ?: true
        if (validBatch && validOther && validReference) return value
        val remaining = value.copy(result = value.result.takeIf { validBatch },
            comparisonSource = value.comparisonSource.takeIf { validOther },
            reference = value.reference.takeIf { validReference }, comparison = null, comparedOfferIds = null, needPreviews = emptyList(),
            authorizedEvidenceRef = value.authorizedEvidenceRef.takeIf { validBatch },
            automatic = value.automatic && validBatch,
            message = "Quellenfreigabe oder Geltungsbereich geändert. Betroffene Sitzungsdaten verworfen; diese Quelle erneut ausdrücklich aktualisieren.")
        retainDisplayedArticles(remaining)
        val visibleIds = remaining.batches.flatMap { it.offers }.map { it.id }.toSet()
        return remaining.copy(reviewedOffers = value.reviewedOffers.intersect(visibleIds))
    }
    private fun publishValidated(value: LiveOffersUiState): Job? {
        val checked = validated(value)
        mutable.value = checked
        val scope = value.scope
        if (!value.automatic || checked.automatic || scope == null) return null
        val expected = generation
        return viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { settings.pauseAutomatic(scope) { generation == expected } }.isSuccess
            if (!saved) withContext(Dispatchers.Main) {
                if (generation == expected) mutable.value = mutable.value.copy(
                    message = "Betroffene Sitzungsdaten verworfen. Automatik in dieser Sitzung ausgeschaltet; lokale Einstellung konnte nicht sicher gespeichert werden.")
            }
        }
    }
    private fun currentForAction(): LiveOffersUiState? {
        val value = mutable.value
        if (value.busy) return null
        if (validated(value) !== value) { publishValidated(value); return null }
        return value
    }
    fun selectProvider() {
        cancelArticlePrompt()
        articles.retainOnly(setOfNotNull(mutable.value.reference?.takeIf { it.authorization.cachePermitted }?.offer?.id))
        work?.cancel(); val expected = ++generation; val ticket = authTicket; authTicket = null
        mutable.value = mutable.value.copy(scope = null, result = null, discovery = null, message = null,
            automatic = false, comparison = null, authorizationUrl = null, busy = true, authorizedEvidenceRef = null,
            reviewedOffers = emptySet(), needPreviews = emptyList(), comparisonSource = null, comparedOfferIds = null)
        work = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { ticket?.let(privateStore::cancelAttempt); settings.clearSelection() } }
            finally { if (generation == expected) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun refresh(scope: LiveOfferScope, automatic: Boolean = false) {
        if (automatic && (mutable.value.busy || !mutable.value.automatic || mutable.value.comparisonSource != null)) return
        articles.retainOnly(setOfNotNull(mutable.value.reference?.takeIf { it.authorization.cachePermitted }?.offer?.id))
        cancelArticlePrompt(); work?.cancel(); val expected = ++generation
        val canSave = scope.searchTerm == null
        val keepAutomatic = canSave && mutable.value.scope == scope && mutable.value.automatic
        mutable.value = mutable.value.copy(scope = scope, busy = true, result = null, message = null, automatic = keepAutomatic,
            comparison = null, authorizedEvidenceRef = null, reviewedOffers = emptySet(), needPreviews = emptyList(),
            comparisonSource = null, comparedOfferIds = null)
        work = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (!automatic && canSave) settings.select(scope, keepAutomatic)
                    val result = runtime.refresh(scope, automatic).also {
                        if (canSave && it.status in setOf(LiveRefreshStatus.BLOCKED, LiveRefreshStatus.FAILED)) settings.select(scope, false)
                    }
                    result
                }
                if (generation == expected) publishValidated(mutable.value.copy(busy = false, result = result,
                    authorizedEvidenceRef = result.authorization?.evidenceRef,
                    automatic = keepAutomatic && result.status !in setOf(LiveRefreshStatus.BLOCKED, LiveRefreshStatus.FAILED),
                    message = when {
                        result.fromCache -> "Letzter erlaubter Stand aus dieser Sitzung. Keine erfolgreiche neue Abfrage; Gültigkeit und Preisstand prüfen."
                        result.status == LiveRefreshStatus.LOADED -> "Abfrage abgeschlossen. Keine Buchung erzeugt."
                        result.status == LiveRefreshStatus.THROTTLED -> "Erlaubter Abrufabstand noch nicht erreicht. Nächster möglicher Abruf: ${result.refreshAfter}."
                        else -> "Keine freigegebene aktuelle Angebotsübernahme. Vorhandene Lebensmittelbuchungen bleiben erhalten."
                    }))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false, message = "Auswahl oder Quelle konnte nicht geprüft werden.") }
        }
    }
    fun loadComparison(scope: LiveOfferScope) {
        val value = currentForAction() ?: return
        val primary = value.result?.takeIf { it.status == LiveRefreshStatus.LOADED }?.batch ?: return
        if (scope.retailer == primary.scope.retailer || scope.retailer.stationary == null ||
            (scope.storeId == null && scope.regionId == null) || scope.purchaseOn != primary.scope.purchaseOn ||
            primary.offers.none { it.isComparisonCandidate(primary.scope) }) return
        if (scope.searchTerm != null && (runCatching { scope.validate() }.isFailure ||
                sources.profile(scope, Instant.now())?.allowsGoodsComparison != true)) return
        val expected = ++generation
        val next = withoutComparisonSource(value).copy(busy = true, comparisonSource = LiveComparisonSource(scope), message = null)
        mutable.value = next
        work = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runtime.refresh(scope) }
                val fresh = if (result.status == LiveRefreshStatus.LOADED) result else result.copy(batch = null)
                if (generation == expected) publishValidated(mutable.value.copy(busy = false,
                    comparisonSource = LiveComparisonSource(scope, fresh)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false,
                comparisonSource = LiveComparisonSource(scope, LiveRefreshResult(LiveRefreshStatus.FAILED))) }
        }
    }

    private fun withoutComparisonSource(value: LiveOffersUiState): LiveOffersUiState {
        val ids = value.result?.batch?.offers.orEmpty().map { it.id }.toSet()
        val remaining = value.copy(comparisonSource = null, comparison = null, comparedOfferIds = null,
            reviewedOffers = value.reviewedOffers.intersect(ids),
            needPreviews = value.needPreviews.filter { it.offerId in ids })
        retainDisplayedArticles(remaining)
        return remaining
    }

    fun closeComparison() {
        val value = mutable.value
        if (value.busy || value.comparisonSource == null) return
        generation++
        publishValidated(withoutComparisonSource(value))
    }

    fun leaveOfferPage() {
        val value = mutable.value
        if (value.scope?.searchTerm == null && value.comparisonSource == null && value.result?.cachePermitted != false &&
            (!value.busy || value.scope == null)) return
        generation++
        work?.cancel()
        val result = value.result?.takeIf { it.cachePermitted }
        mutable.value = withoutComparisonSource(value.copy(result = result, busy = false,
            scope = value.scope?.takeIf { it.searchTerm == null },
            authorizedEvidenceRef = value.authorizedEvidenceRef.takeIf { result != null }, message = null))
    }

    fun compareSources(targetId: String, alternativeId: String) {
        val value = currentForAction() ?: return
        val primary = value.result?.batch ?: return
        val other = value.comparisonSource?.result?.batch ?: return
        val target = primary.offers.singleOrNull { it.id == targetId && it.isComparisonCandidate(primary.scope) } ?: return
        val alternative = other.offers.singleOrNull { it.id == alternativeId && it.isComparisonCandidate(other.scope) } ?: return
        compareReviewed(value, target, primary.scope, alternative, other.scope)
    }

    fun setAutomatic(enabled: Boolean) {
        val value = currentForAction() ?: return
        val scope = value.scope ?: return
        if (scope.searchTerm != null) return
        cancelArticlePrompt(); val expected = ++generation
        mutable.value = mutable.value.copy(busy = true)
        work = viewModelScope.launch {
            try {
                val accepted = withContext(Dispatchers.IO) {
                    val allowed = !enabled || runtime.automaticAllowed(scope)
                    if (allowed) settings.select(scope, enabled)
                    allowed
                }
                if (generation == expected) publishValidated(mutable.value.copy(busy = false,
                    automatic = enabled && accepted, message = when {
                        !accepted -> "Automatik pausiert: Quelle, Geltungsbereich oder Abrufrhythmus nicht nutzbar, oder die letzte Abfrage ist fehlgeschlagen. Zuerst ausdrücklich erfolgreich aktualisieren."
                        enabled -> "Automatische Aktualisierung bei sichtbarer Angebotsseite eingeschaltet. Keine Hintergrundabfragen."
                        else -> "Automatische Aktualisierung ausgeschaltet."
                    }))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false, automatic = false,
                message = "Aktualisierungseinstellung konnte nicht sicher gespeichert werden.") }
        }
    }
    suspend fun automaticTick() {
        val expected = generation; val value = mutable.value
        if (!value.selectionLoaded || value.busy) return
        // Host-only registry checks, including with HTTP automation disabled.
        // Validate at publication as well as on each explicit compare/reference
        // action, rather than attaching a later grant to an earlier response.
        publishValidated(value)?.join()
        if (expected != generation || mutable.value.busy) return
        if (suppliedComparison == null && (mutable.value.reviewedOffers.isNotEmpty() || mutable.value.needPreviews.isNotEmpty())) {
            val versions = withContext(Dispatchers.IO) { runCatching { readCatalog() }.getOrNull() }
            if (expected != generation || mutable.value.busy) return
            val ids = versions?.let { reviewedIds(mutable.value, it) } ?: emptySet()
            mutable.value = mutable.value.copy(reviewedOffers = ids,
                comparison = mutable.value.comparison.takeIf { ids == mutable.value.reviewedOffers },
                needPreviews = mutable.value.needPreviews.filter { it.offerId in ids })
        }
        val current = mutable.value
        readConnection()
        if (current.result?.refreshAfter?.let { java.time.Instant.now() < it } == true) return
        if (current.automatic && current.comparisonSource == null) current.scope?.let { refresh(it, automatic = true) }
    }
    fun compare(targetId: String, alternativeId: String) {
        val value = currentForAction() ?: return
        val scope = value.scope ?: return
        val batch = value.result?.batch ?: return
        val target = batch.offers.singleOrNull { it.id == targetId } ?: return
        val alternative = batch.offers.singleOrNull { it.id == alternativeId } ?: return
        compareReviewed(value, target, scope, alternative, scope)
    }
    private fun compareReviewed(value: LiveOffersUiState, target: LiveOffer, targetScope: LiveOfferScope,
        alternative: LiveOffer, alternativeScope: LiveOfferScope) {
        val ids = target.id to alternative.id
        suppliedComparison?.let {
            publishValidated(value.copy(comparedOfferIds = ids,
                comparison = it.compare(target, targetScope, alternative, alternativeScope, java.time.Instant.now())))
            return
        }
        val expected = generation; mutable.value = value.copy(busy = true, comparison = null, comparedOfferIds = ids)
        work = viewModelScope.launch {
            try {
                val versions = withContext(Dispatchers.IO) { readCatalog() }
                if (generation == expected) publishValidated(mutable.value.copy(busy = false,
                    reviewedOffers = reviewedIds(mutable.value, versions),
                    comparison = articles.compare(target, targetScope, alternative, alternativeScope, versions)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutable.value = mutable.value.copy(busy = false,
                comparison = LivePriceComparison(null, "Aktuelle Artikel-/Katalogprüfung nicht möglich.")) }
        }
    }
    fun rememberReference(id: String) {
        val value = currentForAction() ?: return
        val scope = value.scope ?: return
        val offer = value.result?.batch?.offers?.singleOrNull { it.id == id } ?: return
        if (value.result.cachePermitted != true) {
            mutable.value = value.copy(message = "Die Quelle erlaubt kein Behalten für einen Anbieterwechsel. Vergleich innerhalb der geladenen Liste bleibt möglich.")
            return
        }
        publishValidated(value.copy(reference = LiveOfferSelection(offer, scope, requireNotNull(value.result.authorization)), comparison = null,
            message = "Referenz nur für diese Sitzung gewählt. Bei jedem Vergleich werden Quelle, Gültigkeit und Artikel erneut geprüft."))
    }
    fun forgetReference() { mutable.value = mutable.value.copy(reference = null, comparison = null) }
    fun compareWithReference(alternativeId: String) {
        val value = currentForAction() ?: return
        val target = value.reference ?: return
        val scope = value.scope ?: return
        val alternative = value.result?.batch?.offers?.singleOrNull { it.id == alternativeId } ?: return
        compareReviewed(value, target.offer, target.scope, alternative, scope)
    }
    fun connect(confirmed: Boolean) {
        if (!confirmed || mutable.value.busy) return
        val expected = ++generation
        mutable.value = mutable.value.copy(busy = true, connection = null, discovery = null, authorizationUrl = null, message = null)
        work = viewModelScope.launch {
            try {
                val ticket = withContext(Dispatchers.IO) { privateStore.beginAttempt() }
                authTicket = ticket
                val oauth = KnusprOAuth(http)
                val server = oauth.discover()
                val (pending, authorization) = oauth.begin(app.packageName, server)
                val retained = withContext(Dispatchers.IO) { privateStore.stage(ticket, pending) }
                if (generation == expected && retained) mutable.value = mutable.value.copy(busy = false, connection = null, authorizationUrl = authorization)
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
            val connection = withContext(Dispatchers.IO) { runCatching { privateStore.read().token?.connectionInfo() }.getOrNull() }
            if (generation == expected && !mutable.value.busy) mutable.value = mutable.value.copy(connection = connection)
        }
    }
    fun renewConnection(confirmed: Boolean): Job? {
        if (!confirmed || mutable.value.busy) return null
        val expected = ++generation
        mutable.value = mutable.value.copy(busy = true, discovery = null, message = null)
        work = viewModelScope.launch {
            var claim: PrivateKnusprStore.Renewal? = null
            try {
                val current = withContext(Dispatchers.IO) { privateStore.claimRenewal().also { claim = it } }
                val token = KnusprOAuth(http).renew(current.previous)
                currentCoroutineContext().ensureActive()
                val retained = withContext(Dispatchers.IO) { privateStore.finishRenewal(current, token) { generation == expected } }
                if (generation == expected) mutable.value = mutable.value.copy(
                    connection = token.connectionInfo().takeIf { retained }, message = if (retained)
                        "Anmeldung erneuert. Lesefunktionen und Angebotsfreigaben werden weiterhin getrennt geprüft." else
                        "Verbindung inzwischen geändert. Keine alte Anmeldung wiederhergestellt.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: OfferSourceFailure) {
                if (failure.code == OfferSourceFailureCode.LOGIN_REQUIRED) withContext(Dispatchers.IO) {
                    claim?.let { privateStore.rejectRenewal(it) }
                }
                if (generation == expected) mutable.value = mutable.value.copy(
                    connection = mutable.value.connection.takeUnless { failure.code == OfferSourceFailureCode.LOGIN_REQUIRED },
                    message = when (failure.code) {
                    OfferSourceFailureCode.LOGIN_REQUIRED -> "Anmeldung kann nicht erneuert werden. Erneut ausdrücklich verbinden."
                    OfferSourceFailureCode.ACCESS_BLOCKED -> "Knuspr blockiert die Erneuerung. Kein automatischer Wiederholungsversuch."
                    OfferSourceFailureCode.RATE_LIMITED -> "Knuspr begrenzt die Erneuerung. Später ausdrücklich erneut versuchen."
                    else -> "Anmeldung derzeit nicht erneuerbar. Bisherige lokale Daten bleiben erhalten."
                })
            } catch (_: Exception) {
                if (generation == expected) mutable.value = mutable.value.copy(
                    message = "Anmeldung nicht sicher erneuerbar. Verbindung prüfen oder erneut ausdrücklich anmelden.")
            } finally {
                withContext(NonCancellable) {
                    claim?.let { current -> withContext(Dispatchers.IO) { runCatching { privateStore.abortRenewal(current) } } }
                    withContext(Dispatchers.Main) { if (generation == expected) mutable.value = mutable.value.copy(busy = false) }
                }
            }
        }
        return work
    }
    fun discoverTools() {
        if (mutable.value.busy) return
        val expected = ++generation
        mutable.value = mutable.value.copy(busy = true, discovery = null, message = null)
        work = viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { requireNotNull(privateStore.read().token) }
                val discovery = KnusprMcp(http, token).discover()
                if (generation == expected) mutable.value = mutable.value.copy(busy = false, connection = token.connectionInfo(), discovery = discovery,
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
        cancelArticlePrompt()
        work?.cancel(); val expected = ++generation; val ticket = authTicket; authTicket = null
        val value = mutable.value
        val remaining = if (value.comparisonSource?.result == null) value.copy(comparisonSource = null) else value
        mutable.value = remaining.copy(busy = true, authorizationUrl = null, message = "Abgebrochen. Keine Buchung erzeugt.")
        work = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { ticket?.let(privateStore::cancelAttempt) } }
            finally { if (generation == expected) mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
