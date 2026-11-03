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
            val reviewed = Gate.review(draft(f), request(f), mapOf("rice" to fact()), c)
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

    @Test fun foreignOrMixedHostFactsCannotAuthorizeCurrentRecipe() {
        val trusted = mapOf("rice" to fact())
        val foreign = fact().copy(foodTypeId = "beans", productVersionId = "version-beans")
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,
            Gate.review(draft(), request(), trusted).status)

        // A previously approved ingredient mixed with another draft's evidence
        // must fail closed rather than silently dropping the foreign entry.
        val mixed = Gate.review(draft(), request(), trusted + ("beans" to foreign))
        assertEquals(Gate.Status.BLOCKED, mixed.status)
        assertTrue("FOREIGN_INGREDIENT_EVIDENCE" in mixed.blockers)
        assertNull(mixed.input)

        val onlyForeign = Gate.review(draft(), request(), mapOf("beans" to foreign))
        assertEquals(Gate.Status.BLOCKED, onlyForeign.status)
        assertTrue("FOREIGN_INGREDIENT_EVIDENCE" in onlyForeign.blockers)
        assertNull(onlyForeign.input)

        // Fresh, exactly scoped evidence recovers without a FoodStore write.
        val recovered = Gate.review(draft(), request(), trusted)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, recovered.status)
        assertEquals(200_000L, recovered.input!!.ingredients.single().amount)
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

    @Test fun malformedRestrictionTokensCannotBypassConfirmedFoodOrAllergenLimits() {
        val host = mapOf("rice" to fact(allergens = mapOf("SOY" to false)))
        // A well-formed restriction on a different food remains harmless.
        val allowed = Gate.review(draft(), request(restrictions = setOf(
            "FOOD:beans", "ALLERGEN:SOY")), host)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, allowed.status)
        assertNotNull(allowed.input)

        // Whitespace, nonbreaking space, and control characters cannot turn a
        // confirmed food exclusion into an unmatched (and therefore ignored) ID.
        for (bad in listOf(
            "FOOD:rice ", "FOOD: rice", "FOOD:rice\t", "FOOD:rice\n",
            "FOOD:rice\u00A0", "FOOD:", "FOOD: ",
            "ALLERGEN:SOY ", "ALLERGEN: SOY", "ALLERGEN:SOY\u00A0"
        )) {
            val result = Gate.review(draft(), request(restrictions = setOf(bad)), host)
            assertEquals(bad, Gate.Status.BLOCKED, result.status)
            assertTrue(bad, "UNSUPPORTED_RESTRICTION" in result.blockers)
            assertNull(bad, result.input)
        }
        val mixed = Gate.review(draft(), request(restrictions = setOf(
            "FOOD:beans", "FOOD:rice ")), host)
        assertEquals(Gate.Status.BLOCKED, mixed.status)
        assertNull(mixed.input)

        // A correctly encoded restriction still blocks the actual ingredient.
        val restricted = Gate.review(draft(), request(restrictions = setOf("FOOD:rice")), host)
        assertEquals(Gate.Status.BLOCKED, restricted.status)
        assertTrue("CONFIRMED_FOOD_CONFLICT" in restricted.blockers)
        assertNull(restricted.input)
    }

    @Test fun extraDelimitersAndInvisibleUnicodeCannotBypassFoodOrAllergenRestrictions() {
        val host = mapOf("rice" to fact(allergens = mapOf("SOY" to false)))
        // Valid host identifiers and an unrelated food exclusion remain usable.
        val valid = Gate.review(draft(), request(restrictions = setOf(
            "FOOD:beans-1", "ALLERGEN:SOY")), host)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, valid.status)
        assertNotNull(valid.input)

        // Previously these malformed tokens could be interpreted as different
        // identifiers and therefore silently miss the actual forbidden food.
        for (bad in listOf(
            "FOOD:rice:shadow", "ALLERGEN:SOY:shadow",
            "FOOD:rice\u200B", "FOOD:rice\u2060", "FOOD:rice\u200D",
            "ALLERGEN:SOY\u202E"
        )) {
            val result = Gate.review(draft(), request(restrictions = setOf(bad)), host)
            assertEquals(bad, Gate.Status.BLOCKED, result.status)
            assertTrue(bad, "UNSUPPORTED_RESTRICTION" in result.blockers)
            assertNull(bad, result.input)
        }
        // A malformed restriction must also poison an otherwise valid set.
        val mixed = Gate.review(draft(), request(restrictions = setOf(
            "FOOD:beans", "FOOD:rice:shadow")), host)
        assertEquals(Gate.Status.BLOCKED, mixed.status)
        assertNull(mixed.input)
    }

    @Test fun supplementaryAndCombiningInvisibleRestrictionCharactersFailClosed() {
        val invisibleTag = String(Character.toChars(0xE0061))
        val host = mapOf("rice" to fact(allergens = mapOf("SOY" to false)))
        // UTF-16 char iteration previously treated supplementary tag characters
        // as two surrogates, not as an invisible Unicode FORMAT code point.
        val invalid = listOf(
            "FOOD:rice$invisibleTag", "ALLERGEN:SOY$invisibleTag",
            "FOOD:ri\u034Fce", "FOOD:rice\uD800"
        )
        for (token in invalid) {
            assertFalse(token, Gate.isSupportedRestriction(token))
            val result = Gate.review(draft(), request(restrictions = setOf(token)), host)
            assertEquals(token, Gate.Status.BLOCKED, result.status)
            assertTrue(token, "UNSUPPORTED_RESTRICTION" in result.blockers)
            assertNull(token, result.input)
        }

        // Valid ordinary and visible supplementary identifiers remain usable.
        assertTrue(Gate.isSupportedRestriction("FOOD:beans"))
        assertTrue(Gate.isSupportedRestriction("FOOD:carrot-🥕"))
        val safe = Gate.review(draft(), request(restrictions = setOf(
            "FOOD:carrot-🥕", "ALLERGEN:SOY"
        )), host)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, safe.status)
        assertNotNull(safe.input)
        assertEquals(Gate.Status.BLOCKED, Gate.review(
            draft(), request(restrictions = setOf("FOOD:rice")), host
        ).status)
    }

    @Test fun visualUnicodeLineSeparatorsCannotSpoofFoodOrAllergenExclusions() {
        val host = mapOf("rice" to fact(allergens = mapOf("SOY" to false)))
        // Neither U+2028 nor U+2029 is an ISO control. Both render a line
        // break and could make an exclusion appear to match a different ID.
        for (separator in listOf("\u2028", "\u2029")) {
            for (prefix in listOf("FOOD:rice", "ALLERGEN:SOY")) {
                val token = prefix + separator + "ignored"
                assertFalse(token, Gate.isSupportedRestriction(token))
                val result = Gate.review(draft(),
                    request(restrictions = setOf("FOOD:beans", token)), host)
                assertEquals(token, Gate.Status.BLOCKED, result.status)
                assertTrue(token, "UNSUPPORTED_RESTRICTION" in result.blockers)
                assertNull(token, result.input)
            }
        }
        // A visible identifier is not rejected merely for being non-ASCII.
        assertTrue(Gate.isSupportedRestriction("FOOD:carrot-🥕"))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, Gate.review(
            draft(), request(restrictions = setOf("FOOD:carrot-🥕", "ALLERGEN:SOY")),
            host).status)
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


    @Test fun requestedContextsMustBePresentAndApprovedAtFinalReview() {
        val f = Gate.Flags(true, true, true)
        val facts = mapOf("rice" to fact())
        val absent = Gate.review(draft(f), request(f), facts)
        assertEquals(Gate.Status.REVIEW_REQUIRED, absent.status)
        assertTrue("REQUESTED_CONTEXT_NOT_VALIDATED" in absent.gaps)
        assertNull(absent.input)

        val incomplete = Gate.context(f, Gate.Evidence("stock", true),
            null as Gate.Evidence<String>?, Gate.Evidence("offers", false))
        val pending = Gate.review(draft(f), request(f), facts, incomplete)
        assertEquals(Gate.Status.REVIEW_REQUIRED, pending.status)
        assertTrue("SHOPPING_SNAPSHOT_UNAVAILABLE" in pending.gaps)
        assertTrue("OFFERS_SNAPSHOT_UNAVAILABLE" in pending.gaps)
        assertNull(pending.input)

        val approved = Gate.context(f, Gate.Evidence("stock", true),
            Gate.Evidence("shopping", true), Gate.Evidence("offers", true))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,
            Gate.review(draft(f), request(f), facts, approved).status)

        val leaked = Gate.Context<String, String, String>("stock", null, null, emptySet())
        val rejected = Gate.review(draft(), request(), facts, leaked)
        assertEquals(Gate.Status.BLOCKED, rejected.status)
        assertTrue("CONTEXT_CONSENT_MISMATCH" in rejected.blockers)
        assertNull(rejected.input)
    }

    @Test fun contextProjectionCannotBeReusedAcrossAnyOfEightConsentCombinations() {
        val stock = Gate.Evidence("private-stock", true)
        val shopping = Gate.Evidence("private-shopping", true)
        val offers = Gate.Evidence("private-offers", true)
        val facts = mapOf("rice" to fact())
        for (sourceBits in 0..7) {
            val source = Gate.Flags(sourceBits and 1 != 0, sourceBits and 2 != 0,
                sourceBits and 4 != 0)
            val projected = Gate.context(source, stock, shopping, offers)
            assertEquals(source, projected.projectedFlags)
            for (requestBits in 0..7) {
                val target = Gate.Flags(requestBits and 1 != 0, requestBits and 2 != 0,
                    requestBits and 4 != 0)
                val reviewed = Gate.review(draft(target), request(target), facts, projected)
                if (source == target) {
                    assertEquals("source=$sourceBits target=$requestBits",
                        Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed.status)
                } else {
                    assertEquals("source=$sourceBits target=$requestBits",
                        Gate.Status.BLOCKED, reviewed.status)
                    assertTrue("source=$sourceBits target=$requestBits",
                        "CONTEXT_CONSENT_MISMATCH" in reviewed.blockers)
                    assertNull(reviewed.input)
                }
            }
        }
        // Caller-assembled values without a recorded host projection are not approval.
        val unbound = Gate.Context("private-stock", "private-shopping",
            "private-offers", emptySet<String>())
        val requested = Gate.Flags(true, true, true)
        val rejected = Gate.review(draft(requested), request(requested), facts, unbound)
        assertEquals(Gate.Status.BLOCKED, rejected.status)
        assertTrue("CONTEXT_CONSENT_MISMATCH" in rejected.blockers)
        assertNull(rejected.input)
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
