package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.local.*
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
class FitnessNutritionUiTest {
    @get:Rule(order = 0) val database: org.junit.rules.TestRule = IsolatedWorkspaceDatabase()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.waitForIdle(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) {
        waitTag(tag)
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun expect(tag: String, value: String) {
        compose.waitUntil(25_000) {
            compose.waitForIdle()
            compose.onAllNodes(hasTestTag(tag) and hasText(value)).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(tag).performScrollTo().assertTextEquals(value)
    }

    @Test fun actualFitnessAndDiaryShareVersionBoundConsumptionWithSeparateCompletenessGapsAndReversal() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val store = FoodStore(db)
        val day = AppCalendar.today().toString()
        assertTrue(store.dayNutrition(day).meals.isEmpty())
        val authBefore = db.healthDao().state("com.sec.android.app.shealth")
        val healthBefore = db.healthDao().history("com.sec.android.app.shealth")
        val operationsBefore = store.operations().first()
        tap("workspace_tab_MORE"); tap("workspace_go_FITNESS")
        expect("fitness_nutrition_energy", "Erfasste Energie: Unbekannt")
        expect("fitness_nutrition_status", "Erfassung offen")
        assertEquals(operationsBefore, store.operations().first())
        val known = store.rememberProduct("fitness-known", ProductInput("Synthetische EG-Nahrung", energyKcal = 125.0, quality = Quality.CONFIRMED))
        store.savePlan("fitness-plan", PlanInput("Nur geplant", day, day,
            listOf(PlanEntryInput("not-eaten", day, PlanKind.DIRECT_CONSUMPTION, productVersionId = known, amount = 100_000, unit = BaseUnit.GRAM)), "Synthetisch"))
        val planned = store.operations().first()
        expect("fitness_nutrition_energy", "Erfasste Energie: Unbekannt")
        assertEquals(planned, store.operations().first())
        store.consume("fitness-eaten", ConsumptionInput(known, day, "Bestätigt gegessen", 100_000), true)
        expect("fitness_nutrition_energy", "Erfasste Energie: 125 kcal")
        expect("fitness_nutrition_status", "Erfassung teilweise")
        store.rememberProduct("fitness-label-changed", ProductInput.from(db.foodDao().version(known)!!).copy(energyKcal = 500.0),
            db.foodDao().version(known)!!.productId, "Neues Etikett; historischer Verzehr bleibt unverändert")
        expect("fitness_nutrition_energy", "Erfasste Energie: 125 kcal")
        val missing = store.rememberProduct("fitness-missing", ProductInput("Unbekannte Testenergie"))
        store.consume("fitness-missing-meal", ConsumptionInput(missing, day, "Tatsächlich gegessen; Energie unbekannt", 100_000), true)
        expect("fitness_nutrition_energy", "Erfasste Energie: Teilsumme: 125 kcal")
        expect("fitness_nutrition_coverage", "Nährwertbeiträge: 1 bekannt · 1 ungeklärt")
        store.setDayStatus("fitness-day-complete", day, DayStatus.COMPLETE, store.dayNutrition(day).snapshot, true)
        expect("fitness_nutrition_status", "Erfassung vollständig bestätigt")
        expect("fitness_nutrition_energy", "Erfasste Energie: Teilsumme: 125 kcal")
        val beforeReading = store.operations().first()
        tap("fitness_open_nutrition"); waitTag("day_ENERGY")
        compose.onNodeWithTag("day_ENERGY").performScrollTo().assertTextContains("Teilsumme: 125 kcal", substring = true)
        compose.onNodeWithTag("day_status").assertTextEquals("Erfassung vollständig bestätigt")
        compose.onNodeWithTag("workspace_back").performClick(); waitTag("fitness_nutrition_energy")
        compose.activityRule.scenario.recreate()
        expect("fitness_nutrition_energy", "Erfasste Energie: Teilsumme: 125 kcal")
        assertEquals(beforeReading, store.operations().first())
        store.reverse("fitness-reverse-known", ReversalInput("fitness-eaten", day, "Synthetische Korrektur"), true)
        expect("fitness_nutrition_energy", "Erfasste Energie: Unbekannt")
        expect("fitness_nutrition_status", "Erfassung teilweise")
        val zero = store.rememberProduct("fitness-zero", ProductInput("Explizite Testnull", energyKcal = 0.0))
        store.consume("fitness-zero-meal", ConsumptionInput(zero, day, "Synthetischer Nullwert", 100_000), true)
        expect("fitness_nutrition_energy", "Erfasste Energie: Teilsumme: 0 kcal")
        assertEquals(authBefore, db.healthDao().state("com.sec.android.app.shealth"))
        assertEquals(healthBefore, db.healthDao().history("com.sec.android.app.shealth"))
    }
}
