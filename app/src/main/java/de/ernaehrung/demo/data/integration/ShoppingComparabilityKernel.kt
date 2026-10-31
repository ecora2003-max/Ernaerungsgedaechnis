package de.ernaehrung.demo.data.integration

/**
 * Isolated, deterministic port of EG-Einkaufsintelligenz@a3e13b1471
 * eg_shopping.engine._comparability (engine.py blob be47af89bd15).
 *
 * Product comparability is NOT retailer identity, a source-rights grant,
 * an offer, a price comparison, an allergy clearance, or a booking approval.
 * Confirmed functional substitution is evidence only, not automatic consent
 * to purchase or consume. A separate host gate must verify every other claim.
 */
internal object ShoppingComparabilityKernel {
    data class Article(
        val foodTypeId: String?,
        val attributes: Map<String, String> = emptyMap(),
        val quantity: ShoppingUnitRateKernel.Quantity? = null,
        val gtin: String? = null,
        val brandKind: String? = null,
        val brandName: String? = null
    )
    data class Observation(val observationId: String, val article: Article)
    data class Match(
        val targetId: String,
        val candidateId: String,
        val status: String,
        val matchLevel: String,
        val evidence: List<String> = emptyList()
    )
    data class Verdict(val level: String, val evidence: List<String>)

    private val critical = listOf(
        "form", "sweetened", "species", "fatPercent", "state", "diet",
        "variety", "allergens", "processing", "cut"
    )

    fun compare(target: Observation, candidate: Observation, matches: List<Match> = emptyList()): Verdict {
        val a = target.article
        val b = candidate.article
        if (a.foodTypeId == null || b.foodTypeId == null)
            return Verdict("UNKNOWN", listOf("food type not established"))
        if (a.foodTypeId != b.foodTypeId) {
            val confirmed = matches.firstOrNull {
                it.targetId == target.observationId &&
                    it.candidateId == candidate.observationId &&
                    it.status == "CONFIRMED_BY_USER" &&
                    it.matchLevel == "FUNCTIONAL_SUBSTITUTE"
            }
            return if (confirmed != null)
                Verdict("FUNCTIONAL_SUBSTITUTE",
                    listOf("explicitly confirmed substitution") + confirmed.evidence)
            else Verdict("UNKNOWN", listOf("different food types; no confirmed substitution"))
        }
        for (key in critical) {
            if (key in a.attributes || key in b.attributes) {
                if (key !in a.attributes || key !in b.attributes)
                    return Verdict("UNKNOWN", listOf("critical attribute $key missing"))
                if (a.attributes[key] != b.attributes[key])
                    return Verdict("UNKNOWN", listOf(
                        "incompatible $key: '${a.attributes[key]}' vs '${b.attributes[key]}'"
                    ))
            }
        }
        // Keep Python's confirmed-quantity semantics, including null vs zero,
        // package count, and NET/DRAINED/GROSS/PIECE. Never infer a conversion.
        val qa = ShoppingUnitRateKernel.confirmedQuantity(a.quantity)
        val qb = ShoppingUnitRateKernel.confirmedQuantity(b.quantity)
        if (qa == null || qb == null || qa.unit != qb.unit ||
            qa.quantityBasis != qb.quantityBasis)
            return Verdict("UNKNOWN", listOf("quantity or unit/basis not comparable"))
        if (!a.gtin.isNullOrEmpty() && a.gtin == b.gtin && qa.amount == qb.amount)
            return Verdict("EXACT_SKU", listOf("same GTIN and package"))
        val notes = mutableListOf("same food type and critical attributes")
        if (a.brandKind != b.brandKind || a.brandName != b.brandName)
            notes.add("brand/private-label difference")
        return Verdict("SAME_FOOD_VARIANT", notes)
    }
}
