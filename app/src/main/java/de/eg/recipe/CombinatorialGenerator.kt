package de.eg.recipe

import de.eg.recipe.RecipeEngine.Basis
import de.eg.recipe.RecipeEngine.State
import de.eg.recipe.RecipeEngine.UnitCode
import de.eg.recipe.RecipeEngine.Template
import de.eg.recipe.RecipeEngine.TemplateLine
import de.eg.recipe.RecipeEngine.Request
import de.eg.recipe.RecipeEngine.Candidate
import de.eg.recipe.RecipeEngine.RecipeCandidateGenerator

/** Deterministic generative recipe grammar; predefined food safety sequences, not an LLM. */
class CombinatorialGenerator(private val maxCandidates: Int = 27) : RecipeCandidateGenerator {
    private data class Component(val key: String, val label: String, val quantityPerPortion: Long, val basis: Basis, val state: State)
    private val grains = listOf(
        Component("dry-rice", "Reis", 75, Basis.NET, State.RAW),
        Component("dry-quinoa", "Quinoa", 70, Basis.NET, State.RAW),
        Component("dry-bulgur", "Bulgur", 75, Basis.NET, State.RAW)
    )
    private val legumes = listOf(
        Component("cooked-beans", "gegarte Bohnen", 130, Basis.DRAINED, State.COOKED),
        Component("cooked-chickpeas", "gegarte Kichererbsen", 130, Basis.DRAINED, State.COOKED),
        Component("cooked-lentils", "gegarte Linsen", 130, Basis.DRAINED, State.COOKED)
    )
    private val vegetables = listOf(
        Component("tomato", "Tomaten", 120, Basis.NET, State.RAW),
        Component("carrot", "Karotten", 120, Basis.NET, State.RAW),
        Component("bell-pepper", "Paprika", 120, Basis.NET, State.RAW)
    )
    init { require(maxCandidates in 1..27) { "maxCandidates out of supported range" } }

    override fun generate(request: Request): List<Candidate> {
        if (request.mealType != null && request.mealType != "MAIN") return emptyList()
        // The generator does not read stock, shopping or offer snapshots: only the engine's
        // three independent context ports can make these affect the resulting ranking.
        return grains.flatMap { g -> legumes.flatMap { l -> vegetables.map { v -> yieldTemplate(request, g, l, v) } } }
            .sortedWith(compareByDescending<Candidate> { c ->
                c.ingredients.count { it.foodTypeId in request.preferredFoodTypes }
            }.thenBy { it.id })
            .take(maxCandidates)
    }
    private fun yieldTemplate(request: Request, g: Component, l: Component, v: Component): Candidate {
        val id = "${g.key}-${l.key}-${v.key}"
        val template = Template(
            id = id,
            title = "${g.label} mit ${l.label} und ${v.label}",
            mealType = "MAIN",
            minutes = 35,
            equipment = setOf("HOB", "POT"),
            ingredients = listOf(g, l, v).map { x ->
                TemplateLine(x.key, x.label, x.quantityPerPortion, UnitCode.G, x.basis, x.state)
            },
            steps = listOf(
                "Hände und Zutaten auf hygienischen Zustand prüfen; Gemüse gründlich reinigen.",
                "${g.label} mit Wasser nach Verpackungshinweis vollständig garen; Roh- und Gargewicht nicht verwechseln.",
                "${l.label} auf sicheren bereits gegarten Zustand prüfen und vor dem Servieren ausreichend erhitzen.",
                "${v.label} putzen, klein schneiden und mit den gegarten Zutaten kombinieren.",
                "In ${request.portions} Portionen teilen; bei Meal-Prep Kühlkette und Lagerdauer gesondert prüfen."
            )
        )
        val ingredientLines = template.ingredients.map { x ->
            RecipeEngine.Line(x.foodTypeId, x.label, RecipeEngine.Quantity(
                Math.multiplyExact(x.amountPerPortion!!, request.portions.toLong()), x.unit, x.basis, false, x.state
            ))
        }
        return Candidate("${request.requestId}-$id", template, ingredientLines, request.portions)
    }
}

/** Combines three fixed, tested reference dishes and a bounded set of constructed bowls. */
class HybridGenerator(private val maximumNewRecipes: Int = 12) : RecipeCandidateGenerator {
    private val templates = RecipeEngine.OfflineGenerator()
    private val combinations = CombinatorialGenerator(maximumNewRecipes)
    override fun generate(request: Request): List<Candidate> =
        templates.generate(request) + combinations.generate(request)
}
