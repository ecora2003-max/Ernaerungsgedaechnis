package de.ernaehrung.demo.data.local

import androidx.room.withTransaction

data class ProductInput(
    val name: String, val brand: String? = null, val unit: BaseUnit = BaseUnit.GRAM,
    val packageAmount: Long? = null, val nutritionBasis: Long = 100_000,
    val energyKcal: Double? = null, val proteinGrams: Double? = null, val fatGrams: Double? = null,
    val carbsGrams: Double? = null, val source: Source = Source.USER,
    val quality: Quality = Quality.UNKNOWN, val sourceDetail: String? = null
)
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
        val fingerprint = fingerprint("PRODUCT", listOf(input.name, input.brand, input.unit, input.packageAmount,
            input.nutritionBasis, input.energyKcal, input.proteinGrams, input.fatGrams, input.carbsGrams,
            input.source, input.quality, input.sourceDetail), productId, reason)
        replay(operationId, fingerprint)?.let { return@withTransaction it }
        val previous = productId?.let { requireNotNull(dao.latestVersion(it)) { "Produkt nicht gefunden." } }
        require(previous == null || previous.baseUnit == input.unit) { "Basiseinheit einer Produktidentität darf nicht geändert werden." }
        val identity = productId ?: id()
        if (previous == null) dao.insertProduct(ProductEntity(identity, now()))
        val version = id()
        dao.insertVersion(ProductVersionEntity(version, identity, (previous?.revision ?: 0) + 1,
            input.name.trim(), input.brand?.trim()?.takeIf { it.isNotEmpty() }, null, null,
            input.unit, input.packageAmount, input.nutritionBasis, input.energyKcal, input.proteinGrams,
            input.fatGrams, input.carbsGrams, source = input.source, sourceDetail = input.sourceDetail,
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
        listOf(input.energyKcal, input.proteinGrams, input.fatGrams, input.carbsGrams).forEach {
            require(it == null || it.isFinite() && it >= 0) { "Nährwerte müssen bekannte, nichtnegative Zahlen sein; leere Felder bleiben unbekannt." }
        }
    }

    private fun date(value: String) = WriteRules.date(value)
    private fun actualDate(value: String) = WriteRules.actualDate(value, now())
    private fun id(): String = WriteRules.id()
    private fun fingerprint(vararg parts: Any?): String = WriteRules.fingerprint(*parts)
}
