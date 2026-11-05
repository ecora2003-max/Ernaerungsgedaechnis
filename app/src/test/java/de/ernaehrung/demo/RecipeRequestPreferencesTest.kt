package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.math.BigDecimal
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class RecipeRequestPreferencesTest {
    private val now = OffsetDateTime.parse("2026-11-05T08:00:00Z")
    private val flags = Gate.Flags(false, false, false)

    @Test fun breakfastUsesTheOfficialTenMinuteTemplateWithMillilitresAndKeepsDraftUntrusted() {
        val preferences = RecipeRequestPreferences(mealType = "BREAKFAST", maxMinutes = 10, equipment = emptySet())
        val variant = RecipeOfflineDraftGenerator.nextAllowedVariant(0, emptySet(), preferences = preferences, portions = 2)
        assertEquals(27, variant)
        val json = RecipeOfflineDraftGenerator.generate(2, requireNotNull(variant), now, "breakfast", preferences = preferences)
        val imported = RecipeDraftV1Import.read(json, emptyMap(), now)
        assertEquals("Haferflocken-Bowl", imported.draft.title)
        val water = imported.draft.lines.single { it.foodTypeId == "water" }
        assertEquals(360L, water.amount)
        assertEquals(Gate.UnitCode.ML, water.unit)
        assertEquals(Gate.State.UNKNOWN, water.state)
        assertNull(RecipeDraftV1Import.review(json, Gate.Request(flags, 2, preferences = preferences),
            emptyMap(), emptyMap(), now).input)
    }

    @Test fun explicitEquipmentAndTimeLimitsFilterTheActualReferenceGenerator() {
        val main = RecipeRequestPreferences(mealType = "MAIN", maxMinutes = 30, equipment = setOf("HOB", "POT"))
        assertEquals(28, RecipeOfflineDraftGenerator.nextAllowedVariant(0, emptySet(), preferences = main))
        assertNull(RecipeOfflineDraftGenerator.nextAllowedVariant(0, emptySet(), preferences = main.copy(equipment = emptySet())))
        assertNull(RecipeOfflineDraftGenerator.nextAllowedVariant(0, emptySet(), preferences = main.copy(maxMinutes = 29)))
        assertNotNull(RecipeOfflineDraftGenerator.nextAllowedVariant(0, emptySet(), preferences = RecipeRequestPreferences()))
        assertTrue(runCatching { RecipeOfflineDraftGenerator.generate(1, 0, now, "wrong-time", preferences = main) }.isFailure)
    }

    @Test fun knownZeroCapacityBlocksFreezingAndZeroBudgetIsNeverClaimedCompliant() {
        val frozen = RecipeRequestPreferences(freezePortions = 1, freezerCapacityPortions = 0)
        assertNull(RecipeOfflineDraftGenerator.nextAllowedVariant(0, emptySet(), preferences = frozen, portions = 2))
        val budget = frozen.copy(freezerCapacityPortions = null, budgetCents = 0L, budgetConfirmed = true)
        val json = RecipeOfflineDraftGenerator.generate(2, 0, now, "unknown-cost", preferences = budget)
        val payload = RecipeDraftBoundedJson.decode(json)["payload"] as Map<*, *>
        val extension = payload["xRecipeKi"] as Map<*, *>
        assertNull(extension["budgetCompliance"])
        assertNull(extension["actualCostCents"])
        assertTrue((payload["warnings"] as List<*>).any { it.toString().startsWith("BUDGET_UNVERIFIED") })
        assertTrue((payload["warnings"] as List<*>).any { it.toString().startsWith("FREEZER_UNKNOWN") })
    }

    @Test fun nutrientTargetsUseExactIndependentVersionFactsAndUnknownValuesStayUnknown() {
        val prefs = RecipeRequestPreferences(targets = listOf(
            RecipeRequestPreferences.Target("energyKcal", BigDecimal("400"), BigDecimal("500")),
            RecipeRequestPreferences.Target("proteinG", BigDecimal.ZERO, null)))
        val json = RecipeOfflineDraftGenerator.generate(1, 0, now, "targets", preferences = prefs)
        val catalog = RecipeOfflineDraftGenerator.ingredientsFor(0).mapIndexed { i, ingredient ->
            ProductVersionEntity("v$i", "p$i", 1, ingredient.label, null, null, null,
                BaseUnit.GRAM, null, 100_000L, listOf(350.0, 100.0, 20.0)[i], null, null, null,
                source = Source.USER, sourceDetail = null, quality = Quality.CONFIRMED,
                recordedAt = now.toInstant().toEpochMilli() - 1000L, changeReason = "synthetic")
        }
        val facts = RecipeOfflineDraftGenerator.ingredientsFor(0).mapIndexed { i, ingredient ->
            ingredient.foodTypeId to Gate.HostLine(ingredient.foodTypeId, catalog[i].id, true, BaseUnit.GRAM,
                Gate.Basis.valueOf(ingredient.basis), true,
                if (ingredient.foodTypeId.startsWith("cooked-")) Gate.State.COOKED else Gate.State.RAW, true)
        }.toMap()
        val report = RecipeReferenceV1.revalidate(json, Gate.Request(flags, 1, preferences = prefs), facts, now, catalog)
        assertEquals("IN_RANGE", report.targets.single { it["nutrient"] == "energyKcal" }["status"])
        assertEquals("UNKNOWN", report.targets.single { it["nutrient"] == "proteinG" }["status"])
        assertTrue("TIME_UNKNOWN" in report.findings) // Untrusted JSON cannot prove actual preparation time.
        assertEquals(prefs, report.preferences)
    }

    @Test fun malformedTargetsCannotReachTheCoreAndCanonicalBindingKeepsUnknownDifferentFromZero() {
        val empty = RecipeRequestPreferences()
        assertNotEquals(empty.binding(), empty.copy(budgetCents = 0L).binding())
        assertNotEquals(empty.binding(), empty.copy(equipment = emptySet()).binding())
        val first = empty.copy(equipment = linkedSetOf("POT", "HOB"), targets = listOf(
            RecipeRequestPreferences.Target("fatG", BigDecimal("0.0"), null)))
        assertEquals(first.binding(), first.copy(equipment = linkedSetOf("HOB", "POT"), targets = listOf(
            RecipeRequestPreferences.Target("fatG", BigDecimal.ZERO, null))).binding())
        for (prefs in listOf(empty.copy(maxMinutes = 0), empty.copy(budgetConfirmed = true),
            empty.copy(freezePortions = 3), empty.copy(targets = listOf(
                RecipeRequestPreferences.Target("proteinG", BigDecimal.TEN, BigDecimal.ONE))))) {
            assertTrue(runCatching { prefs.validate(2) }.isFailure)
        }
    }
}
