package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.local.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Read-only connection of the actual diary to Fitness, using synthetic food.
 * No physical Watch, activity-energy or TDEE evidence is claimed.
 */
@RunWith(AndroidJUnit4::class)
class FitnessNutritionOnDeviceTest {
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
    private fun expect(tag: String, value: String) {
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag(tag) and hasText(value)).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(tag).performScrollTo().assertTextEquals(value)
    }

    @Test fun offlineFitnessReadsTheSameConfirmedDiaryHistoryAndKeepsGapsVersionsReversalAndTrueZeroAfterRestart() = runBlocking {
        assertTrue("Synthetic cleanup requires a disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Require actual network absence, not just disabled radios", connectivity.activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        val store = FoodStore(db)
        val day = AppCalendar.today().toString()
        assertTrue("Never overwrite existing meals", store.dayNutrition(day).meals.isEmpty())
        val token = "synthetic-fitness-nutrition-${UUID.randomUUID()}"
        val authBefore = db.healthDao().state("com.sec.android.app.shealth")
        val healthBefore = db.healthDao().history("com.sec.android.app.shealth")
        val batchesBefore = db.foodDao().planningBatches()
        val consumed = mutableListOf<String>()
        var plan: String? = null
        try {
            val operationsBefore = store.operations().first()
            tap("workspace_tab_MORE"); tap("workspace_go_FITNESS")
            expect("fitness_nutrition_energy", "Erfasste Energie: Unbekannt")
            expect("fitness_nutrition_day", "Deine erfasste Ernährung · $day")
            expect("fitness_nutrition_status", "Erfassung offen")
            assertEquals(operationsBefore, store.operations().first())
            val known = store.rememberProduct("$token-product", ProductInput("Synthetische EG-Nahrung", energyKcal = 125.0, quality = Quality.CONFIRMED))
            plan = store.savePlan("$token-plan", PlanInput("Synthetisch nur geplant", day, day,
                listOf(PlanEntryInput("not-eaten", day, PlanKind.DIRECT_CONSUMPTION, productVersionId = known,
                    amount = 100_000, unit = BaseUnit.GRAM)), "Kein Verzehr"))
            expect("fitness_nutrition_meals", "0 tatsächlich erfasste Verzehre")
            expect("fitness_nutrition_energy", "Erfasste Energie: Unbekannt")
            store.consume("$token-eaten", ConsumptionInput(known, day, "Bestätigt gegessen · synthetisch", 100_000), true)
            consumed += "$token-eaten"
            expect("fitness_nutrition_energy", "Erfasste Energie: 125 kcal")
            expect("fitness_nutrition_status", "Erfassung teilweise")
            store.rememberProduct("$token-new-label", ProductInput.from(db.foodDao().version(known)!!).copy(energyKcal = 500.0),
                db.foodDao().version(known)!!.productId, "Neues Etikett ändert keinen historischen Verzehr")
            expect("fitness_nutrition_energy", "Erfasste Energie: 125 kcal")
            val missing = store.rememberProduct("$token-missing", ProductInput("Synthetisch unbekannte Energie"))
            store.consume("$token-missing-meal", ConsumptionInput(missing, day, "Energie unbekannt · synthetisch", 100_000), true)
            consumed += "$token-missing-meal"
            expect("fitness_nutrition_energy", "Erfasste Energie: Teilsumme: 125 kcal")
            expect("fitness_nutrition_coverage", "Nährwertbeiträge: 1 bekannt · 1 ungeklärt")
            store.setDayStatus("$token-complete", day, DayStatus.COMPLETE, store.dayNutrition(day).snapshot, true)
            expect("fitness_nutrition_status", "Erfassung vollständig bestätigt")
            expect("fitness_nutrition_energy", "Erfasste Energie: Teilsumme: 125 kcal")
            val beforeReading = store.operations().first()
            val snapshot = store.dayNutrition(day)
            tap("fitness_open_nutrition"); waitTag("day_ENERGY")
            compose.onNodeWithTag("day_ENERGY").performScrollTo().assertTextContains("Teilsumme: 125 kcal", substring = true)
            compose.onNodeWithTag("day_status").assertTextEquals("Erfassung vollständig bestätigt")
            compose.onNodeWithTag("workspace_back").performClick(); waitTag("fitness_nutrition_energy")
            compose.activityRule.scenario.recreate()
            expect("fitness_nutrition_energy", "Erfasste Energie: Teilsumme: 125 kcal")
            assertEquals(snapshot, store.dayNutrition(day)); assertEquals(beforeReading, store.operations().first())
            store.reverse("$token-reverse-known", ReversalInput("$token-eaten", day, "Synthetische Korrektur"), true)
            expect("fitness_nutrition_energy", "Erfasste Energie: Unbekannt")
            expect("fitness_nutrition_status", "Erfassung teilweise")
            val zero = store.rememberProduct("$token-zero", ProductInput("Synthetische echte Null", energyKcal = 0.0))
            store.consume("$token-zero-meal", ConsumptionInput(zero, day, "Synthetischer Nullwert", 100_000), true)
            consumed += "$token-zero-meal"
            expect("fitness_nutrition_energy", "Erfasste Energie: Teilsumme: 0 kcal")
            assertEquals(batchesBefore, db.foodDao().planningBatches())
            assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
            assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth"))
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("fitness-nutrition")
            throw failure
        } finally {
            consumed.forEach { key -> if (db.foodDao().reversal(key) == null)
                store.reverse("$key-cleanup", ReversalInput(key, day, "Synthetische Testbereinigung"), true) }
            plan?.let { store.cancelPlan("$token-cancel", it, "Synthetische Testbereinigung") }
        }
    }
}
