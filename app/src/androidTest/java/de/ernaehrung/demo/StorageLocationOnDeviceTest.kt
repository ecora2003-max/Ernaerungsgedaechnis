package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.room.Room
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageLocationOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private val approval = "Name und Status geprüft und ausdrücklich bestätigen"
    private val present = "Dieser Lagerort ist tatsächlich vorhanden"
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun waitText(tag: String, value: String) = compose.waitUntil(25_000) {
        compose.onAllNodes(hasTestTag(tag) and hasText(value)).fetchSemanticsNodes().size == 1
    }
    private fun waitCapacityEnabled() = compose.waitUntil(25_000) {
        compose.onAllNodes(hasTestTag("storage_capacity_edit") and isEnabled()).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) { finishDeviceTextEditing(compose); waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun text(value: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(value).performScrollTo().performClick()
    }
    private fun input(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)

    @Test fun plannedPlaceBecomesTheSameRealLocationWithFreshConsentAndIndependentCapacityThenKeepsStockAndHistoryOffline() = runBlocking {
        assertTrue("Synthetic location fixtures require a disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context = compose.activity.applicationContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(context); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val day = AppCalendar.today().toString()
        val place = store.addLocation("$token-plan", "Geplant $token", false)
        val version = store.rememberProduct("$token-product", ProductInput("Lagerortware $token", energyKcal = 0.0))
        val original = dao.originalLocations().single { it.id == place }
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount()
        val prices = dao.allPaidPrices(); val bodies = db.bodyDao().versions(); val health = db.healthDao().history("com.sec.android.app.shealth")
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_STORAGE_CAPACITY")
            tap("Lagerort für Kapazitätsprüfung"); tap("choose_$place")
            compose.onNodeWithTag("storage_capacity_edit").assertIsNotEnabled()
            tap("storage_location_edit"); input("storage_location_name", "Küche $token")
            text(present); input("storage_location_reason", "Jetzt tatsächlich vorhanden"); text(approval)
            compose.activityRule.scenario.recreate(); waitTag("storage_location_edit")
            compose.onNodeWithTag("storage_location_save").assertDoesNotExist()
            assertEquals(before, dao.operationCount()); assertFalse(dao.location(place)!!.confirmed)
            tap("storage_location_edit"); input("storage_location_name", "Küche $token")
            text(present); input("storage_location_reason", "Jetzt tatsächlich vorhanden"); text(approval)
            tap("storage_location_save"); waitTag("storage_location_saved")
            waitText("storage_capacity_free", "Unbekannt")
            compose.onNodeWithTag("storage_capacity_free").assertTextEquals("Unbekannt")
            assertEquals(original, dao.originalLocations().single { it.id == place })
            assertTrue(dao.location(place)!!.confirmed); assertEquals(before + 1, dao.operationCount())
            waitCapacityEnabled(); tap("storage_capacity_edit"); input("storage_capacity_total_input", "1500")
            input("storage_capacity_occupied_input", "0"); input("storage_capacity_temperature_input", "4,000")
            input("storage_capacity_reason_input", "Separat vor Ort geprüft")
            text("Diese aktuellen tatsächlichen Angaben vor Ort geprüft und ausdrücklich bestätigen")
            tap("storage_capacity_save"); waitTag("storage_capacity_saved")
            waitText("storage_capacity_free", "1500 ml")
            compose.onNodeWithTag("storage_capacity_free").assertTextEquals("1500 ml")
            // Confirmed synthetic fixture uses the same identity at the actual
            // FoodStore boundary; receipt UI has separate existing device cases.
            val batch = store.receive("$token-receive", BatchInput(version, place, 10_000, day, release = ReleaseStatus.RELEASED), true)
            val movements = dao.stockMovementSnapshotRows()
            tap("storage_location_edit"); text(present); input("storage_location_reason", "Noch Bestand vorhanden")
            text(approval); tap("storage_location_save")
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Hier liegt noch Bestand.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(dao.location(place)!!.confirmed); assertEquals(10_000L, dao.balance(batch))
            assertEquals(movements, dao.stockMovementSnapshotRows()); tap("storage_location_cancel")
            tap("storage_location_edit"); input("storage_location_name", "Umbenannt $token")
            input("storage_location_reason", "Name vor Ort korrigiert"); text(approval)
            tap("storage_location_save"); waitTag("storage_location_saved")
            waitText("storage_capacity_free", "Unbekannt")
            compose.onNodeWithTag("storage_capacity_free").assertTextEquals("Unbekannt")
            assertEquals(movements, dao.stockMovementSnapshotRows()); assertEquals(meals, dao.activeConsumptionCount())
            val review = db.storageLocationReviewDao().latest(place)!!
            assertEquals(review.id, store.reviewStorageLocation(review.operationId, StorageLocationReviewInput(place,
                review.previousId, review.capacityReviewId, review.name, review.confirmed, review.reason), true))
            val reopened = Room.databaseBuilder(context, FoodDatabase::class.java, FoodDatabase.TEST_DATABASE_NAME).build()
            try {
                assertEquals(original, reopened.foodDao().originalLocations().single { it.id == place })
                assertEquals(dao.location(place), reopened.foodDao().location(place)); assertEquals(10_000L, reopened.foodDao().balance(batch))
                assertEquals(db.storageLocationReviewDao().history(), reopened.storageLocationReviewDao().history())
                assertEquals(db.storageCapacityDao().history(), reopened.storageCapacityDao().history())
                assertEquals(movements, reopened.foodDao().stockMovementSnapshotRows())
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { reopened.close() }
            assertEquals(prices, dao.allPaidPrices()); assertEquals(bodies, db.bodyDao().versions())
            assertEquals(health, db.healthDao().history("com.sec.android.app.shealth")); assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("storage-location-flow"); throw failure }
    }
}
