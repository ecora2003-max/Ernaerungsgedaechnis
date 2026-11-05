package de.ernaehrung.demo

import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.integration.RecipeContextContentBinding as Binding
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.integration.RecipeReferenceV1
import de.ernaehrung.demo.data.local.*
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test

/** Actual host DTOs enter the unchanged reference core; no model facts trusted. */
class RecipeReferenceV1Test {
    private val now = OffsetDateTime.parse("2026-11-04T06:00:00Z")
    private val flags = Gate.Flags(false, false, false)
    private fun version(id: String, unit: BaseUnit, energy: Double?, protein: Double?, basis: Long = 100_000L) =
        ProductVersionEntity(id, "product-$id", 1, id, null, null, null, unit, null, basis,
            energy, protein, 0.0, null, source = Source.USER, sourceDetail = null,
            quality = Quality.CONFIRMED, recordedAt = now.toInstant().toEpochMilli() - 1_000L,
            changeReason = "synthetic exact version")
    private val rice get() = version("rice-v1", BaseUnit.GRAM, 175.0, 3.5, 50_000L)
    private val drink get() = version("drink-v1", BaseUnit.MILLILITRE, 20.0, null)
    private fun host(id: String, version: ProductVersionEntity) = Gate.HostLine(
        id, version.id, true, version.baseUnit, Gate.Basis.NET, true, Gate.State.RAW, true,
        allergens = mapOf("SOY" to false))
    private fun facts() = mapOf("rice" to host("rice", rice), "drink" to host("drink", drink))
    private fun json(selected: Gate.Flags = flags, suppliedEnergy: BigDecimal? = null): String = JsonOutput.encode(
        linkedMapOf("contractVersion" to "1.0.0-draft", "messageType" to "recipe.draft",
            "meta" to mapOf("producer" to "synthetic-untrusted", "createdAt" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "traceId" to "host-reference"),
            "payload" to linkedMapOf("draftId" to "host-reference", "title" to "Untrusted Rice and Drink", "portionCount" to 2,
                "ingredients" to listOf("rice" to "g", "drink" to "ml").mapIndexed { index, (id, unit) ->
                    mapOf("foodTypeId" to id, "label" to id, "sourceStatus" to "UNKNOWN",
                        "quantity" to mapOf("amount" to if (index == 0) 150L else 200L,
                            "unit" to unit, "basis" to "NET", "confirmed" to false))
                }, "steps" to listOf("Prepare safely"), "warnings" to emptyList<String>(), "status" to "DRAFT",
                "flags" to mapOf("considerStock" to selected.considerStock,
                    "considerShopping" to selected.considerShopping, "considerOffers" to selected.considerOffers),
                "nutrition" to suppliedEnergy?.let { mapOf("total" to mapOf("energyKcal" to it)) })))

    @Test fun exactVersionBasisAndUnitsDetermineNutritionWhileModelClaimsRemainUntrusted() {
        val report = RecipeReferenceV1.revalidate(json(suppliedEnergy = BigDecimal("999999")),
            Gate.Request(flags, 2), facts(), now, listOf(rice, drink,
                rice.copy(id = "rice-v2", revision = 2, energyKcal = 9999.0)))
        assertTrue(report.blockers.isEmpty())
        assertEquals(565.0, requireNotNull(report.totals["energyKcal"]).toDouble(), 0.0001)
        assertEquals(282.5, requireNotNull(report.perPortion["energyKcal"]).toDouble(), 0.0001)
        assertNull(report.totals["proteinG"])
        assertEquals(10.5, requireNotNull(report.partial["proteinG"]).toDouble(), 0.0001)
        assertEquals(0, requireNotNull(report.totals["fatG"]).signum())
        assertNull(report.totals["vitaminB12Ug"])
        assertTrue("GENERATED_CANDIDATE_UNVERIFIED" in report.findings)
        assertTrue("ALLERGEN_DATA_GAP" in report.findings) // SOY=false is not comprehensive evidence.
    }

    @Test fun unconfirmedStateOrUnitMismatchCannotCreateCompatibleNutrientFacts() {
        val unknown = facts() + ("rice" to host("rice", rice).copy(state = null, stateConfirmed = false))
        val result = RecipeReferenceV1.revalidate(json(), Gate.Request(flags, 2), unknown, now, listOf(rice, drink))
        assertNull(result.totals["energyKcal"])
        assertEquals(40.0, requireNotNull(result.partial["energyKcal"]).toDouble(), 0.0001)
        val mismatched = facts() + ("rice" to host("rice", rice).copy(unit = BaseUnit.MILLILITRE))
        assertNull(RecipeReferenceV1.revalidate(json(), Gate.Request(flags, 2), mismatched,
            now, listOf(rice, drink)).totals["energyKcal"])
    }

    @Test fun confirmedFoodAndAllergenConflictsUseTheOfficialCaseInsensitiveChecks() {
        val food = RecipeReferenceV1.revalidate(json(), Gate.Request(flags, 2, setOf("FOOD:RICE")),
            facts(), now, listOf(rice, drink))
        assertTrue("FOOD_FORBIDDEN" in food.blockers)
        val present = facts() + ("rice" to host("rice", rice).copy(allergens = mapOf("soy" to true)))
        val allergen = RecipeReferenceV1.revalidate(json(), Gate.Request(flags, 2, setOf("ALLERGEN:SOY")),
            present, now, listOf(rice, drink))
        assertTrue("ALLERGEN_CONFLICT" in allergen.blockers)
    }

    @Test fun eachOfTheEightFlagsProjectsOnlyItsApprovedSourceAndShoppingNeverBecomesStock() {
        val at = now.toInstant()
        fun snapshot(source: Binding.Source, amount: Long) = Binding.Snapshot(source, "scope", "revision", at,
            listOf(Binding.Line("line-${source.name}", rice.id, amount, "g")))
        for (mask in 0..7) {
            val selected = Gate.Flags(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            val context = Gate.context(selected,
                Gate.Evidence(snapshot(Binding.Source.STOCK, 50_000L), true),
                Gate.Evidence(snapshot(Binding.Source.SHOPPING, 30_000L), true),
                Gate.Evidence(snapshot(Binding.Source.OFFERS, 100_000L), true))
            val report = RecipeReferenceV1.revalidate(json(selected), Gate.Request(selected, 2), facts(), now,
                listOf(rice, drink), context)
            val row = report.ingredientContexts.single { it["foodTypeId"] == "rice" }
            assertEquals(selected.considerStock, row.containsKey("stockAvailable"))
            assertEquals(selected.considerShopping, row.containsKey("plannedShoppingQuantity"))
            if (selected.considerStock) {
                assertEquals(50L, row["stockAvailable"])
                assertEquals(100L, row["remainingNeed"])
            }
            if (selected.considerShopping) assertEquals(30L, row["plannedShoppingQuantity"])
            assertFalse(row.containsKey("validOfferCount")) // No price/validity DTO, never invent it.
            assertEquals(selected.considerOffers,
                report.contextNotes.any { it.startsWith("OFFERS_SNAPSHOT_UNAVAILABLE") })
        }
    }

    @Test fun fractionalMilliQuantitiesStayUnknownInTheWholeUnitReferencePort() {
        val selected = Gate.Flags(true, false, false)
        val stock = Binding.Snapshot(Binding.Source.STOCK, "scope", "revision", now.toInstant(),
            listOf(Binding.Line("rice-batch", rice.id, 50_500L, "g")))
        val report = RecipeReferenceV1.revalidate(json(selected), Gate.Request(selected, 2), facts(), now,
            listOf(rice, drink), Gate.context<Binding.Snapshot, Binding.Snapshot, Binding.Snapshot>(
                selected, Gate.Evidence(stock, true), null, null))
        val row = report.ingredientContexts.single { it["foodTypeId"] == "rice" }
        assertNull(row["stockAvailable"])
        assertNull(row["remainingNeed"])
        assertTrue(report.contextNotes.any { it == "STOCK_AMOUNT_UNKNOWN:rice" })
    }
}
