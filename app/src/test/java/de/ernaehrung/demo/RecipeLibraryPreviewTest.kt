package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import org.junit.Assert.*
import org.junit.Test

class RecipeLibraryPreviewTest {
    private val day = "2026-11-07"
    private val rice = ProductVersionEntity("rice-v1","rice",1,"Vollkornreis",null,null,null,BaseUnit.GRAM,
        null,100_000L,100.0,null,null,null,source = Source.USER,sourceDetail = null,quality = Quality.CONFIRMED,
        recordedAt = 1L,changeReason = "Synthetisch")
    private val newerRice = rice.copy(id = "rice-v2",revision = 2,name = "Neue Reisvariante")
    private val place = StorageLocationEntity("pantry","Vorrat",true)
    private val recipe = RecipeVersionEntity("recipe-v1","recipe",1,"Schneller Reistopf",2000,null,"Garen",
        null,null,false,null,null,Quality.CONFIRMED,2L,"Synthetisch")
    private val ingredient = RecipeIngredientEntity(recipe.id,0,rice.id,200_000L,BaseUnit.GRAM,null)
    private fun batch(id: String = "old", version: ProductVersionEntity = rice, amount: Long = 200_000L,
        release: ReleaseStatus = ReleaseStatus.RELEASED, useBy: String? = "2026-11-08") = BatchRow(
        BatchEntity(id,version.id,place.id,"origin-$id",day,null,useBy,release = release),
        version.name,version.baseUnit,place.name,amount,false)
    private fun build(recipes: List<RecipeVersionEntity> = listOf(recipe), lines: List<RecipeIngredientEntity> = listOf(ingredient),
        rows: List<BatchRow> = listOf(batch()), locations: List<StorageLocationEntity> = listOf(place)) =
        RecipeLibraryPreviews.build(recipes,lines,listOf(rice,newerRice),rows,locations,day)

    @Test fun fullYieldUsesExactHistoricalIngredientVersionAndOnlyCurrentRecipeRevision() {
        assertTrue(build().single().stock.complete)
        assertFalse(build(rows = listOf(batch(version = newerRice))).single().stock.complete)
        val revision = recipe.copy(id = "recipe-v2",revision = 2,name = "Neuer Reistopf")
        val selected = build(listOf(recipe,revision),listOf(ingredient,ingredient.copy(recipeVersionId = revision.id,amount = 300_000L)))
        assertEquals(revision,selected.single().recipe)
        assertFalse(selected.single().stock.complete)
        assertEquals(100_000L,selected.single().stock.ingredients.single().stock.missing)
        assertEquals(listOf("Vollkornreis"),selected.single().ingredientNames)
    }

    @Test fun unknownQuantityExpiredUnreleasedUnconfirmedAndReversedStockNeverPassTheCoveredFilter() {
        val variants = listOf(
            build(lines = listOf(ingredient.copy(amount = null))),
            build(rows = listOf(batch(useBy = "2026-11-06"))),
            build(rows = listOf(batch(release = ReleaseStatus.UNCHECKED))),
            build(locations = listOf(place.copy(confirmed = false))),
            build(rows = listOf(batch().copy(originReversed = true))),
            build(rows = emptyList()))
        variants.forEach {
            assertFalse(it.single().stock.complete)
            assertTrue(RecipeLibraryPreviews.select(it,"",true,false).isEmpty())
            assertEquals(1,RecipeLibraryPreviews.select(it,"",false,false).size)
        }
        assertNotNull(variants.first().single().stock.issue)
        assertEquals(200_000L,variants.last().single().stock.ingredients.single().stock.missing)
    }

    @Test fun wordSearchCoversTitleAndIngredientNamesAndCombinesIndependentFiltersWithoutReplacingMissingValues() {
        val mealPrep = recipe.copy(id = "prep-v1",recipeId = "prep",name = "Vorratstopf",mealPrep = true)
        val entries = build(listOf(recipe,mealPrep),listOf(ingredient,ingredient.copy(recipeVersionId = mealPrep.id,amount = null)))
        assertEquals(recipe.id,RecipeLibraryPreviews.select(entries," SCHNELLER vollKORN ",true,false).single().recipe.id)
        assertEquals(mealPrep.id,RecipeLibraryPreviews.select(entries,"reis",false,true).single().recipe.id)
        assertTrue(RecipeLibraryPreviews.select(entries,"",true,true).isEmpty())
        assertTrue(RecipeLibraryPreviews.select(entries,"unbekanntes Gericht",false,false).isEmpty())
        assertEquals(2,RecipeLibraryPreviews.select(entries,"",false,false).size)
        assertEquals(recipe.id,RecipeLibraryPreviews.select(entries,"",false,false,true,setOf(recipe.recipeId)).single().recipe.id)
        assertTrue(RecipeLibraryPreviews.select(entries,"",false,true,true,setOf(recipe.recipeId)).isEmpty())
    }
}
