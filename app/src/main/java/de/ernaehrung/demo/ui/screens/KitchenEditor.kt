package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun KitchenEditor(state: LocalDataState, blocked: Boolean, key: String, model: LocalDataViewModel,
    onOpenStorageCapacity: ((String?) -> Unit)? = null) {
    Heading("Rezepte & tatsächliche Herstellung", "Kochen ist kein Essen.")
    Hint("Rezept speichern erzeugt keinen Bestand. Erst die bestätigte Herstellung entnimmt Zutaten und legt eine neue Fertigportionencharge an. Portionsnährwerte werden aus tatsächlichen Zutaten und Ausbeuten berechnet; Datenlücken bleiben sichtbar.", Sage)
    RecipeEditor(state, blocked, key, model)
    val draftConsent by model.recipeDraftConsentState.collectAsStateWithLifecycle()
    val recipeContext by model.recipeContextState.collectAsStateWithLifecycle()
    val productionPrefill by model.productionPrefillState.collectAsStateWithLifecycle()
    val personal by model.personalRestrictionState.collectAsStateWithLifecycle()
    val personalReady = personal.snapshot != null && !personal.readError
    if (personalReady) {
        Text(if (personal.snapshot!!.tokens.isEmpty()) "Keine eigenen bestätigten Rezeptausschlüsse. Das ist kein Nachweis von Allergenfreiheit."
            else "Eigene bestätigte Rezeptvorgaben: ${personal.snapshot!!.tokens.sorted().joinToString(", ")}", Modifier.testTag("recipe_personal_restrictions"))
    } else if (personal.readError) {
        Hint("Eigene Vorgaben gerade nicht lesbar. Vor einer neuen Rezeptprüfung erneut lesen.")
        OutlinedButton(model::retryPersonalRestrictions) { Text("Eigene Vorgaben neu lesen") }
    }
    RecipeDraftImportPanel(
        catalog = state.versions,
        editingEnabled = !draftConsent.busy && personalReady,
        personalRestrictions = personal.snapshot?.tokens.orEmpty(),
        personalRestrictionsBinding = personal.snapshot?.binding,
        personalRestrictionsReady = personalReady,
        recordedDigest = draftConsent.recordedDigest,
        contextState = recipeContext,
        onRequestContext = model::requestRecipeDraftContext,
        onClearContext = model::clearRecipeDraftContext,
        onInvalidate = { model.clearRecipeDraftConsent() },
        onDiscardPending = { model.discardPendingRecipeDraftConsent() },
        onReview = { json, request, facts, states ->
            model.prepareRecipeDraftConsent(json, request, facts, states)
        }
    )
    // This panel appears only after a separately host-reviewed draft was supplied
    // to the ViewModel. No untrusted generator output can activate it on its own.
    if (personalReady && (draftConsent.review != null || draftConsent.error != null)) {
        Panel {
            draftConsent.previewNutrition?.let { nutrition ->
                Section("Nährwerte der geprüften Vorschau", "Je Soll-Portion aus den zugeordneten Produktversionen")
                NutrientLines(nutrition, "recipe_draft_preview")
                Fold("Weitere Nährwerte der Vorschau") {
                    NutrientLines(nutrition, "recipe_draft_preview", extra = true)
                }
                QualityNotes(nutrition)
                Text("Rezeptvorschau, keine tatsächliche Mahlzeit. Herstellung und Verzehr werden getrennt bestätigt.")
            }
            draftConsent.review?.let { review ->
                RecipeDraftDeviceCredentialReview(review,
                    sessionKey = draftConsent.sessionId,
                    operationKey = draftConsent.operationKey,
                    requestNonce = model::beginRecipeDraftCredentialAction,
                    resetVersion = draftConsent.attemptVersion,
                    actionEnabled = !draftConsent.busy && draftConsent.recordedDigest == null &&
                        draftConsent.sessionId != null && draftConsent.operationKey != null) { digest, renderedSessionId, proof ->
                    model.confirmRecipeDraftConsent(digest, renderedSessionId, proof)
                }
            }
            if (draftConsent.busy) CircularProgressIndicator()
            draftConsent.error?.let { Hint(it) }
            draftConsent.recordedDigest?.let { digest ->
                if (draftConsent.savedRecipeVersionId == null) {
                    Text("Rezeptvorschau ausdrücklich bestätigt und lokal protokolliert. Noch kein Rezept gespeichert.")
                    Button(
                        onClick = { model.saveConfirmedRecipeDraft(digest) },
                        enabled = !draftConsent.busy,
                        modifier = Modifier.testTag("recipe_draft_save_confirmed")
                    ) { Text("Geprüfte Rezeptversion jetzt speichern") }
                }
            }
            draftConsent.savedRecipeVersionId?.let { version ->
                Text("Rezeptversion lokal gespeichert: $version. Keine Herstellung oder Verzehrbuchung.")
                Button(
                    onClick = { model.prepareProductionForSavedRecipe(version) },
                    enabled = !draftConsent.busy && state.recipes.any { it.id == version },
                    modifier = Modifier.testTag("recipe_production_prepare")
                ) { Text("Herstellung mit dieser Rezeptversion vorbereiten") }
            }
        }
    }
    if (state.recipes.isNotEmpty()) ProductionEditor(state, blocked, key, model, productionPrefill, onOpenStorageCapacity)
    // Consumption is a distinct user gesture and FoodStore transaction, never a side effect
    // of recipe save or production. Only released output batches are offered here.
    if (eligibleConsumptionBatches(state.batches, preparedOnly = true).isNotEmpty()) {
        ConsumptionEditor(state, blocked, key, model, preparedOnly = true)
    }
    state.recipes.forEach { recipe -> Panel {
        Section(recipe.name, "Version ${recipe.revision} · ${Amounts.format(recipe.yieldPortions)} Soll-Portionen")
        state.ingredients.filter { it.ingredient.recipeVersionId == recipe.id }.forEach { item ->
            KV(item.productName, item.ingredient.amount?.let { "${Amounts.format(it)} ${item.ingredient.unit.label}" } ?: "Menge unbekannt")
        }
        if (recipe.steps.isNotBlank()) Text(recipe.steps)
        Fold("Nährwerte pro Rezeptportion · Vorschau") {
            val calculated by remember(model, recipe.id) { model.recipeNutrition(recipe.id) }.collectAsStateWithLifecycle()
            calculated.error?.let { Hint(it) }
            calculated.report?.let { nutrition ->
                NutrientLines(nutrition, "recipe_${recipe.id}"); QualityNotes(nutrition)
                Text("Soll-Rezept, keine tatsächliche Mahlzeit. Ein realer Ansatz kann andere Mengen und Ausbeuten haben.")
            } ?: if (calculated.error == null) CircularProgressIndicator() else Unit
        }
    } }
}

@Composable
private fun RecipeEditor(state: LocalDataState, blocked: Boolean, key: String, model: LocalDataViewModel) {
    var recipeId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var portions by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var versions by rememberSaveable { mutableStateOf<List<String>>(listOf("")) }
    var quantities by rememberSaveable { mutableStateOf<List<String>>(listOf("")) }
    Fold("Rezept speichern / neue Version") {
        IdPicker("Rezept bearbeiten", listOf("" to "Neues Rezept") + state.recipes.map { it.recipeId to "${it.name} · v${it.revision}" }, recipeId) { chosen ->
            recipeId = chosen
            val previous = state.recipes.find { it.recipeId == chosen }
            name = previous?.name.orEmpty(); portions = previous?.yieldPortions?.let(Amounts::format).orEmpty()
            steps = previous?.steps.orEmpty(); reason = ""
            val items = state.ingredients.filter { it.ingredient.recipeVersionId == previous?.id }
            versions = ArrayList(items.map { it.ingredient.productVersionId }.ifEmpty { listOf("") })
            quantities = ArrayList(items.map { it.ingredient.amount?.let(Amounts::format).orEmpty() }.ifEmpty { listOf("") })
        }
        Field("Rezeptname", name, { name = it })
        Field("Soll-Portionen des ganzen Rezepts", portions, { portions = it })
        Text("Zutaten für den gesamten Ansatz. Leere Mengen bleiben unbekannt.")
        versions.indices.forEach { position ->
            val product = state.versions.find { it.id == versions[position] }
            IdPicker("Artikelversion Zutat ${position + 1}", state.versions.map { it.id to "${it.name} · v${it.revision} · ${it.baseUnit.label} · ${it.id.take(8)}" }, versions[position]) { chosen ->
                versions = ArrayList(versions).apply { this[position] = chosen }
            }
            Field("Rezeptmenge Zutat ${position + 1} in ${product?.baseUnit?.label ?: "Basiseinheit"}", quantities[position], { text -> quantities = ArrayList(quantities).apply { this[position] = text } })
            if (versions.size > 1) Action("Zutat ${position + 1} entfernen", true) {
                versions = ArrayList(versions).apply { removeAt(position) }; quantities = ArrayList(quantities).apply { removeAt(position) }
            }
        }
        Action("Weitere Rezeptzutat", true) { versions = ArrayList(versions).apply { add("") }; quantities = ArrayList(quantities).apply { add("") } }
        OutlinedTextField(steps, { steps = it }, Modifier.fillMaxWidth(), label = { Text("Zubereitungsschritte") }, minLines = 3)
        Field("Änderungsgrund des Rezepts", reason, { reason = it })
        SaveButton("Rezept lokal speichern", blocked) {
            val recipeName = name; val yield = portions; val description = steps; val change = reason
            val ingredients = versions.zip(quantities).toList(); val identity = recipeId.takeIf { it.isNotEmpty() }
            val previous = state.recipes.find { it.recipeId == identity }
            model.save(key) { saveRecipe(key, RecipeInput(recipeName, Amounts.parse(yield),
                ingredients.map { (id, text) -> RecipeItemInput(id, text.takeIf { it.isNotBlank() }?.let(Amounts::parse)) },
                description, previous?.storageInstructions, previous?.safetySource), identity, change) }
        }
    }
}

@Composable
private fun ProductionEditor(state: LocalDataState, blocked: Boolean, key: String, model: LocalDataViewModel,
    preselected: ProductionPrefillUiState?, onOpenStorageCapacity: ((String?) -> Unit)?) {
    var allocationMode by rememberSaveable { mutableStateOf("Chargen selbst eintragen") }
    var selected by rememberSaveable { mutableStateOf("") }
    var batches by rememberSaveable { mutableStateOf<List<String>>(listOf("")) }
    var quantities by rememberSaveable { mutableStateOf<List<String>>(listOf("")) }
    var portions by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(AppCalendar.today().toString()) }
    var storageNote by rememberSaveable { mutableStateOf("") }
    var deviation by rememberSaveable { mutableStateOf("") }
    var useBy by rememberSaveable { mutableStateOf("") }
    var checked by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val capacityState by model.storageCapacity.collectAsStateWithLifecycle()
    var useCapacity by remember { mutableStateOf(false) }
    var outerVolume by remember { mutableStateOf("") }
    var capacityConfirmed by remember { mutableStateOf(false) }
    var capacityBasis by remember { mutableStateOf<String?>(null) }
    val capacityData = capacityState.snapshot
    val currentCapacity = capacityData?.history?.filter { it.locationId == location }?.maxByOrNull { it.revision }
    val freeCapacity = if (currentCapacity == null || capacityData == null) null
        else StorageCapacityRules.trustedFree(currentCapacity, capacityData.history, capacityData.reversedOperationIds)
    val parsedOuterVolume = outerVolume.trim().toLongOrNull()?.takeIf { it >= 0L }
    LaunchedEffect(useCapacity, location, currentCapacity?.id, capacityState.readError, capacityData?.reversedOperationIds) {
        capacityBasis = if (useCapacity) currentCapacity?.id else null
        capacityConfirmed = false
        if (useCapacity) confirmed = false
    }
    LaunchedEffect(preselected?.requestSequence) {
        if (preselected != null && state.recipes.any { it.id == preselected.recipeVersionId }) {
            selected = preselected.recipeVersionId
            val count = state.ingredients.count { it.ingredient.recipeVersionId == selected }.coerceAtLeast(1)
            batches = ArrayList(List(count) { "" })
            quantities = ArrayList(List(count) { "" })
            portions = ""; location = ""; storageNote = ""; deviation = ""; useBy = ""
            checked = false; released = false; confirmed = false
        }
    }
    Fold("Tatsächliche Herstellung buchen", openRequest = preselected?.requestSequence?.toString()) {
        val recipe = state.recipes.find { it.id == selected }
        val ingredients = state.ingredients.filter { it.ingredient.recipeVersionId == selected }
        val versions = ingredients.map { it.ingredient.productVersionId }.toSet()
        IdPicker("Rezept für Herstellung", state.recipes.map { it.id to "${it.name} · v${it.revision}" }, selected) { chosen ->
            selected = chosen; confirmed = false
            val count = state.ingredients.count { it.ingredient.recipeVersionId == chosen }.coerceAtLeast(1)
            batches = ArrayList(List(count) { "" }); quantities = ArrayList(List(count) { "" })
        }
        recipe?.let { Text("Rezeptbezug: ${Amounts.format(it.yieldPortions)} Soll-Portionen. Tatsächliche Mengen und Ausbeute separat eintragen.") }
        Field("Tatsächlich hergestellte Portionen", portions, { portions = it; confirmed = false })
        Field("Herstellungsdatum JJJJ-MM-TT", date, { date = it; confirmed = false })
        WrappingChoices(listOf("Chargen selbst eintragen", "Chargen nach FEFO vorschlagen"), allocationMode, "production_source_") {
            allocationMode = it; confirmed = false
        }
        val asOf = AppCalendar.today().toString()
        val proposal = if (allocationMode == "Chargen nach FEFO vorschlagen") remember(
            recipe, ingredients, portions, state.versions, state.batches, state.locations, date, asOf
        ) {
            ProductionStockPreviews.build(recipe, ingredients.map { it.ingredient },
                portions.takeIf(String::isNotBlank)?.let { runCatching { Amounts.parse(it) }.getOrNull() },
                state.versions, state.batches, state.locations, date, asOf)
        } else null
        if (proposal != null) {
            Panel {
                Section("Chargenvorschlag für diese Herstellung", "Rezeptmengen exakt auf tatsächliche Portionen skaliert · noch keine Buchung")
                proposal.issue?.let { Text(it) }
                proposal.ingredients.forEach { ingredient ->
                    val name = state.versions.single { it.id == ingredient.versionId }.name
                    Text("$name: benötigt ${Amounts.format(ingredient.amount)} ${ingredient.unit.label}")
                    ingredient.stock.issue?.let { Text(it) }
                    ingredient.stock.allocations.forEach { allocation ->
                        val row = state.batches.single { it.batch.id == allocation.batchId }
                        Text("${Amounts.format(allocation.amount)} ${row.unit.label} · ${row.locationName} · ${row.batch.id}",
                            Modifier.testTag("production_fefo_${row.batch.id}"))
                    }
                    ingredient.stock.missing?.let {
                        Text("$name: nicht gedeckt ${Amounts.format(it)} ${ingredient.unit.label}",
                            Modifier.testTag("production_fefo_missing_${ingredient.versionId}"))
                    }
                    ingredient.stock.excluded.forEach { Text("${it.batchId}: ${it.reason.label}") }
                }
                Text("Nur übernehmen, wenn diese Mengen und Chargen tatsächlich verwendet wurden. Für abweichende Mengen die Chargen selbst eintragen. Änderungen erfordern eine neue Bestätigung.")
            }
        } else {
            val available = state.batches.filter { !it.originReversed && it.stockAmount > 0 && it.batch.versionId in versions }
            batches.indices.forEach { position ->
                val batch = state.batches.find { it.batch.id == batches[position] }
                IdPicker("Zutatencharge ${position + 1}", available.map { it.batch.id to "${it.productName} · ${it.locationName} · ${Amounts.format(it.stockAmount)} ${it.unit.label} · ${it.batch.id.take(8)}" }, batches[position]) { chosen ->
                    batches = ArrayList(batches).apply { this[position] = chosen }; confirmed = false
                }
                Field("Tatsächliche Zutatmenge ${position + 1} in ${batch?.unit?.label ?: "Basiseinheit"}", quantities[position], { text -> quantities = ArrayList(quantities).apply { this[position] = text }; confirmed = false })
                if (batches.size > 1) Action("Chargenzeile ${position + 1} entfernen", true) {
                    batches = ArrayList(batches).apply { removeAt(position) }; quantities = ArrayList(quantities).apply { removeAt(position) }; confirmed = false
                }
            }
            Action("Weitere Zutatencharge", true) { batches = ArrayList(batches).apply { add("") }; quantities = ArrayList(quantities).apply { add("") }; confirmed = false }
        }
        IdPicker("Fertigportionen lagern in", state.locations.filter { it.confirmed }.map { it.id to it.name }, location) { location = it; confirmed = false; checked = false }
        Check("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft", checked) { checked = it; confirmed = false }
        if (onOpenStorageCapacity != null) OutlinedButton({ onOpenStorageCapacity(location.takeIf { it.isNotBlank() }) },
            enabled = !blocked, modifier = Modifier.testTag("production_open_capacity")) { Text("Lagerkapazität vor Ort prüfen") }
        Check("Herstellung an strukturierte physische Kapazitätsprüfung binden", useCapacity) {
            useCapacity = it; capacityConfirmed = false; confirmed = false
        }
        if (useCapacity) {
            Text("Freier Platz der aktuellen Prüfgrundlage: ${freeCapacity?.let { "$it ml" } ?: "unbekannt"}",
                Modifier.testTag("production_capacity_free"))
            if (capacityState.readError) Text("Kapazitätsangaben nicht lesbar; strukturierte Herstellung bleibt gesperrt.")
            if (currentCapacity == null) Text("Zuerst für diesen tatsächlich vorhandenen Lagerort eine physische Kapazitätsprüfung speichern.")
            Field("Zusätzliches Behälter-Außenvolumen · ganze ml", outerVolume, {
                outerVolume = it; capacityConfirmed = false; confirmed = false
            }, modifier = Modifier.testTag("production_capacity_volume"))
            Text("Leer bleibt unbekannt. 0 ist erlaubt, wenn bereits im belegten Platz enthaltene Behälter weiterverwendet werden. Keine Umrechnung aus Lebensmittelmasse oder Portionszahl.")
            Check("Diese Kapazitätsfassung aktuell vor Ort geprüft und zusätzliches Außenvolumen bestätigen", capacityConfirmed) {
                capacityConfirmed = it; confirmed = false
            }
            if (freeCapacity != null && parsedOuterVolume != null && parsedOuterVolume > freeCapacity)
                Text("Zusätzliche Behälter überschreiten den bestätigten freien Platz.")
        } else Text("Keine rechnerische Kapazitätsbindung gewählt. Tatsächlich verfügbaren Platz, Behälter und geeignete Bedingungen selbst prüfen und beschreiben; unbekannter Platz wird nicht als frei berechnet.")
        Field("Geprüfte Lagerbedingungen und Platz beschreiben", storageNote, { storageNote = it; confirmed = false })
        Field("Abweichungen von Rezeptmengen oder Ausbeute · falls nötig", deviation, { deviation = it; confirmed = false })
        Field("Verbrauchsdatum der Portionen · optional", useBy, { useBy = it; confirmed = false })
        Check("Fertigportionen für diesen Test ausdrücklich freigeben", released) { released = it; confirmed = false }
        Hint("Unbekannte Haltbarkeit wird nicht berechnet. Keine automatische Sicherheitsfreigabe. Nährwerte entstehen aus den tatsächlich verwendeten Chargen; fehlende Werte bleiben offen.")
        var proposalApproved by remember(proposal?.digest, allocationMode, selected, portions, date,
            location, checked, storageNote, deviation, useBy, released, key) { mutableStateOf(false) }
        val acceptedProposal = confirmed && (proposal == null || proposal.complete && proposalApproved)
        if (proposal == null || proposal.complete) {
            Check("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen", acceptedProposal) {
                confirmed = it
                if (proposal != null) proposalApproved = it
            }
        }
        val capacityReady = !useCapacity || (!capacityState.readError && currentCapacity != null &&
            currentCapacity.id == capacityBasis && freeCapacity != null && parsedOuterVolume != null &&
            parsedOuterVolume <= freeCapacity && capacityConfirmed)
        SaveButton("Herstellung buchen", blocked || selected.isEmpty() || proposal != null && !proposal.complete || !capacityReady) {
            val recipeVersion = selected; val yield = portions; val day = date; val target = location
            val inputs = batches.zip(quantities).toList(); val storage = storageNote; val deviations = deviation.takeIf { it.isNotBlank() }
            val conditions = checked
            // Read confirmation state at this gesture, not an earlier composition.
            val accepted = confirmed && (proposal == null || proposal.complete && proposalApproved) && (!useCapacity || capacityConfirmed)
            val release = if (released) ReleaseStatus.RELEASED else ReleaseStatus.UNCHECKED
            val expiry = useBy.takeIf { it.isNotBlank() }
            val stockProposal = proposal
            val bindCapacity = useCapacity; val basisToUse = capacityBasis
            val volumeToUse = parsedOuterVolume; val spaceConfirmed = capacityConfirmed
            model.save(key) {
                val capacityProof = if (bindCapacity) ProductionCapacityEvidence(
                    requireNotNull(basisToUse) { "Kapazitätsfassung zuerst prüfen." },
                    requireNotNull(volumeToUse) { "Zusätzliches Außenvolumen zuerst prüfen." }, spaceConfirmed) else null
                val allocations = if (stockProposal == null) inputs.map { (id, amount) -> Allocation(id, Amounts.parse(amount)) }
                    else {
                        require(stockProposal.complete) { "Chargenvorschlag unvollständig. Tatsächliche Zutaten prüfen." }
                        stockProposal.allocations.map { Allocation(it.batchId, it.amount) }
                    }
                produce(key, ProductionInput(recipeVersion, Amounts.parse(yield), allocations,
                    target, day, conditions, storage, release, deviations, useBy = expiry, capacityEvidence = capacityProof), accepted)
            }
        }
    }
}
