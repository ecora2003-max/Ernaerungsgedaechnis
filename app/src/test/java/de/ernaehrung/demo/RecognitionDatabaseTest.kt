package de.ernaehrung.demo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.recognition.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionDatabaseTest {
    private lateinit var db: FoodDatabase
    private lateinit var store: FoodStore
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = db.foodDao()
    private val clock = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private val day = "2026-10-30"
    private val code = "3017620422003"
    private val raw = "TESTMARKT\n30.10.2026\nBIO TOFU 200 g 1,99 A\nARTIKEL UNBEKANNT 2,98 B\nSUMME 4,97\nBAR 10,00\nRUECKGELD 5,03"
    private val paid = PurchaseInput("TESTMARKT", day, 497, listOf(PurchaseLineInput("BIO TOFU 200 g 1,99 A", "BIO TOFU", totalCents = 199),
        PurchaseLineInput("ARTIKEL UNBEKANNT 2,98 B", "ARTIKEL UNBEKANNT", totalCents = 298)), "Bon geprüft und bezahlt")
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build(); store = FoodStore(db) { clock } }
    @After fun close() { db.close() }
    private suspend fun product(key: String = "product") = store.rememberProduct(key, ProductInput("Testtofu"))
    private fun alias(v: String, text: String = "BIO TOFU 200 g", merchant: String? = "TESTMARKT", kind: AliasKind = AliasKind.RECEIPT) =
        AliasInput(v, text, merchant, kind, reason = "Variante ausdrücklich geprüft")
    private suspend fun rejected(block: suspend () -> Unit) { try { block(); fail("Expected rejected transaction") } catch (_: IllegalArgumentException) { } }
    private suspend fun scan(key: String = "scan") = store.saveReceiptScan(key, raw, "MANUAL_TEXT")
    private suspend fun accept(key: String, id: String = "scan", input: PurchaseInput = paid, distinct: Boolean = false) =
        store.acceptReceiptScan(key, id, input, store.reviewReceiptScan(id).snapshot, true, distinct)
    private fun reviewed(p: ProductInput = ProductInput("Testtofu OFF", source = Source.OPEN_FOOD_FACTS, quality = Quality.SOURCED,
        metadata = ProductMetadataInput(code, "Test", "Testzutaten", "Testallergen"))) =
        RecognizedProductInput(p, null, null, true, true, "Etikett/Variante und Basis geprüft")

    @Test fun confirmedMerchantAliasIsReusedOnlyForItsExactVariantAndDoesNotCreateStock() = runBlocking {
        val v = product(); val input = alias(v)
        val id = store.saveAlias("alias", input, confirmed = true)
        assertEquals(id, store.saveAlias("alias", input, confirmed = true))
        val match = store.matchAlias("  BIO   TOFU ２００ g ", "testmarkt", AliasKind.RECEIPT)
        assertEquals(AliasMatchStatus.CONFIRMED, match.status); assertEquals(v, match.reusableVersionId)
        assertNull(store.matchAlias(input.text, "Anderer Markt", AliasKind.RECEIPT).reusableVersionId)
        assertNull(store.matchAlias(input.text, null, AliasKind.RECEIPT).reusableVersionId)
        assertNull(store.matchAlias("BIO TOFU 400 g", "TESTMARKT", AliasKind.RECEIPT).reusableVersionId)
        assertEquals(input.text, dao.alias(id)!!.text); assertTrue(dao.planningBatches().isEmpty()); assertTrue(dao.purchases().isEmpty())
        rejected { store.saveAlias("not-confirmed", input, confirmed = false) }
        assertNull(dao.operation("not-confirmed"))
    }

    @Test fun equivalentUpcAndEanAreOneBarcodeButAmbiguousConfirmedProductsRequireReview() = runBlocking {
        val a = product("a"); val b = product("b")
        store.saveAlias("barcode-a", alias(a, "036000291452", null, AliasKind.BARCODE), confirmed = true)
        assertEquals(a, store.matchAlias("0036000291452", null, AliasKind.BARCODE).reusableVersionId)
        store.saveAlias("barcode-b", alias(b, "0036000291452", null, AliasKind.BARCODE), confirmed = true)
        val match = store.matchAlias("036000291452", null, AliasKind.BARCODE)
        assertEquals(AliasMatchStatus.AMBIGUOUS, match.status); assertEquals(2, match.candidates.size); assertNull(match.reusableVersionId)
        rejected { store.saveAlias("invalid-code", alias(a, "3017620422004", null, AliasKind.BARCODE), confirmed = true) }
        assertNull(dao.operation("invalid-code"))
    }

    @Test fun oldVersionAliasAndUnconfirmedLegacyAliasNeverSilentlyChooseNewNutritionVersion() = runBlocking {
        val old = product(); val identity = dao.version(old)!!.productId
        store.saveAlias("alias", alias(old), confirmed = true)
        val fresh = store.rememberProduct("new", ProductInput("Testtofu neu", proteinGrams = 20.0), identity, "Neue Rezeptur")
        val match = store.matchAlias("BIO TOFU 200 g", "TESTMARKT", AliasKind.RECEIPT)
        assertEquals(AliasMatchStatus.REVIEW, match.status); assertNull(match.reusableVersionId); assertEquals(old, match.candidates.single().version.id)
        dao.insertAlias(ProductAliasEntity("legacy", identity, null, "LEGACY TOFU", null, null, null, Quality.CONFIRMED, Source.USER, clock))
        assertEquals(AliasMatchStatus.REVIEW, store.matchAlias("LEGACY TOFU", null, AliasKind.RECEIPT).status)
        assertNull(store.matchAlias("LEGACY TOFU", null, AliasKind.RECEIPT).reusableVersionId)
        assertEquals(fresh, dao.latestVersion(identity)!!.id)
    }

    @Test fun aliasCorrectionAndRetirementKeepHistoryAndOldRetriesCannotReactivateIt() = runBlocking {
        val v = product(); val firstInput = alias(v); val first = store.saveAlias("first", firstInput, confirmed = true)
        val retiredInput = firstInput.copy(reason = "Falsche Zuordnung zurückgenommen")
        val retired = store.saveAlias("retire", retiredInput, first, true, false)
        assertNull(store.matchAlias(firstInput.text, "TESTMARKT", AliasKind.RECEIPT).reusableVersionId)
        assertEquals(first, store.saveAlias("first", firstInput, confirmed = true))
        assertNull(store.matchAlias(firstInput.text, "TESTMARKT", AliasKind.RECEIPT).reusableVersionId)
        rejected { store.saveAlias("stale", firstInput, first, true) }
        val second = store.saveAlias("restore", firstInput.copy(reason = "Nach Prüfung erneut bestätigt"), retired, true)
        assertEquals(v, store.matchAlias(firstInput.text, "TESTMARKT", AliasKind.RECEIPT).reusableVersionId)
        assertEquals(3, dao.aliases().size); assertEquals(second, store.recognitionData().aliases.single { !it.replaced }.alias.id)
        assertEquals(retired, store.saveAlias("retire", retiredInput, first, true, false))
        assertEquals(v, store.matchAlias(firstInput.text, "TESTMARKT", AliasKind.RECEIPT).reusableVersionId)
    }

    @Test fun recognizedProductAndBarcodeAreOneAtomicReviewPreserveMetadataAndNeverBuyOrEat() = runBlocking {
        val input = reviewed(); val version = store.rememberRecognizedProduct("review", input, true)
        val p = dao.version(version)!!
        assertEquals(code, p.barcode); assertEquals("Testzutaten", p.ingredients); assertEquals("Testallergen", p.allergens)
        assertEquals(Source.OPEN_FOOD_FACTS, p.source); assertEquals(Quality.SOURCED, p.quality)
        assertEquals(version, store.matchAlias(code, null, AliasKind.BARCODE).reusableVersionId)
        assertEquals(version, store.rememberRecognizedProduct("review", input, true))
        assertEquals(1, dao.allVersions().size); assertEquals(1, dao.aliases().size); assertTrue(dao.receipts().isEmpty())
        assertEquals(0, dao.consumptionCount()); assertTrue(dao.planningBatches().isEmpty())
        rejected { store.rememberRecognizedProduct("unchecked", input.copy(basisConfirmed = false), true) }
        rejected { store.rememberRecognizedProduct("not-confirmed", input, false) }
        assertNull(dao.operation("unchecked")); assertNull(dao.operation("not-confirmed"))
    }

    @Test fun staleProductReviewCannotReplaceNewerVersionAndMetadataChangesPreserveHistoricalMeals() = runBlocking {
        val original = reviewed(ProductInput("Testtofu", proteinGrams = 10.0, metadata = ProductMetadataInput(code, "Test", "Altzutaten", "Altallergene")))
        val v1 = store.rememberRecognizedProduct("original", original, true); val identity = dao.version(v1)!!.productId
        store.consume("eat", ConsumptionInput(v1, day, "Testmahlzeit", 100_000), true)
        val stale = original.copy(productId = identity, expectedVersionId = v1, product = original.product.copy(proteinGrams = 30.0))
        val v2 = store.rememberProduct("other-edit", ProductInput.from(dao.version(v1)!!).copy(proteinGrams = 20.0), identity, "Zwischenzeitliche Änderung")
        rejected { store.rememberRecognizedProduct("stale-review", stale, true) }; assertNull(dao.operation("stale-review"))
        val v3 = store.rememberRecognizedProduct("fresh-review", stale.copy(expectedVersionId = v2,
            product = stale.product.copy(metadata = ProductMetadataInput(code, null, "Neuzutaten", null))), true)
        assertEquals("Altzutaten", dao.version(v1)!!.ingredients); assertEquals("Neuzutaten", dao.version(v3)!!.ingredients)
        assertNull(dao.version(v3)!!.allergens); assertEquals("Altallergene", dao.version(v2)!!.allergens)
        assertEquals(0, java.math.BigDecimal("10").compareTo(store.dayNutrition(day).totals[Nutrient.PROTEIN].knownAmount))
        assertEquals(2, dao.aliases().size) // Earlier mapping survives as historical evidence, so ambiguity remains visible.
        assertNull(store.matchAlias(code, null, AliasKind.BARCODE).reusableVersionId)
    }

    @Test fun scanPersistsRawEvidenceAndOnlyUnambiguousAliasProposalsWithoutPaymentOrStock() = runBlocking {
        val v = product(); store.saveAlias("alias", alias(v), confirmed = true)
        val id = scan(); assertEquals(id, scan())
        val report = store.reviewReceiptScan(id)
        assertEquals(raw, report.draft.scan.rawText); assertEquals(ReceiptParser.VERSION, report.draft.scan.parserVersion)
        assertEquals(497L, report.parsed.totalCents); assertEquals(v, report.matches.first().reusableVersionId)
        assertNull(report.matches.last().reusableVersionId); assertTrue(dao.receipts().isEmpty()); assertTrue(dao.purchases().isEmpty())
        assertTrue(dao.planningBatches().isEmpty()); assertEquals(0, dao.consumptionCount())
        assertEquals("200 g", report.parsed.lines.first().quantityText)
        rejected { store.saveReceiptScan("bad-method", raw, "CLOUD_OCR") }
        rejected { store.saveReceiptScan("huge", "x".repeat(ReceiptParser.MAX_TEXT + 1), "MANUAL_TEXT") }
        rejected { store.saveReceiptScan("no-hash", raw, "CAMERA_OCR") }
        assertNull(dao.operation("bad-method")); assertNull(dao.operation("huge")); assertNull(dao.operation("no-hash"))
    }

    @Test fun samePhotoIsOneDraftAcrossDifferentKeysAndConcurrentRetriesIncludingAfterAcceptance() = runBlocking {
        val imageHash = "a".repeat(64)
        val ids = coroutineScope { (1..6).map { n -> async(Dispatchers.Default) { store.saveReceiptScan("photo-$n", raw, "CAMERA_OCR", imageHash) } }.awaitAll() }
        assertEquals(1, ids.distinct().size); assertEquals(1, dao.receiptScans().size)
        val id = ids.first(); val receipt = accept("paid", id)
        assertEquals(id, store.saveReceiptScan("reselected-image", "Anders sortierte OCR zum gleichen Bild", "GALLERY_OCR", imageHash))
        rejected { accept("second-payment", id) }
        assertEquals(receipt, dao.scanResolution(id)!!.receiptId); assertEquals(1, dao.purchases().size)
        assertNull(dao.operation("second-payment"))
    }

    @Test fun reviewedScanAcceptsKnownPaymentWithUnresolvedProductsExactlyOnceAndLeavesStockSeparate() = runBlocking {
        scan(); val before = store.reviewReceiptScan("scan"); val receipt = accept("accept")
        assertEquals(receipt, store.acceptReceiptScan("accept", "scan", paid, before.snapshot, true))
        assertEquals(497L, dao.receipt(receipt)!!.totalCents); assertEquals(2, dao.receiptLines(receipt).size)
        assertTrue(dao.receiptLines(receipt).all { it.amount == null && it.versionId == null && it.stockOperationId == null })
        assertEquals(raw, dao.receiptScan("scan")!!.rawText); assertEquals(receipt, dao.scanResolution("scan")!!.receiptId)
        assertTrue(dao.planningBatches().isEmpty()); assertEquals(0, dao.consumptionCount())
        assertEquals(497L, store.expenseReport(ExpenseScope("2026-10-01", day)).knownSubtotalCents)
        rejected { store.acceptReceiptScan("accept", "scan", paid.copy(paidCents = 498), before.snapshot, true) }
        assertEquals(1, dao.purchases().size)
    }

    @Test fun catalogChangeAndSameClockAliasAbaInvalidateOldScanReviewBeforeAnyPaymentIsWritten() = runBlocking {
        val v = product(); val first = store.saveAlias("a", alias(v), confirmed = true); scan()
        val old = store.reviewReceiptScan("scan")
        val withdrawn = store.saveAlias("retire", alias(v, text = "BIO TOFU 200 g").copy(reason = "Zurücknehmen"), first, true, false)
        store.saveAlias("restore", alias(v).copy(reason = "Erneut geprüft"), withdrawn, true)
        assertNotEquals(old.snapshot, store.reviewReceiptScan("scan").snapshot)
        rejected { store.acceptReceiptScan("stale", "scan", paid, old.snapshot, true) }
        assertNull(dao.operation("stale")); assertTrue(dao.purchases().isEmpty()); assertNull(dao.scanResolution("scan"))
        val freshSnapshot = store.reviewReceiptScan("scan").snapshot
        store.acceptReceiptScan("fresh", "scan", paid, freshSnapshot, true); assertEquals(1, dao.purchases().size)
        val newVersion = store.rememberProduct("new-product", ProductInput("Anderes Produkt"))
        assertNotNull(newVersion)
        assertEquals(dao.purchase("fresh")!!.receiptId, store.acceptReceiptScan("fresh", "scan", paid, freshSnapshot, true))
    }

    @Test fun duplicateMerchantDateAmountRequiresExplicitDistinctPurchaseAndCannotRaceThroughGuard() = runBlocking {
        scan("a"); scan("b")
        val results = coroutineScope { listOf("a", "b").map { id -> async(Dispatchers.Default) { runCatching { accept("accept-$id", id) }.isSuccess } }.awaitAll() }
        assertEquals(1, results.count { it }); assertEquals(1, dao.purchases().size)
        val unresolved = dao.receiptScans().single { dao.scanResolution(it.operationId) == null }.operationId
        val receipt = accept("distinct", unresolved, distinct = true)
        assertEquals(2, dao.purchases().size); assertEquals(994L, store.expenseReport(ExpenseScope("2026-10-01", day)).knownSubtotalCents)
        assertTrue(dao.scanResolution(unresolved)!!.reason.contains("Eigenständiger weiterer Einkauf")); assertNotNull(dao.receipt(receipt))
    }

    @Test fun simultaneousAcceptAndDiscardProduceOneResolutionAndOriginalTextSurvives() = runBlocking {
        scan(); val snapshot = store.reviewReceiptScan("scan").snapshot
        val outcomes = coroutineScope { listOf(async(Dispatchers.Default) { runCatching { store.acceptReceiptScan("accept", "scan", paid, snapshot, true) }.isSuccess },
            async(Dispatchers.Default) { runCatching { store.discardReceiptScan("discard", "scan", "Kein eigener Einkauf", true) }.isSuccess }).awaitAll() }
        assertEquals(1, outcomes.count { it }); assertNotNull(dao.scanResolution("scan")); assertEquals(raw, dao.receiptScan("scan")!!.rawText)
        assertEquals(if (dao.scanResolution("scan")!!.receiptId == null) 0 else 1, dao.purchases().size)
        assertTrue(dao.planningBatches().isEmpty())
    }

    @Test fun discardedScanCannotBePaidAndOldRetryCannotReopenItOrChangeExpenseCompleteness() = runBlocking {
        val scope = ExpenseScope("2026-10-01", day)
        store.setExpenseStatus("complete", ExpenseStatusInput(scope, true, store.expenseReport(scope).snapshot, "Keine Ausgaben nach Prüfung"), true)
        scan(); val old = store.reviewReceiptScan("scan")
        store.discardReceiptScan("discard", "scan", "Fremder Testbeleg", true)
        assertEquals("scan", scan()); assertEquals("scan", store.discardReceiptScan("discard", "scan", "Fremder Testbeleg", true))
        rejected { store.acceptReceiptScan("wrong", "scan", paid, old.snapshot, true) }
        assertNull(dao.operation("wrong")); assertEquals(0L, store.expenseReport(scope).totalCents)
        assertTrue(dao.purchases().isEmpty()); assertNotNull(dao.scanResolution("scan"))
    }

    @Test fun sqliteAbortAtResolutionRollsBackPaymentReceiptPositionsAndKeyThenSameDraftCanSucceed() = runBlocking {
        scan(); val sql = db.openHelper.writableDatabase
        val before = dao.operationCount()
        sql.execSQL("CREATE TRIGGER abort_resolution BEFORE INSERT ON receipt_scan_resolutions BEGIN SELECT RAISE(ABORT,'test rollback'); END")
        try { accept("accept"); fail("Expected SQL abort") } catch (_: android.database.sqlite.SQLiteException) { }
        finally { sql.execSQL("DROP TRIGGER abort_resolution") }
        assertEquals(before, dao.operationCount()); assertTrue(dao.receipts().isEmpty()); assertTrue(dao.purchases().isEmpty())
        assertNull(dao.scanResolution("scan")); assertEquals(raw, dao.receiptScan("scan")!!.rawText)
        accept("accept"); assertEquals(1, dao.purchases().size)
    }

    @Test fun sqliteAliasFailureRollsBackEntireRecognizedProductIncludingIdentityVersionAndNestedKeys() = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER abort_alias BEFORE INSERT ON alias_decisions BEGIN SELECT RAISE(ABORT,'test rollback'); END")
        try { store.rememberRecognizedProduct("review", reviewed(), true); fail("Expected SQL abort") } catch (_: android.database.sqlite.SQLiteException) { }
        finally { sql.execSQL("DROP TRIGGER abort_alias") }
        assertEquals(0, dao.operationCount()); assertTrue(dao.allVersions().isEmpty()); assertTrue(dao.aliases().isEmpty())
        sql.query("SELECT COUNT(*) FROM products").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        store.rememberRecognizedProduct("review", reviewed(), true); assertEquals(1, dao.allVersions().size)
    }

    @Test fun fileReopenKeepsAliasAndResolvedScanAndGoldenRetryWithNoSecondExpense() = runBlocking {
        db.close(); val name = "recognition-${UUID.randomUUID()}.db"
        try {
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            val v = product(); store.saveAlias("alias", alias(v), confirmed = true); scan()
            val snapshot = store.reviewReceiptScan("scan").snapshot; val receipt = accept("accept")
            val before = store.recognitionData(); db.close()
            db = Room.databaseBuilder(context, FoodDatabase::class.java, name).build(); store = FoodStore(db) { clock }
            assertEquals(before, store.recognitionData()); assertEquals(v, store.matchAlias("BIO TOFU 200 g", "TESTMARKT", AliasKind.RECEIPT).reusableVersionId)
            assertEquals(receipt, store.acceptReceiptScan("accept", "scan", paid, snapshot, true)); assertEquals(1, dao.purchases().size)
            assertEquals(497L, store.expenseReport(ExpenseScope("2026-10-01", day)).knownSubtotalCents)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun originalReceiptClassifierJoinsReadOnlyReviewWithoutBookingNonFoodAsFood() = runBlocking {
        val rawWithMixedLines = "TESTMARKT\\n30.10.2026\\nBIO TOFU 200 g 1,99 A\\nTASCHE 0,39 A\\nSUMME 2,38\\nPFAND 0,25\\nRABATT -0,50"
        val id = store.saveReceiptScan("classifier-scan", rawWithMixedLines, "MANUAL_TEXT")
        val review = store.reviewReceiptScan(id)
        assertEquals(listOf("BIO TOFU 200 g 1,99 A", "TASCHE 0,39 A"), review.parsed.lines.map { it.originalText })
        assertEquals(review.parsed.lines.size, review.lineClassifications.size)
        assertEquals(de.ernaehrung.demo.data.integration.ProductReceiptClassifier.Kind.UNKNOWN, review.lineClassifications[0].kind)
        assertEquals(de.ernaehrung.demo.data.integration.ProductReceiptClassifier.Kind.NON_FOOD, review.lineClassifications[1].kind)
        assertEquals(39L, review.lineClassifications[1].amountCents)
        assertTrue(review.lineClassifications.all { !it.confirmed && "unconfirmed_ocr" in it.warnings })
        assertTrue(dao.receipts().isEmpty()); assertTrue(dao.purchases().isEmpty())
        assertEquals(0, dao.consumptionCount()); assertTrue(dao.planningBatches().isEmpty())
        assertEquals(id, store.saveReceiptScan("classifier-scan", rawWithMixedLines, "MANUAL_TEXT"))
    }

    @Test fun excessivelyLongReceiptLineDoesNotBreakExistingEditableReviewOrCausePayment() = runBlocking {
        val longName = "TOFU " + "X".repeat(4_100)
        val id = store.saveReceiptScan("long-line", "TESTMARKT\\n30.10.2026\\n$longName 1,99 A\\nSUMME 1,99",
            "MANUAL_TEXT")
        val review = store.reviewReceiptScan(id)
        assertEquals(1, review.parsed.lines.size)
        assertEquals(longName + " 1,99 A", review.parsed.lines.single().originalText)
        assertEquals(de.ernaehrung.demo.data.integration.ProductReceiptClassifier.Kind.UNKNOWN, review.lineClassifications.single().kind)
        assertEquals(null, review.lineClassifications.single().amountCents)
        assertTrue("receipt_line_unclassifiable" in review.lineClassifications.single().warnings)
        assertNull(dao.operation("accept-unreviewed"))
        assertTrue(dao.receipts().isEmpty()); assertTrue(dao.purchases().isEmpty())
    }

}