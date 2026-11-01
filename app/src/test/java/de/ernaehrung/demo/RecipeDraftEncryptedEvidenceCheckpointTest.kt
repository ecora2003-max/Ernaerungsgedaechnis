package de.ernaehrung.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.PrivateRecipeDraftEncryptedEvidenceCheckpoint
import de.ernaehrung.demo.data.local.RecipeDraftEvidenceForm
import de.ernaehrung.demo.data.local.recipeDraftEvidenceDigest
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeDraftEncryptedEvidenceCheckpointTest {
    private val keyA = SecretKeySpec(ByteArray(32) { (it + 2).toByte() }, "AES")
    private val keyB = SecretKeySpec(ByteArray(32) { (it + 22).toByte() }, "AES")
    private val form = RecipeDraftEvidenceForm(
        recipeDraftEvidenceDigest("{private recipe}"),
        mapOf("rice" to "product-v2"),
        mapOf("rice" to "NET"),
        mapOf("rice" to "RAW"),
        mapOf(("rice" to "SOY") to null, ("rice" to "MILK") to false),
        "ALLERGEN:SOY, ALLERGEN:MILK"
    )

    @Test fun explicitEncryptedFormRoundtripNeverExposesEvidenceOrIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateRecipeDraftEncryptedEvidenceCheckpoint(context, { 1_000_000L }, { keyA })
        val file = File(context.noBackupFilesDir, "recipe-draft-encrypted-evidence-v1")
        store.discard()
        try {
            assertNull(store.load())
            store.save(form)
            assertTrue(file.exists())
            val bytes = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse(bytes.contains("product-v2"))
            assertFalse(bytes.contains("ALLERGEN:SOY"))
            assertFalse(bytes.contains("private recipe"))
            assertEquals(form, PrivateRecipeDraftEncryptedEvidenceCheckpoint(
                context, { 1_000_000L }, { keyA }).load())
            assertNull(PrivateRecipeDraftEncryptedEvidenceCheckpoint(
                context, { 1_000_000L }, { keyB }).load())
            assertEquals(form, store.load()) // Wrong key cannot destroy valid data.
        } finally {
            store.discard()
        }
        assertFalse(file.exists())
    }

    @Test fun ttlRollbackTamperAndExplicitRevocationFailClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var time = 5_000_000L
        val store = PrivateRecipeDraftEncryptedEvidenceCheckpoint(context, { time }, { keyA })
        val file = File(context.noBackupFilesDir, "recipe-draft-encrypted-evidence-v1")
        store.discard()
        try {
            store.save(form)
            val valid = file.readBytes()
            time--
            assertNull(store.load())
            time++
            assertEquals(form, store.load())
            val forged = valid.clone()
            forged[4] = (forged[4].toInt() xor 1).toByte()
            file.writeBytes(forged)
            assertNull(store.load())
            assertTrue(file.exists())
            file.writeBytes(valid)
            assertEquals(form, store.load())
            time += 24L * 60 * 60 * 1000 + 1
            assertNull(store.load())
            assertFalse(file.exists())
            time = 5_000_000L
            assertNull(store.load())
            store.save(form)
            store.discard()
            assertNull(store.load())
        } finally {
            store.discard()
        }
    }

    @Test fun malformedOrOversizedUnconfirmedFormNeverReplacesValidCheckpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateRecipeDraftEncryptedEvidenceCheckpoint(context, { 1_000L }, { keyA })
        store.discard()
        try {
            store.save(form)
            try {
                store.save(form.copy(draftHash = "not-a-digest"))
                fail("must reject unbound form")
            } catch (_: IllegalArgumentException) { }
            try {
                store.save(form.copy(restrictionsText = "x".repeat(1025)))
                fail("must reject oversized restrictions")
            } catch (_: IllegalArgumentException) { }
            try {
                store.save(form.copy(states = mapOf("rice" to "MODEL_CONFIRMED")))
                fail("must reject model assertion")
            } catch (_: IllegalArgumentException) { }
            assertEquals(form, store.load())
        } finally {
            store.discard()
        }
    }
}
