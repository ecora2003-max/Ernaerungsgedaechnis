package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanNutritionRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private var at = Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
    private val day = "2026-11-06"
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { at }
    }
    @After fun close() { db.close() }
    private suspend fun product(key: String = "rice", energy: Double = 100.0) = store.rememberProduct(key,
        ProductInput("Planungsreis", source = Source.USER, quality = Quality.CONFIRMED, energyKcal = energy, proteinGrams = 0.0))
    private fun meal(key: String, version: String, amount: Long?) = PlanEntryInput(key, day,
        PlanKind.DIRECT_CONSUMPTION, productVersionId = version, amount = amount, unit = BaseUnit.GRAM)
    private fun same(value: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(value).compareTo(requireNotNull(actual)))

    @Test fun preparationAndLaterPortionAreNotCountedTwiceAndExactGramRecipeRatiosPreserveKnownZeroAndMissingNutrients() = runBlocking {
        val rice = product()
        val recipe = store.saveRecipe("recipe", RecipeInput("Sollgericht", 2000,
            listOf(RecipeItemInput(rice, 200_000)), "Kochen"))
        store.savePlan("plan", PlanInput("Gemeinsamer Plan", day, "2026-11-07", listOf(
            PlanEntryInput("cook", day, PlanKind.PREPARATION, recipeVersionId = recipe, amount = 2000, unit = BaseUnit.PORTION),
            PlanEntryInput("portion", "2026-11-07", PlanKind.PREPARED_PORTION,
                amount = 1000, unit = BaseUnit.PORTION, preparationKey = "cook"), meal("direct", rice, 50_000)), "Synthetisch geplant"))
        val before = db.foodDao().operationCount()
        val report = store.plannedNutrition(day, "2026-11-07")
        assertEquals(1, report.preparationsExcluded)
        assertEquals(2, report.days.sumOf { it.lines.size })
        same("50", report.days[0].nutrition[Nutrient.ENERGY].knownAmount)
        same("100", report.days[1].nutrition[Nutrient.ENERGY].knownAmount)
        same("0", report.days[1].nutrition[Nutrient.PROTEIN].knownAmount)
        assertTrue(report.days[1].nutrition[Nutrient.PROTEIN].complete)
        assertNull(report.days[1].nutrition[Nutrient.FAT].knownAmount)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().activeConsumptionCount())
        assertTrue(db.foodDao().planningBatches().isEmpty())
        // The referenced cooking can precede the selected date window.
        val tomorrowOnly = store.plannedNutrition("2026-11-07", "2026-11-07")
        same("100", tomorrowOnly.days.single().nutrition[Nutrient.ENERGY].knownAmount)
        assertEquals(0, tomorrowOnly.preparationsExcluded)
    }

    @Test fun existingPreparedPortionUsesActualProductionYieldAndAReversalRemainsUnknownInsteadOfUsingRecipeDefaults() = runBlocking {
        val rice = product()
        val location = store.addLocation("loc", "Synthetischer Kühlschrank", true)
        val batch = store.receive("receive", BatchInput(rice, location, 200_000, day, release = ReleaseStatus.RELEASED), true)
        val recipe = store.saveRecipe("recipe", RecipeInput("Gericht", 2000,
            listOf(RecipeItemInput(rice, 200_000)), "Kochen"))
        val output = store.produce("cook", ProductionInput(recipe, 4000, listOf(Allocation(batch, 200_000)), location, day,
            true, "Behälter und Lagerplatz geprüft", ReleaseStatus.RELEASED, "Tatsächliche Ausbeute geprüft"), true)
        val version = requireNotNull(db.foodDao().batch(output)).versionId
        store.savePlan("plan", PlanInput("Vorhandene Portion", day, day, listOf(
            PlanEntryInput("portion", day, PlanKind.PREPARED_PORTION, productVersionId = version,
                amount = 1000, unit = BaseUnit.PORTION)), "Eigene Planung"))
        val before = db.foodDao().operationCount()
        same("50", store.plannedNutrition(day, day).days.single().nutrition[Nutrient.ENERGY].knownAmount)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(4000L, db.foodDao().balance(output))
        store.reverse("undo-cook", ReversalInput("cook", day, "Nicht hergestellt"), true)
        val reversed = store.plannedNutrition(day, day).days.single().nutrition[Nutrient.ENERGY]
        assertNull(reversed.knownAmount)
        assertTrue(NutritionGap.REVERSED_PRODUCTION in reversed.gaps)
        assertEquals(before + 1, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().activeConsumptionCount())
    }

    @Test fun referencedHistoricalProductVersionSurvivesLaterCatalogCorrectionAndOnlyLatestConfirmedPlanContributes() = runBlocking {
        val rice = product()
        val original = store.savePlan("plan1", PlanInput("Erste Planung", day, day, listOf(meal("m", rice, 100_000)), "Eigene Planung"))
        val old = requireNotNull(db.foodDao().version(rice))
        at++
        store.rememberProduct("revision", ProductInput("Neue Produktfassung", source = Source.USER,
            quality = Quality.CONFIRMED, energyKcal = 900.0), old.productId, "Neue belegte Angaben", old.id)
        var report = store.plannedNutrition(day, day)
        same("100", report.days.single().nutrition[Nutrient.ENERGY].knownAmount)
        assertEquals(1, report.days.single().lines.single().referenceRevision)
        val revised = store.savePlan("plan2", PlanInput("Zweite Planung", day, day, listOf(meal("m2", rice, 300_000)), "Absichtlich geändert"), original)
        report = store.plannedNutrition(day, day)
        assertEquals(1, report.days.single().lines.size)
        assertEquals(revised, report.days.single().lines.single().planVersionId)
        same("300", report.days.single().nutrition[Nutrient.ENERGY].knownAmount)
        store.cancelPlan("cancel", revised, "Verworfen")
        assertTrue(store.plannedNutrition(day, day).days.isEmpty())
    }

    @Test fun originalFullPlanStaysSeparateFromConfirmedActualIntakeAndRemainingDemandAfterExecutionAssignment() = runBlocking {
        val rice = product()
        store.savePlan("plan", PlanInput("Plan", day, day, listOf(meal("m", rice, 100_000)), "Geplant"))
        val entry = db.foodDao().allPlannedEntries().single()
        store.consume("actual", ConsumptionInput(rice, day, "Tatsächlich gegessen", 50_000), true)
        store.linkPlanExecution("link", LinkPlanExecutionInput(entry.id, "actual", store.planExecution(entry.id).status.snapshot,
            "Schon gebuchte Menge ausdrücklich zugeordnet", true), true)
        val before = db.foodDao().operationCount()
        same("50", store.dayNutrition(day).totals[Nutrient.ENERGY].knownAmount)
        same("100", store.plannedNutrition(day, day).days.single().nutrition[Nutrient.ENERGY].knownAmount)
        assertEquals(50_000L, store.planExecution(entry.id).status.remainingAmount)
        assertEquals(1, db.foodDao().activeConsumptionCount())
        assertEquals(before, db.foodDao().operationCount())
    }

    @Test fun unknownPlanAmountRemainsAGapAndOnlyDateApplicableConfirmedPersonalGoalsJoinTheReadSnapshot() = runBlocking {
        val rice = product()
        store.savePlan("plan", PlanInput("Lücken sichtbar", day, "2026-11-07", listOf(meal("unknown", rice, null),
            PlanEntryInput("tomorrow", "2026-11-07", PlanKind.DIRECT_CONSUMPTION, productVersionId = rice,
                amount = 100_000, unit = BaseUnit.GRAM)), "Noch offene Menge"))
        val p = requireNotNull(store.proposeNutritionGoals("goal", NutritionGoalDraftInput(null, "2026-11-07", "Eigener Bereich",
            listOf(NutritionGoalRange(Nutrient.PROTEIN, "9,0000", null))), true).versionId)
        assertTrue(store.plannedNutrition(day, "2026-11-07").days.all { it.goalVersion == null })
        val confirmed = store.confirmNutritionGoals("confirm", p, store.readNutritionGoals().versionBinding(p), true).versionId
        val report = store.plannedNutrition(day, "2026-11-07")
        assertNull(report.days[0].nutrition[Nutrient.ENERGY].knownAmount)
        assertTrue(NutritionGap.UNKNOWN_AMOUNT in report.days[0].nutrition[Nutrient.ENERGY].gaps)
        assertNull(report.days[0].goalVersion)
        assertEquals(confirmed, report.days[1].goalVersion?.id)
        assertEquals("9,0000", report.days[1].goals.single().minimumText)
        val goals = store.readNutritionGoals()
        store.eraseNutritionGoals("erase", goals.token, true)
        assertTrue(store.plannedNutrition(day, "2026-11-07").days.all { it.goalVersion == null && it.goals.isEmpty() })
    }

    @Test fun calendarWeeksClipTheSelectedWindowAndKeepUnknownMondaySeparateFromKnownSundayWithoutInventedMeals() = runBlocking {
        val rice = product()
        store.savePlan("plan", PlanInput("Zwei Wochen", "2026-11-08", "2026-11-09", listOf(
            meal("sunday", rice, 100_000).copy(date = "2026-11-08"),
            meal("monday", rice, null).copy(date = "2026-11-09")), "Offene Menge sichtbar lassen"))
        val before = db.foodDao().operationCount()
        val report = store.plannedNutrition("2026-11-08", "2026-11-09")
        val weeks = report.weeks()
        assertEquals(listOf("2026-11-08", "2026-11-09"), weeks.map { it.startsOn })
        assertEquals(listOf("2026-11-08", "2026-11-09"), weeks.map { it.endsOn })
        assertEquals(listOf(1, 1), weeks.map { it.days.size })
        assertEquals(report.days.flatMap { it.lines }.map { it.entry.id }, weeks.flatMap { it.days }.flatMap { it.lines }.map { it.entry.id })
        same("100", weeks[0].nutrition[Nutrient.ENERGY].knownAmount)
        assertNull(weeks[1].nutrition[Nutrient.ENERGY].knownAmount)
        assertTrue(NutritionGap.UNKNOWN_AMOUNT in weeks[1].nutrition[Nutrient.ENERGY].gaps)
        assertEquals(before, db.foodDao().operationCount())
        assertEquals(0, db.foodDao().activeConsumptionCount())
    }
}
