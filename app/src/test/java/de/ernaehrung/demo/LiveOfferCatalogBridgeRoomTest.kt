package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.offers.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real FoodStore/Room catalog amounts, synthetic independent reviews and
 * supplier quotes. Never a real source permission or retailer-data proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveOfferCatalogBridgeRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val recordedAt = Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
    private val at = Instant.ofEpochMilli(recordedAt + 2)
    private val day = LocalDate.parse("2026-11-08")
    private val scope = LiveOfferScope(LiveRetailer.LIDL, "DE123", null, null, day)
    @Before fun create() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { recordedAt }
    }
    @After fun close() = db.close()
    private suspend fun catalog(sku: String, pack: String, unit: BaseUnit): ProductVersionEntity {
        val id = store.rememberProduct("catalog-$sku", ProductInput("Synthetic $sku", unit = unit,
            packageAmount = Amounts.parse(pack), source = Source.USER, quality = Quality.CONFIRMED))
        return requireNotNull(db.foodDao().version(id))
    }
    private fun article(unit: BaseUnit, amount: Long, count: Long = 1) = ShoppingComparabilityKernel.Article(
        "synthetic-independently-reviewed-food", quantity = ShoppingUnitRateKernel.Quantity(amount, unit.label, "NET", count, true))
    private fun quote(id: String, cents: Long) = LiveOffer(id, "synthetic-source", LiveRetailer.LIDL,
        "Synthetic $id", id, null, cents, null, null, null, null, LiveOfferKind.PROMOTION,
        day, day, at, at, "DE123", null, null, LiveOfferApplicability.STORE_CONFIRMED, null, true,
        "https://offers.example.test/selected-store")
    private suspend fun compare(left: ProductVersionEntity, a: ShoppingComparabilityKernel.Article,
        right: ProductVersionEntity, b: ShoppingComparabilityKernel.Article, price: Long = 199): LivePriceComparison {
        val reviews = mapOf("a" to LiveArticleReview(left.id, a, null, true, true, true),
            "b" to LiveArticleReview(right.id, b, null, true, true, true))
        val confirmations = reviews.values.associate { review ->
            val version = if (review.productVersionId == left.id) left else right
            version.id to HostCatalogArticleRegistry.ConfirmedArticle(version.id, version.revision,
                version.recordedAt, recordedAt + 1, review.article, review.organic)
        }
        return LiveOfferComparison(LiveArticleReviewRegistry { offer, _, _ -> reviews[offer.sourceProductId] },
            HostCatalogArticleRegistry(db.foodDao().allVersions(), confirmations), sourceStillPermitted = { _, _ -> true })
            .compare(quote("a", price), scope, quote("b", 299), scope, at)
    }
    @Test fun actualCatalogEntryToReviewedOfferRatesUsesExactMilliunitBridgeForGramsMillilitresAndMultiplePacks(): Unit = runBlocking {
        for (unit in listOf(BaseUnit.GRAM, BaseUnit.MILLILITRE)) {
            val left = catalog("$unit-a", "500", unit); val right = catalog("$unit-b", "1000", unit)
            assertEquals(500_000L, left.packageAmount); assertEquals(1_000_000L, right.packageAmount)
            val before = db.foodDao().operationCount()
            val result = compare(left, article(unit, 500), right, article(unit, 1000))
            assertTrue(result.rateOrder!! > 0)
            val displayUnit = if (unit == BaseUnit.GRAM) "kg" else "l"
            assertEquals("3,98 €/$displayUnit · NET", formatReviewedOfferRate(result.targetRate!!))
            assertEquals("2,99 €/$displayUnit · NET", formatReviewedOfferRate(result.alternativeRate!!))
            val twoPacks = compare(left, article(unit, 500, 2), right, article(unit, 1000), 398)
            assertTrue(twoPacks.rateOrder!! > 0)
            assertEquals("3,98 €/$displayUnit · NET", formatReviewedOfferRate(twoPacks.targetRate!!))
            assertEquals(before, db.foodDao().operationCount())
            assertTrue(db.foodDao().stockMovementSnapshotRows().isEmpty()); assertTrue(db.foodDao().allPaidPrices().isEmpty())
            assertFalse(result.marketWideBestPriceProven)
        }
    }
    @Test fun actualHalfGramAndNonIntegralCatalogPacksCannotPassAsWholeGramsOrCreatePricesAndStock(): Unit = runBlocking {
        val right = catalog("right", "1000", BaseUnit.GRAM)
        for (pack in listOf("0.5", "500.001")) {
            val left = catalog("tiny-$pack", pack, BaseUnit.GRAM)
            val before = db.foodDao().operationCount()
            assertNull(compare(left, article(BaseUnit.GRAM, 500), right, article(BaseUnit.GRAM, 1000)).rateOrder)
            assertEquals(before, db.foodDao().operationCount())
            assertEquals(Amounts.parse(pack), db.foodDao().version(left.id)!!.packageAmount)
        }
        assertTrue(db.foodDao().stockMovementSnapshotRows().isEmpty()); assertTrue(db.foodDao().allPaidPrices().isEmpty())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
}
