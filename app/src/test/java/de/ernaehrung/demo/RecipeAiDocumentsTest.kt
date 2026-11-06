package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeAiDocuments
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class RecipeAiDocumentsTest {
    @Test fun readsExplicitUtf8WithOptionalBomAndKeepsUnicodeAndKnownZero() {
        val text = "{\"title\":\"Gemüse ≠ Null\",\"amount\":0}"
        assertEquals(text, RecipeAiDocuments.readUtf8(ByteArrayInputStream(text.toByteArray())))
        assertEquals(text, RecipeAiDocuments.readUtf8(ByteArrayInputStream(("\uFEFF" + text).toByteArray())))
    }
    @Test fun oversizedUntrustedDocumentIsRejectedBeforeJsonParsing() {
        try { RecipeAiDocuments.readUtf8(ByteArrayInputStream(ByteArray(RecipeAiDocuments.MAX_BYTES + 1))); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun invalidUtf8MustNotBeSilentlyReplacedWithDifferentText() {
        try { RecipeAiDocuments.readUtf8(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))); fail() }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }
}
