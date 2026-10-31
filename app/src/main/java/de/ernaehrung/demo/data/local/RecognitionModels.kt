package de.ernaehrung.demo.data.local

enum class AliasKind { BARCODE, RECEIPT }
data class AliasInput(val versionId: String, val text: String, val merchant: String?, val kind: AliasKind,
    val packageAmount: Long? = null, val reason: String)
enum class AliasMatchStatus { CONFIRMED, REVIEW, AMBIGUOUS, SUGGESTED, UNKNOWN }
data class AliasCandidate(val version: ProductVersionEntity, val alias: ProductAliasEntity?, val confirmed: Boolean, val currentVersion: Boolean)
data class AliasMatch(val status: AliasMatchStatus, val candidates: List<AliasCandidate>, val snapshot: String) {
    val reusableVersionId: String? get() = candidates.singleOrNull()?.version?.id?.takeIf { status == AliasMatchStatus.CONFIRMED }
}
data class AliasHistory(val alias: ProductAliasEntity, val decision: AliasDecisionEntity?, val replaced: Boolean)
data class ReceiptScanView(val scan: ReceiptScanEntity, val resolution: ReceiptScanResolutionEntity?)
data class RecognitionData(val aliases: List<AliasHistory>, val scans: List<ReceiptScanView>, val snapshot: String)
data class ReceiptReview(val draft: ReceiptScanView, val parsed: de.ernaehrung.demo.data.recognition.ReceiptSuggestion,
    val matches: List<AliasMatch>, val snapshot: String,
    val lineClassifications: List<de.ernaehrung.demo.data.integration.ProductReceiptClassifier.Proposal> = emptyList())
data class ProductMetadataInput(val barcode: String?, val category: String?, val ingredients: String?, val allergens: String?)
data class RecognizedProductInput(val product: ProductInput, val productId: String?, val expectedVersionId: String?,
    val basisConfirmed: Boolean, val barcodeConfirmed: Boolean, val reason: String)
