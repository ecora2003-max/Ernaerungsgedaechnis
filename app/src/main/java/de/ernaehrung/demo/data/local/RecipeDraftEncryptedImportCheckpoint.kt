package de.ernaehrung.demo.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional, unconfirmed JSON-only checkpoint. Never stores independently checked
 * catalog, state, allergen or consent evidence. A restored draft must pass the
 * entire import, host/Room revalidation and a NEW explicit gesture.
 */
internal interface RecipeDraftEncryptedImportCheckpoint {
    fun save(json: String)
    fun load(): String?
    fun discard()
}

/**
 * AndroidKeyStore AES-GCM with authenticated timestamp and a bounded AtomicFile
 * in noBackupFilesDir. No cloud backup, plaintext fallback, booking or auto-save.
 * Keystore failure is fail-closed; this is device-at-rest protection, NOT
 * authenticated user identity or protection against an unlocked compromised app.
 */
internal class PrivateRecipeDraftEncryptedImportCheckpoint(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val keyProvider: () -> SecretKey = { androidKey() },
    fileName: String = "recipe-draft-encrypted-import-v1"
) : RecipeDraftEncryptedImportCheckpoint {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir,
        fileName))

    override fun save(json: String) = synchronized(lock) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES) {
            "Rezeptentwurf ist leer oder überschreitet die sichere Speichergrenze."
        }
        val time = now()
        require(time >= 0) { "Ungültiger Zeitpunkt." }
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putLong(time).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unerwartete GCM-Nonce." }
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(bytes)
        val payload = header + iv + ciphertext
        check(payload.size <= MAX_FILE_BYTES)
        val stream = file.startWrite()
        try {
            stream.write(payload)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    override fun load(): String? = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return@synchronized null
        try {
            val payload = file.openRead().use { stream ->
                val buffer = ByteArray(MAX_FILE_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val count = stream.read(buffer, size, buffer.size - size)
                    if (count <= 0) break
                    size += count
                }
                if (size > MAX_FILE_BYTES) return@synchronized null
                buffer.copyOf(size)
            }
            if (payload.size < HEADER_BYTES + IV_BYTES + TAG_BYTES) return@synchronized null
            val header = payload.copyOfRange(0, HEADER_BYTES)
            if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
                return@synchronized null
            val issuedAt = ByteBuffer.wrap(header, MAGIC.size, 8).long
            val time = now()
            if (issuedAt < 0 || time < issuedAt) return@synchronized null
            val iv = payload.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
            val ciphertext = payload.copyOfRange(HEADER_BYTES + IV_BYTES, payload.size)
            if (ciphertext.size > MAX_PLAINTEXT_BYTES + TAG_BYTES)
                return@synchronized null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val plain = cipher.doFinal(ciphertext)
            if (plain.isEmpty() || plain.size > MAX_PLAINTEXT_BYTES) return@synchronized null
            // The timestamp is untrusted until GCM authenticates the header as AAD.
            // A forged old timestamp must never delete a still-valid checkpoint.
            if (time - issuedAt > MAX_AGE_MS) {
                file.delete()
                return@synchronized null
            }
            String(plain, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // Includes IO, missing/invalidated Keystore key, and GCM tag failures.
            null
        }
    }

    override fun discard() = synchronized(lock) { file.delete() }

    internal companion object {
        val lock = Any()
        val MAGIC = byteArrayOf(0x52, 0x44, 0x49, 0x31) // RDI1
        const val HEADER_BYTES = 12
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_PLAINTEXT_BYTES = 262_144
        const val MAX_FILE_BYTES = HEADER_BYTES + IV_BYTES + TAG_BYTES + MAX_PLAINTEXT_BYTES
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val KEY_ALIAS = "eg.recipe.unconfirmed.import.v1"

        fun androidKey(alias: String = KEY_ALIAS): SecretKey = synchronized(lock) {
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            val existing = store.getKey(alias, null) as? SecretKey
            if (existing != null) return@synchronized existing
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generator.generateKey()
        }
    }
}
