package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.screens.eligibleConsumptionBatches
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kitchen selector + actual FoodStore boundary; no emulator/device test claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedPortionConsumptionRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var rawBatch: String
    private lateinit var recipeVersion: String
    private lateinit var location: String

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        store = FoodStore(db) { Instant.parse("2026-10-31T12:00:00Z").toEpochMilli() }
        val product = store.rememberProduct("prepared-product", ProductInput("Linsen"))
        location = store.addLocation("prepared-location", "Kühlschrank", true)
        rawBatch = store.receive("prepared-receipt",
            BatchInput(product, location, 100_000, "2026-10-30",
                release = ReleaseStatus.RELEASED), true)
        recipeVersion = store.saveRecipe("prepared-recipe",
            RecipeInput("Linsengericht", 2000,
                listOf(RecipeItemInput(product, 100_000)), "Kochen"))
    }

    @After fun close() { db.close() }

    private fun production(release: ReleaseStatus = ReleaseStatus.RELEASED) =
        ProductionInput(recipeVersion, 2000, listOf(Allocation(rawBatch, 100_000)),
            location, "2026-10-30", true, "Kühlbedingungen und Platz geprüft",
            release = release)

    @Test fun kitchenShowsOnlyReleasedCookedBatchAndEatingRequiresSeparateAction() = runBlocking {
        assertTrue(eligibleConsumptionBatches(store.batches().first(), true).isEmpty())
        val output = store.produce("prepared-cook", production(), true)
        assertEquals(0, db.foodDao().consumptionCount())
        val offered = eligibleConsumptionBatches(store.batches().first(), true)
        assertEquals(listOf(output), offered.map { it.batch.id })
        val portionVersion = offered.single().batch.versionId
        val eating = ConsumptionInput(portionVersion, "2026-10-30",
            "Fertigportionen", 1000, listOf(Allocation(output, 1000)))
        try {
            store.consume("prepared-eat-rejected", eating, false)
            fail("Separate consumption confirmation required")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, db.foodDao().consumptionCount())
        val saved = store.consume("prepared-eat", eating, true)
        assertEquals(saved, store.consume("prepared-eat", eating, true))
        assertEquals(1, db.foodDao().consumptionCount())
        assertEquals(1000L, db.foodDao().balance(output))
        assertEquals(0L, db.foodDao().balance(rawBatch))
    }

    @Test fun kitchenExcludesUnreleasedReversedAndEmptyPortionsWithoutChangingGeneralStockView() = runBlocking {
        val output = store.produce("prepared-cook-unsafe",
            production(ReleaseStatus.UNCHECKED), true)
        val all = store.batches().first()
        val cooked = all.single { it.batch.id == output }
        val candidates = listOf(
            cooked,
            cooked.copy(batch = cooked.batch.copy(release = ReleaseStatus.RELEASED),
                originReversed = true),
            cooked.copy(batch = cooked.batch.copy(release = ReleaseStatus.RELEASED),
                stockAmount = 0),
            all.single { it.batch.id == rawBatch }
        )
        assertTrue(eligibleConsumptionBatches(candidates, true).isEmpty())
        assertEquals(2, eligibleConsumptionBatches(candidates, false).size)
        assertEquals(0, db.foodDao().consumptionCount())
    }
}
