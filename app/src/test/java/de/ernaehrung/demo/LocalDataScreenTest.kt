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
    private fun tap(text: String) = rule.onNodeWithText(text, substring = false).performScrollTo().performClick()
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

    private fun capture(name: String) = rule.runOnUiThread {
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = java.io.File("build/reports/demo-screenshots/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
