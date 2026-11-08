package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageLocationReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val approval = "Name und Status geprüft und ausdrücklich bestätigen"
    private val present = "Dieser Lagerort ist tatsächlich vorhanden"
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun waitText(tag: String, value: String) = compose.waitUntil(25_000) {
        compose.onAllNodes(hasTestTag(tag) and hasText(value)).fetchSemanticsNodes().size == 1
    }
    private fun waitCapacityEnabled() = compose.waitUntil(25_000) {
        compose.onAllNodes(hasTestTag("storage_capacity_edit") and isEnabled()).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun input(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun text(value: String) = compose.onNodeWithText(value).performScrollTo().performClick()
    private fun open(place: String) {
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tap("workspace_go_STORAGE_CAPACITY")
        waitTag("Lagerort für Kapazitätsprüfung"); tap("Lagerort für Kapazitätsprüfung"); tap("choose_$place")
    }

    @Test fun plannedPlaceHasARealConfirmationPathWithTheSameIdentityAndASeparateCapacityCheck() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val place = store.addLocation("$token-plan", "Geplant $token", false)
        val before = db.foodDao().operationCount(); open(place)
        compose.onNodeWithTag("storage_capacity_edit").assertIsNotEnabled()
        tap("storage_location_edit"); input("storage_location_name", "Kühlschrank $token")
        text(present); input("storage_location_reason", "Gerät jetzt tatsächlich vorhanden")
        text(approval); input("storage_location_name", "Küche $token")
        compose.onNodeWithText(approval).assertIsOff()
        compose.onNodeWithTag("storage_location_save").assertIsNotEnabled()
        assertEquals(before, db.foodDao().operationCount())
        text(approval); tap("storage_location_save"); waitTag("storage_location_saved")
        compose.waitUntil(25_000) { runBlocking { db.foodDao().location(place)?.confirmed == true } }
        assertEquals(before + 1, db.foodDao().operationCount()); assertEquals("Küche $token", db.foodDao().location(place)!!.name)
        assertEquals("Geplant $token", db.foodDao().originalLocations().single { it.id == place }.name)
        assertTrue(db.storageCapacityDao().history(place).isEmpty())
        compose.onNodeWithTag("storage_capacity_free").assertTextEquals("Unbekannt")
        waitCapacityEnabled(); tap("storage_capacity_edit")
        input("storage_capacity_total_input", "2500"); input("storage_capacity_occupied_input", "0")
        input("storage_capacity_reason_input", "Neue separate physische Prüfung")
        text("Diese aktuellen tatsächlichen Angaben vor Ort geprüft und ausdrücklich bestätigen")
        tap("storage_capacity_save"); waitTag("storage_capacity_saved")
        waitText("storage_capacity_free", "2500 ml")
        compose.onNodeWithTag("storage_capacity_free").assertTextEquals("2500 ml")
        assertEquals(before + 2, db.foodDao().operationCount())
    }

    @Test fun activityRecreationAndConcurrentPhysicalReviewCannotReuseOrReplaceAnOpenLocationApproval() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val place = store.addLocation("$token-place", "Vorrat $token", true)
        val before = db.foodDao().operationCount(); open(place); tap("storage_location_edit")
        input("storage_location_name", "Eigener Entwurf"); input("storage_location_reason", "Eigener Grund"); text(approval)
        compose.activityRule.scenario.recreate(); waitTag("storage_location_edit")
        compose.onNodeWithTag("storage_location_save").assertDoesNotExist()
        assertEquals(before, db.foodDao().operationCount())
        tap("storage_location_edit"); input("storage_location_name", "Neuer eigener Entwurf")
        input("storage_location_reason", "Erneut geprüft"); text(approval)
        store.reviewStorageCapacity("$token-concurrent", StorageCapacityInput(place, null, 1000, 0, null, "Andere aktuelle Prüfung"), true)
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Lagerort oder Kapazitätsprüfung inzwischen geändert. Formular schließen und aktuellen Stand erneut prüfen.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("storage_location_save").assertIsNotEnabled(); compose.onNodeWithText(approval).assertIsOff()
        compose.onNodeWithTag("storage_location_name").assertTextContains("Neuer eigener Entwurf")
        assertEquals(before + 1, db.foodDao().operationCount()); assertNull(db.storageLocationReviewDao().latest(place))
    }
}
