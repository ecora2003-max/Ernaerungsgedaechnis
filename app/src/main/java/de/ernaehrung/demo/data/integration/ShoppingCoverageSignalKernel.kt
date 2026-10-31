package de.ernaehrung.demo.data.integration

/**
 * Pure post-comparison signal projection based on EG-Einkaufsintelligenz@a3e13b1471,
 * eg_shopping.engine._coverage_gaps and compare_prices' final signal rules.
 *
 * IMPORTANT: [CheaperAlternative] must already have passed source-rights, retailer
 * capability, dated validity, confirmed store identity, product/quantity and exact
 * rational cheaper-than-target gates. This object DOES NOT run those gates, fetch
 * offers, prove best-market prices, authorize purchases or write to FoodStore.
 * A FUNCTIONAL_SUBSTITUTE is deliberately never an automatic RED/ORANGE signal.
 */
internal object ShoppingCoverageSignalKernel {
    data class Coverage(val complete: Boolean?, val gaps: List<String> = emptyList())
    data class CheaperAlternative(
        val observationId: String, val bioDifference: String, val comparability: String
    )
    data class Result(
        val signals: List<String>, val gaps: List<String>, val incomplete: Boolean,
        val bestPriceClaim: String = "CHEAPEST_KNOWN_MATCHING_ONLY"
    )

    /** Exact _coverage_gaps parity for the accepted coverage subset. */
    fun coverageGaps(coverage: Coverage?): List<String> {
        if (coverage == null) return listOf("comparison coverage not supplied")
        val gaps = coverage.gaps.toMutableList()
        if (coverage.complete != true) gaps.add("comparison coverage incomplete")
        return gaps.distinct().sorted()
    }

    /** Input rows are PREVALIDATED and strictly cheaper, never raw candidate observations. */
    fun project(
        coverage: Coverage?,
        cheaperAlternatives: List<CheaperAlternative>,
        upstreamGaps: List<String> = emptyList()
    ): Result {
        require(cheaperAlternatives.map { it.observationId }.distinct().size ==
            cheaperAlternatives.size) { "duplicate alternative observationId" }
        for (a in cheaperAlternatives) {
            require(a.observationId.isNotBlank()) { "alternative observationId required" }
            require(a.bioDifference in setOf("SAME", "DIFFERENT", "UNKNOWN")) {
                "unverified organic comparison"
            }
            require(a.comparability in setOf(
                "EXACT_SKU", "SAME_FOOD_VARIANT", "FUNCTIONAL_SUBSTITUTE"
            )) { "unverified product comparability" }
        }
        val gaps = (coverageGaps(coverage) + upstreamGaps +
            cheaperAlternatives.filter { it.bioDifference == "UNKNOWN" }.map {
                "${it.observationId}: unknown organic status"
            }).distinct().sorted()
        val eligible = cheaperAlternatives.filter {
            it.comparability != "FUNCTIONAL_SUBSTITUTE"
        }
        val signals = buildList {
            if (eligible.any { it.bioDifference == "SAME" })
                add("RED_SAME_BIO_CHEAPER")
            if (eligible.any { it.bioDifference == "DIFFERENT" })
                add("ORANGE_DIFFERENT_BIO_CHEAPER")
            if (gaps.isNotEmpty()) add("GREY_MAY_BE_CHEAPER")
        }
        return Result(signals, gaps, gaps.isNotEmpty())
    }
}
