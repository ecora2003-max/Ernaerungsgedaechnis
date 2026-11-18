package de.ernaehrung.demo

import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.offers.*
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AldiSuedPublicOfferDecoderTest {
    private val at = Instant.parse("2026-11-17T12:00:00Z")
    private val url = "https://www.aldi-sued.de/angebote"
    private fun html(amount: Any? = 179L, relevant: Any? = amount, currency: String? = "EUR",
        visible: String = "1,79 €", display: String? = visible, linkSku: String = "synthetic-1", name: String = "Synthetic article",
        duplicate: Boolean = false, visibleTile: Boolean = true): String {
        val table = mutableListOf<Any?>()
        fun ref(v: Any?): Int { table += v; return table.lastIndex }
        val row = linkedMapOf<String, Any?>("sku" to ref("synthetic-1"), "name" to ref("Synthetic article"),
            "notForSale" to ref(true), "isDropship" to ref(false), "isAbstract" to ref(false), "sellingSize" to ref(null),
            "price" to ref(linkedMapOf("amount" to ref(amount), "amountRelevant" to ref(relevant), "currencyCode" to ref(currency),
                "amountRelevantDisplay" to ref(display), "bottleDeposit" to ref(0L), "bottleDepositDisplay" to ref("0,00 €"))))
        ref(row)
        val script = "<script id='__NUXT_DATA__' type='application/json'>${JsonOutput.encode(table)}</script>"
        val tile = if (visibleTile) """<div class="product-teaser-list"><div class="product-tile"><a class="product-tile__link" href="/produkt/synthetic-$linkSku"><p class="product-tile__name">$name</p></a><div class="base-price"><span class="base-price__regular">$visible</span></div></div></div>""" else ""
        return "<html>$script${if (duplicate) script else ""}$tile</html>"
    }
    private fun decode(text: String) = AldiSuedPublicOfferDecoder.decode(text, "synthetic-public-sued", at, url)
    private fun rejected(text: String) { assertTrue(runCatching { decode(text) }.isFailure) }

    @Test fun actualIndexedSourceIntegerPricesAreCentsAndRenderedOnlineStatusDoesNotInventShelfAvailabilityOrQuantity() {
        val row = decode(html()).single()
        assertEquals(179L, row.merchandiseCents); assertEquals(0L, row.depositCents); assertEquals(179L, row.checkoutCents)
        assertEquals(LiveOfferApplicability.CHAIN_REPORTED, row.applicability)
        assertNull(row.storeId); assertNull(row.gtin); assertNull(row.quantityMilli); assertNull(row.quantityBasis)
        assertNull(row.validFrom); assertNull(row.validUntil); assertFalse(row.conditionsConfirmed)
        assertEquals("https://www.aldi-sued.de/produkt/synthetic-synthetic-1", row.productPageUrl)
        assertEquals(0L, decode(html(amount = 0L, visible = "0,00 €")).single().merchandiseCents)
        assertEquals(179L, decode(html().replace("1,79 €</span>", "1,79 €<span class='base-price__superscript'>¹</span></span>")).single().merchandiseCents)
    }
    @Test fun unknownRelevantPriceCannotFallBackToRegularAmountAndForeignCurrencyIsNeverConvertedToEuro() {
        assertNull(decode(html(relevant = null)).single().merchandiseCents)
        val foreign = decode(html(currency = "USD")).single()
        assertNull(foreign.merchandiseCents); assertNull(foreign.depositCents); assertNull(foreign.checkoutCents)
        assertNull(decode(html(amount = null)).single().merchandiseCents)
    }
    @Test fun independentRenderedSkuTitleAndCentsMustAgreeWithExactlyOneIndexedRowAndScript() {
        rejected(html(visible = "179,00 €")); rejected(html(display = "1,99 €"))
        rejected(html(amount = BigDecimal("1.79"))); rejected(html(linkSku = "different"))
        rejected(html(name = "Different visible article")); rejected(html(duplicate = true))
        rejected(html().replace("\"sku\":0", "\"sku\":99999"))
    }
    @Test fun unrelatedIndexedPricesAndExecutableScriptsNeverBecomeOffersOrNetworkRequests() {
        assertTrue(decode(html(visibleTile = false)).isEmpty())
        val row = decode(html() + "<script src='https://never.example.test/script.js'></script><script>throw 'must not execute'</script>").single()
        assertEquals(179L, row.merchandiseCents)
        rejected(html().replace("/produkt/synthetic-synthetic-1", "//other.example.test/produkt/synthetic-synthetic-1"))
    }
}
