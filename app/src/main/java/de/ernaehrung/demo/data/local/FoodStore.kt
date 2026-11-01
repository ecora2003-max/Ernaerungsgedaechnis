package de.ernaehrung.demo.data.local

import androidx.room.withTransaction

data class ProductInput(
    val name: String, val brand: String? = null, val unit: BaseUnit = BaseUnit.GRAM,
    val packageAmount: Long? = null, val nutritionBasis: Long = 100_000,
    val energyKcal: Double? = null, val proteinGrams: Double? = null, val fatGrams: Double? = null,
    val carbsGrams: Double? = null, val source: Source = Source.USER,
    val quality: Quality = Quality.UNKNOWN, val sourceDetail: String? = null,
    val fiberGrams: Double? = null, val sugarGrams: Double? = null, val saturatedFatGrams: Double? = null,
    val saltGrams: Double? = null, val calciumMg: Double? = null, val ironMg: Double? = null,
    val magnesiumMg: Double? = null, val iodineMicrograms: Double? = null, val vitaminB12Micrograms: Double? = null,
    val vitaminDMicrograms: Double? = null, val metadata: ProductMetadataInput? = null
) {
    internal fun extras() = listOf(fiberGrams, sugarGrams, saturatedFatGrams, saltGrams, calciumMg, ironMg,
        magnesiumMg, iodineMicrograms, vitaminB12Micrograms, vitaminDMicrograms)
    fun withNutrients(values: Map<Nutrient, Double?>) = copy(energyKcal = values[Nutrient.ENERGY], proteinGrams = values[Nutrient.PROTEIN],
        fatGrams = values[Nutrient.FAT], carbsGrams = values[Nutrient.CARBS], fiberGrams = values[Nutrient.FIBRE],
        sugarGrams = values[Nutrient.SUGAR], saturatedFatGrams = values[Nutrient.SATURATED], saltGrams = values[Nutrient.SALT],
        calciumMg = values[Nutrient.CALCIUM], ironMg = values[Nutrient.IRON], magnesiumMg = values[Nutrient.MAGNESIUM],
        iodineMicrograms = values[Nutrient.IODINE], vitaminB12Micrograms = values[Nutrient.B12], vitaminDMicrograms = values[Nutrient.VITAMIN_D])
    companion object {
        fun from(version: ProductVersionEntity) = ProductInput(version.name, version.brand, version.baseUnit,
            version.packageAmount, version.nutritionBasis, source = version.source, quality = version.quality, sourceDetail = version.sourceDetail)
            .withNutrients(Nutrient.entries.associateWith(version::nutrient))
    }
}
data class BatchInput(val versionId: String, val locationId: String, val amount: Long,
    val acquiredOn: String? = null, val bestBefore: String? = null, val useBy: String? = null,
    val release: ReleaseStatus = ReleaseStatus.UNCHECKED, val safetyNote: String? = null)
data class Allocation(val batchId: String, val amount: Long)
data class ConsumptionInput(val versionId: String, val date: String, val meal: String, val amount: Long?,
    val allocations: List<Allocation> = emptyList(), val occurredAt: Long? = null,
    val quality: Quality = Quality.UNKNOWN, val note: String? = null)

/** Single application write boundary. Exceptions roll back every effect, including operation IDs. */
class FoodStore(private val db: FoodDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = db.foodDao()
    private val stock = StockOperations(db, now)
    private val kitchen = KitchenOperations(db, now)
    private val nutrition = NutritionReader(db)
    private val planning = PlanningOperations(db, now)
    private val demand = PlanningReader(db, now)
    private val shoppingReader = ShoppingReader(db, now)
    private val shoppingWriter = ShoppingOperations(db, now)
    private val purchaseWriter = PurchaseOperations(db, now)
    private val paidPriceReader = PaidReceiptPriceReader(db)
    private val historicalPaidComparisonReader = HistoricalPaidComparisonReader(db)
    private val expenseReader = ExpenseReader(db, now)
    private val recognitionReader = RecognitionReader(db)
    private val recognitionWriter = RecognitionOperations(db, now)
    /** Read-only, transactionally consistent host snapshot; never a booking. */
    internal suspend fun readRecipeRoomStockSnapshot():
        de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot? =
        db.withTransaction {
            RecipeRoomStockSnapshotReader(dao).read(java.time.Instant.ofEpochMilli(now()))
        }

    /** Actual Room planning/checklist context, never an external offer or booking. */
    internal suspend fun readRecipeRoomShoppingSnapshot(scope: ShoppingScope):
        de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot? =
        db.withTransaction {
            RecipeRoomShoppingSnapshotReader(db, now)
                .read(scope, java.time.Instant.ofEpochMilli(now()))
        }


    /** Authorized host-cache offers; the caller must supply independent rights and store gates. */
    internal suspend fun readRecipeAuthorizedOffersSnapshot(
        scope: RecipeAuthorizedOffersReader.Scope,
        reader: RecipeAuthorizedOffersReader
    ): de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot? =
        db.withTransaction { reader.read(scope, java.time.Instant.ofEpochMilli(now())) }

    /**
     * Database-backed, read-only preview. Model-provided identities and allergy
     * claims cannot populate host facts; only separately confirmed, current
     * catalog versions may do so. Never calls saveRecipe/produce/consume.
     */
    internal suspend fun previewRecipeDraftV1(
        json: String,
        request: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Request,
        confirmations: Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        reviewedStates: Map<String, de.ernaehrung.demo.data.integration.RecipeDraftHostGate.State>,
        context: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Context<*, *, *>? = null,
        trustedContextSnapshotAt: java.time.OffsetDateTime? = null,
        offersScope: RecipeAuthorizedOffersReader.Scope? = null,
        offersReader: RecipeAuthorizedOffersReader? = null
    ): de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Review = db.withTransaction {
        reviewRecipeDraftV1InTransaction(json, request, confirmations, reviewedStates,
            context, trustedContextSnapshotAt, offersScope = offersScope,
            offersReader = offersReader)
    }

    /**
     * Durable, separate confirmation checkpoint for one already reviewed draft.
     * This records a host-side action and its time in the existing append-only
     * operation ledger; it is NOT proof of a human/UI gesture. No recipe, stock,
     * purchase or consumption is written by this checkpoint.
     *
     * The UI must eventually supply independently authenticated action provenance.
     */
    internal suspend fun recordReviewedRecipeDraftV1Consent(
        key: String,
        json: String,
        request: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Request,
        confirmations: Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        reviewedStates: Map<String, de.ernaehrung.demo.data.integration.RecipeDraftHostGate.State>,
        expectedPreview: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Review,
        confirmed: Boolean,
        context: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Context<*, *, *>? = null,
        trustedContextSnapshotAt: java.time.OffsetDateTime? = null
    ): String = db.withTransaction {
        require(confirmed && request.userConfirmedRecipeSave) {
            "Rezeptentwurf nur nach separater Nutzeraktion bestätigen."
        }
        requireValidRecipeDraftSaveKey(key)
        val actionAt = now()
        val current = reviewRecipeDraftV1InTransaction(json, request, confirmations,
            reviewedStates, context, trustedContextSnapshotAt,
            requireNonFutureDraft = true, trustedNow = actionAt)
        require(current.status ==
            de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
            current.input != null &&
            expectedPreview.status ==
                de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
            expectedPreview.input == current.input &&
            expectedPreview.approvalBasisDigest != null &&
            expectedPreview.approvalBasisDigest == current.approvalBasisDigest) {
            "Bestätigung bezieht sich nicht auf die aktuelle geprüfte Rezeptvorschau."
        }
        val digest = requireNotNull(current.approvalBasisDigest)
        val consentKey = recipeDraftConsentKey(key)
        val hash = recipeDraftConsentFingerprint(key, digest)
        val previous = dao.operation(consentKey)
        if (previous != null) {
            require(previous.kind == "RECIPE_DRAFT_CONSENT_V1" &&
                previous.fingerprint == hash && previous.resultId == digest) {
                "Bestätigungs-ID wurde bereits für einen anderen Rezeptentwurf verwendet."
            }
            return@withTransaction previous.resultId
        }
        dao.insertOperation(OperationEntity(consentKey, "RECIPE_DRAFT_CONSENT_V1",
            hash, actionAt, digest))
        digest
    }

    /**
     * Reopen an already recorded checkpoint using the existing append-only Room
     * ledger. This is read-only: an unconfirmed session cannot be reconstructed,
     * and a recovered checkpoint is not new evidence of a human action.
     * Fingerprint verification prevents unrelated or malformed ledger entries
     * from being interpreted as a matching recipe consent.
     */
    internal suspend fun recordedRecipeDraftV1ConsentKey(digest: String): String? {
        if (!Regex("[0-9a-f]{64}").matches(digest)) return null
        val prefix = "RECIPE_DRAFT_CONSENT_V1:"
        return dao.recipeDraftConsentsForDigest(digest).firstOrNull { operation ->
            if (!operation.id.startsWith(prefix)) false
            else {
                val key = operation.id.substring(prefix.length)
                key.isNotBlank() && key.length <= 200 &&
                    !key.startsWith(prefix) &&
                    operation.fingerprint == recipeDraftConsentFingerprint(key, digest)
            }
        }?.id?.substring(prefix.length)
    }

    private fun requireValidRecipeDraftSaveKey(key: String) {
        require(key.isNotBlank() && key.length <= 200 &&
            !key.startsWith("RECIPE_DRAFT_CONSENT_V1:")) {
            "Ungültige Rezeptvorgangs-ID."
        }
    }

    private fun recipeDraftConsentKey(key: String) = "RECIPE_DRAFT_CONSENT_V1:$key"

    private fun recipeDraftConsentFingerprint(key: String, digest: String) =
        WriteRules.fingerprint("RECIPE_DRAFT_CONSENT_V1", key, digest)

    /** Always re-read current catalog and revalidate the exact previously reviewed input
     * inside the same transaction as the new recipe write. An AI draft, stale preview,
     * changed ingredient version or absent separate confirmation can never be saved here.
     * This entry point only creates a new recipe; edits require a separate revision gate.
     */
    internal suspend fun saveReviewedRecipeDraftV1(
        key: String,
        json: String,
        request: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Request,
        confirmations: Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        reviewedStates: Map<String, de.ernaehrung.demo.data.integration.RecipeDraftHostGate.State>,
        expectedPreview: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Review,
        confirmed: Boolean,
        context: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Context<*, *, *>? = null,
        trustedContextSnapshotAt: java.time.OffsetDateTime? = null
    ): String = db.withTransaction {
        require(confirmed && request.userConfirmedRecipeSave) {
            "Rezeptübernahme separat und ausdrücklich bestätigen."
        }
        requireValidRecipeDraftSaveKey(key)
        val saveAt = now()
        val current = reviewRecipeDraftV1InTransaction(json, request, confirmations,
            reviewedStates, context, trustedContextSnapshotAt,
            requireNonFutureDraft = true, trustedNow = saveAt)
        require(current.status ==
            de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
            current.input != null &&
            expectedPreview.status ==
                de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
            expectedPreview.input == current.input &&
            expectedPreview.approvalBasisDigest != null &&
            expectedPreview.approvalBasisDigest == current.approvalBasisDigest) {
            "Rezeptentwurf, Freigabebasis oder Produktbelege verändert; aktuelle Vorschau erneut bestätigen."
        }
        // The confirmation must have been recorded by a separate host action
        // and must survive app restart. A caller-provided Boolean alone is not enough.
        val digest = requireNotNull(current.approvalBasisDigest)
        val consent = dao.operation(recipeDraftConsentKey(key))
        require(consent != null &&
            consent.kind == "RECIPE_DRAFT_CONSENT_V1" &&
            consent.resultId == digest &&
            consent.fingerprint == recipeDraftConsentFingerprint(key, digest) &&
            consent.recordedAt <= saveAt) {
            "Keine passende dauerhaft gespeicherte Rezeptbestätigung vorhanden."
        }
        // Bind the persisted operation fingerprint to the exact approved preview.
        // A second draft with the same projected recipe must not replay this key.
        kitchen.saveRecipe(key, current.input, null, "Bestätigter Rezeptentwurf v1",
            current.approvalBasisDigest)
    }

    /** Caller must supply independently verified confirmation facts, never model claims. */
    private suspend fun reviewRecipeDraftV1InTransaction(
        json: String,
        request: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Request,
        confirmations: Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        reviewedStates: Map<String, de.ernaehrung.demo.data.integration.RecipeDraftHostGate.State>,
        context: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Context<*, *, *>?,
        trustedContextSnapshotAt: java.time.OffsetDateTime?,
        requireNonFutureDraft: Boolean = false,
        trustedNow: Long = now(),
        offersScope: RecipeAuthorizedOffersReader.Scope? = null,
        offersReader: RecipeAuthorizedOffersReader? = null
    ): de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Review {
        val clock = java.time.OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(trustedNow), java.time.ZoneOffset.UTC)
        val imported = try {
            de.ernaehrung.demo.data.integration.RecipeDraftV1Import
                .read(json, reviewedStates, clock)
        } catch (_: IllegalArgumentException) {
            null
        }
        val lines = imported?.draft?.lines ?: emptyList()
        val facts = de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.resolve(
            lines, dao.allVersions(), confirmations, trustedNow)
        val review = de.ernaehrung.demo.data.integration.RecipeDraftV1Import.review(
            json, request, facts, reviewedStates, clock, context, trustedContextSnapshotAt)
        // Producer clock skew is tolerated for read-only preview only. A host
        // confirmation or recipe write must never predate the draft it approves.
        if (requireNonFutureDraft &&
            imported?.createdAt?.toInstant()?.isAfter(clock.toInstant()) == true) {
            return review.copy(
                status = de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.BLOCKED,
                blockers = review.blockers + "FUTURE_DRAFT_NOT_YET_CREATED",
                input = null, approvalBasisDigest = null
            )
        }
        // Re-read actual Room stock within this transaction, never trust caller-built
        // typed content. Keep the original timestamp only for content comparison:
        // the fresh read's revision and rows must match, and review expires in 5 min.
        // Other active sources and user-action provenance still cannot authorize save.
        if (request.flags.considerStock) {
            val binding = context?.contentBinding
            val original = context?.stock as?
                de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot
            val fresh = RecipeRoomStockSnapshotReader(dao)
                .read(java.time.Instant.ofEpochMilli(trustedNow))
            val ageSeconds = if (binding != null && fresh != null)
                runCatching {
                    java.time.Duration.between(binding.snapshotAt, fresh.capturedAt).seconds
                }.getOrNull() else null
            val valid = context?.contentBindingRequired == true &&
                binding?.stockDigest != null && original != null && fresh != null &&
                original.capturedAt == binding.snapshotAt &&
                ageSeconds != null && ageSeconds in 0L..300L &&
                original == fresh.copy(capturedAt = original.capturedAt)
            if (!valid) {
                return review.copy(
                    status = if (review.status ==
                        de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.BLOCKED)
                        review.status else
                        de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.REVIEW_REQUIRED,
                    gaps = review.gaps + "ROOM_STOCK_SNAPSHOT_STALE_OR_CHANGED",
                    input = null, approvalBasisDigest = null
                )
            }
        }
        // Shopping needs are projections of actual Room plans and checklist
        // revisions, not confirmed purchases or authorized retailer offers.
        // Recompute in this same transaction, with the original scope and
        // capture time, so an intervening plan/stock/mark change fails closed.
        if (request.flags.considerShopping) {
            val binding = context?.contentBinding
            val original = context?.shopping as?
                de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot
            val scope = original?.scopeId?.let {
                RecipeRoomShoppingSnapshotReader.scopeFromId(it)
            }
            val fresh = scope?.let {
                RecipeRoomShoppingSnapshotReader(db, now)
                    .read(it, java.time.Instant.ofEpochMilli(trustedNow))
            }
            val ageSeconds = if (binding != null && fresh != null)
                runCatching {
                    java.time.Duration.between(binding.snapshotAt, fresh.capturedAt).seconds
                }.getOrNull() else null
            val valid = context?.contentBindingRequired == true &&
                binding?.shoppingDigest != null && original != null && fresh != null &&
                original.capturedAt == binding.snapshotAt &&
                ageSeconds != null && ageSeconds in 0L..300L &&
                original == fresh.copy(capturedAt = original.capturedAt)
            if (!valid) {
                return review.copy(
                    status = if (review.status ==
                        de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.BLOCKED)
                        review.status else
                        de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.REVIEW_REQUIRED,
                    gaps = review.gaps + "ROOM_SHOPPING_SNAPSHOT_STALE_OR_CHANGED",
                    input = null, approvalBasisDigest = null
                )
            }
        }
        // An offer is not a Room price observation or a feed permission flag.
        // Revalidate the independently authorized host cache against the current
        // Room product version and rights/store registries in this transaction.
        // No configured host cache means REVIEW_REQUIRED, not empty offers.
        if (request.flags.considerOffers) {
            val binding = context?.contentBinding
            val original = context?.offers as?
                de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot
            val fresh = if (offersReader != null && offersScope != null &&
                original?.scopeId == RecipeAuthorizedOffersReader.scopeId(offersScope)) {
                offersReader.read(offersScope, java.time.Instant.ofEpochMilli(trustedNow))
            } else null
            val ageSeconds = if (binding != null && fresh != null)
                runCatching {
                    java.time.Duration.between(binding.snapshotAt, fresh.capturedAt).seconds
                }.getOrNull() else null
            val valid = context?.contentBindingRequired == true &&
                binding?.offersDigest != null && original != null && fresh != null &&
                original.capturedAt == binding.snapshotAt &&
                ageSeconds != null && ageSeconds in 0L..300L &&
                original == fresh.copy(capturedAt = original.capturedAt)
            if (!valid) {
                return review.copy(
                    status = if (review.status ==
                        de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.BLOCKED)
                        review.status else
                        de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.REVIEW_REQUIRED,
                    gaps = review.gaps + "HOST_OFFERS_SNAPSHOT_STALE_OR_UNAUTHORIZED",
                    input = null, approvalBasisDigest = null
                )
            }
        }
        // Active context is not independent user-action provenance and never
        // authorizes recipe saving, even if all Room sources are current.
        val contextRequested = request.flags.considerStock ||
            request.flags.considerShopping || request.flags.considerOffers
        return if (review.status ==
            de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE &&
            !contextRequested) {
            review.copy(approvalBasisDigest = recipeDraftApprovalBasisDigest(
                json, request, confirmations, reviewedStates, trustedContextSnapshotAt))
        } else review
    }

    /**
     * Versioned, length-delimited SHA-256 over the exact incoming UTF-8 draft and
     * all typed caller-provided approval inputs. The raw-byte digest is deliberately
     * strict (even a whitespace change requires re-review); it is NOT a durable
     * user-action attestation or canonical JSON/2020-12 schema validation.
     */
    private fun recipeDraftApprovalBasisDigest(
        json: String,
        request: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Request,
        confirmations: Map<String, de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.ConfirmedLine>,
        reviewedStates: Map<String, de.ernaehrung.demo.data.integration.RecipeDraftHostGate.State>,
        trustedContextSnapshotAt: java.time.OffsetDateTime?
    ): String = WriteRules.fingerprint(
        "RECIPE_DRAFT_APPROVAL_BASIS_V1", json,
        listOf(request.flags.considerStock, request.flags.considerShopping,
            request.flags.considerOffers, request.portions,
            request.confirmedRestrictions.sorted(), request.userConfirmedRecipeSave),
        confirmations.toSortedMap().map { (foodType, c) ->
            listOf(foodType, c.foodTypeId, c.productVersionId, c.productRevision,
                c.versionRecordedAt, c.confirmedAt, c.unit.name, c.basis?.name,
                c.state?.name, c.pieceMassMilliGrams,
                c.allergens.toSortedMap().map { (allergen, present) ->
                    listOf(allergen, present)
                })
        },
        reviewedStates.toSortedMap().map { (foodType, state) ->
            listOf(foodType, state.name)
        },
        trustedContextSnapshotAt?.toInstant()?.toString()
    )

    fun watchRecognition() = recognitionReader.watch()
    suspend fun recognitionData() = recognitionReader.data()
    suspend fun matchAlias(text: String, merchant: String?, kind: AliasKind) = recognitionReader.match(text, merchant, kind)
    suspend fun saveAlias(key: String, input: AliasInput, previousId: String? = null, confirmed: Boolean, active: Boolean = true) = recognitionWriter.alias(key, input, previousId, confirmed, active)
    suspend fun saveReceiptScan(key: String, rawText: String, method: String, imageHash: String? = null) = recognitionWriter.scan(key, rawText, method, imageHash)
    suspend fun discardReceiptScan(key: String, scanId: String, reason: String, confirmed: Boolean) = recognitionWriter.discard(key, scanId, reason, confirmed)
    suspend fun reviewReceiptScan(scanId: String) = recognitionReader.review(scanId)
    suspend fun acceptReceiptScan(key: String, scanId: String, input: PurchaseInput, expectedCatalog: String, confirmed: Boolean, distinctPurchaseConfirmed: Boolean = false) =
        purchaseWriter.save(key, input, confirmed, scanId, expectedCatalog, distinctPurchaseConfirmed)
    suspend fun rememberRecognizedProduct(key: String, input: RecognizedProductInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed && input.basisConfirmed && input.reason.isNotBlank()) { "Produktvariante, Nährwertbasis/Einheit und Speichern ausdrücklich bestätigen." }
        val p = input.product; val m = p.metadata
        val hash = fingerprint("RECOGNIZED_PRODUCT_1", listOf(p.name, p.brand, p.unit, p.packageAmount, p.nutritionBasis,
            p.energyKcal, p.proteinGrams, p.fatGrams, p.carbsGrams, p.source, p.quality, p.sourceDetail), p.extras(),
            m?.let { listOf(it.barcode, it.category, it.ingredients, it.allergens) }, input.productId, input.expectedVersionId,
            input.basisConfirmed, input.barcodeConfirmed, input.reason)
        replay(key, hash)?.let { return@withTransaction it }
        require(if (input.productId == null) input.expectedVersionId == null else dao.latestVersion(input.productId)?.id == input.expectedVersionId) {
            "Produktversion inzwischen geändert. Aktuelle Angaben neu prüfen."
        }
        val version = rememberProduct(id(), p, input.productId, input.reason)
        if (input.barcodeConfirmed) {
            val code = requireNotNull(m?.barcode) { "Bestätigter Barcode fehlt." }
            recognitionWriter.alias(id(), AliasInput(version, code, null, AliasKind.BARCODE, reason = input.reason), null, true)
        }
        dao.insertOperation(OperationEntity(key, "RECOGNIZED_PRODUCT", hash, now(), version))
        version
    }
    suspend fun savePurchase(key: String, input: PurchaseInput, confirmed: Boolean) = purchaseWriter.save(key, input, confirmed)
    suspend fun confirmReceiptPayment(key: String, receiptId: String, reason: String, confirmed: Boolean) = purchaseWriter.confirmReceipt(key, receiptId, reason, confirmed)
    /** Review-only: verified paid evidence, not a price_observations write or a current offer. */
    suspend fun previewHistoricalPaidPrice(input: PaidPriceBasisReview): PaidPricePreview = paidPriceReader.preview(input)
    /**
     * The sole new write boundary: one immutable, private, explicitly reviewed historical
     * price per real paid receipt line. A cancelled purchase remains auditable but is
     * excluded from activePaidPrices; nothing is inserted into current price_observations.
     */
    suspend fun saveHistoricalPaidPrice(key: String, input: PaidPriceBasisReview, confirmed: Boolean): String = db.withTransaction {
        require(confirmed && !input.basisReason.isNullOrBlank()) { "Positionspreis und Mengen-/Rabattbasis ausdrücklich mit Begründung bestätigen." }
        val hash = fingerprint("HISTORICAL_PAID_1", input.purchaseOperationId, input.receiptLineId,
            input.expectedProductVersionId, input.purchaseUnits, input.allocatedDiscountCents,
            input.depositPerUnitCents, input.discountReconciled, input.amountExcludesDeposit,
            input.unitsConfirmed, input.foodAndProductConfirmed, input.basisReason)
        replay(key, hash)?.let { return@withTransaction it }
        val preview = paidPriceReader.preview(input)
        require(preview.eligible) { "Unbelegter historischer Positionspreis: ${preview.rejectionReason}" }
        require(dao.paidPriceForLine(input.receiptLineId) == null) { "Belegposition hat bereits einen unveränderlichen historischen Preisbeleg." }
        val line = requireNotNull(dao.receiptLine(input.receiptLineId))
        val receipt = requireNotNull(dao.receipt(line.receiptId))
        val observationId = requireNotNull(preview.observationId)
        dao.insertOperation(OperationEntity(key, "HISTORICAL_PAID", hash, now(), observationId))
        dao.insertPaidPrice(PaidPriceObservationEntity(observationId, key, input.purchaseOperationId,
            line.id, requireNotNull(preview.productVersionId), requireNotNull(receipt.merchant),
            null, requireNotNull(preview.purchasedOn), requireNotNull(input.purchaseUnits),
            requireNotNull(preview.unitPriceCents), requireNotNull(preview.linePaidCents),
            requireNotNull(input.allocatedDiscountCents), preview.depositPerUnitCents,
            requireNotNull(input.basisReason), now()))
        observationId
    }
    /** Private historical evidence only, not current retailer offers. */
    suspend fun activeHistoricalPaidPrices(): List<PaidPriceObservationEntity> = db.withTransaction { dao.activePaidPrices() }
    suspend fun historicalPaidPriceAudit(): List<PaidPriceObservationEntity> = db.withTransaction { dao.allPaidPrices() }
    /** Past-only private evidence, never a current price or an implicit shopping choice. */
    suspend fun historicalPaidComparison(scope: HistoricalPaidComparisonScope): HistoricalPaidComparison =
        historicalPaidComparisonReader.compare(scope)
    suspend fun bookPurchaseLine(key: String, input: PurchaseStockInput, confirmed: Boolean) = purchaseWriter.bookLine(key, input, confirmed)
    suspend fun cancelPurchase(key: String, input: ReversalInput, confirmed: Boolean) = purchaseWriter.cancel(key, input, confirmed)
    suspend fun expenseReport(scope: ExpenseScope) = expenseReader.report(scope)
    fun watchExpenses(scope: ExpenseScope) = expenseReader.watch(scope)
    suspend fun setExpenseStatus(key: String, input: ExpenseStatusInput, confirmed: Boolean) = purchaseWriter.setStatus(key, input, confirmed)
    fun watchShopping(scope: ShoppingScope) = shoppingReader.watch(scope)
    suspend fun shoppingReport(scope: ShoppingScope) = shoppingReader.report(scope)
    suspend fun savePrice(key: String, input: PriceInput, previousId: String? = null) = shoppingWriter.price(key, input, previousId)
    suspend fun saveBudget(key: String, input: BudgetInput, previousId: String? = null) = shoppingWriter.budget(key, input, previousId)
    suspend fun markShopping(key: String, input: ShoppingMarkInput) = shoppingWriter.mark(key, input)
    private val executionReader = PlanExecutionReader(db)
    private val executionWriter = PlanExecutionOperations(db, now)
    fun watchPlanExecution(entryId: String) = executionReader.watch(entryId)
    suspend fun planExecution(entryId: String) = executionReader.view(entryId)
    suspend fun linkPlanExecution(key: String, input: LinkPlanExecutionInput, confirmed: Boolean) = executionWriter.link(key, input, confirmed)
    suspend fun unlinkPlanExecution(key: String, input: UnlinkPlanExecutionInput, confirmed: Boolean) = executionWriter.unlink(key, input, confirmed)
    fun watchPlanning(startsOn: String, endsOn: String) = demand.watch(startsOn, endsOn)
    suspend fun planningReport(startsOn: String, endsOn: String) = demand.report(startsOn, endsOn)
    suspend fun savePlan(key: String, input: PlanInput, previousVersionId: String? = null) = planning.save(key, input, previousVersionId)
    suspend fun cancelPlan(key: String, previousVersionId: String, reason: String) = planning.cancel(key, previousVersionId, reason)
    fun watchDay(date: String) = nutrition.watchDay(date)
    fun watchRecipeNutrition(id: String) = nutrition.watchRecipe(id)
    suspend fun dayNutrition(date: String) = nutrition.day(date)
    suspend fun recipeNutrition(id: String) = nutrition.recipe(id)
    suspend fun setDayStatus(key: String, date: String, status: DayStatus, expectedSnapshot: String, confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Erfassungsstatus ausdrücklich bestätigen." }
        WriteRules.date(date)
        val hash = fingerprint("DAY_STATUS", date, status, expectedSnapshot)
        replay(key, hash)?.let { return@withTransaction it }
        actualDate(date)
        val current = nutrition.day(date)
        require(current.snapshot == expectedSnapshot) { "Tag wurde inzwischen verändert. Aktuelle Einträge und Datenlücken erneut prüfen." }
        dao.saveDay(DiaryDayEntity(date, status, if (status == DayStatus.COMPLETE) now() else null))
        dao.insertOperation(OperationEntity(key, "DAY_${status.name}", hash, now(), date))
        date
    }
    fun operations() = dao.observeOperations()
    fun recipes() = dao.observeRecipes()
    fun recipeIngredients() = dao.observeIngredients()
    fun versions() = dao.observeVersions()
    suspend fun operationDetails(key: String): OperationDetails = db.withTransaction {
        OperationDetails(requireNotNull(dao.operation(key)) { "Vorgang nicht gefunden." }, dao.reversal(key),
            dao.movementDetails(key), dao.consumptionForOperation(key))
    }
    suspend fun transfer(key: String, input: TransferInput, confirmed: Boolean) = stock.transfer(key, input, confirmed)
    suspend fun waste(key: String, input: WasteInput, confirmed: Boolean) = stock.waste(key, input, confirmed)
    suspend fun correctStock(key: String, input: StockCountInput, confirmed: Boolean) = stock.correctStock(key, input, confirmed)
    suspend fun reverse(key: String, input: ReversalInput, confirmed: Boolean) = stock.reverse(key, input, confirmed)
    suspend fun saveRecipe(key: String, input: RecipeInput, recipeId: String? = null, reason: String = "Erste Fassung") = kitchen.saveRecipe(key, input, recipeId, reason)
    suspend fun produce(key: String, input: ProductionInput, confirmed: Boolean) = kitchen.produce(key, input, confirmed)
    fun catalog() = dao.observeCatalog()
    fun batches() = dao.observeBatches()
    fun locations() = dao.observeLocations()
    fun movements() = dao.observeMovements()

    /** No inventory is created by remembering a product or adding a version. */
    suspend fun rememberProduct(operationId: String, input: ProductInput, productId: String? = null,
        reason: String = "Manuell erfasst"): String = db.withTransaction {
        validate(input)
        require(reason.isNotBlank()) { "Änderungsgrund fehlt." }
        val legacyFingerprint = fingerprint("PRODUCT", listOf(input.name, input.brand, input.unit, input.packageAmount,
            input.nutritionBasis, input.energyKcal, input.proteinGrams, input.fatGrams, input.carbsGrams,
            input.source, input.quality, input.sourceDetail), productId, reason)
        // Existing 0.2/0.3 product retry keys remain valid when no additional nutrients were supplied.
        val nutrientsFingerprint = if (input.extras().all { it == null }) legacyFingerprint else fingerprint("PRODUCT_NUTRIENTS", legacyFingerprint, input.extras())
        val fingerprint = input.metadata?.let { fingerprint("PRODUCT_METADATA_1", nutrientsFingerprint, it.barcode, it.category, it.ingredients, it.allergens) } ?: nutrientsFingerprint
        replay(operationId, fingerprint)?.let { return@withTransaction it }
        val previous = productId?.let { requireNotNull(dao.latestVersion(it)) { "Produkt nicht gefunden." } }
        require(previous == null || previous.baseUnit == input.unit) { "Basiseinheit einer Produktidentität darf nicht geändert werden." }
        val identity = productId ?: id()
        if (previous == null) dao.insertProduct(ProductEntity(identity, now()))
        val version = id()
        dao.insertVersion(ProductVersionEntity(version, identity, (previous?.revision ?: 0) + 1,
            input.name.trim(), input.brand?.trim()?.takeIf { it.isNotEmpty() }, if (input.metadata != null) input.metadata.category else previous?.category,
            if (input.metadata != null) input.metadata.barcode else previous?.barcode,
            input.unit, input.packageAmount, input.nutritionBasis, input.energyKcal, input.proteinGrams,
            input.fatGrams, input.carbsGrams, fiberGrams = input.fiberGrams, sugarGrams = input.sugarGrams,
            saturatedFatGrams = input.saturatedFatGrams, saltGrams = input.saltGrams, calciumMg = input.calciumMg,
            ironMg = input.ironMg, magnesiumMg = input.magnesiumMg, iodineMicrograms = input.iodineMicrograms,
            vitaminB12Micrograms = input.vitaminB12Micrograms, vitaminDMicrograms = input.vitaminDMicrograms,
            ingredients = if (input.metadata != null) input.metadata.ingredients else previous?.ingredients,
            allergens = if (input.metadata != null) input.metadata.allergens else previous?.allergens, source = input.source, sourceDetail = input.sourceDetail,
            quality = input.quality, recordedAt = now(), changeReason = reason))
        dao.insertOperation(OperationEntity(operationId, "PRODUCT", fingerprint, now(), version))
        version
    }

    suspend fun addLocation(operationId: String, name: String, confirmed: Boolean): String = db.withTransaction {
        require(name.isNotBlank()) { "Lagername fehlt." }
        val fingerprint = fingerprint("LOCATION", name.trim(), confirmed)
        replay(operationId, fingerprint)?.let { return@withTransaction it }
        val locationId = id()
        dao.insertLocation(StorageLocationEntity(locationId, name.trim(), confirmed))
        dao.insertOperation(OperationEntity(operationId, "LOCATION", fingerprint, now(), locationId))
        locationId
    }

    /** This is an explicit opening/receipt record in the sandbox, not a receipt or an expense. */
    suspend fun receive(operationId: String, input: BatchInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Zugang muss ausdrücklich bestätigt werden." }
        Amounts.positive(input.amount)
        listOf(input.acquiredOn, input.bestBefore, input.useBy).forEach { it?.let(::date) }
        val fingerprint = fingerprint("RECEIPT", input.versionId, input.locationId, input.amount,
            input.acquiredOn, input.bestBefore, input.useBy, input.release, input.safetyNote)
        replay(operationId, fingerprint)?.let { return@withTransaction it }
        input.acquiredOn?.let(::actualDate)
        requireNotNull(dao.version(input.versionId)) { "Produktversion nicht gefunden." }
        val location = requireNotNull(dao.location(input.locationId)) { "Lagerort nicht gefunden." }
        require(location.confirmed) { "Lagerort ist nicht als vorhanden bestätigt." }
        val batchId = id()
        dao.insertOperation(OperationEntity(operationId, "RECEIPT", fingerprint, now(), batchId))
        dao.insertBatch(BatchEntity(batchId, input.versionId, input.locationId, operationId,
            input.acquiredOn, input.bestBefore, input.useBy, release = input.release, safetyNote = input.safetyNote))
        dao.insertMovement(StockMovementEntity(id(), batchId, operationId, input.amount, MovementKind.RECEIPT,
            null, Source.USER, "Bestätigter Zugang im lokalen Testbereich", recordedAt = now(), occurredOn = input.acquiredOn))
        batchId
    }

    /** Stock allocations are optional; an unknown amount is permitted only without stock effects. */
    suspend fun consume(operationId: String, input: ConsumptionInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Tatsächlichen Verzehr bestätigen." }
        date(input.date)
        require(input.meal.isNotBlank()) { "Mahlzeitenbezeichnung fehlt." }
        input.amount?.let(Amounts::positive)
        require(input.allocations.map { it.batchId }.distinct().size == input.allocations.size) { "Charge doppelt zugeordnet." }
        input.allocations.forEach { Amounts.positive(it.amount) }
        val total = input.allocations.fold(0L) { sum, a -> Math.addExact(sum, a.amount) }
        require(input.allocations.isEmpty() || input.amount != null && total == input.amount) { "Chargenmengen stimmen nicht mit Verzehrmenge überein." }
        val fingerprint = fingerprint("CONSUMPTION", input.versionId, input.date, input.meal, input.amount,
            input.occurredAt, input.quality, input.note, input.allocations.sortedBy { it.batchId }.map { listOf(it.batchId, it.amount) })
        replay(operationId, fingerprint)?.let { return@withTransaction it }
        actualDate(input.date)
        requireNotNull(dao.version(input.versionId)) { "Produktversion nicht gefunden." }
        val consumptionId = id()
        dao.insertOperation(OperationEntity(operationId, "CONSUMPTION", fingerprint, now(), consumptionId))
        dao.insertConsumption(ConsumptionEntity(consumptionId, operationId, input.versionId, input.date,
            input.occurredAt, input.meal.trim(), input.amount, Source.USER, input.quality, input.note))
        for (allocation in input.allocations) {
            val batch = WriteRules.available(dao, allocation.batchId, allocation.amount, input.date, true)
            require(batch.versionId == input.versionId) { "Charge gehört zu einer anderen Produktversion." }
            dao.insertAllocation(ConsumptionAllocationEntity(consumptionId, batch.id, allocation.amount))
            dao.insertMovement(StockMovementEntity(id(), batch.id, operationId, -allocation.amount,
                MovementKind.CONSUMPTION, input.occurredAt, Source.USER, "Bestätigter Verzehr", recordedAt = now(), occurredOn = input.date))
        }
        // A new entry invalidates a prior completeness assertion. No automatic COMPLETE status.
        dao.saveDay(DiaryDayEntity(input.date, DayStatus.PARTIAL, null))
        consumptionId
    }

    private suspend fun replay(operationId: String, fingerprint: String): String? = WriteRules.replay(dao, operationId, fingerprint)

    private fun validate(input: ProductInput) {
        require(input.name.isNotBlank()) { "Produktname fehlt." }
        Amounts.positive(input.nutritionBasis)
        input.packageAmount?.let(Amounts::positive)
        input.metadata?.let { metadata ->
            metadata.barcode?.let(de.ernaehrung.demo.data.recognition.Gtin::parse)
            require((metadata.ingredients?.length ?: 0) <= 20_000 && (metadata.allergens?.length ?: 0) <= 5000 &&
                (metadata.category?.length ?: 0) <= 1000) { "Produktmetadaten sind zu lang." }
        }
        (listOf(input.energyKcal, input.proteinGrams, input.fatGrams, input.carbsGrams) + input.extras()).forEach {
            require(it == null || it.isFinite() && it >= 0) { "Nährwerte müssen bekannte, nichtnegative Zahlen sein; leere Felder bleiben unbekannt." }
        }
    }

    private fun date(value: String) = WriteRules.date(value)
    private fun actualDate(value: String) = WriteRules.actualDate(value, now())
    private fun id(): String = WriteRules.id()
    private fun fingerprint(vararg parts: Any?): String = WriteRules.fingerprint(*parts)
}
