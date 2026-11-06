package de.ernaehrung.demo

import de.ernaehrung.demo.data.backup.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BackupCodecTest {
    private val password get() = "synthetic-backup-password".toCharArray()
    private fun archive() = BackupArchive("de.ernaehrung.demo", 1_800_000_000_000, 13, "synthetic-schema", true,
        listOf(BackupTable("observations", listOf("id", "unknown", "cents", "zero", "negativeZero", "text", "binary"), listOf(
            listOf(BackupCell.Text("original-version"), BackupCell.Unknown, BackupCell.Integer(Long.MAX_VALUE),
                BackupCell.Integer(0), BackupCell.Real((-0.0).toRawBits()), BackupCell.Text("Taille · Öl · 🥗\nOriginaltext"),
                BackupCell.Blob(byteArrayOf(0, 1, -1)))))))

    @Test fun typedArchivePreservesLongCentsNullRealBitsUnicodeOriginalIdentitiesAndBlobBytes() {
        val original = archive()
        val encoded = BackupArchiveCodec.encode(original)
        val copy = BackupArchiveCodec.decode(encoded)
        assertEquals(original, copy)
        assertEquals(encoded.toList(), BackupArchiveCodec.encode(copy).toList())
        val values = copy.tables.single().rows.single()
        assertNotEquals(values[1], values[3]); assertEquals(BackupCell.Integer(Long.MAX_VALUE), values[2])
        assertEquals((-0.0).toRawBits(), (values[4] as BackupCell.Real).bits)
        assertEquals(64, BackupArchiveCodec.digest(encoded).length)
    }

    @Test fun blobCannotBeChangedThroughEitherTheInputOrReturnedByteArray() {
        val bytes = byteArrayOf(1, 2, 3); val cell = BackupCell.Blob(bytes)
        bytes.fill(0); cell.bytes().fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), cell.bytes())
    }

    @Test fun malformedUnsupportedTruncatedDuplicateAndTrailingArchivesFailWithoutTypeCoercion() {
        val valid = BackupArchiveCodec.encode(archive())
        assertTrue(runCatching { BackupArchiveCodec.decode(valid + byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { BackupArchiveCodec.decode(valid.copyOf(valid.size - 1)) }.isFailure)
        val future = valid.copyOf(); ByteBuffer.wrap(future).putInt(999)
        assertTrue(runCatching { BackupArchiveCodec.decode(future) }.isFailure)
        val forgedLength = valid.copyOf(); ByteBuffer.wrap(forgedLength).putInt(4, Int.MAX_VALUE)
        assertTrue(runCatching { BackupArchiveCodec.decode(forgedLength) }.isFailure)
        assertTrue(runCatching { BackupArchiveCodec.encode(archive().copy(tables = archive().tables + archive().tables)) }.isFailure)
        assertTrue(runCatching { BackupArchiveCodec.encode(archive().copy(applicationId = "\uD800")) }.isFailure)
    }

    @Test fun authenticatedEnvelopeMatchesIndependentPythonPbkdf2AndAesGcmKnownAnswer() {
        // Independently generated using Python hashlib.pbkdf2_hmac + cryptography AESGCM.
        var counter = 0
        val crypto = BackupEncryption { bytes -> bytes.indices.forEach { bytes[it] = (counter++).toByte() } }
        val plain = "Nur synthetische Werte: null ≠ 0; 123 Cent.".toByteArray(Charsets.UTF_8)
        val golden = "4547424b5030303100000001000927c0000102030405060708090a0b0c0d0e0f101112131415161718191a1b244a496ad3778375221fc1d0d8bef759c6ea41e3079d257fab2d83f8208d8a7094717845a36fb99f524021121ca0d1a3fcc0e9b2f56639b1eb1422951c"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(golden, crypto.seal(plain, password))
        assertArrayEquals(plain, BackupEncryption().open(golden, password))
        // UTF-8 password portability must also cover umlauts and supplementary code points.
        val unicode = "4547424b5030303100000001000927c0000102030405060708090a0b0c0d0e0f101112131415161718191a1b8e00669049c1e26f30087ae7678ea2f45b560eade30e458a16290c283161fa3e1072a7ebd89d30dd6edbfd146e12574d6daf003197ca0216ce5419b688"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        counter = 0
        assertArrayEquals(unicode, crypto.seal(plain, "synthetisch-äöü🔐-backup".toCharArray()))
        assertArrayEquals(plain, crypto.open(unicode, "synthetisch-äöü🔐-backup".toCharArray()))
    }

    @Test fun freshEnvelopeUsesNewSaltNonceAndAuthenticatesAllBytesAndPasswordBeforeDecoding() {
        val plain = BackupArchiveCodec.encode(archive()); val crypto = BackupEncryption()
        val first = crypto.seal(plain, password); val second = crypto.seal(plain, password)
        assertFalse(first.contentEquals(second)); assertArrayEquals(plain, crypto.open(first, password))
        assertEquals(archive(), BackupArchiveCodec.decode(crypto.open(second, password)))
        assertTrue(runCatching { crypto.open(first, "wrong-backup-password".toCharArray()) }.isFailure)
        listOf(0, 8, 12, 16, 32, first.size - 1).forEach { position ->
            val modified = first.copyOf(); modified[position] = (modified[position].toInt() xor 1).toByte()
            assertTrue("Tampered byte $position", runCatching { crypto.open(modified, password) }.isFailure)
        }
        assertTrue(runCatching { crypto.open(first.copyOf(first.size - 1), password) }.isFailure)
        assertTrue(runCatching { crypto.seal(plain, "short".toCharArray()) }.isFailure)
    }
}
