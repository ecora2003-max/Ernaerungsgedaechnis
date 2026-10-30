package de.ernaehrung.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.ernaehrung.demo.data.DemoRepository
import de.ernaehrung.demo.navigation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun screen(id:String) { rule.onNodeWithTag("screen_$id").assertExists(); rule.onNodeWithTag("tab_${Screen.valueOf(id).tab.name}").assertIsSelected() }
    private fun tap(tag:String) { rule.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun go(id:String) { tap("go_$id"); screen(id) }
    private fun open(id:String) {
        rule.onNodeWithTag("tab_More").performClick()
        rule.onNodeWithText("Demo-Rundgang · alle 33 Ansichten").performScrollTo().performClick()
        rule.onAllNodesWithTag("go_$id").onLast().performScrollTo().performClick()
        screen(id)
    }
    private fun notice() { rule.onNodeWithTag("demo_dialog").assertExists() }
    private fun closeNotice() { rule.onNodeWithTag("dialog_confirm").performClick() }
    @Test fun startDiaryAndBothBackButtons() {
        screen("S01"); go("S02"); go("T02"); go("T03")
        rule.onNodeWithTag("back").performClick(); screen("T02")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; screen("S02")
    }
    @Test fun bodyTabsAndChartFilters() {
        go("K01"); rule.onNodeWithText("Training",substring=false).performClick(); screen("K02")
        rule.onNodeWithText("Entwicklung",substring=false).performClick(); screen("K03")
        rule.onNodeWithText("7 Tage",substring=false).performClick()
        rule.onNodeWithContentDescription("4 fiktive Rohmesspunkte; gestrichelte vorbereitete Beispielkurve").assertExists()
        rule.onNodeWithText("Fettmasse",substring=false).performClick()
        rule.onNodeWithText("Fettmasse · 7 Tage",substring=false).assertExists()
    }
    @Test fun productCatalogueIncludesZeroStockAndRetainsSelection() {
        rule.onNodeWithTag("tab_Scan").performClick(); go("P02"); go("P03"); go("L01")
        rule.onNodeWithText("Bestand 0",substring=false).performClick()
        tap("product_DEMO-CHICK-001"); screen("P02")
        rule.onNodeWithText("Kichererbsen",substring=false).assertExists()
        go("T02"); rule.onNodeWithText("Kichererbsen",substring=false).assertExists()
        open("L02"); tap("product_DEMO-TOFU-001"); screen("L03"); go("T02")
    }
    @Test fun receiptPreviewCannotBookStock() {
        val before = DemoRepository.products.map { it.quantity }
        open("E01"); tap("Bon fotografieren"); notice(); closeNotice(); screen("E02")
        go("E03"); tap("Beleg speichern"); notice(); closeNotice()
        tap("Bestätigte Vorräte buchen"); notice(); closeNotice(); screen("L02")
        assertEquals(before, DemoRepository.products.map { it.quantity })
        open("E03"); go("H03")
    }
    @Test fun recipeAndMealPrepRemainPreviews() {
        open("R01"); tap("recipe_DEMO-R01"); screen("R02"); go("R03")
        rule.onNodeWithText("Tatsächlich hergestellte Portionen").performTextInput("6")
        tap("Produktion bestätigen"); notice(); closeNotice(); screen("L02")
        assertEquals(6.0, DemoRepository.product("DEMO-CURRY-001").quantity, 0.0)
    }
    @Test fun planningNeedsAndShoppingAreConnected() {
        rule.onNodeWithTag("tab_Plan").performClick(); screen("G01")
        rule.onNodeWithText("Monat",substring=false).performClick(); screen("G02"); go("G01")
        go("G03"); go("T01"); assertEquals(1, DemoRepository.eaten.size)
        open("H01"); go("H02"); tap("check_DEMO-TOFU-001")
        rule.onNodeWithTag("check_DEMO-TOFU-001").assertIsOn()
        assertEquals(400.0, DemoRepository.tofu.quantity, 0.0); go("E01")
    }
    @Test fun simulatedImportAndQualityChecksAreReachable() {
        open("I01"); go("I02"); tap("Demo-Datei auswählen"); go("I03")
        tap("Konflikte ansehen"); notice(); closeNotice()
        tap("Plan übernehmen"); notice(); closeNotice(); screen("G02")
        assertEquals(8, DemoRepository.products.size)
        open("M01"); open("M02"); go("P02")
        open("M02"); go("E02")
    }
    @Test fun unclearOrRepeatedConsumptionCannotChangeData() {
        open("T02")
        rule.onNodeWithText("Tatsächlich gegessene Menge").performTextReplacement("etwas")
        tap("Verzehr bestätigen"); rule.onNodeWithText("Menge noch unklar").assertExists(); closeNotice()
        repeat(2) {
            rule.onNodeWithText("Tatsächlich gegessene Menge").performTextReplacement("50")
            tap("Verzehr bestätigen"); notice(); rule.onNodeWithText("Schließen",substring=false).performClick()
        }
        assertEquals(400.0, DemoRepository.tofu.quantity, 0.0); assertEquals(1, DemoRepository.eaten.size)
    }
    @Test fun measurementAndActivityFormsAcceptInputWithoutSaving() {
        open("K01"); tap("Neue Messung"); rule.onNodeWithTag("form_dialog").assertExists()
        rule.onNodeWithText("Gewicht in kg").performScrollTo().performTextInput("81.5")
        rule.onNodeWithText("Speichern · Demo",substring=false).performClick(); notice(); closeNotice()
        rule.onNodeWithText("Training",substring=false).performScrollTo().performClick(); tap("Einheit eintragen")
        rule.onNodeWithText("Dauer in Minuten").performScrollTo().performTextInput("30")
        rule.onNodeWithText("Speichern · Demo",substring=false).performClick(); notice(); closeNotice()
        assertEquals(8, DemoRepository.measurements.size); assertEquals(3, DemoRepository.activities.size)
    }
    @Test fun correctionAndBackupButtonsDoNotWrite() {
        open("P03"); tap("Korrektur speichern"); notice(); closeNotice()
        assertEquals(125.0, DemoRepository.tofu.nutrition.kcal!!, 0.0)
        open("M03")
        listOf("Backup erstellen","Daten exportieren","Sicherung wiederherstellen").forEach { tap(it); notice(); closeNotice() }
    }
}
