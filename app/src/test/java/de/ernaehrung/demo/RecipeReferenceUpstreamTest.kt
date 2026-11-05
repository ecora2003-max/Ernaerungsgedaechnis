package de.ernaehrung.demo

import de.eg.recipe.CombinatorialGeneratorTest
import de.eg.recipe.RecipeDraftImportTest
import de.eg.recipe.RecipeDraftRevalidationTest
import de.eg.recipe.RecipeEngineTest
import org.junit.Test
import org.junit.Assert.*

/** Unmodified synthetic assertions shipped by the pinned read-only module. */
class RecipeReferenceUpstreamTest {
    @Test fun engineContractIncludingEightIndependentContextCombinations() = RecipeEngineTest.run()
    @Test fun combinatorialGrammar() = CombinatorialGeneratorTest.main(emptyArray())
    @Test fun untrustedImport() = RecipeDraftImportTest.main(emptyArray())
    @Test fun independentRevalidation() = RecipeDraftRevalidationTest.main(emptyArray())

    @Test fun android26CompatibleBigIntegerConversionPreservesTheOriginalExactBounds() {
        val request = de.eg.recipe.RecipeEngine.Request("integer-bounds", "2026-11-04T06:00:00Z",
            java.time.LocalDate.of(2026, 11, 4), 2)
        val message = de.eg.recipe.RecipeEngine.propose(request,
            de.eg.recipe.RecipeEngine.RecipeFacts(emptyMap())).messages.first()
        fun withAmount(amount: java.math.BigInteger): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val payload = (message["payload"] as Map<String, Any?>).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val ingredients = (payload["ingredients"] as List<Map<String, Any?>>).toMutableList()
            val first = ingredients.first().toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val quantity = (first["quantity"] as Map<String, Any?>).toMutableMap()
            quantity["amount"] = amount; first["quantity"] = quantity
            ingredients[0] = first; payload["ingredients"] = ingredients
            return message + ("payload" to payload)
        }
        for (value in listOf(0L, 1L, Long.MAX_VALUE)) {
            assertEquals(value, de.eg.recipe.RecipeDraftImport.read(
                withAmount(java.math.BigInteger.valueOf(value))).ingredients.first().amount)
        }
        for (value in listOf(java.math.BigInteger.valueOf(-1L),
            java.math.BigInteger.ONE.shiftLeft(63), java.math.BigInteger.ONE.shiftLeft(64))) {
            assertTrue(runCatching { de.eg.recipe.RecipeDraftImport.read(withAmount(value)) }.isFailure)
        }
    }
}
