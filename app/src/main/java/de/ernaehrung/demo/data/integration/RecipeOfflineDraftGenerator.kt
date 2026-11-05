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
    private val grammar by lazy {
        CombinatorialGenerator().generate(Core.Request("host-grammar",
            "2000-01-01T00:00:00Z", LocalDate.of(2000, 1, 1), 1))
    }

    const val VARIANT_COUNT = 27

    data class Ingredient(val foodTypeId: String, val label: String,
        val gramsPerPortion: Long, val basis: String)

    private fun ingredient(id: String): Ingredient {
        val line = grammar.first { c -> c.ingredients.any { it.foodTypeId == id } }
            .ingredients.single { it.foodTypeId == id }
        return Ingredient(id, line.label, requireNotNull(line.quantity.amount), line.quantity.basis.name)
    }

    val ingredientTypes: List<Ingredient> by lazy { (grains + legumes + vegetables).map(::ingredient) }

    fun ingredientsFor(variant: Int): List<Ingredient> {
        require(variant in 0 until VARIANT_COUNT)
        return listOf(grains[variant / 9], legumes[(variant / 3) % 3], vegetables[variant % 3])
            .map(::ingredient)
    }

    /**
     * Selection-time exclusions are preferences, never verified allergen facts.
     * Unknown allergen status must not be interpreted as absence. The later
     * independent catalog/quantity/state review remains mandatory.
     */
    fun allowsSuggestion(
        variant: Int,
        requestedRestrictions: Set<String>,
        independentlyConfirmedAllergens: Map<String, Map<String, Boolean?>> = emptyMap()
    ): Boolean {
        require(requestedRestrictions.all(RecipeDraftHostGate::isSupportedRestriction)) {
            "unsupported restriction"
        }
        val excludedFoods = requestedRestrictions.filter { it.startsWith("FOOD:") }
            .map { it.removePrefix("FOOD:") }.toSet()
        val excludedAllergens = requestedRestrictions.filter { it.startsWith("ALLERGEN:") }
            .map { it.removePrefix("ALLERGEN:") }.toSet()
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
        independentlyConfirmedAllergens: Map<String, Map<String, Boolean?>> = emptyMap()
    ): Int? {
        require(startVariant in 0 until VARIANT_COUNT)
        require(requestedRestrictions.all(RecipeDraftHostGate::isSupportedRestriction)) {
            "unsupported restriction"
        }
        return (0 until VARIANT_COUNT).asSequence()
            .map { (startVariant + it) % VARIANT_COUNT }
            .firstOrNull { allowsSuggestion(it, requestedRestrictions, independentlyConfirmedAllergens) }
    }

    /** Context flags label separately captured host inputs; variant ranking is not yet context-aware. */
    fun generate(
        portions: Int, variant: Int, issuedAt: OffsetDateTime, draftId: String,
        flags: RecipeDraftHostGate.Flags = RecipeDraftHostGate.Flags(false, false, false),
        trustedSnapshotAt: OffsetDateTime? = null,
        requestedRestrictions: Set<String> = emptySet(),
        independentlyConfirmedAllergens: Map<String, Map<String, Boolean?>> = emptyMap()
    ): String {
        require(portions in 1..1000) { "portions outside host bounds" }
        require(variant in 0 until VARIANT_COUNT) { "unknown recipe combination" }
        // Enforce user exclusions at the generator boundary as well as UI selection.
        // An unverified allergen is never treated as absent in a proposed draft.
        require(allowsSuggestion(variant, requestedRestrictions, independentlyConfirmedAllergens)) {
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
        val request = Core.Request(draftId, issuedAt.format(formatter), issuedAt.toLocalDate(), portions,
            Core.Flags(flags.considerStock, flags.considerShopping, flags.considerOffers),
            restrictions = requestedRestrictions.sorted().map { Core.Restriction(it, true) })
        val foodIds = ingredientsFor(variant).map { it.foodTypeId }
        val candidate = CombinatorialGenerator().generate(request).single {
            it.ingredients.map { line -> line.foodTypeId } == foodIds
        }.copy(id = draftId)
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
