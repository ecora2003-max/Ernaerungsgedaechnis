package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ShoppingUnitRateKernel as Kernel
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Same TSV vectors are checked against pinned Python engine.py in GitHub Actions. */
class ShoppingUnitRateParityTest {
    private fun num(value: String): Long? = value.takeUnless { it == "-" }?.toLong()
    private fun quantity(parts: List<String>): Kernel.Quantity = Kernel.Quantity(
        num(parts[1]), parts[2], parts[3], num(parts[4]), parts[5] == "true"
    )

    @Test fun pinnedPythonQuantityAndRationalRateVectors() {
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "integration/shopping_unit_rate_v1.tsv"))
        val rows = resource.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertEquals(15, rows.first().split('\t').size)
        var count = 0
        for (row in rows.drop(1)) {
            val p = row.split('\t')
            assertEquals("bad fixture: ${p[0]}", 15, p.size)
            try {
                val rate = Kernel.rate(num(p[6]), p[9], quantity(p), num(p[7]), p[8] == "true")
                if (p[10] == "UNKNOWN") {
                    assertNull(p[0], rate)
                } else {
                    assertEquals(p[0], "OK", p[10])
                    val actual = requireNotNull(rate) { p[0] }
                    assertEquals(p[0], BigInteger(p[11]), actual.numerator)
                    assertEquals(p[0], BigInteger(p[12]), actual.denominator)
                    assertEquals(p[0], p[13], actual.displayBasis)
                    assertEquals(p[0], BigInteger(p[14]), actual.milliCents())
                }
            } catch (e: IllegalArgumentException) {
                assertEquals(p[0] + ": " + e.message, "ERROR", p[10])
            }
            count++
        }
        assertTrue("expected at least 15 parity vectors", count >= 15)
    }

    @Test fun exactTargetEquivalentDeltaAndNoBasisGuessing() {
        val targetQty = Kernel.Quantity(500, "g", "NET", 1, true)
        val target = requireNotNull(Kernel.rate(199, "EUR", targetQty))
        val cheaper = requireNotNull(Kernel.rate(179, "EUR", targetQty))
        assertEquals(-1, Kernel.compare(cheaper, target))
        assertEquals(BigInteger.valueOf(-20), Kernel.targetEquivalentDifferenceCents(target, cheaper, targetQty))
        val bulk = requireNotNull(Kernel.rate(230, "EUR", Kernel.Quantity(500, "g", "NET", 2, true)))
        assertEquals(BigInteger.valueOf(-84), Kernel.targetEquivalentDifferenceCents(target, bulk, targetQty))
        val fractional = requireNotNull(Kernel.rate(100, "EUR", Kernel.Quantity(300, "g", "NET", 1, true)))
        assertNull(Kernel.targetEquivalentDifferenceCents(target, fractional, targetQty))
        val drained = requireNotNull(Kernel.rate(100, "EUR", Kernel.Quantity(500, "g", "DRAINED", 1, true)))
        assertNull(Kernel.compare(target, drained))
        assertNull(Kernel.targetEquivalentDifferenceCents(target, drained, targetQty))
        val pieces = requireNotNull(Kernel.rate(100, "EUR", Kernel.Quantity(3, "piece", "PIECE", 1, true)))
        assertNull(Kernel.compare(target, pieces))
        assertNull(Kernel.targetEquivalentDifferenceCents(target, pieces, targetQty))
    }
}
