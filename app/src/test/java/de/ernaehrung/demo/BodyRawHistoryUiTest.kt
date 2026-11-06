package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BodyRawHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.waitForIdle(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) {
        waitTag(tag)
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test fun defaultReadOnlyWindowsUseCurrentCorrectionKeepUnknownZeroAndNeverCreateConsentOrWrites() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val today = LocalDate.now()
        val foodBefore = store.operations().first()
        val authBefore = db.healthDao().state("com.sec.android.app.shealth")
        val healthBefore = db.healthDao().history("com.sec.android.app.shealth")
        assertTrue(db.bodyDao().versions().isEmpty())
        val zero = store.saveBodyMeasurement("raw-zero", BodyMeasurementInput(BodyMetric.WEIGHT, "0,000", today.toString(),
            device = "Testwaage", method = BodyMethod.SCALE, conditions = "Morgens"), null, true)
        val oldInput = BodyMeasurementInput(BodyMetric.WEIGHT, "72,10", today.minusDays(1).toString())
        val old = store.saveBodyMeasurement("raw-old", oldInput, null, true)
        val corrected = store.saveBodyMeasurement("raw-corrected", oldInput.copy(rawValue = "72,200", localDate = today.minusDays(89).toString(),
            correctionReason = "Originales Messdatum korrigiert"), old, true)
        val unknown = store.saveBodyMeasurement("raw-unknown", BodyMeasurementInput(BodyMetric.WEIGHT, null, today.minusDays(2).toString()), null, true)
        val outside = store.saveBodyMeasurement("raw-outside", oldInput.copy(localDate = today.minusDays(90).toString()), null, true)
        val excluded = store.saveBodyMeasurement("raw-excluded", oldInput.copy(rawValue = "9999,9999",
            device = "Andere Testwaage", method = BodyMethod.BIA, quality = BodyQuality.MEASURED,
            conditions = "Abends nach Sport", excludedFromTrend = true), null, true)
        val originals = db.bodyDao().versions()
        tap("workspace_tab_MORE"); tap("workspace_go_BODY_RAW"); waitTag("body_raw_value_$zero")
        compose.onNodeWithTag("body_raw_value_$zero").performScrollTo().assertTextContains("0,000 kg", substring = true)
        compose.onNodeWithTag("body_raw_value_$unknown").performScrollTo().assertTextContains("Unbekannt", substring = true)
        compose.onNodeWithTag("body_raw_value_$old").assertDoesNotExist()
        compose.onNodeWithTag("body_raw_value_$corrected").assertDoesNotExist()
        tap("body_raw_period_DAYS_7")
        compose.onNodeWithTag("body_raw_count").performScrollTo().assertTextEquals("3 Messungen im Zeitraum · 2 außerhalb")
        compose.onNodeWithTag("body_plot_count_WEIGHT__kg").performScrollTo()
            .assertTextEquals("Rohmesspunkte · 2 darstellbar · 1 unbekannt")
        compose.onNodeWithTag("body_plot_WEIGHT__kg").performScrollTo().assertIsDisplayed()
            .assertContentDescriptionEquals("2 einzelne Rohmesspunkte, 1 unbekannte Werte, 1 ausgeschlossene Messpunkte weiterhin sichtbar. Keine Verbindung oder Glättung.")
        compose.onNodeWithTag("body_raw_gaps_WEIGHT_").performScrollTo().assertTextContains("5 Kalendertage", substring = true)
        compose.onNodeWithTag("body_raw_value_$excluded").performScrollTo().assertTextContains("9999,9999", substring = true)
        tap("body_raw_period_DAYS_90"); waitTag("body_raw_value_$corrected")
        compose.onNodeWithTag("body_raw_value_$corrected").performScrollTo().assertTextContains("72,200", substring = true)
        compose.onNodeWithTag("body_raw_value_$outside").assertDoesNotExist()
        compose.activityRule.scenario.recreate(); waitTag("body_raw_value_$corrected")
        compose.onNodeWithTag("body_raw_period_DAYS_90").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("body_plot_count_WEIGHT__kg").performScrollTo()
            .assertTextEquals("Rohmesspunkte · 3 darstellbar · 1 unbekannt")
        tap("body_raw_period_ALL"); waitTag("body_raw_value_$outside")
        assertEquals(originals, db.bodyDao().versions())
        assertEquals(foodBefore, store.operations().first())
        assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
        assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth"))
        assertEquals(6, store.bodyMeasurementHistory().first().versions.size)
        tap("body_raw_open_editor"); waitTag("body_save")
        compose.onNodeWithTag("body_confirm").performScrollTo().assertIsOff()
        compose.onNodeWithTag("body_save").assertIsNotEnabled()
        assertEquals(originals, db.bodyDao().versions())
        store.purgeBodyMeasurements("raw-ui-purge", true)
        tap("workspace_tab_MORE"); tap("workspace_go_BODY_RAW"); waitTag("body_raw_empty")
        compose.onNodeWithTag("body_raw_value_$zero").assertDoesNotExist()
        assertEquals(foodBefore, store.operations().first())
        assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
    }
}
