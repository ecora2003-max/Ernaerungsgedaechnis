package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import java.math.BigInteger
import de.ernaehrung.demo.data.local.WriteRules.actualDate
import de.ernaehrung.demo.data.local.WriteRules.available
import de.ernaehrung.demo.data.local.WriteRules.date
import de.ernaehrung.demo.data.local.WriteRules.fingerprint
import de.ernaehrung.demo.data.local.WriteRules.id
import de.ernaehrung.demo.data.local.WriteRules.replay

data class RecipeItemInput(val versionId: String, val amount: Long?, val note: String? = null)
data class RecipeInput(val name: String, val yieldPortions: Long, val ingredients: List<RecipeItemInput>,
    val steps: String, val storageInstructions: String? = null, val safetySource: String? = null)
data class ProductionInput(val recipeVersionId: String, val actualPortions: Long, val allocations: List<Allocation>,
    val locationId: String, val date: String, val storageChecked: Boolean, val storageNote: String,
    val release: ReleaseStatus = ReleaseStatus.UNCHECKED, val deviationNote: String? = null,
    val bestBefore: String? = null, val useBy: String? = null)

internal class KitchenOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    suspend fun saveRecipe(
        key: String, input: RecipeInput, recipeId: String?, reason: String,
        /** Only for a separately reviewed recipe.draft; null preserves all legacy retry hashes. */
        approvalBasisDigest: String? = null
    ): String = db.withTransaction {
        require(input.name.isNotBlank() && reason.isNotBlank()) { "Rezeptname und Änderungsgrund fehlen." }
        Amounts.positive(input.yieldPortions)
        require(input.ingredients.isNotEmpty()) { "Mindestens eine Zutat angeben." }
        require(input.ingredients.map { it.versionId }.distinct().size == input.ingredients.size) { "Gleiche Artikelversion nur einmal als Zutat angeben." }
        input.ingredients.forEach { it.amount?.let(Amounts::positive) }
        val recipeHash = fingerprint("RECIPE", recipeId, reason, input.name, input.yieldPortions, input.steps,
            input.storageInstructions, input.safetySource, input.ingredients.map { listOf(it.versionId, it.amount, it.note) })
        // Preserve the existing fingerprint for all manual/legacy recipe operations.
        // An approved AI draft must additionally bind its operation key to the exact
        // reviewed draft/host-evidence digest, even when the RecipeInput is identical.
        val hash = if (approvalBasisDigest == null) recipeHash else {
            require(approvalBasisDigest.matches(Regex("[0-9a-f]{64}"))) {
                "Ungültiger Rezept-Freigabedigest."
            }
            fingerprint("RECIPE_DRAFT_APPROVAL_BOUND_V1", recipeHash, approvalBasisDigest)
        }
        replay(dao, key, hash)?.let { return@withTransaction it }
        val previous = recipeId?.let { requireNotNull(dao.latestRecipeVersion(it)) { "Rezept nicht gefunden." } }
        val identity = recipeId ?: id()
        val version = id()
        if (previous == null) dao.insertRecipe(RecipeEntity(identity, now()))
        dao.insertRecipeVersion(RecipeVersionEntity(version, identity, (previous?.revision ?: 0) + 1, input.name.trim(),
            input.yieldPortions, null, input.steps, null, null, true, input.storageInstructions, input.safetySource, Quality.UNKNOWN, now(), reason))
        input.ingredients.forEachIndexed { position, ingredient ->
            val product = requireNotNull(dao.version(ingredient.versionId)) { "Artikelversion der Zutat nicht gefunden." }
            dao.insertIngredient(RecipeIngredientEntity(version, position, product.id, ingredient.amount, product.baseUnit, ingredient.note))
        }
        dao.insertOperation(OperationEntity(key, "RECIPE", hash, now(), version))
        version
    }

    suspend fun produce(key: String, input: ProductionInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Tatsächliche Herstellung ausdrücklich bestätigen." }
        date(input.date); input.bestBefore?.let(::date); input.useBy?.let(::date)
        Amounts.positive(input.actualPortions)
        require(input.allocations.isNotEmpty()) { "Tatsächlich verwendete Chargen fehlen." }
        require(input.allocations.map { it.batchId }.distinct().size == input.allocations.size) { "Charge doppelt zugeordnet." }
        input.allocations.forEach { Amounts.positive(it.amount) }
        val hash = fingerprint("PRODUCTION", input.recipeVersionId, input.actualPortions, input.locationId, input.date,
            input.storageChecked, input.storageNote, input.release, input.deviationNote, input.bestBefore, input.useBy,
            input.allocations.sortedBy { it.batchId }.map { listOf(it.batchId, it.amount) })
        replay(dao, key, hash)?.let { return@withTransaction it }
        // Capture one physical time for date authorization and every ledger row.
        // A moving clock must not split one confirmed production across Berlin days.
        val recordedAt = now()
        require(recordedAt >= 0L) { "Ungültiger Herstellungszeitpunkt." }
        actualDate(input.date, recordedAt)
        require(input.storageChecked && input.storageNote.isNotBlank()) { "Geeignete Lagerbedingungen, Behälter und tatsächlich verfügbaren Platz ausdrücklich prüfen und beschreiben." }
        require(input.release != ReleaseStatus.RELEASED || input.useBy == null || input.useBy >= input.date) { "Abgelaufene Fertigportionen können nicht freigegeben werden." }
        val recipe = requireNotNull(dao.recipeVersion(input.recipeVersionId)) { "Rezeptversion nicht gefunden." }
        val ingredients = dao.ingredients(recipe.id)
        require(ingredients.isNotEmpty()) { "Rezept enthält keine Zutaten." }
        requireNotNull(dao.location(input.locationId)) { "Lagerort nicht gefunden." }.also { require(it.confirmed) { "Lagerort ist nicht als vorhanden bestätigt." } }
        val actualByVersion = mutableMapOf<String, Long>()
        for (allocation in input.allocations) {
            val batch = requireNotNull(dao.batch(allocation.batchId)) { "Zutatencharge nicht gefunden." }
            actualByVersion[batch.versionId] = Math.addExact(actualByVersion[batch.versionId] ?: 0, allocation.amount)
        }
        require(actualByVersion.keys == ingredients.map { it.productVersionId }.toSet()) { "Tatsächliche Chargen passen nicht zu allen Artikelversionen des Rezepts. Bei anderen Zutaten eine neue Rezeptversion anlegen." }
        val differs = ingredients.any { ingredient ->
            ingredient.amount == null || BigInteger.valueOf(actualByVersion.getValue(ingredient.productVersionId)) * BigInteger.valueOf(recipe.yieldPortions) !=
                BigInteger.valueOf(ingredient.amount) * BigInteger.valueOf(input.actualPortions)
        }
        require(!differs || !input.deviationNote.isNullOrBlank()) { "Abweichende oder ungeklärte Mengen/Ausbeute begründen." }

        val outputBatch = id()
        dao.insertOperation(OperationEntity(key, "PRODUCTION", hash, recordedAt, outputBatch))
        var outputProduct = dao.outputProduct(recipe.recipeId)
        if (outputProduct == null) {
            val product = id(); dao.insertProduct(ProductEntity(product, recordedAt))
            outputProduct = RecipeOutputProductEntity(recipe.recipeId, product); dao.insertOutputProduct(outputProduct)
        }
        val previous = dao.latestVersion(outputProduct.productId)
        val outputVersion = id()
        // Actual recipe/ingredient history is retained; nutrition remains unknown until calculated correctly.
        dao.insertVersion(ProductVersionEntity(outputVersion, outputProduct.productId, (previous?.revision ?: 0) + 1,
            recipe.name, null, "Zubereitung", null, BaseUnit.PORTION, null, 1000,
            null, null, null, null, source = Source.USER, sourceDetail = "Herstellung $key · Rezeptversion ${recipe.id}",
            quality = Quality.UNKNOWN, recordedAt = recordedAt, changeReason = "Tatsächlicher Produktionsansatz"))
        dao.insertBatch(BatchEntity(outputBatch, outputVersion, input.locationId, key, input.date, input.bestBefore, input.useBy,
            release = input.release, safetyNote = input.storageNote, recipeVersionId = recipe.id))
        dao.insertProduction(ProductionEntity(key, recipe.id, outputBatch, input.actualPortions, input.date, input.storageNote, input.deviationNote))
        for (allocation in input.allocations) {
            available(dao, allocation.batchId, allocation.amount, input.date, true)
            dao.insertMovement(StockMovementEntity(id(), allocation.batchId, key, -allocation.amount,
                MovementKind.PRODUCTION_INPUT, null, Source.USER, "Herstellung ${recipe.name}", recordedAt, occurredOn = input.date))
        }
        dao.insertMovement(StockMovementEntity(id(), outputBatch, key, input.actualPortions,
            MovementKind.PRODUCTION_OUTPUT, null, Source.USER, "Tatsächlich hergestellte Portionen", recordedAt, occurredOn = input.date))
        outputBatch
    }
}
