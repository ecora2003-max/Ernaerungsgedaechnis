package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ProductReceiptClassifier
import org.junit.Assert.*
import org.junit.Test

/** Kotlin output is checked against pinned Python-reference golden TSV in CI. */
class ProductReceiptClassifierParityTest {
    @Test fun receiptClassificationMatchesPythonReferenceFixtures() {
        val file = checkNotNull(javaClass.classLoader!!.getResourceAsStream("integration/product_receipt_v1.tsv"))
        val rows = file.bufferedReader().use { it.readLines().filter { s -> s.isNotBlank() && !s.startsWith("#") } }
        assertEquals(17, rows.size)
        for (row in rows) {
            val parts = row.split('|')
            assertEquals("Malformed synthetic vector: $row", 8, parts.size)
            val id = parts[0]
            val rawText = parts[1]
            val provided = parts[2]
            val rawAmount = parts[3]
            val rawConfirmed = parts[4]
            val expectedKind = parts[5]
            val expectedAmount = parts[6]
            val expectedWarnings = parts[7]
            val result = ProductReceiptClassifier.propose(
                rawText, ProductReceiptClassifier.Kind.valueOf(provided),
                rawAmount.takeUnless { it == "null" }?.toLong(), rawConfirmed.toBooleanStrict()
            )
            assertEquals("$id rawText changed", rawText, result.rawText)
            assertEquals("$id kind", expectedKind, result.kind.name)
            assertEquals("$id price", expectedAmount.takeUnless { it == "null" }?.toLong(), result.amountCents)
            assertEquals("$id warnings", if (expectedWarnings == "-") emptyList<String>() else expectedWarnings.split(','), result.warnings)
            assertEquals("$id confirmation", rawConfirmed.toBooleanStrict(), result.confirmed)
        }
    }
    @Test fun invalidHostAmountsAndUntrustedKindsFailClosed() {
        try { ProductReceiptClassifier.propose("TOFU", amountCents = Long.MAX_VALUE); fail("Must reject overflow") }
        catch (_: IllegalArgumentException) {}
        try { ProductReceiptClassifier.propose("x".repeat(4097)); fail("Must reject oversized OCR") }
        catch (_: IllegalArgumentException) {}
        try { ProductReceiptClassifier.Kind.valueOf("BOUGHT"); fail("Draft has no purchase kind") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun classificationNeverBooksOrConfirmsUnreviewedFood() {
        val p = ProductReceiptClassifier.propose("BIO LINSEN 1,99")
        assertEquals(ProductReceiptClassifier.Kind.UNKNOWN, p.kind)
        assertFalse(p.confirmed)
        assertTrue("unconfirmed_ocr" in p.warnings)
    }
}
