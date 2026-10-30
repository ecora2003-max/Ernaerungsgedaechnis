package de.ernaehrung.demo.data.local

import java.math.BigDecimal
import java.math.BigInteger

/** Money is integer EUR cents. Unknown is nullable, never a numeric placeholder. */
object Money {
    const val MAX_CENTS = 1_000_000_000_000L
    fun valid(cents: Long): Long {
        require(cents in 0..MAX_CENTS) { "Geldbetrag außerhalb des zulässigen Bereichs." }
        return cents
    }
    fun parse(text: String): Long {
        val value = text.trim().replace(',', '.')
        require(value.matches(Regex("[0-9]+(\\.[0-9]{1,2})?"))) { "Eurobetrag mit höchstens zwei Nachkommastellen eingeben." }
        return try { valid(BigDecimal(value).movePointRight(2).longValueExact()) }
        catch (_: ArithmeticException) { throw IllegalArgumentException("Geldbetrag außerhalb des zulässigen Bereichs.") }
    }
    fun format(cents: Long): String = BigDecimal.valueOf(cents, 2).setScale(2).toPlainString().replace('.', ',') + " €"
}

data class PackCalculation(val count: Long, val amount: Long, val surplus: Long, val costCents: Long?)
object ShoppingMath {
    fun packs(required: Long, usablePackAmount: Long, priceCents: Long?): PackCalculation? {
        require(required > 0); Amounts.positive(usablePackAmount); priceCents?.let(Money::valid)
        val need = BigInteger.valueOf(required); val size = BigInteger.valueOf(usablePackAmount)
        val count = (need + size - BigInteger.ONE) / size
        val amount = count * size
        if (amount > BigInteger.valueOf(Long.MAX_VALUE)) return null
        val cost = priceCents?.let { count * BigInteger.valueOf(it) }
        return PackCalculation(count.toLong(), amount.toLong(), (amount - need).toLong(),
            cost?.takeIf { it <= BigInteger.valueOf(Money.MAX_CENTS) }?.toLong())
    }
}
data class PriceInput(val versionId: String, val merchant: String?, val observedOn: String,
    val amount: Long?, val totalCents: Long?, val wholePack: Boolean, val basisConfirmed: Boolean,
    val basisDescription: String?, val validFrom: String?, val validUntil: String?, val priceConfirmed: Boolean,
    val reason: String, val source: Source = Source.USER)
data class BudgetInput(val label: String, val startsOn: String, val endsOn: String,
    val amountCents: Long?, val confirmed: Boolean, val reason: String)
data class ShoppingScope(val startsOn: String, val endsOn: String, val purchaseOn: String, val budgetId: String? = null)
data class ShoppingMarkInput(val scope: ShoppingScope, val versionId: String, val offerId: String?,
    val checked: Boolean, val expectedSnapshot: String)
enum class ShoppingIssue(val label: String) {
    NO_OFFER("Keine Packungs-/Preisfassung gewählt"), OBSOLETE_OFFER("Gewählte Preisfassung wurde ersetzt; neu prüfen"),
    PACK_BASIS_UNKNOWN("Nutzbare Menge je ganzer Kaufpackung nicht bestätigt"),
    PRICE_UNKNOWN("Preis unbekannt"), PRICE_UNCONFIRMED("Preisangabe ungeprüft"),
    CURRENCY_UNKNOWN("Währung nicht als EUR geklärt"), PRICE_VALIDITY_UNKNOWN("Preisgültigkeit zum Einkauf ungeklärt"),
    PRICE_NOT_VALID("Preis gilt am geplanten Einkaufstag nicht"), UNKNOWN_REQUIREMENT("Weitere Bedarfsmenge ungeklärt"),
    PURCHASE_TOO_LATE("Einkaufstag liegt nach dem ersten Bedarfstermin"), NUMBER_RANGE("Berechnete Menge oder Kosten außerhalb des Zahlenbereichs")
}
data class ShoppingQuote(val offer: PriceObservationEntity?, val packsForKnownNeed: Long?, val exactPackCount: Long?,
    val purchasedAmount: Long?, val surplus: Long?, val costCents: Long?, val issues: Set<ShoppingIssue>)
data class ShoppingLine(val need: ShoppingNeed, val offer: PriceObservationEntity?, val alternatives: List<PriceObservationEntity>,
    val firstNeededOn: String?, val lastNeededOn: String?, val packsForKnownNeed: Long?, val exactPackCount: Long?,
    val purchasedAmount: Long?, val surplus: Long?, val calculatedCostCents: Long?, val issues: Set<ShoppingIssue>,
    val checked: Boolean, val staleCheck: Boolean, val snapshot: String, val contentToken: String,
    val quotes: List<ShoppingQuote> = emptyList())
data class ShoppingBudget(val record: BudgetEntity?, val usableLimitCents: Long?, val knownCostsOverLimitCents: Long?,
    val issue: String?, val remainingBudgetCents: Long? = null, val knownPaidCents: Long? = null,
    val paidTotalCents: Long? = null, val expensesThroughOn: String? = null, val afterPlannedCents: Long? = null)
data class ShoppingReport(val scope: ShoppingScope, val asOf: String, val items: List<ShoppingLine>,
    val prices: List<PriceObservationEntity>, val budgets: List<BudgetEntity>, val knownSubtotalCents: Long?,
    val estimatedTotalCents: Long?, val incomplete: Boolean, val budget: ShoppingBudget) {
    companion object { const val CALCULATION_VERSION = "shopping-1" }
}
