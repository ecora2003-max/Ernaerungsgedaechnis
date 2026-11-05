package de.eg.recipe

import de.eg.recipe.RecipeEngine.Request
import de.eg.recipe.RecipeEngine.Flags
import de.eg.recipe.RecipeEngine.Snapshot
import de.eg.recipe.RecipeEngine.Quantity
import de.eg.recipe.RecipeEngine.UnitCode
import de.eg.recipe.RecipeEngine.Basis
import de.eg.recipe.RecipeEngine.State
import de.eg.recipe.RecipeEngine.Completeness
import de.eg.recipe.RecipeEngine.InventoryEntry
import de.eg.recipe.RecipeEngine.RecipeFacts
import de.eg.recipe.RecipeEngine.Restriction
import java.time.LocalDate

/** Pure JVM tests. All data are synthetic; no network, inventory writes, or health profiles. */
object CombinatorialGeneratorTest {
    private fun request(portions: Int = 2, mealType: String? = "MAIN", flags: Flags = Flags(),
                        preferred: Set<String> = emptySet(), stock: Snapshot? = null) = Request(
        requestId="COMBO", createdAt="2026-10-31T17:00:00+01:00",shoppingDate=LocalDate.parse("2026-11-04"),
        portions=portions,mealType=mealType,flags=flags,preferredFoodTypes=preferred,stock=stock,
        equipment=setOf("HOB","POT"))
    private fun q(n: Long) = Quantity(n,UnitCode.G,Basis.NET,true)
    @JvmStatic fun main(args: Array<String>) {
        val all=CombinatorialGenerator().generate(request())
        check(all.size==27)
        check(all.map { it.id }.toSet().size==27)
        check(all.all { it.ingredients.size==3 && it.template.steps.size==5 })
        check(CombinatorialGenerator().generate(request(mealType="BREAKFAST")).isEmpty())
        check(HybridGenerator().generate(request()).size==14) // 2 fixed MAIN plus 12 generated
        val two=CombinatorialGenerator(1).generate(request(portions=2)).single()
        val four=CombinatorialGenerator(1).generate(request(portions=4)).single()
        check(two.ingredients.zip(four.ingredients).all { (a,b) -> b.quantity.amount==a.quantity.amount!!*2L })
        check(two.ingredients.any { it.quantity.state==State.COOKED })
        val stock=Snapshot(true,Completeness.PARTIAL,"2026-10-31T17:00:00+01:00",
            listOf(InventoryEntry("dry-rice",q(200))),"synthetic","CONFIRMED")
        for (bits in 0..7) {
            val flags=Flags(bits and 1 != 0,bits and 2 != 0,bits and 4 != 0)
            val base=request(flags=flags,stock=stock)
            val changed=base.copy(stock=if(flags.considerStock) stock else stock.copy(entries=listOf(InventoryEntry("dry-rice",q(999)))))
            val x=RecipeEngine.propose(base, RecipeFacts(emptyMap()),CombinatorialGenerator(3))
            val y=RecipeEngine.propose(changed,RecipeFacts(emptyMap()),CombinatorialGenerator(3))
            check(JsonOutput.encode(x.messages)==JsonOutput.encode(y.messages))
            check(x.messages.all { (it["payload"] as Map<*,*>)["status"]=="DRAFT" })
        }
        val forbidden=request().copy(restrictions=listOf(Restriction("FOOD:dry-rice",true)))
        val verified=RecipeEngine.propose(forbidden,RecipeFacts(emptyMap()),CombinatorialGenerator())
        check(verified.rejected.isNotEmpty())
        check(verified.messages.size+verified.rejected.size==27)
        val preferred=CombinatorialGenerator().generate(request(preferred=setOf("dry-quinoa","tomato")))
        check(preferred.first().ingredients.map { it.foodTypeId }.containsAll(setOf("dry-quinoa","tomato")))
        println("SUMMARY combinatorial_generator passed=10 failed=0")
    }
}
