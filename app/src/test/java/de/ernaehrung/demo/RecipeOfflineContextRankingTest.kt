package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeContextContentBinding as Binding
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.integration.RecipeOfflineContextRanking
import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Real typed host content-binding, but no FoodStore write or user approval. */
class RecipeOfflineContextRankingTest {
    private val at = Instant.parse("2026-11-04T06:00:00Z")
    private val stockFlags = Gate.Flags(true, false, false)
    private val shoppingFlags = Gate.Flags(false, true, false)
    private val mapping = mapOf(
        "dry-rice" to "rice-v1",
        "dry-quinoa" to "quinoa-v1",
        "cooked-beans" to "beans-v1",
        "tomato" to "tomato-v1"
    )

    private fun version(id: String, revision: Int = 1,
        productId: String = id.substringBefore("-v"),
        recordedAt: Long = at.toEpochMilli() - 1000L): ProductVersionEntity =
        ProductVersionEntity(
            id = id, productId = productId, revision = revision,
            name = id, brand = null, category = null, barcode = null,
            baseUnit = BaseUnit.GRAM, packageAmount = null, nutritionBasis = 100_000L,
            energyKcal = null, proteinGrams = null, fatGrams = null, carbsGrams = null,
            source = Source.USER, sourceDetail = null, quality = Quality.CONFIRMED,
            recordedAt = recordedAt, changeReason = "confirmed mapping fixture"
        )

    private val catalog get() = mapping.values.map { version(it) }

    private fun snapshot(source: Binding.Source, vararg quantities: Pair<String, Long?>):
        Binding.Snapshot = Binding.Snapshot(source, "verified-room-scope",
            "revision-1", at, quantities.mapIndexed { index, (versionId, amount) ->
                Binding.Line("row-$index", versionId, amount, "g")
            })

    private fun stockContext(snapshot: Binding.Snapshot) =
        Gate.typedContext(stockFlags, at, Gate.Evidence(snapshot, true), null, null)

    private fun shoppingContext(snapshot: Binding.Snapshot) =
        Gate.typedContext(shoppingFlags, at, null, Gate.Evidence(snapshot, true), null)

    @Test fun independentlyMappedStockRanksActualRoomQuantityInsteadOfRoundRobin() {
        val context = stockContext(snapshot(Binding.Source.STOCK,
            "rice-v1" to 0L, "quinoa-v1" to 140_000L,
            "beans-v1" to 260_000L, "tomato-v1" to 240_000L))
        val ranked = RecipeOfflineContextRanking.rank(2, stockFlags, context,
            catalog, mapping, true)
        assertEquals(listOf(9, 0), ranked.map { it.variant })
        assertEquals(640_000L, ranked.first().stockCoveredMilliGrams)
        assertEquals(0L, ranked.first().shoppingNeedMilliGrams)
        assertEquals(500_000L, ranked.last().stockCoveredMilliGrams)
    }

    @Test fun shoppingNeedRanksMappedCandidatesWithoutCallingItStock() {
        val context = shoppingContext(snapshot(Binding.Source.SHOPPING,
            "rice-v1" to 150_000L, "quinoa-v1" to 0L,
            "beans-v1" to 0L, "tomato-v1" to 0L))
        val ranked = RecipeOfflineContextRanking.rank(2, shoppingFlags, context,
            catalog, mapping, true)
        assertEquals(listOf(9, 0), ranked.map { it.variant })
        assertEquals(0L, ranked.first().stockCoveredMilliGrams)
        assertEquals(0L, ranked.first().shoppingNeedMilliGrams)
        assertEquals(150_000L, ranked.last().shoppingNeedMilliGrams)
    }

    @Test fun staleRoomBindingAndUnreviewedOrAmbiguousMappingFailClosed() {
        val stock = snapshot(Binding.Source.STOCK, "rice-v1" to 150_000L)
        val context = stockContext(stock)
        assertTrue(runCatching {
            RecipeOfflineContextRanking.rank(2, stockFlags, context, catalog, mapping, false)
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineContextRanking.rank(2, stockFlags,
                context.copy(stock = stock.copy(lines = listOf(
                    Binding.Line("changed", "rice-v1", 150_000L, "g")))),
                catalog, mapping, true)
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineContextRanking.rank(2, stockFlags, context, catalog,
                mapping + ("dry-bulgur" to "rice-v1"), true)
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineContextRanking.rank(2, stockFlags, context, catalog,
                mapping, true, setOf("ALLERGEN:SOY"))
        }.getOrThrow().isEmpty())
    }

    @Test fun unknownUnitsFutureVersionsAndUnapprovedOffersNeverRank() {
        val unknown = stockContext(snapshot(Binding.Source.STOCK, "rice-v1" to null))
        assertTrue(runCatching {
            RecipeOfflineContextRanking.rank(2, stockFlags, unknown, catalog, mapping, true)
        }.isFailure)
        val context = stockContext(snapshot(Binding.Source.STOCK, "rice-v1" to 150_000L))
        val revisedCatalog = catalog + version("rice-v2", revision = 2, productId = "rice")
        assertTrue(runCatching {
            RecipeOfflineContextRanking.rank(2, stockFlags, context,
                revisedCatalog, mapping, true)
        }.isFailure)
        val offers = Gate.Flags(true, false, true)
        assertTrue(runCatching {
            RecipeOfflineContextRanking.rank(2, offers, context, catalog, mapping, true)
        }.isFailure)
        val futureCatalog = catalog.map { it.copy(recordedAt = at.toEpochMilli() + 1000L) }
        assertTrue(runCatching {
            RecipeOfflineContextRanking.rank(2, stockFlags, context,
                futureCatalog, mapping, true)
        }.isFailure)
    }

    @Test fun independentlyConfirmedAllergenExclusionCanFilterButNeverSelfCertifies() {
        val context = stockContext(snapshot(Binding.Source.STOCK,
            "rice-v1" to 150_000L, "quinoa-v1" to 140_000L,
            "beans-v1" to 260_000L, "tomato-v1" to 240_000L))
        val excluded = setOf("ALLERGEN:SOY")
        val knownAbsent = mapOf(
            "dry-rice" to mapOf("SOY" to false),
            "dry-quinoa" to mapOf("SOY" to false),
            "cooked-beans" to mapOf("SOY" to false),
            "tomato" to mapOf("SOY" to false)
        )
        assertEquals(2, RecipeOfflineContextRanking.rank(2, stockFlags, context,
            catalog, mapping, true, excluded, knownAbsent).size)
        assertTrue(RecipeOfflineContextRanking.rank(2, stockFlags, context,
            catalog, mapping, true, excluded,
            knownAbsent + ("tomato" to mapOf("SOY" to true))).isEmpty())
    }
}
