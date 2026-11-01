package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.ExternalPriceBridge as Price
import de.ernaehrung.demo.data.integration.ExternalPriceCandidateSupplier
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.integration.RetailerPolicy as Policy
import de.ernaehrung.demo.data.integration.ShoppingOfferGate
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Room product identity plus independently gated host-cache offer snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeAuthorizedOffersSnapshotTest {
    private val at = Instant.parse("2026-11-01T04:00:00Z")
    private var clock = at.toEpochMilli()
    private val purchaseOn = LocalDate.parse("2026-11-02")
    private val flags = Gate.Flags(false, false, true)
    private val request = Gate.Request(flags, 2, userConfirmedRecipeSave = true)
    private val states = mapOf("rice" to Gate.State.RAW)
    private val json = """
        {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
         "meta":{"producer":"synthetic","traceId":"authorized-offers-test",
                 "createdAt":"2026-11-01T04:00:00Z",
                 "inputsSnapshotAt":"2026-11-01T04:00:00Z"},
         "payload":{"draftId":"authorized-offers","title":"Reis","portionCount":2,
           "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"UNKNOWN",
             "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":false}}],
           "steps":["Kochen"],"flags":{"considerStock":false,
             "considerShopping":false,"considerOffers":true},
           "warnings":[],"status":"DRAFT"}}
    """.trimIndent()

    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var versionId: String
    private lateinit var scope: RecipeAuthorizedOffersReader.Scope
    private var priceCents: Long? = 199L
    private var permitted = true

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        store = FoodStore(db) { clock }
        versionId = store.rememberProduct("offers-context-product",
            ProductInput("Reis", source = Source.USER, quality = Quality.CONFIRMED))
        scope = RecipeAuthorizedOffersReader.Scope(
            Policy.Retailer.LIDL, versionId, "canonical-lidl-42", "berlin", purchaseOn)
    }

    @After fun close() { db.close() }

    private fun candidate() = Price.Candidate(
        observationId = "published-offer-1",
        productVersionId = versionId,
        productVersionConfirmed = false, // never trust a feed identity assertion
        retailer = Policy.Retailer.LIDL,
        capability = Policy.Capability.PUBLISHED_OFFER_COLLECTION,
        sourceId = "approved-local-cache",
        origin = Price.Origin.EXTERNAL,
        rightsStatus = Price.RightsStatus.PERMITTED,
        rightsEvidenceRef = "host-rights-42",
        observedAt = OffsetDateTime.parse("2026-11-01T03:00:00Z"),
        validFrom = purchaseOn.minusDays(1),
        validUntil = purchaseOn.plusDays(1),
        storeId = scope.storeId,
        regionId = scope.regionId,
        merchandiseCents = priceCents,
        depositCents = null,
        quantityMilli = 200_000L,
        unit = Price.Unit.GRAM,
        conditionsVerified = true
    )

    private fun reader() = RecipeAuthorizedOffersReader(
        db.foodDao(),
        ExternalPriceCandidateSupplier { listOf(candidate()) },
        RecipeAuthorizedOffersReader.HostFactsRegistry {
            ShoppingOfferGate.HostFacts("SALE", "CONFIRMED", "AVAILABLE",
                false, "STORE_CONFIRMED", verifiedByHost = true)
        },
        Price.DisplayPermissionRegistry { source, ref, capability, day ->
            permitted && source == "approved-local-cache" &&
                ref == "host-rights-42" &&
                capability == Policy.Capability.PUBLISHED_OFFER_COLLECTION &&
                day == purchaseOn
        },
        ShoppingOfferGate.ConfirmedStoreRegistry { retailer, id, region, _ ->
            retailer == scope.retailer && id == scope.storeId && region == scope.regionId
        }
    )

    private suspend fun confirmations(): Map<String, Catalog.ConfirmedLine> {
        val v = requireNotNull(db.foodDao().version(versionId))
        return mapOf("rice" to Catalog.ConfirmedLine("rice", v.id, v.revision,
            v.recordedAt, clock, BaseUnit.GRAM, Gate.Basis.NET, Gate.State.RAW))
    }

    private suspend fun review(
        snapshot: de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot,
        source: RecipeAuthorizedOffersReader? = reader()
    ): Gate.Review {
        val context = Gate.typedContext(flags, at,
            null, null, Gate.Evidence(snapshot, true))
        return store.previewRecipeDraftV1(json, request, confirmations(), states,
            context, OffsetDateTime.parse("2026-11-01T04:00:00Z"),
            offersScope = scope, offersReader = source)
    }

    @Test fun approvedHostCacheAndRoomIdentityBindWithoutBooking() = runBlocking {
        val before = db.foodDao().operationCount()
        val snapshot = requireNotNull(store.readRecipeAuthorizedOffersSnapshot(scope, reader()))
        assertEquals("AUTHORIZED_HOST_OFFERS_V1", snapshot.scopeId.substringBefore("|"))
        assertEquals("published-offer-1", snapshot.lines.single().identity.removePrefix("offer:"))
        assertEquals(versionId, snapshot.lines.single().versionId)
        assertEquals(200_000L, snapshot.lines.single().amountMilli)
        assertEquals("g", snapshot.lines.single().unit)
        assertEquals(scope.storeId, snapshot.lines.single().storeId)
        val result = review(snapshot)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, result.status)
        assertNull(result.approvalBasisDigest)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun interveningOfferPriceRevisionRequiresNewReview() = runBlocking {
        val original = requireNotNull(store.readRecipeAuthorizedOffersSnapshot(scope, reader()))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(original).status)
        priceCents = 249L
        val current = requireNotNull(store.readRecipeAuthorizedOffersSnapshot(scope, reader()))
        assertNotEquals(original.revision, current.revision)
        val stale = review(original)
        assertEquals(Gate.Status.REVIEW_REQUIRED, stale.status)
        assertTrue("HOST_OFFERS_SNAPSHOT_STALE_OR_UNAUTHORIZED" in stale.gaps)
        assertNull(stale.input)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(current).status)
    }

    @Test fun revokedRightsOrMissingHostReaderFailClosed() = runBlocking {
        val original = requireNotNull(store.readRecipeAuthorizedOffersSnapshot(scope, reader()))
        permitted = false
        assertNull(store.readRecipeAuthorizedOffersSnapshot(scope, reader()))
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
        permitted = true
        val absent = review(original, null)
        assertEquals(Gate.Status.REVIEW_REQUIRED, absent.status)
        assertNull(absent.input)
    }

    @Test fun knownZeroOfferIsNotUnknownAndStaleSnapshotExpires() = runBlocking {
        priceCents = 0L
        val free = requireNotNull(store.readRecipeAuthorizedOffersSnapshot(scope, reader()))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(free).status)
        priceCents = null
        assertNull(store.readRecipeAuthorizedOffersSnapshot(scope, reader()))
        priceCents = 0L
        clock += 301_000L
        val expired = review(free)
        assertEquals(Gate.Status.REVIEW_REQUIRED, expired.status)
        assertNull(expired.input)
    }
}
