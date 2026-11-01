package de.ernaehrung.demo

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LocalDataViewModel
import de.ernaehrung.demo.ui.screens.LocalDataContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalDataScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var db: FoodDatabase? = null
    private val models = ViewModelStore()
    @After fun close() { models.clear(); db?.close() }

    @Test fun productFormPersistsUnknownValuesAndPreventsRepeatedSave() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        db = database
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(FoodStore(database)) as T
        })[LocalDataViewModel::class.java]
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model) } }
            }
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("0 gespeicherte Produkte · unabhängig vom Bestand").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Produkt speichern / neue Version").performClick()
        rule.onNodeWithText("Produktname · lokal").performScrollTo().performTextInput("Testtofu")
        rule.onNodeWithText("Protein in g · optional").performScrollTo().performTextInput("13,5")
        rule.onNodeWithText("Fett in g · optional").performScrollTo().performTextInput("0")
        tap("Weitere Produktnährstoffe")
        rule.onNodeWithText("Calcium in mg · optional").performScrollTo().performTextInput("120")
        rule.onNodeWithText("Änderungsgrund").performScrollTo().performTextInput("Erster Test")
        rule.onNodeWithTag("Produkt lokal speichern").performScrollTo().performClick()
        rule.waitUntil(15_000) {
            // Synchronize the paused Android looper as well as Room's background executor.
            rule.waitForIdle()
            model.saveState.value.message != null
        }
        assertTrue(model.saveState.value.message, model.saveState.value.succeeded)
        rule.onNodeWithTag("Produkt lokal speichern").assertIsNotEnabled()
        runBlocking {
            val rows = database.foodDao().observeCatalog().first()
            assertEquals("Testtofu", rows.single().version.name)
            assertEquals(null, rows.single().version.energyKcal)
            assertEquals(13.5, rows.single().version.proteinGrams!!, 0.0)
            assertEquals(0.0, rows.single().version.fatGrams!!, 0.0)
            assertEquals(120.0, rows.single().version.calciumMg!!, 0.0)
            assertEquals(0L, rows.single().stockAmount)
            assertEquals(1, database.foodDao().operationCount())
        }
        rule.onNodeWithTag("Neuen Vorgang beginnen").performScrollTo().performClick()
        rule.onNodeWithTag("Produkt lokal speichern").performScrollTo().assertIsEnabled()
    }

    private fun mount(database: FoodDatabase, fontScale: Float = 1f, now: Long = 1_800_000_000_000L): LocalDataViewModel {
        db = database
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(FoodStore(database) { now }) as T
        })[LocalDataViewModel::class.java]
        rule.runOnUiThread { rule.activity.setContent {
            DemoTheme { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model) }
            } }
        } }
        rule.waitUntil(15_000) { rule.waitForIdle(); model.state.value.loading.not() }
        return model
    }
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
    private fun tap(text: String) {
        // Navigation and scope changes update Compose asynchronously. Wait for the actual
        // affordance, not just the ViewModel report, before trying to scroll/click it.
        // Still fail if the expected button never appears; no assertions are skipped.
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(text, substring = false).performScrollTo().performClick()
    }
    private fun choose(label: String, id: String) {
        rule.onNodeWithTag(label).performScrollTo().performClick()
        rule.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }
    private fun field(label: String, value: String) = rule.onNodeWithText(label, substring = false).performScrollTo().performTextReplacement(value)
    private fun saved(model: LocalDataViewModel) {
        rule.waitUntil(15_000) { rule.waitForIdle(); model.saveState.value.message != null }
        assertTrue(model.saveState.value.message, model.saveState.value.succeeded)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun transferAndItsFullReversalThroughUiPreserveBothSides() {
        val database = database(); db = database
        val ids = runBlocking {
            val store = FoodStore(database)
            val version = store.rememberProduct("p", ProductInput("Testtofu"))
            val a = store.addLocation("a", "Kühlschrank", true); val b = store.addLocation("b", "Vorrat", true)
            val batch = store.receive("buy", BatchInput(version, a, 200_000, "2026-10-30", release = ReleaseStatus.RELEASED), true)
            batch to b
        }
        val model = mount(database)
        tap("Lager"); tap("Umlagern, entsorgen oder zählen")
        choose("Quellcharge", ids.first); choose("Ziellager", ids.second)
        field("Menge für Bestandsvorgang", "75")
        field("Datum des Bestandsvorgangs", "2026-10-30")
        field("Begründung des Bestandsvorgangs", "Aufteilen")
        tap("Diesen tatsächlichen Bestandsvorgang bestätigen"); tap("Bestandsvorgang buchen"); saved(model)
        val transfer = runBlocking {
            val row = database.foodDao().observeOperations().first().single { it.operation.kind == "TRANSFER" }
            assertEquals(125_000L, database.foodDao().balance(ids.first))
            assertEquals(75_000L, database.foodDao().balance(row.operation.resultId))
            row.operation
        }
        tap("Neuen Vorgang beginnen"); tap("Chronik")
        rule.onNodeWithTag("local_section_Chronik").assertIsSelected()
        tap("Vorgang prüfen und stornieren")
        choose("Ursprungsvorgang · letzte 30", transfer.id)
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Vorgesehene Gegenbuchungen").fetchSemanticsNodes().isNotEmpty() }
        field("Stornodatum JJJJ-MM-TT", "2026-10-30"); field("Stornogrund", "Falsches Ziellager")
        tap("Diese vollständige Gegenbuchung ausdrücklich bestätigen"); tap("Vorgang stornieren"); saved(model)
        runBlocking {
            assertEquals(200_000L, database.foodDao().balance(ids.first))
            assertEquals(0L, database.foodDao().balance(transfer.resultId))
            assertEquals(2, database.foodDao().movements(transfer.id).size)
            assertEquals(2, database.foodDao().movements(database.foodDao().reversal(transfer.id)!!.reversalOperationId).size)
        }
        capture("local-storno-small")
    }

    @Test fun actualCookingAndLaterEatingThroughUiCountPortionsOnlyOnce() {
        val database = database(); db = database
        val ids = runBlocking {
            val store = FoodStore(database)
            val version = store.rememberProduct("p", ProductInput("Testlinsen", energyKcal = 340.0, proteinGrams = 24.0))
            val location = store.addLocation("loc", "Kühlschrank", true)
            val batch = store.receive("buy", BatchInput(version, location, 300_000, "2026-10-30", release = ReleaseStatus.RELEASED), true)
            val recipe = store.saveRecipe("recipe", RecipeInput("Linsentopf", 6000, listOf(RecipeItemInput(version, 300_000)), "Garen"))
            Triple(recipe, batch, location)
        }
        val model = mount(database)
        tap("Küche")
        tap("Nährwerte pro Rezeptportion · Vorschau")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("recipe_${ids.first}_ENERGY").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("recipe_${ids.first}_ENERGY").assertTextContains("170 kcal", substring = true)
        tap("Nährwerte pro Rezeptportion · Vorschau")
        tap("Tatsächliche Herstellung buchen")
        choose("Rezept für Herstellung", ids.first); choose("Zutatencharge 1", ids.second)
        field("Tatsächliche Zutatmenge 1 in g", "300"); field("Tatsächlich hergestellte Portionen", "6")
        field("Herstellungsdatum JJJJ-MM-TT", "2026-10-30"); choose("Fertigportionen lagern in", ids.third)
        tap("Platz, Behälter und geeignete Lagerbedingungen tatsächlich geprüft")
        field("Geprüfte Lagerbedingungen und Platz beschreiben", "Platz für sechs geschlossene Behälter und Kühlbedingungen geprüft")
        tap("Fertigportionen für diesen Test ausdrücklich freigeben")
        tap("Tatsächlich gekocht · alle Entnahmen und Portionen gemeinsam buchen"); tap("Herstellung buchen"); saved(model)
        runBlocking {
            val operation = database.foodDao().observeOperations().first().single { it.operation.kind == "PRODUCTION" }.operation
            assertEquals(0L, database.foodDao().balance(ids.second))
            assertEquals(6000L, database.foodDao().balance(operation.resultId))
            assertEquals(0, database.foodDao().activeConsumptionCount())
            assertEquals(ids.first, database.foodDao().production(operation.id)!!.recipeVersionId)
        }
        rule.onNodeWithTag("Herstellung buchen").assertIsNotEnabled()
        capture("local-production")
        val output = runBlocking { database.foodDao().observeOperations().first().single { it.operation.kind == "PRODUCTION" }.operation.resultId }
        tap("Neuen Vorgang beginnen"); tap("Ernährung")
        field("Tag JJJJ-MM-TT", "2026-10-30"); tap("Tag anzeigen"); waitDay(model, 0)
        rule.onNodeWithTag("active_meal_count").assertTextContains("0 aktive Verzehre")
        tap("Tatsächlichen Verzehr erfassen"); tap("Aus Charge"); choose("Verzehrcharge", output)
        field("Mahlzeit / Beschreibung", "Mittag"); field("Gegessene Menge in Portion", "1")
        tap("Tatsächlich gegessen · lokal speichern"); tap("Verzehr lokal buchen"); saved(model); waitDay(model, 1)
        rule.onNodeWithTag("day_ENERGY").assertTextContains("170 kcal", substring = true)
        runBlocking {
            assertEquals(0L, database.foodDao().balance(ids.second)); assertEquals(5000L, database.foodDao().balance(output))
            assertEquals(1, database.foodDao().activeConsumptionCount())
        }
        capture("local-nutrition-portions")
    }

    private fun waitDay(model: LocalDataViewModel, count: Int) = rule.waitUntil(15_000) {
        rule.waitForIdle(); model.daily.value.report?.let { it.date == "2026-10-30" && it.meals.size == count } == true
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun diaryShowsPartialSumsConfirmsRecordingAndStornoKeepsUnknownsAtLargeText() {
        val database = database(); db = database
        val version = runBlocking { FoodStore(database).rememberProduct("p", ProductInput("Testtofu", energyKcal = 125.0, proteinGrams = 13.0)) }
        val model = mount(database, fontScale = 1.3f)
        tap("Ernährung"); field("Tag JJJJ-MM-TT", "2026-10-30"); tap("Tag anzeigen"); waitDay(model, 0)
        tap("Tatsächlichen Verzehr erfassen"); choose("Verzehr-Artikelversion", version)
        field("Mahlzeit / Beschreibung", "Mittag"); field("Gegessene Menge in g", "50")
        tap("Tatsächlich gegessen · lokal speichern"); tap("Verzehr lokal buchen"); saved(model); waitDay(model, 1)
        rule.onNodeWithTag("day_ENERGY").assertTextContains("62,5 kcal", substring = true)
        val firstMeal = model.daily.value.report!!.meals.single().event.id
        tap("Neuen Vorgang beginnen")
        field("Mahlzeit / Beschreibung", "Unterwegs"); field("Gegessene Menge in g", "")
        tap("Tatsächlich gegessen · lokal speichern"); tap("Verzehr lokal buchen"); saved(model); waitDay(model, 2)
        rule.onNodeWithTag("day_ENERGY").assertTextContains("Teilsumme: 62,5 kcal", substring = true)
        tap("Neuen Vorgang beginnen"); tap("Erfassung als vollständig bestätigen")
        rule.onNodeWithTag("confirm_day_status").assertIsNotEnabled()
        tap("Alle Mahlzeiten dieses Tages sind erfasst")
        rule.onNodeWithTag("confirm_day_status").performClick(); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.daily.value.report?.status == DayStatus.COMPLETE }
        rule.onNodeWithTag("day_status").assertTextEquals("Erfassung vollständig bestätigt")
        rule.onNodeWithTag("day_ENERGY").assertTextContains("Teilsumme: 62,5 kcal", substring = true)
        tap("Neuen Vorgang beginnen")
        rule.onNodeWithTag("storno_meal_$firstMeal").performScrollTo().performClick()
        field("Stornodatum JJJJ-MM-TT", "2026-10-30"); field("Stornogrund", "Falscher Eintrag")
        rule.onNodeWithTag("confirm_meal_storno").performClick(); saved(model); waitDay(model, 1)
        rule.onNodeWithTag("day_ENERGY").assertTextContains("Unbekannt", substring = true)
        rule.onNodeWithTag("day_status").assertTextEquals("Erfassung teilweise")
        runBlocking {
            assertTrue(database.foodDao().observeBatches().first().isEmpty())
            assertEquals(2, database.foodDao().consumptionCount()); assertEquals(1, database.foodDao().activeConsumptionCount())
        }
        rule.onNodeWithTag("day_status").performScrollTo()
        capture("local-nutrition-small-large-text")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun planningFormSharesDayAndWeekDemandAndPreservesReplacedAndCancelledVersions() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val (version, batch) = runBlocking {
            val store = FoodStore(database) { clock }
            val v = store.rememberProduct("p", ProductInput("Testtofu"))
            val loc = store.addLocation("loc", "Vorrat", true)
            v to store.receive("b", BatchInput(v, loc, 500_000, "2026-10-30", useBy = "2026-11-06", release = ReleaseStatus.RELEASED), true)
        }
        val model = mount(database, 1.3f, clock)
        tap("Planung")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report != null }
        field("Ansicht ab JJJJ-MM-TT", "2026-10-30"); tap("Planungszeitraum anzeigen")
        tap("Plan anlegen oder überarbeiten")
        field("Plantitel", "Testwoche"); field("Planbeginn JJJJ-MM-TT", "2026-10-30"); field("Planende JJJJ-MM-TT", "2026-11-05")
        choose("Planbezug", version)
        field("Geplanter Termin JJJJ-MM-TT", "2026-10-30"); field("Geplante Menge · g · leer bei unbekannt", "300")
        tap("Zum Planentwurf hinzufügen")
        field("Geplanter Termin JJJJ-MM-TT", "2026-10-31"); field("Geplante Menge · g · leer bei unbekannt", "300")
        tap("Zum Planentwurf hinzufügen"); field("Änderungsgrund des Plans", "Gemeinsame Testwoche")
        tap("Planversion lokal speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report?.shopping?.singleOrNull()?.knownMissing == 100_000L }
        rule.onNodeWithTag("shopping_$version").assertTextEquals("Fehlmenge: 100 g")
        tap("56 Tage"); tap("Planungszeitraum anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report?.endsOn == "2026-12-24" }
        assertEquals(2, model.planning.value.report!!.entries.size)
        rule.onNodeWithTag("shopping_$version").assertTextEquals("Fehlmenge: 100 g")
        field("Ansicht ab JJJJ-MM-TT", "2026-10-31"); tap("1 Tag"); tap("Planungszeitraum anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report?.entries?.size == 1 }
        rule.onNodeWithTag("shopping_$version").assertTextEquals("Fehlmenge: 100 g")
        rule.onNodeWithTag("shopping_$version").performScrollTo(); capture("local-planning-small-large-text")
        tap("Neuen Vorgang beginnen"); tap("Plan anlegen oder überarbeiten")
        val first = model.planning.value.report!!.plans.single().version.id
        choose("Plan bearbeiten", first)
        tap("Planeintrag 2 entfernen"); field("Änderungsgrund des Plans", "Nur eine Mahlzeit")
        tap("Planversion lokal speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report?.plans?.singleOrNull()?.version?.revision == 2 }
        assertTrue(model.planning.value.report!!.shopping.isEmpty())
        tap("Neuen Vorgang beginnen"); tap("Planversionen und Verwerfen")
        field("Verwerfungsgrund Testwoche", "Termin entfällt")
        val second = model.planning.value.report!!.plans.single().version.id
        rule.onNodeWithTag("Plan verwerfen ${second.take(8)}").performScrollTo().assertIsEnabled()
        tap("Plan verwerfen ${second.take(8)}"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report?.plans?.singleOrNull()?.version?.status == EntryStatus.CANCELLED }
        runBlocking {
            assertEquals(3, database.foodDao().allPlans().size)
            assertEquals("Termin entfällt", database.foodDao().latestPlan(model.planning.value.report!!.plans.single().version.seriesId!!)!!.changeReason)
            assertEquals(4, database.foodDao().allPlannedEntries().size)
            assertEquals(500_000L, database.foodDao().balance(batch)); assertEquals(0, database.foodDao().consumptionCount())
        }
    }

    @Test fun plannedCookingAndLinkedPortionFormCalculateIngredientsOnceAndKeepUnknownsVisible() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val (version, recipe) = runBlocking {
            val store = FoodStore(database) { clock }
            val v = store.rememberProduct("p", ProductInput("Testtofu"))
            val loc = store.addLocation("loc", "Vorrat", true)
            store.receive("b", BatchInput(v, loc, 200_000, "2026-10-30", useBy = "2026-11-06", release = ReleaseStatus.RELEASED), true)
            v to store.saveRecipe("r", RecipeInput("Testtopf", 6000, listOf(RecipeItemInput(v, 300_000)), "Garen"))
        }
        val model = mount(database, now = clock)
        tap("Planung"); rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report != null }
        field("Ansicht ab JJJJ-MM-TT", "2026-10-30"); tap("Planungszeitraum anzeigen")
        tap("Plan anlegen oder überarbeiten")
        field("Plantitel", "Meal Prep"); field("Planbeginn JJJJ-MM-TT", "2026-10-30"); field("Planende JJJJ-MM-TT", "2026-11-05")
        field("Geplanter Termin JJJJ-MM-TT", "2026-10-30")
        choose("Art des Planeintrags", PlanKind.PREPARATION.name); choose("Planbezug", recipe)
        field("Geplante Menge · Portion · leer bei unbekannt", "6"); tap("Zum Planentwurf hinzufügen")
        choose("Art des Planeintrags", PlanKind.PREPARED_PORTION.name)
        tap("Geplante Zubereitung")
        rule.onNodeWithTag("Planbezug").performScrollTo().performClick()
        rule.onNodeWithText("Testtopf · 2026-10-30", substring = false).performScrollTo().performClick()
        field("Geplanter Termin JJJJ-MM-TT", "2026-10-31")
        field("Geplante Menge · Portion · leer bei unbekannt", "2"); tap("Zum Planentwurf hinzufügen")
        field("Geplanter Termin JJJJ-MM-TT", "2026-11-01"); tap("Zum Planentwurf hinzufügen") // deliberately unknown amount
        field("Änderungsgrund des Plans", "Ein Kochtermin und spätere Portionen")
        tap("Planversion lokal speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report?.entries?.size == 3 }
        val report = model.planning.value.report!!
        assertEquals(100_000L, report.shopping.single().knownMissing)
        assertEquals(300_000L, report.entries.flatMap { it.demands }.sumOf { it.required!! })
        assertTrue(report.hasUnknownRequirements)
        rule.onNodeWithTag("shopping_$version").assertTextEquals("Fehlmenge: 100 g")
        rule.onNodeWithText(PlanningIssue.UNKNOWN_AMOUNT.label, substring = false).assertExists()
        runBlocking {
            assertEquals(1, database.foodDao().planningBatches().size)
            assertEquals(0, database.foodDao().consumptionCount())
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun executionUiMapsPartialMealCarriesIdentityAfterConfirmedEditAndReleasesOnlyTheMapping() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val (entry, batch) = runBlocking {
            val store = FoodStore(database) { clock }
            val version = store.rememberProduct("p", ProductInput("Testtofu", energyKcal = 125.0))
            val loc = store.addLocation("loc", "Vorrat", true)
            val stock = store.receive("b", BatchInput(version, loc, 200_000, "2026-10-30", useBy = "2026-11-05", release = ReleaseStatus.RELEASED), true)
            val plan = store.savePlan("plan", PlanInput("Testtag", "2026-10-30", "2026-11-05", listOf(PlanEntryInput("eat-plan",
                "2026-10-30", PlanKind.DIRECT_CONSUMPTION, version, amount = 200_000, unit = BaseUnit.GRAM)), "Tagesplan"))
            store.consume("eat", ConsumptionInput(version, "2026-10-30", "Mittag", 50_000, listOf(Allocation(stock, 50_000))), true)
            database.foodDao().allPlannedEntries().single { it.planId == plan } to stock
        }
        val model = mount(database, 1.3f, clock)
        tap("Planung"); rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report != null }
        field("Ansicht ab JJJJ-MM-TT", "2026-10-30"); tap("Planungszeitraum anzeigen")
        tap("Ist-Ausführung zuordnen oder zurücknehmen"); choose("Planposition für Ist-Zuordnung", entry.id)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.execution.value.view?.entry?.id == entry.id }
        choose("Bereits gebuchtes Ist-Ereignis", "eat")
        field("Begründung der Ist-Zuordnung", "Erster Teil des Mittagessens")
        tap("Abweichenden Termin oder Menge bewusst zuordnen"); tap("Diese Ist-Buchung dieser Planposition zuordnen")
        tap("Ist-Zuordnung speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.execution.value.view?.status?.remainingAmount == 150_000L }
        rule.onNodeWithTag("execution_remaining").assertTextEquals("Noch offen: 150 g")
        assertTrue(model.planning.value.report!!.shopping.isEmpty())
        val assignment = model.execution.value.view!!.status.events.single().link.operationId
        tap("Neuen Vorgang beginnen"); tap("Plan anlegen oder überarbeiten"); choose("Plan bearbeiten", entry.planId)
        field("Menge Planposition 1 · g", "300")
        field("Änderungsgrund des Plans", "Mehr geplant; tatsächlichen Teil behalten")
        tap("Planversion lokal speichern")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.saveState.value.message != null }
        assertTrue(model.saveState.value.message, !model.saveState.value.succeeded)
        tap("Bestehende Ist-Zuordnungen bei Termin- oder Mengenänderung bewusst übernehmen")
        tap("Planversion lokal speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report?.plans?.singleOrNull()?.version?.revision == 2 }
        val current = model.planning.value.report!!.plans.single().entries.single()
        assertEquals(entry.positionId, current.positionId)
        rule.onNodeWithTag("remaining_${current.id}").assertTextEquals("Noch offen: 250 g")
        rule.onNodeWithTag("remaining_${current.id}").performScrollTo(); capture("local-execution-partial-large-text")
        tap("Neuen Vorgang beginnen"); tap("Ist-Ausführung zuordnen oder zurücknehmen")
        choose("Planposition für Ist-Zuordnung", current.id)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.execution.value.view?.entry?.id == current.id }
        field("Rücknahmegrund ${assignment.take(8)}", "Andere Planposition gemeint")
        tap("Nur diese Zuordnung zurücknehmen ${assignment.take(8)}")
        tap("Zuordnung zurücknehmen ${assignment.take(8)}"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.execution.value.view?.status?.remainingAmount == 300_000L }
        runBlocking {
            assertEquals(150_000L, database.foodDao().balance(batch)); assertEquals(1, database.foodDao().activeConsumptionCount())
            assertEquals(2, database.foodDao().allPlanExecutions().size)
            assertEquals(1, FoodStore(database).dayNutrition("2026-10-30").meals.size)
        }
    }

    @Test fun executionUiLinksActualCookingThenItsPortionsAndNeverCreatesAnExtraMealOrBatch() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val (prep, plannedMeal, stockIds) = runBlocking {
            val store = FoodStore(database) { clock }
            val v = store.rememberProduct("p", ProductInput("Testlinsen", energyKcal = 340.0))
            val loc = store.addLocation("loc", "Kühlschrank", true)
            val raw = store.receive("b", BatchInput(v, loc, 300_000, "2026-10-30", useBy = "2026-11-05", release = ReleaseStatus.RELEASED), true)
            val recipe = store.saveRecipe("r", RecipeInput("Testtopf", 6000, listOf(RecipeItemInput(v, 300_000)), "Garen"))
            val plan = store.savePlan("plan", PlanInput("Meal Prep", "2026-10-30", "2026-11-05", listOf(
                PlanEntryInput("prep", "2026-10-30", PlanKind.PREPARATION, recipeVersionId = recipe, amount = 6000, unit = BaseUnit.PORTION),
                PlanEntryInput("meal", "2026-10-31", PlanKind.PREPARED_PORTION, amount = 6000, unit = BaseUnit.PORTION, preparationKey = "prep")), "Planung"))
            val out = store.produce("cook", ProductionInput(recipe, 4000, listOf(Allocation(raw, 200_000)), loc, "2026-10-30",
                true, "Geeigneter Platz und Bedingungen", ReleaseStatus.RELEASED, useBy = "2026-11-05"), true)
            store.consume("eat", ConsumptionInput(database.foodDao().batch(out)!!.versionId, "2026-10-30", "Mittag", 2000, listOf(Allocation(out, 2000))), true)
            val items = database.foodDao().allPlannedEntries().filter { it.planId == plan }
            Triple(items.single { it.kind == PlanKind.PREPARATION }, items.single { it.kind == PlanKind.PREPARED_PORTION }, raw to out)
        }
        val model = mount(database, now = clock)
        tap("Planung"); rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report != null }
        field("Ansicht ab JJJJ-MM-TT", "2026-10-30"); tap("Planungszeitraum anzeigen")
        tap("Ist-Ausführung zuordnen oder zurücknehmen"); choose("Planposition für Ist-Zuordnung", prep.id)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.execution.value.view?.entry?.id == prep.id }
        choose("Bereits gebuchtes Ist-Ereignis", "cook")
        field("Begründung der Ist-Zuordnung", "Vier von sechs Portionen gekocht")
        tap("Abweichenden Termin oder Menge bewusst zuordnen"); tap("Diese Ist-Buchung dieser Planposition zuordnen")
        tap("Ist-Zuordnung speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.execution.value.view?.status?.remainingAmount == 2000L }
        tap("Neuen Vorgang beginnen"); tap("Ist-Ausführung zuordnen oder zurücknehmen")
        choose("Planposition für Ist-Zuordnung", plannedMeal.id)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.execution.value.view?.entry?.id == plannedMeal.id }
        choose("Bereits gebuchtes Ist-Ereignis", "eat")
        field("Begründung der Ist-Zuordnung", "Zwei Portionen schon heute gegessen")
        tap("Abweichenden Termin oder Menge bewusst zuordnen"); tap("Diese Ist-Buchung dieser Planposition zuordnen")
        tap("Ist-Zuordnung speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.planning.value.report?.entries?.singleOrNull { it.entry.id == plannedMeal.id }?.execution?.remainingAmount == 4000L }
        val report = model.planning.value.report!!
        val meal = report.entries.single { it.entry.id == plannedMeal.id }
        assertEquals(2000L, meal.actualPortionsCovered); assertEquals(2000L, meal.projectedPortions); assertEquals(0L, meal.missingPortions)
        assertEquals(100_000L, report.entries.single { it.entry.id == prep.id }.demands.single().required)
        assertTrue(report.shopping.isEmpty())
        runBlocking {
            assertEquals(100_000L, database.foodDao().balance(stockIds.first)); assertEquals(2000L, database.foodDao().balance(stockIds.second))
            assertEquals(2, database.foodDao().planningBatches().size); assertEquals(1, database.foodDao().consumptionCount())
            assertEquals(2, database.foodDao().allPlanExecutions().size)
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun shoppingUiStoresConfirmedPackPriceAndBudgetThenChecksListWithoutBuyingAnything() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val version = runBlocking {
            val store = FoodStore(database) { clock }
            val v = store.rememberProduct("p", ProductInput("Testtofu"))
            store.savePlan("plan", PlanInput("Woche", "2026-10-30", "2026-11-05", listOf(PlanEntryInput("eat",
                "2026-10-30", PlanKind.DIRECT_CONSUMPTION, v, amount = 350_000, unit = BaseUnit.GRAM)), "Planung"))
            v
        }
        val model = mount(database, 1.3f, clock)
        tap("Einkauf"); rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report != null }
        field("Bedarf ab JJJJ-MM-TT", "2026-10-30"); field("Geplanter Einkaufstag JJJJ-MM-TT", "2026-10-30"); tap("Einkaufsliste anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report != null }
        tap("Packungsbasis und Preis speichern"); choose("Artikelversion der Preisangabe", version)
        field("Händler der Preisangabe · optional", "Testmarkt"); field("Beobachtet am JJJJ-MM-TT", "2026-10-30")
        field("Nutzbare Menge der Preisangabe in g · optional", "200"); field("Preis für diese Menge in EUR · optional", "1,99")
        tap("Preisbezug ist genau eine ganze Kaufpackung")
        field("Mengenbasis beschreiben, etwa Abtropfgewicht", "200 g nutzbares Abtropfgewicht pro Kaufpackung")
        tap("Nutzbare Packungsbasis ausdrücklich bestätigt")
        field("Preis gültig ab JJJJ-MM-TT · optional", "2026-10-30"); field("Preis gültig bis JJJJ-MM-TT · optional", "2026-11-05")
        tap("Preisangabe geprüft · keine bezahlte Ausgabe"); field("Quelle / Änderungsgrund der Preisangabe", "Packung und Aktionsschild geprüft")
        tap("Preisfassung lokal speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.prices?.size == 1 }
        val offer = model.shopping.value.report!!.prices.single().id
        assertEquals(null, model.shopping.value.report!!.estimatedTotalCents)
        tap("Neuen Vorgang beginnen"); tap("Budgetobergrenze speichern")
        field("Budgetname", "Testwoche"); field("Budgetbeginn JJJJ-MM-TT", "2026-10-30"); field("Budgetende JJJJ-MM-TT", "2026-11-05")
        field("Budgetobergrenze in EUR · optional", "3,00"); tap("Diese Budgetobergrenze bewusst bestätigen")
        field("Änderungsgrund des Budgets", "Bestätigter Wochenrahmen")
        tap("Budgetfassung lokal speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.budgets?.size == 1 }
        val budget = model.shopping.value.report!!.budgets.single().id
        tap("Neuen Vorgang beginnen"); choose("Budget für Einkaufsvergleich", budget); tap("Einkaufsliste anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.scope?.budgetId == budget }
        choose("Packungs-/Preisfassung ${version.take(8)}", offer)
        rule.onNodeWithTag("shopping_packs_$version").assertTextEquals("Ganze Packungen: 2")
        rule.onNodeWithTag("shopping_cost_$version").assertTextEquals("Kosten dieser Auswahl: 3,98 €")
        tap("Auf Einkaufsliste abhaken ${version.take(8)} · kein Kauf")
        tap("Auswahl / Markierung speichern ${version.take(8)}"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.items?.singleOrNull()?.checked == true }
        rule.onNodeWithTag("shopping_total").assertTextEquals("Voraussichtliche Gesamtkosten: 3,98 €")
        assertEquals(98L, model.shopping.value.report!!.budget.knownCostsOverLimitCents)
        assertEquals(null, model.shopping.value.report!!.budget.remainingBudgetCents)
        rule.onNodeWithTag("shopping_cost_$version").performScrollTo(); capture("local-shopping-small-large-text")
        runBlocking {
            val dao = database.foodDao(); val price = dao.prices().single()
            assertEquals(200_000L, price.amount); assertEquals(199L, price.totalCents); assertEquals(true, price.basisConfirmed)
            assertEquals(1, dao.shoppingMarks("2026-10-30", "2026-11-05", "2026-10-30").size)
            assertTrue(dao.planningBatches().isEmpty()); assertEquals(0, dao.consumptionCount())
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM receipts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun shoppingUiKeepsOldPriceHistoryAndRequiresFreshChoiceAfterPriceReplacement() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val (version, firstPrice) = runBlocking {
            val store = FoodStore(database) { clock }
            val v = store.rememberProduct("p", ProductInput("Testtofu"))
            store.savePlan("plan", PlanInput("Woche", "2026-10-30", "2026-11-05", listOf(PlanEntryInput("eat", "2026-10-30",
                PlanKind.DIRECT_CONSUMPTION, v, amount = 350_000, unit = BaseUnit.GRAM)), "Planung"))
            val price = store.savePrice("price", PriceInput(v, "Testmarkt", "2026-10-30", 200_000, 199, true, true, "Nutzbare Packungsmenge",
                "2026-10-30", "2026-11-05", true, "Erste Beobachtung"))
            val scope = ShoppingScope("2026-10-30", "2026-11-05", "2026-10-30")
            val item = store.shoppingReport(scope).items.single()
            store.markShopping("checked", ShoppingMarkInput(scope, v, price, true, item.snapshot))
            v to price
        }
        val model = mount(database, now = clock)
        tap("Einkauf"); rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report != null }
        field("Bedarf ab JJJJ-MM-TT", "2026-10-30"); field("Geplanter Einkaufstag JJJJ-MM-TT", "2026-10-30"); tap("Einkaufsliste anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.items?.singleOrNull()?.checked == true }
        tap("Packungsbasis und Preis speichern"); choose("Preisfassung bearbeiten", firstPrice)
        field("Nutzbare Menge der Preisangabe in g · optional", "100"); field("Preis für diese Menge in EUR · optional", "1,49")
        field("Mengenbasis beschreiben, etwa Abtropfgewicht", "Neue Packung: 100 g nutzbare Menge")
        tap("Nutzbare Packungsbasis ausdrücklich bestätigt"); tap("Preisangabe geprüft · keine bezahlte Ausgabe")
        field("Quelle / Änderungsgrund der Preisangabe", "Neue Kaufpackung geprüft")
        tap("Preisfassung lokal speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.prices?.size == 2 }
        val current = model.shopping.value.report!!
        assertTrue(current.items.single().staleCheck); assertTrue(!current.items.single().checked); assertEquals(null, current.estimatedTotalCents)
        rule.onNodeWithTag("shopping_checked_$version").assertTextEquals("Gespeichert: nicht abgehakt")
        val secondPrice = current.prices.single { it.supersedesId == firstPrice }.id
        tap("Neuen Vorgang beginnen"); choose("Packungs-/Preisfassung ${version.take(8)}", secondPrice)
        rule.onNodeWithTag("shopping_packs_$version").assertTextEquals("Ganze Packungen: 4")
        rule.onNodeWithTag("shopping_cost_$version").assertTextEquals("Kosten dieser Auswahl: 5,96 €")
        tap("Auswahl / Markierung speichern ${version.take(8)}"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.estimatedTotalCents == 596L }
        runBlocking {
            assertEquals(2, database.foodDao().prices().size)
            assertEquals(2, database.foodDao().shoppingMarks("2026-10-30", "2026-11-05", "2026-10-30").size)
            assertTrue(database.foodDao().planningBatches().isEmpty()); assertEquals(0, database.foodDao().consumptionCount())
        }
    }

    @Test fun shoppingUiReadsPrivatePaidHistoryWithoutCreatingAnOfferOrBooking() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val version = runBlocking {
            val store = FoodStore(database) { clock }
            val v = store.rememberProduct("paid-product", ProductInput("Testtofu"))
            store.savePlan("paid-plan", PlanInput("Woche", "2026-10-30", "2026-11-05",
                listOf(PlanEntryInput("need", "2026-10-30",
                    PlanKind.DIRECT_CONSUMPTION, v, amount = 350_000, unit = BaseUnit.GRAM)),
                "Synthetischer Bedarf"))
            val receipt = store.savePurchase("paid-buy",
                PurchaseInput("Testmarkt", "2026-10-30", 398L,
                    listOf(PurchaseLineInput("TOFU", "Tofu", v, totalCents = 398L)),
                    "Synthetisch bezahlt"), true)
            val lineId = database.foodDao().receiptLines(receipt).single().id
            store.saveHistoricalPaidPrice("paid-price",
                PaidPriceBasisReview("paid-buy", lineId, v, 2L, 0L, null,
                    discountReconciled = true, amountExcludesDeposit = true,
                    unitsConfirmed = true, foodAndProductConfirmed = true,
                    basisReason = "Zwei Einheiten, Rabatt und Warenpreis bestätigt"), true)
            v
        }
        val initialOperations = runBlocking { database.foodDao().operationCount() }
        val model = mount(database, now = clock)
        tap("Einkauf")
        field("Bedarf ab JJJJ-MM-TT", "2026-10-30")
        field("Geplanter Einkaufstag JJJJ-MM-TT", "2026-10-30")
        tap("Einkaufsliste anzeigen")
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            model.shopping.value.report?.items?.any { it.need.versionId == version } == true
        }
        tap("Eigene vergangene Bezahlpreise ansehen")
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            model.historicalPaid.value.report?.references?.size == 1
        }
        rule.onNodeWithTag("shopping_paid_history_$version")
            .assertTextContains("1 belegte Positionen")
        rule.onNodeWithText("Kein aktuelles Angebot", substring = true).assertExists()
        runBlocking {
            assertEquals(initialOperations, database.foodDao().operationCount())
            assertTrue(database.foodDao().prices().isEmpty())
            assertTrue(database.foodDao().shoppingMarks("2026-10-30", "2026-11-05", "2026-10-30").isEmpty())
            assertEquals(0, database.foodDao().consumptionCount())
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun manualPurchaseUiKeepsUnresolvedLinesAndBooksOnlyOneExplicitlyConfirmedUsableQuantity() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val ids = runBlocking {
            val store = FoodStore(database) { clock }
            store.rememberProduct("p", ProductInput("Testtofu")) to store.addLocation("loc", "Testlager", true)
        }
        val model = mount(database, fontScale = 1.3f, now = clock)
        tap("Käufe"); tap("Bezahlten Einkauf erfassen")
        field("Händler · optional", "Testmarkt")
        field("Tatsächliches Kaufdatum JJJJ-MM-TT · optional", "2026-10-30")
        field("Tatsächlich bezahlt in EUR · optional", "3,98")
        field("Originalbontext 1 · nur wenn lesbar", "TOFU 2x1,99")
        field("Eigene Bezeichnung 1 · optional", "Testtofu")
        field("Positionsbetrag 1 in EUR · optional", "3,98")
        tap("Weitere Belegposition"); field("Eigene Bezeichnung 2 · optional", "Ungeklärt")
        field("Grund / Herkunft der Kauferfassung", "Manuell vom Testbon")
        tap("Bezahlten Einkauf speichern")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.saveState.value.message != null }
        assertTrue(!model.saveState.value.succeeded)
        runBlocking { assertTrue(database.foodDao().receipts().isEmpty()) }
        tap("Tatsächlich bezahlten Einkauf mit diesen Angaben bestätigen · noch kein Lagerzugang")
        tap("Bezahlten Einkauf speichern"); saved(model)
        val line = runBlocking {
            val dao = database.foodDao(); val receipt = dao.receipts().single()
            assertEquals(398L, receipt.totalCents); assertEquals(2, dao.receiptLines(receipt.id).size)
            assertTrue(dao.planningBatches().isEmpty()); assertEquals(0, dao.consumptionCount())
            assertEquals(null, dao.receiptLines(receipt.id).single { it.interpretedName == "Ungeklärt" }.originalText)
            dao.receiptLines(receipt.id).single { it.interpretedName == "Testtofu" }
        }
        tap("Neuen Vorgang beginnen")
        field("Ausgaben ab JJJJ-MM-TT", "2026-10-01"); field("Ausgaben bis JJJJ-MM-TT", "2026-10-30"); tap("Ausgabenzeitraum anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.expenses.value.report?.purchases?.size == 1 }
        tap("Position ins Lager buchen ${line.id.take(8)}")
        choose("Kauf-Produktversion ${line.id.take(8)}", ids.first); choose("Kauf-Lagerort ${line.id.take(8)}", ids.second)
        field("Nutzbarer Zugang in g ${line.id.take(8)}", "200")
        field("Mengenbasis der Position ${line.id.take(8)}", "200 g nach Abtropfen")
        tap("Nutzbare Menge und Einheit dieser Position geprüft ${line.id.take(8)}")
        field("Grund des Positionszugangs ${line.id.take(8)}", "Tatsächlichen Inhalt geprüft")
        tap("Diesen tatsächlichen Lagerzugang bestätigen ${line.id.take(8)}")
        tap("Kaufposition buchen ${line.id.take(8)}"); saved(model)
        runBlocking {
            val dao = database.foodDao(); val batch = dao.planningBatches().single()
            assertEquals(200_000L, batch.stockAmount); assertEquals(ReleaseStatus.UNCHECKED, batch.batch.release)
            assertEquals(1, dao.purchases().size); assertEquals(398L, FoodStore(database) { clock }.expenseReport(ExpenseScope("2026-10-01", "2026-10-30")).knownSubtotalCents)
            assertEquals(0, dao.consumptionCount())
        }
        tap("Neuen Vorgang beginnen"); tap("Ausgaben vollständig abgleichen / wieder öffnen")
        tap("Alle Ausgaben im angezeigten Zeitraum vollständig erfasst und geprüft")
        field("Grund des Ausgabenabgleichs", "Alle Testausgaben geprüft")
        tap("Diesen Erfassungsstatus ausdrücklich bestätigen"); tap("Ausgabenstatus speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.expenses.value.report?.complete == true }
        rule.onNodeWithTag("expense_total").assertTextEquals("Vollständig abgeglichene Ausgaben: 3,98 €")
        rule.onNodeWithTag("expense_total").performScrollTo(); capture("local-purchases-small-large-text")
    }

    @Test fun expenseUiCancellationInvalidatesBudgetAndFreshExplicitZeroRestoresRemainingLimit() {
        val database = database(); db = database
        val clock = java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        val budget = runBlocking {
            val store = FoodStore(database) { clock }
            store.savePurchase("paid", PurchaseInput("Testmarkt", "2026-10-30", 800, emptyList(), "Testzahlung"), true)
            store.saveBudget("budget", BudgetInput("Monat", "2026-10-01", "2026-10-31", 1000, true, "Testobergrenze"))
        }
        val model = mount(database, now = clock)
        tap("Käufe")
        field("Ausgaben ab JJJJ-MM-TT", "2026-10-01"); field("Ausgaben bis JJJJ-MM-TT", "2026-10-30"); tap("Ausgabenzeitraum anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.expenses.value.report?.knownSubtotalCents == 800L }
        tap("Ausgaben vollständig abgleichen / wieder öffnen")
        tap("Alle Ausgaben im angezeigten Zeitraum vollständig erfasst und geprüft")
        field("Grund des Ausgabenabgleichs", "Vollständig geprüft")
        tap("Diesen Erfassungsstatus ausdrücklich bestätigen"); tap("Ausgabenstatus speichern"); saved(model)
        tap("Einkauf"); rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report != null }
        field("Bedarf ab JJJJ-MM-TT", "2026-10-30"); field("Geplanter Einkaufstag JJJJ-MM-TT", "2026-10-30")
        choose("Budget für Einkaufsvergleich", budget); tap("Einkaufsliste anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.budget?.remainingBudgetCents == 200L }
        rule.onNodeWithTag("shopping_budget_remaining").assertTextEquals("Verbleibendes Budget: 2,00 €")
        tap("Neuen Vorgang beginnen"); tap("Käufe")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.expenses.value.report?.purchases?.size == 1 }
        tap("Fehlerhafte Buchung prüfen · Kaufbuchung paid")
        field("Stornodatum paid", "2026-10-30"); field("Stornogrund paid", "Falsche Testzahlung")
        tap("Fehlerhafte Buchung ausdrücklich stornieren paid"); tap("Kaufbuchung stornieren paid"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.expenses.value.report?.staleConfirmation == true }
        rule.onNodeWithTag("expense_total").assertTextEquals("Vollständig abgeglichene Ausgaben: Ungeklärt")
        tap("Neuen Vorgang beginnen"); tap("Ausgaben vollständig abgleichen / wieder öffnen")
        tap("Alle Ausgaben im angezeigten Zeitraum vollständig erfasst und geprüft")
        field("Grund des Ausgabenabgleichs", "Nach Testkorrektur vollständig geprüft")
        tap("Diesen Erfassungsstatus ausdrücklich bestätigen"); tap("Ausgabenstatus speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.expenses.value.report?.complete == true }
        tap("Einkauf"); rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.budget?.remainingBudgetCents == 1000L }
        rule.onNodeWithTag("shopping_budget_remaining").assertTextEquals("Verbleibendes Budget: 10,00 €")
        runBlocking {
            assertEquals(1, database.foodDao().purchases().size); assertTrue(database.foodDao().planningBatches().isEmpty())
            assertTrue(database.foodDao().reversal("paid") != null)
        }
    }

    private fun capture(name: String) = rule.runOnUiThread {
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = java.io.File("build/reports/demo-screenshots/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
