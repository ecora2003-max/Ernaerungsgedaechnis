package de.ernaehrung.demo

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LocalDataViewModel
import de.ernaehrung.demo.ui.screens.LocalDataContent
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual purchase UI -> FoodStore private historical price -> shopping UI; no offer or implicit booking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
class LocalPaidPricePurchaseUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private var db: FoodDatabase? = null
    private val now = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()

    @After fun close() { models.clear(); db?.close() }

    private fun mount(database: FoodDatabase): LocalDataViewModel {
        db = database
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LocalDataViewModel(FoodStore(database) { now }) as T
        })[LocalDataViewModel::class.java]
        rule.runOnUiThread {
            rule.activity.setContent {
                DemoTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model) }
                }
            }
        }
        rule.waitUntil(15_000) { rule.waitForIdle(); !model.state.value.loading }
        return model
    }

    private fun tap(text: String) {
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(text, substring = false).performScrollTo().performClick()
    }

    private fun field(label: String, value: String) =
        rule.onNodeWithText(label, substring = false).performScrollTo().performTextReplacement(value)

    private fun choose(label: String, id: String) {
        rule.onNodeWithTag(label).performScrollTo().performClick()
        rule.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }

    private fun saved(model: LocalDataViewModel, expected: Boolean) {
        rule.waitUntil(15_000) { rule.waitForIdle(); model.saveState.value.message != null }
        assertEquals(model.saveState.value.message, expected, model.saveState.value.succeeded)
    }

    private fun receipt(model: LocalDataViewModel, version: String?) {
        tap("Käufe")
        tap("Bezahlten Einkauf erfassen")
        field("Händler · optional", "Testmarkt")
        field("Tatsächliches Kaufdatum JJJJ-MM-TT · optional", "2026-10-30")
        field("Tatsächlich bezahlt in EUR · optional", "3,98")
        field("Originalbontext 1 · nur wenn lesbar", "TOFU 2x 1,99")
        field("Eigene Bezeichnung 1 · optional", "Tofu")
        field("Positionsbetrag 1 in EUR · optional", "3,98")
        if (version != null) choose("Produktversion Belegposition 1", version)
        field("Grund / Herkunft der Kauferfassung", "Bezahlten Originalbon manuell geprüft")
        tap("Tatsächlich bezahlten Einkauf mit diesen Angaben bestätigen · noch kein Lagerzugang")
        tap("Bezahlten Einkauf speichern")
        saved(model, true)
        tap("Neuen Vorgang beginnen")
        field("Ausgaben ab JJJJ-MM-TT", "2026-10-01")
        field("Ausgaben bis JJJJ-MM-TT", "2026-10-30")
        tap("Ausgabenzeitraum anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.expenses.value.report?.purchases?.size == 1 }
    }

    @Test fun confirmedReceiptVersionAndPriceFlowIntoPrivateShoppingHistoryWithoutNewOffer() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        val version = runBlocking {
            val store = FoodStore(database) { now }
            val v = store.rememberProduct("product", ProductInput("Tofu"))
            store.savePlan("need", PlanInput("Woche", "2026-10-30", "2026-11-05",
                listOf(PlanEntryInput("direct", "2026-10-31", PlanKind.DIRECT_CONSUMPTION,
                    v, amount = 350_000, unit = BaseUnit.GRAM)), "Testbedarf"))
            v
        }
        val model = mount(database)
        receipt(model, version)
        val line = runBlocking { database.foodDao().receiptLines(database.foodDao().receipts().single().id).single() }
        assertEquals(version, line.versionId)
        val suffix = line.id.take(8)
        tap("Privaten Bezahlpreis prüfen $suffix")
        field("Bezahlte Einheiten $suffix", "2")
        field("Zugeordneter Rabatt in EUR $suffix", "0")
        tap("Rabattzuordnung vollständig geprüft $suffix")
        tap("Positionsbetrag ohne Pfand geprüft $suffix")
        tap("Bezahlte Einheiten geprüft $suffix")
        tap("Lebensmittel und Produktversion geprüft $suffix")
        field("Grund der privaten Preisbestätigung $suffix", "Zwei Stück, Warenpreis und Pfand separat geprüft")
        tap("Privaten historischen Preis ausdrücklich bestätigen $suffix")
        tap("Privaten Bezahlpreis speichern $suffix")
        saved(model, true)
        runBlocking {
            assertEquals(1, database.foodDao().allPaidPrices().size)
            assertEquals(199L, database.foodDao().allPaidPrices().single().unitPriceCents)
            assertTrue(database.foodDao().prices().isEmpty())
            assertTrue(database.foodDao().planningBatches().isEmpty())
        }
        tap("Neuen Vorgang beginnen")
        tap("Einkauf")
        field("Bedarf ab JJJJ-MM-TT", "2026-10-30")
        field("Geplanter Einkaufstag JJJJ-MM-TT", "2026-10-30")
        tap("Einkaufsliste anzeigen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.shopping.value.report?.items?.any { it.need.versionId == version } == true }
        tap("Eigene vergangene Bezahlpreise ansehen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.historicalPaid.value.report?.references?.size == 1 }
        rule.onNodeWithTag("shopping_paid_history_$version")
            .assertTextContains("1 belegte Positionen", substring = true)
        runBlocking {
            assertEquals(1, database.foodDao().allPaidPrices().size)
            assertTrue(database.foodDao().prices().isEmpty())
            assertTrue(database.foodDao().shoppingMarks("2026-10-30", "2026-11-05", "2026-10-30").isEmpty())
        }
    }

    @Test fun unassignedVersionAndUnknownPriceBasisCannotCreatePrivatePrice() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        val version = runBlocking { FoodStore(database) { now }.rememberProduct("product", ProductInput("Tofu")) }
        val model = mount(database)
        receipt(model, null)
        val line = runBlocking { database.foodDao().receiptLines(database.foodDao().receipts().single().id).single() }
        assertNull(line.versionId)
        rule.onAllNodesWithText("Privaten Bezahlpreis prüfen ${line.id.take(8)}").assertCountEquals(0)
        runBlocking {
            assertTrue(database.foodDao().allPaidPrices().isEmpty())
            assertEquals(1, database.foodDao().purchases().size)
            assertTrue(database.foodDao().prices().isEmpty())
            assertTrue(database.foodDao().planningBatches().isEmpty())
        }
        // A separately selected catalog version is not retroactively attached to the paid line.
        assertNotNull(version)
    }
}
