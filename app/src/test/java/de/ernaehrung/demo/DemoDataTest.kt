package de.ernaehrung.demo

import de.ernaehrung.demo.data.*
import de.ernaehrung.demo.navigation.*
import org.junit.Assert.*
import org.junit.Test

class DemoDataTest {
    private val d = DemoRepository
    @Test fun productIdentityAndUnknownValuesSurviveZeroStock() {
        assertEquals(8, d.products.size)
        assertEquals(d.products.size, d.products.map { it.id }.toSet().size)
        assertTrue(d.products.all { it.id.startsWith("DEMO-") })
        val empty = d.product("DEMO-CHICK-001")
        assertEquals(0.0, empty.quantity, 0.0)
        assertNotNull(empty.nutrition.kcal)
        assertNull(d.tofu.nutrition.salt)
        assertEquals("Unbekannt", value(null))
        assertEquals("0 g", value(0.0))
        d.recipes.flatMap { it.ingredients }.forEach { assertNotNull(d.product(it.productId)) }
    }
    @Test fun needsAndBudgetAreInternallyConsistent() {
        d.needs.forEach { assertEquals(maxOf(0.0, it.required-d.product(it.id).quantity), it.missing, 0.0) }
        assertEquals(3, d.shopping.size)
        assertEquals(785, d.shoppingCost)
        assertEquals(2735, d.remaining)
        assertEquals(497, d.receipt.sumOf { it.price })
        assertEquals(1, d.receipt.count { it.id == null })
    }
    @Test fun plannedMealsDoNotEnterActualNutrition() {
        assertTrue(d.eaten.all { !it.planned })
        assertTrue(d.planned.all { it.planned })
        assertEquals(420, d.eaten.sumOf { it.kcal })
        assertEquals(20, d.eaten.sumOf { it.protein })
        assertEquals(420.0, d.dayNutrition.kcal!!, 0.0)
        assertEquals(8, d.measurements.size)
        assertEquals(d.measurements.size, d.weightCurve.size)
        assertEquals(80.2f, d.measurements.last().weight, .001f)
        assertNull(d.activities.last().calories)
    }
    @Test fun everySpecifiedScreenHasAValidTab() {
        val expected = listOf("S","P","L","E","T","R","G","H","I","M","K").flatMap { p -> (1..3).map { p+"0"+it } }.toSet()
        assertEquals(expected, Screen.entries.filter { it != Screen.MORE }.map { it.name }.toSet())
        assertEquals(5, MainTab.entries.size)
        MainTab.entries.forEach { assertEquals(it, Screen.valueOf(it.root).tab) }
    }
}
