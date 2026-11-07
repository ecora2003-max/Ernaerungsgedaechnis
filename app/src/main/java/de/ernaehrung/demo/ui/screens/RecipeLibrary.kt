package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*

@Composable
internal fun RecipeLibrary(state: LocalDataState, blocked: Boolean, model: LocalDataViewModel) {
    Section("Gespeichertes Rezeptgedächtnis","Aktuelle Fassungen · offline durchsuchbar")
    var query by remember { mutableStateOf("") }
    var coveredOnly by remember { mutableStateOf(false) }
    var mealPrepOnly by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }
    val favorites by model.recipeFavoriteState.collectAsStateWithLifecycle()
    val favoriteSave by model.recipeFavoriteSaveState.collectAsStateWithLifecycle()
    val favoriteReady = favorites.snapshot != null && !favorites.readError
    val favoriteRecipeIds = favorites.snapshot?.recipeIds.orEmpty()
    val day = AppCalendar.today().toString()
    val entries = remember(state.recipes,state.ingredients,state.versions,state.batches,state.locations,day) {
        RecipeLibraryPreviews.build(state.recipes,state.ingredients.map { it.ingredient },state.versions,
            state.batches,state.locations,day)
    }
    val selected = remember(entries,query,coveredOnly,mealPrepOnly,favoritesOnly,favoriteRecipeIds) {
        RecipeLibraryPreviews.select(entries,query,coveredOnly,mealPrepOnly,favoritesOnly,favoriteRecipeIds)
    }
    OutlinedTextField(query,{ if (it.length <= 160) query = it },Modifier.fillMaxWidth().testTag("recipe_library_search"),
        label = { Text("Rezeptname oder Zutaten suchen") },singleLine = true)
    Check("Nur mit vollständig vorhandenen Zutaten",coveredOnly) { coveredOnly = it }
    Check("Nur als Meal Prep gekennzeichnete Rezepte",mealPrepOnly) { mealPrepOnly = it }
    // A failed preference read must still let the user turn this filter off.
    Check("Nur eigene Favoriten",favoritesOnly) { favoritesOnly = it }
    if (favorites.readError) {
        Hint("Favoriten gerade nicht lesbar. Auswahl und Änderungen erst nach erneutem Lesen möglich.")
        OutlinedButton(model::retryRecipeFavorites) { Text("Favoriten neu lesen") }
    } else if (!favoriteReady) Text("Favoritenstand wird gelesen.")
    if (favoriteSave.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    favoriteSave.message?.let { Text(it,Modifier.testTag("recipe_favorite_saved")) }
    favoriteSave.error?.let { Hint(it) }
    Text("Bestandsprüfung für den gesamten gespeicherten Ansatz am $day. Nur passende Artikelversionen und freigegebene, zeitlich nutzbare Chargen an bestätigten Lagerorten zählen. Keine Reservierung oder Prüfung von Allergenen, Kühlung und Behälterplatz. Vor der Herstellung erneut prüfen.")
    Text(if (favoritesOnly && !favoriteReady) "Favoritenanzahl unbekannt" else "${selected.size} von ${entries.size} Rezepten",
        Modifier.testTag("recipe_library_count"))
    if (favoritesOnly && !favoriteReady) Text("Ohne lesbaren Favoritenstand keine Favoritenauswahl ableitbar.")
    else if (selected.isEmpty()) Text(if (entries.isEmpty()) "Noch keine eigene Rezeptversion gespeichert." else
        "Kein Rezept entspricht dieser Suche und Auswahl. Fehlende oder ungeklärte Zutaten unter allen Rezepten prüfen.")
    selected.forEach { entry ->
        val recipe = entry.recipe
        Panel(Modifier.testTag("recipe_library_${recipe.id}")) {
            Section(recipe.name,"Version ${recipe.revision} · ${Amounts.format(recipe.yieldPortions)} Soll-Portionen${if (recipe.mealPrep) " · Meal Prep" else ""}")
            val isFavorite = recipe.recipeId in favoriteRecipeIds
            val favoritePrevious = favorites.snapshot?.latest?.get(recipe.recipeId)?.id
            OutlinedButton({ model.setRecipeFavorite(RecipeFavoriteInput(recipe.id,favoritePrevious,!isFavorite)) },
                enabled = favoriteReady && !favoriteSave.busy,modifier = Modifier.testTag("recipe_favorite_${recipe.id}")) {
                Text(if (!favoriteReady) "Favoritenstand unbekannt" else if (isFavorite) "Favorit entfernen" else "Als Favorit merken")
            }
            Text(if (entry.stock.complete) "Zutaten für den gesamten Soll-Ansatz vorhanden." else
                "Zutatenbestand für den gesamten Soll-Ansatz nicht vollständig belegt.",Modifier.testTag("recipe_library_stock_${recipe.id}"))
            entry.ingredients.forEachIndexed { index,line ->
                KV(entry.ingredientNames[index],line.amount?.let { "${Amounts.format(it)} ${line.unit.label}" } ?: "Menge unbekannt")
            }
            if (!entry.stock.complete) Fold("Fehlende oder ungeklärte Zutaten · ${recipe.name}") {
                entry.stock.issue?.let { Text(it) }
                entry.stock.ingredients.forEach { ingredient ->
                    val name = state.versions.single { it.id == ingredient.versionId }.name
                    val stock = ingredient.stock
                    Text("$name · benötigt ${Amounts.format(ingredient.amount)} ${ingredient.unit.label}")
                    stock.issue?.let { Text(it) }
                    stock.missing?.takeIf { it > 0L }?.let { Text("Fehlend: ${Amounts.format(it)} ${ingredient.unit.label}") }
                    stock.excluded.forEach { Text("Nicht angerechnet: ${Amounts.format(it.amount)} ${ingredient.unit.label} · ${it.reason.label}") }
                }
            }
            if (recipe.steps.isNotBlank()) Fold("Zubereitung · ${recipe.name}") { Text(recipe.steps) }
            Fold("Nährwerte pro Rezeptportion · Vorschau") {
                val calculated by remember(model,recipe.id) { model.recipeNutrition(recipe.id) }.collectAsStateWithLifecycle()
                calculated.error?.let { Hint(it) }
                calculated.report?.let { nutrition ->
                    NutrientLines(nutrition,"recipe_${recipe.id}"); QualityNotes(nutrition)
                    Text("Soll-Rezept, keine tatsächliche Mahlzeit. Ein realer Ansatz kann andere Mengen und Ausbeuten haben.")
                } ?: if (calculated.error == null) CircularProgressIndicator() else Unit
            }
            OutlinedButton({ model.prepareProductionForRecipe(recipe.id) },enabled = !blocked,
                modifier = Modifier.testTag("recipe_library_prepare_${recipe.id}")) { Text("Herstellung dieser Rezeptfassung vorbereiten") }
        }
    }
}
