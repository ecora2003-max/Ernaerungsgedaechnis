package de.ernaehrung.demo.data.integration

import de.eg.recipe.CombinatorialGenerator
import de.eg.recipe.JsonOutput
import de.eg.recipe.RecipeEngine as Core
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Offline recipe.draft v1 producer adapted from the deterministic three-component
 * grammar in EG-Rezept-KI CombinatorialGenerator.kt (read-only upstream).
 *
 * This is a generator of untrusted suggestions, NOT an allergen, product-version,
 * cooking-state, nutrition, stock or booking authority. The existing v1 importer,
 * independent catalog review and FoodStore consent remain mandatory.
 */
internal object RecipeOfflineDraftGenerator {
    // Keep existing variant selection order stable; quantities, states, labels
    // and preparation steps come from the unchanged pinned module grammar.
    private val grains = listOf("dry-rice", "dry-quinoa", "dry-bulgur")
    private val legumes = listOf("cooked-beans", "cooked-chickpeas", "cooked-lentils")
    private val vegetables = listOf("tomato", "carrot", "bell-pepper")
    private val sampleRequest = Core.Request("host-grammar", "2000-01-01T00:00:00Z", LocalDate.of(2000, 1, 1), 1)
    private val grammar by lazy { CombinatorialGenerator().generate(sampleRequest) }
    private val templates by lazy { Core.OfflineGenerator().generate(sampleRequest) }

    const val VARIANT_COUNT = 30

    data class Ingredient(val foodTypeId: String, val label: String,
        val amountPerPortion: Long, val basis: String, val unit: String = "g")

    private fun Core.Line.hostIngredient() = Ingredient(foodTypeId, label,
        requireNotNull(quantity.amount), quantity.basis.name, quantity.unit.wire)

    private fun ingredient(id: String): Ingredient = grammar.first { c -> c.ingredients.any { it.foodTypeId == id } }
        .ingredients.single { it.foodTypeId == id }.hostIngredient()

    val ingredientTypes: List<Ingredient> by lazy {
        ((grains + legumes + vegetables).map(::ingredient) + templates.flatMap { c -> c.ingredients.map { it.hostIngredient() } })
            .distinctBy { it.foodTypeId }
    }

    fun ingredientsFor(variant: Int): List<Ingredient> {
        require(variant in 0 until VARIANT_COUNT)
        return if (variant < 27) listOf(grains[variant / 9], legumes[(variant / 3) % 3], vegetables[variant % 3]).map(::ingredient)
            else templates[variant - 27].ingredients.map { it.hostIngredient() }
    }

    private fun candidate(variant: Int, request: Core.Request): Core.Candidate? {
        require(variant in 0 until VARIANT_COUNT)
        return if (variant < 27) {
            val ids = ingredientsFor(variant).map { it.foodTypeId }
            CombinatorialGenerator().generate(request).firstOrNull { it.ingredients.map { line -> line.foodTypeId } == ids }
        } else Core.OfflineGenerator().generate(request).firstOrNull { it.template.id == templates[variant - 27].template.id }
    }

    /**
     * Selection-time exclusions are preferences, never verified allergen facts.
     * Unknown allergen status must not be interpreted as absence. The later
     * independent catalog/quantity/state review remains mandatory.
     */
    fun allowsSuggestion(
        variant: Int,
        requestedRestrictions: Set<String>,
        independentlyConfirmedAllergens: Map<String, Map<String, Boolean?>> = emptyMap(),
        preferences: RecipeRequestPreferences = RecipeRequestPreferences(), portions: Int = 1
    ): Boolean {
        require(requestedRestrictions.all(RecipeDraftHostGate::isSupportedRestriction)) {
            "unsupported restriction"
        }
        val excludedFoods = requestedRestrictions.filter { it.startsWith("FOOD:") }
            .map { it.removePrefix("FOOD:") }.toSet()
        val excludedAllergens = requestedRestrictions.filter { it.startsWith("ALLERGEN:") }
            .map { it.removePrefix("ALLERGEN:") }.toSet()
        val request = preferences.applyTo(sampleRequest.copy(portions = portions))
        val selected = candidate(variant, request) ?: return false
        if (Core.validate(selected, request, Core.RecipeFacts(emptyMap())).verdict == Core.Verdict.BLOCKED) return false
        return ingredientsFor(variant).all { ingredient ->
            excludedFoods.none { it.equals(ingredient.foodTypeId, ignoreCase = true) } &&
                excludedAllergens.all { allergen ->
                    independentlyConfirmedAllergens[ingredient.foodTypeId]?.get(allergen) == false
                }
        }
    }

    fun nextAllowedVariant(
        startVariant: Int,
        requestedRestrictions: Set<String>,
        independentlyConfirmedAllergens: Map<String, Map<String, Boolean?>> = emptyMap(),
        preferences: RecipeRequestPreferences = RecipeRequestPreferences(), portions: Int = 1
    ): Int? {
        require(startVariant in 0 until VARIANT_COUNT)
        require(requestedRestrictions.all(RecipeDraftHostGate::isSupportedRestriction)) {
            "unsupported restriction"
        }
        return (0 until VARIANT_COUNT).asSequence()
            .map { (startVariant + it) % VARIANT_COUNT }
            .firstOrNull { allowsSuggestion(it, requestedRestrictions, independentlyConfirmedAllergens, preferences, portions) }
    }

    /** Ranking is supplied by the separately bound host context, never by untrusted JSON. */
    fun generate(
        portions: Int, variant: Int, issuedAt: OffsetDateTime, draftId: String,
        flags: RecipeDraftHostGate.Flags = RecipeDraftHostGate.Flags(false, false, false),
        trustedSnapshotAt: OffsetDateTime? = null,
        requestedRestrictions: Set<String> = emptySet(),
        independentlyConfirmedAllergens: Map<String, Map<String, Boolean?>> = emptyMap(),
        preferences: RecipeRequestPreferences = RecipeRequestPreferences()
    ): String {
        require(portions in 1..1000) { "portions outside host bounds" }
        require(variant in 0 until VARIANT_COUNT) { "unknown recipe combination" }
        // Enforce user exclusions at the generator boundary as well as UI selection.
        // An unverified allergen is never treated as absent in a proposed draft.
        require(allowsSuggestion(variant, requestedRestrictions, independentlyConfirmedAllergens, preferences, portions)) {
            "recipe candidate conflicts with exclusions or unverified allergens"
        }
        require(Regex("[A-Za-z0-9-]{1,80}").matches(draftId)) { "invalid draft identifier" }
        val needsContext = flags.considerStock || flags.considerShopping || flags.considerOffers
        require(!needsContext || (trustedSnapshotAt != null &&
            !trustedSnapshotAt.toInstant().isAfter(issuedAt.toInstant()))) {
            "context requires an independently captured host snapshot before generation"
        }
        require(!needsContext || !trustedSnapshotAt!!.toInstant().isBefore(java.time.Instant.EPOCH)) {
            "invalid context snapshot instant"
        }
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val request = preferences.applyTo(Core.Request(draftId, issuedAt.format(formatter), issuedAt.toLocalDate(), portions,
            Core.Flags(flags.considerStock, flags.considerShopping, flags.considerOffers),
            restrictions = requestedRestrictions.sorted().map { Core.Restriction(it, true) }))
        val candidate = requireNotNull(candidate(variant, request)) { "no recipe for requested meal type" }.copy(id = draftId)
        val result = Core.propose(request, Core.RecipeFacts(emptyMap()), Core.ProvidedCandidates(listOf(candidate)))
        require(result.rejected.isEmpty()) { "reference generator rejected this candidate" }
        val message = result.messages.single().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val metadata = (message.getValue("meta") as Map<String, Any?>).toMutableMap()
        // This timestamp is captured by the host, never supplied by a model.
        metadata["inputsSnapshotAt"] = if (needsContext) requireNotNull(trustedSnapshotAt).format(formatter) else null
        message["meta"] = metadata
        // Reference nutrition remains unknown until independent catalog review.
        return JsonOutput.encode(message)
    }
}
