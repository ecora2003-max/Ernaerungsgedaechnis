package de.ernaehrung.demo.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.ui.*
import java.time.LocalDate

private data class ArticlePrompt(val challenge: LiveArticleReviewSession.Challenge,
    val draft: LiveArticleReviewDraft, val offer: LiveOffer)

/** This uses the actual system credential Activity and a separate auth-bound
 * key. Form state, prompts and approvals are transient; no booking permission. */
@Composable
internal fun OfferArticleReviewPanel(model: LiveOffersViewModel, offer: LiveOffer, scope: LiveOfferScope,
    catalog: List<ProductVersionEntity>, enabled: Boolean, onOpenCatalog: (() -> Unit)?, onClose: () -> Unit) {
    val context = LocalContext.current
    var selected by remember(offer) { mutableStateOf("") }
    var type by remember(offer) { mutableStateOf("") }
    var amount by remember(offer, selected) { mutableStateOf(catalog.singleOrNull { it.id == selected }?.packageAmount
        ?.takeIf { it > 0 && it % 1000 == 0L }?.div(1000)?.toString().orEmpty()) }
    var count by remember(offer) { mutableStateOf("1") }
    var basis by remember(offer) { mutableStateOf("") }
    var organic by remember(offer) { mutableStateOf("unknown") }
    var from by remember(offer) { mutableStateOf(offer.validFrom?.toString().orEmpty()) }
    var until by remember(offer) { mutableStateOf(offer.validUntil?.toString().orEmpty()) }
    var identity by remember(offer) { mutableStateOf(false) }
    var pack by remember(offer) { mutableStateOf(false) }
    var conditions by remember(offer) { mutableStateOf(false) }
    var attributes by remember(offer) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var prompt by remember { mutableStateOf<ArticlePrompt?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var alive by remember { mutableStateOf(true) }
    val draft = runCatching { LiveArticleReviewDraft(selected, type, requireNotNull(amount.toLongOrNull()),
        requireNotNull(count.toLongOrNull()), basis, when (organic) { "yes" -> true; "no" -> false; else -> null },
        LocalDate.parse(from), LocalDate.parse(until), attributes.filterValues { it.isNotBlank() }, identity, pack, conditions) }.getOrNull()
    val latestDraft by rememberUpdatedState(draft)
    val latestOffer by rememberUpdatedState(offer)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestClose by rememberUpdatedState(onClose)
    DisposableEffect(model, offer) {
        alive = true
        onDispose { alive = false; prompt?.let { model.cancelArticleReview(it.challenge.id) } }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val completed = prompt; prompt = null
        val secure = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true
        val current = completed != null && alive && latestEnabled && secure &&
            completed.offer == latestOffer && completed.draft == latestDraft && result.resultCode == Activity.RESULT_OK
        val proof = if (current) requireNotNull(completed).challenge.let {
            AndroidOfferArticleProof.sign(it.id, it.digest, it.nonce, System.currentTimeMillis())
        } else null
        if (completed != null && proof != null) {
            model.acceptArticleReview(completed.challenge.id, proof); latestClose()
        } else {
            completed?.let { model.cancelArticleReview(it.challenge.id) }
            identity = false; pack = false; conditions = false
            error = "Gerätebestätigung abgebrochen oder Artikelprüfung geändert. Erneut ausdrücklich prüfen."
        }
    }
    Panel {
        Section("Angebotsartikel unabhängig prüfen", offer.title)
        Text("Wähle eine aktuelle eigene Produktfassung und prüfe das Originalangebot. Quellenname und Barcode allein bestätigen keine gleiche Variante. Diese Zuordnung gilt nur für diese Sitzung und erzeugt keinen Einkauf.")
        if (catalog.isEmpty()) Text("Keine geeignete eigene Produktfassung vorhanden. Im Katalog eine tatsächliche g/ml-Produktfassung erfassen und bestätigen; Beispieldaten erlauben keinen produktiven Abgleich.")
        if (onOpenCatalog != null) OutlinedButton(onOpenCatalog, enabled = prompt == null) { Text("Eigenen Produktkatalog öffnen") }
        IdPicker("Eigene Produktfassung", catalog.map { it.id to "${it.name} · ${it.brand ?: "Marke unbekannt"} · Fassung ${it.revision}" }, selected) {
            selected = it; identity = false; pack = false; conditions = false
        }
        Field("Produkttyp · selbst benennen", type, { type = it }, modifier = Modifier.testTag("article_review_type"))
        val version = catalog.singleOrNull { it.id == selected }
        Text("Katalogmenge: ${version?.packageAmount?.let(Amounts::format) ?: "unbekannt"} ${version?.baseUnit?.label.orEmpty()} · GTIN ${version?.barcode ?: "unbekannt"}")
        Field("Menge je Einzelpackung · ganze g/ml", amount, { amount = it }, modifier = Modifier.testTag("article_review_amount"))
        Field("Einzelpackungen im Angebot", count, { count = it }, modifier = Modifier.testTag("article_review_count"))
        IdPicker("Bestätigte Mengenbasis", listOf("NET" to "Netto", "DRAINED" to "Abgetropft", "GROSS" to "Brutto"), basis) { basis = it }
        IdPicker("Bio unabhängig geprüft", listOf("unknown" to "Unbekannt", "yes" to "Ja", "no" to "Nein"), organic) { organic = it }
        Field("Bestätigt gültig ab JJJJ-MM-TT", from, { from = it }, modifier = Modifier.testTag("article_review_from"))
        Field("Bestätigt gültig bis JJJJ-MM-TT", until, { until = it }, modifier = Modifier.testTag("article_review_until"))
        OutlinedButton({ from = scope.purchaseOn.toString(); until = scope.purchaseOn.toString() }, enabled = prompt == null,
            modifier = Modifier.testTag("article_review_today")) { Text("Ich habe die Gültigkeit für ${scope.purchaseOn} geprüft") }
        Text("Fehlende Quellgültigkeit wird nicht aus der Angebotswoche berechnet. Eigene bestätigte Daten bleiben getrennt vom unveränderten Quellenstand; bekannte Quellfristen dürfen nicht erweitert werden.")
        Fold("Variantenmerkmale · für verschiedene Produktfassungen vollständig prüfen") {
            val names = listOf("Form", "Gesüßt/ungesüßt", "Art/Spezies", "Fettgehalt", "Roh/gegart", "Ernährungsform", "Sorte", "Allergene", "Verarbeitung", "Zuschnitt")
            LiveArticleReviewSession.criticalAttributes.forEachIndexed { index, key ->
                Field(names[index], attributes[key].orEmpty(), { value -> attributes = attributes + (key to value) })
            }
            Text("Leere Merkmale bleiben unbekannt. Keine automatische Ersatzprodukt- oder Allergenfreigabe aus diesem Preisvergleich.")
        }
        Check("Genau diese Produktfassung und Variante im Originalangebot geprüft", identity) { identity = it }
        Check("Einzelmenge, Packungszahl und Mengenbasis geprüft", pack) { pack = it }
        offer.conditions?.let { Text("Quellbedingungen: $it") }
        Check("Preisbedingungen, Filiale und Gültigkeit für diesen Einkauf geprüft", conditions) { conditions = it }
        Button({
            val reviewed = draft ?: return@Button
            val guard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val intent = if (guard?.isDeviceSecure == true) guard.createConfirmDeviceCredentialIntent(
                "Angebotsartikel bestätigen", "Genau diese Produktfassung und dieses Gebinde für den Preisvergleich bestätigen.") else null
            if (intent == null || !AndroidOfferArticleProof.ensureKey()) {
                error = "Für die Artikelbestätigung ist eine sichere Gerätesperre erforderlich."
            } else model.prepareArticleReview(offer.id, reviewed) { challenge ->
                if (!alive || latestDraft != reviewed || latestOffer != offer) {
                    model.cancelArticleReview(challenge.id)
                } else {
                    prompt = ArticlePrompt(challenge, reviewed, offer); error = null
                    runCatching { launcher.launch(intent) }.onFailure {
                        prompt = null; model.cancelArticleReview(challenge.id)
                        identity = false; pack = false; conditions = false
                        error = "Gerätebestätigung konnte nicht gestartet werden."
                    }
                }
            }
        }, enabled = enabled && prompt == null && version != null && draft != null && type.isNotBlank() &&
            basis.isNotBlank() && identity && pack && conditions,
            modifier = Modifier.testTag("article_review_confirm")) { Text("Artikel mit Gerätesperre bestätigen") }
        OutlinedButton({ prompt?.let { model.cancelArticleReview(it.challenge.id) }; onClose() }, enabled = prompt == null) { Text("Artikelprüfung schließen") }
        error?.let { Text(it, Modifier.testTag("article_review_error")) }
    }
}
