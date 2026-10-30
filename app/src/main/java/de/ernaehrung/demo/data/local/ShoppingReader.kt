package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Suggestions are derived from the same transaction as current plans/stock. No purchases or expenses are inferred. */
internal class ShoppingReader(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    fun watch(scope: ShoppingScope) = db.invalidationTracker.createFlow("price_observations", "budgets", "shopping_marks",
        "operations", "plans", "planned_entries", "plan_executions", "plan_positions", "product_versions", "recipe_versions",
        "recipe_ingredients", "batches", "stock_movements", "operation_reversals", "storage_locations", "consumption_events", "productions",
        "purchase_events", "receipts", "expense_status_events")
        .map { report(scope) }.distinctUntilChanged()

    suspend fun report(scope: ShoppingScope, choiceOverrides: Map<String, String?> = emptyMap()): ShoppingReport = db.withTransaction {
        listOf(scope.startsOn, scope.endsOn, scope.purchaseOn).forEach(WriteRules::date)
        require(ChronoUnit.DAYS.between(LocalDate.parse(scope.startsOn), LocalDate.parse(scope.endsOn)) in 0..55) { "Einkaufsausschnitt zwischen einem und 56 Tagen wählen." }
        val today = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        require(scope.purchaseOn >= today) { "Einkaufsplanung mit heutigem Bestand nicht auf einen vergangenen Einkaufstag rückdatieren." }
        val planning = PlanningReader(db, now).report(scope.startsOn, scope.endsOn)
        val prices = dao.prices(); val budgets = dao.budgets()
        val replacedPrices = prices.mapNotNull { it.supersedesId }.toSet()
        val replacedBudgets = budgets.mapNotNull { it.supersedesId }.toSet()
        val marks = dao.shoppingMarks(scope.startsOn, scope.endsOn, scope.purchaseOn).groupBy { it.versionId }.mapValues { (_, rows) -> rows.maxBy { it.revision } }
        // Marks are deliberately excluded: checking one item must not invalidate every other item or itself.
        val context = WriteRules.fingerprint("SHOPPING_CONTEXT_1", dao.shoppingContextOperations(), scope.startsOn, scope.endsOn,
            scope.purchaseOn, planning.asOf, planning.hasUnknownRequirements)
        val items = planning.shopping.map { need ->
            val relevant = planning.entries.filter { result -> result.demands.any {
                it.versionId == need.versionId && (it.required == null || (it.missing ?: 0) > 0)
            } }
            val first = relevant.minOfOrNull { it.entry.date }; val last = relevant.maxOfOrNull { it.entry.date }
            val alternatives = prices.filter { it.versionId == need.versionId && it.id !in replacedPrices }
                .sortedWith(compareBy({ it.merchant ?: "" }, { it.observedOn }, { it.id }))
            val mark = marks[need.versionId]
            val selectedId = if (choiceOverrides.containsKey(need.versionId)) choiceOverrides[need.versionId] else mark?.offerId
            val offer = prices.find { it.id == selectedId && it.versionId == need.versionId }
            val quotes = alternatives.map { quote(need, it, false, first, scope.purchaseOn) }
            val selected = quotes.find { it.offer?.id == selectedId } ?: quote(need, offer, selectedId != null && (offer == null || offer.id in replacedPrices), first, scope.purchaseOn)
            val token = WriteRules.fingerprint(ShoppingReport.CALCULATION_VERSION, context, need.versionId, need.unit, need.knownMissing,
                need.unknownRequirements, first, last, selectedId, offer?.toString(), selected.toString())
            val currentCheck = mark?.checked == true && mark.itemToken == token
            ShoppingLine(need, offer, alternatives, first, last, selected.packsForKnownNeed, selected.exactPackCount,
                selected.purchasedAmount, selected.surplus, selected.costCents, selected.issues, currentCheck,
                mark?.checked == true && !currentCheck,
                WriteRules.fingerprint("SHOPPING_APPROVAL_1", token, mark?.operationId, mark?.checked, mark?.itemToken), token, quotes)
        }
        val sum = items.fold(BigInteger.ZERO) { total, item -> total + BigInteger.valueOf(item.calculatedCostCents ?: 0) }
        val subtotal = sum.takeIf { it <= BigInteger.valueOf(Money.MAX_CENTS) }?.toLong()
        val incomplete = planning.hasUnknownRequirements || subtotal == null || items.any { it.issues.isNotEmpty() || it.calculatedCostCents == null }
        val total = subtotal.takeIf { !incomplete }
        val selectedBudget = budgets.find { it.id == scope.budgetId }
        val budgetIssue = when {
            selectedBudget == null -> "Budget nicht gewählt oder unbekannt"
            selectedBudget.id in replacedBudgets -> "Gewählte Budgetfassung wurde ersetzt"
            scope.purchaseOn !in selectedBudget.startsOn..selectedBudget.endsOn -> "Einkaufstag liegt außerhalb des Budgetzeitraums"
            selectedBudget.currency != "EUR" -> "Budgetwährung ungeklärt"
            selectedBudget.amountCents == null || selectedBudget.amountCents !in 0..Money.MAX_CENTS -> "Budgetbetrag ungeklärt"
            selectedBudget.quality != Quality.CONFIRMED -> "Budgetobergrenze nicht bestätigt"
            else -> null
        }
        val limit = selectedBudget?.amountCents?.takeIf { budgetIssue == null }
        val through = selectedBudget?.endsOn?.let { minOf(it, today) }
        val expenses = if (limit != null && through != null && selectedBudget.startsOn <= through &&
            ChronoUnit.DAYS.between(LocalDate.parse(selectedBudget.startsOn), LocalDate.parse(through)) <= 365)
            ExpenseReader(db, now).report(ExpenseScope(selectedBudget.startsOn, through)) else null
        val remaining = if (limit != null && expenses?.totalCents != null) limit - expenses.totalCents else null
        val knownPaid = expenses?.knownSubtotalCents
        ShoppingReport(scope, today, items, prices, budgets, subtotal, total, incomplete,
            ShoppingBudget(selectedBudget, limit,
                if (limit != null && subtotal != null && (expenses == null || knownPaid != null))
                    (subtotal + (knownPaid ?: 0L) - limit).coerceAtLeast(0) else null,
                budgetIssue, remaining, knownPaid, expenses?.totalCents, expenses?.scope?.endsOn,
                if (remaining != null && total != null) remaining - total else null))
    }

    private fun quote(need: ShoppingNeed, offer: PriceObservationEntity?, obsolete: Boolean, firstNeeded: String?, purchaseOn: String): ShoppingQuote {
        val issues = mutableSetOf<ShoppingIssue>()
        if (need.unknownRequirements > 0) issues += ShoppingIssue.UNKNOWN_REQUIREMENT
        if (firstNeeded != null && purchaseOn > firstNeeded) issues += ShoppingIssue.PURCHASE_TOO_LATE
        if (offer == null) issues += ShoppingIssue.NO_OFFER
        if (obsolete) issues += ShoppingIssue.OBSOLETE_OFFER
        val basis = offer != null && !obsolete && offer.wholePack == true && offer.basisConfirmed == true &&
            offer.amount != null && offer.amount in 1..Amounts.MAX && !offer.basisDescription.isNullOrBlank()
        if (!basis) issues += ShoppingIssue.PACK_BASIS_UNKNOWN
        if (offer?.totalCents == null) issues += ShoppingIssue.PRICE_UNKNOWN
        if (offer?.quality != Quality.CONFIRMED) issues += ShoppingIssue.PRICE_UNCONFIRMED
        if (offer?.currency != "EUR") issues += ShoppingIssue.CURRENCY_UNKNOWN
        val validity = offer?.validFrom != null && offer.validUntil != null
        if (!validity) issues += ShoppingIssue.PRICE_VALIDITY_UNKNOWN
        else if (purchaseOn !in offer!!.validFrom!!..offer.validUntil!!) issues += ShoppingIssue.PRICE_NOT_VALID
        if (offer?.totalCents != null && offer.totalCents !in 0..Money.MAX_CENTS) issues += ShoppingIssue.NUMBER_RANGE
        val priceUsable = !obsolete && offer != null && offer.totalCents != null && offer.totalCents in 0..Money.MAX_CENTS &&
            offer.quality == Quality.CONFIRMED && offer.currency == "EUR" && validity && purchaseOn in offer.validFrom!!..offer.validUntil!!
        val packs = if (basis && need.knownMissing > 0) ShoppingMath.packs(need.knownMissing, offer!!.amount!!, if (priceUsable) offer.totalCents else null) else null
        if (basis && need.knownMissing > 0 && packs == null || priceUsable && packs != null && packs.costCents == null) issues += ShoppingIssue.NUMBER_RANGE
        return ShoppingQuote(offer, packs?.count, packs?.count?.takeIf { need.unknownRequirements == 0 }, packs?.amount,
            packs?.surplus, packs?.costCents, issues)
    }
}
