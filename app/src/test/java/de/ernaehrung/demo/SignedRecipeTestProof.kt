package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.LocalDataViewModel
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Collections
import java.util.WeakHashMap

/** Synthetic JCA action signer for host tests, never an Android credential claim.
 * Production code has no unsigned-consent/test-mode escape hatch.
 */
internal object SignedRecipeTestProof {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private class Clock { @Volatile var lastSample = 0L }
    private val clocks = Collections.synchronizedMap(WeakHashMap<FoodStore, Clock>())
    private val stores = Collections.synchronizedMap(WeakHashMap<LocalDataViewModel, FoodStore>())
    private val verifier = RecipeActionProofVerifier { proof, key, digest, at ->
        RecipeActionProofPayload.fresh(proof, key, digest, at) && runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyPair.public)
                update(RecipeActionProofPayload.bytes(proof))
                verify(Base64.getDecoder().decode(proof.signatureBase64))
            }
        }.getOrDefault(false)
    }

    fun store(database: FoodDatabase, now: () -> Long): FoodStore {
        val clock = Clock()
        return FoodStore(database, { now().also { clock.lastSample = it } }, verifier)
            .also { clocks[it] = clock }
    }

    fun model(store: FoodStore,
        pendingKeyStore: RecipeDraftPendingKeyStore = EphemeralRecipeDraftPendingKeyStore
    ) = LocalDataViewModel(store, pendingKeyStore = pendingKeyStore).also { stores[it] = store }

    private fun sign(store: FoodStore, key: String, digest: String, nonce: String): RecipeActionProof {
        val proof = RecipeActionProof(key, digest, requireNotNull(clocks[store]).lastSample, nonce, "")
        val bytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(RecipeActionProofPayload.bytes(proof))
            sign()
        }
        return proof.copy(signatureBase64 = Base64.getEncoder().encodeToString(bytes))
    }

    fun issue(store: FoodStore, key: String, digest: String): RecipeActionProof =
        sign(store, key, digest, store.beginRecipeDraftCredentialAction(key, digest))

    fun issue(model: LocalDataViewModel, digest: String): RecipeActionProof? {
        val key = model.recipeDraftConsentState.value.operationKey ?: return null
        val nonce = model.beginRecipeDraftCredentialAction(digest) ?: return null
        return sign(requireNotNull(stores[model]), key, digest, nonce)
    }
}

internal fun signedRecipeStore(db: FoodDatabase, now: () -> Long) = SignedRecipeTestProof.store(db, now)
internal fun signedRecipeModel(store: FoodStore,
    pendingKeyStore: RecipeDraftPendingKeyStore = EphemeralRecipeDraftPendingKeyStore
) = SignedRecipeTestProof.model(store, pendingKeyStore)

internal fun LocalDataViewModel.confirmSignedRecipeTestConsent(digest: String, session: String?) =
    confirmRecipeDraftConsent(digest, session, SignedRecipeTestProof.issue(this, digest))

internal suspend fun FoodStore.recordSignedRecipeTestConsent(
    key: String, json: String, request: Gate.Request,
    confirmations: Map<String, RecipeCatalogEvidence.ConfirmedLine>,
    states: Map<String, Gate.State>, expected: Gate.Review, confirmed: Boolean,
    context: Gate.Context<*, *, *>? = null,
    trustedContextSnapshotAt: OffsetDateTime? = null
): String {
    val proof = expected.approvalBasisDigest?.takeIf { confirmed }?.let {
        SignedRecipeTestProof.issue(this, key, it)
    }
    return recordReviewedRecipeDraftV1Consent(key, json, request, confirmations,
        states, expected, confirmed, context, trustedContextSnapshotAt, proof)
}
