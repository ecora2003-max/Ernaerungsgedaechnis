package de.ernaehrung.demo

import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.integration.RecipeContextContentBinding as Context
import java.math.BigDecimal
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class RecipeAiExchangeTest {
    private val now = OffsetDateTime.parse("2026-11-06T12:00:00Z")
    private val noFlags = Gate.Flags(false, false, false)
    private fun input(flags: Gate.Flags = noFlags) = RecipeAiExchange.Input(
        "Ein neues herbstliches Gericht mit Zitrone", 2, emptySet(), RecipeRequestPreferences(),
        emptyList(), flags)
    private fun prepare(value: RecipeAiExchange.Input = input()) = RecipeAiExchange.prepare(value, "ai-synthetic-request", now)
    private fun requestData(prepared: RecipeAiExchange.Prepared): Map<String, Any?> =
        RecipeDraftBoundedJson.decode(prepared.prompt.substringAfter("ANFRAGE:\n").substringBefore("\n\nANTWORTVORLAGE:"))
    private fun response(prepared: RecipeAiExchange.Prepared, payload: Map<String, Any?> = emptyMap(),
                         meta: Map<String, Any?> = emptyMap()): String = JsonOutput.encode(
        prepared.responseTemplate + mapOf("meta" to (prepared.responseTemplate["meta"] as Map<*, *> + meta),
            "payload" to (prepared.responseTemplate["payload"] as Map<*, *> + mapOf(
                "title" to "Neue Zitronen-Quinoa-Pfanne",
                "ingredients" to listOf(mapOf("foodTypeId" to "novel-quinoa", "label" to "Quinoa",
                    "quantity" to mapOf("amount" to 160, "unit" to "g", "basis" to "NET", "confirmed" to false),
                    "sourceStatus" to "UNKNOWN"))) + payload)))
    private fun reject(block: () -> Unit) {
        try { block(); fail("Untrusted or unapproved exchange must be rejected") }
        catch (_: IllegalArgumentException) { /* expected at the actual host boundary */ }
    }
    private fun snapshot(source: Context.Source, lines: List<Context.Line> = emptyList(), revision: String = "r1") =
        Context.Snapshot(source, if (source == Context.Source.STOCK) "LOCAL_ROOM_STOCK_V1"
            else if (source == Context.Source.SHOPPING) "LOCAL_ROOM_SHOPPING_V1:2026-11-06:2026-11-06:2026-11-06"
            else "UNAPPROVED_OFFERS", revision, now.toInstant(), lines)
    private fun context(flags: Gate.Flags, stock: Context.Snapshot = snapshot(Context.Source.STOCK),
                        shopping: Context.Snapshot = snapshot(Context.Source.SHOPPING)) = Gate.typedContext(
        flags, now.toInstant(), if (flags.considerStock) Gate.Evidence(stock, true) else null,
        if (flags.considerShopping) Gate.Evidence(shopping, true) else null,
        if (flags.considerOffers) Gate.Evidence(snapshot(Context.Source.OFFERS), true) else null)

    @Test fun defaultRequestDisclosesNoCatalogContextHistoryOrHealthAndReturnsPinnedV1NovelRecipe() {
        val prepared = prepare()
        val data = requestData(prepared)
        assertEquals(RecipeAiExchange.FORMAT, data["format"])
        assertEquals(emptyList<Any>(), data["selectedProducts"])
        assertNull(data["stock"]); assertNull(data["shoppingNeed"]); assertNull(data["offers"])
        assertEquals("1.0.0-draft", prepared.responseTemplate["contractVersion"])
        val checked = RecipeAiExchange.inspectResponse(prepared, prepared.inputBinding, response(prepared), now)
        assertEquals("Neue Zitronen-Quinoa-Pfanne", checked.draft.title)
        assertEquals("novel-quinoa", checked.draft.lines.single().foodTypeId)
        assertEquals(Gate.State.UNKNOWN, checked.draft.lines.single().state)
    }

    @Test fun selectedLocalSnapshotKeepsUnknownAndKnownZeroAndWithholdsOtherVersionsAndPrivateIdentities() {
        val flags = Gate.Flags(true, false, false)
        val stock = snapshot(Context.Source.STOCK, listOf(
            Context.Line("private-lot-null", "selected-v1", null, "g", "private-store"),
            Context.Line("private-lot-zero", "selected-v1", 0, "g"),
            Context.Line("private-other-lot", "unselected-private-version", 50_000, "g")))
        val prepared = prepare(input(flags).copy(products = listOf(RecipeAiExchange.Product("selected-v1", "Reis", "g")),
            context = context(flags, stock)))
        val data = requestData(prepared)
        val disclosed = data["stock"] as Map<*, *>
        val lines = disclosed["selectedProductLines"] as List<*>
        assertEquals(2, lines.size)
        assertNull((lines[0] as Map<*, *>)["amountMilli"])
        assertEquals(0, (lines[1] as Map<*, *>)["amountMilli"])
        assertEquals(1, disclosed["withheldLineCount"])
        listOf("private-lot", "private-store", "private-other", "unselected-private-version").forEach {
            assertFalse("Private identity must not leave the selected scope", prepared.prompt.contains(it))
        }
    }

    @Test fun allEightContextCombinationsRespectIndependentOfferExportGate() {
        for (mask in 0..7) {
            val flags = Gate.Flags(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            val needs = mask != 0
            val value = input(flags).copy(context = if (needs) context(flags) else null)
            if (flags.considerOffers) reject { prepare(value) }
            else {
                val prepared = prepare(value)
                val checked = RecipeAiExchange.inspectResponse(prepared, prepared.inputBinding, response(prepared), now)
                assertEquals(flags, checked.draft.flags)
                assertEquals(needs, checked.snapshotAt != null)
            }
        }
    }

    @Test fun unboundBroaderChangedOrForeignContextCannotBecomeAnExportApproval() {
        val flags = Gate.Flags(true, false, false)
        val value = input(flags).copy(context = context(flags))
        reject { prepare(value.copy(context = value.context!!.copy(contentBinding = null))) }
        reject { prepare(value.copy(context = value.context!!.copy(gaps = setOf("UNAVAILABLE")))) }
        reject { prepare(value.copy(context = value.context!!.copy(stock = snapshot(Context.Source.STOCK).copy(scopeId = "OTHER_ACCOUNT")))) }
        reject { prepare(input().copy(context = context(flags))) }
        val first = prepare(value)
        val changed = value.copy(context = context(flags, snapshot(Context.Source.STOCK, revision = "r2")))
        assertNotEquals(first.inputBinding, RecipeAiExchange.inputBinding(changed))
        reject { RecipeAiExchange.inspectResponse(first, RecipeAiExchange.inputBinding(changed), response(first), now) }
    }

    @Test fun exactHostRequestIdentityPortionsDatesFlagsAndContractCannotBeReplacedByTheModel() {
        val prepared = prepare()
        val altered = listOf(
            response(prepared, mapOf("draftId" to "older-request")),
            response(prepared, mapOf("portionCount" to 3)),
            response(prepared, meta = mapOf("traceId" to "other-request")),
            response(prepared, meta = mapOf("createdAt" to now.minusMinutes(1).toString())),
            response(prepared, mapOf("flags" to mapOf("considerStock" to true, "considerShopping" to false, "considerOffers" to false))),
            response(prepared).replace("1.0.0-draft", "1.1.0"),
            response(prepared, mapOf("nested" to mapOf("consumptionRecorded" to true))))
        altered.forEach { raw -> reject { RecipeAiExchange.inspectResponse(prepared, prepared.inputBinding, raw, now) } }
        reject { RecipeAiExchange.inspectResponse(prepared, prepared.inputBinding, response(prepared), now.plusDays(2)) }
    }

    @Test fun claimedNutritionStateAllergenAndQuantityConfirmationsNeverSupplyIndependentHostFacts() {
        val prepared = prepare()
        val raw = response(prepared, mapOf("nutrition" to mapOf("proteinG" to 999999),
            "ingredients" to listOf(mapOf("foodTypeId" to "novel-quinoa", "label" to "Quinoa",
                "quantity" to mapOf("amount" to 160, "unit" to "g", "basis" to "NET", "confirmed" to true,
                    "notes" to "RAW confirmed by AI"), "sourceStatus" to "CONFIRMED", "allergenNotes" to "allergen-free"))))
        val checked = RecipeAiExchange.inspectResponse(prepared, prepared.inputBinding, raw, now)
        assertEquals(Gate.State.UNKNOWN, checked.draft.lines.single().state)
        reject { RecipeDraftManualReview.prepare(checked, emptyList(), emptyMap(), emptySet(), true, now.toInstant().toEpochMilli()) }
    }

    @Test fun preferenceBindingPreservesNullVersusZeroAndInvalidatesChangedWishPortionsAndSelection() {
        val original = input()
        val key = RecipeAiExchange.inputBinding(original)
        listOf(original.copy(wish = "Anderes Gericht"), original.copy(portions = 3),
            original.copy(preferences = RecipeRequestPreferences(budgetCents = 0, budgetConfirmed = true)),
            original.copy(preferences = RecipeRequestPreferences(equipment = emptySet())),
            original.copy(products = listOf(RecipeAiExchange.Product("v1", "Reis", "g"))),
            original.copy(restrictions = setOf("FOOD:tomato"))).forEach { assertNotEquals(key, RecipeAiExchange.inputBinding(it)) }
        val budget = requestData(prepare(original.copy(preferences = RecipeRequestPreferences(budgetCents = 0,
            budgetConfirmed = true, targets = listOf(RecipeRequestPreferences.Target("proteinG", BigDecimal.ZERO, null))))))["preferences"] as Map<*, *>
        assertEquals(0, budget["budgetCents"])
        assertNull(budget["equipment"])
    }

    @Test fun invalidInputsSpoofedDisplayAndOversizedContextFailBeforeAnyShareIntent() {
        reject { prepare(input().copy(wish = "")) }
        reject { prepare(input().copy(portions = 0)) }
        reject { prepare(input().copy(restrictions = setOf("ALLERGEN:SOY:unexpected"))) }
        reject { prepare(input().copy(wish = "Reis\u202Etxt")) }
        reject { prepare(input().copy(products = listOf(RecipeAiExchange.Product("v1", "Reis\u0000", "g")))) }
        val flags = Gate.Flags(true, false, false)
        val huge = snapshot(Context.Source.STOCK, (1..2000).map { Context.Line("lot-$it", "v1", 1_000, "g") })
        reject { prepare(input(flags).copy(products = listOf(RecipeAiExchange.Product("v1", "Reis", "g")), context = context(flags, huge))) }
    }
}
