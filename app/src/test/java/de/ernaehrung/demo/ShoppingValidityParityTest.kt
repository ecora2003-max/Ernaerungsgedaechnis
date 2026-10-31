package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ShoppingValidityKernel as Kernel
import org.junit.Assert.*
import org.junit.Test

/** Same TSV vectors are checked against pinned Python engine._valid in CI. */
class ShoppingValidityParityTest {
    private fun nullable(value: String) = value.takeUnless { it == "-" }
    private fun number(value: String) = nullable(value)?.toLong()
    @Test fun pinnedPythonTimeScopeAndChannelVectors() {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "integration/shopping_validity_v1.tsv"))
        val rows = stream.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertEquals(22, rows.first().split('\t').size)
        assertTrue(rows.size >= 25)
        for (row in rows.drop(1)) {
            val p = row.split('\t')
            assertEquals(p[0], 22, p.size)
            val observation = Kernel.Observation(
                nullable(p[1]), number(p[2]), nullable(p[3]), p[4] == "true",
                Kernel.Source(nullable(p[5]), nullable(p[6]), nullable(p[7])),
                Kernel.Scope(nullable(p[10]), nullable(p[11]), nullable(p[12])),
                nullable(p[8]), nullable(p[9]), number(p[17])
            )
            val stores = nullable(p[18])?.let { retailer ->
                mapOf(retailer to nullable(p[19])?.split(',')?.toSet().orEmpty())
            }
            try {
                val result = Kernel.check(observation, p[13], p[14],
                    historical = p[15] == "true", allowOnline = p[16] == "true",
                    allowedStores = stores)
                assertNotEquals(p[0] + " expected DomainError", "ERROR", p[20])
                assertEquals(p[0], p[20] == "PASS", result.eligible)
                assertEquals(p[0], nullable(p[21]) ?: "",
                    result.notes.joinToString("|"))
            } catch (e: IllegalArgumentException) {
                assertEquals(p[0] + ": " + e.message, "ERROR", p[20])
            }
        }
    }

    @Test fun eligibilityDoesNotCreateAClaimOrMutateInput() {
        val source = Kernel.Source("CONFIRMED", "UNKNOWN", "2026-10-31T12:00:00+01:00")
        val observation = Kernel.Observation("SALE", 100, null, false, source,
            Kernel.Scope("REWE", "CHAIN_WIDE_REPORTED", null),
            "2026-10-31", "2026-11-01", null)
        val result = Kernel.check(observation, "2026-10-31", "2026-10-31T14:00:00+01:00")
        assertTrue(result.eligible)
        assertTrue(result.notes.isNotEmpty())
        assertEquals("UNKNOWN", source.rightsStatus)
        assertEquals("CHAIN_WIDE_REPORTED", observation.scope?.scopeType)
        assertFalse(Kernel.check(observation, "2026-10-31",
            "2026-10-31T14:00:00+01:00", historical = true).eligible)
    }
}
