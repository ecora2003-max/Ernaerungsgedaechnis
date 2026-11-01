package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic real-Room catalog evidence; preview never writes a recipe or actual event. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeCatalogEvidenceTest {
    private val at = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var versionId: String
    private val dao get() = db.foodDao()
    private val json = """
        {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
         "meta":{"producer":"synthetic","traceId":"catalog-test",
                 "createdAt":"2026-11-01T04:00:00Z"},
         "payload":{"draftId":"draft-catalog","title":"Reis","portionCount":2,
           "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"UNKNOWN",
             "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":false}}],
           "steps":["Kochen"],"flags":{"considerStock":false,
             "considerShopping":false,"considerOffers":false},
           "warnings":[],"status":"DRAFT"}}
    """.trimIndent()
    private val request = Gate.Request(Gate.Flags(false, false, false), 2,
        setOf("ALLERGEN:SOY"), userConfirmedRecipeSave = true)
    private val states = mapOf("rice" to Gate.State.RAW)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        store = FoodStore(db) { at }
        versionId = store.rememberProduct("product-rice", ProductInput("Reis",
            source = Source.USER, quality = Quality.CONFIRMED))
    }
    @After fun close() { db.close() }

    private suspend fun confirmation(): Catalog.ConfirmedLine {
        val v = requireNotNull(dao.version(versionId))
        return Catalog.ConfirmedLine("rice", v.id, v.revision, v.recordedAt,
            at, BaseUnit.GRAM, Gate.Basis.NET, Gate.State.RAW,
            allergens = mapOf("SOY" to false))
    }
    private suspend fun preview(c: Catalog.ConfirmedLine?) =
        store.previewRecipeDraftV1(json, request,
            if (c == null) emptyMap() else mapOf("rice" to c), states)

    @Test fun confirmedCurrentVersionAndExplicitAllergenAnswerAreReadOnly() = runBlocking {
        val before = dao.operationCount()
        val ready = preview(confirmation())
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, ready.status)
        assertEquals(versionId, ready.input!!.ingredients.single().versionId)
        assertEquals(200_000L, ready.input!!.ingredients.single().amount)
        assertEquals(before, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
        assertTrue(dao.allPaidPrices().isEmpty())
        assertTrue(dao.allVersions().size == 1)
    }

    @Test fun absentOrStaleIdentityAndUnconfirmedAllergensCannotSave() = runBlocking {
        val original = confirmation()
        assertEquals(Gate.Status.REVIEW_REQUIRED, preview(null).status)
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            preview(original.copy(allergens = emptyMap())).status)
        assertEquals(Gate.Status.BLOCKED,
            preview(original.copy(allergens = mapOf("SOY" to true))).status)
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            preview(original.copy(confirmedAt = at + 1)).status)
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            preview(original.copy(unit = BaseUnit.MILLILITRE)).status)
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            preview(original.copy(versionRecordedAt = at - 1)).status)
        val productId = requireNotNull(dao.version(versionId)).productId
        store.rememberProduct("rice-revision-2", ProductInput("Reis, neu",
            source = Source.USER, quality = Quality.CONFIRMED), productId, "Neue Version")
        assertEquals(Gate.Status.REVIEW_REQUIRED, preview(original).status)
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun unconfirmedCatalogQualityAndDemoSourcesNeverBecomeTrustedFacts() = runBlocking {
        val original = confirmation()
        val v = requireNotNull(dao.version(versionId))
        val line = Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G,
            Gate.Basis.NET, Gate.State.RAW)
        for (unsafe in listOf(
            v.copy(quality = Quality.UNKNOWN),
            v.copy(source = Source.DEMO)
        )) {
            assertTrue(Catalog.resolve(listOf(line), listOf(unsafe),
                mapOf("rice" to original), at).isEmpty())
        }
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            preview(original.copy(state = null)).status)
    }
    @Test fun ambiguousCatalogIdsAndLatestRevisionsFailClosed() = runBlocking {
        val version = requireNotNull(dao.version(versionId))
        val line = Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G,
            Gate.Basis.NET, Gate.State.RAW)
        val confirmed = mapOf("rice" to confirmation())
        fun resolve(versions: List<ProductVersionEntity>) =
            Catalog.resolve(listOf(line), versions, confirmed, at)

        assertEquals(versionId, resolve(listOf(version))["rice"]?.productVersionId)
        // A duplicated ID must not silently select the last row in associateBy.
        assertTrue(resolve(listOf(version, version.copy(name = "Conflicting name"))).isEmpty())
        assertTrue(resolve(listOf(version, version)).isEmpty())
        // Two rows claiming to be the latest revision of the same product are ambiguous.
        assertTrue(resolve(listOf(version, version.copy(id = "other-version"))).isEmpty())
        // The same version ID cannot belong to two different products.
        assertTrue(resolve(listOf(version, version.copy(productId = "other-product"))).isEmpty())
        // Invalid revision/identity metadata cannot be independently confirmed.
        val invalid = version.copy(revision = 0)
        assertTrue(Catalog.resolve(listOf(line), listOf(invalid),
            mapOf("rice" to confirmation().copy(productRevision = 0)), at).isEmpty())
        assertTrue(resolve(listOf(version.copy(productId = ""))).isEmpty())
        assertEquals(0, dao.consumptionCount())
        assertEquals(0, dao.operationCount() - 1) // only setup's product creation
    }

}
