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

/** Real Room/Compose shopping: local pack alternatives stay suggestions, paid history stays private. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
class LocalShoppingComparisonUiTest {
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

    private fun tap(label: String) {
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            rule.onAllNodesWithText(label, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(label, substring = false).performScrollTo().performClick()
    }

    private fun openShopping(model: LocalDataViewModel, version: String) {
        tap("Einkauf")
        rule.onNodeWithText("Bedarf ab JJJJ-MM-TT").performScrollTo().performTextReplacement("2026-10-30")
        rule.onNodeWithText("Geplanter Einkaufstag JJJJ-MM-TT").performScrollTo().performTextReplacement("2026-10-30")
        tap("Einkaufsliste anzeigen")
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            model.shopping.value.report?.items?.any { it.need.versionId == version } == true
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java
    ).build()

    private suspend fun plan(store: FoodStore): String {
        val version = store.rememberProduct("product", ProductInput("Testtofu"))
        store.savePlan("plan", PlanInput("Woche", "2026-10-30", "2026-11-05",
            listOf(PlanEntryInput("need", "2026-10-30", PlanKind.DIRECT_CONSUMPTION,
                version, amount = 350_000, unit = BaseUnit.GRAM)), "Synthetischer Bedarf"))
        return version
    }

    @Test fun twoVerifiedOfflinePacksCanBeComparedAndChosenWithoutBuying() {
        val database = database()
        val version = runBlocking {
            val store = FoodStore(database) { now }
            val v = plan(store)
            store.savePrice("price-a", PriceInput(v, "Markt A", "2026-10-30", 200_000, 199,
                true, true, "200 g nutzbar", "2026-10-30", "2026-11-05", true, "Lokal geprüft"))
            store.savePrice("price-b", PriceInput(v, "Markt B", "2026-10-30", 400_000, 350,
                true, true, "400 g nutzbar", "2026-10-30", "2026-11-05", true, "Lokal geprüft"))
            val receipt = store.savePurchase("buy", PurchaseInput("Testmarkt", "2026-10-30",
                398L, listOf(PurchaseLineInput("TOFU", "Tofu", v, totalCents = 398L)),
                "Bezahlter Bon"), true)
            val lineId = database.foodDao().receiptLines(receipt).single().id
            store.saveHistoricalPaidPrice("paid", PaidPriceBasisReview("buy", lineId, v,
                2L, 0L, null, discountReconciled = true, amountExcludesDeposit = true,
                unitsConfirmed = true, foodAndProductConfirmed = true,
                basisReason = "Zwei bestätigte Kaufeinheiten"), true)
            v
        }
        val model = mount(database)
        openShopping(model, version)
        val item = model.shopping.value.report!!.items.single()
        val cheaper = item.quotes.single { it.costCents == 350L }.offer!!.id
        rule.onNodeWithTag("shopping_cheapest_$version").performScrollTo()
            .assertTextContains("3,50 €", substring = true)
        rule.onNodeWithTag("shopping_comparison_$cheaper").performScrollTo()
            .assertTextContains("Markt B", substring = true)
        tap("Eigene vergangene Bezahlpreise ansehen")
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            model.historicalPaid.value.report?.references?.size == 1
        }
        rule.onNodeWithTag("shopping_paid_history_$version").performScrollTo().assertExists()
        tap("Packungsalternative prüfen ${cheaper.take(8)}")
        rule.onNodeWithTag("shopping_comparison_$cheaper").performScrollTo()
            .assertTextContains("zur Prüfung ausgewählt", substring = true)
        runBlocking {
            assertTrue(database.foodDao().shoppingMarks("2026-10-30", "2026-11-05", "2026-10-30").isEmpty())
        }
        tap("Auswahl / Markierung speichern ${version.take(8)}")
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            model.shopping.value.report?.items?.singleOrNull()?.offer?.id == cheaper
        }
        runBlocking {
            assertEquals(1, database.foodDao().shoppingMarks("2026-10-30", "2026-11-05", "2026-10-30").size)
            assertEquals(1, database.foodDao().purchases().size)
            assertEquals(1, database.foodDao().allPaidPrices().size)
            assertEquals(2, database.foodDao().prices().size)
            assertTrue(database.foodDao().planningBatches().isEmpty())
            assertEquals(0, database.foodDao().consumptionCount())
        }
    }

    @Test fun singleOfflinePackDoesNotClaimAComparisonOrBookAnything() {
        val database = database()
        val version = runBlocking {
            val store = FoodStore(database) { now }
            val v = plan(store)
            store.savePrice("only", PriceInput(v, "Markt A", "2026-10-30", 200_000, 199,
                true, true, "200 g nutzbar", "2026-10-30", "2026-11-05", true, "Lokal geprüft"))
            v
        }
        val model = mount(database)
        openShopping(model, version)
        rule.onAllNodesWithTag("shopping_cheapest_$version").assertCountEquals(0)
        rule.onNodeWithText("Weniger als zwei vollständig berechenbare Packungsalternativen.", substring = true)
            .performScrollTo().assertExists()
        runBlocking {
            assertTrue(database.foodDao().shoppingMarks("2026-10-30", "2026-11-05", "2026-10-30").isEmpty())
            assertTrue(database.foodDao().purchases().isEmpty())
            assertTrue(database.foodDao().allPaidPrices().isEmpty())
            assertEquals(0, database.foodDao().consumptionCount())
        }
    }
}
