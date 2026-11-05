package de.eg.recipe

import java.time.LocalDate
import java.math.BigDecimal

/** Synthetic JVM checks for untrusted import -> deterministic validation gate. */
object RecipeDraftRevalidationTest {
    private val date=LocalDate.parse("2026-11-04")
    private fun request(flags:RecipeEngine.Flags=RecipeEngine.Flags())=RecipeEngine.Request(
        "REVALIDATION","2026-10-31T17:00:00+01:00",date,2,flags,
        mealType="MAIN",equipment=setOf("HOB","POT"))
    private fun original(r:RecipeEngine.Request=request()):Map<String,Any?> =
        RecipeEngine.propose(r,RecipeEngine.RecipeFacts(emptyMap())).messages.first()
    private fun fact(id:String)=RecipeEngine.NutrientFact(
        id,RecipeEngine.UnitCode.G,RecipeEngine.Basis.NET,RecipeEngine.State.RAW,
        mapOf("proteinG" to BigDecimal("5")),setOf("GLUTEN"),RecipeEngine.Evidence.COMPLETE,
        setOf("VEGAN"),"synthetic","SOURCED")
    private fun expectInvalid(fn:()->Unit){var bad=false;try{fn()}catch(_:IllegalArgumentException){bad=true};check(bad)}
    @JvmStatic fun main(args:Array<String>){
        var n=0
        fun test(name:String,fn:()->Unit){fn();n++;println("PASS $name")}
        test("imported draft never enters trusted verification state") {
            val v=RecipeDraftRevalidation.revalidate(original(),request(),RecipeEngine.RecipeFacts(emptyMap()))
            check(v.messages.size==1 && v.rejected.isEmpty())
            val e=(v.messages.first()["payload"] as Map<*,*>)["xRecipeKi"] as Map<*,*>
            check(e["verification"]=="REVIEW_REQUIRED" && e["generator"]=="UNTRUSTED_PROVIDED")
        }
        test("confirmed allergen restriction blocks imported recipe") {
            val m=original()
            val id=(((m["payload"] as Map<*,*>)["ingredients"] as List<*>).first() as Map<*,*>)["foodTypeId"] as String
            val r=request().copy(restrictions=listOf(RecipeEngine.Restriction("ALLERGEN:GLUTEN",true)))
            val v=RecipeDraftRevalidation.revalidate(m,r,RecipeEngine.RecipeFacts(mapOf(id to fact(id))))
            check(v.messages.isEmpty() && v.rejected.isNotEmpty())
        }
        test("unconfirmed preparation state cannot produce fabricated nutrient totals") {
            val m=original()
            val id=(((m["payload"] as Map<*,*>)["ingredients"] as List<*>).first() as Map<*,*>)["foodTypeId"] as String
            val v=RecipeDraftRevalidation.revalidate(m,request(),RecipeEngine.RecipeFacts(mapOf(id to fact(id))))
            val nutrition=(v.messages.first()["payload"] as Map<*,*>)["nutrition"] as Map<*,*>
            check((nutrition["total"] as Map<*,*>)["proteinG"]==null)
        }
        test("host-confirmed preparation state cannot cover still-missing ingredients") {
            val m=original()
            val id=(((m["payload"] as Map<*,*>)["ingredients"] as List<*>).first() as Map<*,*>)["foodTypeId"] as String
            val v=RecipeDraftRevalidation.revalidate(m,request(),
                RecipeEngine.RecipeFacts(mapOf(id to fact(id))),mapOf(id to RecipeEngine.State.RAW))
            val nutrition=(v.messages.first()["payload"] as Map<*,*>)["nutrition"] as Map<*,*>
            check((nutrition["total"] as Map<*,*>)["proteinG"]==null)
        }
        test("context flags must match current opt-in") {
            expectInvalid { RecipeDraftRevalidation.revalidate(original(request(RecipeEngine.Flags(true))),
                request(),RecipeEngine.RecipeFacts(emptyMap())) }
        }
        test("portion mismatch rejected without implicit scaling") {
            expectInvalid { RecipeDraftRevalidation.revalidate(original(),request().copy(portions=4),
                RecipeEngine.RecipeFacts(emptyMap())) }
        }
        test("unknown food identity rejected before attempting allergy checks") {
            val m=original().toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val p=(m["payload"] as Map<String,Any?>).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val lines=(p["ingredients"] as List<Map<String,Any?>>).toMutableList()
            lines[0]=lines[0].toMutableMap().also { it["foodTypeId"]=null }
            p["ingredients"]=lines;m["payload"]=p
            expectInvalid { RecipeDraftRevalidation.revalidate(m,request(),RecipeEngine.RecipeFacts(emptyMap())) }
        }
        println("SUMMARY recipe_draft_revalidation passed=$n failed=0")
    }
}
