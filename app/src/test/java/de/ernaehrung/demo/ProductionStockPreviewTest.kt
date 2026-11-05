package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import org.junit.Assert.*
import org.junit.Test

class ProductionStockPreviewTest {
    private val day = "2026-11-05"
    private val rice = ProductVersionEntity("rice", "rice-product", 1, "Rice", null, null, null,
        BaseUnit.GRAM, null, 100_000L, 100.0, null, null, null,
        source = Source.USER, sourceDetail = null, quality = Quality.CONFIRMED,
        recordedAt = 1L, changeReason = "synthetic")
    private val water = rice.copy(id = "water", productId = "water-product", name = "Water", baseUnit = BaseUnit.MILLILITRE)
    private val recipe = RecipeVersionEntity("recipe-v1", "recipe", 1, "Rice with water", 2_000L,
        null, "Cook", null, null, false, null, null, Quality.CONFIRMED, 2L, "synthetic")
    private val ingredients = listOf(
        RecipeIngredientEntity(recipe.id, 0, rice.id, 200_000L, BaseUnit.GRAM, null),
        RecipeIngredientEntity(recipe.id, 1, water.id, 100_000L, BaseUnit.MILLILITRE, null))
    private val location = StorageLocationEntity("pantry", "Pantry", true)
    private fun batch(id: String, version: ProductVersionEntity, amount: Long, until: String) = BatchRow(
        BatchEntity(id, version.id, location.id, "origin-$id", day, null, until,
            release = ReleaseStatus.RELEASED), version.name, version.baseUnit, location.name, amount, false)
    private val batches = listOf(batch("later", rice, 60_000L, "2026-11-08"),
        batch("early", rice, 40_000L, "2026-11-06"), batch("water", water, 100_000L, "2026-11-09"))
    private fun preview(portions: Long? = 1_000L, lines: List<RecipeIngredientEntity> = ingredients,
        rows: List<BatchRow> = batches, selected: RecipeVersionEntity = recipe) =
        ProductionStockPreviews.build(selected, lines, portions, listOf(rice, water), rows, listOf(location), day, day)

    @Test fun actualPortionsScaleExactVersionQuantitiesAndAllocateMultipleBatchesAcrossDifferentUnits() {
        val result = preview()
        assertTrue(result.complete)
        assertEquals(listOf(100_000L, 50_000L), result.ingredients.map { it.amount })
        assertEquals(listOf(BaseUnit.GRAM, BaseUnit.MILLILITRE), result.ingredients.map { it.unit })
        assertEquals(listOf(PlannedAllocation("early", 40_000L), PlannedAllocation("later", 60_000L),
            PlannedAllocation("water", 50_000L)), result.allocations)
    }

    @Test fun partialRecipeStockAndUnknownRecipeQuantitiesDoNotAuthorizeProduction() {
        val partial = preview(2_000L)
        assertFalse(partial.complete)
        assertEquals(100_000L, partial.ingredients.first().stock.missing)
        val unknown = preview(lines = ingredients.map { it.copy(amount = null) })
        assertFalse(unknown.complete)
        assertTrue(unknown.allocations.isEmpty())
        assertNotNull(unknown.issue)
        assertFalse(preview(null).complete)
    }

    @Test fun scalingNeverRoundsOrOverflowsAndManualActualAmountsRemainNecessaryForUnrepresentableResults() {
        val thirds = preview(selected = recipe.copy(yieldPortions = 3_000L))
        assertFalse(thirds.complete)
        assertTrue(thirds.allocations.isEmpty())
        assertNotNull(thirds.issue)
        val overflow = preview(Amounts.MAX,
            lines = ingredients.map { it.copy(amount = Amounts.MAX) }, selected = recipe.copy(yieldPortions = 1L))
        assertFalse(overflow.complete)
        assertTrue(overflow.allocations.isEmpty())
    }

    @Test fun wrongUnitsOrDuplicatedVersionsCannotReuseAnotherIngredientStockAndChangedStockRevokesBinding() {
        assertFalse(preview(lines = ingredients.map { it.copy(unit = BaseUnit.PORTION) }).complete)
        assertFalse(preview(lines = listOf(ingredients.first(), ingredients.first().copy(position = 1))).complete)
        val changed = batches + batch("new-first", rice, 20_000L, day)
        assertNotEquals(preview().digest, preview(rows = changed).digest)
        assertNotEquals(preview().digest, preview(portions = 500L).digest)
    }
}
