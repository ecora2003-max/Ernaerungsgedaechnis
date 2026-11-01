package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ShoppingPackMixKernel as Kernel
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Synthetic bounded-search regression; not yet full suggest_purchases parity. */
class ShoppingPackMixKernelTest {
    private fun pack(id: String, amount: Long, price: Long,
                     shelf: Long? = 10, volume: Long? = 100) =
        Kernel.Candidate(id, amount, price, shelfLifeDays = shelf,
            storageClass = "DRY", packageVolumeMl = volume)

    @Test fun cheapestFeasibleMixIsExactAndReadOnly() {
        val offers = listOf(pack("A", 6, 70), pack("B", 5, 40))
        val before = offers.toList()
        val result = Kernel.optimize(11, offers, "2026-11-01", "2026-11-06",
            budgetCents = 200, capacityMl = mapOf("DRY" to 1000))
        assertEquals("FEASIBLE_NOT_PURCHASED", result.status)
        assertEquals(BigInteger.valueOf(110), result.costCents)
        assertEquals(BigInteger.valueOf(11), result.quantityBought)
        assertEquals(listOf("A", "B"), result.items.map { it.productId })
        assertEquals(listOf(1L, 1L), result.items.map { it.packs })
        assertEquals(BigInteger.valueOf(200), result.usedCapacityMl["DRY"])
        assertTrue(result.optimalityProven)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.sideEffects.isEmpty())
        assertEquals(before, offers)
    }

    @Test fun missingEvidenceAndOverBudgetRemainReview() {
        val unknown = Kernel.optimize(5, listOf(pack("A", 5, 50, shelf = null)),
            "2026-11-01", "2026-11-06", budgetCents = 20, capacityMl = null)
        assertEquals("REVIEW", unknown.status)
        assertEquals(BigInteger.valueOf(50), unknown.costCents)
        assertTrue(unknown.warnings.contains("budget exceeded"))
        assertTrue(unknown.warnings.contains("available storage volume UNKNOWN"))
        assertTrue(unknown.warnings.contains("shelf life or due date UNKNOWN/insufficient"))
        val noBudget = Kernel.optimize(5, listOf(pack("A", 5, 50)),
            "2026-11-01", "2026-11-06", capacityMl = mapOf("DRY" to 500))
        assertEquals("REVIEW", noBudget.status)
        assertTrue(noBudget.warnings.contains("budget UNKNOWN"))
    }

    @Test fun conditionsTimeScopeAndNonPurchasablePricesAreExcluded() {
        val offers = listOf(
            pack("coupon", 5, 1).copy(conditional = true),
            pack("historical", 5, 1).copy(priceKind = "HISTORICAL_PAID"),
            pack("online", 5, 1).copy(priceKind = "ONLINE_LISTED"),
            pack("future", 5, 1).copy(priceKind = "SALE",
                validFrom = "2026-11-02", validUntil = "2026-11-03"),
            pack("soldout", 5, 1).copy(availability = "OUT_OF_STOCK_REPORTED")
        )
        val result = Kernel.optimize(5, offers, "2026-11-01", "2026-11-02",
            budgetCents = 100, capacityMl = mapOf("DRY" to 1000))
        assertEquals("NO_ELIGIBLE_PRICE", result.status)
        assertNull(result.costCents)
        assertEquals(5, result.rejected.size)
        assertFalse(result.optimalityProven)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test fun boundedSearchNeverClaimsUnprovenOptimality() {
        val result = Kernel.optimize(100, listOf(pack("A", 1, 1),
            pack("B", 2, 2)), "2026-11-01", maxNodes = 1)
        assertEquals("SEARCH_LIMIT", result.status)
        assertNull(result.costCents)
        assertFalse(result.optimalityProven)
        assertEquals(emptyList<Kernel.Item>(), result.items)
    }

    @Test fun duplicatesInvalidDatesAndNegativeAmountsFailClosed() {
        val a = pack("A", 5, 50)
        assertThrows(IllegalArgumentException::class.java) {
            Kernel.optimize(5, listOf(a, a), "2026-11-01")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Kernel.optimize(5, listOf(a), "2026-11-31")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Kernel.optimize(5, listOf(a.copy(priceCents = -1)), "2026-11-01")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Kernel.optimize(5, listOf(a.copy(validFrom = "2026-11-03",
                validUntil = "2026-11-02")), "2026-11-01")
        }
    }

    @Test fun storageCapacityAndShelfLifeConstrainFeasibility() {
        val tooLarge = Kernel.optimize(10, listOf(pack("A", 10, 20, volume = 200)),
            "2026-11-01", "2026-11-02", budgetCents = 100,
            capacityMl = mapOf("DRY" to 100))
        assertEquals("REVIEW", tooLarge.status)
        assertTrue(tooLarge.warnings.contains("insufficient confirmed storage volume"))
        val tooShort = Kernel.optimize(10, listOf(pack("A", 10, 20, shelf = 1)),
            "2026-11-01", "2026-11-04", budgetCents = 100,
            capacityMl = mapOf("DRY" to 1000))
        assertEquals("REVIEW", tooShort.status)
        assertTrue(tooShort.warnings.contains("shelf life or due date UNKNOWN/insufficient"))
    }
}
