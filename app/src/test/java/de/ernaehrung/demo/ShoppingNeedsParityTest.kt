package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ShoppingNeedsKernel as Kernel
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Shared TSV is also executed against pinned Python engine.project_needs in CI. */
class ShoppingNeedsParityTest {
    private fun nullable(value: String): String? = value.takeUnless { it == "-" }
    private fun number(value: String): Long? = nullable(value)?.toLong()
    private fun flag(value: String): Boolean? = when (value) {
        "true" -> true
        "false" -> false
        "-" -> null
        else -> error("invalid flag $value")
    }
    private fun qty(parts: List<String>, index: Int) = Kernel.Quantity(
        number(parts[index]), nullable(parts[index + 1]), nullable(parts[index + 2]),
        number(parts[index + 3]), flag(parts[index + 4]) == true
    )
    private fun stock(value: String): List<Kernel.Lot>? = when (value) {
        "~" -> null
        "-" -> emptyList()
        else -> value.split(';').map { entry ->
            val p = entry.split('|')
            assertEquals(10, p.size)
            Kernel.Lot(nullable(p[0]), nullable(p[1]), qty(p, 2),
                nullable(p[9]), flag(p[7]), flag(p[8]))
        }
    }
    private fun demands(value: String): List<Kernel.Demand>? = when (value) {
        "~" -> null
        "-" -> emptyList()
        else -> value.split(';').map { entry ->
            val p = entry.split('|')
            assertEquals(9, p.size)
            Kernel.Demand(nullable(p[0]), nullable(p[1]), nullable(p[2]),
                qty(p, 3), nullable(p[8]))
        }
    }
    private fun history(value: String): List<Kernel.Actual>? = when (value) {
        "-" -> emptyList()
        else -> value.split(';').map { entry ->
            val p = entry.split('|')
            assertEquals(9, p.size)
            Kernel.Actual(nullable(p[0]), nullable(p[1]), p[2], qty(p, 3),
                flag(p[7]), nullable(p[8]))
        }
    }
    private fun totals(rows: List<Kernel.Total>): String = rows.joinToString(",") {
        "${it.foodTypeId}:${it.unit}:${it.basis}=${it.quantityToBuy}"
    }.ifEmpty { "-" }

    @Test fun pinnedPythonProjectNeedsVectors() {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "integration/shopping_needs_v1.tsv"))
        val lines = stream.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertEquals(13, lines.first().split('\t').size)
        var cases = 0
        for (line in lines.drop(1)) {
            val p = line.split('\t')
            assertEquals("fixture columns", 13, p.size)
            val snapshot = Kernel.Snapshot(p[1], stock(p[2]), demands(p[3]),
                history(p[4]), nullable(p[5]), nullable(p[6]))
            val result = Kernel.project(snapshot)
            for ((column, horizon) in listOf(7 to 1, 8 to 7, 9 to 28, 10 to 56)) {
                assertEquals(p[0] + " horizon $horizon", p[column],
                    totals(requireNotNull(result.horizons[horizon]).totals))
            }
            val unknownIds = result.unknownNeeds.joinToString(",") { it.eventId }.ifEmpty { "-" }
            assertEquals(p[0] + " unknown", p[11], unknownIds)
            assertEquals(p[0] + " completeness", p[12].toBooleanStrict(),
                result.snapshotComplete)
            assertEquals(p[0] + " side effects", emptyList<String>(), result.sideEffects)
            assertTrue(p[0], result.allocations.all {
                it.quantityToBuy >= BigInteger.ZERO &&
                    it.quantityToBuy <= it.quantityRequired
            })
            assertEquals(p[0], result.warnings.isEmpty(), result.snapshotComplete)
            cases++
        }
        assertEquals(12, cases)
    }

    @Test fun duplicateAndOutOfWindowEventsFailClosed() {
        val q = Kernel.Quantity(10, "g", "NET", 1, true)
        val event = Kernel.Demand("e", "oats", "2026-10-31", q)
        assertThrows(IllegalArgumentException::class.java) {
            Kernel.project(Kernel.Snapshot("2026-10-31", emptyList(), listOf(event, event)))
        }
        val lot = Kernel.Lot("lot", "oats", q, "2026-11-10", true, true)
        assertThrows(IllegalArgumentException::class.java) {
            Kernel.project(Kernel.Snapshot("2026-10-31", listOf(lot, lot), emptyList()))
        }
        val actual = Kernel.Actual("a", "oats", "2026-10-09", q, true)
        assertThrows(IllegalArgumentException::class.java) {
            Kernel.project(Kernel.Snapshot("2026-10-31", emptyList(), emptyList(),
                listOf(actual), "2026-10-10", "2026-10-16"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Kernel.project(Kernel.Snapshot("2026-10-31", emptyList(), emptyList()),
                listOf(0, 7))
        }
    }

    @Test fun unknownIsNotZeroAndSnapshotsRemainUnchanged() {
        val unknown = Kernel.Demand("missing", "oats", "2026-10-31", null)
        val snapshot = Kernel.Snapshot("2026-10-31", null, listOf(unknown))
        val before = snapshot.copy()
        val result = Kernel.project(snapshot)
        assertEquals(before, snapshot)
        assertNull(result.unknownNeeds.single().quantityToBuy)
        assertTrue(result.horizons.getValue(1).totals.isEmpty())
        assertFalse(result.snapshotComplete)
        assertTrue(result.warnings.contains("stock snapshot UNKNOWN"))
    }
}
