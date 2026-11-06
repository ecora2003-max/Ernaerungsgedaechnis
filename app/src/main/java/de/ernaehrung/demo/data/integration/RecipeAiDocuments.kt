package de.ernaehrung.demo.data.integration

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Read only the document explicitly chosen in SAF; never retain a URI grant. */
internal object RecipeAiDocuments {
    const val MAX_BYTES = 262_144
    fun read(resolver: ContentResolver, uri: Uri): String {
        require(uri.scheme == "content") { "Antwort über den Android-Dateidialog auswählen." }
        return requireNotNull(resolver.openInputStream(uri)) { "Antwortdatei nicht lesbar." }.use(::readUtf8)
    }
    fun readUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "Antwortdatei zu groß (maximal 262144 Byte)." }
            output.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
            .removePrefix("\uFEFF")
    }
}
