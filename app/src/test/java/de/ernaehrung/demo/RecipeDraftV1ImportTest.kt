package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.integration.RecipeDraftV1Import as Import
import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson as Json
import de.ernaehrung.demo.data.local.BaseUnit
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

    @Test fun allEightIndependentConsentFlagsRequireMatchingDatedApprovedContext() {
        for (bits in 0..7) {
            val f = Gate.Flags(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            val json = sample
                .replace("\"considerStock\":false", "\"considerStock\":" + f.considerStock)
                .replace("\"considerShopping\":false", "\"considerShopping\":" + f.considerShopping)
                .replace("\"considerOffers\":false", "\"considerOffers\":" + f.considerOffers)
            val c = Gate.context(f,
                Gate.Evidence("stock", true), Gate.Evidence("shopping", true),
                Gate.Evidence("offers", true))
            assertEquals("bits=" + bits, Gate.Status.READY_FOR_EXPLICIT_SAVE,
                reviewed(json, req = request(f), context = c,
                    trustedSnapshot = if (bits == 0) null
                        else OffsetDateTime.parse("2026-11-01T04:15:00+01:00")).status)
            if (bits != 0) {
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
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, sameInstant.status)
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
        val zero = reviewed(change("\"amount\":200", "\"amount\":0"))
        assertEquals(Gate.Status.BLOCKED, zero.status)
        val unknown = reviewed(change("\"amount\":200", "\"amount\":null"))
        assertEquals(Gate.Status.REVIEW_REQUIRED, unknown.status)
        assertTrue("UNKNOWN_AMOUNT" in unknown.gaps)
        assertNull(unknown.input)
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
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            reviewed(change("\"foodTypeId\":\"rice\"", "\"foodTypeId\":\"unknown\"")).status)
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
}
