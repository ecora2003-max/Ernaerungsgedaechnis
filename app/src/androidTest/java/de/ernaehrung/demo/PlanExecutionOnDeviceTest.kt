package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual default UI: create a plan, explicitly map existing meals, invalidate
 * live approval, recreate the Activity and reopen SQLite. No replacement content.
 */
@RunWith(AndroidJUnit4::class)
class PlanExecutionOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlockFixture() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun finishEditing() = finishDeviceTextEditing(compose)
    private fun tap(text: String) {
        finishEditing()

        compose.waitUntil(25_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun field(label: String, value: String) {
        compose.waitUntil(25_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    }
    private fun choose(label: String, id: String) {
        finishEditing()
        waitTag(label)
        compose.onNodeWithTag(label).performScrollTo().performClick()
        waitTag("choose_$id")
        compose.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }
    private fun approve() {
        tap("Abweichenden Termin oder Menge bewusst zuordnen")
        tap("Diese Ist-Buchung dieser Planposition zuordnen")
        compose.onNodeWithText("Diese Ist-Buchung dieser Planposition zuordnen").assertIsOn()
    }

    @Test fun actualPlanCreationLiveAssignmentRecreationAndRetryNeverCreateAnotherMeal(): Unit = runBlocking {
        assertTrue("Synthetic plan fixture requires a disposable emulator",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = compose.activity.applicationContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val dao = db.foodDao()
        val store = FoodStore(db)
        val fixture = "plan-device-" + UUID.randomUUID()
        val day = AppCalendar.today()
        val version = store.rememberProduct("$fixture-product", ProductInput("Geräteplanung Reis",
            source = Source.USER, quality = Quality.CONFIRMED, energyKcal = 100.0))
        val firstMeal = "$fixture-meal-one"
        val secondMeal = "$fixture-meal-two"
        for (key in listOf(firstMeal, secondMeal)) store.consume(key,
            ConsumptionInput(version, day.toString(), key, 50_000L, emptyList()), true)
        val capacityLocation = store.addLocation("$fixture-capacity-location","Geräteplanung Gefrierraum $fixture",true)
        store.reviewStorageCapacity("$fixture-capacity",StorageCapacityInput(capacityLocation,null,5000,1000,"-18,0040",
            "Synthetischer physischer Prüfstand für Planung"),true)
        val mealsBefore = dao.activeConsumptionCount()
        val stockBefore = dao.planningBatches()
        val nutritionBefore = store.dayNutrition(day.toString())
        val operationsBefore = dao.operationCount()
        val note = "Diesen bereits gegessenen Teil dem Tagesplan zuordnen"
        try {
            waitTag("workspace_tab_PLAN")
            compose.onNodeWithTag("workspace_tab_PLAN").performClick()
            tap("Plan anlegen oder überarbeiten")
            field("Plantitel", fixture)
            field("Planbeginn JJJJ-MM-TT", day.toString())
            field("Planende JJJJ-MM-TT", day.plusDays(6).toString())
            field("Geplanter Termin JJJJ-MM-TT", day.toString())
            choose("Planbezug", version)
            field("Geplante Menge · g · leer bei unbekannt", "200")
            tap("Zum Planentwurf hinzufügen")
            assertTrue(dao.allPlannedEntries().none { it.productVersionId == version })
            assertEquals(operationsBefore, dao.operationCount())
            field("Änderungsgrund des Plans", "Tagesbedarf am Gerät geprüft")
            tap("Planversion lokal speichern")
            var created: PlannedEntryEntity? = null
            compose.waitUntil(25_000) {
                created = runBlocking { dao.allPlannedEntries().singleOrNull { it.productVersionId == version } }
                created != null
            }
            val entry = requireNotNull(created)
            assertEquals(200_000L, entry.amount)
            assertEquals(operationsBefore + 1, dao.operationCount())
            assertEquals(mealsBefore, dao.activeConsumptionCount())
            tap("Plan-Nährwerte aus gespeicherten Verzehrpositionen")
            val week = "plan_nutrition_week_open_$day"
            waitTag(week)
            if (compose.onAllNodes(hasTestTag(week) and hasText("Plantage anzeigen")).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(week).performScrollTo().performClick()
            }
            waitTag("plan_nutrition_day_${day}_ENERGY")
            tap("Planpositionen und weitere Nährwerte · $day")
            compose.onNodeWithTag("plan_nutrition_entry_${entry.id}_ENERGY").performScrollTo().assertTextContains("200 kcal", substring = true)
            assertEquals(operationsBefore + 1, dao.operationCount())
            assertEquals(mealsBefore, dao.activeConsumptionCount())
            tap("Plan-Nährwerte aus gespeicherten Verzehrpositionen")
            tap("Lagerraum für die Planung")
            waitTag("plan_storage_${capacityLocation}_free")
            compose.onNodeWithTag("plan_storage_${capacityLocation}_free").performScrollTo().assertTextContains("4000 ml",substring = true)
            compose.onNodeWithTag("plan_storage_${capacityLocation}_temperature").assertTextContains("-18,0040",substring = true)
            compose.onNodeWithTag("plan_storage_open_$capacityLocation").performScrollTo().performClick()
            waitTag("workspace_page_STORAGE_CAPACITY"); waitTag("storage_capacity_free")
            compose.onNodeWithTag("storage_capacity_free").assertTextContains("4000 ml",substring = true)
            compose.onNodeWithTag("workspace_back").performClick(); waitTag("workspace_page_PLAN")
            assertEquals(operationsBefore + 1,dao.operationCount())
            assertEquals(mealsBefore,dao.activeConsumptionCount())
            waitTag("shopping_$version")
            compose.onNodeWithTag("shopping_$version").performScrollTo().assertTextContains("200 g", substring = true)
            tap("Neuen Vorgang beginnen")
            tap("Ist-Ausführung zuordnen oder zurücknehmen")
            choose("Planposition für Ist-Zuordnung", entry.id)
            waitTag("execution_remaining")
            choose("Bereits gebuchtes Ist-Ereignis", firstMeal)
            field("Begründung der Ist-Zuordnung", note)
            approve()
            compose.onNodeWithTag("Ist-Zuordnung speichern").assertIsEnabled()

            store.linkPlanExecution("$fixture-independent", LinkPlanExecutionInput(entry.id, secondMeal,
                store.planExecution(entry.id).status.snapshot, "Anderen gegessenen Teil unabhängig zugeordnet", true), true)
            compose.waitUntil(25_000) {
                runCatching { compose.onNodeWithTag("execution_remaining").assertTextEquals("Noch offen: 150 g"); true }
                    .getOrDefault(false)
            }
            compose.onNodeWithTag("Ist-Zuordnung speichern").assertIsNotEnabled()
            compose.onNodeWithText("Diese Ist-Buchung dieser Planposition zuordnen").assertIsOff()
            compose.onNodeWithText("Abweichenden Termin oder Menge bewusst zuordnen").assertIsOff()
            approve()
            compose.onNodeWithTag("Ist-Zuordnung speichern").assertIsEnabled()
            compose.activityRule.scenario.recreate()
            waitTag("workspace_page_PLAN")
            tap("Ist-Ausführung zuordnen oder zurücknehmen")
            waitTag("execution_remaining")
            compose.onNodeWithTag("Ist-Zuordnung speichern").assertIsNotEnabled()
            compose.onNodeWithText("Diese Ist-Buchung dieser Planposition zuordnen").assertIsOff()
            compose.onNodeWithText("Abweichenden Termin oder Menge bewusst zuordnen").assertIsOff()
            choose("Bereits gebuchtes Ist-Ereignis", firstMeal)
            field("Begründung der Ist-Zuordnung", note)
            val retry = LinkPlanExecutionInput(entry.id, firstMeal,
                store.planExecution(entry.id).status.snapshot, note, true)
            approve()
            tap("Ist-Zuordnung speichern")
            compose.waitUntil(25_000) {
                runCatching { compose.onNodeWithTag("execution_remaining").assertTextEquals("Noch offen: 100 g"); true }
                    .getOrDefault(false)
            }
            val links = dao.allPlanExecutions().filter { it.positionId == entry.positionId }
            assertEquals(2, links.size)
            val uiLink = links.single { it.actualOperationId == firstMeal }
            assertEquals(operationsBefore + 3, dao.operationCount())
            assertEquals(mealsBefore, dao.activeConsumptionCount())
            assertEquals(stockBefore, dao.planningBatches())
            assertEquals(nutritionBefore, store.dayNutrition(day.toString()))

            val reopened = Room.databaseBuilder(target, FoodDatabase::class.java, FoodDatabase.TEST_DATABASE_NAME)
                .addMigrations(FoodMigrations.MIGRATION_8_9, FoodMigrations.MIGRATION_9_10, FoodMigrations.MIGRATION_10_11, FoodMigrations.MIGRATION_11_12, FoodMigrations.MIGRATION_12_13, FoodMigrations.MIGRATION_13_14, FoodMigrations.MIGRATION_14_15, FoodMigrations.MIGRATION_15_16, FoodMigrations.MIGRATION_16_17).build()
            try {
                val restarted = FoodStore(reopened)
                assertEquals(100_000L, restarted.planExecution(entry.id).status.remainingAmount)
                assertEquals(uiLink.operationId, restarted.linkPlanExecution(uiLink.operationId, retry, true))
                assertEquals(2, reopened.foodDao().allPlanExecutions().count { it.positionId == entry.positionId })
                assertEquals(mealsBefore, reopened.foodDao().activeConsumptionCount())
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { reopened.close() }
            assertEquals(operationsBefore + 3, dao.operationCount())
            // A second real UI plan places additional demand outside seven
            // days. Opening shopping must carry the applied 56-day view.
            tap("Neuen Vorgang beginnen")
            tap("Plan anlegen oder überarbeiten")
            field("Plantitel", "$fixture-future")
            field("Planende JJJJ-MM-TT",day.plusDays(55).toString())
            field("Geplanter Termin JJJJ-MM-TT",day.plusDays(30).toString())
            choose("Planbezug",version)
            field("Geplante Menge · g · leer bei unbekannt","300")
            tap("Zum Planentwurf hinzufügen")
            field("Änderungsgrund des Plans","Weiteren Bedarf im Achtwochenzeitraum bewusst geplant")
            tap("Planversion lokal speichern")
            compose.waitUntil(25_000) { runBlocking { dao.allPlannedEntries().any { it.productVersionId == version && it.date == day.plusDays(30).toString() } } }
            compose.onNodeWithTag("plan_horizon_56 Tage").performScrollTo().performClick()
            tap("Planungszeitraum anzeigen")
            compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("planning_applied_scope") and hasText(day.plusDays(55).toString(),substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shopping_$version").performScrollTo().assertTextContains("400 g",substring = true)
            assertEquals(operationsBefore + 4,dao.operationCount())
            compose.onNodeWithTag("plan_open_shopping").performScrollTo().performClick()
            waitTag("workspace_page_SHOPPING")
            compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("shopping_applied_scope") and hasText(day.plusDays(55).toString(),substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shopping_horizon_56 Tage").assertIsSelected()
            compose.onNodeWithTag("shopping_need_$version").performScrollTo().assertTextContains("400 g",substring = true)
            compose.activityRule.scenario.recreate(); waitTag("workspace_page_SHOPPING")
            waitTag("shopping_applied_scope")
            compose.onNodeWithTag("shopping_applied_scope").assertTextContains(day.plusDays(55).toString(),substring = true)
            assertEquals(operationsBefore + 4,dao.operationCount())
            assertEquals(mealsBefore,dao.activeConsumptionCount()); assertEquals(stockBefore,dao.planningBatches())
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("plan-flow")
            throw failure
        }
    }
}
