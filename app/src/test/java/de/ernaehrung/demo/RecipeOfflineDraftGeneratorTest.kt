package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeOfflineDraftGenerator
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate
import de.ernaehrung.demo.data.integration.RecipeDraftV1Import
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeOfflineDraftGeneratorTest {
    private val now = OffsetDateTime.parse("2026-11-04T06:00:00Z")

    @Test fun all27CombinationsPassTheRealV1ImporterWithoutGrantingTrust() {
        val titles = mutableSetOf<String>()
        for (variant in 0 until RecipeOfflineDraftGenerator.VARIANT_COUNT) {
            val json = RecipeOfflineDraftGenerator.generate(2, variant, now, "offline-$variant")
            val imported = RecipeDraftV1Import.read(json, emptyMap(), now)
            assertEquals("offline-$variant", imported.draftId)
            assertEquals(2, imported.draft.portions)
            assertEquals(3, imported.draft.lines.size)
            assertTrue(imported.draft.lines.all { it.amount != null && it.amount > 0L })
            assertTrue(imported.draft.lines.all { it.state == RecipeDraftHostGate.State.UNKNOWN })
            assertTrue(titles.add(imported.draft.title))
            val request = RecipeDraftHostGate.Request(
                imported.draft.flags, imported.draft.portions, userConfirmedRecipeSave = false
            )
            val review = RecipeDraftV1Import.review(
                json, request, emptyMap(), emptyMap(), now
            )
            assertNotEquals(RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE, review.status)
            assertNull(review.input)
        }
        assertEquals(27, titles.size)
    }

    @Test fun eightContextCombinationsFailClosedWithoutApprovedTypedSnapshots() {
        for (mask in 0..7) {
            val flags = RecipeDraftHostGate.Flags(
                mask and 1 != 0, mask and 2 != 0, mask and 4 != 0
            )
            val result = runCatching {
                RecipeOfflineDraftGenerator.generate(2, 0, now, "offline-context-$mask", flags)
            }
            if (mask == 0) {
                assertTrue(result.isSuccess)
                val imported = RecipeDraftV1Import.read(result.getOrThrow(), emptyMap(), now)
                assertFalse(imported.draft.flags.considerStock)
                assertFalse(imported.draft.flags.considerShopping)
                assertFalse(imported.draft.flags.considerOffers)
            } else {
                assertTrue("unapproved context mask $mask must be refused", result.isFailure)
            }
        }
    }


    @Test fun allEightContextFlagsAreEncodedButNeverSelfAuthorizeARecipe() {
        val capturedAt = now.minusSeconds(30)
        for (mask in 0..7) {
            val flags = RecipeDraftHostGate.Flags(
                mask and 1 != 0, mask and 2 != 0, mask and 4 != 0
            )
            val json = RecipeOfflineDraftGenerator.generate(
                2, 0, now, "context-$mask", flags,
                if (mask == 0) null else capturedAt
            )
            val imported = RecipeDraftV1Import.read(json, emptyMap(), now)
            assertEquals(flags, imported.draft.flags)
            assertEquals(if (mask == 0) null else capturedAt.toInstant(),
                imported.snapshotAt?.toInstant())
            val request = RecipeDraftHostGate.Request(flags, 2, userConfirmedRecipeSave = true)
            val preview = RecipeDraftV1Import.review(
                json, request, emptyMap(), emptyMap(), now
            )
            assertNotEquals(RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE, preview.status)
            assertNull(preview.approvalBasisDigest)
        }
    }

    @Test fun invalidPortionsVariantAndUntrustedIdentifierCannotBecomeDrafts() {
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.generate(0, 0, now, "offline-zero")
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.generate(1001, 0, now, "offline-large")
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.generate(2, 27, now, "offline-variant")
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.generate(2, -1, now, "offline-negative")
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.generate(2, 0, now, "x\"},\"status\":\"CONFIRMED")
        }.isFailure)
    }

    @Test fun portionsScaleExactlyAndNoBookingOrAllergenClearanceIsClaimed() {
        val one = RecipeDraftV1Import.read(
            RecipeOfflineDraftGenerator.generate(1, 0, now, "offline-one"),
            emptyMap(), now
        )
        val three = RecipeDraftV1Import.read(
            RecipeOfflineDraftGenerator.generate(3, 0, now, "offline-three"),
            emptyMap(), now
        )
        assertEquals(listOf(75L, 130L, 120L), one.draft.lines.map { it.amount })
        assertEquals(listOf(225L, 390L, 360L), three.draft.lines.map { it.amount })
        assertTrue(three.draft.steps.contains("Kühlkette"))
    }
    @Test fun foodExclusionsSelectTheNextCompatibleOfflineVariantWithoutChangingDraftTrust() {
        val excluded = setOf("FOOD:dry-rice", "FOOD:tomato")
        val candidates = (0 until RecipeOfflineDraftGenerator.VARIANT_COUNT).filter {
            RecipeOfflineDraftGenerator.allowsSuggestion(it, excluded)
        }
        assertEquals(12, candidates.size)
        val first = RecipeOfflineDraftGenerator.nextAllowedVariant(0, excluded)
        assertEquals(10, first)
        val draft = RecipeDraftV1Import.read(
            RecipeOfflineDraftGenerator.generate(2, requireNotNull(first), now, "excluded-food"),
            emptyMap(), now
        )
        assertTrue(draft.draft.lines.none { it.foodTypeId == "dry-rice" || it.foodTypeId == "tomato" })
        assertTrue(draft.draft.lines.all { it.state == RecipeDraftHostGate.State.UNKNOWN })
        val preview = RecipeDraftV1Import.review(
            RecipeOfflineDraftGenerator.generate(2, first, now, "excluded-review"),
            RecipeDraftHostGate.Request(draft.draft.flags, 2, userConfirmedRecipeSave = false),
            emptyMap(), emptyMap(), now
        )
        assertNull(preview.input)
    }

    @Test fun unknownAllergenEvidenceBlocksAllSuggestionsUntilEachIngredientIsCleared() {
        val restriction = setOf("ALLERGEN:SOY")
        assertNull(RecipeOfflineDraftGenerator.nextAllowedVariant(0, restriction))
        val selected = RecipeOfflineDraftGenerator.ingredientsFor(0)
        val confirmedAbsent = selected.associate {
            it.foodTypeId to mapOf("SOY" to false)
        }
        assertEquals(0, RecipeOfflineDraftGenerator.nextAllowedVariant(
            0, restriction, confirmedAbsent
        ))
        val incomplete = confirmedAbsent - selected.first().foodTypeId
        assertFalse(RecipeOfflineDraftGenerator.allowsSuggestion(0, restriction, incomplete))
        assertFalse(RecipeOfflineDraftGenerator.allowsSuggestion(
            0, restriction, confirmedAbsent + (selected.first().foodTypeId to mapOf("SOY" to true))
        ))
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.nextAllowedVariant(0, setOf("ALLERGEN:"))
        }.isFailure)
    }

    @Test fun generatorEnforcesRestrictionsAgainAfterCandidateSelection() {
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.generate(2, 0, now, "excluded-food-direct",
                requestedRestrictions = setOf("FOOD:dry-rice"))
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.generate(2, 0, now, "unverified-allergen-direct",
                requestedRestrictions = setOf("ALLERGEN:SOY"))
        }.isFailure)
        assertTrue(runCatching {
            RecipeOfflineDraftGenerator.generate(2, 0, now, "malformed-restriction-direct",
                requestedRestrictions = setOf("ALLERGEN:"))
        }.isFailure)

        val allowedJson = RecipeOfflineDraftGenerator.generate(
            2, 9, now, "allowed-food-direct",
            requestedRestrictions = setOf("FOOD:dry-rice")
        )
        val imported = RecipeDraftV1Import.read(allowedJson, emptyMap(), now)
        assertFalse(imported.draft.lines.any { it.foodTypeId == "dry-rice" })
        val preview = RecipeDraftV1Import.review(
            allowedJson,
            RecipeDraftHostGate.Request(
                imported.draft.flags, 2, setOf("FOOD:dry-rice"),
                userConfirmedRecipeSave = false
            ),
            emptyMap(), emptyMap(), now
        )
        assertNull(preview.input)
        assertNotEquals(RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE, preview.status)
    }

}
