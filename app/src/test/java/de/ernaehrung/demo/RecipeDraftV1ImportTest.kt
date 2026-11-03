package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.integration.RecipeDraftV1Import as Import
import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson as Json
import de.ernaehrung.demo.data.integration.RecipeContextContentBinding as Binding
import de.ernaehrung.demo.data.local.BaseUnit
import java.time.Instant
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

/** Pinned v1 draft JSON is untrusted; tests use synthetic facts only. */
class RecipeDraftV1ImportTest {
    private val now = OffsetDateTime.parse("2026-11-01T05:00:00+01:00")
    private val flags = Gate.Flags(false, false, false)
    private val states = mapOf("rice" to Gate.State.RAW)
    private val facts = mapOf("rice" to Gate.HostLine(
        "rice", "version-rice", true, BaseUnit.GRAM,
        Gate.Basis.NET, true, Gate.State.RAW, true,
        allergens = mapOf("SOY" to false)
    ))
    private fun request(f: Gate.Flags = flags, save: Boolean = true) =
        Gate.Request(f, 2, setOf("ALLERGEN:SOY"), save)

    private val sample = """
        {
          "contractVersion":"1.0.0-draft",
          "messageType":"recipe.draft",
          "meta":{"producer":"EG-Rezept-KI","traceId":"synthetic",
            "createdAt":"2026-11-01T04:30:00+01:00",
            "inputsSnapshotAt":"2026-11-01T04:15:00+01:00"},
          "payload":{"draftId":"synthetic-1","title":"Reis",
            "portionCount":2,
            "ingredients":[{"label":"Reis","foodTypeId":"rice",
              "quantity":{"amount":200,"unit":"g","basis":"NET",
                "confirmed":true,"notes":"RAW according to untrusted AI"},
              "sourceStatus":"CONFIRMED","allergenNotes":"allergen-free claimed"}],
            "steps":["Reis kochen."],
            "flags":{"considerStock":false,"considerShopping":false,"considerOffers":false},
            "warnings":[],"status":"DRAFT",
            "nutrition":{"proteinG":999999},
            "xRecipeKi":{"verification":"STRUCTURALLY_VALID",
              "mealPrep":{"rawIngredientsCharged":false,
                "preparedPortionsCreated":false,"consumptionRecorded":false}}
          }
        }
    """.trimIndent()
    private fun change(before: String, after: String) =
        sample.replace(before, after).also { assertNotEquals(sample, it) }
    private fun reviewed(
        json: String = sample, state: Map<String, Gate.State> = states,
        host: Map<String, Gate.HostLine> = facts,
        req: Gate.Request = request(),
        context: Gate.Context<*, *, *>? = null,
        trustedSnapshot: OffsetDateTime? = null
    ) = Import.review(json, req, host, state, now, context, trustedSnapshot)

    @Test fun importRejectsDuplicateIngredientsAndForeignReviewedStatesBeforePreview() {
        val duplicated = change(
            "\"sourceStatus\":\"CONFIRMED\",\"allergenNotes\":\"allergen-free claimed\"}]",
            "\"sourceStatus\":\"CONFIRMED\",\"allergenNotes\":\"allergen-free claimed\"}," +
                "{\"label\":\"Reis doppelt\",\"foodTypeId\":\"rice\"," +
                "\"quantity\":{\"amount\":100,\"unit\":\"g\",\"basis\":\"NET\"," +
                "\"confirmed\":false},\"sourceStatus\":\"UNKNOWN\"}]"
        )
        try {
            Import.read(duplicated, states, now)
            fail("duplicate foodTypeId must fail at import")
        } catch (_: IllegalArgumentException) { }
        assertEquals(Gate.Status.BLOCKED, reviewed(duplicated).status)
        assertNull(reviewed(duplicated).input)

        val foreignStates = states + ("other-food" to Gate.State.COOKED)
        try {
            Import.read(sample, foreignStates, now)
            fail("foreign reviewed state must fail at import")
        } catch (_: IllegalArgumentException) { }
        assertEquals(Gate.Status.BLOCKED, reviewed(state = foreignStates).status)
        assertNull(reviewed(state = foreignStates).input)

        // A wholly unrelated review state is not applied to this recipe:
        // missing per-ingredient state remains UNKNOWN, not a false approval.
        val onlyForeign = mapOf("other-food" to Gate.State.COOKED)
        assertEquals(Gate.Status.REVIEW_REQUIRED, reviewed(state = onlyForeign).status)
        assertEquals("synthetic-1", Import.read(sample, emptyMap(), now).draftId)
        assertEquals(Gate.Status.REVIEW_REQUIRED, reviewed(state = emptyMap()).status)
    }

    @Test fun validV1DraftRequiresIndependentHostFactsAndExplicitSave() {
        val result = reviewed()
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, result.status)
        assertEquals(200_000L, result.input!!.ingredients.single().amount)
        assertEquals("version-rice", result.input!!.ingredients.single().versionId)
        assertEquals(2_000L, result.input!!.yieldPortions)
        assertEquals("synthetic-1", Import.read(sample, states, now).draftId)
    }

    @Test fun claimedConfirmedStateNutritionAndVerificationNeverAuthorizeRecipe() {
        val withoutReview = reviewed(state = emptyMap())
        assertEquals(Gate.Status.REVIEW_REQUIRED, withoutReview.status)
        assertTrue("UNKNOWN_PREPARATION_STATE" in withoutReview.gaps)
        assertNull(withoutReview.input)
        val withoutSave = reviewed(req = request(save = false))
        assertEquals(Gate.Status.REVIEW_REQUIRED, withoutSave.status)
        assertNull(withoutSave.input)
        val allergic = reviewed(host = facts.mapValues {
            it.value.copy(allergens = mapOf("SOY" to true))
        })
        assertEquals(Gate.Status.BLOCKED, allergic.status)
        assertNull(allergic.input)
        val unknown = reviewed(host = facts.mapValues { it.value.copy(allergens = emptyMap()) })
        assertEquals(Gate.Status.REVIEW_REQUIRED, unknown.status)
    }

    private fun approvedContext(f: Gate.Flags): Gate.Context<Binding.Snapshot,
        Binding.Snapshot, Binding.Snapshot> {
        val at = Instant.parse("2026-11-01T03:15:00Z")
        fun snapshot(source: Binding.Source) = Binding.Snapshot(
            source, "synthetic-" + source.name, "revision-1", at,
            listOf(Binding.Line("rice", "version-rice", 200_000L, "g"))
        )
        return Gate.typedContext(f, at,
            Gate.Evidence(snapshot(Binding.Source.STOCK), true),
            Gate.Evidence(snapshot(Binding.Source.SHOPPING), true),
            Gate.Evidence(snapshot(Binding.Source.OFFERS), true))
    }

    @Test fun allEightIndependentConsentFlagsRequireMatchingDatedApprovedContext() {
        for (bits in 0..7) {
            val f = Gate.Flags(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            val json = sample
                .replace("\"considerStock\":false", "\"considerStock\":" + f.considerStock)
                .replace("\"considerShopping\":false", "\"considerShopping\":" + f.considerShopping)
                .replace("\"considerOffers\":false", "\"considerOffers\":" + f.considerOffers)
            val trusted = if (bits == 0) null
                else OffsetDateTime.parse("2026-11-01T04:15:00+01:00")
            val typed = approvedContext(f)
            assertEquals("bits=" + bits, Gate.Status.READY_FOR_EXPLICIT_SAVE,
                reviewed(json, req = request(f), context = typed,
                    trustedSnapshot = trusted).status)
            if (bits != 0) {
                // Independent approval of a timestamp and generic projection
                // cannot substitute for a digest of the selected host rows.
                val untyped = Gate.context(f,
                    Gate.Evidence("stock", true), Gate.Evidence("shopping", true),
                    Gate.Evidence("offers", true))
                val unbound = reviewed(json, req = request(f), context = untyped,
                    trustedSnapshot = trusted)
                assertEquals(Gate.Status.REVIEW_REQUIRED, unbound.status)
                assertTrue("TYPED_CONTEXT_CONTENT_REQUIRED" in unbound.gaps)
                assertNull(unbound.input)
                assertEquals(Gate.Status.REVIEW_REQUIRED,
                    reviewed(json, req = request(f)).status)
                assertEquals(Gate.Status.BLOCKED,
                    reviewed(json, req = request()).status)
            }
        }
    }


    @Test fun activeContextRequiresIndependentMatchingHostSnapshotTimestamp() {
        val f = Gate.Flags(true, false, false)
        val json = change("\"considerStock\":false", "\"considerStock\":true")
        val c = Gate.context(f,
            Gate.Evidence("stock", true), Gate.Evidence("shopping", true),
            Gate.Evidence("offers", true))
        val missing = reviewed(json, req = request(f), context = c)
        assertEquals(Gate.Status.REVIEW_REQUIRED, missing.status)
        assertTrue("TRUSTED_CONTEXT_SNAPSHOT_REQUIRED" in missing.gaps)
        assertNull(missing.input)
        val wrong = reviewed(json, req = request(f), context = c,
            trustedSnapshot = OffsetDateTime.parse("2026-11-01T04:15:01+01:00"))
        assertEquals(Gate.Status.BLOCKED, wrong.status)
        assertTrue("CONTEXT_SNAPSHOT_TIME_MISMATCH" in wrong.blockers)
        assertNull(wrong.input)
        val sameInstant = reviewed(json, req = request(f), context = c,
            trustedSnapshot = OffsetDateTime.parse("2026-11-01T03:15:00Z"))
        assertEquals(Gate.Status.REVIEW_REQUIRED, sameInstant.status)
        assertTrue("TYPED_CONTEXT_CONTENT_REQUIRED" in sameInstant.gaps)
        assertNull(sameInstant.input)
    }

    @Test fun typedContextMustMatchTheIndependentlyTrustedSnapshotInstant() {
        val requested = Gate.Flags(true, false, false)
        val json = change("\"considerStock\":false", "\"considerStock\":true")
        val trusted = OffsetDateTime.parse("2026-11-01T04:15:00+01:00")
        fun boundContext(at: Instant): Gate.Context<Binding.Snapshot,
            Binding.Snapshot, Binding.Snapshot> {
            val stock = Binding.Snapshot(
                Binding.Source.STOCK, "synthetic-stock", "revision-1", at,
                listOf(Binding.Line("rice", "version-rice", 200_000L, "g"))
            )
            return Gate.typedContext(requested, at, Gate.Evidence(stock, true),
                null, null)
        }
        val correct = reviewed(json, req = request(requested),
            context = boundContext(Instant.parse("2026-11-01T03:15:00Z")),
            trustedSnapshot = trusted)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, correct.status)
        assertNotNull(correct.input)

        // The binding is internally valid but describes another snapshot.
        // Its own digest must not authorize the draft's later snapshot.
        val wrong = reviewed(json, req = request(requested),
            context = boundContext(Instant.parse("2026-11-01T03:14:00Z")),
            trustedSnapshot = trusted)
        assertEquals(Gate.Status.BLOCKED, wrong.status)
        assertTrue("CONTEXT_CONTENT_SNAPSHOT_TIME_MISMATCH" in wrong.blockers)
        assertNull(wrong.input)
    }

    @Test fun contextFlagRequiresDatedSnapshotAndNoConsentLeakage() {
        val json = change("\"considerStock\":false", "\"considerStock\":true")
        val missing = json.replace(
            "\"inputsSnapshotAt\":\"2026-11-01T04:15:00+01:00\"",
            "\"inputsSnapshotAt\":null")
        assertEquals(Gate.Status.BLOCKED,
            reviewed(missing, req = request(Gate.Flags(true, false, false))).status)
        val leaked = Gate.Context<String, String, String>("stock", null, null, emptySet())
        assertEquals(Gate.Status.BLOCKED, reviewed(context = leaked).status)
    }

    @Test fun staleFutureAndReversedEvidenceTimeAreRejected() {
        for (json in listOf(
            change("2026-11-01T04:30:00+01:00", "2026-10-30T01:00:00+01:00"),
            change("2026-11-01T04:30:00+01:00", "2026-11-01T06:00:00+01:00"),
            change("2026-11-01T04:15:00+01:00", "2026-10-30T01:00:00+01:00"),
            change("2026-11-01T04:15:00+01:00", "2026-11-01T04:50:00+01:00"),
            change("2026-11-01T04:30:00+01:00", "2026-11-01T04:30:00"),
            change("2026-11-01T04:15:00+01:00", "yesterday")
        )) {
            assertEquals(Gate.Status.BLOCKED, reviewed(json).status)
        }
    }

    @Test fun jsonParserRejectsDuplicateKeysFractionalIntegersAndInvalidUnicode() {
        for (json in listOf(
            change("\"amount\":200", "\"amount\":200,\"amount\":300"),
            change("\"amount\":200", "\"amount\":2.00"),
            change("\"amount\":200", "\"amount\":2e2"),
            change("\"amount\":200", "\"amount\":9223372036854775808"),
            change("\"amount\":200", "\"amount\":-1"),
            change("\"amount\":200", "\"amount\":\"200\""),
            change("\"title\":\"Reis\"", "\"title\":\"\\uD800\""),
            sample + " trailing"
        )) {
            assertEquals(Gate.Status.BLOCKED, reviewed(json).status)
        }
        assertEquals(Gate.Status.BLOCKED, reviewed("[" + sample + "]").status)
    }

    @Test fun zeroIsInvalidButNullIsUnknownAndNeverConvertedToZero() {
        val zeroJson = change("\"amount\":200", "\"amount\":0")
        try {
            Import.read(zeroJson, states, now)
            fail("known zero quantity must not reach recipe preview")
        } catch (_: IllegalArgumentException) { }
        val zero = reviewed(zeroJson)
        assertEquals(Gate.Status.BLOCKED, zero.status)
        assertNull(zero.input)

        val unknownJson = change("\"amount\":200", "\"amount\":null")
        assertNull(Import.read(unknownJson, states, now).draft.lines.single().amount)
        val unknown = reviewed(unknownJson)
        assertEquals(Gate.Status.REVIEW_REQUIRED, unknown.status)
        assertTrue("UNKNOWN_AMOUNT" in unknown.gaps)
        assertNull(unknown.input)

        assertEquals(200L, Import.read(sample, states, now).draft.lines.single().amount)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed().status)
    }

    @Test fun wrongVersionTypeStatusOrExtraMetaCannotBeImported() {
        for (json in listOf(
            change("1.0.0-draft", "1.1.0-draft"),
            change("recipe.draft", "receipt.line"),
            change("\"status\":\"DRAFT\"", "\"status\":\"CONFIRMED\""),
            change("\"traceId\":\"synthetic\"", "\"traceId\":\"synthetic\",\"hostApproved\":true"),
            change("\"considerOffers\":false", "\"considerOffers\":\"false\""),
            change("\"portionCount\":2", "\"portionCount\":2.5"),
            change("\"warnings\":[]", "\"warnings\":[0]"),
            change("\"steps\":[\"Reis kochen.\"]", "\"steps\":[]")
        )) assertEquals(Gate.Status.BLOCKED, reviewed(json).status)
    }

    @Test fun untrustedBookingClaimsAreAlwaysRejected() {
        for (field in listOf("rawIngredientsCharged", "preparedPortionsCreated",
            "consumptionRecorded")) {
            val json = change("\"" + field + "\":false", "\"" + field + "\":true")
            assertEquals(Gate.Status.BLOCKED, reviewed(json).status)
        }
    }

    @Test fun bookingClaimsInNestedAndUnknownExtensionsNeverBecomeHostEvents() {
        val forbidden = listOf(
            change("\"status\":\"DRAFT\"", "\"status\":\"DRAFT\",\"consumptionRecorded\":true"),
            change("\"label\":\"Reis\"", "\"label\":\"Reis\",\"preparedPortionsCreated\":true"),
            change("\"nutrition\":{\"proteinG\":999999}",
                "\"nutrition\":{\"proteinG\":999999,\"rawIngredientsCharged\":true}"),
            change("\"warnings\":[]", "\"warnings\":[],\"sideEffects\":[{\"consumptionRecorded\":true}]"),
            change("\"warnings\":[]", "\"warnings\":[],\"consumptionRecorded\":null")
        )
        forbidden.forEach { candidate ->
            val review = reviewed(candidate)
            assertEquals(Gate.Status.BLOCKED, review.status)
            assertNull(review.input)
        }
        // A false extension claim is not itself proof of a real event or a blocker.
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,
            reviewed(change("\"status\":\"DRAFT\"",
                "\"status\":\"DRAFT\",\"consumptionRecorded\":false")).status)
    }

    @Test fun futureDatedHostContextCannotAttestARecipeEvenWithinProducerClockSkew() {
        val f = Gate.Flags(true, false, false)
        val futureJson = sample
            .replace("2026-11-01T04:30:00+01:00", "2026-11-01T05:03:00+01:00")
            .replace("2026-11-01T04:15:00+01:00", "2026-11-01T05:02:00+01:00")
            .replace("\"considerStock\":false", "\"considerStock\":true")
        val c = Gate.context(f, Gate.Evidence("stock", true),
            null as Gate.Evidence<String>?, null as Gate.Evidence<String>?)
        val result = reviewed(futureJson, req = request(f), context = c,
            trustedSnapshot = OffsetDateTime.parse("2026-11-01T05:02:00+01:00"))
        assertEquals(Gate.Status.BLOCKED, result.status)
        assertTrue("TRUSTED_CONTEXT_SNAPSHOT_IN_FUTURE" in result.blockers)
        assertNull(result.input)
    }

    @Test fun incompatibleBasisAndUnknownVersionFailClosed() {
        assertEquals(Gate.Status.BLOCKED,
            reviewed(change("\"basis\":\"NET\"", "\"basis\":\"GROSS\"")).status)
        assertEquals(Gate.Status.BLOCKED,
            reviewed(change("\"unit\":\"g\"", "\"unit\":\"piece\"")).status)
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            reviewed(host = emptyMap()).status)
        // An unknown ingredient with an old ingredient's host evidence is a
        // cross-draft scope mismatch, not merely an unresolved current version.
        val mismatched = reviewed(change("\"foodTypeId\":\"rice\"",
            "\"foodTypeId\":\"unknown\""))
        assertEquals(Gate.Status.BLOCKED, mismatched.status)
        assertTrue("FOREIGN_INGREDIENT_EVIDENCE" in mismatched.blockers)
        assertNull(mismatched.input)
    }

    @Test fun unsupportedSourceStatusOrQuantityFlagsFailClosed() {
        for (json in listOf(
            change("\"sourceStatus\":\"CONFIRMED\"", "\"sourceStatus\":\"AI_VERIFIED\""),
            change("\"confirmed\":true", "\"confirmed\":\"true\""),
            change("\"amount\":200", "\"amount\":200,\"packageCount\":0"),
            change("\"amount\":200", "\"amount\":200,\"packageCount\":-3"),
            change("\"foodTypeId\":\"rice\"", "\"foodTypeId\":null")
        )) assertEquals(Gate.Status.BLOCKED, reviewed(json).status)
    }

    @Test fun boundedDecoderRejectsOversizedDepthAndNodeBombs() {
        assertEquals(Gate.Status.BLOCKED, reviewed(" ".repeat(262_145)).status)
        val deep = "{\"nested\":".repeat(40) + "null" + "}".repeat(40)
        try { Json.decode(deep); fail("depth must be bounded") }
        catch (_: IllegalArgumentException) { }
        val nodes = "{\"a\":[" + List(10_001) { "null" }.joinToString(",") + "]}"
        try { Json.decode(nodes); fail("nodes must be bounded") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun offsetAwareComparisonUsesInstantNotLocalClock() {
        val equivalent = sample.replace("2026-11-01T04:30:00+01:00",
            "2026-11-01T03:30:00Z")
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed(equivalent).status)
        val future = sample.replace("2026-11-01T04:30:00+01:00",
            "2026-11-01T05:30:00Z")
        assertEquals(Gate.Status.BLOCKED, reviewed(future).status)
    }

    @Test fun untrustedDraftHostPreviewBoundsApplyAtImportNotOnlyAtReview() {
        // The host review already has these limits. Invalid generator output must
        // be rejected before a draft is exposed to the manual-review UI.
        val maxTitle = change("\"title\":\"Reis\"",
            "\"title\":\"" + "T".repeat(200) + "\"")
        assertEquals(200, Import.read(maxTitle, states, now).draft.title.length)
        val longTitle = change("\"title\":\"Reis\"",
            "\"title\":\"" + "T".repeat(201) + "\"")
        try {
            Import.read(longTitle, states, now)
            fail("oversized title reached preview")
        } catch (_: IllegalArgumentException) { }
        assertEquals(Gate.Status.BLOCKED, reviewed(longTitle).status)
        assertNull(reviewed(longTitle).input)

        val maxSteps = change("\"steps\":[\"Reis kochen.\"]",
            "\"steps\":[\"" + "S".repeat(20_000) + "\"]")
        assertEquals(20_000, Import.read(maxSteps, states, now).draft.steps.length)
        val longSteps = change("\"steps\":[\"Reis kochen.\"]",
            "\"steps\":[\"" + "S".repeat(20_001) + "\"]")
        try {
            Import.read(longSteps, states, now)
            fail("oversized steps reached preview")
        } catch (_: IllegalArgumentException) { }
        assertEquals(Gate.Status.BLOCKED, reviewed(longSteps).status)
        assertNull(reviewed(longSteps).input)

        val start = sample.indexOf("\"ingredients\":[")
        val end = sample.indexOf("\"steps\":", start)
        assertTrue(start >= 0 && end > start)
        fun withIngredients(count: Int): String {
            val ingredients = (0 until count).joinToString(",") { i ->
                """{"label":"Synthetic","foodTypeId":"food$i","quantity":{"amount":1,"unit":"g","basis":"NET","confirmed":false},"sourceStatus":"UNKNOWN"}"""
            }
            return sample.substring(0, start) +
                "\"ingredients\":[$ingredients],\n            " + sample.substring(end)
        }
        assertEquals(64, Import.read(withIngredients(64), states, now).draft.lines.size)
        val tooMany = withIngredients(65)
        try {
            Import.read(tooMany, states, now)
            fail("oversized ingredient list reached preview")
        } catch (_: IllegalArgumentException) { }
        assertEquals(Gate.Status.BLOCKED, reviewed(tooMany).status)
        assertNull(reviewed(tooMany).input)
    }


    @Test fun previewRejectsInvisibleControlsAndOversizedIngredientLabels() {
        val unsafe = listOf(
            change("\"title\":\"Reis\"", "\"title\":\"Re\\u0000is\""),
            change("\"title\":\"Reis\"", "\"title\":\"Re\\nis\""),
            change("\"label\":\"Reis\"", "\"label\":\"Re\\u202Eis\""),
            change("\"steps\":[\"Reis kochen.\"]",
                "\"steps\":[\"Reis\\u2066 kochen.\"]"),
            change("\"label\":\"Reis\"",
                "\"label\":\"" + "L".repeat(201) + "\"")
        )
        for (candidate in unsafe) {
            try {
                Import.read(candidate, states, now)
                fail("unsafe untrusted text must not reach manual preview")
            } catch (_: IllegalArgumentException) { }
            val review = reviewed(candidate)
            assertEquals(Gate.Status.BLOCKED, review.status)
            assertNull(review.input)
        }

        val maxLabel = change("\"label\":\"Reis\"",
            "\"label\":\"" + "Ä".repeat(200) + "\"")
        assertEquals(200, Import.read(maxLabel, states, now).draft.lines.single().label.length)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed(maxLabel).status)

        val multilineStep = change("\"steps\":[\"Reis kochen.\"]",
            "\"steps\":[\"Reis\\nkochen.\"]")
        assertEquals("Reis\nkochen.", Import.read(multilineStep, states, now).draft.steps)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed(multilineStep).status)
    }


    @Test fun invisibleUnicodeFormatCharactersNeverReachRecipeManualReview() {
        // Format controls are invisible or reorder visible text. Check both
        // BMP controls and supplementary tag code points, not just UTF-16 chars.
        val supplementaryTag = String(Character.toChars(0xE0061))
        val unsafe = listOf(
            change("\"title\":\"Reis\"", "\"title\":\"Re\\u200Bis\""),
            change("\"label\":\"Reis\"", "\"label\":\"Re\\u00ADis\""),
            change("\"steps\":[\"Reis kochen.\"]",
                "\"steps\":[\"Re\\u2060is kochen.\"]"),
            change("\"title\":\"Reis\"", "\"title\":\"Re\\uFEFFis\""),
            change("\"label\":\"Reis\"", "\"label\":\"Re\\u034Fis\""),
            change("\"title\":\"Reis\"", "\"title\":\"Re" + supplementaryTag + "is\"")
        )
        unsafe.forEach { candidate ->
            try {
                Import.read(candidate, states, now)
                fail("invisible format control reached preview")
            } catch (_: IllegalArgumentException) { }
            val result = reviewed(candidate)
            assertEquals(Gate.Status.BLOCKED, result.status)
            assertNull(result.input)
        }

        // Accented and non-BMP visible characters remain valid.
        val readable = change("\"title\":\"Reis\"",
            "\"title\":\"Café und Reis\"")
        assertEquals("Café und Reis", Import.read(readable, states, now).draft.title)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed(readable).status)
        val visibleEmoji = change("\"label\":\"Reis\"",
            "\"label\":\"Möhre 🥕\"")
        assertEquals("Möhre 🥕", Import.read(visibleEmoji, states, now).draft.lines.single().label)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, reviewed(visibleEmoji).status)
    }

}
