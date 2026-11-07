package de.ernaehrung.demo.data.local

/** Current recipe versions and a read-only check of the full original yield.
 * Stock coverage grants neither allergen clearance nor permission to cook.
 */
internal data class RecipeLibraryEntry(val recipe: RecipeVersionEntity,
    val ingredients: List<RecipeIngredientEntity>, val ingredientNames: List<String>,
    val stock: ProductionStockPreview)

internal object RecipeLibraryPreviews {
    fun build(recipes: List<RecipeVersionEntity>, ingredients: List<RecipeIngredientEntity>,
        versions: List<ProductVersionEntity>, batches: List<BatchRow>, locations: List<StorageLocationEntity>,
        date: String): List<RecipeLibraryEntry> = recipes.groupBy { it.recipeId }.values.map { history ->
        val recipe = history.maxBy { it.revision }
        val lines = ingredients.filter { it.recipeVersionId == recipe.id }.sortedBy { it.position }
        val names = lines.map { line -> versions.singleOrNull { it.id == line.productVersionId }?.name ?: "Unbekannte Artikelversion" }
        RecipeLibraryEntry(recipe,lines,names,ProductionStockPreviews.build(recipe,lines,recipe.yieldPortions,
            versions,batches,locations,date,date))
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.recipe.name })

    fun select(entries: List<RecipeLibraryEntry>, query: String, coveredOnly: Boolean,
        mealPrepOnly: Boolean,favoritesOnly: Boolean = false,favoriteRecipeIds: Set<String> = emptySet()): List<RecipeLibraryEntry> {
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return entries.filter { entry ->
            (!coveredOnly || entry.stock.complete) && (!mealPrepOnly || entry.recipe.mealPrep) &&
                (!favoritesOnly || entry.recipe.recipeId in favoriteRecipeIds) &&
                words.all { word -> (listOf(entry.recipe.name) + entry.ingredientNames).any { it.contains(word,ignoreCase = true) } }
        }
    }
}
