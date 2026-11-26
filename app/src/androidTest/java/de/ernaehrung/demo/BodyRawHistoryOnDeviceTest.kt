package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.Forest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual default navigation and local calendar windows for synthetic manual
 * values and an independently authorized synthetic Health Connect source copy. */
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
        assertTrue(db.healthExtraDao().latest().isEmpty())
        val older = today.minusDays(42)
        val offset = ZoneOffset.ofHours(9)
        val point = older.atTime(0, 15).toInstant(offset).toEpochMilli()
        val source = SyntheticExtraSource().apply {
            response = { query ->
                val dated = syntheticExtraRecord(query, HealthExtraKind.BODY_FAT).copy(
                    externalId = "older-body-fat", startAt = point, startOffsetSeconds = offset.totalSeconds,
                    scalarBits = 9.125.toRawBits())
                val undated = dated.copy(externalId = "unknown-body-fat-day", startOffsetSeconds = null,
                    clientRecordId = "synthetic-undated-body-fat", scalarBits = 13.375.toRawBits())
                HealthExtraSnapshot(query = query, capturedAt = query.requestedAt, records = listOf(dated, undated))
            }
        }
        try {
            store.authorizeHealthExtra("$token-import-authorize",
                HealthExtraSelection(setOf(HealthExtraKind.BODY_FAT), 90, historyOptIn = true), source, true)
            store.syncHealthExtra("$token-import-sync", source)
            store.authorizeHealthExtra("$token-import-stop", null, source, true)
            val importedBefore = store.watchImportedBodyFat().first()
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
            waitTag("body_raw_health_value_unknown-body-fat-day")
            compose.onNodeWithTag("body_raw_health_value_unknown-body-fat-day").performScrollTo().assertTextEquals("13,375 %")
            compose.onNodeWithText("Lokale Quellzeit: unbekannt").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("body_raw_health_value_older-body-fat").assertDoesNotExist()
            compose.onNodeWithTag("body_raw_value_$zero").performScrollTo().assertTextContains("0,000 kg", substring = true)
            compose.onNodeWithTag("body_raw_value_$unknown").performScrollTo().assertTextContains("Unbekannt", substring = true)
            compose.onNodeWithTag("body_raw_value_$original").assertDoesNotExist()
            compose.onNodeWithTag("body_raw_value_$corrected").assertDoesNotExist()
            tap("body_raw_period_DAYS_7")
            compose.onNodeWithTag("body_raw_count").performScrollTo().assertTextEquals("3 Messungen im Zeitraum · 2 außerhalb")
            compose.onNodeWithTag("body_plot_count_WEIGHT__kg").performScrollTo()
                .assertTextEquals("Rohmesspunkte · 2 darstellbar · 1 unbekannt")
            val canvas = compose.onNodeWithTag("body_plot_WEIGHT__kg").performScrollTo().assertIsDisplayed()
                .assertContentDescriptionEquals("2 einzelne Rohmesspunkte, 1 unbekannte Werte, 1 ausgeschlossene Messpunkte weiterhin sichtbar. Keine Verbindung oder Glättung.")
            // Actual rendered chart, not just a label: the known-zero scale
            // circle and excluded BIA cross must both produce their own pixels.
            val pixels = canvas.captureToImage().toPixelMap()
            val count = mutableMapOf<Int, Int>()
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val color = pixels[x, y].toArgb(); count[color] = (count[color] ?: 0) + 1
            }
            assertTrue("Known zero circle must actually render", (count[Forest.toArgb()] ?: 0) > 12)
            assertTrue("Excluded BIA cross must actually render", (count[Color(0xFF945312).toArgb()] ?: 0) > 12)
            compose.onNodeWithTag("body_raw_gaps_WEIGHT_").performScrollTo().assertTextContains("5 Kalendertage", substring = true)
            compose.onNodeWithTag("body_raw_value_$excluded").performScrollTo().assertTextContains("9999,9999", substring = true)
            compose.onNodeWithText("Bioimpedanz · Geräteschätzung · Geräteschätzung").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Messbedingungen: Abends nach Sport").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("body_raw_reference_field").performScrollTo().performTextReplacement("2026-02-30")
            compose.onNodeWithTag("body_raw_apply_reference").assertIsNotEnabled()
            compose.onNodeWithTag("body_raw_reference_field").performScrollTo().performTextReplacement(older.toString())
            finishDeviceTextEditing(compose)
            tap("body_raw_apply_reference")
            waitTag("body_raw_health_value_older-body-fat")
            compose.onNodeWithTag("body_raw_health_value_older-body-fat").performScrollTo().assertTextEquals("9,125 %")
            compose.onNodeWithTag("body_plot_BODY_FAT_Health Connect_%").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Lokale Quellzeit: ${older}T00:15+09:00").performScrollTo().assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            waitTag("body_raw_health_value_older-body-fat")
            assertEquals(importedBefore, store.watchImportedBodyFat().first()); assertEquals(1, source.reads)
            tap("body_raw_today")
            compose.onNodeWithTag("body_raw_health_value_older-body-fat").assertDoesNotExist()
            tap("body_raw_period_DAYS_90"); waitTag("body_raw_value_$corrected")
            compose.onNodeWithTag("body_raw_value_$corrected").performScrollTo().assertTextContains("72,200", substring = true)
            compose.onNodeWithTag("body_raw_value_$outside").assertDoesNotExist()
            compose.activityRule.scenario.recreate(); waitTag("body_raw_value_$corrected")
            compose.onNodeWithTag("body_raw_period_DAYS_90").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("body_plot_count_WEIGHT__kg").performScrollTo()
                .assertTextEquals("Rohmesspunkte · 3 darstellbar · 1 unbekannt")
            tap("body_raw_period_ALL"); waitTag("body_raw_value_$outside")
            assertEquals(originals, db.bodyDao().versions())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM body_measurement_runs").use { it.moveToFirst(); assertEquals(runCount, it.getInt(0)) }
            assertEquals(foodBefore, store.operations().first()); assertEquals(batchesBefore, db.foodDao().planningBatches())
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
            assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth"))
            store.purgeHealthExtra("$token-import-cleanup", true)
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("body_raw_imported").fetchSemanticsNodes().isEmpty() }
            assertEquals(originals, db.bodyDao().versions())
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
        } finally {
            store.purgeHealthExtra("$token-import-cleanup", true)
            store.purgeBodyMeasurements("$token-cleanup", true)
        }
    }
}
