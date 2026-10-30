package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.LocalDate

@Composable
internal fun KitchenEditor(state: LocalDataState, blocked: Boolean, key: String, model: LocalDataViewModel) {
    Heading("Rezepte & tatsächliche Herstellung", "Kochen ist kein Essen.")
    Hint("Rezept speichern erzeugt keinen Bestand. Erst die bestätigte Herstellung entnimmt Zutaten und legt eine neue Fertigportionencharge an. Nährwerte der Portionen sind noch unbekannt.", Sage)
    RecipeEditor(state, blocked, key, model)
    if (state.recipes.isNotEmpty()) ProductionEditor(state, blocked, key, model)
    state.recipes.forEach { recipe -> Panel {
        Section(recipe.name, "Version ${recipe.revision} · ${Amounts.format(recipe.yieldPortions)} Soll-Portionen")
        state.ingredients.filter { it.ingredient.recipeVersionId == recipe.id }.forEach { item ->
            KV(item.productName, item.ingredient.amount?.let { "${Amounts.format(it)} ${item.ingredient.unit.label}" } ?: "Menge unbekannt")
        }
        if (recipe.steps.isNotBlank()) Text(recipe.steps)
    } }
}

@Composable
private fun RecipeEditor(state: LocalDataState, blocked: Boolean, key: String, model: LocalDataViewModel) {
    var recipeId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var portions by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var versions by rememberSaveable { mutableStateOf(arrayListOf("")) }
    var quantities by rememberSaveable { mutableStateOf(arrayListOf("")) }
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
private fun ProductionEditor(state: LocalDataState, blocked: Boolean, key: String, model: LocalDataViewModel) {
    var selected by rememberSaveable { mutableStateOf("") }
    var batches by rememberSaveable { mutableStateOf(arrayListOf("")) }
    var quantities by rememberSaveable { mutableStateOf(arrayListOf("")) }
    var portions by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var storageNote by rememberSaveable { mutableStateOf("") }
    var deviation by rememberSaveable { mutableStateOf("") }
    var useBy by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf(false) }
    var released by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    Fold("Tatsächliche Herstellung buchen") {
        val recipe = state.recipes.find { it.id == selected }
        val ingredients = state.ingredients.filter { it.ingredient.recipeVersionId == selected }
        val versions = ingredients.map { it.ingredient.productVersionId }.toSet()
        IdPicker("Rezept für Herstellung", state.recipes.map { it.id to "${it.name} · v${it.revision}" }, selected) { chosen ->
            selected = chosen; confirmed = false
            val count = state.ingredients.count { it.ingredient.recipeVersionId == chosen }.coerceAtLeast(1)
            batches = ArrayList(List(count) { "" }); quantities = ArrayList(List(count) { "" })
        }
        recipe?.let { Text("Rezeptbezug: ${Amounts.format(it.yieldPortions)} Soll-Portionen. Tatsächliche Mengen und Ausbeute separat eintragen.") }
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
        Field("Tatsächlich hergestellte Portionen", portions, { portions = it; confirmed = false })
        Field("Herstellungsdatum JJJJ-MM-TT", date, { date = it; confirmed = false })
        IdPicker("Fertigportionen lagern in", state.locations.filter { it.confirmed }.map { it.id to it.name }, location) { location = it; confirmed = false; checked = false }
        Check("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft", checked) { checked = it; confirmed = false }
        Field("Geprüfte Lagerbedingungen und Platz beschreiben", storageNote, { storageNote = it; confirmed = false })
        Field("Abweichungen von Rezeptmengen oder Ausbeute · falls nötig", deviation, { deviation = it; confirmed = false })
        Field("Verbrauchsdatum der Portionen · optional", useBy, { useBy = it; confirmed = false })
        Check("Fertigportionen für diesen Test ausdrücklich freigeben", released) { released = it; confirmed = false }
        Hint("Unbekannte Haltbarkeit wird nicht berechnet. Keine automatische Sicherheitsfreigabe. Nährwerte der Fertigportionen sind noch unbekannt.")
        Check("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen", confirmed) { confirmed = it }
        SaveButton("Herstellung buchen", blocked || selected.isEmpty()) {
            val recipeVersion = selected; val yield = portions; val day = date; val target = location
            val inputs = batches.zip(quantities).toList(); val storage = storageNote; val deviations = deviation.takeIf { it.isNotBlank() }
            val conditions = checked; val accepted = confirmed; val release = if (released) ReleaseStatus.RELEASED else ReleaseStatus.UNCHECKED
            val expiry = useBy.takeIf { it.isNotBlank() }
            model.save(key) { produce(key, ProductionInput(recipeVersion, Amounts.parse(yield),
                inputs.map { (id, amount) -> Allocation(id, Amounts.parse(amount)) }, target, day, conditions, storage, release, deviations, useBy = expiry), accepted) }
        }
    }
}
