package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeContextContentBinding as Binding
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.BaseUnit
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RecipeContextContentBindingTest {
    private val at = Instant.parse("2026-11-01T10:00:00Z")
    private fun snapshot(source: Binding.Source) = Binding.Snapshot(
        source, "host-scope-1", "revision-7", at,
        listOf(Binding.Line("item-b", "v2", null, null, "store-2"),
            Binding.Line("item-a", "v1", 0L, "g", "store-1"))
    )
    private val stock get() = snapshot(Binding.Source.STOCK)
    private val shopping get() = snapshot(Binding.Source.SHOPPING)
    private val offers get() = snapshot(Binding.Source.OFFERS)
    private fun flags(bits: Int) = Gate.Flags(bits and 1 != 0,
        bits and 2 != 0, bits and 4 != 0)
    private fun draft(f: Gate.Flags) = Gate.Draft(Gate.CONTRACT, "recipe.draft",
        "Reis", "Kochen", 2, f,
        listOf(Gate.DraftLine("rice", "Reis", 100L, Gate.UnitCode.G,
            Gate.Basis.NET, Gate.State.RAW)))
    private fun facts() = mapOf("rice" to Gate.HostLine("rice", "version-rice",
        true, BaseUnit.GRAM, Gate.Basis.NET, true, Gate.State.RAW, true))

    @Test fun allEightFlagCombinationsBindOnlySelectedSourcesAndRemainReviewable() {
        for (bits in 0..7) {
            val f = flags(bits)
            val context = Gate.typedContext(f, at,
                Gate.Evidence(stock, true), Gate.Evidence(shopping, true),
                Gate.Evidence(offers, true))
            val binding = requireNotNull(context.contentBinding)
            assertEquals(f, binding.flags)
            assertEquals(f.considerStock, binding.stockDigest != null)
            assertEquals(f.considerShopping, binding.shoppingDigest != null)
            assertEquals(f.considerOffers, binding.offersDigest != null)
            assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,
                Gate.review(draft(f), Gate.Request(f, 2,
                    userConfirmedRecipeSave = true), facts(), context).status)
        }
    }

    @Test fun changedContentScopeRevisionAndTimeInvalidateBoundReview() {
        val f = flags(7)
        val original = Gate.typedContext(f, at, Gate.Evidence(stock, true),
            Gate.Evidence(shopping, true), Gate.Evidence(offers, true))
        val variations = listOf(
            original.copy(stock = stock.copy(lines = listOf(
                Binding.Line("item-a", "v1", 1L, "g", "store-1")))),
            original.copy(shopping = shopping.copy(scopeId = "other-user")),
            original.copy(offers = offers.copy(revision = "revision-8")),
            original.copy(stock = stock.copy(capturedAt = at.plusSeconds(1)))
        )
        variations.forEach { changed ->
            val review = Gate.review(draft(f), Gate.Request(f, 2,
                userConfirmedRecipeSave = true), facts(), changed)
            assertEquals(Gate.Status.BLOCKED, review.status)
            assertTrue("CONTEXT_CONTENT_CHANGED" in review.blockers)
            assertNull(review.input)
        }
    }

    @Test fun nullAndZeroDifferWhileRowOrderingDoesNot() {
        val f = flags(1)
        val first = requireNotNull(Binding.bind(f, at, stock, null, null))
        val reordered = stock.copy(lines = stock.lines.reversed())
        assertEquals(first, Binding.bind(f, at, reordered, null, null))
        val zeroToUnknown = stock.copy(lines = stock.lines.map {
            if (it.identity == "item-a") it.copy(amountMilli = null, unit = null) else it
        })
        assertNotEquals(first, Binding.bind(f, at, zeroToUnknown, null, null))
        assertNotEquals(first, Binding.bind(f, at,
            stock.copy(revision = "revision-8"), null, null))
    }

    @Test fun missingUnapprovedDuplicateOrWrongSourceNeverProducesBinding() {
        val f = flags(7)
        assertNull(Binding.bind(f, at, null, shopping, offers))
        assertNull(Binding.bind(f, at, offers, shopping, stock))
        assertNull(Binding.bind(f, at, stock.copy(lines = stock.lines + stock.lines.first()),
            shopping, offers))
        assertNull(Binding.bind(f, at, stock.copy(lines = listOf(
            Binding.Line("x", null, -1, "g"))), shopping, offers))
        val unapproved = Gate.typedContext(f, at, Gate.Evidence(stock, false),
            Gate.Evidence(shopping, true), Gate.Evidence(offers, true))
        assertNull(unapproved.contentBinding)
        assertTrue("STOCK_SNAPSHOT_UNAVAILABLE" in unapproved.gaps)
        assertNull(unapproved.stock)
    }

    @Test fun invalidOrRemovedTypedBindingCannotClaimReadyForSave() {
        val f = flags(1)
        val invalid = Gate.typedContext(f, at,
            Gate.Evidence(stock.copy(source = Binding.Source.OFFERS), true),
            null, null)
        assertNull(invalid.contentBinding)
        assertTrue(invalid.contentBindingRequired)
        val review = Gate.review(draft(f), Gate.Request(f, 2,
            userConfirmedRecipeSave = true), facts(), invalid)
        assertEquals(Gate.Status.REVIEW_REQUIRED, review.status)
        assertTrue("CONTEXT_CONTENT_UNVERIFIED" in review.gaps)
        assertNull(review.input)

        val valid = Gate.typedContext(f, at, Gate.Evidence(stock, true),
            null, null)
        val stripped = valid.copy(contentBinding = null)
        val strippedReview = Gate.review(draft(f), Gate.Request(f, 2,
            userConfirmedRecipeSave = true), facts(), stripped)
        assertEquals(Gate.Status.REVIEW_REQUIRED, strippedReview.status)
        assertTrue("CONTEXT_CONTENT_UNVERIFIED" in strippedReview.gaps)
        assertNull(strippedReview.input)
    }
}
