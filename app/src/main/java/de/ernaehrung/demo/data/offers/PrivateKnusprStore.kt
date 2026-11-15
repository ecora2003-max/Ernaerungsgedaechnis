package de.ernaehrung.demo.data.offers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import de.eg.recipe.JsonOutput
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dedicated no-backup, authenticated encrypted control data. Never part of
 * Room food/health archives, SavedState, telemetry, source fixtures or Git. */
internal class PrivateKnusprStore private constructor(context: Context) {
    companion object {
        @Volatile private var shared: PrivateKnusprStore? = null
        fun forApp(context: Context): PrivateKnusprStore = shared ?: synchronized(this) {
            shared ?: PrivateKnusprStore(context.applicationContext).also { shared = it }
        }
    }
    private val file = AtomicFile(File(context.noBackupFilesDir, "knuspr-oauth-v1.bin"))
    private val alias = "eg_knuspr_oauth_private_v1"
    private var attemptGeneration = 0L
    private var activeRenewal: String? = null
    data class State(val pending: KnusprPendingAuthorization? = null, val token: KnusprToken? = null, val completing: String? = null) {
        override fun toString() = "PrivateKnusprState(redacted)"
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun read(): State {
        if (!file.baseFile.exists()) return State()
        val raw = file.readFully(); require(raw.size in 29..131072)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        cipher.updateAAD("EG_KNUSPR_OAUTH_1".toByteArray())
        val clear = cipher.doFinal(raw, 12, raw.size - 12)
        return try { decode(OfferJson.read(clear.toString(Charsets.UTF_8))) } finally { clear.fill(0); raw.fill(0) }
    }
    @Synchronized fun write(state: State) {
        val plain = JsonOutput.encode(encode(state)).toByteArray(Charsets.UTF_8)
        require(plain.size <= 65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD("EG_KNUSPR_OAUTH_1".toByteArray())
        val sealed = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        var out: java.io.FileOutputStream? = null
        try { out = file.startWrite(); out.write(sealed); file.finishWrite(out) }
        catch (failure: Exception) { out?.let(file::failWrite); throw failure }
        finally { sealed.fill(0) }
    }
    @Synchronized fun clear() { attemptGeneration++; activeRenewal = null; file.delete() }
    @Synchronized fun beginAttempt(): Long { clear(); return attemptGeneration }
    @Synchronized fun stage(generation: Long, pending: KnusprPendingAuthorization): Boolean {
        if (generation != attemptGeneration) return false
        write(State(pending = pending)); return true
    }
    @Synchronized fun cancelAttempt(generation: Long) { if (generation == attemptGeneration) clear() }
    @Synchronized fun claim(state: String): KnusprPendingAuthorization {
        val current = read(); val pending = requireNotNull(current.pending) { "No live OAuth attempt" }
        require(java.security.MessageDigest.isEqual(pending.state.toByteArray(), state.toByteArray()))
        write(current.copy(pending = null, completing = pending.state))
        return pending
    }
    @Synchronized fun finish(state: String, token: KnusprToken): Boolean {
        val current = read()
        if (current.completing != state || current.token != null || current.pending != null) return false
        write(State(token = token)); return true
    }
    class Renewal internal constructor(val generation: Long, val nonce: String, val previous: KnusprToken) {
        override fun toString() = "PrivateKnusprRenewal(redacted)"
    }
    private fun matches(claim: Renewal, current: State): Boolean = claim.generation == attemptGeneration &&
        activeRenewal == claim.nonce && current.completing == claim.nonce && current.pending == null && current.token == claim.previous
    @Synchronized fun claimRenewal(): Renewal {
        require(activeRenewal == null) { "Renewal already running" }
        val current = read()
        require(current.pending == null)
        val previous = requireNotNull(current.token)
        require(!previous.refresh.isNullOrBlank())
        val nonce = java.util.UUID.randomUUID().toString()
        val claim = Renewal(++attemptGeneration, nonce, previous)
        // An abandoned previous-process marker can be replaced only by this
        // new explicit action. A live process permits one renewal at a time.
        write(State(token = previous, completing = nonce))
        activeRenewal = nonce
        return claim
    }
    @Synchronized fun finishRenewal(claim: Renewal, token: KnusprToken, stillCurrent: () -> Boolean = { true }): Boolean {
        val current = read()
        if (!matches(claim, current) || !stillCurrent()) return false
        require(token.clientId == claim.previous.clientId && token.server == claim.previous.server)
        write(State(token = token)); activeRenewal = null
        return true
    }
    @Synchronized fun rejectRenewal(claim: Renewal): Boolean {
        if (!matches(claim, read())) return false
        clear(); return true
    }
    @Synchronized fun abortRenewal(claim: Renewal) {
        if (activeRenewal != claim.nonce) return
        try {
            val current = read()
            if (matches(claim, current))
                write(State(token = claim.previous))
        } finally { activeRenewal = null }
    }
    private fun server(s: KnusprAuthorizationServer) = mapOf("issuer" to s.issuer.toString(),
        "authorization" to s.authorization.toString(), "token" to s.token.toString(), "registration" to s.registration?.toString(),
        "scopes" to s.scopes, "resource" to s.resource.toString())
    private fun encode(state: State): Map<String, Any?> = mapOf("format" to "eg-knuspr-oauth-1", "completing" to state.completing,
        "pending" to state.pending?.let { mapOf("server" to server(it.server), "clientId" to it.clientId, "state" to it.state,
            "verifier" to it.verifier, "redirect" to it.redirect, "createdAt" to it.createdAt) },
        "token" to state.token?.let { mapOf("server" to server(it.server), "clientId" to it.clientId,
            "access" to it.access, "refresh" to it.refresh, "expiresAt" to it.expiresAt) })
    private fun decodeServer(v: Any?): KnusprAuthorizationServer {
        val m = requireNotNull(OfferJson.objectValue(v))
        fun uri(key: String) = URI(m.getValue(key) as String).also(::requirePublicHttps)
        return KnusprAuthorizationServer(uri("issuer"), uri("authorization"), uri("token"),
            (m["registration"] as? String)?.let(::URI)?.also(::requirePublicHttps),
            (m["scopes"] as List<*>).map { it as String }, uri("resource"))
    }
    private fun decode(m: Map<String, Any?>): State {
        require(m["format"] == "eg-knuspr-oauth-1")
        val pending = OfferJson.objectValue(m["pending"])?.let { p -> KnusprPendingAuthorization(decodeServer(p["server"]),
            p["clientId"] as String, p["state"] as String, p["verifier"] as String, p["redirect"] as String, requireNotNull(OfferJson.integer(p["createdAt"]))) }
        val token = OfferJson.objectValue(m["token"])?.let { t -> KnusprToken(t["access"] as String, t["refresh"] as? String,
            OfferJson.integer(t["expiresAt"]), t["clientId"] as String, decodeServer(t["server"])) }
        return State(pending, token, m["completing"] as? String)
    }
}
