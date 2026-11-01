package de.ernaehrung.demo.data.local

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Resumes only the opaque operation key for an UNCONFIRMED recipe preview.
 *
 * No draft JSON, catalog facts, allergy answers, consent gesture or user identity
 * is persisted. After process death the user must reimport the same draft, supply
 * independent evidence, pass FoodStore's fresh Room revalidation and explicitly
 * confirm again. A checkpoint is never itself consent or authorization.
 */
internal interface RecipeDraftPendingKeyStore {
    fun resumeOrCreate(approvalDigest: String): String
    fun discard()
    fun finish(approvalDigest: String, operationKey: String)
}

/** Tests and callers without private Android storage intentionally do not resume. */
internal object EphemeralRecipeDraftPendingKeyStore : RecipeDraftPendingKeyStore {
    override fun resumeOrCreate(approvalDigest: String): String = UUID.randomUUID().toString()
    override fun discard() = Unit
    override fun finish(approvalDigest: String, operationKey: String) = Unit
}

/**
 * Crash-safe, app-private and excluded from Android Auto Backup. One outstanding
 * draft per app sandbox. AtomicFile protects replacement against torn writes;
 * no Room migration or second source of booking truth is introduced.
 */
internal class PrivateRecipeDraftPendingKeyStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis
) : RecipeDraftPendingKeyStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir,
        "recipe-draft-pending-key-v1"))
    private data class Entry(val digest: String, val key: String, val issuedAt: Long)

    override fun resumeOrCreate(approvalDigest: String): String = synchronized(lock) {
        require(DIGEST.matches(approvalDigest)) { "Ungültige Rezept-Freigabebasis." }
        val time = now()
        val previous = read()
        if (previous != null && previous.digest == approvalDigest &&
            time >= previous.issuedAt && time - previous.issuedAt <= MAX_AGE_MS) {
            return@synchronized previous.key
        }
        val key = UUID.randomUUID().toString()
        write(Entry(approvalDigest, key, time))
        key
    }

    override fun discard() = synchronized(lock) { file.delete() }

    override fun finish(approvalDigest: String, operationKey: String) = synchronized(lock) {
        val previous = read()
        if (previous?.digest == approvalDigest && previous.key == operationKey) file.delete()
    }

    private fun read(): Entry? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val content = file.openRead().bufferedReader(StandardCharsets.UTF_8).use {
            // A corrupted or oversized private file must never be treated as consent.
            it.readText().takeIf { text -> text.length <= 256 }
        } ?: return null
        val fields = content.trimEnd('\n').split('\n')
        if (fields.size != 4 || fields[0] != "v1" ||
            !DIGEST.matches(fields[1]) || !KEY.matches(fields[2])) return null
        val issued = fields[3].toLongOrNull() ?: return null
        if (issued < 0) return null
        return Entry(fields[1], fields[2], issued)
    }

    private fun write(entry: Entry) {
        val bytes = "v1\n${entry.digest}\n${entry.key}\n${entry.issuedAt}\n"
            .toByteArray(StandardCharsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        val lock = Any()
        val DIGEST = Regex("[0-9a-f]{64}")
        val KEY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
