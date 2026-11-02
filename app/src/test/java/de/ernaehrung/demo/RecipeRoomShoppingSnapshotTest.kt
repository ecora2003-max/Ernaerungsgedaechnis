package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.integration.RecipeCatalogEvidence as Catalog
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room planning/mark history, not a synthetic context DTO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeRoomShoppingSnapshotTest {
    private val at = Instant.parse("2026-11-01T04:00:00Z")
    private var clock = at.toEpochMilli()
    private val day = "2026-11-01"
    private val scope = ShoppingScope(day, "2026-11-07", day)
    private val flags = Gate.Flags(false, true, false)
    private val request = Gate.Request(flags, 2, userConfirmedRecipeSave = true)
    private val states = mapOf("rice" to Gate.State.RAW)
    private val json = """
        {"contractVersion":"1.0.0-draft","messageType":"recipe.draft",
         "meta":{"producer":"synthetic","traceId":"room-shopping-test",
                 "createdAt":"2026-11-01T04:00:00Z",
                 "inputsSnapshotAt":"2026-11-01T04:00:00Z"},
         "payload":{"draftId":"room-shopping","title":"Reis","portionCount":2,
           "ingredients":[{"label":"Reis","foodTypeId":"rice","sourceStatus":"UNKNOWN",
             "quantity":{"amount":200,"unit":"g","basis":"NET","confirmed":false}}],
           "steps":["Kochen"],"flags":{"considerStock":false,
             "considerShopping":true,"considerOffers":false},
           "warnings":[],"status":"DRAFT"}}
    """.trimIndent()

    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private lateinit var versionId: String

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
        ).build()
        store = FoodStore(db) { clock }
        versionId = store.rememberProduct("shopping-context-product",
            ProductInput("Reis", source = Source.USER, quality = Quality.CONFIRMED))
    }
    @After fun close() { db.close() }

    private fun plan(amount: Long?) = PlanInput("Testwoche", day, "2026-11-07",
        listOf(PlanEntryInput("rice-need", day, PlanKind.DIRECT_CONSUMPTION,
            versionId, amount = amount, unit = BaseUnit.GRAM)), "Planung")

    private suspend fun confirmations(): Map<String, Catalog.ConfirmedLine> {
        val v = requireNotNull(db.foodDao().version(versionId))
        return mapOf("rice" to Catalog.ConfirmedLine("rice", v.id, v.revision,
            v.recordedAt, clock, BaseUnit.GRAM, Gate.Basis.NET, Gate.State.RAW))
    }

    private suspend fun review(
        snapshot: de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot
    ): Gate.Review {
        val context = Gate.typedContext(flags, at,
            null, Gate.Evidence(snapshot, true), null)
        return store.previewRecipeDraftV1(json, request, confirmations(), states,
            context, OffsetDateTime.parse("2026-11-01T04:00:00Z"))
    }

    @Test fun roomPlanningNeedBindsButReviewNeverBooks() = runBlocking {
        store.savePlan("shopping-plan", plan(200_000L))
        val before = db.foodDao().operationCount()
        val snapshot = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals("LOCAL_ROOM_SHOPPING_V1:2026-11-01:2026-11-07:2026-11-01", snapshot.scopeId)
        assertEquals(versionId, snapshot.lines.single().versionId)
        assertEquals(200_000L, snapshot.lines.single().amountMilli)
        assertEquals("g", snapshot.lines.single().unit)
        val result = review(snapshot)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, result.status)
        assertNull(result.approvalBasisDigest) // no authenticated action
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun interveningPlanRevisionInvalidatesBoundShoppingReview() = runBlocking {
        val originalPlan = store.savePlan("shopping-plan", plan(200_000L))
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(original).status)
        store.savePlan("shopping-plan-change", plan(300_000L), originalPlan)
        val current = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertNotEquals(original.revision, current.revision)
        assertEquals(300_000L, current.lines.single().amountMilli)
        val stale = review(original)
        assertEquals(Gate.Status.REVIEW_REQUIRED, stale.status)
        assertTrue("ROOM_SHOPPING_SNAPSHOT_STALE_OR_CHANGED" in stale.gaps)
        assertNull(stale.input)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(current).status)
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun checklistRevisionChangesContextWithoutRecordingPurchase() = runBlocking {
        store.savePlan("shopping-plan", plan(200_000L))
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        val item = store.shoppingReport(scope).items.single()
        store.markShopping("shopping-check", ShoppingMarkInput(
            scope, versionId, null, true, item.snapshot))
        val current = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertNotEquals(original.revision, current.revision)
        assertEquals(original.lines, current.lines)
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
        assertEquals(0, db.foodDao().consumptionCount())
        assertTrue(db.foodDao().planningBatches().isEmpty())
    }

    @Test fun unknownQuantityAndUntrustedScopeFailClosed() = runBlocking {
        store.savePlan("shopping-plan", plan(null))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope.copy(budgetId = "unreviewed")))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope.copy(purchaseOn = "2026-10-31")))
    }

    @Test fun snapshotOlderThanFiveMinutesRequiresNewReview() = runBlocking {
        store.savePlan("shopping-plan", plan(200_000L))
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        clock += 301_000L
        val stale = review(original)
        assertEquals(Gate.Status.REVIEW_REQUIRED, stale.status)
        assertTrue("ROOM_SHOPPING_SNAPSHOT_STALE_OR_CHANGED" in stale.gaps)
        assertNull(stale.input)
    }

    @Test fun berlinDayBoundaryKeepsRoomShoppingAndPlanningStableAcrossDeviceTimezones() = runBlocking {
        val previousZone = java.util.TimeZone.getDefault()
        try {
            // 23:30 UTC is already the next calendar day in Berlin.
            clock = Instant.parse("2026-11-01T23:30:00Z").toEpochMilli()
            val berlinDay = "2026-11-02"
            val berlinScope = ShoppingScope(berlinDay, "2026-11-07", berlinDay)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            store.savePlan("berlin-day-plan", PlanInput("Berlin-Tag", berlinDay,
                "2026-11-07", listOf(PlanEntryInput("berlin-rice", berlinDay,
                    PlanKind.DIRECT_CONSUMPTION, versionId,
                    amount = 200_000L, unit = BaseUnit.GRAM)), "Planung"))
            val firstReport = store.shoppingReport(berlinScope)
            assertEquals(berlinDay, firstReport.asOf)
            assertEquals(200_000L, firstReport.items.single().need.knownMissing)
            val first = requireNotNull(store.readRecipeRoomShoppingSnapshot(berlinScope))
            assertEquals(200_000L, first.lines.single().amountMilli)

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val secondReport = store.shoppingReport(berlinScope)
            val second = requireNotNull(store.readRecipeRoomShoppingSnapshot(berlinScope))
            assertEquals(berlinDay, secondReport.asOf)
            assertEquals(first.revision, second.revision)
            assertEquals(first.lines, second.lines)
            assertEquals(0, db.foodDao().consumptionCount())
        } finally {
            java.util.TimeZone.setDefault(previousZone)
        }
    }

    @Test fun berlinPastPurchaseIsRejectedEvenWhenDeviceClockZoneStillShowsYesterday() = runBlocking {
        val previousZone = java.util.TimeZone.getDefault()
        try {
            clock = Instant.parse("2026-11-01T23:30:00Z").toEpochMilli()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            // At this instant Berlin is on 2 November: a 1 November shopping
            // purchase cannot use the current Room stock as a historical quote.
            val past = ShoppingScope("2026-11-01", "2026-11-07", "2026-11-01")
            assertNull(store.readRecipeRoomShoppingSnapshot(past))
            assertEquals(0, db.foodDao().consumptionCount())
        } finally {
            java.util.TimeZone.setDefault(previousZone)
        }
    }
}
