package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Default Android navigation + real Room + actual network absence.
 * Synthetic physical values never imply a real freezer measurement.
 */
@RunWith(AndroidJUnit4::class)
class StorageCapacityOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private val confirmation = "Diese aktuellen tatsächlichen Angaben vor Ort geprüft und ausdrücklich bestätigen"
    private fun tap(tag: String) {
        finishDeviceTextEditing(compose)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun input(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun confirm() { finishDeviceTextEditing(compose); compose.onNodeWithText(confirmation).performScrollTo().performClick() }
    private fun waitFree(value: String) = compose.waitUntil(25_000) {
        compose.onAllNodes(hasTestTag("storage_capacity_free") and hasText(value)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun actualPhysicalFormKeepsExactHistoryKnownZeroAndUnknownAcrossRecreationWithoutFoodBookings() = runBlocking {
        assertTrue("Synthetic physical data only on a disposable emulator",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Actual network absence required", connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val store = FoodStore(db)
        val token = UUID.randomUUID().toString()
        val location = store.addLocation("capacity-device-$token", "Synthetischer Gefrierschrank $token", true)
        val before = db.foodDao().operationCount()
        val batches = db.foodDao().planningBatches()
        val meals = db.foodDao().activeConsumptionCount()
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick()
            tap("workspace_go_STORAGE_CAPACITY")
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("Lagerort für Kapazitätsprüfung").fetchSemanticsNodes().isNotEmpty() }
            tap("Lagerort für Kapazitätsprüfung"); tap("choose_$location")
            waitFree("Unbekannt")
            // Cancelling a filled form cannot turn an unknown capacity into free space.
            tap("storage_capacity_edit")
            input("storage_capacity_total_input", "5000")
            input("storage_capacity_occupied_input", "1000")
            input("storage_capacity_reason_input", "Verworfene synthetische Prüfung")
            confirm(); tap("storage_capacity_cancel")
            assertEquals(before, db.foodDao().operationCount())
            assertTrue(db.storageCapacityDao().history(location).isEmpty())
            tap("storage_capacity_edit")
            input("storage_capacity_total_input", "5000")
            input("storage_capacity_occupied_input", "1000")
            val rawTemperature = "-18,000000000000000001"
            input("storage_capacity_temperature_input", rawTemperature)
            input("storage_capacity_reason_input", "Synthetisch vor Ort geprüft")
            compose.onNodeWithTag("storage_capacity_save").assertIsNotEnabled()
            confirm()
            input("storage_capacity_occupied_input", "1200")
            compose.onNodeWithText(confirmation).assertIsOff()
            compose.onNodeWithTag("storage_capacity_save").assertIsNotEnabled()
            input("storage_capacity_occupied_input", "1000")
            confirm(); tap("storage_capacity_save")
            waitFree("4000 ml")
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("storage_capacity_saved").fetchSemanticsNodes().isNotEmpty() }
            val first = requireNotNull(db.storageCapacityDao().latest(location))
            assertEquals(rawTemperature, first.temperatureCelsiusText)
            assertEquals(5000L, first.totalMl); assertEquals(1000L, first.occupiedMl)
            assertEquals(before + 1, db.foodDao().operationCount())
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("workspace_page_STORAGE_CAPACITY").assertExists()
            waitFree("4000 ml")
            compose.onNodeWithTag("storage_capacity_total_input").assertDoesNotExist()
            tap("storage_capacity_edit")
            input("storage_capacity_total_input", "")
            input("storage_capacity_occupied_input", "0")
            input("storage_capacity_reason_input", "Gesamtkapazität aktuell unbekannt; leerer Behälterbereich bestätigt")
            confirm(); tap("storage_capacity_save")
            waitFree("Unbekannt")
            val history = db.storageCapacityDao().history(location)
            assertEquals(2, history.size)
            assertEquals(first, history.first())
            assertNull(history.last().totalMl); assertEquals(0L, history.last().occupiedMl)
            assertEquals(first.id, history.last().previousId)
            assertEquals(before + 2, db.foodDao().operationCount())
            assertEquals(batches, db.foodDao().planningBatches())
            assertEquals(meals, db.foodDao().activeConsumptionCount())
            assertNull(db.foodDao().location(location)?.usableCapacityMl)
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("capacity-flow")
            throw failure
        }
    }
}
