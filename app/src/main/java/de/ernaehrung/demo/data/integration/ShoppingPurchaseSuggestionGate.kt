package de.ernaehrung.demo.data.integration

import java.math.BigInteger
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Non-booking host composition of the pinned FEFO need projection, independently
 * authorized in-store price observations and bounded per-food-type pack search.
 *
 * This is intentionally stricter than Python suggest_purchases: it never uses an
 * unverified price as a fallback. Inputs called "host" must come from independent
 * local registries, not from the price feed. No Room, FoodStore, network or UI writes.
 */
internal object ShoppingPurchaseSuggestionGate {
    data class Offer(
        val input: ShoppingPriceComparisonGate.Input,
        val shelfLifeDays: Long? = null,
        val storageClass: String? = null,
        val packageVolumeMl: Long? = null,
        val minPacks: Long = 1,
        val maxPacks: Long? = null,
        /** Independent host attestation, not a source-provided coupon flag. */
        val multibuyTermsConfirmedByHost: Boolean = false
    )
    data class Budget(
        val amountCents: Long?, val periodStart: LocalDate?, val periodEnd: LocalDate?
    )
    data class Line(
        val observationId: String, val productVersionId: String,
        val storeId: String, val packs: Long, val quantityBought: BigInteger,
        val merchandiseCents: BigInteger, val depositCents: BigInteger,
        val checkoutCents: BigInteger
    )
    data class Row(
        val foodTypeId: String, val unit: String, val basis: String,
        val status: String, val lines: List<Line>,
        val merchandiseCents: BigInteger?, val depositCents: BigInteger?,
        val checkoutCents: BigInteger?, val reasons: List<String>,
        val rejected: Map<String, String>, val optimalityProven: Boolean
    )
    data class Result(
        val rows: List<Row>, val knownMerchandiseSubtotalCents: BigInteger,
        val totalMerchandiseCents: BigInteger?,
        val knownCheckoutSubtotalCents: BigInteger,
        val totalCheckoutCents: BigInteger?,
        val budgetRemainingCents: BigInteger?,
        val issues: List<String>, val snapshotComplete: Boolean,
        val optimizationScope: String = "PER_FOODTYPE_SEQUENTIAL",
        val sideEffects: List<String> = emptyList()
    ) {
        val marketWideBestPriceProven: Boolean get() = false
    }

    private data class Key(val foodTypeId: String, val unit: String, val basis: String)
    private data class Verified(
        val offer: Offer, val visible: ExternalPriceBridge.VisiblePrice,
        val quantity: Long
    )
    private val zero = BigInteger.ZERO
    private fun big(value: Long) = BigInteger.valueOf(value)

    fun suggest(
        needs: ShoppingNeedsKernel.Result,
        horizon: Int,
        shoppingDate: LocalDate,
        asOf: OffsetDateTime,
        offers: List<Offer>,
        budget: Budget? = null,
        capacityMl: Map<String, Long?>? = null,
        permissions: ExternalPriceBridge.DisplayPermissionRegistry = ExternalPriceBridge.denyAll,
        stores: ShoppingOfferGate.ConfirmedStoreRegistry = ShoppingOfferGate.denyAllStores,
        articles: ShoppingPriceComparisonGate.ConfirmedArticleRegistry =
            ShoppingPriceComparisonGate.denyAllArticles
    ): Result {
        require(LocalDate.parse(needs.asOf) == shoppingDate) {
            "need snapshot and shopping date differ"
        }
        // A later evidence cutoff could retroactively authorize prices which
        // were not knowable on the shopping day (historical hindsight).
        require(!asOf.toLocalDate().isAfter(shoppingDate)) {
            "evidence cutoff after shopping date"
        }
        val view = requireNotNull(needs.horizons[horizon]) { "unprojected need horizon" }
        require(offers.size <= 256) { "purchase candidate limit exceeded" }
        val ids = offers.map { it.input.candidate.observationId }
        require(ids.all { it.isNotBlank() } && ids.size == ids.toSet().size) {
            "unique nonempty price observation IDs required"
        }
        capacityMl?.values?.forEach { require(it == null || it >= 0) }
        budget?.amountCents?.let { require(it >= 0) }
        val budgetValid = budget?.amountCents != null &&
            budget.periodStart != null && budget.periodEnd != null &&
            !budget.periodStart.isAfter(shoppingDate) &&
            !budget.periodEnd.isBefore(shoppingDate) &&
            !budget.periodStart.isAfter(budget.periodEnd)
        var remainingBudget: BigInteger? =
            if (budgetValid) big(requireNotNull(budget?.amountCents)) else null
        val remainingCapacity = capacityMl?.toMutableMap()
        val snapshotComplete = needs.snapshotComplete &&
            needs.unknownNeeds.isEmpty() && needs.warnings.isEmpty()
        val issues = mutableSetOf<String>()
        if (!snapshotComplete) issues.add("need/stock snapshot incomplete")
        if (!budgetValid) issues.add("budget or budget period UNKNOWN")
        if (capacityMl == null) issues.add("storage capacity UNKNOWN")

        var knownMerchandise = zero
        var knownCheckout = zero
        var allMerchandiseKnown = snapshotComplete
        var allCheckoutKnown = snapshotComplete
        var allFeasible = snapshotComplete && budgetValid && capacityMl != null
        // Per-food-type budgets and capacity are sequential. A review-only row
        // is not a confirmed allocation; later rows cannot claim feasibility
        // against a hypothetical remainder until the earlier row is resolved.
        var priorRowNeedsReview = false
        val rows = mutableListOf<Row>()
        val orderedTotals = view.totals.sortedWith(
            compareBy<ShoppingNeedsKernel.Total> { it.foodTypeId }
                .thenBy { it.unit }.thenBy { it.basis }
        )
        for (total in orderedTotals) {
            val key = Key(total.foodTypeId, total.unit, total.basis)
            val rejected = sortedMapOf<String, String>()
            val valid = mutableListOf<Verified>()
            // Different confirmed quantity bases are separate needs, not rejected competitors.
            // Keep malformed/unconfirmed quantities in scope so they still fail closed.
            val matching = offers.filter { offer ->
                if (offer.input.article.foodTypeId != key.foodTypeId) false
                else {
                    val q = try {
                        ShoppingUnitRateKernel.confirmedQuantity(offer.input.article.quantity)
                    } catch (_: IllegalArgumentException) { null }
                    q == null || (q.unit == key.unit && q.quantityBasis == key.basis)
                }
            }
            for (offer in matching) {
                val input = offer.input
                val id = input.candidate.observationId
                val reason = when {
                    input.request.purchaseOn != shoppingDate ||
                        input.request.asOf.toInstant() != asOf.toInstant() ->
                        "shopping date/evidence cutoff mismatch"
                    input.candidate.observedAt.toLocalDate().isAfter(shoppingDate) ->
                        "price observation postdates shopping day"
                    offer.minPacks <= 0 || (offer.maxPacks != null && offer.maxPacks < 0) ->
                        "invalid package count"
                    offer.minPacks > 1 && !offer.multibuyTermsConfirmedByHost ->
                        "multibuy terms not independently confirmed"
                    else -> null
                }
                if (reason != null) {
                    rejected[id] = reason
                    continue
                }
                val confirmed = try {
                    articles.isConfirmed(input.request.productVersionId, input.article,
                        input.organic, asOf)
                } catch (_: RuntimeException) { false }
                if (!confirmed || input.request.productVersionId.isBlank() ||
                    input.request.productVersionId != input.candidate.productVersionId) {
                    rejected[id] = "host product/version identity not confirmed"
                    continue
                }
                val quantity = try {
                    ShoppingUnitRateKernel.confirmedQuantity(input.article.quantity)
                } catch (_: IllegalArgumentException) { null }
                if (quantity == null || quantity.unit != key.unit ||
                    quantity.quantityBasis != key.basis ||
                    quantity.amount > big(Long.MAX_VALUE)) {
                    rejected[id] = "confirmed quantity/unit/basis mismatch"
                    continue
                }
                val gate = try {
                    ShoppingOfferGate.evaluate(input.request, input.candidate, input.facts,
                        permissions, stores)
                } catch (_: IllegalArgumentException) { null }
                val visible = gate?.offer
                val expectedUnit = when (visible?.unit) {
                    ExternalPriceBridge.Unit.GRAM -> "g"
                    ExternalPriceBridge.Unit.MILLILITRE -> "ml"
                    ExternalPriceBridge.Unit.PORTION -> "piece"
                    null -> null
                }
                if (gate?.eligible != true || visible == null) {
                    rejected[id] = "store/source-rights/price/validity gate rejected"
                    continue
                }
                if (expectedUnit != quantity.unit ||
                    big(visible.quantityMilli) != quantity.amount * big(1000)) {
                    rejected[id] = "observation and host quantity disagree"
                    continue
                }
                if (visible.depositCents == null || visible.checkoutCents == null) {
                    rejected[id] = "deposit/checkout cash UNKNOWN"
                    continue
                }
                valid.add(Verified(offer, visible, quantity.amount.toLong()))
            }
            val due = view.needs.filter {
                it.foodTypeId == key.foodTypeId && it.unit == key.unit &&
                    it.basis == key.basis && it.quantityToBuy > zero
            }.maxOfOrNull { LocalDate.parse(it.dueDate) }
            val reasons = mutableSetOf<String>()
            if (priorRowNeedsReview)
                reasons.add("earlier need unresolved; sequential budget/capacity provisional")
            if (rejected.isNotEmpty()) reasons.add("incomplete authorized candidate coverage")
            if (due == null) reasons.add("last demand date UNKNOWN")
            if (budgetValid && due != null && due.isAfter(requireNotNull(budget?.periodEnd))) {
                reasons.add("budget period does not cover last demand")
            }
            if (total.quantityToBuy <= zero || total.quantityToBuy > big(Long.MAX_VALUE)) {
                reasons.add("positive bounded need quantity required")
            }
            if (valid.isEmpty()) reasons.add("no independently authorized complete checkout price")
            if (reasons.any { it == "positive bounded need quantity required" } ||
                valid.isEmpty()) {
                rows.add(Row(key.foodTypeId, key.unit, key.basis, "REVIEW",
                    emptyList(), null, null, null, reasons.sorted(), rejected, false))
                issues.add("total cost UNKNOWN: " + key.foodTypeId)
                allMerchandiseKnown = false
                allCheckoutKnown = false
                allFeasible = false
                priorRowNeedsReview = true
                continue
            }
            val candidates = valid.map {
                val c = it.offer
                ShoppingPackMixKernel.Candidate(
                    it.visible.observationId, it.quantity,
                    requireNotNull(it.visible.checkoutCents),
                    minPacks = c.minPacks, maxPacks = c.maxPacks,
                    shelfLifeDays = c.shelfLifeDays, storageClass = c.storageClass,
                    packageVolumeMl = c.packageVolumeMl,
                    priceKind = c.input.facts.priceKind,
                    validFrom = c.input.candidate.validFrom?.toString(),
                    validUntil = c.input.candidate.validUntil?.toString(),
                    availability = c.input.facts.availability,
                    conditional = c.input.facts.conditional
                )
            }
            val plan = ShoppingPackMixKernel.optimize(
                total.quantityToBuy.toLong(), candidates, shoppingDate.toString(),
                due?.toString(),
                remainingBudget?.max(zero)?.min(big(Long.MAX_VALUE))?.toLong(),
                remainingCapacity
            )
            reasons.addAll(plan.warnings)
            val byId = valid.associateBy { it.visible.observationId }
            val lines = plan.items.map { item ->
                val selected = requireNotNull(byId[item.productId])
                val merch = big(selected.visible.merchandiseCents) * big(item.packs)
                val deposit = big(requireNotNull(selected.visible.depositCents)) * big(item.packs)
                Line(item.productId, selected.visible.productVersionId,
                    requireNotNull(selected.visible.storeId), item.packs,
                    item.quantityBought, merch, deposit, merch + deposit)
            }
            val merchandise = if (plan.costCents == null) null
                else lines.fold(zero) { sum, line -> sum + line.merchandiseCents }
            val deposits = if (plan.costCents == null) null
                else lines.fold(zero) { sum, line -> sum + line.depositCents }
            val checkout = if (merchandise != null && deposits != null)
                merchandise + deposits else null
            if (checkout != null && checkout != plan.costCents)
                reasons.add("optimizer checkout reconciliation mismatch")
            val safeCheckout = checkout?.takeIf { it == plan.costCents }
            if (merchandise == null || safeCheckout == null) {
                allMerchandiseKnown = false
                allCheckoutKnown = false
                issues.add("total cost UNKNOWN: " + key.foodTypeId)
            } else {
                knownMerchandise += merchandise
                knownCheckout += safeCheckout
                remainingBudget = remainingBudget?.minus(safeCheckout)
                for ((storage, used) in plan.usedCapacityMl) {
                    val available = remainingCapacity?.get(storage)
                    if (available != null) {
                        remainingCapacity[storage] = (big(available) - used)
                            .max(zero).min(big(Long.MAX_VALUE)).toLong()
                    }
                }
            }
            val feasible = plan.status == "FEASIBLE_NOT_PURCHASED" &&
                reasons.isEmpty() && snapshotComplete && budgetValid &&
                safeCheckout != null && remainingBudget != null &&
                remainingBudget >= zero
            if (!feasible) {
                allFeasible = false
                priorRowNeedsReview = true
                issues.add("purchase proposal requires review: " + key.foodTypeId)
            }
            rows.add(Row(key.foodTypeId, key.unit, key.basis,
                if (feasible) "FEASIBLE_NOT_PURCHASED" else "REVIEW",
                lines, merchandise, deposits, safeCheckout, reasons.sorted(),
                rejected, plan.optimalityProven && feasible))
        }
        return Result(rows, knownMerchandise,
            knownMerchandise.takeIf { allMerchandiseKnown },
            knownCheckout, knownCheckout.takeIf { allCheckoutKnown },
            remainingBudget?.takeIf { allFeasible && it >= zero },
            issues.sorted(), snapshotComplete)
    }
}
