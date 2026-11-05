package de.eg.recipe

/**
 * Mandatory independent validation gate for untrusted imported recipes.
 * Never constructs trusted recipes, purchases, cooking or consumption events.
 */
object RecipeDraftRevalidation {
    fun revalidate(
        message: Map<String, Any?>,
        request: RecipeEngine.Request,
        facts: RecipeEngine.RecipeFacts,
        confirmedStates: Map<String, RecipeEngine.State> = emptyMap()
    ): RecipeEngine.Result {
        val draft = RecipeDraftImport.read(message)
        require(draft.flags == request.flags) { "imported flags do not match current consent" }
        require(draft.portions == request.portions) { "imported portions differ from request" }
        require(draft.ingredients.all { !it.foodTypeId.isNullOrBlank() }) {
            "ingredient identity missing: cannot safely validate restrictions"
        }
        // Raw/cooked indications in an untrusted model's text and quantity.confirmed
        // are not confirmation. Only independently evidenced host states are used.
        val lines = draft.ingredients.map { i ->
            val id = i.foodTypeId!!
            RecipeEngine.Line(id, i.label, RecipeEngine.Quantity(
                i.amount, i.unit, i.basis, false,
                confirmedStates[id] ?: RecipeEngine.State.UNKNOWN
            ), i.allergenNotes)
        }
        val template = RecipeEngine.Template(
            id = draft.draftId, title = draft.title,
            mealType = request.mealType ?: "UNKNOWN", minutes = null,
            equipment = emptySet(),
            ingredients = lines.map {
                RecipeEngine.TemplateLine(it.foodTypeId, it.label, null,
                    it.quantity.unit, it.quantity.basis, it.quantity.state)
            },
            steps = draft.steps
        )
        val candidate = RecipeEngine.Candidate(draft.draftId, template, lines, draft.portions)
        // ProvidedCandidates enforces REVIEW_REQUIRED for all non-blocked imports.
        return RecipeEngine.propose(request, facts, RecipeEngine.ProvidedCandidates(listOf(candidate)))
    }
}
