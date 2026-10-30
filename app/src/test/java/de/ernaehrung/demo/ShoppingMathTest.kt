package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import org.junit.Assert.*
import org.junit.Test

class ShoppingMathTest {
    @Test fun wholePackRoundingIncludesOversupplyAndExactIntegerCents() {
        assertEquals(PackCalculation(2, 400_000, 50_000, 398), ShoppingMath.packs(350_000, 200_000, 199))
        assertEquals(PackCalculation(2, 400_000, 0, 398), ShoppingMath.packs(400_000, 200_000, 199))
        assertEquals(PackCalculation(3, 600_000, 199_999, 597), ShoppingMath.packs(400_001, 200_000, 199))
    }
    @Test fun unknownAndKnownFreePricesRemainDifferent() {
        assertNull(ShoppingMath.packs(350_000, 200_000, null)!!.costCents)
        assertEquals(0L, ShoppingMath.packs(350_000, 200_000, 0)!!.costCents)
        assertEquals(0L, Money.parse("0")); assertEquals(199L, Money.parse("1,99")); assertEquals("1,99 €", Money.format(199))
    }
    @Test fun invalidMoneyCannotBeRoundedOrSilentlyParsedAsZero() {
        for (value in listOf("", "-1", "1,999", "NaN", "1e3", "1.000,00", "€ 2", "10000000000.01")) {
            try { Money.parse(value); fail(value) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun arithmeticOverflowRemainsUnknown() {
        assertNull(ShoppingMath.packs(Long.MAX_VALUE, 200_000, 199))
        val huge = ShoppingMath.packs(Amounts.MAX, 1, Money.MAX_CENTS)!!
        assertEquals(Amounts.MAX, huge.count); assertNull(huge.costCents)
    }
}
