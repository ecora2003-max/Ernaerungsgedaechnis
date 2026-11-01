package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic untrusted draft -> separate recipe save -> production -> separate consumption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeDraftHostEndToEndTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var riceVersion: String
    private lateinit var beansVersion: String
    private lateinit var riceBatch: String
    private lateinit var beansBatch: String
    private lateinit var location: String
    private val dao get() = db.foodDao()
    private val date = "2026-10-30"
    private val flags = Gate.Flags(false, false, false)
    private val now = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        store = FoodStore(db) { now }
        riceVersion = store.rememberProduct("product-rice", ProductInput("Reis"))
        beansVersion = store.rememberProduct("product-beans", ProductInput("Bohnen"))
        location = store.addLocation("location", "Kühlschrank", true)
        riceBatch = store.receive("stock-rice", BatchInput(riceVersion, location,
            300_000, date, release = ReleaseStatus.RELEASED), true)
        beansBatch = store.receive("stock-beans", BatchInput(beansVersion, location,
            200_000, date, release = ReleaseStatus.RELEASED), true)
    }
    @After fun close() { db.close() }

    private fun draft(f: Gate.Flags = flags) = Gate.Draft(
        Gate.CONTRACT, "recipe.draft", "Reis mit Bohnen", "Beide Zutaten garen.", 2, f,
        listOf(
            Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G, Gate.Basis.NET, Gate.State.RAW),
            Gate.DraftLine("beans", "Bohnen", 100, Gate.UnitCode.G, Gate.Basis.NET, Gate.State.RAW)
        )
    )
    private fun facts(): Map<String, Gate.HostLine> = mapOf(
        "rice" to Gate.HostLine("rice", riceVersion, true, BaseUnit.GRAM,
            Gate.Basis.NET, true, Gate.State.RAW, true, allergens = mapOf("SOY" to false)),
        "beans" to Gate.HostLine("beans", beansVersion, true, BaseUnit.GRAM,
            Gate.Basis.NET, true, Gate.State.RAW, true, allergens = mapOf("SOY" to false))
    )
    private fun request(confirmed: Boolean = false, f: Gate.Flags = flags) =
        Gate.Request(f, 2, setOf("ALLERGEN:SOY"), confirmed)
    private fun production(recipe: String) = ProductionInput(
        recipe, 2000, listOf(Allocation(riceBatch, 200_000), Allocation(beansBatch, 100_000)),
        location, date, true, "Geeignete gekühlte Behälter und Platz geprüft",
        release = ReleaseStatus.RELEASED
    )
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected explicit confirmation or validation rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun draftCannotBookAndEverySubsequentActualEventRequiresItsOwnConfirmation() = runBlocking {
        val before = dao.operationCount()
        val pending = Gate.review(draft(), request(), facts())
        assertEquals(Gate.Status.REVIEW_REQUIRED, pending.status)
        assertNull(pending.input)
        assertEquals(before, dao.operationCount())
        assertEquals(300_000L, dao.balance(riceBatch))
        assertEquals(200_000L, dao.balance(beansBatch))
        assertEquals(0, dao.consumptionCount())

        val reviewed = Gate.review(draft(), request(confirmed = true), facts())
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed.status)
        val input = requireNotNull(reviewed.input)
        assertEquals(listOf(riceVersion, beansVersion), input.ingredients.map { it.versionId })
        assertEquals(listOf(200_000L, 100_000L), input.ingredients.map { it.amount })
        val recipe = store.saveRecipe("recipe-save", input)
        assertEquals(recipe, store.saveRecipe("recipe-save", input))
        assertEquals(before + 1, dao.operationCount())
        assertEquals(300_000L, dao.balance(riceBatch))
        assertEquals(0, dao.consumptionCount())

        rejected { store.produce("cook-unconfirmed", production(recipe), false) }
        assertNull(dao.operation("cook-unconfirmed"))
        assertEquals(300_000L, dao.balance(riceBatch))
        val output = store.produce("cook", production(recipe), true)
        assertEquals(output, store.produce("cook", production(recipe), true))
        assertEquals(recipe, dao.production("cook")!!.recipeVersionId)
        assertEquals(100_000L, dao.balance(riceBatch))
        assertEquals(100_000L, dao.balance(beansBatch))
        assertEquals(2000L, dao.balance(output))
        assertEquals(0, dao.consumptionCount())

        val meal = ConsumptionInput(dao.batch(output)!!.versionId, date, "Mittag",
            1000, listOf(Allocation(output, 1000)))
        rejected { store.consume("eat-unconfirmed", meal, false) }
        assertNull(dao.operation("eat-unconfirmed"))
        assertEquals(0, dao.consumptionCount())
        store.consume("eat", meal, true)
        store.consume("eat", meal, true)
        assertEquals(1, dao.activeConsumptionCount())
        assertEquals(1000L, dao.balance(output))
        assertEquals(100_000L, dao.balance(riceBatch))
    }

    @Test fun missingContextOrAllergenEvidenceNeverReachesRecipeSave() = runBlocking {
        val before = dao.operationCount()
        val f = Gate.Flags(true, false, true)
        val missing = Gate.context(f, null as Gate.Evidence<String>?,
            null as Gate.Evidence<String>?, Gate.Evidence("offer", false))
        val review = Gate.review(draft(f), request(true, f), facts(), missing)
        assertEquals(Gate.Status.REVIEW_REQUIRED, review.status)
        assertNull(review.input)
        assertTrue("STOCK_SNAPSHOT_UNAVAILABLE" in review.gaps)
        assertTrue("OFFERS_SNAPSHOT_UNAVAILABLE" in review.gaps)

        val unknown = facts().toMutableMap()
        unknown["beans"] = requireNotNull(unknown["beans"]).copy(allergens = emptyMap())
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            Gate.review(draft(), request(true), unknown).status)
        val unsafe = facts().toMutableMap()
        unsafe["beans"] = requireNotNull(unsafe["beans"]).copy(allergens = mapOf("SOY" to true))
        assertEquals(Gate.Status.BLOCKED,
            Gate.review(draft(), request(true), unsafe).status)
        assertEquals(before, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
        assertEquals(300_000L, dao.balance(riceBatch))
    }

    @Test fun failedActualProductionRollsBackAndReversalPreservesAuditHistory() = runBlocking {
        val ready = Gate.review(draft(), request(true), facts())
        val recipe = store.saveRecipe("recipe", requireNotNull(ready.input))
        val invalid = production(recipe).copy(allocations = listOf(
            Allocation(riceBatch, 200_000), Allocation(beansBatch, 300_000)))
        val before = dao.operationCount()
        rejected { store.produce("bad-cook", invalid, true) }
        assertNull(dao.operation("bad-cook"))
        assertEquals(before, dao.operationCount())
        assertEquals(300_000L, dao.balance(riceBatch))
        assertEquals(200_000L, dao.balance(beansBatch))
        assertEquals(0, dao.consumptionCount())

        val output = store.produce("cook", production(recipe), true)
        val meal = ConsumptionInput(dao.batch(output)!!.versionId, date, "Mittag",
            1000, listOf(Allocation(output, 1000)))
        store.consume("eat", meal, true)
        rejected { store.reverse("undo-cook-too-early",
            ReversalInput("cook", date, "Korrektur"), true) }
        assertNull(dao.operation("undo-cook-too-early"))
        store.reverse("undo-eat", ReversalInput("eat", date, "Korrektur"), true)
        store.reverse("undo-cook", ReversalInput("cook", date, "Korrektur"), true)
        assertEquals(300_000L, dao.balance(riceBatch))
        assertEquals(200_000L, dao.balance(beansBatch))
        assertEquals(0L, dao.balance(output))
        assertEquals(0, dao.activeConsumptionCount())
        assertNotNull(dao.production("cook"))
        assertNotNull(dao.operation("undo-cook"))
    }
}
