package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NutritionDayConfirmationOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) {
        finishDeviceTextEditing(compose); waitTag(tag)
        val node = compose.onNodeWithTag(tag)
        if (tag != "confirm_day_status") node.performScrollTo()
        node.performClick()
    }
    private fun text(text: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun field(label: String,value: String) = compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
    private fun day(date: String) {
        waitTag("nutrition_day_input")
        compose.onNodeWithTag("nutrition_day_input",useUnmergedTree = true).performScrollTo().performTextReplacement(date)
        tag("Tag anzeigen"); waitTag("nutrition_applied_day_$date")
    }

    @Test fun offlineDaySwitchPreservesTheUnconfirmedMealAndOnlyFreshGesturesCanSaveOrCompleteTheCorrectDay() = runBlocking {
        assertTrue("Synthetic nutrition fixture requires disposable emulator",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = compose.activity.applicationContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target); val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val first = "2020-04-09"; val second = "2020-04-10"
        assertTrue(store.dayNutrition(first).meals.isEmpty()); assertTrue(store.dayNutrition(second).meals.isEmpty())
        val version = store.rememberProduct("$token-product",ProductInput("Geräte-Tageszutat $token",energyKcal = 0.0))
        val before = dao.operationCount(); val meals = dao.activeConsumptionCount(); val batches = dao.planningBatches()
        val bodies = db.bodyDao().versions(); val health = db.healthDao().history("com.sec.android.app.shealth")
        val label = "Tatsächlicher Geräteverzehr $token"
        val approval = "Tatsächlich gegessen · lokal speichern"
        try {
            compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_NUTRITION")
            day(first); text("Tatsächlichen Verzehr erfassen")
            tag("Verzehr-Artikelversion"); tag("choose_$version")
            field("Mahlzeit / Beschreibung",label); field("Gegessene Menge in g","100")
            text(approval); compose.onNodeWithText(approval).assertIsOn()
            day(second)
            compose.onNodeWithText(approval).assertIsOff()
            compose.onNode(hasText("Gegessene Menge in g") and hasSetTextAction()).assertTextContains("100")
            tag("Verzehr lokal buchen")
            compose.waitUntil(25_000) { compose.onAllNodesWithText("Tatsächlichen Verzehr bestätigen.",substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before,dao.operationCount()); assertEquals(meals,dao.activeConsumptionCount())
            text(approval); compose.activityRule.scenario.recreate(); waitTag("nutrition_applied_day_$second")
            text("Tatsächlichen Verzehr erfassen")
            compose.onNodeWithText(approval).assertIsOff()
            compose.onNode(hasText("Mahlzeit / Beschreibung") and hasSetTextAction()).assertTextContains(label)
            text(approval); tag("Verzehr lokal buchen")
            compose.waitUntil(25_000) { runBlocking { store.dayNutrition(second).meals.size == 1 } }
            val meal = store.dayNutrition(second).meals.single()
            assertEquals(version,meal.event.productVersionId); assertEquals(second,meal.event.date)
            assertTrue(store.dayNutrition(first).meals.isEmpty())
            assertEquals(meal.event.id,store.consume(meal.event.operationId,ConsumptionInput(version,second,label,100_000L),true))
            assertEquals(before + 1,dao.operationCount()); assertEquals(batches,dao.planningBatches())
            tag("Neuen Vorgang beginnen"); waitTag("day_ENERGY")
            tag("Erfassung als vollständig bestätigen"); text("Alle Mahlzeiten dieses Tages sind erfasst")
            compose.onNodeWithTag("confirm_day_status").assertIsEnabled()
            compose.activityRule.scenario.recreate(); waitTag("nutrition_applied_day_$second")
            compose.onNodeWithTag("day_status_dialog").assertDoesNotExist()
            assertEquals(DayStatus.PARTIAL,store.dayNutrition(second).status)
            assertEquals(before + 1,dao.operationCount())
            tag("Erfassung als vollständig bestätigen")
            compose.onNodeWithText("Alle Mahlzeiten dieses Tages sind erfasst").assertIsOff()
            compose.onNodeWithTag("confirm_day_status").assertIsNotEnabled()
            text("Alle Mahlzeiten dieses Tages sind erfasst"); tag("confirm_day_status")
            compose.waitUntil(25_000) { runBlocking { store.dayNutrition(second).status == DayStatus.COMPLETE } }
            assertEquals(before + 2,dao.operationCount())
            // A fresh ViewModel is a separate owner-restart check, not a claim
            // that this instrumentation process was killed and relaunched.
            compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
            compose.activityRule.scenario.recreate(); waitTag("nutrition_applied_day_${AppCalendar.today()}")
            compose.onNodeWithTag("nutrition_day_input",useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(AppCalendar.today().toString())))
            val reopened = Room.databaseBuilder(target,FoodDatabase::class.java,FoodDatabase.TEST_DATABASE_NAME).build()
            try {
                val report = FoodStore(reopened).dayNutrition(second)
                assertEquals(DayStatus.COMPLETE,report.status); assertEquals(meal.event,report.meals.single().event)
                assertEquals(0,report.totals[Nutrient.ENERGY].knownAmount!!.signum())
                assertNull(report.totals[Nutrient.PROTEIN].knownAmount)
                assertEquals(before + 2,reopened.foodDao().operationCount())
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
            } finally { reopened.close() }
            assertEquals(meals + 1,dao.activeConsumptionCount()); assertEquals(batches,dao.planningBatches())
            assertEquals(bodies,db.bodyDao().versions()); assertEquals(health,db.healthDao().history("com.sec.android.app.shealth"))
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("nutrition-day-flow"); throw failure }
    }
}
