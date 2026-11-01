package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ShoppingPackMixKernel as Kernel
import org.junit.Assert.*
import org.junit.Test

/** Exact same TSV is run against pinned Python optimize_package_mix in Actions. */
class ShoppingPackMixParityTest {
    private fun nullable(s: String) = s.takeUnless { it == "-" }
    private fun candidates(raw: String) = raw.split(';').map {
        val p = it.split('|')
        assertEquals(6, p.size)
        Kernel.Candidate(p[0], p[1].toLong(), p[2].toLong(),
            shelfLifeDays = nullable(p[3])?.toLong(), storageClass = nullable(p[4]),
            packageVolumeMl = nullable(p[5])?.toLong())
    }
    private fun capacity(raw: String): Map<String, Long?>? =
        nullable(raw)?.split(';')?.associate {
            val p = it.split('=')
            p[0] to p[1].toLong()
        }

    @Test fun sharedPinnedPythonFullOptimizerVectors() {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "integration/shopping_pack_mix_v1.tsv"))
        val rows = stream.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() }
        assertEquals(12, rows.first().split('\t').size)
        var count = 0
        for (line in rows.drop(1)) {
            val p = line.split('\t')
            assertEquals("columns", 12, p.size)
            val result = Kernel.optimize(p[1].toLong(), candidates(p[2]), p[3],
                nullable(p[4]), nullable(p[5])?.toLong(), capacity(p[6]), p[7].toInt())
            assertEquals(p[0] + " status", p[8], result.status)
            assertEquals(p[0] + " cost", p[9], result.costCents?.toString() ?: "-")
            assertEquals(p[0] + " mix", p[10],
                result.items.joinToString(",") { "${it.productId}:${it.packs}" }.ifEmpty { "-" })
            assertEquals(p[0] + " warnings", p[11],
                result.warnings.joinToString(";").ifEmpty { "-" })
            assertEquals(p[0] + " side effects", emptyList<String>(), result.sideEffects)
            count++
        }
        assertEquals(7, count)
    }
}
