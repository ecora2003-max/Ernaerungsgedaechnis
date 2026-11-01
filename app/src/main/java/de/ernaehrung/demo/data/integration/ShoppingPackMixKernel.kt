package de.ernaehrung.demo.data.integration

import java.math.BigInteger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure, read-only bounded package-mix port of EG-Einkaufsintelligenz@a3e13b1
 * pack_optimizer.optimize_package_mix. Inputs are verified host facts, never
 * purchase instructions. Missing capacity, due date or budget cannot be FEASIBLE.
 */
internal object ShoppingPackMixKernel {
    data class Candidate(
        val productId: String, val amount: Long, val priceCents: Long,
        val minPacks: Long = 1, val maxPacks: Long? = null,
        val shelfLifeDays: Long? = null, val storageClass: String? = null,
        val packageVolumeMl: Long? = null, val priceKind: String? = null,
        val validFrom: String? = null, val validUntil: String? = null,
        val availability: String? = null, val conditional: Boolean = false,
        val shippingCents: Long? = null
    )
    data class Rejected(val productId: String, val reason: String)
    data class Item(val productId: String, val packs: Long,
                    val quantityBought: BigInteger, val costCents: BigInteger)
    data class Result(
        val status: String, val items: List<Item>, val costCents: BigInteger?,
        val quantityBought: BigInteger, val optimalityProven: Boolean,
        val rejected: List<Rejected>, val warnings: List<String>,
        val usedCapacityMl: Map<String, BigInteger> = emptyMap(),
        val scope: String? = null, val sideEffects: List<String> = emptyList()
    )
    private data class Entry(val candidate: Candidate, val max: Long)
    private data class Plan(
        val counts: List<Long>, val units: BigInteger, val cost: BigInteger,
        val used: Map<String, BigInteger>, val missing: Set<String>, val shelfIssue: Boolean,
        val overbuy: BigInteger, val packCount: BigInteger
    )
    private fun positive(value: Long, name: String, min: Long = 0) {
        require(value >= min) { "$name: integer >= $min required" }
    }
    private fun day(value: String): LocalDate = try {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value))
        LocalDate.parse(value).also { require(it.toString() == value) }
    } catch (_: Exception) {
        throw IllegalArgumentException("ISO shopping_date / last_due_date required")
    }
    private fun big(n: Long) = BigInteger.valueOf(n)
    private fun less(a: Plan, b: Plan?): Boolean {
        if (b == null) return true
        for ((x, y) in listOf(a.cost to b.cost, a.overbuy to b.overbuy,
            a.packCount to b.packCount)) {
            if (x != y) return x < y
        }
        for (i in a.counts.indices) {
            if (a.counts[i] != b.counts[i]) return a.counts[i] < b.counts[i]
        }
        return false
    }

    fun optimize(
        targetAmount: Long, candidates: List<Candidate>, shoppingDate: String,
        lastDueDate: String? = null, budgetCents: Long? = null,
        capacityMl: Map<String, Long?>? = null, maxNodes: Int = 250000
    ): Result {
        positive(targetAmount, "target_amount", 1)
        require(maxNodes > 0) { "max_nodes: integer >= 1 required" }
        val today = day(shoppingDate)
        val due = lastDueDate?.let(::day)
        budgetCents?.let { positive(it, "budget_cents") }
        capacityMl?.forEach { (key, value) ->
            value?.let { positive(it, "capacity[$key]") }
        }
        val rejected = mutableListOf<Rejected>()
        val seen = mutableSetOf<String>()
        val entries = mutableListOf<Entry>()
        for (c in candidates) {
            require(c.productId.isNotBlank()) { "productId required" }
            require(seen.add(c.productId)) { "duplicate productId: ${c.productId}" }
            positive(c.amount, "amount", 1)
            positive(c.priceCents, "priceCents")
            positive(c.minPacks, "minPacks", 1)
            val reason = when {
                c.conditional -> "unverified coupon or access condition"
                c.priceKind in setOf("REFERENCE_OLD", "HISTORICAL_PAID", "UNKNOWN") ->
                    "non-purchasable price kind"
                c.availability == "OUT_OF_STOCK_REPORTED" -> "out of stock"
                c.priceKind == "SALE" && (c.validFrom == null || c.validUntil == null) ->
                    "sale validity unknown"
                else -> null
            }
            if (reason != null) {
                rejected.add(Rejected(c.productId, reason))
                continue
            }
            val from = c.validFrom?.let(::day)
            val until = c.validUntil?.let(::day)
            require(from == null || until == null || !from.isAfter(until)) {
                "validFrom after validUntil"
            }
            val excluded = when {
                (from != null && today.isBefore(from)) || (until != null && today.isAfter(until)) ->
                    "price not valid on shopping date"
                c.priceKind == "ONLINE_LISTED" && c.shippingCents == null -> "shipping unknown"
                c.priceKind == "ONLINE_LISTED" -> "online order-level shipping not modeled"
                else -> null
            }
            if (excluded != null) {
                rejected.add(Rejected(c.productId, excluded))
                continue
            }
            c.maxPacks?.let { positive(it, "maxPacks") }
            c.shelfLifeDays?.let { positive(it, "shelfLifeDays") }
            c.packageVolumeMl?.let { positive(it, "packageVolumeMl", 1) }
            val required = (big(targetAmount) + big(c.amount) - BigInteger.ONE) / big(c.amount)
            val upper = required.max(big(c.minPacks)).min(big(c.maxPacks ?: Long.MAX_VALUE))
            // The reference has unbounded Python integers; never silently truncate a search.
            if (upper > big(Long.MAX_VALUE - 1)) {
                return Result("SEARCH_LIMIT", emptyList(), null, BigInteger.ZERO,
                    false, rejected, listOf("package count exceeds bounded host search"))
            }
            entries.add(Entry(c, upper.toLong()))
        }
        entries.sortBy { it.candidate.productId }
        if (entries.isEmpty()) return Result("NO_ELIGIBLE_PRICE", emptyList(), null,
            BigInteger.ZERO, false, rejected, listOf("no eligible priced packages"))
        if (entries.size > 8) return Result("SEARCH_LIMIT", emptyList(), null,
            BigInteger.ZERO, false, rejected,
            listOf("more than eight variants; exact search declined"))

        var strict: Plan? = null
        var fallback: Plan? = null
        var nodes = 0
        var exceeded = false
        val counts = LongArray(entries.size)
        val target = big(targetAmount)
        val daysUntilDue = due?.let { ChronoUnit.DAYS.between(today, it) }
        fun consider(units: BigInteger, cost: BigInteger, used: Map<String, BigInteger>,
                     missing: Set<String>, shelfIssue: Boolean) {
            if (units < target) return
            val plan = Plan(counts.toList(), units, cost, used, missing, shelfIssue,
                units - target, counts.fold(BigInteger.ZERO) { a, n -> a + big(n) })
            if (less(plan, fallback)) fallback = plan
            val budgetOk = budgetCents == null || cost <= big(budgetCents)
            val capacityOk = capacityMl != null && missing.isEmpty() &&
                used.all { (k, v) -> capacityMl[k]?.let { v <= big(it) } == true }
            if (budgetOk && capacityOk && due != null && !shelfIssue && less(plan, strict)) {
                strict = plan
            }
        }
        fun visit(index: Int, units: BigInteger, cost: BigInteger,
                  used: Map<String, BigInteger>, missing: Set<String>, shelfIssue: Boolean) {
            nodes++
            if (nodes > maxNodes) {
                exceeded = true
                return
            }
            val s = strict
            val f = fallback
            if (s != null && f != null && cost > s.cost && cost > f.cost) return
            if (index == entries.size) {
                consider(units, cost, used, missing, shelfIssue)
                return
            }
            val entry = entries[index]
            val c = entry.candidate
            fun branch(n: Long) {
                val newUsed = used.toMutableMap()
                val newMissing = missing.toMutableSet()
                var badShelf = shelfIssue
                if (n > 0) {
                    val storage = c.storageClass
                    val volume = c.packageVolumeMl
                    if (storage == null || volume == null || capacityMl?.get(storage) == null) {
                        newMissing.add(c.productId)
                    } else {
                        newUsed[storage] = newUsed.getOrDefault(storage, BigInteger.ZERO) +
                            big(volume) * big(n)
                    }
                    if (daysUntilDue == null || c.shelfLifeDays == null ||
                        c.shelfLifeDays < daysUntilDue) badShelf = true
                }
                counts[index] = n
                visit(index + 1, units + big(n) * big(c.amount),
                    cost + big(n) * big(c.priceCents), newUsed, newMissing, badShelf)
                counts[index] = 0
            }
            branch(0)
            var n = c.minPacks
            while (n <= entry.max && !exceeded) {
                branch(n)
                if (n == Long.MAX_VALUE) break
                n++
            }
        }
        visit(0, BigInteger.ZERO, BigInteger.ZERO, emptyMap(), emptySet(), false)
        val chosen = strict ?: fallback
        if (chosen == null) return Result(if (exceeded) "SEARCH_LIMIT" else "NO_FEASIBLE_MIX",
            emptyList(), null, BigInteger.ZERO, !exceeded, rejected,
            listOf("no bounded package combination proven"))
        val warnings = mutableSetOf<String>()
        if (exceeded) warnings.add("exact search limit reached; optimality not proven")
        if (chosen.missing.isNotEmpty() || capacityMl == null)
            warnings.add("available storage volume UNKNOWN")
        if (capacityMl != null && chosen.used.any { (k, v) ->
            capacityMl[k]?.let { v > big(it) } == true
        }) warnings.add("insufficient confirmed storage volume")
        if (due == null || chosen.shelfIssue)
            warnings.add("shelf life or due date UNKNOWN/insufficient")
        if (budgetCents == null) warnings.add("budget UNKNOWN")
        else if (chosen.cost > big(budgetCents)) warnings.add("budget exceeded")
        val items = entries.mapIndexedNotNull { i, e ->
            val n = chosen.counts[i]
            if (n == 0L) null else Item(e.candidate.productId, n,
                big(n) * big(e.candidate.amount), big(n) * big(e.candidate.priceCents))
        }
        return Result(
            if (strict != null && !exceeded && budgetCents != null)
                "FEASIBLE_NOT_PURCHASED" else "REVIEW",
            items, chosen.cost, chosen.units, !exceeded, rejected, warnings.sorted(),
            chosen.used, "EXACT_PER_FOODTYPE_ONLY"
        )
    }
}
