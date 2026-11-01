package de.ernaehrung.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.PrivateRecipeDraftEncryptedImportCheckpoint
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM crypto checks use a test-only JCE key; production uses AndroidKeyStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeDraftEncryptedImportCheckpointTest {
    private val keyA = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val keyB = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES")
    private val json = """{"messageType":"recipe.draft","title":"PRIVATE INGREDIENT SOY"}"""

    @Test fun explicitSaveIsEncryptedAndCanResumeButNeverBecomesConsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateRecipeDraftEncryptedImportCheckpoint(context, { 1_000_000L }, { keyA })
        val file = File(context.noBackupFilesDir, "recipe-draft-encrypted-import-v1")
        store.discard()
        try {
            assertNull(store.load())
            store.save(json)
            assertTrue(file.exists())
            assertTrue(file.length() <= 262_184L)
            val raw = file.readBytes()
            assertFalse(String(raw, Charsets.ISO_8859_1).contains("PRIVATE INGREDIENT"))
            assertFalse(String(raw, Charsets.ISO_8859_1).contains("recipe.draft"))
            assertEquals(json, PrivateRecipeDraftEncryptedImportCheckpoint(
                context, { 1_000_000L }, { keyA }).load())
            assertNull(PrivateRecipeDraftEncryptedImportCheckpoint(
                context, { 1_000_000L }, { keyB }).load())
            assertEquals(json, store.load()) // failed decrypt must not destroy a valid file
        } finally {
            store.discard()
        }
        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test fun authenticatedTimestampRejectsTamperRollbackAndExpiry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var time = 10_000_000L
        val store = PrivateRecipeDraftEncryptedImportCheckpoint(context, { time }, { keyA })
        val file = File(context.noBackupFilesDir, "recipe-draft-encrypted-import-v1")
        store.discard()
        try {
            store.save(json)
            time = 9_999_999L
            assertNull(store.load()) // Clock rollback fails closed without deleting evidence.
            time = 10_000_000L
            assertEquals(json, store.load())
            val original = file.readBytes()
            val timestampTampered = original.clone()
            timestampTampered[11] = (timestampTampered[11].toInt() xor 1).toByte()
            file.writeBytes(timestampTampered)
            assertNull(store.load())
            file.writeBytes(original)
            assertEquals(json, store.load())
            val ciphertextTampered = original.clone()
            ciphertextTampered[ciphertextTampered.lastIndex] =
                (ciphertextTampered.last().toInt() xor 1).toByte()
            file.writeBytes(ciphertextTampered)
            assertNull(store.load())
            file.writeBytes(original)

            time += 24L * 60 * 60 * 1000
            assertEquals(json, store.load()) // Inclusive 24-hour boundary.
            time++
            assertNull(store.load())
            assertFalse("Expired ciphertext must be deleted, not just hidden", file.exists())
            time = 10_000_000L
            assertNull(store.load()) // Rewinding the clock cannot resurrect an expired draft.
        } finally {
            store.discard()
        }
    }

    @Test fun oversizeCorruptionAndInvalidSaveFailClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateRecipeDraftEncryptedImportCheckpoint(context, { 1_000L }, { keyA })
        val file = File(context.noBackupFilesDir, "recipe-draft-encrypted-import-v1")
        store.discard()
        try {
            file.writeBytes(ByteArray(1_000_000) { 42 })
            assertNull(store.load())
            try {
                store.save("x".repeat(262_145))
                fail("Oversize draft must not be persisted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1_000_000L, file.length())
            store.save(json)
            assertEquals(json, store.load())
            try {
                store.save("")
                fail("Empty draft must not be persisted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(json, store.load())
        } finally {
            store.discard()
        }
    }

    @Test fun keystoreFailureHasNoPlaintextFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "recipe-draft-encrypted-import-v1")
        val store = PrivateRecipeDraftEncryptedImportCheckpoint(context, { 1_000L }) {
            throw IllegalStateException("Key unavailable")
        }
        store.discard()
        try {
            try {
                store.save(json)
                fail("Unavailable encryption key must fail closed")
            } catch (_: IllegalStateException) { }
            assertFalse(file.exists())
            assertNull(store.load())
        } finally {
            store.discard()
        }
    }
}
