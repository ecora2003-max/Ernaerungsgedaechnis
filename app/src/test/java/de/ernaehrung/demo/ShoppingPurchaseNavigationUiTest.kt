package de.ernaehrung.demo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShoppingPurchaseNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tag(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun text(value: String) = compose.onNodeWithText(value).performScrollTo().performClick()
    private fun input(tag: String,value: String) = compose.onNodeWithTag(tag,useUnmergedTree = true).performScrollTo().performTextReplacement(value)
    private fun value(tag: String,value: String) = compose.onNodeWithTag(tag,useUnmergedTree = true)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(value)))

    @Test fun proposedItemOpensOnlyANewInterpretedLineAndActualZeroPaymentRequiresFreshConsentAfterRecreation() = runBlocking {
        val db = FoodDatabase.localSandbox(compose.activity.applicationContext)
        val dao = db.foodDao(); val store = FoodStore(db)
        val token = UUID.randomUUID().toString(); val today = AppCalendar.today()
        val name = "Einkaufszutat $token"
        val version = store.rememberProduct("$token-product",ProductInput(name))
        store.savePlan("$token-plan",PlanInput("Einkaufsplan $token",today.toString(),today.plusDays(6).toString(),listOf(
            PlanEntryInput("future",today.plusDays(1).toString(),PlanKind.DIRECT_CONSUMPTION,version,amount = 200_000L,unit = BaseUnit.GRAM)),"Eigener Bedarf"))
        val offer = store.savePrice("$token-price",PriceInput(version,"Nur vorgeschlagener Markt",today.toString(),250_000L,299L,true,true,
            "Eigene Packungsbeobachtung",today.toString(),today.plusDays(6).toString(),true,"Etikett geprüft"))
        val scope = ShoppingScope(today.toString(),today.plusDays(6).toString(),today.plusDays(1).toString())
        val line = store.shoppingReport(scope).items.single { it.need.versionId == version }
        store.markShopping("$token-mark",ShoppingMarkInput(scope,version,offer,true,line.snapshot))
        val before = dao.operationCount(); val receipts = dao.receipts().size
        val batches = dao.planningBatches(); val meals = dao.activeConsumptionCount(); val prices = dao.prices()
        compose.onNodeWithTag("workspace_tab_MORE").performClick(); tag("workspace_go_SHOPPING")
        waitTag("shopping_purchase_input"); input("shopping_purchase_input",scope.purchaseOn); tag("Einkaufsliste anzeigen")
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("shopping_checked_$version") and hasText("auf Liste abgehakt",substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shopping_cost_$version").assertTextContains("2,99",substring = true)
        tag("shopping_record_purchase_$version"); waitTag("workspace_page_PURCHASES"); waitTag("purchase_paid_input")
        for (key in listOf("purchase_merchant_input","purchase_date_input","purchase_paid_input","purchase_original_0","purchase_price_0")) value(key,"")
        value("purchase_name_0",name)
        compose.onNodeWithTag("Produktversion Belegposition 1").assertTextContains(version.take(8),substring = true)
        assertEquals(before,dao.operationCount()); assertEquals(receipts,dao.receipts().size)
        // New catalogue versions must not replace the exact item the user chose.
        val productId = requireNotNull(dao.version(version)).productId
        store.rememberProduct("$token-correction",ProductInput("Neuere andere Fassung $token"),productId,"Produkt korrigiert",version)
        input("purchase_name_0","Eigene geprüfte Bezeichnung $token")
        input("purchase_merchant_input","Tatsächlicher Markt"); input("purchase_date_input",today.toString())
        input("purchase_paid_input","0,00"); input("purchase_reason_input","Eigenen tatsächlichen Einkauf geprüft")
        val consent = "Tatsächlich bezahlten Einkauf mit diesen Angaben bestätigen · noch kein Lagerzugang"
        text(consent); compose.onNodeWithText(consent).assertIsOn()
        compose.activityRule.scenario.recreate(); waitTag("workspace_page_PURCHASES")
        compose.onNodeWithTag("purchase_paid_input").assertDoesNotExist()
        text("Bezahlten Einkauf erfassen"); waitTag("purchase_paid_input")
        value("purchase_paid_input","0,00"); value("purchase_name_0","Eigene geprüfte Bezeichnung $token")
        compose.onNodeWithText(consent).assertIsOff()
        tag("Bezahlten Einkauf speichern")
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Tatsächlichen Vorgang ausdrücklich bestätigen.",substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before + 1,dao.operationCount()); assertEquals(receipts,dao.receipts().size)
        text(consent); tag("Bezahlten Einkauf speichern")
        compose.waitUntil(25_000) { runBlocking { dao.receipts().size == receipts + 1 } }
        val receipt = dao.receipts().single { it.merchant == "Tatsächlicher Markt" }
        assertEquals(today.toString(),receipt.purchasedOn); assertEquals(0L,receipt.totalCents)
        val saved = dao.receiptLines(receipt.id).single()
        assertEquals(version,saved.versionId); assertEquals("Eigene geprüfte Bezeichnung $token",saved.interpretedName)
        assertNull(saved.originalText); assertNull(saved.amount); assertNull(saved.unit); assertNull(saved.totalCents)
        val purchase = requireNotNull(dao.purchaseForReceipt(receipt.id))
        assertEquals(receipt.id,store.savePurchase(purchase.operationId,PurchaseInput("Tatsächlicher Markt",today.toString(),0L,
            listOf(PurchaseLineInput(interpretedName = "Eigene geprüfte Bezeichnung $token",versionId = version)),"Eigenen tatsächlichen Einkauf geprüft"),true))
        assertEquals(before + 2,dao.operationCount()); assertEquals(batches,dao.planningBatches())
        assertEquals(meals,dao.activeConsumptionCount()); assertEquals(prices,dao.prices())
    }
}
