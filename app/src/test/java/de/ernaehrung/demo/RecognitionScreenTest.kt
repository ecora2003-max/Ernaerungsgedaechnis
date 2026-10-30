package de.ernaehrung.demo

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.recognition.*
import de.ernaehrung.demo.ui.*
import de.ernaehrung.demo.ui.screens.LocalDataContent
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecognitionScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var db: FoodDatabase
    private val models = ViewModelStore()
    private val clock = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private val code = "3017620422003"
    private val raw = "TESTMARKT\n30.10.2026\nBIO TOFU 200 g 1,99 A\nUNGEKLAERT 2,98 B\nSUMME 4,97\nBAR 10,00\nRUECKGELD 5,03"
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build() }
    @After fun close() { models.clear(); db.close() }
    private fun mount(gateway: ProductLookupGateway = ProductLookupGateway { ProductLookup.NotFound(it) }, fontScale: Float = 1f): LocalDataViewModel {
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = LocalDataViewModel(FoodStore(db) { clock }, gateway) as T
        })[LocalDataViewModel::class.java]
        rule.runOnUiThread { rule.activity.setContent { DemoTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Column(Modifier.verticalScroll(rememberScrollState())) { LocalDataContent(model, initialSection = "Erkennung") }
            }
        } } }
        rule.waitUntil(15_000) { rule.waitForIdle(); !model.state.value.loading && model.recognition.value.data != null }
        return model
    }
    private fun tap(text: String) = rule.onNodeWithText(text, substring = false).performScrollTo().performClick()
    private fun field(label: String, text: String) = rule.onNodeWithText(label, substring = false).performScrollTo().performTextReplacement(text)
    private fun choose(label: String, id: String) {
        rule.onNodeWithTag(label).performScrollTo().performClick()
        rule.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }
    private fun saved(model: LocalDataViewModel) {
        rule.waitUntil(15_000) { rule.waitForIdle(); model.saveState.value.message != null }
        assertTrue(model.saveState.value.message, model.saveState.value.succeeded)
    }
    private fun waitSaveError(model: LocalDataViewModel) {
        rule.waitUntil(15_000) { rule.waitForIdle(); model.saveState.value.message != null }
        assertFalse(model.saveState.value.message, model.saveState.value.succeeded)
    }
    private fun source() = OpenFoodFactsClient.parse(code, """{"status":1,"product":{"code":"$code","product_name":"OFF Testtofu","brands":"Testmarke","quantity":"200 g","product_quantity":200,"product_quantity_unit":"g","nutriments":{"energy-kcal_100g":120,"proteins_100g":13.5,"fat_100g":0,"calcium_100g":0.12},"ingredients_text":"Testzutaten","allergens":"en:soybeans"}}""", clock)

    @Test fun knownBarcodeWorksOfflineAndDoesNotCallExternalProductService() {
        val calls = AtomicInteger()
        val version = runBlocking {
            val store = FoodStore(db) { clock }; val v = store.rememberProduct("p", ProductInput("Lokaler Testtofu"))
            store.saveAlias("a", AliasInput(v, code, null, AliasKind.BARCODE, reason = "Geprüft"), confirmed = true); v
        }
        val model = mount(ProductLookupGateway { calls.incrementAndGet(); throw java.io.IOException("Offline") })
        field("EAN / GTIN · führende Nullen erhalten", code); tap("Barcode im lokalen Katalog prüfen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.aliasLookup.value.match != null }
        rule.onNodeWithTag("barcode_match_status").assertTextEquals("Eindeutig bestätigter Alias · aktuelle Artikelversion")
        assertEquals(version, model.aliasLookup.value.match!!.reusableVersionId); assertEquals(0, calls.get())
        runBlocking { assertEquals(1, db.foodDao().allVersions().size); assertTrue(db.foodDao().purchases().isEmpty()); assertTrue(db.foodDao().planningBatches().isEmpty()) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun externalProductReviewPersistsEditedValuesSourceUnknownsAndBarcodeOnlyAfterExplicitConfirmation() {
        val calls = AtomicInteger(); val model = mount(ProductLookupGateway { calls.incrementAndGet(); source() }, fontScale = 1.3f)
        field("EAN / GTIN · führende Nullen erhalten", code); tap("Produktdaten bei Open Food Facts abfragen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.externalLookup.value.result is ProductLookup.Found }
        runBlocking { assertTrue(db.foodDao().allVersions().isEmpty()) }
        tap("Quellenwerte prüfen und bearbeiten")
        field("Geprüft: Protein in g · optional", "16")
        field("Grund / Quelle der geprüften Produktangaben", "Variante und Quelle geprüft")
        tap("Geprüftes Produkt speichern"); waitSaveError(model)
        runBlocking { assertTrue(db.foodDao().allVersions().isEmpty()); assertTrue(db.foodDao().aliases().isEmpty()) }
        tap("Produktvariante und Bezugsmenge/Einheit ausdrücklich geprüft")
        tap("Diesen Barcode ausdrücklich dieser Artikelversion zuordnen")
        tap("Diese Produktangaben speichern · kein Einkauf und kein Lagerzugang")
        tap("Geprüftes Produkt speichern"); saved(model)
        rule.onNodeWithTag("Geprüftes Produkt speichern").assertIsNotEnabled()
        runBlocking {
            val dao = db.foodDao(); val v = dao.allVersions().single()
            assertEquals("OFF Testtofu", v.name); assertEquals(16.0, v.proteinGrams!!, 0.0); assertEquals(0.0, v.fatGrams!!, 0.0)
            assertNull(v.saltGrams); assertEquals(120.0, v.calciumMg!!, 1e-9); assertEquals(Source.OPEN_FOOD_FACTS, v.source)
            assertEquals(Quality.SOURCED, v.quality); assertEquals("Testzutaten", v.ingredients); assertTrue(v.sourceDetail!!.contains("Open Food Facts"))
            assertEquals(v.id, FoodStore(db).matchAlias(code, null, AliasKind.BARCODE).reusableVersionId)
            assertTrue(dao.purchases().isEmpty()); assertTrue(dao.planningBatches().isEmpty()); assertEquals(0, dao.consumptionCount())
        }
        assertEquals(1, calls.get())
        tap("Katalog"); field("Lokalen Produktkatalog durchsuchen", "Testmarke")
        rule.onNodeWithText("OFF Testtofu", substring = false).performScrollTo().assertExists()
        capture("phase5-product-small-large-text")
    }

    @Test fun failedExternalLookupLeavesManualProductEntryAvailableWithoutInventingFacts() {
        val model = mount(ProductLookupGateway { throw java.io.IOException("Offline") })
        field("EAN / GTIN · führende Nullen erhalten", code); tap("Produktdaten bei Open Food Facts abfragen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.externalLookup.value.error != null }
        rule.onNodeWithText("Produktabfrage: Offline").performScrollTo().assertExists()
        tap("Produkt manuell erfassen"); field("Geprüfter Produktname", "Manueller Testtofu")
        choose("Geprüfte Basiseinheit", BaseUnit.GRAM.name)
        field("Grund / Quelle der geprüften Produktangaben", "Manuell ohne Netz")
        tap("Produktvariante und Bezugsmenge/Einheit ausdrücklich geprüft")
        tap("Diese Produktangaben speichern · kein Einkauf und kein Lagerzugang")
        tap("Geprüftes Produkt speichern"); saved(model)
        runBlocking {
            val v = db.foodDao().allVersions().single()
            assertEquals("Manueller Testtofu", v.name); assertEquals(Source.USER, v.source); assertNull(v.energyKcal); assertNull(v.proteinGrams)
            assertTrue(db.foodDao().aliases().isEmpty()); assertTrue(db.foodDao().purchases().isEmpty())
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun multilineReceiptReviewUsesMerchantAliasKeepsUnresolvedLineAndWritesOnlyEditedConfirmedPayment() {
        val version = runBlocking {
            val store = FoodStore(db) { clock }; val v = store.rememberProduct("p", ProductInput("Testtofu"))
            store.saveAlias("alias", AliasInput(v, "BIO TOFU 200 g", "TESTMARKT", AliasKind.RECEIPT, reason = "Geprüft"), confirmed = true); v
        }
        val model = mount(fontScale = 1.3f)
        tap("Bon & OCR"); tap("Bontext manuell eingeben / einfügen")
        rule.onNodeWithTag("receipt_manual_text").performScrollTo().performTextInput(raw)
        tap("Manuellen Bonentwurf speichern"); saved(model)
        rule.waitUntil(15_000) { rule.waitForIdle(); model.receiptReview.value.review != null }
        val scan = model.receiptReview.value.review!!.draft.scan.operationId
        runBlocking { assertTrue(db.foodDao().receipts().isEmpty()); assertTrue(db.foodDao().purchases().isEmpty()); assertEquals(raw, db.foodDao().receiptScan(scan)!!.rawText) }
        tap("Neuen Vorgang beginnen")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.receiptReview.value.review != null }
        field("Geprüfter bezahlter Gesamtbetrag in EUR · optional", "4,50")
        field("Grund / Herkunft der Bonbestätigung", "Tatsächlichen Testbetrag geprüft")
        tap("Geprüften Bon als bezahlten Einkauf übernehmen"); waitSaveError(model)
        runBlocking { assertTrue(db.foodDao().purchases().isEmpty()) }
        tap("Bonangaben geprüft und diesen Einkauf tatsächlich bezahlt · kein Lagerzugang")
        tap("Geprüften Bon als bezahlten Einkauf übernehmen"); saved(model)
        runBlocking {
            val dao = db.foodDao(); val receipt = dao.receipts().single(); val lines = dao.receiptLines(receipt.id)
            assertEquals(450L, receipt.totalCents); assertEquals(2, lines.size)
            assertEquals(version, lines.single { it.interpretedName == "BIO TOFU 200 g" }.versionId)
            assertNull(lines.single { it.interpretedName == "UNGEKLAERT" }.versionId)
            assertTrue(lines.all { it.amount == null && it.stockOperationId == null })
            assertEquals(raw, dao.receiptScan(scan)!!.rawText); assertTrue(dao.planningBatches().isEmpty()); assertEquals(0, dao.consumptionCount())
            assertEquals(1, dao.purchases().size)
        }
        rule.waitUntil(15_000) { rule.waitForIdle(); model.receiptReview.value.resolved }
        rule.onNodeWithText("Dieser Bonentwurf wurde bereits übernommen oder verworfen. Gespeicherte Käufe stehen unter Käufe; Lagerzugänge dort separat bestätigen.").performScrollTo()
        capture("phase5-receipt-small-large-text")
    }

    @Test fun changingGuessedMerchantClearsAutomaticAliasBeforePaymentButPreservesOriginalScan() {
        runBlocking {
            val store = FoodStore(db) { clock }; val v = store.rememberProduct("p", ProductInput("Testtofu"))
            store.saveAlias("alias", AliasInput(v, "BIO TOFU 200 g", "TESTMARKT", AliasKind.RECEIPT, reason = "Geprüft"), confirmed = true)
            store.saveReceiptScan("scan", raw, "MANUAL_TEXT")
        }
        val model = mount(); tap("Bon & OCR")
        tap("Bonentwurf prüfen scan")
        rule.waitUntil(15_000) { rule.waitForIdle(); model.receiptReview.value.review != null }
        assertNotNull(model.receiptReview.value.review!!.matches.first().reusableVersionId)
        field("Geprüfter Bon-Händler · optional", "Anderer Händler")
        field("Grund / Herkunft der Bonbestätigung", "Händler am Original korrigiert")
        tap("Bonangaben geprüft und diesen Einkauf tatsächlich bezahlt · kein Lagerzugang")
        tap("Geprüften Bon als bezahlten Einkauf übernehmen"); saved(model)
        runBlocking {
            val receipt = db.foodDao().receipts().single()
            assertEquals("Anderer Händler", receipt.merchant)
            assertTrue(db.foodDao().receiptLines(receipt.id).all { it.versionId == null })
            assertEquals(raw, db.foodDao().receiptScan("scan")!!.rawText); assertTrue(db.foodDao().planningBatches().isEmpty())
        }
    }

    @Test fun aliasUiSavesAndRetiresAnExplicitDecisionAcrossNewOperationWithoutStockEffects() {
        val version = runBlocking { FoodStore(db).rememberProduct("p", ProductInput("Testtofu")) }
        val model = mount(); tap("Aliase"); tap("Alias anlegen / berichtigen / zurücknehmen")
        field("Aliastext / Barcode", "BIO TOFU"); field("Händlerbindung des Alias · optional", "Testmarkt")
        choose("Artikelversion des Alias", version); field("Grund der Aliasentscheidung", "Eindeutig geprüft")
        tap("Diese genaue Produktzuordnung ausdrücklich bestätigen"); tap("Aliasentscheidung speichern"); saved(model)
        val first = runBlocking { db.foodDao().aliases().single().id }
        tap("Neuen Vorgang beginnen"); tap("Alias anlegen / berichtigen / zurücknehmen")
        choose("Vorhandene Aliasentscheidung", first)
        tap("Diese Zuordnung weiter verwenden"); field("Grund der Aliasentscheidung", "Falsche Zuordnung zurückgenommen")
        tap("Diese genaue Produktzuordnung ausdrücklich bestätigen"); tap("Aliasentscheidung speichern"); saved(model)
        runBlocking {
            assertEquals(2, db.foodDao().aliases().size)
            assertNull(FoodStore(db).matchAlias("BIO TOFU", "Testmarkt", AliasKind.RECEIPT).reusableVersionId)
            assertTrue(db.foodDao().purchases().isEmpty()); assertTrue(db.foodDao().planningBatches().isEmpty())
        }
    }
    private fun capture(name: String) = rule.runOnUiThread {
        val view = rule.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = java.io.File("build/reports/demo-screenshots/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
