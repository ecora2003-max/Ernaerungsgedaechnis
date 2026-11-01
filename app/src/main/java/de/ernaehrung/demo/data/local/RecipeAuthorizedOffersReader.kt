package de.ernaehrung.demo.data.local

import de.ernaehrung.demo.data.integration.ExternalPriceBridge
import de.ernaehrung.demo.data.integration.ExternalPriceCandidateSupplier
import de.ernaehrung.demo.data.integration.RecipeContextContentBinding
import de.ernaehrung.demo.data.integration.RetailerPolicy
import de.ernaehrung.demo.data.integration.ShoppingOfferGate
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

/**
 * A read-only host adapter for independently authorized external offer evidence.
 * The supplier is an already configured host-side local cache, not a network
 * downloader or a trusted feed permission. No provider is configured by default.
 * Callers must execute read inside the same Room transaction as their review.
 */
internal class RecipeAuthorizedOffersReader(
    private val dao: FoodDao,
    private val supplier: ExternalPriceCandidateSupplier,
    private val hostFacts: HostFactsRegistry,
    private val permissions: ExternalPriceBridge.DisplayPermissionRegistry =
        ExternalPriceBridge.denyAll,
    private val stores: ShoppingOfferGate.ConfirmedStoreRegistry =
        ShoppingOfferGate.denyAllStores
) {
    fun interface HostFactsRegistry {
        /** Independently confirmed host facts, never feed-supplied flags. */
        fun verified(observationId: String): ShoppingOfferGate.HostFacts?
    }

    data class Scope(
        val retailer: RetailerPolicy.Retailer,
        val productVersionId: String,
        val storeId: String,
        val regionId: String,
        val purchaseOn: LocalDate
    )

    companion object {
        private const val PREFIX = "AUTHORIZED_HOST_OFFERS_V1"
        private const val MAX_OFFERS = 256

        fun scopeId(scope: Scope): String = listOf(
            PREFIX, scope.retailer.name, scope.productVersionId,
            scope.storeId, scope.regionId, scope.purchaseOn.toString()
        ).joinToString("|")

        private fun validScope(scope: Scope): Boolean =
            scope.productVersionId.isNotBlank() && scope.storeId.isNotBlank() &&
                scope.regionId.isNotBlank() &&
                listOf(scope.productVersionId, scope.storeId, scope.regionId)
                    .none { "|" in it }
    }

    suspend fun read(scope: Scope, capturedAt: Instant): RecipeContextContentBinding.Snapshot? {
        if (!validScope(scope)) return null
        val version = dao.version(scope.productVersionId) ?: return null
        val request = ExternalPriceBridge.Request(
            retailer = scope.retailer,
            capability = RetailerPolicy.Capability.PUBLISHED_OFFER_COLLECTION,
            productVersionId = scope.productVersionId,
            purchaseOn = scope.purchaseOn,
            asOf = OffsetDateTime.ofInstant(capturedAt, ZoneOffset.UTC),
            storeId = scope.storeId,
            regionId = scope.regionId
        )
        // A missing cache, unknown coverage, duplicate observation or rejected
        // candidate is UNKNOWN, not a fabricated empty offer collection.
        val candidates = try {
            supplier.load(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return null
        }
        if (candidates.isEmpty() || candidates.size > MAX_OFFERS ||
            candidates.map { it.observationId }.toSet().size != candidates.size) return null
        val approved = mutableListOf<Pair<ExternalPriceBridge.Candidate,
            ExternalPriceBridge.VisiblePrice>>()
        for (candidate in candidates) {
            if (candidate.productVersionId != version.id) return null
            val facts = try { hostFacts.verified(candidate.observationId) }
                catch (_: RuntimeException) { null } ?: return null
            // Feed-supplied productVersionConfirmed cannot establish identity.
            // Exact current Room identity is checked above and used here.
            val result = ShoppingOfferGate.evaluate(
                request, candidate.copy(productVersionConfirmed = true),
                facts, permissions, stores
            )
            val visible = result.offer ?: return null
            if (!result.eligible) return null
            val unitMatches = when (visible.unit) {
                ExternalPriceBridge.Unit.GRAM -> version.baseUnit == BaseUnit.GRAM
                ExternalPriceBridge.Unit.MILLILITRE ->
                    version.baseUnit == BaseUnit.MILLILITRE
                ExternalPriceBridge.Unit.PORTION -> version.baseUnit == BaseUnit.PORTION
            }
            if (!unitMatches) return null
            approved += candidate to visible
        }
        val ordered = approved.sortedBy { it.second.observationId }
        val revision = WriteRules.fingerprint(
            "RECIPE_AUTHORIZED_OFFERS_REVISION_V1",
            scopeId(scope), version.id, version.revision, version.recordedAt,
            version.baseUnit.name,
            ordered.map { (candidate, visible) ->
                listOf(candidate.observationId, candidate.sourceId,
                    candidate.rightsStatus.name, candidate.rightsEvidenceRef,
                    candidate.observedAt.toInstant().toString(),
                    candidate.validFrom?.toString(), candidate.validUntil?.toString(),
                    candidate.merchandiseCents, candidate.depositCents,
                    candidate.quantityMilli, candidate.unit?.name,
                    candidate.currency, candidate.conditionsVerified,
                    visible.merchandiseCents, visible.depositCents,
                    visible.checkoutCents, visible.validUntil.toString(),
                    visible.storeId, visible.regionId)
            }
        )
        val lines = ordered.map { (_, visible) ->
            RecipeContextContentBinding.Line(
                identity = "offer:${visible.observationId}",
                versionId = visible.productVersionId,
                amountMilli = visible.quantityMilli,
                unit = when (visible.unit) {
                    ExternalPriceBridge.Unit.GRAM -> "g"
                    ExternalPriceBridge.Unit.MILLILITRE -> "ml"
                    ExternalPriceBridge.Unit.PORTION -> "portion"
                },
                storeId = visible.storeId
            )
        }
        return RecipeContextContentBinding.Snapshot(
            RecipeContextContentBinding.Source.OFFERS,
            scopeId(scope), revision, capturedAt, lines
        )
    }
}
