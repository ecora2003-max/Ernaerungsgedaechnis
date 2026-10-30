package de.ernaehrung.demo

import de.ernaehrung.demo.data.local.*
import org.junit.Assert.*
import org.junit.Test

class AmountsTest {
    @Test fun zeroCanRepresentCountedEmptyStockButNeverAnEventQuantity() {
        assertEquals(0L, Amounts.parseCount("0"))
        assertEquals(0L, Amounts.parseCount("0,000"))
        assertEquals(1250L, Amounts.parseCount("1,25"))
        assertEquals(Amounts.MAX, Amounts.parseCount("1000000000"))
        for (text in listOf("", "-1", "1.0001", "NaN", "1000000001")) {
            assertThrows(IllegalArgumentException::class.java) { Amounts.parseCount(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { Amounts.parse("0") }
    }
    @Test fun decimalQuantitiesAndConversionsStayExact() {
        assertEquals(1234L, Amounts.parse("1,234"))
        assertEquals(1L, Amounts.parse("0.001"))
        assertEquals(1_500_000L, Amounts.convert("1,5", "kg", BaseUnit.GRAM))
        assertEquals(250_000L, Amounts.convert("0.25", "l", BaseUnit.MILLILITRE))
        assertEquals(600_000L, Amounts.convert("3", "Packung", BaseUnit.GRAM, 200_000))
        assertEquals("-1,234", Amounts.format(-1234))
        assertEquals(Amounts.MAX, Amounts.parse("1000000000"))
    }
    @Test fun unknownConversionsAndInvalidQuantitiesAreRejected() {
        for (text in listOf("", "0", "-1", "1.0001", "NaN", "Infinity", "1e3", "1.000,5", "1000000001")) {
            assertThrows(IllegalArgumentException::class.java) { Amounts.parse(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { Amounts.convert("1", "Packung", BaseUnit.GRAM) }
        assertThrows(IllegalArgumentException::class.java) { Amounts.convert("1", "ml", BaseUnit.GRAM) }
    }
}
