package de.ernaehrung.demo.data.offers

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

internal data class OfferArticleProof(val challengeId: String, val digest: String, val nonce: String,
    val issuedAt: Long, val signature: String)
internal fun interface OfferArticleProofVerifier { fun verify(proof: OfferArticleProof, now: Long): Boolean }

/** Independent domain/key: an article review cannot authorize a recipe, an
 * actual purchase, manufacturing or consumption. Private key never exported. */
internal object AndroidOfferArticleProof : OfferArticleProofVerifier {
    private const val ALIAS = "eg_offer_article_review_v1"
    private fun bytes(proof: OfferArticleProof): ByteArray {
        require(UUID.fromString(proof.challengeId).toString() == proof.challengeId)
        require(UUID.fromString(proof.nonce).toString() == proof.nonce)
        require(proof.digest.matches(Regex("[0-9a-f]{64}")))
        return ByteArrayOutputStream().also { output -> DataOutputStream(output).use {
            it.writeUTF("EG_OFFER_ARTICLE_REVIEW_V1"); it.writeUTF(proof.challengeId)
            it.writeUTF(proof.digest); it.writeUTF(proof.nonce); it.writeLong(proof.issuedAt)
        } }.toByteArray()
    }
    fun ensureKey(): Boolean = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL)
            else { @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(30) }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(spec.build()) }.generateKeyPair()
        }
        true
    }.getOrDefault(false)
    fun sign(id: String, digest: String, nonce: String, now: Long): OfferArticleProof? = runCatching {
        val unsigned = OfferArticleProof(id, digest, nonce, now, "")
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes(unsigned)); sign() }
        unsigned.copy(signature = Base64.getEncoder().encodeToString(signature))
    }.getOrNull()
    override fun verify(proof: OfferArticleProof, now: Long): Boolean = runCatching {
        require(proof.issuedAt >= 0 && now >= proof.issuedAt && now - proof.issuedAt <= 30_000)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getCertificate(ALIAS).publicKey
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(bytes(proof)); verify(Base64.getDecoder().decode(proof.signature))
        }
    }.getOrDefault(false)
}
