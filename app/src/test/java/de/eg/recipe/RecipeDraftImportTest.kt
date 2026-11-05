package de.eg.recipe

import java.time.LocalDate

/** JVM only: synthetic parsed maps; there is no user data, JSON library or network. */
object RecipeDraftImportTest {
    private fun original(): Map<String, Any?> {
        val req = RecipeEngine.Request("IMPORT-1", "2026-10-31T17:00:00+01:00",
            LocalDate.parse("2026-11-04"), 2, equipment = setOf("HOB", "POT"))
        return RecipeEngine.propose(req, RecipeEngine.RecipeFacts(emptyMap())).messages.first()
    }
    @Suppress("UNCHECKED_CAST")
    private fun mutate(m: Map<String, Any?>, op:(MutableMap<String,Any?>)->Unit):Map<String,Any?> =
        m.toMutableMap().also(op)
    @Suppress("UNCHECKED_CAST")
    private fun payload(m:Map<String,Any?>, op:(MutableMap<String,Any?>)->Unit):Map<String,Any?> =
        mutate(m) { outer ->
            val p = (outer["payload"] as Map<String,Any?>).toMutableMap()
            op(p);outer["payload"]=p
        }
    @Suppress("UNCHECKED_CAST")
    private fun ingredient(m:Map<String,Any?>, op:(MutableMap<String,Any?>)->Unit):Map<String,Any?> =
        payload(m) { p ->
            val lines = (p["ingredients"] as List<Map<String,Any?>>).toMutableList()
            val first=lines[0].toMutableMap();op(first);lines[0]=first;p["ingredients"]=lines
        }
    private fun fail(m:Map<String,Any?>) {
        var rejected=false
        try { RecipeDraftImport.read(m) } catch (_:IllegalArgumentException) { rejected=true }
        check(rejected)
    }
    @JvmStatic fun main(args:Array<String>) {
        var passed=0
        fun test(name:String, f:()->Unit) { f();passed++;println("PASS $name") }
        val m=original()
        test("own generated recipe maps read as untrusted draft") {
            val d=RecipeDraftImport.read(m)
            check(d.portions==2 && d.ingredients.isNotEmpty() && d.requiresIndependentValidation)
            check(d.flags==RecipeEngine.Flags() && d.suppliedNutritionClaim)
        }
        test("forged verification claim never overrides independent revalidation") {
            val forged=payload(m) { p ->
                val ext=(p["xRecipeKi"] as Map<*,*>).toMutableMap()
                ext["verification"]="STRUCTURALLY_VALID";p["xRecipeKi"]=ext
            }
            check(RecipeDraftImport.read(forged).requiresIndependentValidation)
            check(RecipeDraftImport.read(forged).suppliedVerificationClaim=="STRUCTURALLY_VALID")
        }
        test("all eight context flag combinations preserved exactly") {
            for(bits in 0..7) {
                val x=payload(m){p->p["flags"]=mapOf("considerStock" to (bits and 1!=0),
                    "considerShopping" to (bits and 2!=0),"considerOffers" to (bits and 4!=0))}
                check(RecipeDraftImport.read(x).flags==RecipeEngine.Flags(
                    bits and 1!=0,bits and 2!=0,bits and 4!=0))
            }
        }
        test("missing quantity stays unknown") {
            val x=ingredient(m){it["quantity"]=mapOf("amount" to null,"unit" to "g",
                "basis" to "NET","confirmed" to false)}
            check(RecipeDraftImport.read(x).ingredients.first().amount==null)
        }
        test("wrong version, message type or absent meta rejected") {
            fail(mutate(m){it["contractVersion"]="9.9.9"})
            fail(mutate(m){it["messageType"]="price.observation"})
            fail(mutate(m){it.remove("meta")})
        }
        test("confirmed/consumed statuses never imported") {
            fail(payload(m){it["status"]="CONFIRMED"})
            fail(payload(m){it["status"]="CONSUMED"})
        }
        test("flags must be exact booleans and complete") {
            fail(payload(m){it["flags"]=mapOf("considerStock" to "false",
                "considerShopping" to false,"considerOffers" to false)})
            fail(payload(m){it["flags"]=mapOf("considerStock" to false, "considerShopping" to false)})
        }
        test("fractional, negative, oversized and string amounts rejected") {
            for(n in listOf<Any>(1.5,-4,java.math.BigInteger("9223372036854775808"),"42")) {
                fail(ingredient(m){it["quantity"]=mapOf("amount" to n,"unit" to "g",
                    "basis" to "NET","confirmed" to false)})
            }
        }
        test("unit/mass basis conflict never guessed") {
            fail(ingredient(m){it["quantity"]=mapOf("amount" to 100,"unit" to "piece",
                "basis" to "NET","confirmed" to false)})
        }
        test("invalid timestamp and unnamespaced meta extension rejected") {
            fail(mutate(m){it["meta"]=mapOf("producer" to "t","traceId" to "x","createdAt" to "yesterday")})
            fail(mutate(m){it["meta"]=mapOf("producer" to "t","traceId" to "x",
                "createdAt" to "2026-10-31T17:00:00+01:00","untrusted" to true)})
        }
        test("unsupported nutrition assertion never considered verified") {
            val x=payload(m){it["nutrition"]=mapOf("proteinG" to 999999)}
            check(RecipeDraftImport.read(x).suppliedNutritionClaim)
            check(RecipeDraftImport.read(x).requiresIndependentValidation)
        }
        test("embedded booking claim cannot be a draft") {
            fail(payload(m){p->
                val ext=(p["xRecipeKi"] as Map<*,*>).toMutableMap()
                ext["mealPrep"]=mapOf("consumptionRecorded" to true)
                p["xRecipeKi"]=ext
            })
        }
        test("unknown ingredient provenance or no preparation rejected") {
            fail(ingredient(m){it["sourceStatus"]="AI_VERIFIED"})
            fail(payload(m){it["steps"]=emptyList<String>()})
        }
        test("supported Long portion counts accepted with overflow guard") {
            check(RecipeDraftImport.read(payload(m){it["portionCount"]=2L}).portions==2)
            fail(payload(m){it["portionCount"]=Long.MAX_VALUE})
        }
        println("SUMMARY recipe_draft_import passed=$passed failed=0")
    }
}
