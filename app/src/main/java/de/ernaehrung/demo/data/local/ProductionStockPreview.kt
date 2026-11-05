package de.ernaehrung.demo.data.local

import java.math.BigInteger

internal data class ProductionIngredientPreview(
    val versionId: String, val amount: Long, val unit: BaseUnit, val stock: MealStockPreview
)

/** Exact recipe scaling and read-only stock proposals. Never a production event. */
internal data class ProductionStockPreview(
    val ingredients: List<ProductionIngredientPreview> = emptyList(),
    val issue: String? = null, val digest: String? = null
) {
    val complete get() = issue == null && digest != null && ingredients.isNotEmpty() &&
        ingredients.all { it.stock.complete }
    val allocations get() = ingredients.flatMap { it.stock.allocations }
}

internal object ProductionStockPreviews {
    fun build(
        recipe: RecipeVersionEntity?, ingredients: List<RecipeIngredientEntity>,
        portions: Long?, versions: List<ProductVersionEntity>, batches: List<BatchRow>,
        locations: List<StorageLocationEntity>, date: String, asOf: String
    ): ProductionStockPreview {
        fun issue(text: String) = ProductionStockPreview(issue = text)
        if (recipe == null) return issue("Für den Chargenvorschlag eine konkrete Rezeptversion wählen.")
        if (portions == null || portions !in 1..Amounts.MAX || recipe.yieldPortions !in 1..Amounts.MAX)
            return issue("Für den Chargenvorschlag die tatsächlich hergestellten Portionen eingeben.")
        if (ingredients.isEmpty() || ingredients.any { it.recipeVersionId != recipe.id } ||
            ingredients.map { it.productVersionId }.distinct().size != ingredients.size)
            return issue("Die Zutaten dieser Rezeptversion sind nicht eindeutig. Rezept prüfen.")
        val rows = mutableListOf<ProductionIngredientPreview>()
        for (ingredient in ingredients.sortedBy { it.position }) {
            val version = versions.singleOrNull { it.id == ingredient.productVersionId }
                ?: return issue("Eine genaue Artikelversion des Rezepts fehlt. Rezept prüfen.")
            val amount = ingredient.amount?.takeIf { it in 1..Amounts.MAX }
                ?: return issue("Eine Rezeptmenge ist unbekannt. Tatsächliche Chargenmengen selbst eintragen.")
            if (version.baseUnit != ingredient.unit)
                return issue("Rezeptmenge und Artikelversion haben verschiedene Einheiten.")
            val scaled = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(portions))
                .divideAndRemainder(BigInteger.valueOf(recipe.yieldPortions))
            if (scaled[1] != BigInteger.ZERO || scaled[0] < BigInteger.ONE ||
                scaled[0] > BigInteger.valueOf(Amounts.MAX))
                return issue("Die skalierte Rezeptmenge ist nicht exakt darstellbar. Tatsächliche Chargenmengen selbst eintragen; keine automatische Rundung.")
            // The bound check above makes this exact on Android 26 as well.
            val required = scaled[0].toLong()
            rows += ProductionIngredientPreview(version.id, required, version.baseUnit,
                MealStockPreviews.build(versions, batches, locations, version.id, required, date, asOf))
        }
        val digest = WriteRules.fingerprint("PRODUCTION_STOCK_PREVIEW_1", recipe.id,
            recipe.recordedAt, recipe.yieldPortions, portions, date, asOf,
            rows.map { listOf(it.versionId, it.amount, it.unit.name, it.stock.digest) })
        return ProductionStockPreview(rows, digest = digest)
    }
}
