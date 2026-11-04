package de.ernaehrung.demo.data.local

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * Ephemeral OS-credential-backed signature, never imported from a recipe draft.
 * The signature is checked by FoodStore against the app's Android Keystore public key.
 * A signed action is not a general-purpose identity certificate or proof of user intent.
 */
data class RecipeActionProof(
    val operationKey: String,
    val approvalDigest: String,
    val issuedAtUtcMillis: Long,
    val nonce: String,
    val signatureBase64: String
)

internal object RecipeActionProofPayload {
    const val MAX_AGE_MS = 30_000L

    fun bytes(proof: RecipeActionProof): ByteArray {
        require(Regex("[0-9a-f]{64}").matches(proof.approvalDigest))
        require(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}").matches(proof.operationKey))
        require(runCatching { UUID.fromString(proof.nonce) }.isSuccess)
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeUTF("EG_RECIPE_CREDENTIAL_ACTION_V1")
                data.writeUTF(proof.operationKey)
                data.writeUTF(proof.approvalDigest)
                data.writeLong(proof.issuedAtUtcMillis)
                data.writeUTF(proof.nonce)
            }
        }.toByteArray()
    }

    fun fresh(proof: RecipeActionProof, key: String, digest: String, now: Long): Boolean =
        proof.operationKey == key && proof.approvalDigest == digest &&
            proof.issuedAtUtcMillis >= 0L && now >= proof.issuedAtUtcMillis &&
            now - proof.issuedAtUtcMillis <= MAX_AGE_MS
}

fun interface RecipeActionProofVerifier {
    fun verify(proof: RecipeActionProof, key: String, digest: String, now: Long): Boolean
}

/** Auth-bound private key is non-exportable. Device credential is required to sign. */
/**
 * In-memory challenge only. A process restart discards it; cancellation/retry
 * replaces the nonce, and consumption is one-shot even if the DB write fails.
 */
internal class RecipeActionNonceGate {
    private data class Pending(val digest: String, val nonce: String, val issuedAt: Long)
    private val pending = mutableMapOf<String, Pending>()

    @Synchronized fun issue(key: String, digest: String, now: Long): String {
        require(now >= 0L)
        RecipeActionProofPayload.bytes(RecipeActionProof(key, digest, now,
            UUID.randomUUID().toString(), ""))
        val nonce = UUID.randomUUID().toString()
        pending[key] = Pending(digest, nonce, now)
        return nonce
    }

    @Synchronized fun consume(proof: RecipeActionProof, key: String, digest: String,
        now: Long): Boolean {
        val challenge = pending.remove(key) ?: return false
        return challenge.digest == digest && challenge.nonce == proof.nonce &&
            now >= challenge.issuedAt && now - challenge.issuedAt <= 300_000L
    }
}

internal object AndroidRecipeActionProof : RecipeActionProofVerifier {
    private const val ALIAS = "eg_recipe_credential_action_v1"

    fun ensureKey(): Boolean = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val builder = KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(30)
            }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(builder.build()) }.generateKeyPair()
        }
        true
    }.getOrDefault(false)

    /** Call only after the system credential Activity returned RESULT_OK. */
    fun sign(key: String, digest: String, nonce: String, now: Long): RecipeActionProof? = runCatching {
        val unsigned = RecipeActionProof(key, digest, now, nonce, "")
        val message = RecipeActionProofPayload.bytes(unsigned)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = requireNotNull(store.getKey(ALIAS, null) as? java.security.PrivateKey)
        val signed = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message)
            sign()
        }
        unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(signed))
    }.getOrNull()

    override fun verify(proof: RecipeActionProof, key: String, digest: String, now: Long): Boolean {
        if (!RecipeActionProofPayload.fresh(proof, key, digest, now)) return false
        return runCatching {
            val message = RecipeActionProofPayload.bytes(proof)
            val signature = Base64.getDecoder().decode(proof.signatureBase64)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val publicKey = requireNotNull(store.getCertificate(ALIAS)?.publicKey)
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}
