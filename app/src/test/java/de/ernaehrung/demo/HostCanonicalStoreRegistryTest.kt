package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ExternalPriceBridge as Price
import de.ernaehrung.demo.data.integration.HostCanonicalStoreRegistry
import de.ernaehrung.demo.data.integration.RetailerPolicy as Policy
import de.ernaehrung.demo.data.integration.ShoppingOfferGate as Gate
import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class HostCanonicalStoreRegistryTest {
    private val asOf = OffsetDateTime.parse("2026-10-20T12:00:00Z")
    private val day = asOf.toLocalDate()
    private val recorded = OffsetDateTime.parse("2026-10-18T12:00:00Z")
        .toInstant().toEpochMilli()
    private val confirmed = OffsetDateTime.parse("2026-10-19T10:00:00Z")
        .toInstant().toEpochMilli()

    private fun snapshot(
        id: String = "canonical-42", revision: Int = 1,
        recordedAt: Long = recorded,
        retailer: Policy.Retailer = Policy.Retailer.LIDL,
        region: String? = "berlin",
        verified: Boolean = true
    ) = HostCanonicalStoreRegistry.StoreSnapshot(
        id, retailer, region, revision, recordedAt,
        LocalDate.parse("2026-10-01"), null, verified
    )

    private fun review(
        id: String = "canonical-42", revision: Int = 1,
        recordedAt: Long = recorded, at: Long = confirmed,
        retailer: Policy.Retailer = Policy.Retailer.LIDL,
        region: String? = "berlin",
        evidence: String = "host-review-1"
    ) = HostCanonicalStoreRegistry.StoreReview(
        id, retailer, region, revision, recordedAt, at, evidence
    )

    private fun registry(
        snapshots: List<HostCanonicalStoreRegistry.StoreSnapshot> = listOf(snapshot()),
        reviews: Map<String, HostCanonicalStoreRegistry.StoreReview> =
            mapOf("canonical-42" to review())
    ) = HostCanonicalStoreRegistry(snapshots, reviews)

    private fun confirmed(
        gate: HostCanonicalStoreRegistry = registry(),
        at: OffsetDateTime = asOf,
        store: String = "canonical-42",
        retailer: Policy.Retailer = Policy.Retailer.LIDL,
        region: String? = "berlin"
    ) = gate.isConfirmed(retailer, store, region, at)

    @Test fun exactIndependentlyReviewedStoreIsHistoricalAndScopeBound() {
        assertTrue(confirmed())
        assertFalse(confirmed(store = "Lidl am Bahnhof"))
        assertFalse(confirmed(store = "unknown"))
        assertFalse(confirmed(retailer = Policy.Retailer.ALDI_NORD))
        assertFalse(confirmed(region = "hamburg"))
        assertFalse(confirmed(region = null))
        assertFalse(confirmed(at = OffsetDateTime.parse("2026-10-18T13:00:00Z")))
    }

    @Test fun futureRevisionDoesNotInvalidateHistoricalReviewButDoesInvalidateCurrent() {
        val future = OffsetDateTime.parse("2026-10-21T10:00:00Z")
            .toInstant().toEpochMilli()
        val gate = registry(listOf(snapshot(), snapshot(revision = 2, recordedAt = future)))
        assertTrue(confirmed(gate))
        assertFalse(confirmed(gate, at = OffsetDateTime.parse("2026-10-22T12:00:00Z")))
        assertFalse(confirmed(registry(listOf(snapshot(), snapshot()))))
        assertFalse(confirmed(registry(listOf(snapshot(), snapshot(id = "canonical-42",
            revision = 1, recordedAt = recorded + 1)))))
    }

    @Test fun reviewProvenanceAndSnapshotFreshnessFailClosed() {
        assertFalse(confirmed(registry(reviews = emptyMap())))
        assertFalse(confirmed(registry(reviews = mapOf("canonical-42" to
            review(evidence = "")))))
        assertFalse(confirmed(registry(reviews = mapOf("canonical-42" to
            review(revision = 2)))))
        assertFalse(confirmed(registry(reviews = mapOf("canonical-42" to
            review(at = recorded - 1)))))
        assertFalse(confirmed(registry(reviews = mapOf("canonical-42" to
            review(at = asOf.plusDays(1).toInstant().toEpochMilli()))))
        assertFalse(confirmed(registry(snapshots = listOf(snapshot(verified = false)))))
        assertFalse(confirmed(registry(snapshots = listOf(snapshot(recordedAt = -1)))))
    }

    @Test fun closedOrNotYetOpenStoresCannotPassAsOf() {
        val base = snapshot()
        assertFalse(confirmed(registry(snapshots = listOf(
            base.copy(validFrom = day.plusDays(1))))))
        assertFalse(confirmed(registry(snapshots = listOf(
            base.copy(validUntil = day.minusDays(1))))))
        assertFalse(confirmed(registry(snapshots = listOf(
            base.copy(validFrom = day.plusDays(1), validUntil = day.minusDays(1))))))
        assertFalse(confirmed(registry(snapshots = listOf(
            base.copy(regionId = "hamburg")))))
    }

    @Test fun storeReviewNeverReplacesIndependentSourceDisplayRights() {
        val request = Price.Request(
            Policy.Retailer.LIDL, Policy.Capability.PUBLISHED_OFFER_COLLECTION,
            "product-v1", day, asOf, "canonical-42", "berlin"
        )
        val candidate = Price.Candidate(
            "offer-1", "product-v1", true, Policy.Retailer.LIDL,
            Policy.Capability.PUBLISHED_OFFER_COLLECTION, "feed-1",
            Price.Origin.EXTERNAL, Price.RightsStatus.PERMITTED, "claimed-rights",
            asOf.minusHours(1), day, day.plusDays(1),
            "canonical-42", "berlin", 199, 0, 500_000, Price.Unit.GRAM,
            conditionsVerified = true
        )
        val facts = Gate.HostFacts("SALE", "CONFIRMED", "AVAILABLE", false,
            "STORE_CONFIRMED", true)
        val denied = Gate.evaluate(request, candidate, facts, stores = registry())
        assertEquals(setOf(Gate.Gap.EXTERNAL_PRICE_REJECTED), denied.gaps)
        assertEquals(setOf(Price.Gap.MISSING_RIGHTS), denied.externalGaps)
        assertNull(denied.offer)
        val rights = Price.DisplayPermissionRegistry { source, evidence, capability, on ->
            source == "feed-1" && evidence == "claimed-rights" &&
                capability == Policy.Capability.PUBLISHED_OFFER_COLLECTION && on == day
        }
        val allowed = Gate.evaluate(request, candidate, facts, rights, registry())
        assertTrue(allowed.eligible)
        assertFalse(allowed.marketWideBestPriceProven)
        assertEquals("offer-1", allowed.offer!!.observationId)
    }
}
