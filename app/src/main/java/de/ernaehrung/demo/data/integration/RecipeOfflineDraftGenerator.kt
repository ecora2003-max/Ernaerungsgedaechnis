package de.ernaehrung.demo.data.integration

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
    private data class Component(
        val foodTypeId: String, val label: String, val gramsPerPortion: Long,
        val basis: String
    )

    private val grains = listOf(
        Component("dry-rice", "Reis", 75, "NET"),
        Component("dry-quinoa", "Quinoa", 70, "NET"),
        Component("dry-bulgur", "Bulgur", 75, "NET")
    )
    private val legumes = listOf(
        Component("cooked-beans", "gegarte Bohnen", 130, "DRAINED"),
        Component("cooked-chickpeas", "gegarte Kichererbsen", 130, "DRAINED"),
        Component("cooked-lentils", "gegarte Linsen", 130, "DRAINED")
    )
    private val vegetables = listOf(
        Component("tomato", "Tomaten", 120, "NET"),
        Component("carrot", "Karotten", 120, "NET"),
        Component("bell-pepper", "Paprika", 120, "NET")
    )

    const val VARIANT_COUNT = 27

    /** The exact generator grammar, exposed for independently mapped context ranking. */
    data class Ingredient(val foodTypeId: String, val label: String,
        val gramsPerPortion: Long, val basis: String)

    val ingredientTypes: List<Ingredient> = (grains + legumes + vegetables).map {
        Ingredient(it.foodTypeId, it.label, it.gramsPerPortion, it.basis)
    }

    fun ingredientsFor(variant: Int): List<Ingredient> {
        require(variant in 0 until VARIANT_COUNT)
        return listOf(grains[variant / 9], legumes[(variant / 3) % 3],
            vegetables[variant % 3]).map {
            Ingredient(it.foodTypeId, it.label, it.gramsPerPortion, it.basis)
        }
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
            ingredient.foodTypeId !in excludedFoods &&
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
        val grain = grains[variant / 9]
        val legume = legumes[(variant / 3) % 3]
        val vegetable = vegetables[variant % 3]
        val components = listOf(grain, legume, vegetable)
        val ingredients = components.joinToString(",") { component ->
            val grams = Math.multiplyExact(component.gramsPerPortion, portions.toLong())
            """{"label":${quoted(component.label)},"foodTypeId":${quoted(component.foodTypeId)},"sourceStatus":"UNKNOWN","quantity":{"amount":$grams,"unit":"g","basis":${quoted(component.basis)},"confirmed":false}}"""
        }
        val steps = listOf(
            "Hände und Zutaten auf hygienischen Zustand prüfen; Gemüse gründlich reinigen.",
            "${grain.label} mit Wasser nach Verpackungshinweis vollständig garen; Roh- und Gargewicht nicht verwechseln.",
            "${legume.label} auf sicheren bereits gegarten Zustand prüfen und vor dem Servieren ausreichend erhitzen.",
            "${vegetable.label} putzen, klein schneiden und mit den gegarten Zutaten kombinieren.",
            "In $portions Portionen teilen; bei Meal-Prep Kühlkette und Lagerdauer gesondert prüfen."
        ).joinToString(",") { quoted(it) }
        val warnings = listOf(
            "Allergene und konkrete Produktversionen sind nicht geprüft.",
            "Mengenbasis und Roh-/Garzustand müssen unabhängig bestätigt werden."
        ).joinToString(",") { quoted(it) }
        val title = "${grain.label} mit ${legume.label} und ${vegetable.label}"
        // OffsetDateTime.toString() omits zero seconds; RFC3339 requires them.
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val contextMeta = if (needsContext) ",\"inputsSnapshotAt\":${quoted(requireNotNull(trustedSnapshotAt).format(formatter))}" else ""
        return """{"contractVersion":"1.0.0-draft","messageType":"recipe.draft","meta":{"producer":"EG-Rezept-KI-offline-host","traceId":${quoted(draftId)},"createdAt":${quoted(issuedAt.format(formatter))}$contextMeta},"payload":{"draftId":${quoted(draftId)},"title":${quoted(title)},"portionCount":$portions,"ingredients":[$ingredients],"steps":[$steps],"flags":{"considerStock":${flags.considerStock},"considerShopping":${flags.considerShopping},"considerOffers":${flags.considerOffers}},"warnings":[$warnings],"status":"DRAFT"}}"""
    }

    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
