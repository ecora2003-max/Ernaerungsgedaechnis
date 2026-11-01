package de.ernaehrung.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.PrivateRecipeDraftPendingKeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The persisted token is never a consent record, a draft or an authenticated user action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeDraftPendingKeyStoreTest {
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    @Test fun opaqueKeyOnlyAndExplicitDiscard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var time = 1_000_000L
        val first = PrivateRecipeDraftPendingKeyStore(context) { time }
        first.discard()
        try {
            val key = first.resumeOrCreate(digestA)
            assertEquals(key, PrivateRecipeDraftPendingKeyStore(context) { time }
                .resumeOrCreate(digestA))
            val file = File(context.noBackupFilesDir, "recipe-draft-pending-key-v1")
            val content = file.readText()
            assertTrue(content.contains(digestA))
            assertTrue(content.contains(key))
            assertFalse(content.contains("recipe.draft"))
            assertFalse(content.contains("ALLERGEN"))
            assertFalse(content.contains("ingredients"))
            assertTrue(content.length <= 256)

            // A stale completion from a previous preview must not clear a new one.
            val other = first.resumeOrCreate(digestB)
            assertNotEquals(key, other)
            first.finish(digestA, key)
            assertEquals(other, first.resumeOrCreate(digestB))
            first.finish(digestB, other)
            assertFalse(file.exists())
            assertNotEquals(other, first.resumeOrCreate(digestB))
        } finally {
            first.discard()
        }
    }

    @Test fun expiryClockRollbackAndInvalidDigestNeverAuthorizeReuse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var time = 10_000_000L
        val store = PrivateRecipeDraftPendingKeyStore(context) { time }
        store.discard()
        try {
            val original = store.resumeOrCreate(digestA)
            time += 24L * 60 * 60 * 1000
            assertEquals(original, store.resumeOrCreate(digestA))
            time += 1
            val expired = store.resumeOrCreate(digestA)
            assertNotEquals(original, expired)
            time -= 10
            assertNotEquals(expired, store.resumeOrCreate(digestA))
            try {
                store.resumeOrCreate("not-a-host-digest")
                fail("Malformed digest must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected, no consent or recipe write.
            }
        } finally {
            store.discard()
        }
    }
}
