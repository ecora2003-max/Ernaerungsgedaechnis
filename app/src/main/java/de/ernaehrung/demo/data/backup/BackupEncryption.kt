package de.ernaehrung.demo.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable, password-encrypted, authenticated envelope. No device key is exported.
 * Version 1 fixes PBKDF2-HMAC-SHA256/600000 and AES-256-GCM, salt 16, nonce 12, tag 16.
 * Header and KDF settings are authenticated; unknown versions are never reinterpreted.
 */
internal class BackupEncryption(private val entropy: (ByteArray) -> Unit = SecureRandom()::nextBytes) {
    companion object {
        private val MAGIC = "EGBKP001".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val ITERATIONS = 600_000
        private const val HEADER_BYTES = 8 + 4 + 4 + 16 + 12
        const val MAX_BYTES = BackupArchiveCodec.MAX_BYTES + HEADER_BYTES + 16
    }

    fun seal(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size in 1..BackupArchiveCodec.MAX_BYTES)
        requirePassword(password)
        val salt = ByteArray(16).also(entropy); val nonce = ByteArray(12).also(entropy)
        val header = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
            it.write(MAGIC); it.writeInt(VERSION); it.writeInt(ITERATIONS); it.write(salt); it.write(nonce)
        } }.toByteArray()
        return header + crypt(Cipher.ENCRYPT_MODE, payload, password, salt, nonce, header)
    }

    fun open(envelope: ByteArray, password: CharArray): ByteArray {
        requirePassword(password)
        require(envelope.size in (HEADER_BYTES + 17)..MAX_BYTES) { "Datei ist keine vollständige unterstützte Sicherung." }
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val input = DataInputStream(ByteArrayInputStream(header))
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC) && input.readInt() == VERSION) { "Nicht unterstütztes verschlüsseltes Sicherungsformat." }
        require(input.readInt() == ITERATIONS) { "Nicht unterstützte Schlüsselableitung." }
        val salt = ByteArray(16).also(input::readFully); val nonce = ByteArray(12).also(input::readFully)
        return crypt(Cipher.DECRYPT_MODE, envelope.copyOfRange(HEADER_BYTES, envelope.size), password, salt, nonce, header)
    }

    private fun requirePassword(password: CharArray) {
        require(password.size in 12..1024) { "Sicherungspasswort mit mindestens 12 und höchstens 1024 Zeichen verwenden." }
    }
    private fun crypt(mode: Int, bytes: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return cipher.doFinal(bytes)
        } finally { key.fill(0) }
    }
}
