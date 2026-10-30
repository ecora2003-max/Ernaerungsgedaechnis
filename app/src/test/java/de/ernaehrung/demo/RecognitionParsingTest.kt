package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.recognition.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionParsingTest {
    private val code = "3017620422003"
    private fun invalid(block: () -> Unit) { try { block(); fail("Expected invalid input") } catch (_: IllegalArgumentException) { } }
    private fun source(product: String) = """{"status":1,"product":{"code":"$code",$product}}"""

    @Test fun knownGtinChecksumsPreserveLeadingZeroesAndEquivalentUpcEanKeys() {
        for (known in listOf("96385074", "036000291452", "0036000291452", "4006381333931", code)) {
            val parsed = Gtin.parse(known); assertEquals(known, parsed.printed); assertEquals(14, parsed.canonical.length)
        }
        assertEquals(Gtin.parse("036000291452").canonical, Gtin.parse("0036000291452").canonical)
        listOf("", "00000000", "96385075", "3017620422004", "3017620422003<script>", "3.017620422003E12", "123", " 3017 620422003 ").forEach { bad -> invalid { Gtin.parse(bad) } }
        assertEquals(code, Gtin.parse(" $code ").printed)
    }
    @Test fun aliasNormalizationRetainsVariantDigitsPunctuationAndMerchantDistinctions() {
        assertEquals("bio tofu 200g", AliasText.normalize("  BIO   TOFU ２００g "))
        assertNotEquals(AliasText.normalize("TOFU 200g"), AliasText.normalize("TOFU 400g"))
        assertNotEquals(AliasText.normalize("TOFU-A"), AliasText.normalize("TOFU A"))
        assertNull(AliasText.merchant(" ")); assertEquals("testmarkt", AliasText.merchant(" TestMarkt "))
    }
    @Test fun openFoodFactsUnknownZeroAndNormalizedMineralsDoNotBecomeInventedValues() {
        val result = OpenFoodFactsClient.parse(code, source(""""product_name":"Testtofu","brands":"Testmarke","product_quantity":200,"product_quantity_unit":"g","quantity":"200 g","nutriments":{"energy-kcal_100g":120,"proteins_100g":13.5,"fat_100g":0,"calcium_100g":0.12,"calcium_unit":"mg","iron_100g":0.002,"vitamin-b12_100g":0.000002,"vitamin-b12_unit":"µg"},"ingredients_text":"Testzutaten","allergens":"en:soybeans""""), 123) as ProductLookup.Found
        val d = result.draft
        assertEquals(BaseUnit.GRAM, d.suggestedUnit); assertEquals(200_000L, d.packageAmount)
        assertEquals(0.0, d.nutrients[Nutrient.FAT]!!, 0.0); assertNull(d.nutrients[Nutrient.SALT])
        assertEquals(120.0, d.nutrients[Nutrient.CALCIUM]!!, 1e-9); assertEquals(2.0, d.nutrients[Nutrient.IRON]!!, 1e-9)
        assertEquals(2.0, d.nutrients[Nutrient.B12]!!, 1e-9)
        assertEquals("Testzutaten", d.ingredients); assertEquals("en:soybeans", d.allergens)
        assertTrue(OpenFoodFactsClient.sourceDetail(d).contains("Abruf 1970-01-01T00:00:00.123Z"))
    }
    @Test fun externalServingValuesBadNumbersAndUnclearPackUnitRemainUnresolved() {
        val d = (OpenFoodFactsClient.parse(code, source(""""product_name":"Test","product_quantity":200,"product_quantity_unit":"pack","nutriments":{"proteins_serving":10,"fat_100g":-1,"salt_100g":"NaN","iron_100g":"1e9999"}"""), 1) as ProductLookup.Found).draft
        assertNull(d.suggestedUnit); assertNull(d.packageAmount)
        assertNull(d.nutrients[Nutrient.PROTEIN]); assertNull(d.nutrients[Nutrient.FAT]); assertNull(d.nutrients[Nutrient.SALT]); assertNull(d.nutrients[Nutrient.IRON])
        assertTrue(d.warnings.size >= 5)
    }
    @Test fun lookupChecksResponseIdentityStatusAndBoundsWithoutFollowingUntrustedUrls() {
        assertTrue(OpenFoodFactsClient.parse(code, """{"status":0}""", 1) is ProductLookup.NotFound)
        invalid { OpenFoodFactsClient.parse(code, """{"status":1,"product":{"code":"4006381333931"}}""", 1) }
        invalid { OpenFoodFactsClient.parse(code, "{}", 1) }
        invalid { OpenFoodFactsClient.parse(code, "x".repeat(OpenFoodFactsClient.MAX_RESPONSE + 1), 1) }
        invalid { OpenFoodFactsClient.apiUrl("https://untrusted.invalid") }
        assertTrue(OpenFoodFactsClient.apiUrl(code).startsWith("https://world.openfoodfacts.org/api/v2/product/$code.json?"))
    }
    @Test fun receiptTotalsAreNotCashGivenTaxOrSumOfPositionsAndUnknownAmountsStayNull() {
        val raw = "TESTMARKT\nTESTBON\n30.10.2026 12:30\nBIO TOFU 1,99 A\nKICHERERBSEN 2,98 B\nSUMME EUR 4,97\nBAR 10,00\nRUECKGELD 5,03\nMWST 0,32"
        val result = ReceiptParser.parse(raw)
        assertEquals("TESTMARKT", result.merchant); assertEquals("2026-10-30", result.purchasedOn)
        assertEquals(497L, result.totalCents); assertEquals(2, result.lines.size)
        assertEquals("BIO TOFU 1,99 A", result.lines.first().originalText); assertEquals("BIO TOFU", result.lines.first().name)
        assertNull(ReceiptParser.parse("TESTMARKT\nTOFU 1,99\nBAR 10,00").totalCents)
        assertNull(ReceiptParser.parse("TESTMARKT\nDatum unbekannt\nArtikel nicht lesbar").purchasedOn)
    }
    @Test fun receiptContradictionsRefundsInvalidDatesAndPrintedAmountsAreNeverAutoStockFacts() {
        val mixed = ReceiptParser.parse("TESTMARKT\n30.10.2026\n29.10.2026\nTOFU 200 g 1,99\nSUMME 1,99\nGESAMT 2,99")
        assertNull(mixed.purchasedOn); assertNull(mixed.totalCents)
        assertEquals("200 g", mixed.lines.single().quantityText)
        val refund = ReceiptParser.parse("TESTMARKT\n31.02.2026\nRETOURE -1,99\nSUMME -1,99")
        assertNull(refund.totalCents); assertNull(refund.purchasedOn); assertTrue(refund.lines.isEmpty())
        assertEquals(0L, ReceiptParser.parse("TESTMARKT\nSUMME 0,00").totalCents)
    }
    @Test fun receiptInputIsBoundedAndDuplicateProductLinesAreNotCollapsed() {
        val r = ReceiptParser.parse("TESTMARKT\nTOFU 1,99\nTOFU 1,99\nSUMME 3,98")
        assertEquals(2, r.lines.size); assertEquals(398L, r.totalCents)
        invalid { ReceiptParser.parse("") }; invalid { ReceiptParser.parse("x".repeat(ReceiptParser.MAX_TEXT + 1)) }
    }
    @Test fun geometricOcrRowsRejoinPriceColumnsWithoutChangingOriginalRecognizedCharacters() {
        val boxes = listOf(TextBox("1,99 A", 300, 50, 400, 70), TextBox("TESTMARKT", 0, 0, 200, 20),
            TextBox("SUMME", 0, 90, 150, 110), TextBox("4,97", 300, 90, 400, 110), TextBox("BIO TOFU", 0, 50, 200, 70))
        assertEquals("TESTMARKT\nBIO TOFU 1,99 A\nSUMME 4,97", OcrRows.read(boxes))
        assertEquals(497L, ReceiptParser.parse(OcrRows.read(boxes)).totalCents)
    }
    @Test fun actualHostOcrLosingADecimalKeepsPositionButNeverGuessesItsPrice() {
        val text = javaClass.classLoader!!.getResourceAsStream("recognition/host-ocr-receipt.txt")!!.bufferedReader().use { it.readText() }
        val parsed = ReceiptParser.parse(text)
        assertEquals("TESTMARKT", parsed.merchant); assertEquals("2026-10-30", parsed.purchasedOn)
        assertEquals(497L, parsed.totalCents); assertEquals(2, parsed.lines.size)
        assertEquals("BIO TOFU 200 g 199A", parsed.lines.first().originalText)
        assertEquals("BIO TOFU 200 g", parsed.lines.first().name); assertNull(parsed.lines.first().totalCents)
        assertEquals(298L, parsed.lines.last().totalCents)
        assertTrue(parsed.warnings.any { it.contains("nicht sicher lesbar") })
    }
    @Test fun unicodeNegativeAndPartlyUnreadableConflictingTotalLinesNeverBecomePositivePayment() {
        assertNull(ReceiptParser.parse("TESTMARKT\nSUMME −1,99").totalCents)
        assertNull(ReceiptParser.parse("TESTMARKT\nSUMME -1,99\nGESAMT 1,99").totalCents)
        assertNull(ReceiptParser.parse("TESTMARKT\nSUMME unlesbar\nGESAMT 1,99").totalCents)
        assertTrue(ReceiptParser.parse("TESTMARKT\nTOFU −1,99\nSUMME −1,99").lines.isEmpty())
        assertEquals(199L, ReceiptParser.parse("TESTMARKT\nSUMME 1,99\nGESAMT 1,99").totalCents)
    }
}
