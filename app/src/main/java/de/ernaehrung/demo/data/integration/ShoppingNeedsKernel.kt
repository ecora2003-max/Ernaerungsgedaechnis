package de.ernaehrung.demo.data.integration

import java.math.BigInteger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Read-only Kotlin port of EG-Einkaufsintelligenz@a3e13b1 engine.project_needs.
 * Input facts are snapshots, not authorization to consume, reserve, buy or book stock.
 * Every horizon is a cumulative slice of ONE chronological FEFO allocation.
 */
internal object ShoppingNeedsKernel {
    data class Quantity(
        val amount: Long?, val unit: String?, val basis: String?,
        val packageCount: Long? = 1, val confirmed: Boolean = false
    )
    data class Lot(
        val lotId: String?, val foodTypeId: String?, val quantity: Quantity?,
        val usableUntil: String?, val safe: Boolean?, val available: Boolean?
    )
    data class Demand(
        val eventId: String?, val foodTypeId: String?, val dueDate: String?,
        val quantity: Quantity?, val kind: String? = null
    )
    data class Actual(
        val eventId: String?, val foodTypeId: String?, val date: String,
        val quantity: Quantity?, val confirmed: Boolean?, val kind: String? = null
    )
    data class Snapshot(
        val asOf: String, val stock: List<Lot>?, val demands: List<Demand>?,
        val consumptionHistory: List<Actual>? = null,
        val historyStart: String? = null, val historyUntil: String? = null
    )
    data class Need(
        val eventId: String, val foodTypeId: String, val dueDate: String,
        val quantityRequired: BigInteger, val quantityToBuy: BigInteger,
        val unit: String, val basis: String, val origin: String
    )
    data class UnknownNeed(
        val eventId: String, val foodTypeId: String?, val dueDate: String?,
        val quantityToBuy: BigInteger? = null,
        val reason: String = "confirmed comparable amount/type UNKNOWN"
    )
    data class Total(
        val foodTypeId: String, val unit: String, val basis: String,
        val quantityToBuy: BigInteger
    )
    data class View(val needs: List<Need>, val totals: List<Total>)
    data class Result(
        val asOf: String, val horizons: Map<Int, View>, val allocations: List<Need>,
        val unknownNeeds: List<UnknownNeed>, val warnings: List<String>,
        val snapshotComplete: Boolean, val sideEffects: List<String> = emptyList()
    )
    private data class Key(val foodTypeId: String, val unit: String, val basis: String)
    private data class Amount(val value: BigInteger, val unit: String, val basis: String)
    private data class UsableLot(val id: String, val until: LocalDate, var remaining: BigInteger)
    private data class Task(val id: String, val key: Key, val due: LocalDate,
                            val quantity: BigInteger, val origin: String)

    private fun day(value: String?): LocalDate {
        require(value != null && Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) {
            "date: YYYY-MM-DD required"
        }
        return try {
            val parsed = LocalDate.parse(value)
            require(parsed.toString() == value) { "date: YYYY-MM-DD required" }
            parsed
        } catch (_: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("date: YYYY-MM-DD required")
        }
    }

    private fun amount(q: Quantity?): Amount? {
        if (q?.confirmed != true || q.amount == null) return null
        require(q.amount > 0) { "quantity.amount: integer >= 1 required" }
        val count = q.packageCount ?: return null
        require(count > 0) { "packageCount: integer >= 1 required" }
        val unit = q.unit
        val basis = q.basis
        if (unit !in setOf("g", "ml", "piece") ||
            (unit == "piece" && basis != "PIECE") ||
            (unit != "piece" && basis !in setOf("NET", "DRAINED", "GROSS"))
        ) return null
        return Amount(BigInteger.valueOf(q.amount) * BigInteger.valueOf(count),
            unit!!, basis!!)
    }

    fun project(snapshot: Snapshot, horizons: List<Int> = listOf(1, 7, 28, 56)): Result {
        val asOf = day(snapshot.asOf)
        require(horizons.isNotEmpty() && horizons.all { it > 0 && it <= 366 }) {
            "positive bounded integer horizons required"
        }
        val maxH = horizons.max()
        val warnings = mutableSetOf<String>()
        val knownLots = mutableSetOf<String>()
        val lots = mutableMapOf<Key, MutableList<UsableLot>>()
        if (snapshot.stock == null) warnings.add("stock snapshot UNKNOWN")
        for (lot in snapshot.stock.orEmpty()) {
            val id = lot.lotId
            require(!id.isNullOrEmpty() && knownLots.add(id)) { "duplicate/missing lotId" }
            val qty = amount(lot.quantity)
            if (lot.foodTypeId.isNullOrEmpty() || qty == null) {
                warnings.add("lot $id: quantity/type UNKNOWN")
                continue
            }
            if (lot.safe != true || lot.available != true) {
                warnings.add("lot $id: not confirmed usable")
                continue
            }
            if (lot.usableUntil == null) {
                warnings.add("lot $id: usableUntil UNKNOWN")
                continue
            }
            lots.getOrPut(Key(lot.foodTypeId, qty.unit, qty.basis)) { mutableListOf() }
                .add(UsableLot(id, day(lot.usableUntil), qty.value))
        }
        lots.values.forEach { it.sortWith(compareBy<UsableLot> { l -> l.until }.thenBy { l -> l.id }) }

        val tasks = mutableListOf<Task>()
        val usedIds = mutableSetOf<String>()
        val planDays = mutableSetOf<Pair<Key, LocalDate>>()
        val unknown = mutableListOf<UnknownNeed>()
        if (snapshot.demands == null) warnings.add("plan snapshot UNKNOWN")
        for (d in snapshot.demands.orEmpty()) {
            val id = d.eventId
            require(!id.isNullOrEmpty()) { "eventId required" }
            require(usedIds.add(id)) { "duplicate demand eventId" }
            if (d.kind in setOf("PORTION_CONSUMPTION", "COOKED_MEAL_CONSUMPTION")) continue
            val qty = amount(d.quantity)
            if (qty == null || d.foodTypeId.isNullOrEmpty()) {
                warnings.add("demand $id: amount/type UNKNOWN")
                unknown.add(UnknownNeed(id, d.foodTypeId, d.dueDate))
                continue
            }
            val due = day(d.dueDate)
            if (!due.isBefore(asOf) && due.isBefore(asOf.plusDays(maxH.toLong()))) {
                val key = Key(d.foodTypeId, qty.unit, qty.basis)
                tasks.add(Task(id, key, due, qty.value, "PLAN"))
                planDays.add(key to due)
            }
        }

        if (!snapshot.consumptionHistory.isNullOrEmpty()) {
            val start = day(snapshot.historyStart)
            val end = day(snapshot.historyUntil)
            val covered = ChronoUnit.DAYS.between(start, end) + 1
            if (covered < 7 || !end.isBefore(asOf)) {
                warnings.add("consumption baseline insufficient observation period")
            } else {
                val totals = mutableMapOf<Key, BigInteger>()
                val counts = mutableMapOf<Key, Int>()
                val ids = mutableSetOf<String>()
                for (actual in snapshot.consumptionHistory) {
                    require(!actual.eventId.isNullOrEmpty() && ids.add(actual.eventId)) {
                        "duplicate/missing historical eventId"
                    }
                    val date = day(actual.date)
                    require(!date.isBefore(start) && !date.isAfter(end)) {
                        "consumption event outside observation window"
                    }
                    val qty = amount(actual.quantity)
                    if (qty != null && actual.confirmed == true &&
                        !actual.foodTypeId.isNullOrEmpty() &&
                        actual.kind !in setOf("PORTION_CONSUMPTION", "COOKED_MEAL_CONSUMPTION")
                    ) {
                        val key = Key(actual.foodTypeId, qty.unit, qty.basis)
                        totals[key] = totals.getOrDefault(key, BigInteger.ZERO) + qty.value
                        counts[key] = counts.getOrDefault(key, 0) + 1
                    }
                }
                for ((key, total) in totals.toSortedMap(compareBy<Key> { it.foodTypeId }
                    .thenBy { it.unit }.thenBy { it.basis })) {
                    if (counts.getValue(key) < 3) {
                        warnings.add("${key.foodTypeId}: baseline requires >= 3 actual events")
                        continue
                    }
                    val days = BigInteger.valueOf(covered)
                    val daily = (total + days - BigInteger.ONE) / days
                    for (offset in 0 until maxH) {
                        val due = asOf.plusDays(offset.toLong())
                        if ((key to due) !in planDays) {
                            tasks.add(Task("baseline:${key.foodTypeId}:$due:${key.unit}:${key.basis}",
                                key, due, daily, "BASELINE"))
                        }
                    }
                }
            }
        }

        tasks.sortWith(compareBy<Task> { it.due }.thenBy { it.id })
        val allocations = tasks.map { task ->
            var remaining = task.quantity
            for (lot in lots[task.key].orEmpty()) {
                if (lot.until.isBefore(task.due)) continue
                val taken = remaining.min(lot.remaining)
                lot.remaining -= taken
                remaining -= taken
                if (remaining == BigInteger.ZERO) break
            }
            Need(task.id, task.key.foodTypeId, task.due.toString(),
                task.quantity, remaining, task.key.unit, task.key.basis, task.origin)
        }
        val views = sortedMapOf<Int, View>()
        for (h in horizons.toSet().sorted()) {
            val bound = asOf.plusDays(h.toLong())
            val rows = allocations.filter {
                day(it.dueDate).isBefore(bound) && it.quantityToBuy > BigInteger.ZERO
            }
            val sums = mutableMapOf<Key, BigInteger>()
            for (row in rows) {
                val key = Key(row.foodTypeId, row.unit, row.basis)
                sums[key] = sums.getOrDefault(key, BigInteger.ZERO) + row.quantityToBuy
            }
            val totals = sums.entries.sortedWith(compareBy<Map.Entry<Key, BigInteger>> {
                it.key.foodTypeId
            }.thenBy { it.key.unit }.thenBy { it.key.basis }).map {
                Total(it.key.foodTypeId, it.key.unit, it.key.basis, it.value)
            }
            views[h] = View(rows, totals)
        }
        val notes = warnings.sorted()
        return Result(snapshot.asOf, views, allocations, unknown.sortedBy { it.eventId },
            notes, notes.isEmpty())
    }
}
