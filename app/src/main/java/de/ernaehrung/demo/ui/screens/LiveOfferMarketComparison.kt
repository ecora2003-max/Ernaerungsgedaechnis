package de.ernaehrung.demo.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.local.Money
import de.ernaehrung.demo.data.local.ShoppingReport
import de.ernaehrung.demo.data.offers.LiveOffer
import de.ernaehrung.demo.data.offers.LiveOfferBatch
import de.ernaehrung.demo.data.offers.LiveOfferScope
import de.ernaehrung.demo.data.offers.LivePriceComparison
import de.ernaehrung.demo.data.offers.LiveRefreshStatus
import de.ernaehrung.demo.data.offers.formatReviewedOfferRate
import de.ernaehrung.demo.ui.LiveOffersUiState
import de.ernaehrung.demo.ui.LiveOffersViewModel
import de.ernaehrung.demo.ui.Panel
import de.ernaehrung.demo.ui.Section
import java.time.Instant

@Composable
internal fun LiveOfferMarketComparison(model: LiveOffersViewModel, state: LiveOffersUiState, primary: LiveOfferBatch,
    shopping: ShoppingReport?, onOpenCatalog: (() -> Unit)?, reviewing: String?, onReview: (String?) -> Unit) {
    val firstOffers = remember(primary) { primary.offers.filter { it.isComparisonCandidate(primary.scope) } }
    val candidates = model.configuredSources.filter { profile ->
        profile.allowsGoodsComparison && !profile.interactiveSearch && profile.retailer != primary.scope.retailer && (profile.storeId != null || profile.regionId != null) &&
            profile.supports(profile.scope(primary.scope.purchaseOn), Instant.now())
    }
    val secondary = state.comparisonSource
    if (firstOffers.isEmpty() || (candidates.isEmpty() && secondary == null)) return
    var profileId by remember(primary) { mutableStateOf("") }
    val selectedProfile = candidates.singleOrNull { it.id == profileId }
    Panel {
        Section("Preise zweier Händler vergleichen", "Einkaufstag ${primary.scope.purchaseOn}")
        IdPicker("Zweiter Händler und Markt", candidates.map { it.id to "${it.retailer.title} · ${it.label}" }, profileId) {
            profileId = it
        }
        OutlinedButton({ selectedProfile?.let { model.loadComparison(it.scope(primary.scope.purchaseOn)) } },
            enabled = selectedProfile != null && !state.busy, modifier = Modifier.testTag("live_load_comparison")) {
            Text("Angebote des zweiten Markts laden")
        }
        if (secondary != null) {
            Text("Zweiter Markt · ${secondary.scope.retailer.title} · ${secondary.scope.storeId ?: secondary.scope.regionId}")
            if (state.automatic) Text("Die automatische Aktualisierung wartet, bis du den Vergleich schließt.")
            OutlinedButton(model::closeComparison, enabled = !state.busy, modifier = Modifier.testTag("live_close_comparison")) {
                Text("Zweiten Markt schließen")
            }
            val secondResult = secondary.result
            when (secondResult?.status) {
                null -> Text("Angebote werden geladen.")
                LiveRefreshStatus.THROTTLED -> Text("Erneuter Abruf ab ${secondResult.refreshAfter}.")
                LiveRefreshStatus.LOADED -> Text("Abgerufen ${secondResult.batch?.fetchedAt}. Preise für diesen Vergleich bleiben auf dieser Seite.")
                else -> Text(when (secondResult.code) {
                    "ACCESS_BLOCKED" -> "Der zweite Anbieter sperrt den Abruf."
                    "RATE_LIMITED" -> "Der zweite Anbieter begrenzt derzeit Abfragen."
                    "NETWORK_UNAVAILABLE" -> "Netzabruf für den zweiten Markt fehlgeschlagen."
                    else -> "Keine verwendbare Antwort für den zweiten Markt."
                })
            }
            val second = secondResult?.batch
            if (second != null) {
                val secondOffers = remember(second) { second.offers.filter { it.isComparisonCandidate(second.scope) } }
                if (secondOffers.isEmpty()) Text("Für den gewählten Tag sind keine bepreisten Angebote mit passendem Filial- oder Regionsbezug geladen.")
                else {
                    var leftId by remember(primary, second) { mutableStateOf("") }
                    var rightId by remember(primary, second) { mutableStateOf("") }
                    IdPicker("Referenz aus ${primary.scope.retailer.title}", firstOffers.map { it.id to it.title }, leftId) {
                        leftId = it
                        onReview(null)
                    }
                    IdPicker("Alternative aus ${second.scope.retailer.title}", secondOffers.map { it.id to it.title }, rightId) {
                        rightId = it
                        onReview(null)
                    }
                    val left = firstOffers.singleOrNull { it.id == leftId }
                    val right = secondOffers.singleOrNull { it.id == rightId }
                    if (left != null) ComparisonOffer(model, state, left, primary.scope, "reference", shopping, onOpenCatalog, reviewing, onReview)
                    if (right != null) ComparisonOffer(model, state, right, second.scope, "alternative", shopping, onOpenCatalog, reviewing, onReview)
                    OutlinedButton({ model.compareSources(leftId, rightId) },
                        enabled = !state.busy && leftId in state.reviewedOffers && rightId in state.reviewedOffers,
                        modifier = Modifier.testTag("live_compare_markets")) { Text("Geprüfte Grundpreise vergleichen") }
                    if (state.comparedOfferIds == (leftId to rightId)) state.comparison?.let {
                        LiveOfferPriceComparison(it, primary.scope.retailer.title, second.scope.retailer.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonOffer(model: LiveOffersViewModel, state: LiveOffersUiState, offer: LiveOffer, scope: LiveOfferScope,
    tag: String, shopping: ShoppingReport?, onOpenCatalog: (() -> Unit)?, reviewing: String?, onReview: (String?) -> Unit) {
    val context = LocalContext.current
    var sourceUnavailable by remember(offer) { mutableStateOf(false) }
    val reviewed = offer.id in state.reviewedOffers
    Text("${offer.retailer.title} · ${offer.title} · ${offer.storeEvidence?.address ?: offer.storeId ?: offer.regionId}")
    Text("Warenpreis ${offer.merchandiseCents?.let(Money::format) ?: "unbekannt"} · Pfand ${offer.depositCents?.let(Money::format) ?: "unbekannt"}",
        Modifier.testTag("live_comparison_price_$tag"))
    Text("Gültigkeit ${offer.validFrom ?: "unbekannt"} bis ${offer.validUntil ?: "unbekannt"}")
    offer.packageDescription?.let { Text("Packungsangabe der Quelle · $it") }
    OutlinedButton({
        sourceUnavailable = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offer.productPageUrl ?: offer.sourceUrl)))
        }.isFailure
    }) { Text("Originalangebot öffnen · ${offer.retailer.title}") }
    if (sourceUnavailable) Text("Originalangebot konnte nicht geöffnet werden.")
    Text(if (reviewed) "Eigene Produktfassung und Gebinde bestätigt." else "Produktfassung und Gebinde prüfen.")
    OutlinedButton({ onReview(offer.id) }, enabled = !state.busy, modifier = Modifier.testTag("live_comparison_review_$tag")) {
        Text("Artikel abgleichen · ${offer.retailer.title}")
    }
    if (reviewing == offer.id) OfferArticleReviewPanel(model, offer, scope, state.articleCatalog, !state.busy, onOpenCatalog) { onReview(null) }
    if (shopping != null) OutlinedButton({ model.previewShoppingNeed(offer.id, shopping) }, enabled = reviewed && !state.busy,
        modifier = Modifier.testTag("live_comparison_need_$tag")) { Text("Preis für Einkaufsbedarf prüfen") }
}

@Composable
internal fun LiveOfferPriceComparison(comparison: LivePriceComparison, left: String = "Referenz", right: String = "Alternative") {
    Panel {
        Section("Geprüfter Waren-Grundpreisvergleich", "Gewählte Angebote")
        Text(comparison.reason ?: when {
            comparison.rateOrder!! > 0 -> "$right hat einen geringeren Waren-Grundpreis."
            comparison.rateOrder < 0 -> "$left hat einen geringeren Waren-Grundpreis."
            else -> "Gleicher Waren-Grundpreis."
        }, Modifier.testTag("live_comparison_result"))
        comparison.targetRate?.let { Text("$left · ${formatReviewedOfferRate(it)}") }
        comparison.alternativeRate?.let { Text("$right · ${formatReviewedOfferRate(it)}") }
        Text("Die Grundpreise verwenden die bestätigten Gebinde. Pfand, Lieferkosten und Einkaufsbudget bleiben separat.")
    }
}
