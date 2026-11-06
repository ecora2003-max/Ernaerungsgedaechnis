package de.ernaehrung.demo.data.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Only URIs chosen through Android's document contracts. No stored URI grant,
 * filesystem path, network client or automatic cloud destination.
 */
internal interface BackupDocuments {
    fun read(uri: Uri): ByteArray
    fun write(uri: Uri, encrypted: ByteArray)
}
internal class AndroidBackupDocuments(private val resolver: ContentResolver) : BackupDocuments {
    override fun read(uri: Uri): ByteArray {
        require(uri.scheme == "content") { "Datei über den Android-Dateidialog auswählen." }
        return requireNotNull(resolver.openInputStream(uri)) { "Sicherungsdatei nicht lesbar." }.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= BackupEncryption.MAX_BYTES) { "Sicherungsdatei überschreitet die unterstützte Größe." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    override fun write(uri: Uri, encrypted: ByteArray) {
        require(uri.scheme == "content") { "Speicherort über den Android-Dateidialog auswählen." }
        require(encrypted.isNotEmpty() && encrypted.size <= BackupEncryption.MAX_BYTES)
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "Sicherungsdatei nicht beschreibbar." }.use {
            it.write(encrypted); it.flush()
        }
    }
}
