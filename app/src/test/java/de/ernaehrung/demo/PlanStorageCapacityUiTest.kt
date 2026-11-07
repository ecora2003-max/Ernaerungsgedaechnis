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
@Config(sdk = [34],qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanStorageCapacityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun standardPlanReadsTheSamePhysicalHistoryKeepsUnknownDistinctFromZeroAndOpensTheSelectedLocationWithoutBooking() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val id = UUID.randomUUID().toString()
        val location = store.addLocation("$id-loc","Synthetischer Planungs-Gefrierschrank $id",true)
        val planned = store.addLocation("$id-planned","Nur geplanter Vorratsraum $id",false)
        val first = store.reviewStorageCapacity("$id-check",StorageCapacityInput(location,null,5000,1000,"-18,0040","Vor Ort für Planung geprüft"),true)
        val before = db.foodDao().operationCount()
        val batches = db.foodDao().planningBatches()
        val meals = db.foodDao().activeConsumptionCount()
        compose.onNodeWithTag("workspace_tab_PLAN").performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Lagerraum für die Planung").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Lagerraum für die Planung").performScrollTo().performClick()
        val free = "plan_storage_${location}_free"
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag(free) and hasText("4000 ml")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan_storage_${location}_temperature").assertTextContains("-18,0040",substring = true)
        compose.onNodeWithTag("plan_storage_planned_$planned").assertTextContains("unbekannt",substring = true)
        val unknown = store.reviewStorageCapacity("$id-unknown",StorageCapacityInput(location,first,5000,null,null,"Belegung ungeklärt"),true)
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag(free) and hasText("Unbekannt")).fetchSemanticsNodes().isNotEmpty() }
        store.reviewStorageCapacity("$id-zero",StorageCapacityInput(location,unknown,0,0,"-18,0040","Null tatsächlich geprüft"),true)
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag(free) and hasText("0 ml")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan_storage_open_$location").performScrollTo().performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("workspace_page_STORAGE_CAPACITY").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("storage_capacity_free") and hasText("0 ml")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before + 2,db.foodDao().operationCount())
        assertEquals(batches,db.foodDao().planningBatches()); assertEquals(meals,db.foodDao().activeConsumptionCount())
    }
}
