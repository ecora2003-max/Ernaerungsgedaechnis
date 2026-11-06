package de.ernaehrung.demo

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataQualityUiTest {
    @get:Rule(order = 0) val database: org.junit.rules.TestRule = IsolatedWorkspaceDatabase()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun finishEditing() {
        compose.runOnUiThread {
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(5_000) {
            Build.VERSION.SDK_INT < 30 || compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
        }
        compose.waitForIdle()
    }
    private fun tap(tag: String) {
        finishEditing(); waitTag(tag)
        if (tag.startsWith("workspace_tab_") || tag == "workspace_back") compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun tapText(text: String) {
        finishEditing()
        compose.waitUntil(25_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    @Test fun actualQualityActionsCorrectOnlyTheReviewedProductAndRetainHistoricalDiaryGapsWithoutDisplayWrites() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val token = "synthetic-quality-${UUID.randomUUID()}"
        val day = "2019-06-19"
        assertTrue("Synthetic diary day must be unoccupied", store.dayNutrition(day).meals.isEmpty())
        val input = ProductInput("Synthetische Qualitätsware").withNutrients(Nutrient.entries.associateWith { if (it == Nutrient.ENERGY) null else 0.0 })
        val old = store.rememberProduct("$token-product", input)
        val identity = db.foodDao().version(old)!!.productId
        store.consume("$token-meal", ConsumptionInput(old, day, "Synthetisch tatsächlich erfasst", 100_000), true)
        store.setDayStatus("$token-day", day, DayStatus.COMPLETE, store.dayNutrition(day).snapshot, true)
        val scan = store.saveReceiptScan("$token-scan", "QUALITAETSTEST\n19.06.2019\nWARE 1,00\nSUMME 1,00", "MANUAL_TEXT")
        val authBefore = db.healthDao().state("com.sec.android.app.shealth")
        val healthBefore = db.healthDao().history("com.sec.android.app.shealth")
        val bodyBefore = db.bodyDao().versions()
        val trainingBefore = db.manualWorkoutDao().versions()
        val batchesBefore = db.foodDao().planningBatches()
        try {
            val before = db.foodDao().operationCount()
            tap("workspace_tab_MORE"); tap("workspace_go_QUALITY")
            waitTag("quality_issue_product:$old")
            compose.onNodeWithTag("quality_issue_product:$old").performScrollTo().assertTextEquals(input.name)
            assertEquals(before, db.foodDao().operationCount())
            tap("quality_category_RECEIPT"); tap("quality_open_scan:$scan")
            waitTag("workspace_page_SCAN")
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Geprüften Bon als bezahlten Einkauf übernehmen").fetchSemanticsNodes().size == 1 }
            assertNull(db.foodDao().scanResolution(scan)); assertEquals(before, db.foodDao().operationCount())
            tap("workspace_back"); tap("quality_category_PRODUCT"); tap("quality_open_product:$old")
            waitTag("workspace_page_CATALOG")
            compose.onNodeWithText("Lokalen Produktkatalog durchsuchen").assertTextContains(identity)
            tapText("Produkt speichern / neue Version"); tapText("Produkt")
            val option = "${input.name} · $identity"
            compose.waitUntil(25_000) { compose.onAllNodesWithText(option).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(option).performScrollTo().performClick()
            compose.onNodeWithText("Energie in kcal · optional").performScrollTo().performTextReplacement("0")
            compose.onNodeWithText("Änderungsgrund").performScrollTo().performTextReplacement("Bekannte Null am Etikett ausdrücklich geprüft")
            tapText("Produkt lokal speichern")
            compose.waitUntil(25_000) { runBlocking { db.foodDao().latestVersion(identity)?.revision == 2 } }
            val fresh = db.foodDao().latestVersion(identity)!!
            assertEquals(0.0, fresh.energyKcal!!, 0.0)
            assertEquals(old, store.dayNutrition(day).meals.single().event.productVersionId)
            assertNull(db.foodDao().version(old)!!.energyKcal)
            assertEquals(2, db.foodDao().versions(identity).size)
            val afterSave = db.foodDao().operationCount()
            assertEquals(before + 1, afterSave)
            tap("workspace_back"); waitTag("quality_count_PRODUCT")
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("quality_issue_product:$old").fetchSemanticsNodes().isEmpty() }
            assertTrue(store.dataQuality().first().issues.none { it.category == DataQualityCategory.PRODUCT && it.argument == identity })
            tap("quality_category_DAY"); tap("quality_open_day:$day")
            waitTag("day_ENERGY")
            compose.onNodeWithTag("day_ENERGY").performScrollTo().assertTextContains("Unbekannt")
            compose.onNodeWithTag("day_status").assertTextEquals("Erfassung vollständig bestätigt")
            assertEquals(day, store.dayNutrition(day).date)
            tap("workspace_back")
            compose.activityRule.scenario.recreate(); waitTag("quality_issue_day:$day")
            assertEquals(afterSave, db.foodDao().operationCount())
            assertEquals(batchesBefore, db.foodDao().planningBatches())
            assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
            assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth"))
            assertEquals(bodyBefore, db.bodyDao().versions())
            assertEquals(trainingBefore, db.manualWorkoutDao().versions())
        } finally {
            if (db.foodDao().reversal("$token-meal") == null) store.reverse("$token-cleanup", ReversalInput("$token-meal", AppCalendar.today().toString(), "Synthetische Fixture bereinigen"), true)
            if (db.foodDao().scanResolution(scan) == null) store.discardReceiptScan("$token-discard", scan, "Synthetischer Test beendet", true)
        }
    }
}
