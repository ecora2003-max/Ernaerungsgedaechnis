package de.eg.recipe

import de.eg.recipe.RecipeEngine.Flags
import de.eg.recipe.RecipeEngine.Snapshot
import de.eg.recipe.RecipeEngine.OfferSnapshot
import de.eg.recipe.RecipeEngine.Offer
import de.eg.recipe.RecipeEngine.Restriction
import de.eg.recipe.RecipeEngine.Request
import de.eg.recipe.RecipeEngine.NutrientFact
import de.eg.recipe.RecipeEngine.UnitCode
import de.eg.recipe.RecipeEngine.Basis
import de.eg.recipe.RecipeEngine.State
import de.eg.recipe.RecipeEngine.Evidence
import de.eg.recipe.RecipeEngine.RecipeFacts
import de.eg.recipe.RecipeEngine.Quantity
import de.eg.recipe.RecipeEngine.Completeness
import de.eg.recipe.RecipeEngine.InventoryEntry
import de.eg.recipe.RecipeEngine.Validation
import de.eg.recipe.RecipeEngine.OfflineGenerator
import de.eg.recipe.RecipeEngine.Line
import de.eg.recipe.RecipeEngine.ProvidedCandidates
import de.eg.recipe.RecipeEngine.Target
import de.eg.recipe.RecipeEngine.offlineTemplates
import java.math.BigDecimal
import java.time.LocalDate

/** Standalone offline assertions; no JUnit, network, device, database or Maven required. */
object RecipeEngineTest {
    private var passed = 0
    private var failed = 0
    private fun checkCase(name: String, test: () -> Unit) {
        try { test(); passed++; println("PASS $name") }
        catch (e: Throwable) { failed++; println("FAIL $name ${e.message}"); e.printStackTrace() }
    }
    private fun d(s: String) = BigDecimal(s)
    private val date = LocalDate.parse("2026-11-04")
    private fun req(portions: Int = 2, flags: Flags = Flags(), stock: Snapshot? = null,
        shopping: Snapshot? = null, offers: OfferSnapshot? = null, mealType: String? = null,
        restrictions: List<Restriction> = emptyList(), targets: List<Target> = emptyList()) = Request(
        requestId = "TEST-01", createdAt = "2026-10-31T17:00:00+01:00", shoppingDate = date,
        portions = portions, flags = flags, stock = stock, shopping = shopping, offers = offers,
        mealType = mealType, equipment = setOf("HOB", "POT"), restrictions = restrictions, targets = targets
    )
    private val identifiers = listOf("oats", "apple", "water", "dry-lentils", "carrot", "dry-rice", "cooked-beans", "tomato")
    private fun known(id: String): NutrientFact {
        val amount = if (id == "oats") d("12") else if (id == "apple") d("0.3") else d("0")
        return NutrientFact(id, if (id == "water") UnitCode.ML else UnitCode.G,
            if (id == "cooked-beans") Basis.DRAINED else Basis.NET,
            if (id == "cooked-beans") State.COOKED else State.RAW,
            mapOf("energyKcal" to d("100"), "proteinG" to amount, "carbohydrateG" to d("1"), "fatG" to d("0"),
                "fiberG" to d("0"), "vitaminB12Ug" to null, "vitaminDUg" to null),
            emptySet(), Evidence.COMPLETE, setOf("VEGAN", "VEGETARIAN"), "synthetic-confirmed-food-profile", "SOURCED")
    }
    private fun facts() = RecipeFacts(identifiers.associateWith { known(it) })
    private fun q(n: Long?, basis: Basis = Basis.NET) = Quantity(n, UnitCode.G, basis, confirmed = true)
    private fun snap(id: String, n: Long? = 100, shared: Boolean = true): Snapshot = Snapshot(shared, Completeness.PARTIAL, "2026-10-31T17:00:00+01:00", listOf(InventoryEntry(id, q(n))), "synthetic-snapshot", "CONFIRMED")
    private fun offer(id: String, start: LocalDate = date, until: LocalDate = date, quality: String = "SOURCED") = OfferSnapshot(true, "2026-10-31T17:00:00+01:00", listOf(Offer(id, 139, "EUR", start, until, "synthetic-offer", quality)))
    private fun result(r: Request = req(), f: RecipeFacts = facts()) = RecipeEngine.propose(r, f)
    @Suppress("UNCHECKED_CAST")
    private fun payload(message: Map<String, Any?>) = message["payload"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun ext(message: Map<String, Any?>) = payload(message)["xRecipeKi"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun warnings(message: Map<String, Any?>) = payload(message)["warnings"] as List<String>
    private fun containsCode(v: Validation, code: String) = v.findings.any { it.code == code }
    private fun firstBreakfast(r: Request = req(mealType = "BREAKFAST"), f: RecipeFacts = facts()): Map<String, Any?> = result(r, f).messages.single()
    private fun getCandidate(r: Request = req(mealType = "BREAKFAST")) = OfflineGenerator().generate(r).single()
    fun run() {
        checkCase("default offline generator yields verifiable recipe drafts") {
            val actual = result(); assert(actual.messages.size == 3); assert(actual.rejected.isEmpty())
            assert(actual.messages.all { payload(it)["status"] == "DRAFT" })
        }
        checkCase("offline output has exact contract envelope & required fields") {
            val m = firstBreakfast(); assert(m["contractVersion"] == "1.0.0-draft"); assert(m["messageType"] == "recipe.draft")
            assert((payload(m)["ingredients"] as List<*>).size == 3); assert(JsonOutput.encode(m).startsWith("{"))
        }
        checkCase("8 independent flag combinations, no excluded snapshot influence") {
            for (bits in 0..7) {
                val flags = Flags(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
                val s = snap("oats"); val sh = snap("apple"); val o = offer("oats")
                val base = req(flags = flags, stock = s, shopping = sh, offers = o)
                val changed = req(flags = flags, stock = if (flags.considerStock) s else snap("dry-lentils", 999),
                    shopping = if (flags.considerShopping) sh else snap("dry-rice", 999),
                    offers = if (flags.considerOffers) o else offer("carrot"))
                val a = JsonOutput.encode(result(base).messages)
                val b = JsonOutput.encode(result(changed).messages)
                assert(a == b) { "inactive option affected messages for bits=$bits" }
                assert(result(base).messages.all { (payload(it)["flags"] as Map<*, *>) ["considerStock"] == flags.considerStock })
            }
        }
        checkCase("all 8 flag combinations serialized and distinct flag state") {
            val serialized = (0..7).map { n -> val f = Flags(n and 1 != 0,n and 2 != 0,n and 4 != 0); JsonOutput.encode(result(req(flags=f)).messages) }
            assert(serialized.distinct().size == 8)
        }
        checkCase("selected but missing snapshot reports unknown not empty") {
            val r = req(flags = Flags(true,true,true), mealType = "BREAKFAST")
            val w = warnings(firstBreakfast(r)); assert(w.any { "STOCK_SNAPSHOT_UNAVAILABLE" in it })
            assert(w.any { "SHOPPING_SNAPSHOT_UNAVAILABLE" in it }); assert(w.any { "OFFERS_SNAPSHOT_UNAVAILABLE" in it })
        }
        checkCase("unshared snapshots never exposed as known data") {
            val m = firstBreakfast(req(flags=Flags(true), stock=snap("oats",shared=false), mealType="BREAKFAST"))
            assert(warnings(m).any { it.contains("STOCK_SNAPSHOT_UNAVAILABLE") })
            assert(JsonOutput.encode(ext(m)).contains("STOCK_SNAPSHOT_UNAVAILABLE").not()) // notes are warnings only
        }
        checkCase("allergen conflict blocks candidate") {
            val f = facts().byFoodType.toMutableMap(); f["oats"] = known("oats").copy(allergens = setOf("GLUTEN"))
            val r = req(mealType="BREAKFAST",restrictions=listOf(Restriction("ALLERGEN:GLUTEN",true)))
            val out = result(r,RecipeFacts(f)); assert(out.messages.isEmpty()); assert(out.rejected.size == 1)
            assert(containsCode(out.rejected.single().second,"ALLERGEN_CONFLICT"))
        }
        checkCase("unknown allergen evidence is review-required not safe") {
            val f = facts().byFoodType.toMutableMap(); f["apple"] = known("apple").copy(allergenEvidence=Evidence.UNKNOWN)
            val m = firstBreakfast(req(mealType="BREAKFAST",restrictions=listOf(Restriction("ALLERGEN:NUTS",true))),RecipeFacts(f))
            assert(ext(m)["verification"] == "REVIEW_REQUIRED"); assert(warnings(m).any { it.contains("ALLERGEN_UNVERIFIED") })
        }
        checkCase("unknown allergen evidence without known restrictions still warns") {
            val f = facts().byFoodType.toMutableMap(); f["apple"] = known("apple").copy(allergenEvidence=Evidence.UNKNOWN)
            assert(warnings(firstBreakfast(f=RecipeFacts(f))).any { it.contains("ALLERGEN_DATA_GAP") })
        }
        checkCase("unconfirmed allergen restriction does not block") {
            val f = facts().byFoodType.toMutableMap(); f["oats"] = known("oats").copy(allergens=setOf("GLUTEN"))
            assert(result(req(mealType="BREAKFAST",restrictions=listOf(Restriction("ALLERGEN:GLUTEN",false))),RecipeFacts(f)).messages.size == 1)
        }
        checkCase("confirmed diet conflict blocks") {
            val f = facts().byFoodType.toMutableMap(); f["apple"] = known("apple").copy(dietTags=setOf("VEGETARIAN"))
            val r = req(mealType="BREAKFAST",restrictions=listOf(Restriction("DIET:VEGAN",true)))
            val out=result(r,RecipeFacts(f)); assert(out.messages.isEmpty()); assert(containsCode(out.rejected.single().second,"DIET_CONFLICT"))
        }
        checkCase("unknown diet not claimed compliant") {
            val f = facts().byFoodType.toMutableMap(); f["apple"] = known("apple").copy(dietTags=null)
            assert(warnings(firstBreakfast(req(mealType="BREAKFAST",restrictions=listOf(Restriction("DIET:VEGAN",true))),RecipeFacts(f))).any { "DIET_UNKNOWN" in it })
        }
        checkCase("2 vs 4 portions scale ingredients and exact nutrition") {
            val m2 = firstBreakfast(req(portions=2,mealType="BREAKFAST")); val m4 = firstBreakfast(req(portions=4,mealType="BREAKFAST"))
            val i2 = payload(m2)["ingredients"] as List<Map<String,Any?>>
            val i4 = payload(m4)["ingredients"] as List<Map<String,Any?>>
            assert((i2.first()["quantity"] as Map<*,*>)["amount"] == 140L)
            assert((i4.first()["quantity"] as Map<*,*>)["amount"] == 280L)
            val n2=payload(m2)["nutrition"] as Map<String,Any?>; val n4=payload(m4)["nutrition"] as Map<String,Any?>
            val t2=n2["total"] as Map<String,BigDecimal?>; val t4=n4["total"] as Map<String,BigDecimal?>
            assert(t2["proteinG"] == d("17.52")); assert(t4["proteinG"] == d("35.04"))
            assert((n2["perPortion"] as Map<String,BigDecimal?>)["proteinG"] == (n4["perPortion"] as Map<String,BigDecimal?>)["proteinG"])
        }
        checkCase("200g dry rice never substituted for 200g cooked rice nutritionally") {
            val original = getCandidate(); val dry = original.copy(ingredients=listOf(Line("dry-rice","Reis",q(200).copy(state=State.RAW))))
            val cooked = original.copy(ingredients=listOf(Line("dry-rice","Reis",q(200).copy(state=State.COOKED))))
            val f=RecipeFacts(mapOf("dry-rice" to known("dry-rice")))
            assert(RecipeEngine.estimateNutrition(dry,f).totals["energyKcal"]?.compareTo(d("200")) == 0)
            assert(RecipeEngine.estimateNutrition(cooked,f).totals["energyKcal"] == null)
            assert(RecipeEngine.estimateNutrition(cooked,f).gaps["energyKcal"]!!.any { "incompatible" in it })
        }
        checkCase("unknown ingredient quantity has unknown total not zero") {
            val t = offlineTemplates.first().copy(ingredients=offlineTemplates.first().ingredients.map { if(it.foodTypeId=="apple") it.copy(amountPerPortion=null) else it })
            val r = req(mealType="BREAKFAST"); val c=OfflineGenerator(listOf(t)).generate(r).single()
            val v=RecipeEngine.validate(c,r,facts()); assert(containsCode(v,"QUANTITY_UNKNOWN"))
            val n=RecipeEngine.estimateNutrition(c,facts()); assert(n.totals["proteinG"] == null)
            assert(n.partial["proteinG"] == d("16.8"))
        }
        checkCase("vitamin D and B12 missing while known macros preserved") {
            val n=RecipeEngine.estimateNutrition(getCandidate(),facts())
            assert(n.totals["proteinG"] != null && n.totals["vitaminB12Ug"] == null && n.totals["vitaminDUg"] == null)
            assert(n.partial["vitaminB12Ug"] == null)
        }
        checkCase("unknown nutrition provenance cannot count even numeric zero") {
            val f=facts().byFoodType.toMutableMap(); f["apple"]=known("apple").copy(dataQuality="UNKNOWN")
            val n=RecipeEngine.estimateNutrition(getCandidate(),RecipeFacts(f))
            assert(n.totals["proteinG"] == null)
        }
        checkCase("meal prep volume zero or not double-counted") {
            val m = firstBreakfast(req(portions=4,mealType="BREAKFAST"))
            val meal = ext(m)["mealPrep"] as Map<*,*>
            assert(meal["plannedPortions"] == 4 && meal["rawIngredientsCharged"] == false && meal["consumptionRecorded"] == false)
            assert((payload(m)["ingredients"] as List<*>).size == 3)
        }
        checkCase("unknown freezer capacity never automatically released") {
            val r=req(mealType="BREAKFAST").copy(freezePortions=2,freezerCapacityPortions=null)
            val m=firstBreakfast(r); assert(ext(m)["verification"] == "REVIEW_REQUIRED")
            assert(warnings(m).any { "FREEZER_UNKNOWN" in it })
        }
        checkCase("insufficient freezer capacity blocks") {
            val r=req(mealType="BREAKFAST").copy(freezePortions=2,freezerCapacityPortions=1)
            val out=result(r); assert(out.messages.isEmpty()); assert(containsCode(out.rejected.single().second,"FREEZER_INSUFFICIENT"))
        }
        checkCase("best before date is a check not automatic spoilage") {
            val snapshot=Snapshot(true,Completeness.PARTIAL,"2026-10-31T17:00:00+01:00",listOf(InventoryEntry("oats",q(200),date.minusDays(1))), "synthetic-snapshot", "CONFIRMED")
            val m=firstBreakfast(req(mealType="BREAKFAST",flags=Flags(true),stock=snapshot))
            assert(warnings(m).any { "BEST_BEFORE_REVIEW" in it })
            assert(payload(m)["status"] == "DRAFT")
        }
        checkCase("shopping list is planned not available stock") {
            val m=firstBreakfast(req(mealType="BREAKFAST",flags=Flags(true,true),stock=snap("oats",10),shopping=snap("oats",200)))
            val item=(ext(m)["ingredientsContext"] as List<Map<String,Any?>>).first()
            assert(item["stockAvailable"] == 10L && item["remainingNeed"] == 130L && item["plannedShoppingQuantity"] == 200L)
        }
        checkCase("upcoming offer only within valid future interval") {
            val soon = offer("oats",date.plusDays(1),date.plusDays(8))
            val m=firstBreakfast(req(mealType="BREAKFAST",flags=Flags(considerOffers=true),offers=soon))
            val item=(ext(m)["ingredientsContext"] as List<Map<String,Any?>>).first(); assert(item["validOfferCount"] == 0)
            val valid=firstBreakfast(req(mealType="BREAKFAST",flags=Flags(considerOffers=true),offers=offer("oats")))
            val vi=(ext(valid)["ingredientsContext"] as List<Map<String,Any?>>).first(); assert(vi["validOfferCount"] == 1)
        }
        checkCase("offer with missing terms not counted") {
            val malformed=OfferSnapshot(true,"2026-10-31T17:00:00+01:00",listOf(Offer("oats",null,"EUR",date,date,"synthetic", "SOURCED")))
            val m=firstBreakfast(req(mealType="BREAKFAST",flags=Flags(considerOffers=true),offers=malformed))
            assert((ext(m)["ingredientsContext"] as List<Map<String,Any?>>).first()["validOfferCount"] == 0)
        }
        checkCase("undated shared stock is unavailable, never silently treated as zero") {
            val s = snap("oats").copy(asOf=null)
            val m=firstBreakfast(req(mealType="BREAKFAST",flags=Flags(considerStock=true),stock=s))
            assert(warnings(m).any { it.contains("STOCK_SNAPSHOT_UNAVAILABLE") })
            assert((ext(m)["ingredientsContext"] as List<Map<String,Any?>>).first()["stockAvailable"] == null)
        }
        checkCase("budget not asserted when costs absent") {
            val r=req(mealType="BREAKFAST").copy(budgetConfirmed=true,budgetCents=50)
            val m=firstBreakfast(r); assert(ext(m)["budgetCompliance"] == null)
            assert(warnings(m).any { "BUDGET_UNVERIFIED" in it })
        }
        checkCase("unconfirmed nutrition target ignored") {
            val t=listOf(Target("proteinG",d("80"),d("100"),false))
            assert(JsonOutput.encode(firstBreakfast()) == JsonOutput.encode(firstBreakfast(req(mealType="BREAKFAST",targets=t))))
        }
        checkCase("confirmed nutrition target evaluated against per-portion amount") {
            val m=firstBreakfast(req(mealType="BREAKFAST",targets=listOf(Target("proteinG",d("20"),d("40"),true))))
            assert(warnings(m).any { "TARGET_BELOW:proteinG" in it })
            assert((ext(m)["targetsEvaluation"] as List<Map<String,Any?>>).single()["status"] == "BELOW")
        }
        checkCase("unsupported nutrient target unknown rather than 0") {
            val m=firstBreakfast(req(mealType="BREAKFAST",targets=listOf(Target("seleniumUg",d("1"),null,true))))
            assert((ext(m)["targetsEvaluation"] as List<Map<String,Any?>>).single()["status"] == "UNKNOWN")
        }
        checkCase("invalid confirmed target bounds explicit failure") {
            val r=req(targets=listOf(Target("proteinG",d("50"),d("10"),true)))
            assert(runCatching { result(r) }.isFailure)
        }
        checkCase("time restriction blocks slow recipes") {
            val r=req(mealType="MAIN").copy(maxMinutes=10)
            assert(result(r).messages.isEmpty()); assert(result(r).rejected.size == 2)
        }
        checkCase("equipment absence blocks candidates") {
            val r=req(mealType="MAIN").copy(equipment=emptySet())
            assert(result(r).messages.isEmpty()); assert(result(r).rejected.size == 2)
        }
        checkCase("contradictory quantity rejected") {
            val c=getCandidate(); val changed=c.copy(ingredients=c.ingredients.mapIndexed { idx,it -> if (idx==0) it.copy(quantity=it.quantity.copy(amount=200)) else it })
            assert(containsCode(RecipeEngine.validate(changed,req(mealType="BREAKFAST"),facts()),"CONTRADICTORY_AMOUNT"))
        }
        checkCase("contradictory basis rejected") {
            val c=getCandidate(); val changed=c.copy(ingredients=c.ingredients.mapIndexed { idx,it -> if (idx==0) it.copy(quantity=it.quantity.copy(basis=Basis.PIECE)) else it })
            val v=RecipeEngine.validate(changed,req(mealType="BREAKFAST"),facts())
            assert(containsCode(v,"UNIT_BASIS") && containsCode(v,"CONTRADICTORY_AMOUNT"))
        }
        checkCase("negative/zero quantity blocked") {
            val c=getCandidate(); val neg=c.copy(ingredients=c.ingredients.mapIndexed { idx,it -> if (idx==0) it.copy(quantity=it.quantity.copy(amount=-1)) else it })
            assert(containsCode(RecipeEngine.validate(neg,req(mealType="BREAKFAST"),facts()),"QUANTITY_NONPOSITIVE"))
        }
        checkCase("provided model candidate cannot bypass validator") {
            val r=req(mealType="BREAKFAST",restrictions=listOf(Restriction("ALLERGEN:GLUTEN",true)))
            val f=facts().byFoodType.toMutableMap(); f["oats"]=known("oats").copy(allergens=setOf("GLUTEN"))
            val out=RecipeEngine.propose(r,RecipeFacts(f),ProvidedCandidates(listOf(getCandidate(r))))
            assert(out.messages.isEmpty() && out.rejected.size == 1)
        }
        checkCase("provided untrusted generator never marked verified") {
            val r=req(mealType="BREAKFAST")
            val out=RecipeEngine.propose(r,facts(),ProvidedCandidates(listOf(getCandidate(r))))
            assert(ext(out.messages.single())["verification"] == "REVIEW_REQUIRED")
        }
        checkCase("state is pure: repeated propose identical without mutable inventory") {
            val r=req(mealType="BREAKFAST",flags=Flags(true),stock=snap("oats",150))
            val original = JsonOutput.encode(result(r).messages)
            repeat(10) { assert(JsonOutput.encode(result(r).messages)==original) }
        }
        checkCase("json escapes control characters and Unicode") {
            val s = JsonOutput.encode(mapOf("test" to "a\"\\\nü\u0000"))
            assert(s == "{\"test\":\"a\\\"\\\\\\nü\\u0000\"}")
        }
        checkCase("invalid request portions and budget never silently corrected") {
            assert(runCatching { result(req(portions=0)) }.isFailure)
            assert(runCatching { result(req().copy(budgetCents=-1)) }.isFailure)
        }
        checkCase("no unconfirmed production or consumption event emitted") {
            val data = JsonOutput.encode(result().messages)
            assert(!data.contains("foodStore")); assert(!data.contains("purchaseConfirmed")); assert(data.contains("\"consumptionRecorded\":false"))
        }
        checkCase("confirmed food-type prohibition blocks") {
            val r=req(mealType="BREAKFAST",restrictions=listOf(Restriction("FOOD:oats",true)))
            val out=result(r)
            assert(out.messages.isEmpty())
            assert(containsCode(out.rejected.single().second,"FOOD_FORBIDDEN"))
        }
        checkCase("unsupported confirmed restriction fails closed") {
            val r=req(mealType="BREAKFAST",restrictions=listOf(Restriction("MEDICAL:DIABETES",true)))
            assert(containsCode(result(r).rejected.single().second,"RESTRICTION_UNSUPPORTED"))
        }
        checkCase("estimated and negative nutrient values cannot yield validated totals") {
            val f=facts().byFoodType.toMutableMap()
            f["oats"] = known("oats").copy(dataQuality="ESTIMATED")
            assert(RecipeEngine.estimateNutrition(getCandidate(),RecipeFacts(f)).totals["proteinG"] == null)
            f["oats"] = known("oats").copy(per100=known("oats").per100 + ("proteinG" to d("-4")))
            assert(RecipeEngine.estimateNutrition(getCandidate(),RecipeFacts(f)).totals["proteinG"] == null)
        }
        checkCase("corrupt negative snapshot does not create phantom stock") {
            val bad=snap("oats",-10)
            val m=firstBreakfast(req(mealType="BREAKFAST",flags=Flags(true),stock=bad))
            assert((ext(m)["ingredientsContext"] as List<Map<String,Any?>>).first()["stockAvailable"] == null)
            assert(warnings(m).any { "STOCK_AMOUNT_UNKNOWN" in it })
        }
        checkCase("arithmetic overflow in stock sum becomes unknown") {
            val q1=q(Long.MAX_VALUE); val q2=q(1)
            val s=Snapshot(true,Completeness.PARTIAL,"2026-10-31T17:00:00+01:00",
                listOf(InventoryEntry("oats",q1),InventoryEntry("oats",q2)),"synthetic","CONFIRMED")
            val m=firstBreakfast(req(mealType="BREAKFAST",flags=Flags(true),stock=s))
            assert((ext(m)["ingredientsContext"] as List<Map<String,Any?>>).first()["remainingNeed"] == null)
        }
        checkCase("provided candidate source is marked untrusted") {
            val r=req(mealType="BREAKFAST")
            val m=RecipeEngine.propose(r,facts(),ProvidedCandidates(listOf(getCandidate(r)))).messages.single()
            assert(ext(m)["generator"] == "UNTRUSTED_PROVIDED")
        }
        println("SUMMARY passed=$passed failed=$failed")
        if (failed > 0) error("$failed failed tests")
    }
}
fun main() { RecipeEngineTest.run() }
