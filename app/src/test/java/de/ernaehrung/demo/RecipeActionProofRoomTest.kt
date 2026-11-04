package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real FoodStore/Room writes; synthetic JCA signer, not an Android credential test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeActionProofRoomTest {
    private val now = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val verifier = RecipeActionProofVerifier { proof, key, digest, at ->
        if (!RecipeActionProofPayload.fresh(proof, key, digest, at)) false
        else runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyPair.public)
                update(RecipeActionProofPayload.bytes(proof))
                verify(Base64.getDecoder().decode(proof.signatureBase64))
            }
        }.getOrDefault(false)
    }
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var facts: Map<String, Catalog.ConfirmedLine>
    private val states = mapOf("rice" to Gate.State.RAW)
    private val request = Gate.Request(Gate.Flags(false, false, false), 2,
        setOf("ALLERGEN:SOY"), userConfirmedRecipeSave = true)
    private val json = """
        {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
         "meta":{"producer":"synthetic","traceId":"action-proof-test",
                 "createdAt":"2026-11-01T04:00:00Z"},
         "payload":{"draftId":"draft-action","title":"Reis","portionCount":2,
           "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"UNKNOWN",
             "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":false}}],
           "steps":["Kochen"],"flags":{"considerStock":false,
             "considerShopping":false,"considerOffers":false},
           "warnings":[],"status":"DRAFT"}}
    """.trimIndent()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        store = FoodStore(db, { now }, verifier)
        val id = store.rememberProduct("action-rice", ProductInput("Reis",
            source = Source.USER, quality = Quality.CONFIRMED))
        val v = requireNotNull(db.foodDao().version(id))
        facts = mapOf("rice" to Catalog.ConfirmedLine("rice", v.id, v.revision,
            v.recordedAt, now, BaseUnit.GRAM, Gate.Basis.NET, Gate.State.RAW,
            allergens = mapOf("SOY" to false)))
    }
    @After fun close() { db.close() }

    private fun sign(key: String, digest: String, nonce: String,
        issuedAt: Long = now): RecipeActionProof {
        val unsigned = RecipeActionProof(key, digest, issuedAt, nonce, "")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(RecipeActionProofPayload.bytes(unsigned))
            sign()
        }
        return unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(signature))
    }

    private suspend fun consent(key: String, review: Gate.Review,
        proof: RecipeActionProof? = null) =
        store.recordReviewedRecipeDraftV1Consent(key, json, request, facts, states,
            review, true, actionProof = proof)

    private suspend fun attestedSave(key: String, review: Gate.Review) =
        store.saveReviewedRecipeDraftV1(key, json, request, facts, states, review,
            true)

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected FoodStore rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun signedCredentialActionCreatesAttestedConsentThenRecipeOnly() = runBlocking {
        val review = store.previewRecipeDraftV1(json, request, facts, states)
        val digest = requireNotNull(review.approvalBasisDigest)
        val nonce = store.beginRecipeDraftCredentialAction("proof-ok", digest)
        val proof = sign("proof-ok", digest, nonce)
        assertEquals(digest, consent("proof-ok", review, proof))
        assertEquals("RECIPE_DRAFT_ATTESTED_CONSENT_V1",
            db.foodDao().operation("RECIPE_DRAFT_CONSENT_V1:proof-ok")?.kind)
        val version = attestedSave("proof-ok", review)
        assertNotNull(db.foodDao().recipeVersion(version))
        assertEquals(version, attestedSave("proof-ok", review))
        assertEquals(0, db.foodDao().consumptionCount())
        assertTrue(db.foodDao().allPaidPrices().isEmpty())
        assertEquals(1, db.foodDao().observeRecipes().first().size)
        rejected { consent("proof-ok", review, proof) } // nonce already consumed
    }

    @Test fun forgedStaleCancelledAndProcessDeathProofsFailClosed() = runBlocking {
        val review = store.previewRecipeDraftV1(json, request, facts, states)
        val digest = requireNotNull(review.approvalBasisDigest)
        val baseline = db.foodDao().operationCount()

        val nonce = store.beginRecipeDraftCredentialAction("proof-bad", digest)
        rejected { consent("proof-bad", review,
            sign("proof-bad", digest, nonce).copy(signatureBase64 = "invalid")) }
        rejected { consent("proof-bad", review, sign("proof-bad", digest, nonce)) }
        assertEquals(baseline, db.foodDao().operationCount())

        val staleNonce = store.beginRecipeDraftCredentialAction("proof-stale", digest)
        rejected { consent("proof-stale", review,
            sign("proof-stale", digest, staleNonce, now - 30_001L)) }

        val otherNonce = store.beginRecipeDraftCredentialAction("proof-other", digest)
        rejected { consent("proof-other", review, sign("different-key", digest, otherNonce)) }

        val crashNonce = store.beginRecipeDraftCredentialAction("proof-crash", digest)
        val crashProof = sign("proof-crash", digest, crashNonce)
        store = FoodStore(db, { now }, verifier) // new process: no in-memory challenge
        rejected { consent("proof-crash", review, crashProof) }

        val legacyKey = "proof-legacy"
        rejected { consent(legacyKey, review) }
        rejected { attestedSave(legacyKey, review) }
        assertEquals(0, db.foodDao().consumptionCount())
        assertTrue(db.foodDao().observeRecipes().first().isEmpty())
    }

    @Test fun legacyUnsignedConsentCannotResumeOrSaveThroughTheDefaultFoodStoreEntry() = runBlocking {
        val review = store.previewRecipeDraftV1(json, request, facts, states)
        val digest = requireNotNull(review.approvalBasisDigest)
        val key = "unsigned-legacy-checkpoint"
        db.foodDao().insertOperation(OperationEntity("RECIPE_DRAFT_CONSENT_V1:$key",
            "RECIPE_DRAFT_CONSENT_V1", WriteRules.fingerprint("RECIPE_DRAFT_CONSENT_V1", key, digest),
            now, digest))
        store = FoodStore(db, { now }, verifier)
        val before = db.foodDao().operationCount()
        assertNull(store.recordedRecipeDraftV1ConsentKey(digest))
        rejected { store.saveReviewedRecipeDraftV1(key, json, request, facts, states, review, true) }
        rejected { consent("unsigned-new", review) }
        assertEquals(before, db.foodDao().operationCount())
        assertTrue(db.foodDao().observeRecipes().first().isEmpty())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun actualSignedConsentIsReadAfterStoreRestartAndCanStillSaveExactlyOnce() = runBlocking {
        val review = store.previewRecipeDraftV1(json, request, facts, states)
        val digest = requireNotNull(review.approvalBasisDigest)
        val key = "signed-resume"
        val proof = sign(key, digest, store.beginRecipeDraftCredentialAction(key, digest))
        consent(key, review, proof)
        store = FoodStore(db, { now }, verifier)
        assertEquals(key, store.recordedRecipeDraftV1ConsentKey(digest))
        val version = store.saveReviewedRecipeDraftV1(key, json, request, facts, states, review, true)
        assertEquals(version, store.saveReviewedRecipeDraftV1(key, json, request, facts, states, review, true))
        assertEquals(1, db.foodDao().observeRecipes().first().size)
        assertEquals(0, db.foodDao().consumptionCount())
    }
}
