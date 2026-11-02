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

    @Test fun mixedForeignCatalogConfirmationCannotAuthorizeCurrentDraft() = runBlocking {
        val confirmed = confirmation()
        val before = dao.operationCount()
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, preview(confirmed).status)
        val foreign = confirmed.copy(foodTypeId = "unrelated-food")
        val mixed = store.previewRecipeDraftV1(json, request,
            mapOf("rice" to confirmed, "unrelated-food" to foreign), states)
        assertEquals(Gate.Status.REVIEW_REQUIRED, mixed.status)
        assertNull(mixed.input)
        // A completely unrelated map is also not evidence for the ingredient.
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            store.previewRecipeDraftV1(json, request,
                mapOf("unrelated-food" to foreign), states).status)
        // A fresh, correctly scoped review can recover without any actual write.
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, preview(confirmed).status)
        assertEquals(before, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
        assertTrue(dao.allPaidPrices().isEmpty())
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
    @Test fun futureProductRevisionIsNotKnownAtEarlierReviewButInvalidatesLater() = runBlocking {
        val current = requireNotNull(dao.version(versionId))
        val future = current.copy(id = "future-v2", revision = 2, recordedAt = at + 60_000L)
        val line = Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G,
            Gate.Basis.NET, Gate.State.RAW)
        val currentConfirmation = confirmation()
        val versions = listOf(current, future)
        // Exercise the actual FoodStore -> Room catalog read, not only a pure adapter.
        dao.insertVersion(future)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, preview(currentConfirmation).status)
        val afterRevision = FoodStore(db) { at + 60_000L }
        assertEquals(Gate.Status.REVIEW_REQUIRED, afterRevision.previewRecipeDraftV1(
            json, request, mapOf("rice" to currentConfirmation), states).status)
        assertEquals(versionId, Catalog.resolve(listOf(line), versions,
            mapOf("rice" to currentConfirmation), at)["rice"]?.productVersionId)
        assertTrue(Catalog.resolve(listOf(line), versions,
            mapOf("rice" to currentConfirmation), at + 60_000L).isEmpty())
        val futureConfirmation = currentConfirmation.copy(
            productVersionId = future.id, productRevision = future.revision,
            versionRecordedAt = future.recordedAt, confirmedAt = future.recordedAt)
        assertTrue(Catalog.resolve(listOf(line), versions,
            mapOf("rice" to futureConfirmation), at).isEmpty())
        assertEquals(future.id, Catalog.resolve(listOf(line), versions,
            mapOf("rice" to futureConfirmation), at + 60_000L)["rice"]?.productVersionId)
        assertEquals(0, dao.consumptionCount())
        assertEquals(1, dao.operationCount()) // Only the setup product operation.
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

    @Test fun negativeCatalogOrConfirmationTimestampFailsClosedInRealRoomPreview() = runBlocking {
        val version = requireNotNull(dao.version(versionId))
        val confirmed = confirmation()
        val line = Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G,
            Gate.Basis.NET, Gate.State.RAW)
        fun resolve(v: ProductVersionEntity, c: Catalog.ConfirmedLine, time: Long = at) =
            Catalog.resolve(listOf(line), listOf(v), mapOf("rice" to c), time)
        assertEquals(versionId, resolve(version, confirmed)["rice"]?.productVersionId)
        assertTrue(resolve(version.copy(recordedAt = -1L),
            confirmed.copy(versionRecordedAt = -1L)).isEmpty())
        assertTrue(resolve(version, confirmed.copy(confirmedAt = -1L)).isEmpty())
        assertTrue(resolve(version, confirmed, -1L).isEmpty())
        val before = dao.operationCount()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = -1 WHERE id = ?", arrayOf(versionId))
        assertEquals(Gate.Status.REVIEW_REQUIRED,
            preview(confirmed.copy(versionRecordedAt = -1L)).status)
        assertEquals(before, dao.operationCount())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(version.recordedAt, versionId))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, preview(confirmed).status)
        assertEquals(before, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun duplicateOrBlankIngredientIdentityNeverProducesCatalogEvidence() = runBlocking {
        val version = requireNotNull(dao.version(versionId))
        val confirmed = confirmation()
        val line = Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G,
            Gate.Basis.NET, Gate.State.RAW)
        val before = dao.operationCount()
        assertEquals(versionId, Catalog.resolve(listOf(line), listOf(version),
            mapOf("rice" to confirmed), at)["rice"]?.productVersionId)

        // A duplicate foodTypeId used to be silently collapsed by toMap().
        assertTrue(Catalog.resolve(listOf(line, line.copy(label = "Other ingredient")),
            listOf(version), mapOf("rice" to confirmed), at).isEmpty())
        // A blank ID could also resolve if both the untrusted line and
        // caller-supplied confirmation happened to use the same blank key.
        val blank = line.copy(foodTypeId = "")
        assertTrue(Catalog.resolve(listOf(blank), listOf(version),
            mapOf("" to confirmed.copy(foodTypeId = "")), at).isEmpty())

        // Valid evidence still recovers, without adding any actual events.
        assertEquals(versionId, Catalog.resolve(listOf(line), listOf(version),
            mapOf("rice" to confirmed), at)["rice"]?.productVersionId)
        assertEquals(before, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
        assertTrue(dao.allPaidPrices().isEmpty())
    }


    @Test fun catalogConfirmationMustFollowDraftCreationWhenDraftTimeIsKnown() = runBlocking {
        val version = requireNotNull(dao.version(versionId))
        val confirmed = confirmation()
        val line = Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G,
            Gate.Basis.NET, Gate.State.RAW)
        val reviewedAt = at + 120_000L
        val draftCreatedAt = at + 60_000L
        val before = dao.operationCount()
        fun resolve(c: Catalog.ConfirmedLine, created: Long) =
            Catalog.resolve(listOf(line), listOf(version), mapOf("rice" to c),
                reviewedAt, draftCreatedAtMillis = created)

        // Catalog identity alone cannot approve a draft that did not exist
        // when the user reviewed the ingredient.
        assertTrue(resolve(confirmed, draftCreatedAt).isEmpty())
        assertTrue(resolve(confirmed, -1L).isEmpty())
        assertTrue(resolve(confirmed, reviewedAt + 1L).isEmpty())
        // Exact creation instant is a valid lower bound; a later fresh
        // confirmation is also valid.
        assertEquals(versionId, resolve(confirmed.copy(confirmedAt = draftCreatedAt),
            draftCreatedAt)["rice"]?.productVersionId)
        assertEquals(versionId, resolve(confirmed.copy(confirmedAt = reviewedAt),
            draftCreatedAt)["rice"]?.productVersionId)
        assertEquals(before, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
    }

    @Test fun reusedProductVersionCannotApproveTwoDifferentFoodTypeIngredients() = runBlocking {
        val version = requireNotNull(dao.version(versionId))
        val rice = Gate.DraftLine("rice", "Reis", 200, Gate.UnitCode.G,
            Gate.Basis.NET, Gate.State.RAW)
        val side = rice.copy(foodTypeId = "rice-side", label = "Reisbeilage")
        val confirmedRice = confirmation()
        val duplicate = confirmedRice.copy(foodTypeId = "rice-side")
        val confirmations = mapOf("rice" to confirmedRice, "rice-side" to duplicate)
        val before = dao.operationCount()

        // The manual-review entry point already rejects duplicate selected
        // versions. Direct FoodStore callers must not bypass that constraint.
        assertTrue(Catalog.resolve(listOf(rice, side), listOf(version),
            confirmations, at).isEmpty())
        val extraIngredient = """
            {"label":"Reisbeilage","foodTypeId":"rice-side","sourceStatus":"UNKNOWN",
             "quantity":{"amount":100,"unit":"g","basis":"NET","confirmed":false}}
        """.trimIndent().replace("\n", " ")
        val twoLineJson = json.replace("\"ingredients\":[",
            "\"ingredients\":[$extraIngredient,")
        val states = mapOf("rice" to Gate.State.RAW, "rice-side" to Gate.State.RAW)
        val blocked = store.previewRecipeDraftV1(twoLineJson, request,
            confirmations, states)
        assertEquals(Gate.Status.REVIEW_REQUIRED, blocked.status)
        assertNull(blocked.input)
        assertEquals(before, dao.operationCount())
        assertEquals(0, dao.consumptionCount())

        // Two independently identified catalog products still support a
        // positive read-only preview; no purchase or consumption is recorded.
        val sideVersionId = store.rememberProduct("product-rice-side",
            ProductInput("Reisbeilage", source = Source.USER,
                quality = Quality.CONFIRMED))
        val sideVersion = requireNotNull(dao.version(sideVersionId))
        val distinct = duplicate.copy(productVersionId = sideVersion.id,
            productRevision = sideVersion.revision,
            versionRecordedAt = sideVersion.recordedAt,
            unit = sideVersion.baseUnit)
        val count = dao.operationCount()
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE,
            store.previewRecipeDraftV1(twoLineJson, request,
                mapOf("rice" to confirmedRice, "rice-side" to distinct),
                states).status)
        assertEquals(count, dao.operationCount())
        assertEquals(0, dao.consumptionCount())
        assertTrue(dao.allPaidPrices().isEmpty())
    }
}
