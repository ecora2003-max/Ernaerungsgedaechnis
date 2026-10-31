package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.integration.ProductReceiptClassifier
import de.ernaehrung.demo.data.recognition.AliasText
import de.ernaehrung.demo.data.recognition.Gtin
import de.ernaehrung.demo.data.recognition.ReceiptParser
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RecognitionReader(private val db: FoodDatabase) {
    private val dao = db.foodDao()
    fun watch() = db.invalidationTracker.createFlow("product_aliases", "alias_decisions", "receipt_scans",
        "receipt_scan_resolutions", "product_versions").map { data() }.distinctUntilChanged()

    suspend fun data(): RecognitionData = db.withTransaction {
        val decisions = dao.aliasDecisions(); val replaced = decisions.mapNotNull { it.supersedesId }.toSet()
        val aliases = dao.aliases().map { AliasHistory(it, decisions.find { d -> d.aliasId == it.id }, it.id in replaced) }
        RecognitionData(aliases, dao.receiptScans().map { ReceiptScanView(it, dao.scanResolution(it.operationId)) }, snapshot())
    }
    suspend fun snapshot(): String = WriteRules.fingerprint("RECOGNITION_CATALOG_1", dao.aliases().map { it.toString() },
        dao.aliasDecisions().map { it.toString() }, dao.allVersions().map { it.id })

    suspend fun match(text: String, merchant: String?, kind: AliasKind): AliasMatch = db.withTransaction {
        require(text.isNotBlank()) { "Aliastext fehlt." }
        val versions = dao.allVersions(); val byId = versions.associateBy { it.id }
        val latest = versions.groupBy { it.productId }.mapValues { (_, rows) -> rows.maxBy { it.revision } }
        val decisions = dao.aliasDecisions().associateBy { it.aliasId }
        val replaced = decisions.values.mapNotNull { it.supersedesId }.toSet()
        val canonical = if (kind == AliasKind.BARCODE) Gtin.parse(text).canonical else null
        val normalized = AliasText.normalize(text); val shop = AliasText.merchant(merchant)
        val matching = dao.aliases().filter { alias ->
            alias.id !in replaced && decisions[alias.id]?.active != false &&
                (decisions[alias.id]?.kind ?: if (alias.barcode != null) "BARCODE" else "RECEIPT") == kind.name &&
                if (kind == AliasKind.BARCODE) alias.barcode?.let { runCatching { Gtin.parse(it).canonical }.getOrNull() } == canonical
                else AliasText.normalize(alias.text) == normalized &&
                    (AliasText.merchant(alias.merchant) == null || AliasText.merchant(alias.merchant) == shop)
        }
        var candidates = matching.mapNotNull { alias ->
            (alias.versionId?.let(byId::get) ?: latest[alias.productId])?.let { version ->
                AliasCandidate(version, alias, alias.quality == Quality.CONFIRMED && alias.versionId != null,
                    latest[version.productId]?.id == version.id)
            }
        }.groupBy { it.version.id }.values.map { rows -> rows.sortedByDescending { it.confirmed }.first() }
        var status = when {
            candidates.size > 1 -> AliasMatchStatus.AMBIGUOUS
            candidates.singleOrNull()?.let { it.confirmed && it.currentVersion } == true -> AliasMatchStatus.CONFIRMED
            candidates.isNotEmpty() -> AliasMatchStatus.REVIEW
            else -> AliasMatchStatus.UNKNOWN
        }
        if (candidates.isEmpty()) {
            candidates = latest.values.filter { v ->
                if (kind == AliasKind.BARCODE) v.barcode?.let { runCatching { Gtin.parse(it).canonical }.getOrNull() } == canonical
                else normalized.length >= 3 && (AliasText.normalize(v.name).contains(normalized) || normalized.contains(AliasText.normalize(v.name)))
            }.sortedBy { it.id }.take(10).map { AliasCandidate(it, null, false, true) }
            if (candidates.isNotEmpty()) status = AliasMatchStatus.SUGGESTED
        }
        AliasMatch(status, candidates.sortedBy { it.version.id }, WriteRules.fingerprint("ALIAS_MATCH_1", kind, normalized, shop, snapshot()))
    }

    suspend fun review(scanId: String): ReceiptReview = db.withTransaction {
        val scan = requireNotNull(dao.receiptScan(scanId)) { "Bonentwurf fehlt." }
        require(dao.scanResolution(scanId) == null) { "Bonentwurf wurde bereits übernommen oder verworfen." }
        val parsed = ReceiptParser.parse(scan.rawText)
        // Added module output is advisory only. A malformed/long OCR line must not make the
        // existing receipt review unavailable; neither a classifier nor a match confirms payment.
        val proposals = parsed.lines.map { line ->
            runCatching { ProductReceiptClassifier.propose(line.originalText) }.getOrElse {
                ProductReceiptClassifier.Proposal(line.originalText, ProductReceiptClassifier.Kind.UNKNOWN,
                    null, listOf("receipt_line_unclassifiable"), false)
            }
        }
        ReceiptReview(ReceiptScanView(scan, null), parsed,
            parsed.lines.map { match(it.name, parsed.merchant, AliasKind.RECEIPT) }, snapshot(), proposals)
    }
}
