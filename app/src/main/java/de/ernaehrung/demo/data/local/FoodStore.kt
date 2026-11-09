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
    private var actionProofVerifier: RecipeActionProofVerifier = AndroidRecipeActionProof
    internal constructor(db: FoodDatabase, now: () -> Long,
        verifier: RecipeActionProofVerifier) : this(db, now) {
        actionProofVerifier = verifier
    }
    private val dao = db.foodDao()
    private val recipeActionNonceGate = RecipeActionNonceGate()

    /** Challenge is never persisted and cannot be replayed after process death. */
    internal fun beginRecipeDraftCredentialAction(key: String, digest: String): String =
        recipeActionNonceGate.issue(key, digest, now())
    private val stock = StockOperations(db, now)
    private val kitchen = KitchenOperations(db, now)
    private val nutrition = NutritionReader(db)
    private val planning = PlanningOperations(db, now)
    private val demand = PlanningReader(db, now)
    private val planNutritionReader = PlanNutritionReader(db, now)
    internal fun watchPlannedNutrition(startsOn: String, endsOn: String) = planNutritionReader.watch(startsOn, endsOn)
    internal suspend fun plannedNutrition(startsOn: String, endsOn: String) = planNutritionReader.report(startsOn, endsOn)
    private val shoppingReader = ShoppingReader(db, now)
    private val shoppingWriter = ShoppingOperations(db, now)
    private val purchaseWriter = PurchaseOperations(db, now)
    private val paidPriceReader = PaidReceiptPriceReader(db)
    private val historicalPaidComparisonReader = HistoricalPaidComparisonReader(db)
    private val expenseReader = ExpenseReader(db, now)
    private val recognitionReader = RecognitionReader(db)
    private val recognitionWriter = RecognitionOperations(db, now)
    private val body = BodyMeasurementOperations(db, now)
    private val trainingJournal = TrainingJournalReader(db)
    internal fun trainingJournal() = trainingJournal.observe()
    private val dataQuality = DataQualityReader(db, now)
    internal fun dataQuality() = dataQuality.observe()
    private val batchReviews = BatchReviewOperations(db, now)
    internal fun watchBatchReview(id: String) = batchReviews.watch(id)
    internal suspend fun reviewBatch(key: String, input: BatchReviewInput, confirmed: Boolean) = batchReviews.save(key, input, confirmed)
    private val locationReviews = StorageLocationReviewOperations(db, now)
    internal suspend fun reviewStorageLocation(key: String, input: StorageLocationReviewInput, confirmed: Boolean) =
        locationReviews.save(key, input, confirmed)
    private val storageCapacity = StorageCapacityOperations(db, now)
    internal fun watchStorageCapacity() = storageCapacity.watch()
    internal suspend fun reviewStorageCapacity(key: String, input: StorageCapacityInput, confirmed: Boolean) =
        storageCapacity.save(key, input, confirmed)
    private val backups = FoodBackupStore(db, now)
    private val nutritionGoals = NutritionGoalOperations(db, now)
    private val personalRestrictions = PersonalRestrictionOperations(db, now)
    private val recipeFavorites = RecipeFavoriteOperations(db, now)
    internal fun watchRecipeFavorites() = recipeFavorites.watch()
    internal suspend fun readRecipeFavorites() = db.withTransaction { recipeFavorites.snapshot() }
    internal suspend fun setRecipeFavorite(key: String,input: RecipeFavoriteInput,confirmed: Boolean) = recipeFavorites.save(key,input,confirmed)
    internal fun watchPersonalRestrictions() = personalRestrictions.watch()
    internal suspend fun readPersonalRestrictions() = db.withTransaction { personalRestrictions.snapshot() }
    internal suspend fun proposePersonalRestrictions(key: String, input: PersonalRestrictionInput, requested: Boolean) =
        personalRestrictions.propose(key,input,requested)
    internal suspend fun confirmPersonalRestrictions(key: String, proposalId: String, binding: String, confirmed: Boolean) =
        personalRestrictions.confirm(key,proposalId,binding,confirmed)
    internal suspend fun erasePersonalRestrictions(key: String, reviewedToken: String, confirmed: Boolean) =
        personalRestrictions.erase(key,reviewedToken,confirmed)
    internal fun watchNutritionGoals() = nutritionGoals.watch()
    internal suspend fun readNutritionGoals() = db.withTransaction { nutritionGoals.snapshot() }
    internal suspend fun proposeNutritionGoals(key: String, input: NutritionGoalDraftInput, saveRequested: Boolean) =
        nutritionGoals.propose(key, input, saveRequested)
    internal suspend fun confirmNutritionGoals(key: String, proposalId: String, reviewedBinding: String, confirmed: Boolean) =
        nutritionGoals.confirm(key, proposalId, reviewedBinding, confirmed)
    internal suspend fun eraseNutritionGoals(key: String, reviewedToken: String, confirmed: Boolean) =
        nutritionGoals.erase(key, reviewedToken, confirmed)
    internal suspend fun captureBackup(applicationId: String, fitnessIncluded: Boolean, confirmed: Boolean) =
        backups.capture(applicationId, fitnessIncluded, confirmed)
    internal suspend fun validateBackupForPreview(archive: de.ernaehrung.demo.data.backup.BackupArchive, applicationId: String) =
        backups.validateForPreview(archive, applicationId)
    internal suspend fun restoreBackup(key: String, archive: de.ernaehrung.demo.data.backup.BackupArchive,
        expectedDigest: String, applicationId: String, confirmed: Boolean) =
        backups.restore(key, archive, expectedDigest, applicationId, confirmed)
    private val manualWorkouts = ManualWorkoutOperations(db, now)
    internal suspend fun manualWorkoutRun(key: String) = manualWorkouts.run(key)
    internal suspend fun beginManualWorkout(key: String) = manualWorkouts.begin(key)
    internal fun watchManualWorkoutRun(key: String) = manualWorkouts.observeRun(key)
    internal suspend fun saveManualWorkout(key: String, input: de.ernaehrung.demo.data.fitness.ManualWorkoutInput,
        previousId: String?, legacyId: String?, confirmed: Boolean) = manualWorkouts.save(key, input, previousId, legacyId, confirmed)
    internal suspend fun purgeManualWorkouts(key: String, confirmed: Boolean) = manualWorkouts.purge(key, confirmed)
    internal fun bodyMeasurementHistory() = body.observe()
    internal suspend fun bodyMeasurementRun(key: String) = body.run(key)
    internal suspend fun beginBodyMeasurement(key: String) = body.begin(key)
    internal fun watchBodyMeasurementRun(key: String) = body.observeRun(key)
    internal suspend fun saveBodyMeasurement(key: String, input: de.ernaehrung.demo.data.fitness.BodyMeasurementInput,
        expectedPreviousId: String?, confirmed: Boolean, legacyId: String? = null,
        legacyField: de.ernaehrung.demo.data.fitness.LegacyBodyField? = null) = body.save(key, input, expectedPreviousId, confirmed, legacyId, legacyField)
    internal suspend fun purgeBodyMeasurements(key: String, confirmed: Boolean) = body.purge(key, confirmed)
    private val health = HealthImportOperations(db, now)
    internal fun fitnessCache() = health.observe()
    internal suspend fun enableFitnessImport(key: String, enabled: Boolean, confirmed: Boolean) = health.enable(key, enabled, confirmed)
    internal suspend fun syncFitness(key: String, provider: de.ernaehrung.demo.data.fitness.FitnessHealthSource) = health.sync(key, provider)
    internal suspend fun readFitnessWorkoutDuration(key: String, provider: de.ernaehrung.demo.data.fitness.FitnessHealthSource,
        target: de.ernaehrung.demo.data.fitness.FitnessWorkoutDurationTarget) = health.workoutDuration(key, provider, target)
    internal suspend fun stopFitnessAfterPermissionLoss(expectedRevision: Long) = health.permissionsLost(expectedRevision)
    internal suspend fun purgeLocalFitnessImport(key: String, confirmed: Boolean): Unit = db.withTransaction {
        // An old core purge retry must never erase observations imported later.
        // Both domains receive their retry marker on the FIRST global purge only.
        if (health.purge(key, confirmed)) healthExtra.purge(key, confirmed)
    }
    internal suspend fun fitnessRecordHistory(kind: String, id: String) = health.history(kind, id)
    internal suspend fun fitnessWorkoutDurationHistory(id: String) = health.durationHistory(id)
    private val healthExtra = HealthExtraOperations(db, now)
    private val workoutSourceWindow = WorkoutSourceWindowReader(db, healthExtra::readCache)
    internal fun watchWorkoutSourceWindow(target: de.ernaehrung.demo.data.fitness.WorkoutSourceTarget) = workoutSourceWindow.observe(target)
    internal fun healthExtraCache() = healthExtra.observe()
    internal suspend fun authorizeHealthExtra(key: String, selection: de.ernaehrung.demo.data.fitness.HealthExtraSelection?,
        provider: de.ernaehrung.demo.data.fitness.HealthExtraSource, confirmed: Boolean) = healthExtra.authorize(key, selection, provider, confirmed)
    internal suspend fun syncHealthExtra(key: String, provider: de.ernaehrung.demo.data.fitness.HealthExtraSource) = healthExtra.sync(key, provider)
    internal suspend fun stopHealthExtraAfterPermissionLoss(revision: Long) = healthExtra.permissionsLost(revision)
    internal suspend fun purgeHealthExtra(key: String, confirmed: Boolean): Unit = healthExtra.purge(key, confirmed)
    internal suspend fun healthExtraHistory(source: String, kind: String, id: String) = healthExtra.history(source, kind, id)
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
            RecipeRoomShoppingSnapshotReader(db)
                .read(scope, java.time.Instant.ofEpochMilli(now()))
        }


    /**
     * Capture the three independently selected context sources at ONE Room instant.
     * Stock and shopping are local read-only projections. External offers have no
     * configured rights-checked provider in the kitchen: requested offers stay
     * unavailable, never silently interpreted as an empty approved collection.
     * The resulting content binding is only change evidence, not user consent.
     */
    internal suspend fun readRecipeTypedContext(
        flags: de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Flags,
        shoppingScope: ShoppingScope? = null
    ): de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Context<
        de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot,
        de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot,
        de.ernaehrung.demo.data.integration.RecipeContextContentBinding.Snapshot> =
        db.withTransaction {
            val at = java.time.Instant.ofEpochMilli(now())
            val stockSnapshot = if (flags.considerStock)
                RecipeRoomStockSnapshotReader(dao).read(at) else null
            val shoppingSnapshot = if (flags.considerShopping && shoppingScope != null)
                RecipeRoomShoppingSnapshotReader(db).read(shoppingScope, at) else null
            val gate = de.ernaehrung.demo.data.integration.RecipeDraftHostGate
            gate.typedContext(flags, at,
                stockSnapshot?.let { de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Evidence(it, true) },
                shoppingSnapshot?.let { de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Evidence(it, true) },
                null)
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
     * Verifies the device signature and this process's one-shot challenge before
     * recording an attested action in the append-only operation ledger. No
     * recipe, stock, purchase or consumption is written by this checkpoint.
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
        trustedContextSnapshotAt: java.time.OffsetDateTime? = null,
        actionProof: RecipeActionProof? = null
    ): String = db.withTransaction {
        require(confirmed && request.userConfirmedRecipeSave) {
            "Rezeptentwurf nur nach separater Nutzeraktion bestätigen."
        }
        requireValidRecipeDraftSaveKey(key)
        val actionAt = now()
        require(actionAt >= 0L) { "Ungültiger Rezeptbestätigungszeitpunkt." }
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
            expectedPreview.approvalBasisDigest == current.approvalBasisDigest &&
            expectedPreview.personalRestrictionsBinding == current.personalRestrictionsBinding) {
            "Bestätigung bezieht sich nicht auf die aktuelle geprüfte Rezeptvorschau."
        }
        val digest = requireNotNull(current.approvalBasisDigest)
        val actionDigest = requireNotNull(current.actionScopeDigest)
        require(actionProof != null &&
            recipeActionNonceGate.consume(actionProof, key, actionDigest, actionAt) &&
            actionProofVerifier.verify(actionProof, key, actionDigest, actionAt)) {
            "Gerätegebundene Rezeptaktion fehlt, ist ungültig oder abgelaufen."
        }
        val consentKind = "RECIPE_DRAFT_ATTESTED_CONSENT_V1"
        val consentKey = recipeDraftConsentKey(key)
        val hash = recipeDraftConsentFingerprint(key, digest, current.personalRestrictionsBinding)
        val previous = dao.operation(consentKey)
        // The opaque save key shares the global operation namespace. A product,
        // purchase or other operation using that key must never be promoted to
        // a recipe approval merely because its prefixed consent key is unused.
        val existingSaveOperation = dao.operation(key)
        if (previous != null) {
            require(recipeDraftSaveOperationCompatible(existingSaveOperation, previous.recordedAt, actionAt)) {
                "Rezeptvorgangs-ID ist bereits durch einen anderen Vorgang belegt."
            }
            require(previous.kind == consentKind &&
                previous.fingerprint == hash && previous.resultId == digest &&
                previous.recordedAt in 0L..actionAt) {
                // A future-dated ledger row is not an already performed action.
                // Reject retry rather than silently accepting a clock rollback.
                "Bestätigungs-ID wurde bereits für einen anderen Rezeptentwurf verwendet."
            }
            // Replaying an immutable checkpoint cannot renew its five-minute approval.
            // A new confirmation after expiry must use a new operation key.
            require(actionAt - previous.recordedAt <= recipeDraftConsentTtlMs) {
                "Rezeptbestätigung abgelaufen; neue Vorgangs-ID und Gerätebestätigung erforderlich."
            }
            return@withTransaction previous.resultId
        }
        require(existingSaveOperation == null) {
            "Rezeptvorgangs-ID ist bereits durch einen anderen Vorgang belegt."
        }
        dao.insertOperation(OperationEntity(consentKey, consentKind,
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
        // Read the consent ledger and the global operation namespace in one
        // Room snapshot. A concurrent write must not change key ownership
        // between selecting a checkpoint and checking its availability.
        return db.withTransaction {
            val profileBinding = personalRestrictions.snapshot().binding
            val checkedAt = now()
            if (checkedAt < 0L) return@withTransaction null
            // The newest still-valid explicit action wins. Older checkpoints
            // remain immutable but must not shadow a more recent confirmation.
            dao.recipeDraftConsentsForDigest(digest).asReversed().firstOrNull { operation ->
                if (!operation.id.startsWith(prefix)) false
                else {
                    val key = operation.id.substring(prefix.length)
                    val existingSaveOperation = dao.operation(key)
                    isValidRecipeDraftSaveKey(key) &&
                        operation.kind == "RECIPE_DRAFT_ATTESTED_CONSENT_V1" &&
                        recipeDraftSaveOperationCompatible(existingSaveOperation, operation.recordedAt, checkedAt) &&
                        operation.recordedAt in 0L..checkedAt &&
                        checkedAt - operation.recordedAt <= recipeDraftConsentTtlMs &&
                        operation.fingerprint == recipeDraftConsentFingerprint(key, digest, profileBinding)
                }
            }?.id?.substring(prefix.length)
        }
    }

    /** A recovered consent cannot refer to a recipe operation without a real
     * persisted version or to a recipe written before that consent existed.
     * This checks ledger chronology and referential integrity, not cryptographic
     * provenance or the recipe's complete approval fingerprint.
     */
    private suspend fun recipeDraftSaveOperationCompatible(
        saved: OperationEntity?, consentAt: Long, checkedAt: Long
    ): Boolean {
        if (saved == null) return true
        // A future-dated recipe is not a completed action at this host instant.
        // Reject clock rollback on recovery, consent retry and save replay alike.
        if (saved.kind != "RECIPE" || saved.recordedAt !in consentAt..checkedAt) return false
        val version = dao.recipeVersion(saved.resultId) ?: return false
        // A recipe-save operation and its first persisted version are written
        // atomically at one physical instant. An unrelated later revision or
        // an operation pointing at a version created at another time is not a
        // completed replay of this consent, even if its fingerprint matches.
        // This is ledger integrity, NOT cryptographic user-action provenance.
        return version.revision == 1 && version.recordedAt == saved.recordedAt
    }

    /**
     * A resumed opaque pending key is not necessarily unused: the previous
     * consent may have committed before process death, while its private
     * pending-key cleanup did not. An expired ledger consent cannot be renewed
     * with that key. Check both the consent and recipe operation namespaces.
     * This is a read-only collision check, never an authorization to save.
     */
    internal suspend fun isRecipeDraftOperationKeyUnavailable(key: String): Boolean =
        db.withTransaction {
            requireValidRecipeDraftSaveKey(key)
            dao.operation(recipeDraftConsentKey(key)) != null ||
                dao.operation(key) != null
        }

    // A pending key is generated as an ASCII UUID. Keep the durable consent
    // namespace unambiguous and reject invisible controls, Unicode format
    // characters and whitespace that could hide a different ledger identity.
    // This is operation-key hygiene, not independent user authentication.
    private val recipeDraftSaveKeyPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")

    private fun isValidRecipeDraftSaveKey(key: String): Boolean =
        recipeDraftSaveKeyPattern.matches(key) &&
            !key.startsWith("RECIPE_DRAFT_CONSENT_V1:")

    private fun requireValidRecipeDraftSaveKey(key: String) {
        require(isValidRecipeDraftSaveKey(key)) { "Ungültige Rezeptvorgangs-ID." }
    }

    // Bound a durable host checkpoint to a recent action, not an indefinite approval.
    // A completed recipe operation may still replay idempotently after this window.
    private val recipeDraftConsentTtlMs = 5L * 60L * 1000L

    private fun recipeDraftConsentKey(key: String) = "RECIPE_DRAFT_CONSENT_V1:$key"

    private fun recipeDraftConsentFingerprint(key: String, digest: String, profileBinding: String? = null) =
        if (profileBinding == null) WriteRules.fingerprint("RECIPE_DRAFT_CONSENT_V1", key, digest)
        else WriteRules.fingerprint("PERSONAL_RESTRICTIONS_RECIPE_CONSENT_V1", key, digest, profileBinding)

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
        require(saveAt >= 0L) { "Ungültiger Rezept-Speicherzeitpunkt." }
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
            expectedPreview.approvalBasisDigest == current.approvalBasisDigest &&
            expectedPreview.personalRestrictionsBinding == current.personalRestrictionsBinding) {
            "Rezeptentwurf, Freigabebasis oder Produktbelege verändert; aktuelle Vorschau erneut bestätigen."
        }
        // The confirmation must have been recorded by a separate host action
        // and must survive app restart. A caller-provided Boolean alone is not enough.
        val digest = requireNotNull(current.approvalBasisDigest)
        val consent = dao.operation(recipeDraftConsentKey(key))
        require(consent != null &&
            consent.kind == "RECIPE_DRAFT_ATTESTED_CONSENT_V1" &&
            consent.resultId == digest &&
            consent.fingerprint == recipeDraftConsentFingerprint(key, digest, current.personalRestrictionsBinding) &&
            consent.recordedAt in 0L..saveAt) {
            "Keine passende dauerhaft gespeicherte Rezeptbestätigung vorhanden."
        }
        // An existing RECIPE operation can only bypass consent expiry if it
        // actually follows this consent and points to a persisted recipe version.
        // Otherwise KitchenOperations' idempotent replay could return an orphan
        // version or a recipe recorded before the alleged authorization.
        val existingSaveOperation = dao.operation(key)
        require(recipeDraftSaveOperationCompatible(existingSaveOperation, consent.recordedAt, saveAt)) {
            "Rezeptvorgang liegt vor der Bestätigung oder verweist auf fehlende Rezeptversion."
        }
        // A checkpoint alone must not authorize a *new* recipe hours or days later.
        // Preserve safe idempotent replay of an already written recipe; KitchenOperations
        // still checks its full input+approval fingerprint before returning the result.
        require(saveAt - consent.recordedAt <= recipeDraftConsentTtlMs ||
            existingSaveOperation?.kind == "RECIPE") {
            "Rezeptbestätigung abgelaufen; neue Gerätebestätigung erforderlich."
        }
        // Bind the persisted operation fingerprint to the exact approved preview.
        // A second draft with the same projected recipe must not replay this key.
        // Persist the same physical instant that authorized consent freshness and
        // the current host/catalog review; never sample a second clock value.
        kitchen.saveRecipe(key, current.input, null, "Bestätigter Rezeptentwurf v1",
            current.approvalBasisDigest, approvedAt = saveAt)
    }

    /** The five-minute host evidence window is millisecond-exact. Duration.seconds
     * truncates fractions and otherwise accepts a snapshot up to 999 ms too old.
     * This only validates freshness; it never authenticates user consent.
     */
    private fun recipeContextSnapshotFresh(
        capturedAt: java.time.Instant, checkedAt: java.time.Instant
    ): Boolean {
        val age = java.time.Duration.between(capturedAt, checkedAt)
        return !age.isNegative && age <= java.time.Duration.ofMinutes(5)
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
        // Independently enforce review chronology at the FoodStore boundary:
        // even a caller bypassing the manual-review UI cannot reuse a catalog
        // confirmation issued before the draft being approved existed.
        val catalog = dao.allVersions()
        val facts = de.ernaehrung.demo.data.integration.RecipeCatalogEvidence.resolve(
            lines, catalog, confirmations, trustedNow,
            draftCreatedAtMillis = imported?.createdAt?.toInstant()?.toEpochMilli())
        val profile = personalRestrictions.snapshot()
        val review = de.ernaehrung.demo.data.integration.RecipeDraftV1Import.review(
            json, request, facts, reviewedStates, clock, context, trustedContextSnapshotAt, catalog)
            .copy(personalRestrictionsBinding = profile.binding)
        if (!request.confirmedRestrictions.containsAll(profile.tokens)) return review.copy(
            status = de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.BLOCKED,
            blockers = review.blockers + "CONFIRMED_PERSONAL_RESTRICTIONS_MISSING", input = null, approvalBasisDigest = null)
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
            val ageValid = binding != null && fresh != null &&
                recipeContextSnapshotFresh(binding.snapshotAt, fresh.capturedAt)
            val valid = context?.contentBindingRequired == true &&
                binding?.stockDigest != null && original != null && fresh != null &&
                original.capturedAt == binding.snapshotAt &&
                ageValid &&
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
                RecipeRoomShoppingSnapshotReader(db)
                    .read(it, java.time.Instant.ofEpochMilli(trustedNow))
            }
            val ageValid = binding != null && fresh != null &&
                recipeContextSnapshotFresh(binding.snapshotAt, fresh.capturedAt)
            val valid = context?.contentBindingRequired == true &&
                binding?.shoppingDigest != null && original != null && fresh != null &&
                original.capturedAt == binding.snapshotAt &&
                ageValid &&
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
            val ageValid = binding != null && fresh != null &&
                recipeContextSnapshotFresh(binding.snapshotAt, fresh.capturedAt)
            val valid = context?.contentBindingRequired == true &&
                binding?.offersDigest != null && original != null && fresh != null &&
                original.capturedAt == binding.snapshotAt &&
                ageValid &&
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
            de.ernaehrung.demo.data.integration.RecipeDraftHostGate.Status.READY_FOR_EXPLICIT_SAVE) {
            val baseDigest = recipeDraftApprovalBasisDigest(
                json, request, confirmations, reviewedStates, trustedContextSnapshotAt)
            // A context-bound save must attest the exact independently revalidated
            // snapshot content, not merely the three booleans or snapshot time.
            // Keep the historical no-context digest stable for existing consents.
            // External offers require the same rights-checked reader at consent
            // and save time. That dependency is not yet wired: never issue a
            // usable approval digest for offer-enabled requests.
            val digest = if (!contextRequested) baseDigest else if (request.flags.considerOffers)
                null else context?.contentBinding?.takeIf {
                    context?.contentBindingRequired == true &&
                        it.flags == request.flags &&
                        it.snapshotAt == trustedContextSnapshotAt?.toInstant()
                }?.let { binding ->
                    WriteRules.fingerprint("RECIPE_DRAFT_CONTEXT_APPROVAL_V1",
                        baseDigest, binding.digest)
                }
            review.copy(approvalBasisDigest = digest)
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
    ): String {
        val legacy = WriteRules.fingerprint(
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
        // Preserve all existing default-request approval/retry fingerprints.
        val preferencesDigest = if (request.preferences == de.ernaehrung.demo.data.integration.RecipeRequestPreferences()) legacy
            else WriteRules.fingerprint("RECIPE_DRAFT_PREFERENCES_APPROVAL_V1", legacy, request.preferences.binding())
        val dietEvidence = confirmations.toSortedMap().filterValues { it.diets.isNotEmpty() }
            .map { (foodType, c) -> listOf(foodType,
                c.diets.toSortedMap().map { (diet, compatible) -> listOf(diet, compatible) }) }
        return if (dietEvidence.isEmpty()) preferencesDigest else WriteRules.fingerprint(
            "RECIPE_DRAFT_DIET_APPROVAL_V1", preferencesDigest, dietEvidence)
    }

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
        // One physical write time for the immutable operation and its private
        // paid-price evidence, even if the clock crosses midnight mid-transaction.
        val recordedAt = now()
        require(recordedAt >= 0L) { "Ungültiger Erfassungszeitpunkt für historischen Preis." }
        dao.insertOperation(OperationEntity(key, "HISTORICAL_PAID", hash, recordedAt, observationId))
        dao.insertPaidPrice(PaidPriceObservationEntity(observationId, key, input.purchaseOperationId,
            line.id, requireNotNull(preview.productVersionId), requireNotNull(receipt.merchant),
            null, requireNotNull(preview.purchasedOn), requireNotNull(input.purchaseUnits),
            requireNotNull(preview.unitPriceCents), requireNotNull(preview.linePaidCents),
            requireNotNull(input.allocatedDiscountCents), preview.depositPerUnitCents,
            requireNotNull(input.basisReason), recordedAt))
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
    internal suspend fun previewRecipeNutrition(input: RecipeInput) = nutrition.preview(input)
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
    private val stockHistory = StockHistoryReader(db)
    internal fun watchStockHistory(query: StockHistoryQuery) = stockHistory.watch(query)
    internal suspend fun readStockHistory(query: StockHistoryQuery) = stockHistory.read(query)
    internal fun watchOperationDetails(id: String) = stockHistory.watchOperation(id)
    fun recipes() = dao.observeRecipes()
    fun recipeIngredients() = dao.observeIngredients()
    fun versions() = dao.observeVersions()
    suspend fun operationDetails(key: String): OperationDetails = stockHistory.details(key)
    suspend fun transfer(key: String, input: TransferInput, confirmed: Boolean) = stock.transfer(key, input, confirmed)
    suspend fun waste(key: String, input: WasteInput, confirmed: Boolean) = stock.waste(key, input, confirmed)
    suspend fun correctStock(key: String, input: StockCountInput, confirmed: Boolean) = stock.correctStock(key, input, confirmed)
    suspend fun reverse(key: String, input: ReversalInput, confirmed: Boolean) = stock.reverse(key, input, confirmed)
    suspend fun saveRecipe(key: String, input: RecipeInput, recipeId: String? = null, reason: String = "Erste Fassung",
        expectedVersionId: String? = null) = kitchen.saveRecipe(key, input, recipeId, reason, expectedVersionId = expectedVersionId)
    suspend fun produce(key: String, input: ProductionInput, confirmed: Boolean) = kitchen.produce(key, input, confirmed)
    fun catalog() = dao.observeCatalog()
    fun batches() = dao.observeBatches()
    fun locations() = dao.observeLocations()
    fun movements() = dao.observeMovements()

    /** No inventory is created by remembering a product or adding a version. */
    suspend fun rememberProduct(operationId: String, input: ProductInput, productId: String? = null,
        reason: String = "Manuell erfasst", expectedVersionId: String? = null): String = db.withTransaction {
        validate(input)
        require(reason.isNotBlank()) { "Änderungsgrund fehlt." }
        val legacyFingerprint = fingerprint("PRODUCT", listOf(input.name, input.brand, input.unit, input.packageAmount,
            input.nutritionBasis, input.energyKcal, input.proteinGrams, input.fatGrams, input.carbsGrams,
            input.source, input.quality, input.sourceDetail), productId, reason)
        // Existing 0.2/0.3 product retry keys remain valid when no additional nutrients were supplied.
        val nutrientsFingerprint = if (input.extras().all { it == null }) legacyFingerprint else fingerprint("PRODUCT_NUTRIENTS", legacyFingerprint, input.extras())
        val metadataFingerprint = input.metadata?.let { fingerprint("PRODUCT_METADATA_1", nutrientsFingerprint, it.barcode, it.category, it.ingredients, it.allergens) } ?: nutrientsFingerprint
        // Existing retry fingerprints remain byte-for-byte unchanged. Interactive
        // corrections bind a separate versioned action to the reviewed predecessor.
        val fingerprint = expectedVersionId?.let { fingerprint("PRODUCT_EXPECTED_VERSION_1", metadataFingerprint, it) } ?: metadataFingerprint
        replay(operationId, fingerprint)?.let { return@withTransaction it }
        val previous = productId?.let { requireNotNull(dao.latestVersion(it)) { "Produkt nicht gefunden." } }
        require(expectedVersionId == null || previous?.id == expectedVersionId) { "Produktfassung inzwischen geändert. Aktuelle Fassung erneut auswählen und prüfen." }
        require(previous == null || previous.baseUnit == input.unit) { "Basiseinheit einer Produktidentität darf nicht geändert werden." }
        // One confirmed product revision is one physical event. Keep its creation,
        // version and operation timestamps identical even across clock changes.
        val recordedAt = now()
        require(recordedAt >= 0L) { "Produktzeitpunkt ist ungültig." }
        val identity = productId ?: id()
        if (previous == null) dao.insertProduct(ProductEntity(identity, recordedAt))
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
            quality = input.quality, recordedAt = recordedAt, changeReason = reason))
        dao.insertOperation(OperationEntity(operationId, "PRODUCT", fingerprint, recordedAt, version))
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
        // The confirmed receipt and its stock movement represent one atomic
        // event. Freeze its physical timestamp before validating the Berlin day:
        // a moving clock must not make a just-received batch future-dated in
        // an as-of Room stock snapshot.
        val recordedAt = now()
        require(recordedAt >= 0L) { "Ungültiger Zugangszeitpunkt." }
        input.acquiredOn?.let { WriteRules.actualDate(it, recordedAt) }
        val version = requireNotNull(dao.version(input.versionId)) { "Produktversion nicht gefunden." }
        require(version.recordedAt in 0L..recordedAt) {
            "Zugang liegt vor der erfassten Artikelversion."
        }
        val location = requireNotNull(dao.location(input.locationId)) { "Lagerort nicht gefunden." }
        require(location.confirmed) { "Lagerort ist nicht als vorhanden bestätigt." }
        val batchId = id()
        dao.insertOperation(OperationEntity(operationId, "RECEIPT", fingerprint, recordedAt, batchId))
        dao.insertBatch(BatchEntity(batchId, input.versionId, input.locationId, operationId,
            input.acquiredOn, input.bestBefore, input.useBy, release = input.release, safetyNote = input.safetyNote))
        dao.insertMovement(StockMovementEntity(id(), batchId, operationId, input.amount, MovementKind.RECEIPT,
            null, Source.USER, "Bestätigter Zugang im lokalen Testbereich", recordedAt = recordedAt, occurredOn = input.acquiredOn))
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
        // Freeze one physical write instant for the actual event and all its
        // movements. A future/negative occurrence must never be persisted as
        // today's stock consumption (or poison an as-of recipe snapshot).
        val recordedAt = now()
        require(recordedAt >= 0L) { "Ungültiger Verzehrbuchungszeitpunkt." }
        WriteRules.actualDate(input.date, recordedAt)
        input.occurredAt?.let { occurred ->
            require(occurred in 0L..recordedAt) {
                "Tatsächlicher Verzehrzeitpunkt liegt außerhalb der gültigen Vergangenheit."
            }
            // The optional physical instant and the confirmed event day describe
            // the same consumption. Resolve in Berlin, never the device timezone.
            // Reject a mismatched day before creating any operation or movement.
            require(java.time.Instant.ofEpochMilli(occurred)
                .atZone(java.time.ZoneId.of("Europe/Berlin"))
                .toLocalDate().toString() == input.date) {
                "Verzehrzeitpunkt und bestätigter Berliner Ereignistag widersprechen sich."
            }
        }
        val version = requireNotNull(dao.version(input.versionId)) { "Produktversion nicht gefunden." }
        require(version.recordedAt in 0L..recordedAt) {
            "Verzehrbuchung liegt vor der erfassten Artikelversion."
        }
        // An explicitly supplied occurrence is the physical consumption instant.
        // A batch created later on the same Berlin day cannot have been eaten yet.
        val provenanceAt = input.occurredAt ?: recordedAt
        val consumptionId = id()
        dao.insertOperation(OperationEntity(operationId, "CONSUMPTION", fingerprint, recordedAt, consumptionId))
        dao.insertConsumption(ConsumptionEntity(consumptionId, operationId, input.versionId, input.date,
            input.occurredAt, input.meal.trim(), input.amount, Source.USER, input.quality, input.note))
        for (allocation in input.allocations) {
            val batch = WriteRules.available(dao, allocation.batchId, allocation.amount, input.date, true)
            // A same-day calendar match cannot authorize consumption of a
            // batch whose creating operation is still in the physical future.
            WriteRules.batchOriginAt(dao, batch, provenanceAt)
            require(batch.versionId == input.versionId) { "Charge gehört zu einer anderen Produktversion." }
            dao.insertAllocation(ConsumptionAllocationEntity(consumptionId, batch.id, allocation.amount))
            dao.insertMovement(StockMovementEntity(id(), batch.id, operationId, -allocation.amount,
                MovementKind.CONSUMPTION, input.occurredAt, Source.USER, "Bestätigter Verzehr", recordedAt = recordedAt, occurredOn = input.date))
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
