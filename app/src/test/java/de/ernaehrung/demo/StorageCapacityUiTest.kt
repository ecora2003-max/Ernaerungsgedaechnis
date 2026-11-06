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
class StorageCapacityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val confirmation = "Diese aktuellen tatsächlichen Angaben vor Ort geprüft und ausdrücklich bestätigen"
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun input(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)

    @Test fun actualRoomChangeRevokesOpenPhysicalFormWithoutReplacingItsFieldsOrSavingAFoodEvent() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val token = UUID.randomUUID().toString()
        val location = store.addLocation("stale-capacity-$token", "Synthetischer Gefrierschrank", true)
        val before = db.foodDao().operationCount()
        compose.onNodeWithTag("workspace_tab_MORE").performClick()
        tap("workspace_go_STORAGE_CAPACITY")
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("Lagerort für Kapazitätsprüfung").fetchSemanticsNodes().isNotEmpty() }
        tap("Lagerort für Kapazitätsprüfung"); tap("choose_$location")
        compose.onNodeWithTag("storage_capacity_free").assertTextEquals("Unbekannt")
        tap("storage_capacity_edit")
        input("storage_capacity_total_input", "5000")
        input("storage_capacity_occupied_input", "1000")
        input("storage_capacity_reason_input", "Eigene offene Prüfung")
        compose.onNodeWithText(confirmation).performScrollTo().performClick()
        compose.onNodeWithTag("storage_capacity_save").assertIsEnabled()
        val concurrent = store.reviewStorageCapacity("concurrent-$token", StorageCapacityInput(location, null,
            6000, 3000, "-18,0001", "Andere ausdrücklich bestätigte physische Prüfung"), true)
        compose.waitUntil(25_000) {
            compose.onAllNodesWithText("Prüfgrundlage inzwischen geändert oder nicht lesbar. Formular schließen und aktuelle Angaben erneut prüfen.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("storage_capacity_save").assertIsNotEnabled()
        compose.onNodeWithText(confirmation).assertIsOff()
        compose.onNodeWithTag("storage_capacity_total_input").assertTextContains("5000")
        compose.onNodeWithTag("storage_capacity_free").assertTextEquals("3000 ml")
        assertEquals(before + 1, db.foodDao().operationCount())
        assertEquals(concurrent, db.storageCapacityDao().latest(location)?.id)
        tap("storage_capacity_cancel"); tap("storage_capacity_edit")
        compose.onNodeWithTag("storage_capacity_total_input").assertTextContains("6000")
        compose.onNodeWithText(confirmation).assertIsOff()
        compose.onNodeWithTag("storage_capacity_save").assertIsNotEnabled()
        assertEquals(before + 1, db.foodDao().operationCount())
    }
}
