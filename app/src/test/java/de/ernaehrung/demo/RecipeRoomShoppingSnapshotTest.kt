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

    @Test fun shoppingReviewExpiresAtExactMillisecondNotRoundedSeconds() = runBlocking {
        store.savePlan("shopping-ttl-plan", plan(200_000L))
        val snapshot = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        val before = db.foodDao().operationCount()
        clock = at.plusMillis(300_000).toEpochMilli()
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(snapshot).status)
        clock = at.plusMillis(300_001).toEpochMilli()
        val stale = review(snapshot)
        assertEquals(Gate.Status.REVIEW_REQUIRED, stale.status)
        assertTrue("ROOM_SHOPPING_SNAPSHOT_STALE_OR_CHANGED" in stale.gaps)
        assertNull(stale.input)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
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

    @Test fun actualEventDateUsesSameBerlinDayAsRoomShopping() {
        val previousZone = java.util.TimeZone.getDefault()
        try {
            val midnightBoundary = Instant.parse("2026-11-01T23:30:00Z").toEpochMilli()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            // An actual event dated 2 November is today in Berlin, not future.
            WriteRules.actualDate("2026-11-02", midnightBoundary)
            try {
                WriteRules.actualDate("2026-11-03", midnightBoundary)
                fail("A genuinely future Berlin event must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: no implicit future event even if the device zone changes.
            }
        } finally {
            java.util.TimeZone.setDefault(previousZone)
        }
    }
    @Test fun crossingBerlinMidnightDuringSnapshotDoesNotMixPlanningDays() = runBlocking {
        store.savePlan("clock-boundary-plan", plan(200_000L))
        val operationsBefore = db.foodDao().operationCount()
        val beforeMidnight = Instant.parse("2026-11-01T22:59:59Z").toEpochMilli()
        val afterMidnight = Instant.parse("2026-11-01T23:00:01Z").toEpochMilli()
        var reads = 0
        store = FoodStore(db) { if (reads++ == 0) beforeMidnight else afterMidnight }

        val snapshot = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(1, reads)
        assertEquals(200_000L, snapshot.lines.single().amountMilli)
        assertEquals(0, db.foodDao().consumptionCount())
        assertEquals(operationsBefore, db.foodDao().operationCount())
    }

    @Test fun clockRollbackDuringSnapshotCannotAuthorizePastPurchaseDay() = runBlocking {
        store.savePlan("clock-rollback-plan", plan(200_000L))
        val operationsBefore = db.foodDao().operationCount()
        val beforeMidnight = Instant.parse("2026-11-01T22:59:59Z").toEpochMilli()
        val afterMidnight = Instant.parse("2026-11-01T23:00:01Z").toEpochMilli()
        var reads = 0
        store = FoodStore(db) { if (reads++ == 0) afterMidnight else beforeMidnight }

        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(1, reads)
        assertEquals(0, db.foodDao().consumptionCount())
        assertEquals(operationsBefore, db.foodDao().operationCount())
    }
    @Test fun futurePlanRevisionCannotAuthorizeEarlierShoppingContext() = runBlocking {
        val planId = store.savePlan("future-plan-shopping", plan(200_000L))
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        val before = db.foodDao().operationCount()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE plans SET recordedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(60).toEpochMilli(), planId))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        clock = at.plusSeconds(61).toEpochMilli()
        val later = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(original.lines, later.lines)
        assertNotEquals(original.revision, later.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun earlierPlanRevisionCannotBeFutureDatedOrFollowItsSuccessor() = runBlocking {
        val firstId = store.savePlan("plan-history-first", plan(200_000L))
        store.savePlan("plan-history-second", plan(300_000L), firstId)
        val before = db.foodDao().operationCount()
        val valid = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(300_000L, valid.lines.single().amountMilli)
        val firstRecordedAt = requireNotNull(db.foodDao().plan(firstId)).recordedAt

        // The latest revision is valid, but the earlier persisted revision
        // claims to have been recorded after it. A latest-only check misses it.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE plans SET recordedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(60).toEpochMilli(), firstId))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        // Waiting until the malformed timestamp is in the past does not
        // repair the reversed order of the two plan revisions.
        clock = at.plusSeconds(61).toEpochMilli()
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))

        db.openHelper.writableDatabase.execSQL(
            "UPDATE plans SET recordedAt = ? WHERE id = ?",
            arrayOf(firstRecordedAt, firstId))
        val repaired = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(300_000L, repaired.lines.single().amountMilli)
        assertEquals(valid.revision, repaired.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun invalidPlanRevisionTimeFailsClosedWithoutBooking() = runBlocking {
        val planId = store.savePlan("invalid-plan-shopping", plan(200_000L))
        val before = db.foodDao().operationCount()
        val original = requireNotNull(db.foodDao().plan(planId)).recordedAt
        db.openHelper.writableDatabase.execSQL(
            "UPDATE plans SET recordedAt = -1 WHERE id = ?", arrayOf(planId))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE plans SET recordedAt = ? WHERE id = ?", arrayOf(original, planId))
        assertEquals(200_000L,
            requireNotNull(store.readRecipeRoomShoppingSnapshot(scope)).lines.single().amountMilli)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun futureProductVersionCannotAuthorizeEarlierShoppingContext() = runBlocking {
        store.savePlan("future-version-shopping-plan", plan(200_000L))
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        val before = db.foodDao().operationCount()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(at.plusSeconds(60).toEpochMilli(), versionId))
        // A planning need alone cannot prove that its product version was
        // known when the recipe context was captured.
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        clock = at.plusSeconds(61).toEpochMilli()
        val later = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(original.lines, later.lines)
        assertNotEquals(original.revision, later.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun invalidProductVersionTimestampFailsClosedUntilCorrected() = runBlocking {
        store.savePlan("invalid-version-shopping-plan", plan(200_000L))
        val before = db.foodDao().operationCount()
        val recordedAt = requireNotNull(db.foodDao().version(versionId)).recordedAt
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = -1 WHERE id = ?",
            arrayOf(versionId))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(recordedAt, versionId))
        val recovered = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(200_000L, recovered.lines.single().amountMilli)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }


    @Test fun supersededRoomProductVersionCannotSubstantiateShoppingRecipeContext() = runBlocking {
        store.savePlan("superseded-version-shopping", plan(200_000L))
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        val before = db.foodDao().operationCount()
        val productId = requireNotNull(db.foodDao().version(versionId)).productId
        clock += 1_000L
        val replacement = store.rememberProduct("shopping-version-revision",
            ProductInput("Reis neu", source = Source.USER, quality = Quality.CONFIRMED),
            productId = productId)
        assertNotEquals(versionId, replacement)
        // The plan still points to the old version. Do not promote its need
        // to a current host-verified recipe snapshot after a catalog revision.
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)
        assertEquals(before + 1, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }


    @Test fun earlierCatalogRevisionCannotFollowItsSuccessorOrSilentlyChangeEvidence() = runBlocking {
        val olderVersionId = versionId
        val older = requireNotNull(db.foodDao().version(olderVersionId))
        clock += 1_000L
        versionId = store.rememberProduct("shopping-catalog-successor",
            ProductInput("Reis neu", source = Source.USER, quality = Quality.CONFIRMED),
            productId = older.productId)
        store.savePlan("shopping-catalog-history-plan", plan(200_000L))
        val valid = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(versionId, valid.lines.single().versionId)
        val before = db.foodDao().operationCount()
        val sql = db.openHelper.writableDatabase

        // The currently selected revision is sound, but its predecessor
        // claims to have been recorded later. A latest-only check misses it.
        sql.execSQL("UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(clock + 60_000L, olderVersionId))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(valid).status)
        // Passing the future timestamp cannot repair reversed revision history.
        clock += 61_000L
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))

        // A legitimate correction within the chronology must still invalidate
        // the previous context digest, even though the selected version is unchanged.
        sql.execSQL("UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(older.recordedAt + 500L, olderVersionId))
        val corrected = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(valid.lines, corrected.lines)
        assertNotEquals(valid.revision, corrected.revision)
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(valid).status)

        sql.execSQL("UPDATE product_versions SET recordedAt = ? WHERE id = ?",
            arrayOf(older.recordedAt, olderVersionId))
        val restored = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(valid.revision, restored.revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun historicalCatalogAllergenAndIdentityCorrectionsInvalidateShoppingEvidence() = runBlocking {
        val predecessor = requireNotNull(db.foodDao().version(versionId))
        // Keep capture and review at the same trusted instant so a stale
        // decision cannot be explained by an unrelated timestamp mismatch.
        versionId = store.rememberProduct("shopping-metadata-successor",
            ProductInput("Reis neu", source = Source.USER, quality = Quality.CONFIRMED),
            productId = predecessor.productId)
        store.savePlan("shopping-metadata-plan", plan(200_000L))
        val currentVersion = requireNotNull(db.foodDao().version(versionId))
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(original).status)
        val before = db.foodDao().operationCount()
        val sql = db.openHelper.writableDatabase

        // The selected version and planning need do not change. A corrected
        // allergen in its predecessor is nevertheless part of the provenance.
        sql.execSQL("UPDATE product_versions SET allergens = ? WHERE id = ?",
            arrayOf("Sesam", predecessor.id))
        val changedAllergen = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(original.lines, changedAllergen.lines)
        assertNotEquals(original.revision, changedAllergen.revision)
        val stale = review(original)
        assertEquals(Gate.Status.REVIEW_REQUIRED, stale.status)
        assertTrue("ROOM_SHOPPING_SNAPSHOT_STALE_OR_CHANGED" in stale.gaps)

        sql.execSQL("UPDATE product_versions SET allergens = ? WHERE id = ?",
            arrayOf(predecessor.allergens, predecessor.id))
        assertEquals(original.revision,
            requireNotNull(store.readRecipeRoomShoppingSnapshot(scope)).revision)

        // Likewise, a metadata correction to the selected catalog identity
        // cannot reuse a previously approved context digest.
        sql.execSQL("UPDATE product_versions SET name = ? WHERE id = ?",
            arrayOf("Reis mit geaenderter Bezeichnung", currentVersion.id))
        val changedIdentity = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(original.lines, changedIdentity.lines)
        assertNotEquals(original.revision, changedIdentity.revision)

        sql.execSQL("UPDATE product_versions SET name = ? WHERE id = ?",
            arrayOf(currentVersion.name, currentVersion.id))
        assertEquals(original.revision,
            requireNotNull(store.readRecipeRoomShoppingSnapshot(scope)).revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun persistedPlanEntryAndTitleCorrectionsInvalidateShoppingReview() = runBlocking {
        val planId = store.savePlan("shopping-plan-provenance", plan(200_000L))
        val planRow = requireNotNull(db.foodDao().plan(planId))
        val entry = db.foodDao().allPlannedEntries().single { it.planId == planId }
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(original).status)
        val before = db.foodDao().operationCount()
        val sql = db.openHelper.writableDatabase

        // Changing a persisted ingredient annotation leaves the calculated
        // shopping quantity untouched but changes its source evidence.
        sql.execSQL("UPDATE planned_entries SET label = ? WHERE id = ?",
            arrayOf("Manuell korrigierte Zutat", entry.id))
        val correctedEntry = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(original.lines, correctedEntry.lines)
        assertNotEquals(original.revision, correctedEntry.revision)
        val staleEntry = review(original)
        assertEquals(Gate.Status.REVIEW_REQUIRED, staleEntry.status)
        assertTrue("ROOM_SHOPPING_SNAPSHOT_STALE_OR_CHANGED" in staleEntry.gaps)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(correctedEntry).status)

        sql.execSQL("UPDATE planned_entries SET label = ? WHERE id = ?",
            arrayOf(entry.label, entry.id))
        assertEquals(original.revision,
            requireNotNull(store.readRecipeRoomShoppingSnapshot(scope)).revision)

        // The plan's recorded metadata is also part of the source revision.
        sql.execSQL("UPDATE plans SET title = ? WHERE id = ?",
            arrayOf("Geaenderter Wochenplan", planId))
        val correctedTitle = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(original.lines, correctedTitle.lines)
        assertNotEquals(original.revision, correctedTitle.revision)
        assertEquals(Gate.Status.REVIEW_REQUIRED, review(original).status)

        sql.execSQL("UPDATE plans SET title = ? WHERE id = ?",
            arrayOf(planRow.title, planId))
        assertEquals(original.revision,
            requireNotNull(store.readRecipeRoomShoppingSnapshot(scope)).revision)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }

    @Test fun shoppingRecipeEvidenceTracksCatalogProvenanceAndRejectsDemo() = runBlocking {
        store.savePlan("shopping-provenance-plan", plan(200_000L))
        val original = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(original).status)
        val operationsBefore = db.foodDao().operationCount()
        val originalVersion = requireNotNull(db.foodDao().version(versionId))
        assertEquals(Quality.CONFIRMED, originalVersion.quality)

        // A still-eligible source-quality correction changes independent
        // catalog evidence without changing the shopping need or version ID.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET quality = ? WHERE id = ?",
            arrayOf(Quality.SOURCED.name, versionId))
        val changed = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(original.lines, changed.lines)
        assertNotEquals(original.revision, changed.revision)
        val stale = review(original)
        assertEquals(Gate.Status.REVIEW_REQUIRED, stale.status)
        assertTrue("ROOM_SHOPPING_SNAPSHOT_STALE_OR_CHANGED" in stale.gaps)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(changed).status)

        // Demo provenance is never host-confirmed recipe context, even when
        // the persisted shopping plan still references the same product ID.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET source = ? WHERE id = ?",
            arrayOf(Source.DEMO.name, versionId))
        assertNull(store.readRecipeRoomShoppingSnapshot(scope))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_versions SET source = ?, quality = ? WHERE id = ?",
            arrayOf(originalVersion.source.name, originalVersion.quality.name, versionId))
        val restored = requireNotNull(store.readRecipeRoomShoppingSnapshot(scope))
        assertEquals(original.revision, restored.revision)
        assertEquals(Gate.Status.READY_FOR_EXPLICIT_SAVE, review(restored).status)
        assertEquals(operationsBefore, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().consumptionCount())
    }
}
