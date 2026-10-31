package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ShoppingCoverageSignalKernel as Kernel
import org.junit.Assert.*
import org.junit.Test

/** Shared fixture: Python _coverage_gaps on pinned engine.py and Kotlin signals. */
class ShoppingCoverageSignalParityTest {
    private fun list(value: String, separator: Char = '|'): List<String> =
        if (value == "-") emptyList() else value.split(separator)
    private fun coverage(complete: String, gaps: String): Kernel.Coverage? =
        if (complete == "-") null else Kernel.Coverage(
            when (complete) { "true" -> true; "false" -> false; "unknown" -> null
                else -> error("invalid fixture complete=$complete") },
            list(gaps)
        )
    private fun alternatives(value: String): List<Kernel.CheaperAlternative> =
        list(value, ';').map { row ->
            val fields = row.split(':')
            require(fields.size == 3)
            Kernel.CheaperAlternative(fields[0], fields[1], fields[2])
        }

    @Test fun pinnedCoverageAndContractSignalVectors() {
        val input = requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "integration/shopping_coverage_signals_v1.tsv"))
        val rows = input.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertEquals(8, rows.first().split('\t').size)
        assertTrue(rows.size >= 20)
        for (line in rows.drop(1)) {
            val p = line.split('\t')
            assertEquals(p[0], 8, p.size)
            val c = coverage(p[1], p[2])
            assertEquals(p[0] + " coverage", list(p[5]), Kernel.coverageGaps(c))
            val r = Kernel.project(c, alternatives(p[3]), list(p[4]))
            assertEquals(p[0] + " gaps", list(p[6]), r.gaps)
            assertEquals(p[0] + " signals", list(p[7]), r.signals)
            assertEquals(p[0] + " incomplete", r.gaps.isNotEmpty(), r.incomplete)
            assertEquals("CHEAPEST_KNOWN_MATCHING_ONLY", r.bestPriceClaim)
        }
    }

    @Test fun failClosedOnInvalidPrefilteredRows() {
        val valid = Kernel.CheaperAlternative("A", "SAME", "EXACT_SKU")
        fun rejected(vararg rows: Kernel.CheaperAlternative) {
            try { Kernel.project(Kernel.Coverage(true), rows.toList()); fail("must reject") }
            catch (_: IllegalArgumentException) { /* fail closed */ }
        }
        rejected(valid, valid)
        rejected(valid.copy(observationId = ""))
        rejected(valid.copy(bioDifference = "false"))
        rejected(valid.copy(comparability = "UNKNOWN"))
        val result = Kernel.project(Kernel.Coverage(true), listOf(
            valid.copy(comparability = "FUNCTIONAL_SUBSTITUTE")))
        assertTrue(result.signals.isEmpty())
        assertTrue(result.gaps.isEmpty())
    }

    @Test fun unknownBioAndIncompleteCoverageNeverBecomeBestPriceProof() {
        val r = Kernel.project(Kernel.Coverage(false), listOf(
            Kernel.CheaperAlternative("UNKNOWN_BIO", "UNKNOWN", "EXACT_SKU"),
            Kernel.CheaperAlternative("RED", "SAME", "SAME_FOOD_VARIANT")
        ), listOf("store identity not verified"))
        assertEquals(listOf("RED_SAME_BIO_CHEAPER", "GREY_MAY_BE_CHEAPER"), r.signals)
        assertTrue(r.gaps.contains("UNKNOWN_BIO: unknown organic status"))
        assertTrue(r.gaps.contains("store identity not verified"))
        assertTrue(r.incomplete)
    }
}
