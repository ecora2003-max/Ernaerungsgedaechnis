package de.ernaehrung.demo

import de.eg.recipe.CombinatorialGeneratorTest
import de.eg.recipe.RecipeDraftImportTest
import de.eg.recipe.RecipeDraftRevalidationTest
import de.eg.recipe.RecipeEngineTest
import org.junit.Test

/** Unmodified synthetic assertions shipped by the pinned read-only module. */
class RecipeReferenceUpstreamTest {
    @Test fun engineContractIncludingEightIndependentContextCombinations() = RecipeEngineTest.run()
    @Test fun combinatorialGrammar() = CombinatorialGeneratorTest.main(emptyArray())
    @Test fun untrustedImport() = RecipeDraftImportTest.main(emptyArray())
    @Test fun independentRevalidation() = RecipeDraftRevalidationTest.main(emptyArray())
}
