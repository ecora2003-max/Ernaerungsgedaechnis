package de.ernaehrung.demo.data.integration

import java.math.BigInteger
import java.time.OffsetDateTime

/**
 * Conservative, read-only host composition of the already isolated shopping kernels.
 * Unlike a raw Python compare_prices call, every external offer must first pass
 * canonical-store, host-product, retailer-capability, source-rights and date gates.
 * No feed-provided product/organic/coverage assertion is trusted by default.
 *
 * This is a bounded, synthetic host adapter, NOT complete compare_prices parity,
 * a live provider, a market-wide best-price proof, or permission to book a purchase.
 */
internal object ShoppingPriceComparisonGate {
    data class Input(
        val request: ExternalPriceBridge.Request,
        val candidate: ExternalPriceBridge.Candidate,
        val facts: ShoppingOfferGate.HostFacts,
        val article: ShoppingComparabilityKernel.Article,
        val organic: Boolean?
    )

    /** Host-controlled product-version evidence; never implement using feed labels. */
    fun interface ConfirmedArticleRegistry {
        fun isConfirmed(
            productVersionId: String,
            article: ShoppingComparabilityKernel.Article,
            organic: Boolean?,
            asOf: OffsetDateTime
        ): Boolean
    }

    val denyAllArticles = ConfirmedArticleRegistry { _, _, _, _ -> false }

    /**
     * Independent HOST trust anchor for a user's explicit substitution decision.
     * A feed-provided Match(status="CONFIRMED_BY_USER") is never proof by itself.
     * Implementations must bind the real decision to both confirmed product versions
     * and the evidence cutoff; an exception is treated as DENY.
     */
    fun interface ConfirmedSubstitutionRegistry {
        fun isConfirmed(
            targetProductVersionId: String,
            candidateProductVersionId: String,
            match: ShoppingComparabilityKernel.Match,
            asOf: OffsetDateTime
        ): Boolean
    }

    val denyAllSubstitutions = ConfirmedSubstitutionRegistry { _, _, _, _ -> false }

    data class HostCoverage(
        val coverage: ShoppingCoverageSignalKernel.Coverage,
        val verifiedByHost: Boolean
    )

    data class Cheaper(
        val observationId: String,
        val comparability: String,
        val organicDifference: String,
        val candidateRate: ShoppingUnitRateKernel.Rate,
        /** Candidate minus target, only if the exact target-quantity delta is integral cents. */
        val targetEquivalentDifferenceCents: BigInteger?
    )

    data class Result(
        val targetEligible: Boolean,
        val cheaper: List<Cheaper>,
        val excluded: Map<String, String>,
        val signals: ShoppingCoverageSignalKernel.Result
    ) {
        val marketWideBestPriceProven: Boolean get() = false
    }

    private data class Checked(
        val rate: ShoppingUnitRateKernel.Rate?,
        val rejection: String?
    )

    private fun check(
        input: Input,
        permissions: ExternalPriceBridge.DisplayPermissionRegistry,
        stores: ShoppingOfferGate.ConfirmedStoreRegistry,
        articles: ConfirmedArticleRegistry
    ): Checked {
        val request = input.request
        val candidate = input.candidate
        if (request.productVersionId.isBlank() ||
            request.productVersionId != candidate.productVersionId
        ) return Checked(null, "host product/version/organic evidence missing")
        // A failed local identity lookup is UNKNOWN, never confirmed.
        val articleConfirmed = try {
            articles.isConfirmed(request.productVersionId, input.article,
                input.organic, request.asOf)
        } catch (_: RuntimeException) { false }
        if (!articleConfirmed)
            return Checked(null, "host product/version/organic evidence missing")

        val gate = try {
            ShoppingOfferGate.evaluate(request, candidate, input.facts, permissions, stores)
        } catch (_: IllegalArgumentException) {
            return Checked(null, "invalid offer request")
        }
        if (!gate.eligible)
            return Checked(null, "offer gate rejected: " +
                (gate.gaps.map { it.name } + gate.externalGaps.map { it.name } +
                    gate.validityNotes).joinToString(","))

        val visible = requireNotNull(gate.offer)
        val qty = try {
            ShoppingUnitRateKernel.confirmedQuantity(input.article.quantity)
        } catch (_: IllegalArgumentException) { null }
            ?: return Checked(null, "confirmed article quantity missing")
        val expectedUnit = when (visible.unit) {
            ExternalPriceBridge.Unit.GRAM -> "g"
            ExternalPriceBridge.Unit.MILLILITRE -> "ml"
            ExternalPriceBridge.Unit.PORTION -> "piece"
        }
        // Bridge quantities are thousandths of the physical unit; the article
        // model uses integer g/ml/piece. Reject non-exact or inconsistent mapping.
        if (qty.unit != expectedUnit ||
            qty.amount.multiply(BigInteger.valueOf(1000)) !=
                BigInteger.valueOf(visible.quantityMilli)
        ) return Checked(null, "offer and host article quantities disagree")

        val rate = try {
            ShoppingUnitRateKernel.rate(
                visible.merchandiseCents, "EUR", input.article.quantity
            )
        } catch (_: IllegalArgumentException) { null }
        return if (rate == null) Checked(null, "confirmed comparable rate missing")
            else Checked(rate, null)
    }

    fun compare(
        target: Input,
        candidates: List<Input>,
        coverage: HostCoverage? = null,
        matches: List<ShoppingComparabilityKernel.Match> = emptyList(),
        permissions: ExternalPriceBridge.DisplayPermissionRegistry =
            ExternalPriceBridge.denyAll,
        stores: ShoppingOfferGate.ConfirmedStoreRegistry =
            ShoppingOfferGate.denyAllStores,
        articles: ConfirmedArticleRegistry = denyAllArticles,
        substitutions: ConfirmedSubstitutionRegistry = denyAllSubstitutions
    ): Result {
        require(candidates.size <= 256) { "comparison input limit exceeded" }
        val ids = listOf(target.candidate.observationId) +
            candidates.map { it.candidate.observationId }
        require(ids.all { it.isNotBlank() } && ids.size == ids.toSet().size) {
            "nonempty, unique observation IDs required"
        }

        val targetCheck = check(target, permissions, stores, articles)
        if (targetCheck.rate == null) {
            val why = targetCheck.rejection ?: "target not eligible"
            return Result(false, emptyList(), mapOf(target.candidate.observationId to why),
                ShoppingCoverageSignalKernel.project(null, emptyList(),
                    listOf("target: $why")))
        }
        val targetRate = targetCheck.rate
        val accepted = mutableListOf<Cheaper>()
        val excluded = linkedMapOf<String, String>()
        val gaps = mutableListOf<String>()
        // A merchandise-only rate never proves the target checkout amount.
        if (target.candidate.depositCents == null)
            gaps.add("target: deposit/checkout cost UNKNOWN")
        for (candidate in candidates.sortedBy { it.candidate.observationId }) {
            val id = candidate.candidate.observationId
            // Every comparison uses ONE shopping date and ONE evidence cutoff.
            // A candidate must not choose a later asOf to import future knowledge.
            if (candidate.request.purchaseOn != target.request.purchaseOn ||
                candidate.request.asOf.toInstant() != target.request.asOf.toInstant()
            ) {
                excluded[id] = "comparison date/evidence cutoff mismatch"
                gaps.add("$id: comparison date/evidence cutoff mismatch")
                continue
            }
            val checked = check(candidate, permissions, stores, articles)
            if (checked.rate == null) {
                val reason = checked.rejection ?: "offer not eligible"
                excluded[id] = reason
                gaps.add("$id: $reason")
                continue
            }
            // Preserve checkout uncertainty even when merchandise is not cheaper.
            if (candidate.candidate.depositCents == null)
                gaps.add("$id: deposit/checkout cost UNKNOWN")
            // A purported CONFIRMED_BY_USER match may originate in a feed or
            // imported draft. Pass only decisions independently attested by the
            // host for this exact observation pair, product versions and cutoff.
            val trustedMatches = matches.filter { match ->
                match.targetId == target.candidate.observationId &&
                    match.candidateId == id &&
                    match.status == "CONFIRMED_BY_USER" &&
                    match.matchLevel == "FUNCTIONAL_SUBSTITUTE" &&
                    try {
                        substitutions.isConfirmed(
                            target.request.productVersionId,
                            candidate.request.productVersionId,
                            match, target.request.asOf
                        )
                    } catch (_: RuntimeException) {
                        false
                    }
            }
            val comparability = try {
                ShoppingComparabilityKernel.compare(
                    ShoppingComparabilityKernel.Observation(
                        target.candidate.observationId, target.article),
                    ShoppingComparabilityKernel.Observation(id, candidate.article),
                    trustedMatches
                )
            } catch (_: IllegalArgumentException) {
                ShoppingComparabilityKernel.Verdict("UNKNOWN",
                    listOf("invalid product quantity"))
            }
            if (comparability.level == "UNKNOWN") {
                excluded[id] = "product comparability unproven"
                gaps.add("$id: incomplete product comparability")
                continue
            }
            val comparison = ShoppingUnitRateKernel.compare(targetRate, checked.rate)
            if (comparison == null) {
                excluded[id] = "incompatible quantity basis"
                gaps.add("$id: incompatible quantity basis")
                continue
            }
            if (comparison <= 0) continue // equal or more expensive is not cheaper
            val organicDifference = when {
                target.organic == null || candidate.organic == null -> "UNKNOWN"
                target.organic == candidate.organic -> "SAME"
                else -> "DIFFERENT"
            }
            accepted.add(Cheaper(
                id, comparability.level, organicDifference, checked.rate,
                ShoppingUnitRateKernel.targetEquivalentDifferenceCents(
                    targetRate, checked.rate, target.article.quantity)
            ))
        }
        // The pinned Python compare_prices orders alternatives by exact rate,
        // then observation ID, never by the nominal pack price or input order.
        accepted.sortWith { left, right ->
            val byRate = ShoppingUnitRateKernel.compare(
                left.candidateRate, right.candidateRate) ?: 0
            if (byRate != 0) byRate else left.observationId.compareTo(right.observationId)
        }
        val verifiedCoverage = coverage?.takeIf { it.verifiedByHost }?.coverage
        val signals = ShoppingCoverageSignalKernel.project(
            verifiedCoverage,
            accepted.map {
                ShoppingCoverageSignalKernel.CheaperAlternative(
                    it.observationId, it.organicDifference, it.comparability)
            },
            gaps
        )
        return Result(true, accepted, excluded, signals)
    }
}
