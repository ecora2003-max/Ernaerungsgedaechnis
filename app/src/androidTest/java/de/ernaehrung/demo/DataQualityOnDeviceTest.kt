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
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataQualityOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun finishEditing() = finishDeviceTextEditing(compose)
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
        assertTrue("Synthetic fixture requires disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Require actual network absence", connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
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
            val reopened = Room.databaseBuilder(target, FoodDatabase::class.java, FoodDatabase.TEST_DATABASE_NAME)
                .addMigrations(FoodMigrations.MIGRATION_11_12).build()
            try {
                assertEquals(fresh, reopened.foodDao().latestVersion(identity))
                assertEquals(db.foodDao().version(old), reopened.foodDao().version(old))
                assertEquals(store.dataQuality().first(), FoodStore(reopened).dataQuality().first())
            } finally { reopened.close() }
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("data-quality")
            throw failure
        } finally {
            if (db.foodDao().reversal("$token-meal") == null) store.reverse("$token-cleanup", ReversalInput("$token-meal", AppCalendar.today().toString(), "Synthetische Fixture bereinigen"), true)
            if (db.foodDao().scanResolution(scan) == null) store.discardReceiptScan("$token-discard", scan, "Synthetischer Test beendet", true)
        }
    }
}
