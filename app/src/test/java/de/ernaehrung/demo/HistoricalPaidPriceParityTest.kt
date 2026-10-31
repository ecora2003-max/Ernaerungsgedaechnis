package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.HistoricalPaidPriceKernel
import org.junit.Assert.*
import org.junit.Test

/** Exactly the same synthetic fixture rows run against pinned original Python in CI. */
class HistoricalPaidPriceParityTest {
    private fun baseline(): Triple<MutableMap<String, Any?>, MutableMap<String, Any?>, MutableMap<String, Any?>> {
        val receipt = mutableMapOf<String, Any?>("purchaseConfirmed" to true, "purchaseReversed" to false,
            "purchaseId" to "op-1", "receiptId" to "bon-1", "retailerId" to "REWE",
            "confirmedAt" to "2026-10-31T12:00:00+00:00", "purchasedOn" to "2026-10-30",
            "purchaseAt" to "2026-10-30T09:00:00+00:00", "storeId" to "filiale-1")
        val line = mutableMapOf<String, Any?>("confirmed" to true, "productConfirmed" to true,
            "lineKind" to "FOOD", "lineId" to "position-1", "receiptId" to "bon-1",
            "retailerId" to "REWE", "productId" to "food-1", "versionId" to "v1",
            "amountCents" to 499L, "purchaseUnits" to 2L, "unitsConfirmed" to true,
            "discountReconciled" to true, "unallocatedDiscount" to false,
            "allocatedDiscountCents" to 1L, "discountApplied" to true,
            "amountExcludesDeposit" to true, "depositPerUnitCents" to 25L,
            "storeId" to "filiale-1", "gtin" to "3017620422003")
        val article = mutableMapOf<String, Any?>("id" to "food-1", "versionId" to "v1",
            "label" to "Testtofu", "gtin" to "3017620422003")
        return Triple(receipt, line, article)
    }
    private val integers = setOf("amountCents", "purchaseUnits", "allocatedDiscountCents", "depositPerUnitCents")
    private fun patch(target: MutableMap<String, Any?>, changes: String) {
        if (changes.isEmpty()) return
        for (pair in changes.split(";")) {
            val (key, value) = pair.split("=", limit = 2)
            when (value) {
                "!" -> target.remove(key)
                "~" -> target[key] = null
                "true" -> target[key] = true
                "false" -> target[key] = false
                else -> target[key] = if (key in integers) value.toLong() else value
            }
        }
    }
    @Test fun goldenHistoryDecisionsMatchPinnedPythonFixture() {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("integration/historical_paid_v1.tsv"))
        val lines = stream.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertTrue(lines.first().startsWith("case\t"))
        val ids = mutableMapOf<String, String>()
        for (row in lines.drop(1)) {
            val columns = row.split("\t")
            assertEquals("bad fixture: $row", 8, columns.size)
            val (receipt, line, article) = baseline()
            patch(receipt, columns[1]); patch(line, columns[2]); patch(article, columns[3])
            val decision = HistoricalPaidPriceKernel.propose(receipt, line, article)
            if (columns[4] != "OK") {
                assertNull(columns[0], decision.observation)
                assertEquals(columns[0], columns[4], decision.reason)
                continue
            }
            assertNull(columns[0], decision.reason)
            val obs = requireNotNull(decision.observation) { columns[0] }
            assertEquals(columns[0], columns[5].toLong(), obs.priceCents)
            assertEquals(columns[0], columns[6].toLong(), obs.linePaidCents)
            assertEquals(columns[0], columns[7].takeUnless { it == "-" }, obs.storeId)
            assertEquals("HISTORICAL_PAID", obs.priceKind)
            assertEquals("local_user_confirmed_receipt", obs.sourceOrigin)
            assertEquals("EUR", obs.currency)
            assertNull(obs.validFrom); assertNull(obs.validUntil)
            assertEquals(receipt["purchaseId"], "op-1")
            assertTrue(obs.observationId.startsWith("receipt-"))
            ids[columns[0]] = obs.observationId
        }
        assertEquals(ids["base"], ids["zero-paid"])
        assertEquals(ids["base"], ids["no-store"])
        assertNotEquals(ids["base"], ids["same-sku-other-line"])
        assertTrue("too few parity cases", lines.size >= 30)
    }
    @Test fun projectionIsPureAndNeverAcceptsUnconfirmedOrIncompleteLine() {
        val (receipt, line, article) = baseline()
        val before = Triple(receipt.toMap(), line.toMap(), article.toMap())
        assertNotNull(HistoricalPaidPriceKernel.propose(receipt, line, article).observation)
        assertEquals(before.first, receipt); assertEquals(before.second, line); assertEquals(before.third, article)
        line["confirmed"] = false
        assertEquals("UNCONFIRMED_PURCHASE_OR_LINE", HistoricalPaidPriceKernel.propose(receipt, line, article).reason)
    }
}
