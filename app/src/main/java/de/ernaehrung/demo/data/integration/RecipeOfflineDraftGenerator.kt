package de.ernaehrung.demo.data.integration

import java.time.OffsetDateTime

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

    /** Only context-free generation is enabled until approved typed snapshots exist. */
    fun generate(
        portions: Int, variant: Int, issuedAt: OffsetDateTime, draftId: String,
        flags: RecipeDraftHostGate.Flags = RecipeDraftHostGate.Flags(false, false, false),
        trustedSnapshotAt: OffsetDateTime? = null
    ): String {
        require(portions in 1..1000) { "portions outside host bounds" }
        require(variant in 0 until VARIANT_COUNT) { "unknown recipe combination" }
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
        val contextMeta = if (needsContext) ",\"inputsSnapshotAt\":${quoted(requireNotNull(trustedSnapshotAt).toString())}" else ""
        return """{"contractVersion":"1.0.0-draft","messageType":"recipe.draft","meta":{"producer":"EG-Rezept-KI-offline-host","traceId":${quoted(draftId)},"createdAt":${quoted(issuedAt.toString())}$contextMeta},"payload":{"draftId":${quoted(draftId)},"title":${quoted(title)},"portionCount":$portions,"ingredients":[$ingredients],"steps":[$steps],"flags":{"considerStock":${flags.considerStock},"considerShopping":${flags.considerShopping},"considerOffers":${flags.considerOffers}},"warnings":[$warnings],"status":"DRAFT"}}"""
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
