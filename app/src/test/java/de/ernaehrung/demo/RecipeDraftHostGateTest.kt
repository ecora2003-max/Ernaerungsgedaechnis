package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.BaseUnit
import org.junit.Assert.*
import org.junit.Test

class RecipeDraftHostGateTest {
    private val flags = Gate.Flags(false, false, false)
    private fun line() = Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G, Gate.Basis.NET, Gate.State.RAW)
    private fun draft(f: Gate.Flags = flags, lines: List<Gate.DraftLine> = listOf(line())) =
        Gate.Draft(Gate.CONTRACT, "recipe.draft", "Reisgericht", "Reis kochen.", 2, f, lines)
    private fun fact(
        allergens: Map<String, Boolean?> = emptyMap(),
        basis: Gate.Basis = Gate.Basis.NET, state: Gate.State = Gate.State.RAW,
        id: String? = "version-rice"
    ) = Gate.HostLine("rice", id, true, BaseUnit.GRAM, basis, true, state, true,
        allergens = allergens)
    private fun request(f: Gate.Flags = flags, confirmed: Boolean = true,
                        restrictions: Set<String> = emptySet()) =
        Gate.Request(f, 2, restrictions, confirmed)

    @Test fun allEightContextFlagsAreIndependentAndNeverLeakDisabledSnapshots() {
        val stock = Gate.Evidence(listOf("private-stock"), true)
        val shopping = Gate.Evidence(listOf("private-shopping"), true)
        val offers = Gate.Evidence(listOf("private-offer"), true)
        for (bits in 0..7) {
            val f = Gate.Flags(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            val c = Gate.context(f, stock, shopping, offers)
            assertEquals(bits.toString(), if (f.considerStock) stock.value else null, c.stock)
            assertEquals(bits.toString(), if (f.considerShopping) shopping.value else null, c.shopping)
            assertEquals(bits.toString(), if (f.considerOffers) offers.value else null, c.offers)
            assertTrue(c.gaps.isEmpty())
            val reviewed = Gate.review(draft(f), request(f), mapOf("rice" to fact()))
            assertEquals(bits.toString(), Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed.status)
        }
    }

    @Test fun requestedButUnapprovedOrMissingContextRemainsUnknown() {
        val f = Gate.Flags(true, true, true)
        val c = Gate.context(f, Gate.Evidence("secret", false), null as Gate.Evidence<String>?,
            Gate.Evidence("offer", true))
        assertNull(c.stock)
        assertNull(c.shopping)
        assertEquals("offer", c.offers)
        assertEquals(setOf("STOCK_SNAPSHOT_UNAVAILABLE", "SHOPPING_SNAPSHOT_UNAVAILABLE"), c.gaps)
        val disabled = Gate.context(flags, Gate.Evidence("secret", false),
            Gate.Evidence("list", false), Gate.Evidence("offer", false))
        assertEquals(emptySet<String>(), disabled.gaps)
        assertNull(disabled.stock)
        assertNull(disabled.shopping)
        assertNull(disabled.offers)
    }

    @Test fun explicitConfirmationAndIndependentIdentityRequired() {
        val unconfirmed = Gate.review(draft(), request(confirmed = false), mapOf("rice" to fact()))
        assertEquals(Gate.Status.REVIEW_REQUIRED, unconfirmed.status)
        assertNull(unconfirmed.input)
        assertEquals(200_000L, Gate.review(draft(), request(),
            mapOf("rice" to fact())).input!!.ingredients.single().amount)
        assertEquals(2_000L, Gate.review(draft(), request(),
            mapOf("rice" to fact())).input!!.yieldPortions)
        assertEquals(Gate.Status.REVIEW_REQUIRED, Gate.review(draft(), request(), emptyMap()).status)
        assertNull(Gate.review(draft(), request(), mapOf("rice" to fact(id = null))).input)
    }

    @Test fun confirmedAllergenBlocksAndUnknownIsNotAssumedAbsent() {
        val restriction = setOf("ALLERGEN:SOY")
        val present = Gate.review(draft(), request(restrictions = restriction),
            mapOf("rice" to fact(mapOf("SOY" to true))))
        assertEquals(Gate.Status.BLOCKED, present.status)
        assertNull(present.input)
        val unknown = Gate.review(draft(), request(restrictions = restriction),
            mapOf("rice" to fact()))
        assertEquals(Gate.Status.REVIEW_REQUIRED, unknown.status)
        assertNull(unknown.input)
        val absent = Gate.review(draft(), request(restrictions = restriction),
            mapOf("rice" to fact(mapOf("SOY" to false))))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, absent.status)
    }

    @Test fun incompatibleBasisAndCookStateNeverSilentlyConvert() {
        val drained = Gate.review(draft(), request(), mapOf("rice" to fact(basis = Gate.Basis.DRAINED)))
        assertEquals(Gate.Status.BLOCKED, drained.status)
        val cooked = Gate.review(draft(), request(), mapOf("rice" to fact(state = Gate.State.COOKED)))
        assertEquals(Gate.Status.BLOCKED, cooked.status)
        val gross = Gate.review(draft(lines = listOf(line().copy(basis = Gate.Basis.GROSS))),
            request(), mapOf("rice" to fact(basis = Gate.Basis.GROSS)))
        assertEquals(Gate.Status.BLOCKED, gross.status)
        val unknown = Gate.review(draft(lines = listOf(line().copy(state = Gate.State.UNKNOWN))),
            request(), mapOf("rice" to fact(state = Gate.State.UNKNOWN)))
        assertEquals(Gate.Status.REVIEW_REQUIRED, unknown.status)
    }

    @Test fun noPieceWeightGuessAndExactConfirmedConversion() {
        val piece = line().copy(amount = 3, unit = Gate.UnitCode.PIECE, basis = Gate.Basis.PIECE)
        val host = fact(basis = Gate.Basis.PIECE)
        val missing = Gate.review(draft(lines = listOf(piece)), request(),
            mapOf("rice" to host))
        assertEquals(Gate.Status.REVIEW_REQUIRED, missing.status)
        val ready = Gate.review(draft(lines = listOf(piece)), request(),
            mapOf("rice" to host.copy(confirmedPieceMassMilliGrams = 125_000L)))
        assertEquals(375_000L, ready.input!!.ingredients.single().amount)
    }

    @Test fun overflowZeroUnknownAndDuplicateVersionFailClosed() {
        val zero = Gate.review(draft(lines = listOf(line().copy(amount = 0))), request(),
            mapOf("rice" to fact()))
        assertEquals(Gate.Status.BLOCKED, zero.status)
        val unknown = Gate.review(draft(lines = listOf(line().copy(amount = null))), request(),
            mapOf("rice" to fact()))
        assertEquals(Gate.Status.REVIEW_REQUIRED, unknown.status)
        val overflow = Gate.review(draft(lines = listOf(line().copy(amount = Long.MAX_VALUE))),
            request(), mapOf("rice" to fact()))
        assertEquals(Gate.Status.BLOCKED, overflow.status)
        val two = listOf(line(), line().copy(foodTypeId = "beans"))
        val duplicate = Gate.review(draft(lines = two), request(), mapOf(
            "rice" to fact(), "beans" to fact().copy(foodTypeId = "beans")))
        assertEquals(Gate.Status.BLOCKED, duplicate.status)
    }

    @Test fun contractConsentAndRestrictionsCannotBeForged() {
        val forged = Gate.review(draft(Gate.Flags(true, false, false)), request(),
            mapOf("rice" to fact()))
        assertEquals(Gate.Status.BLOCKED, forged.status)
        assertEquals(Gate.Status.BLOCKED, Gate.review(draft().copy(contractVersion = "1.1.0-draft"),
            request(), mapOf("rice" to fact())).status)
        assertEquals(Gate.Status.BLOCKED, Gate.review(draft(), request(restrictions = setOf("DIET:VEGAN")),
            mapOf("rice" to fact())).status)
        assertEquals(Gate.Status.BLOCKED, Gate.review(draft(), request(restrictions = setOf("FOOD:rice")),
            mapOf("rice" to fact())).status)
    }

    @Test fun draftDoesNotMutateInputsOrWriteToFoodStore() {
        val facts = mapOf("rice" to fact())
        val original = draft()
        val before = facts.toMap()
        val r = Gate.review(original, request(), facts)
        assertEquals(original, draft())
        assertEquals(before, facts)
        assertNotNull(r.input)
        // Pure projection: no Room, FoodStore, stock, purchase, production or consumption calls.
    }
}
