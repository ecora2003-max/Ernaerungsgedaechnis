package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ShoppingComparabilityKernel as Kernel
import de.ernaehrung.demo.data.integration.ShoppingUnitRateKernel
import org.junit.Assert.*
import org.junit.Test

/** Same synthetic vectors are checked against the pinned Python _comparability in CI. */
class ShoppingComparabilityParityTest {
    private fun n(s: String) = s.takeUnless { it == "-" }
    private fun attrs(s: String): Map<String, String> = if (s == "-") emptyMap() else
        s.split(';').associate {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2)
            parts[0] to parts[1]
        }
    private fun quantity(p: List<String>, offset: Int): ShoppingUnitRateKernel.Quantity =
        ShoppingUnitRateKernel.Quantity(
            n(p[offset])?.toLong(), n(p[offset + 1]), n(p[offset + 2]),
            n(p[offset + 3])?.toLong(), p[offset + 4] == "true"
        )

    @Test fun pinnedPythonComparabilityVectors() {
        val input = requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "integration/shopping_comparability_v1.tsv"))
        val rows = input.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertEquals(26, rows.first().split('\t').size)
        assertTrue(rows.size >= 30)
        for (line in rows.drop(1)) {
            val p = line.split('\t')
            assertEquals(p[0], 26, p.size)
            val target = Kernel.Observation("target", Kernel.Article(
                n(p[1]), attrs(p[3]), quantity(p, 11), n(p[5]), n(p[7]), n(p[9])))
            val candidate = Kernel.Observation("candidate", Kernel.Article(
                n(p[2]), attrs(p[4]), quantity(p, 16), n(p[6]), n(p[8]), n(p[10])))
            val matches = if (p[21] == "-") emptyList() else listOf(Kernel.Match(
                "target", "candidate", p[21], p[22],
                n(p[23])?.split('|') ?: emptyList()))
            try {
                val verdict = Kernel.compare(target, candidate, matches)
                assertNotEquals(p[0], "ERROR", p[24])
                assertEquals(p[0], p[24], verdict.level)
                assertEquals(p[0], n(p[25]) ?: "", verdict.evidence.joinToString("|"))
            } catch (e: IllegalArgumentException) {
                assertEquals(p[0] + ": " + e.message, "ERROR", p[24])
            }
        }
    }

    @Test fun noWritesOrAuthorizationAndNoInputMutation() {
        val q = ShoppingUnitRateKernel.Quantity(500, "g", "NET", 1, true)
        val a = Kernel.Article("milk", mapOf("form" to "liquid"), q, "123", "BRAND", "A")
        val b = a.copy(foodTypeId = "oat", gtin = "456")
        val target = Kernel.Observation("target", a)
        val candidate = Kernel.Observation("candidate", b)
        assertEquals("UNKNOWN", Kernel.compare(target, candidate).level)
        val match = Kernel.Match("target", "candidate", "CONFIRMED_BY_USER",
            "FUNCTIONAL_SUBSTITUTE", listOf("user evidence"))
        assertEquals("FUNCTIONAL_SUBSTITUTE", Kernel.compare(target, candidate, listOf(match)).level)
        assertEquals("milk", a.foodTypeId)
        assertEquals("oat", b.foodTypeId)
        assertEquals(500L, q.amount)
        // This port has no Room, network, source rights, FoodStore or side-effect dependency.
    }
}
