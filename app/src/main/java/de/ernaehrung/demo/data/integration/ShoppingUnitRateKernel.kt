package de.ernaehrung.demo.data.integration

import java.math.BigInteger

/**
 * Pure arithmetic port of EG-Einkaufsintelligenz@a3e13b1, eg_shopping.engine._quantity/_rate.
 * Not a price-source, product-match, rights, retailer, validity, or purchase authorization.
 * Null means unknown, never zero; all comparisons require identical confirmed unit and basis.
 */
internal object ShoppingUnitRateKernel {
    data class Quantity(
        val amount: Long?, val unit: String?, val basis: String?,
        val packageCount: Long?, val confirmed: Boolean
    )
    data class ConfirmedQuantity(
        val amount: BigInteger, val unit: String, val quantityBasis: String
    )
    data class Rate(
        val numerator: BigInteger, val denominator: BigInteger,
        val displayBasis: String, val unit: String, val quantityBasis: String
    ) {
        init { require(denominator > BigInteger.ZERO && numerator >= BigInteger.ZERO) }
        fun milliCents(): BigInteger =
            (numerator * BigInteger.valueOf(2000) + denominator) /
                (denominator * BigInteger.valueOf(2))
    }

    fun confirmedQuantity(value: Quantity?): ConfirmedQuantity? {
        if (value == null || !value.confirmed || value.amount == null ||
            value.packageCount == null) return null
        require(value.amount > 0 && value.packageCount > 0) { "positive confirmed amount/count required" }
        val valid = when (value.unit) {
            "g", "ml" -> value.basis in setOf("NET", "DRAINED", "GROSS")
            "piece" -> value.basis == "PIECE"
            else -> false
        }
        if (!valid) return null
        return ConfirmedQuantity(
            BigInteger.valueOf(value.amount) * BigInteger.valueOf(value.packageCount),
            value.unit!!, value.basis!!
        )
    }

    fun rate(
        priceCents: Long?, currency: String?, quantity: Quantity?,
        shippingCents: Long? = null, includeShipping: Boolean = false
    ): Rate? {
        val confirmed = confirmedQuantity(quantity) ?: return null
        if (priceCents == null) return null
        require(priceCents >= 0) { "priceCents must be nonnegative" }
        require(currency == "EUR") { "only EUR supported" }
        if (includeShipping && shippingCents == null) return null
        if (includeShipping) require(shippingCents!! >= 0) { "shippingCents must be nonnegative" }
        val totalPrice = BigInteger.valueOf(priceCents) +
            (if (includeShipping) BigInteger.valueOf(shippingCents!!) else BigInteger.ZERO)
        val multiplier = if (confirmed.unit == "piece") BigInteger.ONE else BigInteger.valueOf(1000)
        val n = totalPrice * multiplier
        val d = confirmed.amount
        val gcd = n.gcd(d)
        return Rate(n / gcd, d / gcd,
            when (confirmed.unit) { "g" -> "kg"; "ml" -> "l"; else -> "piece" },
            confirmed.unit, confirmed.quantityBasis)
    }

    fun compare(left: Rate, right: Rate): Int? {
        if (left.unit != right.unit || left.quantityBasis != right.quantityBasis) return null
        return (left.numerator * right.denominator)
            .compareTo(right.numerator * left.denominator)
    }

    /** Candidate cost minus target cost for precisely the target's confirmed quantity. */
    fun targetEquivalentDifferenceCents(
        target: Rate, candidate: Rate, targetQuantity: Quantity?
    ): BigInteger? {
        val qty = confirmedQuantity(targetQuantity) ?: return null
        if (target.unit != qty.unit || target.quantityBasis != qty.quantityBasis ||
            compare(target, candidate) == null) return null
        val multiplier = if (qty.unit == "piece") BigInteger.ONE else BigInteger.valueOf(1000)
        val numerator = (candidate.numerator * target.denominator -
            target.numerator * candidate.denominator) * qty.amount
        val denominator = candidate.denominator * target.denominator * multiplier
        val parts = numerator.divideAndRemainder(denominator)
        return if (parts[1] == BigInteger.ZERO) parts[0] else null
    }
}
