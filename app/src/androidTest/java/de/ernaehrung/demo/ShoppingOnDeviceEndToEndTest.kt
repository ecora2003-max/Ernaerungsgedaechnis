package de.ernaehrung.demo

import android.content.pm.ApplicationInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.recognition.AndroidPhotoRecognition
import de.ernaehrung.demo.data.recognition.CaptureFiles
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.LocalDataViewModel
import de.ernaehrung.demo.ui.screens.LocalDataContent
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real offline OCR -> actual correction/payment/stock/price/shopping UI -> disk reopen.
 * Uses a synthetic content URI, not a real camera or a forged camera Activity result.
 * The device workflow disables Wi-Fi/data; no external catalog/feed is configured.
 */
@RunWith(AndroidJUnit4::class)
class ShoppingOnDeviceEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlockFixtureDevice() = prepareUnlockedTestDevice()
    private var models = ViewModelStore()

    private fun tap(text: String) {
        compose.waitUntil(25_000) {
            compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text, substring = false).performScrollTo().performClick()
    }

    private fun field(label: String, value: String) =
        compose.onNodeWithText(label, substring = false).performScrollTo().performTextReplacement(value)

    private fun choose(label: String, id: String) {
        compose.onNodeWithTag(label).performScrollTo().performClick()
        compose.onNodeWithTag("choose_$id").performScrollTo().performClick()
    }

    private fun saved(model: LocalDataViewModel, expected: Boolean = true) {
        compose.waitUntil(25_000) {
            !model.saveState.value.busy && model.saveState.value.message != null
        }
        assertEquals(model.saveState.value.message, expected, model.saveState.value.succeeded)
    }

    private fun mount(store: FoodStore, section: String): LocalDataViewModel {
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LocalDataViewModel(store) as T
        })[LocalDataViewModel::class.java]
        val generation = UUID.randomUUID().toString()
        compose.runOnUiThread {
            compose.activity.setContent {
                DemoTheme {
                    key(generation) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            LocalDataContent(model, section, "Bon & OCR")
                        }
                    }
                }
            }
        }
        compose.waitUntil(25_000) { !model.state.value.loading }
        return model
    }

    @Test fun offlineOcrCorrectionConfirmedPurchaseStockPrivatePriceBudgetAndRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val name = "shopping-device-" + UUID.randomUUID() + ".db"
        val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
        val day = today.toString()
        val end = today.plusDays(6).toString()
        val receiptDay = "2026-10-30" // Printed on the synthetic OCR fixture.
        fun open() = Room.databaseBuilder(target, FoodDatabase::class.java, name)
            .addMigrations(FoodMigrations.MIGRATION_1_2, FoodMigrations.MIGRATION_2_3,
                FoodMigrations.MIGRATION_3_4, FoodMigrations.MIGRATION_4_5,
                FoodMigrations.MIGRATION_5_6, FoodMigrations.MIGRATION_6_7,
                FoodMigrations.MIGRATION_7_8, FoodMigrations.MIGRATION_8_9).build()
        var db = open()
        val files = CaptureFiles(target)
        val capture = files.create()
        try {
            var store = FoodStore(db)
            val version = store.rememberProduct("device-tofu", ProductInput(
                "Gerätetest-Tofu", source = Source.USER, quality = Quality.CONFIRMED,
                metadata = ProductMetadataInput("4006381333931", null, null, null)))
            val location = store.addLocation("device-kitchen", "Testkühlschrank", true)
            store.savePlan("device-need", PlanInput("Geräte-Testbedarf", day, end,
                listOf(PlanEntryInput("device-meal", day, PlanKind.DIRECT_CONSUMPTION,
                    version, amount = 550_000L, unit = BaseUnit.GRAM)), "Nur synthetische Daten"))
            store.savePrice("device-pack-a", PriceInput(version, "Offline A", day,
                200_000L, 199L, true, true, "200 g nutzbar", day, end, true, "Lokal geprüft"))
            val cheaper = store.savePrice("device-pack-b", PriceInput(version, "Offline B", day,
                400_000L, 350L, true, true, "400 g nutzbar", day, end, true, "Lokal geprüft"))
            val budget = store.saveBudget("device-budget", BudgetInput("Gerätebudget",
                receiptDay, day, 1_000L, true, "Ausdrücklich geprüft"))
            var model = mount(store, "Erkennung")
            val before = db.foodDao().operationCount()
            target.contentResolver.openOutputStream(files.uri(capture))!!.use { output ->
                instrumentation.context.assets.open("recognition/receipt-test.png").use { it.copyTo(output) }
            }
            // Invoke the exact production image handler; all later actions use the real UI.
            compose.runOnUiThread {
                model.analyzePhoto(files.uri(capture), true, "GALLERY_OCR",
                    AndroidPhotoRecognition(target)) { files.remove(capture) }
            }
            compose.waitUntil(30_000) {
                !model.photo.value.busy && model.photo.value.text != null &&
                    !File(target.cacheDir, "recognition/$capture").exists()
            }
            val ocr = requireNotNull(model.photo.value.text)
            assertEquals(before, db.foodDao().operationCount())
            assertFalse(File(target.cacheDir, "recognition/$capture").exists())
            assertTrue(db.foodDao().purchases().isEmpty())
            tap("Erkannten Text als Bonentwurf speichern")
            saved(model)
            tap("Neuen Vorgang beginnen")
            compose.waitUntil(25_000) { model.receiptReview.value.review != null }
            val review = requireNotNull(model.receiptReview.value.review)
            assertEquals(2, review.parsed.lines.size)
            assertTrue(db.foodDao().purchases().isEmpty())
            tap("Bonposition 1 prüfen")
            field("Geprüfte Positionsbezeichnung 1", "Tofu manuell korrigiert")
            choose("Produktvorschlag der Bonposition 1", version)
            field("Grund / Herkunft der Bonbestätigung", "Originalbeleg und Zahlung selbst geprüft")
            // A visible save button and a recognized receipt cannot confirm payment.
            tap("Geprüften Bon als bezahlten Einkauf übernehmen")
            saved(model, false)
            assertTrue(db.foodDao().purchases().isEmpty())
            tap("Bonangaben geprüft und diesen Einkauf tatsächlich bezahlt · kein Lagerzugang")
            tap("Geprüften Bon als bezahlten Einkauf übernehmen")
            saved(model)
            assertEquals(1, db.foodDao().purchases().size)
            assertTrue(db.foodDao().planningBatches().isEmpty())
            assertTrue(db.foodDao().allPaidPrices().isEmpty())
            val purchase = db.foodDao().purchases().single()
            val receipt = requireNotNull(db.foodDao().receipt(purchase.receiptId))
            val lines = db.foodDao().receiptLines(receipt.id)
            val line = lines.single { it.versionId == version }
            assertEquals("Tofu manuell korrigiert", line.interpretedName)
            assertEquals(497L, receipt.totalCents)
            val purchaseInput = PurchaseInput(receipt.merchant, receipt.purchasedOn, receipt.totalCents,
                // Replay the original submitted order. Persisted line IDs are
                // random and the DAO's ID order is not the request's order.
                review.parsed.lines.map { parsed -> lines.single { it.originalText == parsed.originalText } }
                    .map { PurchaseLineInput(it.originalText, it.interpretedName, it.versionId,
                        it.amount, it.unit, it.totalCents) }, purchase.reason, receipt.source)
            tap("Neuen Vorgang beginnen")
            tap("Kauf und private Preisprüfung öffnen ${review.draft.scan.operationId.take(8)}")
            field("Ausgaben ab JJJJ-MM-TT", receiptDay)
            field("Ausgaben bis JJJJ-MM-TT", day)
            tap("Ausgabenzeitraum anzeigen")
            compose.waitUntil(25_000) { model.expenses.value.report?.purchases?.size == 1 }
            val suffix = line.id.take(8)
            tap("Privaten Bezahlpreis prüfen $suffix")
            field("Bezahlte Einheiten $suffix", "1")
            field("Zugeordneter Rabatt in EUR $suffix", "0")
            tap("Rabattzuordnung vollständig geprüft $suffix")
            tap("Positionsbetrag ohne Pfand geprüft $suffix")
            tap("Bezahlte Einheiten geprüft $suffix")
            tap("Lebensmittel und Produktversion geprüft $suffix")
            field("Grund der privaten Preisbestätigung $suffix", "Eine geprüfte Kaufeinheit ohne Pfand")
            tap("Privaten historischen Preis ausdrücklich bestätigen $suffix")
            tap("Privaten Bezahlpreis speichern $suffix")
            saved(model)
            assertEquals(199L, db.foodDao().allPaidPrices().single().unitPriceCents)
            assertEquals(2, db.foodDao().prices().size) // No conversion of receipt history into an offer.
            tap("Neuen Vorgang beginnen")
            tap("Position ins Lager buchen $suffix")
            choose("Kauf-Lagerort $suffix", location)
            field("Nutzbarer Zugang in g $suffix", "200")
            field("Mengenbasis der Position $suffix", "200 g nutzbares Nettogewicht")
            field("MHD der Kaufposition $suffix · optional", end)
            tap("Nutzbare Menge und Einheit dieser Position geprüft $suffix")
            tap("Charge dieser Kaufposition ausdrücklich freigeben $suffix")
            field("Grund des Positionszugangs $suffix", "Tatsächlichen Inhalt geprüft")
            tap("Diesen tatsächlichen Lagerzugang bestätigen $suffix")
            tap("Kaufposition buchen $suffix")
            saved(model)
            val stock = requireNotNull(db.foodDao().receiptBooking(line.id))
            val batch = db.foodDao().planningBatches().single().batch
            assertEquals(200_000L, db.foodDao().balance(batch.id))
            tap("Neuen Vorgang beginnen")
            tap("Ausgaben vollständig abgleichen / wieder öffnen")
            tap("Alle Ausgaben im angezeigten Zeitraum vollständig erfasst und geprüft")
            field("Grund des Ausgabenabgleichs", "Alle Testzahlungen vollständig geprüft")
            tap("Diesen Erfassungsstatus ausdrücklich bestätigen")
            tap("Ausgabenstatus speichern")
            saved(model)
            tap("Neuen Vorgang beginnen")
            tap("Einkauf")
            field("Bedarf ab JJJJ-MM-TT", day)
            field("Geplanter Einkaufstag JJJJ-MM-TT", day)
            choose("Budget für Einkaufsvergleich", budget)
            tap("Einkaufsliste anzeigen")
            compose.waitUntil(25_000) { model.shopping.value.report?.scope?.budgetId == budget }
            val item = requireNotNull(model.shopping.value.report).items.single()
            assertEquals(350_000L, item.need.knownMissing)
            compose.onNodeWithTag("shopping_cheapest_$version").performScrollTo().assertTextContains("3,50 €", substring = true)
            compose.onNodeWithTag("shopping_budget_candidate_$cheaper").performScrollTo().assertTextContains("1,53 €", substring = true)
            tap("Eigene vergangene Bezahlpreise ansehen")
            compose.waitUntil(25_000) { model.historicalPaid.value.report?.references?.size == 1 }
            compose.onNodeWithTag("shopping_paid_history_$version").performScrollTo().assertTextContains("1 belegte Positionen", substring = true)
            tap("Packungsalternative prüfen ${cheaper.take(8)}")
            tap("Auf Einkaufsliste abhaken ${version.take(8)} · kein Kauf")
            tap("Auswahl / Markierung speichern ${version.take(8)}")
            saved(model)
            assertEquals(1, db.foodDao().purchases().size)
            assertEquals(1, db.foodDao().allPaidPrices().size)
            assertEquals(200_000L, db.foodDao().balance(batch.id))
            assertEquals(0, db.foodDao().consumptionCount())

            // Destroy UI/ViewModel ownership and reopen real device SQLite, not an in-memory DB.
            compose.runOnUiThread { compose.activity.setContent {} ; models.clear() }
            db.close()
            db = open()
            store = FoodStore(db)
            models = ViewModelStore()
            model = mount(store, "Einkauf")
            field("Bedarf ab JJJJ-MM-TT", day)
            field("Geplanter Einkaufstag JJJJ-MM-TT", day)
            choose("Budget für Einkaufsvergleich", budget)
            tap("Einkaufsliste anzeigen")
            compose.waitUntil(25_000) { model.shopping.value.report?.scope?.budgetId == budget }
            assertEquals(cheaper, model.shopping.value.report?.items?.single()?.offer?.id)
            assertTrue(model.shopping.value.report!!.items.single().checked)
            val count = db.foodDao().operationCount()
            assertEquals(receipt.id, store.acceptReceiptScan(purchase.operationId,
                review.draft.scan.operationId, purchaseInput, review.snapshot, true))
            assertEquals(review.draft.scan.operationId, store.saveReceiptScan(
                "device-scan-retry", ocr.text, "GALLERY_OCR", ocr.imageHash))
            assertEquals(count + 1, db.foodDao().operationCount()) // Only the scan-reuse audit entry.
            assertEquals(1, db.foodDao().purchases().size)
            assertEquals(200_000L, db.foodDao().balance(batch.id))
            // Cancellation is also checked at the real FoodStore boundary on Android.
            assertTrue(runCatching { store.cancelPurchase("cancel-too-early",
                ReversalInput(purchase.operationId, day, "Testkorrektur"), true) }.isFailure)
            store.reverse("reverse-device-stock", ReversalInput(stock.operationId, day, "Testkorrektur"), true)
            store.cancelPurchase("cancel-device-purchase", ReversalInput(purchase.operationId, day, "Testkorrektur"), true)
            assertEquals(0L, db.foodDao().balance(batch.id))
            assertEquals(1, db.foodDao().allPaidPrices().size) // Historical record preserved.
            assertTrue(store.historicalPaidComparison(HistoricalPaidComparisonScope(
                version, day, System.currentTimeMillis())).references.isEmpty())
            assertEquals(0, target.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} ; models.clear() }
            files.remove(capture)
            db.close()
            target.deleteDatabase(name)
        }
    }
}
