package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual default navigation and local calendar windows. Synthetic manual body
 * values only: this does not prove a physical Watch or provider import.
 */
@RunWith(AndroidJUnit4::class)
class BodyRawHistoryOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) {
        waitTag(tag)
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test fun offlineRawCalendarWindowsPreserveCorrectionsGapsQualityAndReadOnlyPrivacyAfterRestart() = runBlocking {
        assertTrue("Synthetic cleanup requires a disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Require actual network absence, not just disabled radios", connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        assertTrue(db.bodyDao().versions().isEmpty()); assertTrue(db.bodyDao().legacy().isEmpty())
        val store = FoodStore(db)
        val token = "synthetic-body-raw-${UUID.randomUUID()}"
        val today = LocalDate.now()
        val foodBefore = store.operations().first()
        val batchesBefore = db.foodDao().planningBatches()
        val mealsBefore = db.foodDao().activeConsumptionCount()
        val authBefore = db.healthDao().state("com.sec.android.app.shealth")
        val healthBefore = db.healthDao().history("com.sec.android.app.shealth")
        try {
            val zero = store.saveBodyMeasurement("$token-zero", BodyMeasurementInput(BodyMetric.WEIGHT, "0,000", today.toString(),
                device = "Synthetische Testwaage", method = BodyMethod.SCALE, conditions = "Morgens"), null, true)
            val originalInput = BodyMeasurementInput(BodyMetric.WEIGHT, "72,10", today.minusDays(1).toString())
            val original = store.saveBodyMeasurement("$token-original", originalInput, null, true)
            val corrected = store.saveBodyMeasurement("$token-corrected", originalInput.copy(rawValue = "72,200",
                localDate = today.minusDays(89).toString(), correctionReason = "Originales Messdatum korrigiert"), original, true)
            val unknown = store.saveBodyMeasurement("$token-unknown", originalInput.copy(rawValue = null, localDate = today.minusDays(2).toString()), null, true)
            val outside = store.saveBodyMeasurement("$token-outside", originalInput.copy(localDate = today.minusDays(90).toString()), null, true)
            val excluded = store.saveBodyMeasurement("$token-excluded", originalInput.copy(rawValue = "9999,9999",
                device = "Andere Testwaage", method = BodyMethod.BIA, quality = BodyQuality.MEASURED,
                conditions = "Abends nach Sport", excludedFromTrend = true), null, true)
            val originals = db.bodyDao().versions()
            val runCount = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM body_measurement_runs").use { it.moveToFirst(); it.getInt(0) }
            tap("workspace_tab_MORE"); tap("workspace_go_BODY_RAW"); waitTag("body_raw_value_$zero")
            compose.onNodeWithTag("body_raw_value_$zero").performScrollTo().assertTextContains("0,000 kg", substring = true)
            compose.onNodeWithTag("body_raw_value_$unknown").performScrollTo().assertTextContains("Unbekannt", substring = true)
            compose.onNodeWithTag("body_raw_value_$original").assertDoesNotExist()
            compose.onNodeWithTag("body_raw_value_$corrected").assertDoesNotExist()
            tap("body_raw_period_DAYS_7")
            compose.onNodeWithTag("body_raw_count").performScrollTo().assertTextEquals("3 Messungen im Zeitraum · 2 außerhalb")
            compose.onNodeWithTag("body_raw_gaps_WEIGHT_").performScrollTo().assertTextContains("5 Kalendertage", substring = true)
            compose.onNodeWithTag("body_raw_value_$excluded").performScrollTo().assertTextContains("9999,9999", substring = true)
            compose.onNodeWithText("Bioimpedanz · Geräteschätzung · Geräteschätzung").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Messbedingungen: Abends nach Sport").performScrollTo().assertIsDisplayed()
            tap("body_raw_period_DAYS_90"); waitTag("body_raw_value_$corrected")
            compose.onNodeWithTag("body_raw_value_$corrected").performScrollTo().assertTextContains("72,200", substring = true)
            compose.onNodeWithTag("body_raw_value_$outside").assertDoesNotExist()
            compose.activityRule.scenario.recreate(); waitTag("body_raw_value_$corrected")
            compose.onNodeWithTag("body_raw_period_DAYS_90").performScrollTo().assertIsSelected()
            tap("body_raw_period_ALL"); waitTag("body_raw_value_$outside")
            assertEquals(originals, db.bodyDao().versions())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM body_measurement_runs").use { it.moveToFirst(); assertEquals(runCount, it.getInt(0)) }
            assertEquals(foodBefore, store.operations().first()); assertEquals(batchesBefore, db.foodDao().planningBatches())
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
            assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth"))
            tap("body_raw_open_editor"); waitTag("body_save")
            compose.onNodeWithTag("body_confirm").performScrollTo().assertIsOff()
            compose.onNodeWithTag("body_save").assertIsNotEnabled()
            tap("workspace_tab_MORE"); tap("workspace_go_FITNESS"); tap("fitness_open_body_raw")
            waitTag("body_raw_screen")
            store.purgeBodyMeasurements("$token-purge", true); waitTag("body_raw_empty")
            compose.onNodeWithTag("body_raw_value_$zero").assertDoesNotExist()
            assertEquals(foodBefore, store.operations().first())
            assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("body-raw-history")
            throw failure
        } finally { store.purgeBodyMeasurements("$token-cleanup", true) }
    }
}
