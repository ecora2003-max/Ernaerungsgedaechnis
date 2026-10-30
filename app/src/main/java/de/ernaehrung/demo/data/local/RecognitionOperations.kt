package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.recognition.AliasText
import de.ernaehrung.demo.data.recognition.Gtin
import de.ernaehrung.demo.data.recognition.ReceiptParser

internal class RecognitionOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    suspend fun alias(key: String, input: AliasInput, previousId: String?, confirmed: Boolean, active: Boolean = true): String = db.withTransaction {
        require(confirmed && input.reason.isNotBlank()) { "Aliasentscheidung mit Grund ausdrücklich bestätigen." }
        require(input.text.isNotBlank() && input.text.length <= 300 && (input.merchant?.length ?: 0) <= 200) { "Alias-/Händlertext fehlt oder ist zu lang." }
        input.packageAmount?.let(Amounts::positive)
        val barcode = if (input.kind == AliasKind.BARCODE) Gtin.parse(input.text).canonical else null
        require(input.kind != AliasKind.BARCODE || input.merchant.isNullOrBlank()) { "Produktbarcodes werden ohne Händlerbindung zugeordnet." }
        val hash = WriteRules.fingerprint("ALIAS_DECISION_1", input.versionId, input.text, input.merchant, input.kind,
            input.packageAmount, input.reason, previousId, active)
        WriteRules.replay(dao, key, hash)?.let { return@withTransaction it }
        val version = requireNotNull(dao.version(input.versionId)) { "Konkrete Artikelversion wählen." }
        if (previousId != null) {
            val previous = requireNotNull(dao.alias(previousId)) { "Vorgängeralias fehlt." }
            val decisions = dao.aliasDecisions()
            require(decisions.none { it.supersedesId == previous.id }) { "Aliasentscheidung inzwischen ersetzt. Neu prüfen." }
            val previousKind = decisions.find { it.aliasId == previous.id }?.kind ?: if (previous.barcode != null) "BARCODE" else "RECEIPT"
            require(previousKind == input.kind.name &&
                if (barcode != null) previous.barcode?.let { Gtin.parse(it).canonical } == barcode
                else AliasText.normalize(previous.text) == AliasText.normalize(input.text) && AliasText.merchant(previous.merchant) == AliasText.merchant(input.merchant)) {
                "Neue Entscheidung muss denselben Alias und Händler betreffen."
            }
        } else require(active) { "Zum Zurücknehmen einen vorhandenen Alias wählen." }
        val aliasId = WriteRules.id()
        dao.insertOperation(OperationEntity(key, "ALIAS", hash, now(), aliasId))
        dao.insertAlias(ProductAliasEntity(aliasId, version.productId, version.id, input.text, input.merchant?.trim()?.takeIf(String::isNotEmpty),
            barcode, input.packageAmount, if (active) Quality.CONFIRMED else Quality.UNKNOWN, Source.USER, now()))
        dao.insertAliasDecision(AliasDecisionEntity(aliasId, key, input.kind.name, previousId, active, input.reason))
        aliasId
    }

    suspend fun scan(key: String, rawText: String, method: String, imageHash: String?): String = db.withTransaction {
        require(rawText.isNotBlank() && rawText.length <= ReceiptParser.MAX_TEXT) { "Bontext fehlt oder ist zu lang (höchstens ${ReceiptParser.MAX_TEXT} Zeichen)." }
        require(method in setOf("CAMERA_OCR", "GALLERY_OCR", "MANUAL_TEXT")) { "Unbekannte Herkunft des Bontexts." }
        require(imageHash == null || imageHash.matches(Regex("[a-f0-9]{64}"))) { "Ungültiger Bildprüfwert." }
        require(method != "MANUAL_TEXT" || imageHash == null) { "Manueller Text besitzt keinen Bildnachweis." }
        require(method == "MANUAL_TEXT" || imageHash != null) { "Zum OCR-Bild gehört ein Prüfwert." }
        val hash = WriteRules.fingerprint("RECEIPT_SCAN_1", rawText, method, ReceiptParser.VERSION, imageHash)
        WriteRules.replay(dao, key, hash)?.let { return@withTransaction it }
        imageHash?.let { dao.scanByImageHash(it) }?.let { previous ->
            dao.insertOperation(OperationEntity(key, "RECEIPT_SCAN_REUSE", hash, now(), previous.operationId))
            return@withTransaction previous.operationId
        }
        dao.insertOperation(OperationEntity(key, "RECEIPT_SCAN", hash, now(), key))
        dao.insertReceiptScan(ReceiptScanEntity(key, rawText, method, ReceiptParser.VERSION, imageHash))
        key
    }

    suspend fun discard(key: String, scanId: String, reason: String, confirmed: Boolean): String = db.withTransaction {
        require(confirmed && reason.isNotBlank()) { "Verwerfen mit Grund bestätigen." }
        val hash = WriteRules.fingerprint("SCAN_DISCARD_1", scanId, reason)
        WriteRules.replay(dao, key, hash)?.let { return@withTransaction it }
        requireNotNull(dao.receiptScan(scanId)) { "Bonentwurf fehlt." }
        require(dao.scanResolution(scanId) == null) { "Bonentwurf wurde bereits bearbeitet." }
        dao.insertOperation(OperationEntity(key, "SCAN_DISCARD", hash, now(), scanId))
        dao.insertScanResolution(ReceiptScanResolutionEntity(scanId, key, null, reason))
        scanId
    }
}
