package de.ernaehrung.demo.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.local.AppCalendar
import de.ernaehrung.demo.data.local.Amounts
import de.ernaehrung.demo.data.local.ShoppingReport
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.ui.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
internal fun LiveOffersScreen(providedModel: LiveOffersViewModel? = null, onOpenShopping: (() -> Unit)? = null,
    shopping: ShoppingReport? = null) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = LiveOffersViewModel(context.applicationContext) as T
    } }
    val model = providedModel ?: viewModel<LiveOffersViewModel>(key = "live-offers", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(LiveRetailer.ALDI_NORD.name) }
    var store by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var delivery by rememberSaveable { mutableStateOf("") }
    var purchaseOn by rememberSaveable { mutableStateOf(AppCalendar.today().toString()) }
    var filter by remember { mutableStateOf("") }
    var connectionConsent by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val retailer = LiveRetailer.valueOf(selected)
    val source = LiveSourceCatalogue.sources.single { it.retailer == retailer }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(state.selectionLoaded) {
        if (state.selectionLoaded) state.scope?.let {
            selected = it.retailer.name; store = it.storeId.orEmpty(); region = it.regionId.orEmpty()
            delivery = it.deliveryAreaId.orEmpty(); purchaseOn = it.purchaseOn.toString()
        }
    }
    LaunchedEffect(owner, model) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { model.automaticTick(); delay(30_000) }
        }
    }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.readConnection() }
        owner.lifecycle.addObserver(observer); model.readConnection()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.authorizationUrl) {
        state.authorizationUrl?.let { uri ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))) }
                .onFailure { model.cancel() }
            model.authorizationOpened()
        }
    }
    Column(Modifier.fillMaxWidth().testTag("live_offers_screen"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Angebote & Preisvergleich", "Quelle, Gültigkeit und Filiale getrennt prüfen")
        Text("Angebote sind keine Einkäufe. Es wird nichts bestellt, bezahlt oder in den Vorrat gebucht. Unbekannte Preise und fehlende Mengen bleiben unbekannt.")
        if (shopping != null) Fold("Aktueller Einkaufsbedarf · nur lokaler Abgleich") {
            Text("Bedarf ${shopping.scope.startsOn}–${shopping.scope.endsOn} · Einkaufstag ${shopping.scope.purchaseOn}")
            shopping.items.forEach { line ->
                Text("${line.need.name}: ${Amounts.format(line.need.knownMissing)} ${line.need.unit.label} bekannt fehlend · weitere Bedarfe ${line.need.unknownRequirements}")
                OutlinedButton({ filter = line.need.name }) { Text("${line.need.name} in geladener Liste filtern") }
            }
            Text("Produktnamen werden dabei nicht übertragen. Ein Namensfilter belegt keine Artikelgleichheit und übernimmt keinen Preis in einen Kauf.")
        }
        IdPicker("Angebotsanbieter", LiveRetailer.entries.map { it.name to it.title }, selected) {
            model.selectProvider(); selected = it; store = ""; region = ""; delivery = ""
            connectionConsent = false; selectionError = null
        }
        val appliedEvidence = state.authorizedEvidenceRef.takeIf { state.scope?.retailer == retailer }
        Section(retailer.title, if (appliedEvidence != null) "Quellenfreigabe für die angewendete Auswahl geprüft" else when (source.status) {
            LiveSourceStatus.RIGHTS_UNVERIFIED -> "Automatischer Abruf noch nicht freigegeben"
            LiveSourceStatus.RIGHTS_RESTRICTED -> "Fortlaufende Nutzung dieser Quelle eingeschränkt"
            LiveSourceStatus.CONNECTION_REQUIRED -> if (state.connected) "Anmeldung gespeichert · Preisanschluss separat prüfen" else "Offizieller Zugang muss verbunden werden"
            LiveSourceStatus.READY -> "Quelle eingerichtet"
            LiveSourceStatus.BLOCKED -> "Quelle derzeit gesperrt"
        })
        Text(if (appliedEvidence != null) "Quellennachweis: $appliedEvidence · gilt ausschließlich für den angewendeten Geltungsbereich. Keine vollständige Marktabdeckung." else source.explanation,
            Modifier.testTag("live_source_status"))
        OutlinedButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.evidenceRef))) }) { Text("Beleg zur Quelle öffnen") }
        if (retailer == LiveRetailer.KNUSPR) {
            Text("Anmeldung über den offiziellen Knuspr-OAuth-Zugang im Browser. Nur Verbindungsmetadaten und ausdrücklich gewählte Suchdaten sind vorgesehen; keine Ernährungs-, Körper- oder Trainingshistorie. Kein Warenkorb und keine Bestellung.")
            Check("Diese Knuspr-Verbindung ausdrücklich beginnen", connectionConsent) { connectionConsent = it }
            Button({ model.connect(connectionConsent); connectionConsent = false }, enabled = connectionConsent && !state.busy,
                modifier = Modifier.testTag("knuspr_connect")) { Text("Knuspr verbinden") }
            if (state.connected) OutlinedButton(model::discoverTools, enabled = !state.busy,
                modifier = Modifier.testTag("knuspr_discover")) { Text("Verfügbare Lesefunktionen prüfen") }
            OutlinedButton(model::disconnect, enabled = !state.busy, modifier = Modifier.testTag("knuspr_disconnect")) { Text("Verbindung verwerfen") }
            state.discovery?.let { discovery ->
                Text("${discovery.tools.size} bestätigte Suchfunktionen · ${discovery.unsupportedCount} weitere/ungeklärte Funktionen")
                Fold("Technische Verbindungsdetails") {
                    Text("MCP ${discovery.protocol}"); discovery.tools.forEach { Text(it.title) }
                    Text("Die tatsächlich empfangenen Toolnamen und Schemas können für die Anschlussprüfung lokal kopiert werden. Keine Zugangstokens, Sitzungskennung oder privaten Verläufe. Weitere Tools werden dadurch nicht ausgeführt.")
                    var copyError by remember { mutableStateOf(false) }
                    OutlinedButton({
                        copyError = runCatching {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Knuspr-Toolschema", discovery.schemaDocument()))
                        }.isFailure
                    }) { Text("Empfangene Tool-Schemas lokal kopieren") }
                    if (copyError) Text("Schemaumfang zu groß oder Zwischenablage nicht verfügbar. Keine Preise oder Zugangsdaten exportiert.")
                }
                Text("Eine Anmeldung oder gefundene Funktion bestätigt noch keine Angebotsquelle, Artikelzuordnung, Preisbedingungen oder Liefergebietsabdeckung.")
            }
            Field("Liefergebiet-Kennung · falls unabhängig bestätigt", delivery, { delivery = it }, modifier = Modifier.testTag("live_delivery_input"))
        } else {
            Field("Filialkennung · falls unabhängig bestätigt", store, { store = it }, modifier = Modifier.testTag("live_store_input"))
            Field("Regionskennung · optional", region, { region = it }, modifier = Modifier.testTag("live_region_input"))
        }
        Text("Eine eingegebene Kennung allein ist kein Filial- oder Liefergebietsbeleg. Kettenweite und nationale Ersatzangebote gelten nicht automatisch für diese Filiale.")
        Field("Einkaufstag JJJJ-MM-TT", purchaseOn, { purchaseOn = it }, modifier = Modifier.testTag("live_purchase_day"))
        Button({
            runCatching { LiveOfferScope(retailer, store.takeIf { retailer != LiveRetailer.KNUSPR && it.isNotBlank() },
                region.takeIf { retailer != LiveRetailer.KNUSPR && it.isNotBlank() },
                delivery.takeIf { retailer == LiveRetailer.KNUSPR && it.isNotBlank() }, LocalDate.parse(purchaseOn))
                .also { require(it.purchaseOn.toString() == purchaseOn); it.validate() } }
                .onSuccess { selectionError = null; model.refresh(it) }
                .onFailure { selectionError = "Datum und Anbieter-/Filialauswahl prüfen." }
        }, enabled = !state.busy, modifier = Modifier.testTag("live_offers_refresh")) { Text("Auswahl anwenden und aktualisieren") }
        selectionError?.let { Hint(it) }
        state.scope?.let { applied -> Text("Angewendet: ${applied.retailer.title} · ${applied.purchaseOn} · ${applied.storeId ?: applied.deliveryAreaId ?: applied.regionId ?: "kein bestätigter Filial-/Liefergebietsbezug"}", Modifier.testTag("live_applied_scope")) }
        if (state.scope != null) {
            Text(if (state.automatic) "Automatik für diese angewendete Auswahl eingeschaltet" else "Automatik ausgeschaltet", Modifier.testTag("live_automatic_status"))
            OutlinedButton({ model.setAutomatic(!state.automatic) }, enabled = !state.busy,
                modifier = Modifier.testTag("live_automatic_toggle")) {
                Text(if (state.automatic) "Automatik ausschalten" else "Automatik für diese Auswahl einschalten")
            }
        }
        if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(model::cancel) { Text("Abbrechen") } }
        state.message?.let { Hint(it) }
        state.reference?.let { reference ->
            Text("Sitzungsreferenz: ${reference.offer.title} · ${reference.scope.retailer.title} · ${reference.scope.purchaseOn}")
            OutlinedButton(model::forgetReference) { Text("Vergleichsreferenz verwerfen") }
        }
        state.result?.let { result ->
            if (result.code != null && result.code != "REFRESH_INTERVAL") Text(when (result.code) {
                "SOURCE_NOT_CONFIGURED" -> "Für diesen Anbieter ist noch keine freigegebene Datenquelle eingerichtet."
                "APP_DISPLAY_UNVERIFIED" -> "Der konkrete Nachweis zur Quellenanzeige fehlt."
                "SOURCE_SCOPE_OR_PERMISSION_CHANGED" -> "Quellenfreigabe oder gewählter Geltungsbereich ist nicht mehr bestätigt."
                "CAPABILITY_NOT_PERMITTED" -> "Diese Quelle unterstützt die gewählte Abfrage nicht."
                "LOGIN_REQUIRED" -> "Anmeldung erforderlich oder abgelaufen; Verbindung erneut prüfen."
                "ACCESS_BLOCKED" -> "Anbieter sperrt den Zugriff. Kein automatischer Wiederholungsversuch."
                "RATE_LIMITED" -> "Anbieter begrenzt die Abfragen. Später ausdrücklich erneut versuchen."
                "NETWORK_UNAVAILABLE" -> "Netzabruf konnte nicht abgeschlossen werden."
                "UNSUPPORTED_SOURCE_SCHEMA", "SOURCE_READ_OR_CONTRACT_FAILED" -> "Antwort nicht zuverlässig als Angebot auswertbar; keine neue Preisübernahme."
                "REFRESH_JOURNAL_UNAVAILABLE" -> "Lokale Abrufsteuerung nicht sicher lesbar oder speicherbar. Kein Netzabruf gestartet."
                "AUTOMATIC_REFRESH_NOT_PERMITTED" -> "Automatischer Abruf ist nicht freigegeben oder nach einem Fehler pausiert."
                else -> "Quelle derzeit nicht nutzbar."
            })
            if (result.status == LiveRefreshStatus.BLOCKED) Text("Kein freigegebener Liveabruf. Fehlende Angebote bedeuten keine vollständige Marktprüfung.", Modifier.testTag("live_offers_blocked"))
            result.batch?.let { batch ->
                Text("${if (result.fromCache) "Letzter Sitzungsstand" else "Abgerufen"} ${batch.fetchedAt} · Quelle ${batch.sourceId} · Abdeckung unvollständig")
                Field("Geladene Angebote filtern · bleibt lokal", filter, { filter = it }, modifier = Modifier.testTag("live_offer_filter"))
                val rows = batch.offers.filter { it.title.contains(filter, ignoreCase = true) }
                var target by remember(batch) { mutableStateOf("") }
                var alternative by remember(batch) { mutableStateOf("") }
                if (batch.offers.size > 1) {
                    IdPicker("Referenzangebot dieser Liste", batch.offers.map { it.id to it.title }, target) { target = it }
                    IdPicker("Alternativangebot dieser Liste", batch.offers.map { it.id to it.title }, alternative) { alternative = it }
                    OutlinedButton({ model.compare(target, alternative) }, enabled = target.isNotBlank() && alternative.isNotBlank() && !state.busy) { Text("Waren-Grundpreise geprüft vergleichen") }
                }
                if (rows.isEmpty()) Text("Keine passenden geladenen Angebote. Keine Aussage über das gesamte Sortiment.")
                rows.forEach { offer -> Panel {
                    Section(offer.title, when (offer.kind) { LiveOfferKind.PROMOTION -> "Aktionsangabe"; LiveOfferKind.REGULAR -> "Normalpreisangabe"; else -> "Preisart ungeklärt" })
                    fun cents(value: Long?) = value?.let { BigDecimal.valueOf(it, 2).toPlainString().replace('.', ',') + " €" } ?: "unbekannt"
                    Text("Warenpreis: ${cents(offer.merchandiseCents)} · Pfand: ${cents(offer.depositCents)} · zusammen: ${cents(offer.checkoutCents)}")
                    offer.priceDetails?.let { detail ->
                        detail.brand?.let { Text("Markenangabe der Quelle: $it") }
                        Text("Normalpreisangabe der Quelle: ${cents(detail.regularCents)}",
                            Modifier.testTag("live_regular_${offer.sourceProductId}"))
                        detail.previousDisplay?.let { Text("Frühere Preisangabe als Originaltext: $it · Betrag und Gültigkeit nicht unabhängig bestätigt",
                            Modifier.testTag("live_previous_${offer.sourceProductId}")) }
                        detail.appCents?.let { Text("Bedingter Apppreis der Quelle: ${cents(it)} · getrennt vom allgemeinen Warenpreis",
                            Modifier.testTag("live_app_price_${offer.sourceProductId}")) }
                        detail.unitPriceDisplay?.let { Text("Veröffentlichte Grundpreisangabe: $it · Packungsmenge und Mengenbasis ungeprüft",
                            Modifier.testTag("live_unit_text_${offer.sourceProductId}")) }
                        detail.wishlistUntilLocal?.let { Text("Zusätzliche lokale Zeitangabe der Quelle: $it · Bedeutung als Angebotsende ungeprüft") }
                    }
                    Text("Menge: ${offer.quantityMilli?.let(Amounts::format) ?: "unbekannt"} ${offer.unit?.label.orEmpty()} · Basis ${offer.quantityBasis?.name ?: "unbekannt"}")
                    offer.packageDescription?.let { Text("Packungsangabe der Quelle: $it · keine bestätigte Mengenbasis daraus abgeleitet") }
                    Text("Grundpreis: ${offer.unitPriceText()?.replace('.', ',') ?: "unbekannt"}")
                    Text("Gültigkeit: ${offer.validFrom ?: "unbekannt"} bis ${offer.validUntil ?: "unbekannt"} · Preisstand Quelle ${offer.sourceObservedAt ?: "unbekannt"}")
                    Text("${when (offer.applicability) {
                        LiveOfferApplicability.STORE_CONFIRMED -> "Filialbezug bestätigt"
                        LiveOfferApplicability.REGION_CONFIRMED -> "Regionsbezug bestätigt"
                        LiveOfferApplicability.CHAIN_REPORTED -> "Kettenweite Angabe · Filiale ungeprüft"
                        LiveOfferApplicability.DELIVERY_CONFIRMED -> "Liefergebiet bestätigt"
                        LiveOfferApplicability.UNKNOWN -> "Geltungsbereich ungeklärt"
                    }} · ${offer.storeId ?: offer.deliveryAreaId ?: offer.regionId ?: "keine genaue Zuordnung"}")
                    if (!offer.conditionsConfirmed) Text("Diese Quellangabe allein bestätigt keine Preisbedingungen. Ein Vergleich benötigt zusätzlich einen unabhängigen Nachweis.")
                    offer.conditions?.let { Text(it) }
                    Text("Quellenartikel ${offer.sourceProductId} · ${offer.sourceUrl}")
                    OutlinedButton({ model.rememberReference(offer.id) }, enabled = !state.busy) { Text("Als Vergleichsreferenz wählen") }
                    if (state.reference != null) OutlinedButton({ model.compareWithReference(offer.id) }, enabled = !state.busy) { Text("Mit Sitzungsreferenz vergleichen") }
                } }
            }
        }
        state.comparison?.let { comparison ->
            Panel {
                Section("Geprüfter Waren-Grundpreisvergleich", "Keine vollständige Marktprüfung")
                Text(comparison.reason ?: when {
                    comparison.rateOrder!! > 0 -> "Alternative hat einen geringeren Waren-Grundpreis."
                    comparison.rateOrder < 0 -> "Referenz hat einen geringeren Waren-Grundpreis."
                    else -> "Gleicher Waren-Grundpreis."
                }, Modifier.testTag("live_comparison_result"))
                comparison.targetRate?.let { Text("Referenz: ${formatReviewedOfferRate(it)}") }
                comparison.alternativeRate?.let { Text("Alternative: ${formatReviewedOfferRate(it)}") }
                if (comparison.rateOrder != null) Text("Diese Werte verwenden die separat geprüfte genaue Artikel-/Gebindebasis. Fehlende Mengenangaben des ursprünglichen Feeds werden nicht überschrieben.")
                Text("Pfand, Lieferkosten, Mindestbestellwert, Vorrat und Einkaufsbudget sind damit nicht verglichen. Es wird keine Einkaufsposition geändert oder gebucht.")
            }
        }
        if (onOpenShopping != null) OutlinedButton(onOpenShopping, modifier = Modifier.testTag("live_open_shopping")) { Text("Zu den Einkaufsvorschlägen") }
        Text("Automatik prüft ausschließlich bei sichtbarer Angebotsseite und respektiert den belegten Quellenrhythmus, auch nach Neustart. Nach Fehlern ist eine erfolgreiche ausdrückliche neue Abfrage nötig. Preise werden nicht dauerhaft gespeichert. Gesperrte Quellen starten keinen Abruf. Picnic ist vorläufig zurückgestellt.")
    }
}
