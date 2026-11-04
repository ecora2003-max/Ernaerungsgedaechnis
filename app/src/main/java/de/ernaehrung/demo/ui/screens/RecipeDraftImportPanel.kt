package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.integration.RecipeOfflineDraftGenerator
import de.ernaehrung.demo.data.integration.RecipeOfflineContextRanking
import de.ernaehrung.demo.data.integration.RecipeOfflineAllergenEvidence
import de.ernaehrung.demo.data.integration.RecipeDraftManualReview
import de.ernaehrung.demo.data.integration.RecipeDraftV1Import
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.RecipeDraftEvidenceForm
import de.ernaehrung.demo.data.local.RecipeDraftEncryptedEvidenceCheckpoint
import de.ernaehrung.demo.data.local.PrivateRecipeDraftEncryptedEvidenceCheckpoint
import de.ernaehrung.demo.data.local.recipeDraftEvidenceDigest
import de.ernaehrung.demo.data.local.RecipeDraftEncryptedImportCheckpoint
import de.ernaehrung.demo.data.local.PrivateRecipeDraftEncryptedImportCheckpoint
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import de.ernaehrung.demo.ui.RecipeDraftContextUiState
import de.ernaehrung.demo.ui.Choices
import de.ernaehrung.demo.ui.Fold
import de.ernaehrung.demo.ui.Picker
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Manual UI acquisition of independent catalog/mapping/state/basis/allergen facts.
 * JSON remains untrusted and in memory. No model flag can create consent or a booking.
 * The final consent is a separate gesture in RecipeDraftConsentReview.
 */
@Composable
internal fun RecipeDraftImportPanel(
    catalog: List<ProductVersionEntity>,
    editingEnabled: Boolean = true,
    recordedDigest: String? = null,
    contextState: RecipeDraftContextUiState = RecipeDraftContextUiState(),
    onRequestContext: (Gate.Flags) -> Unit = {},
    onClearContext: () -> Unit = {},
    checkpointStore: RecipeDraftEncryptedImportCheckpoint? = null,
    evidenceCheckpointStore: RecipeDraftEncryptedEvidenceCheckpoint? = null,
    onInvalidate: () -> Unit,
    onDiscardPending: () -> Unit,
    onReview: (String, Gate.Request,
        Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        Map<String, Gate.State>) -> Unit
) {
    val context = LocalContext.current
    val checkpoint = checkpointStore ?: remember(context) {
        PrivateRecipeDraftEncryptedImportCheckpoint(context)
    }
    val evidenceCheckpoint = evidenceCheckpointStore ?: remember(context) {
        PrivateRecipeDraftEncryptedEvidenceCheckpoint(context)
    }
    // Durable consent supersedes both unconfirmed local checkpoints.
    LaunchedEffect(recordedDigest) {
        if (recordedDigest != null) {
            checkpoint.discard()
            evidenceCheckpoint.discard()
        }
    }
    var json by remember { mutableStateOf("") }
    var offlinePortions by remember { mutableStateOf("2") }
    var offlineRestrictionsText by remember { mutableStateOf("") }
    var offlineAllergenVersions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var offlineAllergenAbsent by remember { mutableStateOf<Set<Pair<String, String>>>(emptySet()) }
    var offlineVariant by remember { mutableStateOf(0) }
    var useStock by remember { mutableStateOf(false) }
    var useShopping by remember { mutableStateOf(false) }
    var useOffers by remember { mutableStateOf(false) }
    var rankingVersionIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var rankingReviewed by remember { mutableStateOf(false) }
    var rankingHint by remember { mutableStateOf<String?>(null) }
    val selectedFlags = Gate.Flags(useStock, useShopping, useOffers)
    val offlineAllergens = offlineRestrictionsText.split(",").map { it.trim() }
        .filter { it.startsWith("ALLERGEN:") && Gate.isSupportedRestriction(it) }
        .map { it.removePrefix("ALLERGEN:") }.distinct()
    val selectedNeedsContext = useStock || useShopping || useOffers
    val boundContext = contextState.context?.takeIf {
        contextState.flags == selectedFlags && !contextState.busy &&
            it.projectedFlags == selectedFlags && it.gaps.isEmpty() &&
            it.contentBindingRequired && it.contentBinding != null
    }
    var imported by remember { mutableStateOf<RecipeDraftV1Import.Imported?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var versionIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var bases by remember { mutableStateOf<Map<String, Gate.Basis>>(emptyMap()) }
    var states by remember { mutableStateOf<Map<String, Gate.State>>(emptyMap()) }
    var allergenAnswers by remember { mutableStateOf<Map<Pair<String, String>, Boolean?>>(emptyMap()) }
    var restrictionsText by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    fun invalidate() { reviewed = false; onInvalidate() }
    val eligible = catalog.filter {
        it.source != Source.DEMO && it.quality in setOf(Quality.CONFIRMED, Quality.SOURCED)
    }
    val labels = eligible.associate { it.id to "${it.name} · v${it.revision} · ${it.id}" }

    Fold("JSON-Rezeptentwurf importieren und manuell prüfen") {
        if (!editingEnabled) {
            Text("Eine Rezeptbestätigung wird gerade verarbeitet. Änderungen erst nach Abschluss möglich.")
            return@Fold
        }
        Text("Nur lokale Vorschau. Modellangaben sind keine Produkt-, Mengen-, Zustands- oder Allergennachweise. Es wird nichts gebucht.")

        Text("Ohne ausdrückliche Aktion wird kein JSON gespeichert. Optional nur JSON für 24 Stunden verschlüsselt im privaten No-Backup-Speicher ablegen; Katalog-, Zustands- und Allergennachweise werden nie übernommen und müssen erneut geprüft werden.")
        Column {
            Button(
                enabled = json.isNotBlank(),
                modifier = Modifier.testTag("recipe_draft_encrypted_save"),
                onClick = {
                    try {
                        checkpoint.save(json)
                        error = "JSON verschlüsselt vorgemerkt (maximal 24 Stunden). Kein Prüfbeleg und keine Bestätigung."
                    } catch (_: Exception) {
                        error = "Verschlüsselte Speicherung nicht möglich. Kein unverschlüsselter Ersatz."
                    }
                }
            ) { Text("JSON verschlüsselt merken") }
            Button(
                modifier = Modifier.testTag("recipe_draft_encrypted_restore"),
                onClick = {
                    val saved = checkpoint.load()
                    if (saved == null) {
                        error = "Kein gültiger verschlüsselter Entwurf vorhanden."
                    } else {
                        invalidate()
                        json = saved
                        imported = null
                        versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
                        allergenAnswers = emptyMap(); restrictionsText = ""; reviewed = false
                        error = "JSON wiederhergestellt. Alle Nachweise neu prüfen und erneut ausdrücklich bestätigen."
                    }
                }
            ) { Text("Gemerkten JSON-Entwurf laden") }
        }
        Button(
            onClick = {
                invalidate()
                json = ""
                imported = null
                error = null
                versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
                allergenAnswers = emptyMap(); restrictionsText = ""
                checkpoint.discard()
                evidenceCheckpoint.discard()
                onDiscardPending()
            },
            modifier = Modifier.testTag("recipe_draft_discard_pending")
        ) { Text("Unbestätigten Rezeptvorgang verwerfen") }
        OutlinedTextField(
            value = json,
            onValueChange = { next ->
                // Reject oversized input without changing the current draft, revoking
                // its pending review or deleting an explicitly saved checkpoint.
                if (next.length > 262_144) {
                    error = "JSON zu groß (maximal 262144 Zeichen)."
                } else if (next != json) {
                    checkpoint.discard()
                    // Initial re-entry after process loss must not erase an opt-in
                    // form for the same JSON. Subsequent edits revoke it.
                    if (json.isNotBlank()) evidenceCheckpoint.discard()
                    invalidate()
                    imported = null
                    error = null
                    versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
                    allergenAnswers = emptyMap(); restrictionsText = ""
                    json = next
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("recipe_draft_json"),
            label = { Text("recipe.draft v1 JSON einfügen") },
            minLines = 3, maxLines = 6
        )
        Button(
            onClick = {
                invalidate()
                imported = null
                error = null
                try {
                    imported = RecipeDraftV1Import.read(json, emptyMap(), OffsetDateTime.now())
                } catch (_: IllegalArgumentException) {
                    error = "Ungültiger, veralteter oder nicht unterstützter Rezeptentwurf."
                }
            },
            enabled = json.isNotBlank(),
            modifier = Modifier.testTag("recipe_draft_json_inspect")
        ) { Text("JSON-Entwurf lokal prüfen") }
        Text("Offline-Vorschlag: 27 deterministische Kombinationen. Bei geprüftem Room-Kontext werden nur ausdrücklich zugeordnete Produktversionen nach potenziellen Gramm-Mengen in Bestand und Einkaufsbedarf sortiert. Keine Allergen-, Abtropfgewichts- oder Garzustandsfreigabe; die spätere Einzelprüfung bleibt Pflicht. Angebote ohne Rechtequelle bleiben gesperrt.")
        fun contextChanged() {
            invalidate()
            onClearContext()
            rankingVersionIds = emptyMap()
            offlineAllergenVersions = emptyMap()
            offlineAllergenAbsent = emptySet()
            rankingReviewed = false
            rankingHint = null
            imported = null
            versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
            allergenAnswers = emptyMap(); restrictionsText = ""; reviewed = false
            evidenceCheckpoint.discard()
        }
        Row {
            Checkbox(useStock, { if (it != useStock) { contextChanged(); useStock = it } },
                modifier = Modifier.testTag("recipe_context_stock"))
            Text("Bestand für Prüfung einbeziehen", Modifier.padding(top = 12.dp))
        }
        Row {
            Checkbox(useShopping, { if (it != useShopping) { contextChanged(); useShopping = it } },
                modifier = Modifier.testTag("recipe_context_shopping"))
            Text("Einkaufsbedarf für Prüfung einbeziehen", Modifier.padding(top = 12.dp))
        }
        Row {
            Checkbox(useOffers, { if (it != useOffers) { contextChanged(); useOffers = it } },
                modifier = Modifier.testTag("recipe_context_offers"))
            Text("Angebote prüfen (noch gesperrt)", Modifier.padding(top = 12.dp))
        }
        if (selectedNeedsContext) {
            Button(
                enabled = !contextState.busy,
                onClick = {
                    invalidate()
                    rankingReviewed = false
                    rankingHint = null
                    onRequestContext(selectedFlags)
                },
                modifier = Modifier.testTag("recipe_context_refresh")
            ) { Text("Aktuellen lokalen Kontext prüfen") }
            if (contextState.busy) Text("Lokale Kontextprüfung läuft.")
            contextState.error?.let { Text(it) }
            val binding = boundContext?.contentBinding
            if (binding != null) {
                Text("Room-Kontext gebunden: Bestand ${boundContext.stock?.lines?.size ?: 0}, Einkauf ${boundContext.shopping?.lines?.size ?: 0} Zeilen. Vorschlag bleibt unbestätigt.")
            } else if (!contextState.busy) {
                Text("Kontext nicht vollständig freigegeben. Unbekannte Quellen sind nicht leer oder null Bestand.")
                contextState.context?.gaps?.forEach { Text(it) }
            }
        }
        if (selectedNeedsContext && !useOffers) {
            Text("Kontextauswahl: Mindestens drei passende Zutaten selbst einer aktuellen Katalogversion zuordnen. Keine automatische Gleichsetzung von FoodType und Produkt. Nur Gramm-Versionen werden verglichen.")
            RecipeOfflineDraftGenerator.ingredientTypes.forEach { ingredient ->
                Picker(
                    "Produktversion für ${ingredient.label} (${ingredient.foodTypeId})",
                    listOf("Nicht zugeordnet") + labels.values,
                    rankingVersionIds[ingredient.foodTypeId]?.let { labels[it] }
                        ?: "Nicht zugeordnet"
                ) { selected ->
                    invalidate()
                    rankingReviewed = false
                    rankingHint = null
                    val version = labels.entries.firstOrNull { it.value == selected }?.key
                    rankingVersionIds = if (version == null)
                        rankingVersionIds - ingredient.foodTypeId
                    else rankingVersionIds + (ingredient.foodTypeId to version)
                    offlineAllergenAbsent = offlineAllergenAbsent.filterNot {
                        it.first == ingredient.foodTypeId
                    }.toSet()
                }
            }
            Row {
                Checkbox(
                    checked = rankingReviewed,
                    onCheckedChange = { rankingReviewed = it; rankingHint = null; invalidate() },
                    modifier = Modifier.testTag("recipe_context_mapping_reviewed")
                )
                Text("Ich habe die ausgewählten FoodType-Produktversionen unabhängig zugeordnet. Die Mengenwertung ist nur ein Planungshinweis.", Modifier.padding(top = 10.dp))
            }
        }
        rankingHint?.let { Text(it, Modifier.testTag("recipe_context_ranking_hint")) }
        fun invalidateGeneratedDraft() {
            // A new serving count or exclusion must not retain an old JSON
            // preview, evidence checkpoint or pending FoodStore consent.
            invalidate()
            checkpoint.discard()
            evidenceCheckpoint.discard()
            json = ""
            imported = null
            reviewed = false
            versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
            allergenAnswers = emptyMap(); restrictionsText = ""
            rankingHint = null
            error = null
        }
        OutlinedTextField(
            value = offlineRestrictionsText,
            onValueChange = { next ->
                if (next.length <= 512 && next != offlineRestrictionsText) {
                    invalidateGeneratedDraft()
                    offlineAllergenAbsent = emptySet()
                    offlineAllergenVersions = emptyMap()
                    offlineRestrictionsText = next
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("recipe_offline_restrictions"),
            label = { Text("Ausschlüsse vor dem Vorschlag (z. B. FOOD:tomato)") },
            maxLines = 2
        )
        Text("Lebensmittel-Ausschlüsse filtern nur Vorschläge. Bei Allergenen ohne unabhängig bestätigte Abwesenheit wird kein Vorschlag erzeugt; eine spätere Einzelprüfung bleibt Pflicht.")
        if (offlineAllergens.isNotEmpty()) {
            Fold("Allergennachweise für Offline-Vorschläge") {
                Text("Nur für die Vorschlagsauswahl: Jede Allergen-Abwesenheit muss für eine aktuell zugeordnete Produktversion separat anhand unabhängiger Angaben geprüft werden. Diese Vorprüfung wird nicht in den Entwurf übernommen und ersetzt niemals die spätere vollständige Rezeptprüfung.")
                RecipeOfflineDraftGenerator.ingredientTypes.forEach { ingredient ->
                    val food = ingredient.foodTypeId
                    val selectedVersion = if (selectedNeedsContext)
                        rankingVersionIds[food] else offlineAllergenVersions[food]
                    if (!selectedNeedsContext) {
                        Picker(
                            "Produktversion für Allergen-Vorprüfung: ${ingredient.label} ($food)",
                            listOf("Nicht zugeordnet") + labels.values,
                            selectedVersion?.let { labels[it] } ?: "Nicht zugeordnet"
                        ) { selected ->
                            invalidateGeneratedDraft()
                            val version = labels.entries.firstOrNull {
                                it.value == selected
                            }?.key
                            offlineAllergenVersions = if (version == null)
                                offlineAllergenVersions - food
                            else offlineAllergenVersions + (food to version)
                            offlineAllergenAbsent = offlineAllergenAbsent.filterNot {
                                it.first == food
                            }.toSet()
                        }
                    }
                    offlineAllergens.forEach { allergen ->
                        val key = food to allergen
                        Row {
                            Checkbox(
                                checked = key in offlineAllergenAbsent,
                                enabled = selectedVersion != null,
                                onCheckedChange = { checked ->
                                    invalidateGeneratedDraft()
                                    offlineAllergenAbsent = if (checked)
                                        offlineAllergenAbsent + key
                                    else offlineAllergenAbsent - key
                                },
                                modifier = Modifier.testTag(
                                    "recipe_offline_allergen_absent_${food}_${allergen}"
                                )
                            )
                            Text("${ingredient.label}: ${allergen} für die ausgewählte Produktversion unabhängig als nicht enthalten geprüft",
                                Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = offlinePortions,
            onValueChange = { next ->
                if (next.length <= 4 && next.all(Char::isDigit) &&
                    next != offlinePortions) {
                    invalidateGeneratedDraft()
                    offlinePortions = next
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("recipe_offline_portions"),
            label = { Text("Portionen für Offline-Vorschlag (1–1000)") },
            singleLine = true
        )
        Button(
            enabled = (offlinePortions.toIntOrNull() ?: 0) in 1..1000 &&
                (!selectedNeedsContext || (boundContext != null && !useOffers &&
                    rankingReviewed && rankingVersionIds.size >= 3)),
            modifier = Modifier.testTag("recipe_offline_generate"),
            onClick = {
                try {
                    val issuedAt = OffsetDateTime.now()
                    val snapshotAt = boundContext?.contentBinding?.snapshotAt?.let {
                        OffsetDateTime.ofInstant(it, ZoneOffset.UTC)
                    }
                    val requestedRestrictions = offlineRestrictionsText.split(",")
                        .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    require(requestedRestrictions.all(Gate::isSupportedRestriction)) {
                        "unsupported offline restriction"
                    }
                    val confirmedAllergenAbsences = RecipeOfflineAllergenEvidence.confirmedAbsent(
                        catalog,
                        if (selectedNeedsContext) rankingVersionIds else offlineAllergenVersions,
                        offlineAllergenAbsent, requestedRestrictions,
                        issuedAt.toInstant().toEpochMilli()
                    )
                    val chosenVariant = if (selectedNeedsContext) {
                        val ranked = RecipeOfflineContextRanking.rank(
                            offlinePortions.toInt(), selectedFlags,
                            requireNotNull(boundContext), catalog,
                            rankingVersionIds, rankingReviewed,
                            requestedRestrictions, confirmedAllergenAbsences
                        )
                        require(ranked.isNotEmpty()) { "no independently safe recipe candidate" }
                        val first = ranked.first()
                        rankingHint = "Kontext-Hinweis: potenziell ${first.stockCoveredMilliGrams / 1000} g im Bestand; ${first.shoppingNeedMilliGrams / 1000} g vorgemerkter Einkaufsbedarf. Mengenbasis, Zustand und Allergene weiterhin einzeln prüfen."
                        first.variant
                    } else requireNotNull(RecipeOfflineDraftGenerator.nextAllowedVariant(
                        offlineVariant, requestedRestrictions, confirmedAllergenAbsences
                    )) { "no independently safe recipe candidate" }
                    val generated = RecipeOfflineDraftGenerator.generate(
                        offlinePortions.toInt(), chosenVariant,
                        issuedAt, "offline-" + UUID.randomUUID().toString(),
                        selectedFlags, snapshotAt, requestedRestrictions,
                        confirmedAllergenAbsences
                    )
                    // Use the identical bounded v1 import boundary as pasted JSON.
                    val checked = RecipeDraftV1Import.read(
                        generated, emptyMap(), OffsetDateTime.now()
                    )
                    checkpoint.discard()
                    evidenceCheckpoint.discard()
                    invalidate()
                    versionIds = emptyMap(); bases = emptyMap(); states = emptyMap()
                    allergenAnswers = emptyMap(); restrictionsText = offlineRestrictionsText; reviewed = false
                    json = generated
                    imported = checked
                    error = null
                    if (!selectedNeedsContext) offlineVariant =
                        (chosenVariant + 1) % RecipeOfflineDraftGenerator.VARIANT_COUNT
                } catch (_: IllegalArgumentException) {
                    rankingHint = null
                    error = "Offline-Entwurf nicht verfügbar: Ausschlüsse, Allergennachweise, Produktzuordnung, Kontext und Mengen prüfen."
                }
            }
        ) { Text("Offline-Rezeptvorschlag erzeugen") }
        error?.let { Text(it, Modifier.testTag("recipe_draft_import_error")) }
        imported?.let { parsed ->
            val draft = parsed.draft
            Text("Entwurf: ${draft.title} · ${draft.portions} Soll-Portionen")
            Text("Entwurfs-ID: ${parsed.draftId} · ${draft.lines.size} Zutaten")
            val contextRequested = draft.flags.considerStock || draft.flags.considerShopping ||
                draft.flags.considerOffers
            val verifiedContext = contextState.context?.takeIf {
                contextRequested && !contextState.busy &&
                    contextState.flags == draft.flags &&
                    it.projectedFlags == draft.flags && it.gaps.isEmpty() &&
                    it.contentBindingRequired &&
                    it.contentBinding?.snapshotAt == parsed.snapshotAt?.toInstant()
            }?.contentBinding != null
            if (contextRequested) {
                Text(if (verifiedContext)
                    "Unabhängig gebundener Kontext vorhanden; FoodStore prüft alle Quellen vor Bestätigung und Speichern erneut."
                else "Kontextflags aktiv: Fehlender, veralteter oder abweichender Host-Snapshot sperrt die Übernahme.")
            }
            OutlinedTextField(
                restrictionsText, { evidenceCheckpoint.discard(); invalidate(); restrictionsText = it },
                Modifier.fillMaxWidth().testTag("recipe_draft_restrictions"),
                label = { Text("Bestätigte Ausschlüsse, z. B. ALLERGEN:SOY, FOOD:milk") },
                singleLine = false, maxLines = 2
            )
            val restrictions = restrictionsText.split(",").map { it.trim() }
                .filter { it.isNotEmpty() }.toSet()
            val allergens = restrictions.filter { it.startsWith("ALLERGEN:") && it.length > 9 }
                .map { it.removePrefix("ALLERGEN:") }.toSet()
            Text("Leeres Ausschlussfeld bedeutet keine erfassten Ausschlüsse, nicht geprüfte Allergenfreiheit.")
            Text("Unbestätigte Formulareingaben nur auf ausdrücklichen Wunsch für maximal 24 Stunden verschlüsselt merken. Wiederherstellung ist KEIN Nachweis: aktuelle Katalogversionen und Room werden vor einer Buchung erneut geprüft; die Prüfbestätigung bleibt immer aus.")
            Button(
                modifier = Modifier.testTag("recipe_draft_evidence_save"),
                onClick = {
                    try {
                        evidenceCheckpoint.save(RecipeDraftEvidenceForm(
                            recipeDraftEvidenceDigest(json), versionIds,
                            bases.mapValues { it.value.name },
                            states.mapValues { it.value.name },
                            allergenAnswers, restrictionsText
                        ))
                        error = "Unbestätigte Formulareingaben verschlüsselt gemerkt (maximal 24 Stunden)."
                    } catch (_: Exception) {
                        error = "Verschlüsselte Formularspeicherung nicht möglich."
                    }
                }
            ) { Text("Unbestätigtes Prüf-Formular verschlüsselt merken") }
            Button(
                modifier = Modifier.testTag("recipe_draft_evidence_restore"),
                onClick = {
                    val saved = evidenceCheckpoint.load()
                    if (saved == null || saved.draftHash != recipeDraftEvidenceDigest(json)) {
                        error = "Kein gültiges Formular für genau diesen JSON-Entwurf vorhanden."
                    } else {
                        // Never trust saved product identities. Re-resolve current,
                        // non-demo latest versions and require a NEW manual gesture.
                        val ids = draft.lines.map { it.foodTypeId }.toSet()
                        val latest = eligible.groupBy { it.productId }.mapValues { (_, rows) ->
                            rows.filter { it.revision == rows.maxOf { row -> row.revision } }
                                .singleOrNull()?.id
                        }
                        versionIds = saved.versionIds.filter { (food, version) ->
                            food in ids && eligible.any {
                                it.id == version && latest[it.productId] == version
                            }
                        }
                        bases = saved.bases.mapNotNull { (food, basis) ->
                            val line = draft.lines.singleOrNull { it.foodTypeId == food }
                            if (line != null && basis == line.basis.name &&
                                line.basis != Gate.Basis.UNKNOWN) food to line.basis else null
                        }.toMap()
                        states = saved.states.mapNotNull { (food, state) ->
                            if (food in ids && state in setOf("RAW", "COOKED"))
                                food to Gate.State.valueOf(state) else null
                        }.toMap()
                        val restoredAllergens = saved.restrictionsText.split(",")
                            .map { it.trim() }.filter { it.startsWith("ALLERGEN:") }
                            .map { it.removePrefix("ALLERGEN:") }.toSet()
                        allergenAnswers = saved.allergens.filterKeys {
                            it.first in ids && it.second in restoredAllergens
                        }
                        restrictionsText = saved.restrictionsText
                        reviewed = false
                        invalidate()
                        error = "Formular nur als unbestätigte Eingabe geladen. Alle Nachweise und aktuellen Versionen erneut prüfen; neue Bestätigung erforderlich."
                    }
                }
            ) { Text("Unbestätigtes Prüf-Formular laden") }
            draft.lines.forEach { line ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${line.label} (${line.foodTypeId}) · ${line.amount ?: "Menge unbekannt"} ${line.unit} · Modellbasis ${line.basis}")
                    Picker(
                        "Unabhängig geprüfte Katalogversion",
                        listOf("Nicht zugeordnet") + labels.values,
                        versionIds[line.foodTypeId]?.let { labels[it] } ?: "Nicht zugeordnet"
                    ) { selected ->
                        evidenceCheckpoint.discard()
                        invalidate()
                        versionIds = versionIds + (line.foodTypeId to
                            (labels.entries.firstOrNull { it.value == selected }?.key ?: ""))
                    }
                    Text("Mengenbasis separat am Produkt prüfen")
                    Choices(listOf("Ungeprüft", line.basis.name),
                        bases[line.foodTypeId]?.name ?: "Ungeprüft") { selected ->
                        evidenceCheckpoint.discard()
                        invalidate()
                        bases = if (selected == "Ungeprüft") bases - line.foodTypeId
                        else bases + (line.foodTypeId to line.basis)
                    }
                    Text("Zubereitungszustand separat prüfen")
                    Choices(listOf("Ungeprüft", "RAW", "COOKED"),
                        states[line.foodTypeId]?.name ?: "Ungeprüft") { selected ->
                        evidenceCheckpoint.discard()
                        invalidate()
                        states = if (selected == "Ungeprüft") states - line.foodTypeId
                        else states + (line.foodTypeId to Gate.State.valueOf(selected))
                    }
                    allergens.forEach { allergen ->
                        val key = line.foodTypeId to allergen
                        val selected = when (allergenAnswers[key]) {
                            true -> "Nachweislich enthalten"
                            false -> "Nachweislich nicht enthalten"
                            null -> "Unbekannt"
                        }
                        Text("Allergen ${allergen}: unabhängige Prüfung dieser Zutat")
                        Choices(listOf("Unbekannt", "Nachweislich enthalten",
                            "Nachweislich nicht enthalten"), selected) { answer ->
                            evidenceCheckpoint.discard()
                            invalidate()
                            allergenAnswers = allergenAnswers + (key to when (answer) {
                                "Nachweislich enthalten" -> true
                                "Nachweislich nicht enthalten" -> false
                                else -> null
                            })
                        }
                    }
                }
            }
            Row {
                Checkbox(checked = reviewed, onCheckedChange = { reviewed = it },
                    modifier = Modifier.testTag("recipe_draft_manual_review_checkbox"))
                Text("Ich habe Katalogzuordnung, Basis, Zustand und angegebene Ausschlüsse unabhängig vom JSON geprüft.",
                    Modifier.padding(top = 10.dp))
            }
            Button(
                enabled = reviewed && (!contextRequested || verifiedContext),
                modifier = Modifier.testTag("recipe_draft_manual_review"),
                onClick = {
                    try {
                        val choices = draft.lines.associate { line ->
                            line.foodTypeId to RecipeDraftManualReview.Choice(
                                versionIds[line.foodTypeId].orEmpty(),
                                bases[line.foodTypeId], states[line.foodTypeId],
                                allergens.associateWith { allergen ->
                                    allergenAnswers[line.foodTypeId to allergen]
                                }
                            )
                        }
                        val prepared = RecipeDraftManualReview.prepare(
                            parsed, catalog, choices, restrictions, reviewed,
                            System.currentTimeMillis(), independentlyBoundContext = verifiedContext
                        )
                        error = null
                        reviewed = false
                        onReview(json, prepared.request, prepared.confirmations,
                            prepared.reviewedStates)
                    } catch (_: IllegalArgumentException) {
                        error = "Manuelle Prüfung unvollständig oder widersprüchlich: Version, Mengenbasis, Zustand und Ausschlüsse prüfen."
                    }
                }
            ) { Text("Geprüfte Vorschau zur separaten Bestätigung") }
            Text("Die nächste Gerätebestätigung gilt nur für diese Vorschau. Erst eine weitere ausdrückliche Aktion speichert das Rezept; Herstellung und Verzehr bleiben getrennte Schritte.")
        }
    }
}
