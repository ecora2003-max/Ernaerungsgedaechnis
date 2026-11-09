package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** All supplied documents are synthetic and shared with native/reference tests. */
class StationaryPublishedSourcesTest {
    private val at = Instant.parse("2026-11-09T10:00:00Z")
    private fun fixture(file: String) = javaClass.getResourceAsStream("/published_sources/$file")!!.bufferedReader().use { it.readText() }
    @Test fun canonicalSuppliedRowsMatchPinnedIndependentPythonReferenceVectors() {
        val keys = listOf("id", "current", "regular", "deposit", "previous", "app", "brand", "wishlist", "from", "unitText")
        val expected = OfferJson.read(fixture("reference_expected.json")).mapValues { (_, rows) ->
            (rows as List<*>).map { raw ->
                val row = requireNotNull(OfferJson.objectValue(raw))
                keys.associateWith { key -> row[key].let { if (it is Number) it.toLong() else it } }
            }
        }
        val groups = mapOf(
            "rewe" to StationaryCatalogueDecoders.rewe(fixture("rewe.json"), "s", "000001", at, "https://www.rewe.de/search"),
            "aldi_sued" to StationaryCatalogueDecoders.aldiSued(fixture("aldi_sued.json"), "s", "B000", at, "https://api.aldi-sued.de/search"),
            "netto" to NettoBranchOfferDecoder.decode(fixture("netto.html"), "s", at, "https://www.netto-online.de/angebote/c-N07"))
        val actual = groups.mapValues { (_, rows) -> rows.map { o -> mapOf(
            "id" to o.sourceProductId, "current" to o.merchandiseCents, "regular" to o.priceDetails?.regularCents,
            "deposit" to o.depositCents, "previous" to o.priceDetails?.previousDisplay, "app" to o.priceDetails?.appCents,
            "brand" to o.priceDetails?.brand, "wishlist" to o.priceDetails?.wishlistUntilLocal,
            "from" to o.validFrom?.toString(), "unitText" to o.priceDetails?.unitPriceDisplay) } }
        assertEquals(expected, actual)
    }
    @Test fun reweCurrentRegularAndDepositRemainSeparateWithKnownZeroAndUnprovenValidity() {
        val rows = StationaryCatalogueDecoders.rewe(fixture("rewe.json"), "synthetic", "000001", at, "https://www.rewe.de/api/stationary-product-search/products")
        assertEquals(listOf("101", "zero", "unknown", "above"), rows.map { it.sourceProductId })
        val promo = rows[0]
        assertEquals(129L, promo.merchandiseCents); assertEquals(189L, promo.priceDetails!!.regularCents)
        assertEquals(25L, promo.depositCents); assertEquals(154L, promo.checkoutCents)
        assertEquals(LiveOfferKind.PROMOTION, promo.kind); assertEquals("Testmarke", promo.priceDetails.brand)
        assertEquals(0L, rows[1].merchandiseCents); assertEquals(0L, rows[1].depositCents)
        assertEquals(0L, rows[1].priceDetails!!.regularCents); assertEquals(LiveOfferKind.REGULAR, rows[1].kind)
        assertNull(rows[2].priceDetails!!.regularCents); assertNull(rows[2].depositCents); assertNull(rows[2].checkoutCents)
        assertEquals(LiveOfferKind.UNKNOWN, rows[2].kind); assertEquals(LiveOfferKind.UNKNOWN, rows[3].kind)
        rows.forEach { row ->
            assertNull(row.quantityMilli); assertNull(row.unitPriceText()); assertNull(row.gtin)
            assertNull(row.validFrom); assertNull(row.validUntil); assertNull(row.sourceObservedAt)
            assertFalse(row.conditionsConfirmed); assertEquals(at, row.fetchedAt)
            assertFalse(row.applicable(LiveOfferScope(LiveRetailer.REWE, "000001", null, null, LocalDate.parse("2026-11-09"))))
        }
        val preserved = StationaryCatalogueDecoders.rewe(fixture("rewe.json").replace("\"unknown\"", "\" raw/#? \""),
            "synthetic", "000001", at, "https://www.rewe.de/api/stationary-product-search/products")
        assertEquals(" raw/#? ", preserved[2].sourceProductId)
    }
    @Test fun aldiRelevantZeroWinsFallbackIsExplicitAndFormerDisplayNeverBecomesNumericRegularPrice() {
        val rows = StationaryCatalogueDecoders.aldiSued(fixture("aldi_sued.json"), "synthetic", "B000", at, "https://api.aldi-sued.de/v3/product-search")
        assertEquals(listOf("primary", "zero", "fallback", "unknown-deposit"), rows.map { it.sourceProductId })
        assertEquals(159L, rows[0].merchandiseCents); assertEquals(25L, rows[0].depositCents)
        assertEquals("  1,99 €  ", rows[0].priceDetails!!.previousDisplay)
        assertEquals(0L, rows[1].merchandiseCents); assertEquals(0L, rows[1].depositCents)
        assertEquals(199L, rows[2].merchandiseCents); assertNull(rows[2].depositCents)
        rows.forEach {
            assertNull(it.priceDetails!!.regularCents); assertEquals(LiveOfferKind.UNKNOWN, it.kind)
            assertNull(it.quantityMilli); assertNull(it.validFrom); assertNull(it.validUntil)
            assertNull(it.sourceObservedAt); assertFalse(it.conditionsConfirmed)
        }
    }
    @Test fun missingCatalogueArrayFailsInsteadOfReportingAnEmptyAssortment() {
        assertTrue(runCatching { StationaryCatalogueDecoders.rewe("{\"errors\":[]}", "s", "1", at, "https://www.rewe.de/search") }.isFailure)
        assertTrue(runCatching { StationaryCatalogueDecoders.aldiSued("{\"data\":{}}", "s", "B0", at, "https://api.aldi-sued.de/search") }.isFailure)
    }
    @Test fun nettoBranchHtmlKeepsAppPriceAndWishlistTimeSeparateAndExcludesWebshopCards() {
        val rows = NettoBranchOfferDecoder.decode(fixture("netto.html"), "synthetic", at, "https://www.netto-online.de/angebote/c-N07")
        assertEquals(listOf("n-zero", "n-offer"), rows.map { it.sourceProductId })
        assertEquals(0L, rows[0].merchandiseCents); assertEquals(0L, rows[0].priceDetails!!.appCents)
        assertEquals(100L, rows[1].merchandiseCents); assertEquals(79L, rows[1].priceDetails!!.appCents)
        assertEquals("2,00 €/kg", rows[1].priceDetails!!.unitPriceDisplay); assertNull(rows[1].packageDescription)
        rows.forEach {
            assertEquals(LocalDate.parse("2026-11-09"), it.validFrom); assertEquals(LocalDate.parse("2026-11-14"), it.validUntil)
            assertEquals("2026-11-14 20:00:00", it.priceDetails!!.wishlistUntilLocal)
            assertNull(it.priceDetails.regularCents); assertNull(it.storeId); assertNull(it.quantityMilli)
            assertNull(it.depositCents); assertNull(it.checkoutCents); assertNull(it.sourceObservedAt)
            assertEquals(LiveOfferApplicability.CHAIN_REPORTED, it.applicability); assertFalse(it.conditionsConfirmed)
        }
    }
    @Test fun nettoUnknownHeaderEndRemainsUnknownDespiteWishlistTimestampAndMismatchedPricesAreExcluded() {
        val html = fixture("netto.html").replace(" - Samstag, 14.11.26", "").replace("Price=1.00", "Price=9.00")
        val row = NettoBranchOfferDecoder.decode(html, "synthetic", at, "https://www.netto-online.de/angebote/c-N07").single()
        assertEquals("n-zero", row.sourceProductId); assertNull(row.validUntil)
        assertEquals("2026-11-14 20:00:00", row.priceDetails!!.wishlistUntilLocal)
        val invalidStart = NettoBranchOfferDecoder.decode(fixture("netto.html").replace("09.11.26", "31.02.26"),
            "synthetic", at, "https://www.netto-online.de/angebote/c-N07")
        assertTrue(invalidStart.all { it.validFrom == null && it.validUntil == null })
    }
    @Test fun nettoRequiresActualBranchSectionAndCorroboratingOfficialLink() {
        assertTrue(runCatching { NettoBranchOfferDecoder.decode("<h2>Online-Angebote</h2>", "s", at, "https://www.netto-online.de/angebote/c-N07") }.isFailure)
        val fake = fixture("netto.html").replace("https://www.netto-online.de/INTERSHOP", "https://untrusted.example/INTERSHOP")
        assertTrue(NettoBranchOfferDecoder.decode(fake, "s", at, "https://www.netto-online.de/angebote/c-N07").isEmpty())
        val repeated = fixture("netto.html").replace("&amp;Price=", "&amp;Price=7.00&amp;Price=")
        assertTrue(NettoBranchOfferDecoder.decode(repeated, "s", at, "https://www.netto-online.de/angebote/c-N07").isEmpty())
    }
}
