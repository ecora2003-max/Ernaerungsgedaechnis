package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataQualityRoomTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val at = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private val dao get() = db.foodDao()
    private val reader get() = DataQualityReader(db) { at }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FoodDatabase::class.java).build()
        store = FoodStore(db) { at }
    }
    @After fun close() { db.close() }
    private fun complete(name: String) = ProductInput(name).withNutrients(Nutrient.entries.associateWith { 0.0 })

    @Test fun reviewedProductCorrectionsRejectChangedPredecessorsAtomicallyAndKeepOldAndNewRetries() = runBlocking {
        val original = ProductInput("Original", energyKcal = null)
        val v1 = store.rememberProduct("v1", original)
        val identity = dao.version(v1)!!.productId
        val correction = complete("Geprüfte Null")
        val v2 = store.rememberProduct("v2", correction, identity, "Ausdrücklich geprüft", v1)
        assertEquals(v2, store.rememberProduct("v2", correction, identity, "Ausdrücklich geprüft", v1))
        try {
            store.rememberProduct("stale", ProductInput("Andere alte Eingabe"), identity, "Veraltete Prüfung", v1)
            fail("Stale reviewed version must not overwrite a newer one")
        } catch (_: IllegalArgumentException) { }
        assertNull(dao.operation("stale")); assertEquals(v2, dao.latestVersion(identity)!!.id)
        assertEquals(2, dao.versions(identity).size)
        assertEquals(v1, store.rememberProduct("v1", original))
        assertNull(dao.version(v1)!!.energyKcal)
        assertEquals(0.0, dao.version(v2)!!.energyKcal!!, 0.0)
    }

    @Test fun currentProductNullIsNotZeroAndConfirmedHistoricalDaysKeepTheirActualVersionGaps() = runBlocking {
        val old = store.rememberProduct("old", ProductInput("Unbekannte Werte"))
        val product = dao.version(old)!!.productId
        store.consume("old-meal", ConsumptionInput(old, "2026-10-29", "Historisch gegessen", 100_000), true)
        store.setDayStatus("complete", "2026-10-29", DayStatus.COMPLETE, store.dayNutrition("2026-10-29").snapshot, true)
        val fresh = store.rememberProduct("fresh", complete("Vollständige bekannte Null"), product, "Etikett berichtigt")
        store.consume("unknown-amount", ConsumptionInput(fresh, "2026-10-30", "Menge unbekannt", null), true)
        store.savePlan("only-plan", PlanInput("Nur geplant", "2026-10-28", "2026-10-28", listOf(
            PlanEntryInput("p", "2026-10-28", PlanKind.DIRECT_CONSUMPTION, productVersionId = fresh,
                amount = 100_000, unit = BaseUnit.GRAM)), "Kein Verzehr"))
        val before = dao.operationCount()
        val report = reader.read()
        assertEquals(report, store.dataQuality().first())
        assertEquals(1, report.products); assertEquals(2, report.knownDays)
        assertTrue(report.issues.none { it.category == DataQualityCategory.PRODUCT })
        assertTrue(report.issues.single { it.id == "day:2026-10-29" }.details.any { it.contains("Nährwertlücken") })
        assertTrue(report.issues.any { it.id == "day:2026-10-30" })
        assertTrue(report.issues.none { it.argument == "2026-10-28" })
        assertEquals(1, report.issues.count { it.category == DataQualityCategory.QUANTITY })
        assertEquals(old, store.dayNutrition("2026-10-29").meals.single().event.productVersionId)
        assertEquals(before, dao.operationCount()); assertTrue(dao.planningBatches().isEmpty())
        assertTrue(db.healthDao().history("com.sec.android.app.shealth").isEmpty())
    }

    @Test fun actualMatcherControlsMerchantWildcardBarcodeEquivalenceVersionsAndRetiredAliases() = runBlocking {
        val a = store.rememberProduct("a", complete("A")); val b = store.rememberProduct("b", complete("B"))
        fun input(v: String, text: String, merchant: String?, kind: AliasKind = AliasKind.RECEIPT) =
            AliasInput(v, text, merchant, kind, reason = "Synthetisch geprüft")
        store.saveAlias("global", input(a, " ＴＯＦＵ  ", null), confirmed = true)
        val shop = store.saveAlias("shop", input(b, "tofu", "Markt A"), confirmed = true)
        store.saveAlias("other-shop", input(b, "TOFU", "Markt B"), confirmed = true)
        store.saveAlias("upc", input(a, "036000291452", null, AliasKind.BARCODE), confirmed = true)
        val eanInput = input(b, "0036000291452", null, AliasKind.BARCODE)
        val ean = store.saveAlias("ean", eanInput, confirmed = true)
        val before = dao.operationCount()
        val issues = reader.read().issues.filter { it.category == DataQualityCategory.ALIAS }
        assertEquals(3, issues.size) // two actual merchant scopes, one canonical barcode
        assertTrue(issues.all { it.details.first().startsWith("Mehrere Artikelversionen") })
        assertEquals(before, dao.operationCount())
        store.saveAlias("retire-shop", input(b, "tofu", "Markt A"), shop, true, false)
        store.saveAlias("retire-ean", eanInput, ean, true, false)
        assertEquals(1, reader.read().issues.count { it.category == DataQualityCategory.ALIAS })
        val identity = dao.version(a)!!.productId
        store.rememberProduct("new-a", complete("A neu"), identity, "Neue Fassung")
        assertTrue(reader.read().issues.filter { it.category == DataQualityCategory.ALIAS }
            .any { it.details.first().startsWith("Artikelversion oder") })
        assertEquals(7, dao.aliases().size) // originals and both retirement decisions retained
    }

    @Test fun partialReceiptsOpenScansAndOnlyActivePositiveBatchesAreReportedWithoutInventedBooking() = runBlocking {
        val v = store.rememberProduct("product", complete("Testware"))
        val location = store.addLocation("location", "Testlager", true)
        val batch = store.receive("receive", BatchInput(v, location, 100_000, bestBefore = "2026-10-28"), true)
        val spent = store.receive("spent", BatchInput(v, location, 100_000), true)
        store.waste("waste", WasteInput(spent, 100_000, "2026-10-30", "Synthetisch verbraucht"), true)
        store.savePurchase("partial", PurchaseInput(null, null, null,
            listOf(PurchaseLineInput(originalText = "Pfand ungeklärt", totalCents = 0)), "Teilwissen ausdrücklich bestätigt"), true)
        store.savePurchase("zero", PurchaseInput("Testmarkt", "2026-10-30", 0,
            listOf(PurchaseLineInput("Ware", "Ware", v, 100_000, BaseUnit.GRAM, 0)), "Bekannter Nullpreis"), true)
        val scan = store.saveReceiptScan("scan", "TESTMARKT\n30.10.2026\nWARE 1,00\nSUMME 1,00", "MANUAL_TEXT")
        val before = dao.operationCount()
        val report = reader.read()
        assertEquals(1, report.activeBatches)
        val storage = report.issues.single { it.category == DataQualityCategory.STORAGE }
        assertTrue(storage.reference.startsWith(batch)); assertTrue(storage.details.any { it.startsWith("MHD 2026-10-28") })
        assertEquals(3, report.issues.count { it.category == DataQualityCategory.RECEIPT })
        assertEquals(DataQualityTarget.SCAN, report.issues.single { it.id == "scan:$scan" }.target)
        assertTrue(report.issues.none { it.reference.contains(dao.operation("zero")!!.resultId) })
        assertEquals(before, dao.operationCount()); assertEquals(2, dao.batchCount())
        store.discardReceiptScan("discard", scan, "Kein Kauf", true)
        assertTrue(reader.read().issues.none { it.id == "scan:$scan" })
        assertEquals(0, dao.activeConsumptionCount())
    }
}
