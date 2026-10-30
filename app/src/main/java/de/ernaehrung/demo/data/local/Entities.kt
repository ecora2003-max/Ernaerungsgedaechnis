package de.ernaehrung.demo.data.local

import androidx.room.*

// Timestamps: epoch milliseconds (UTC). Calendar dates: ISO yyyy-MM-dd, no invented dates.
// No defaults for food safety, personal targets, or unknown quantities imply confirmation.
@Entity(tableName = "products")
data class ProductEntity(@PrimaryKey val id: String, val createdAt: Long)

@Entity(tableName = "product_versions", foreignKeys = [ForeignKey(
    entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT
)], indices = [Index(value = ["productId", "revision"], unique = true), Index(value = ["productId", "id"], unique = true)])
data class ProductVersionEntity(
    @PrimaryKey val id: String, val productId: String, val revision: Int,
    val name: String, val brand: String?, val category: String?, val barcode: String?,
    val baseUnit: BaseUnit, val packageAmount: Long?, val nutritionBasis: Long,
    val energyKcal: Double?, val proteinGrams: Double?, val fatGrams: Double?, val carbsGrams: Double?,
    val fiberGrams: Double? = null, val sugarGrams: Double? = null, val saturatedFatGrams: Double? = null, val saltGrams: Double? = null,
    val calciumMg: Double? = null, val ironMg: Double? = null, val magnesiumMg: Double? = null,
    val iodineMicrograms: Double? = null, val vitaminB12Micrograms: Double? = null, val vitaminDMicrograms: Double? = null,
    val ingredients: String? = null, val allergens: String? = null,
    val source: Source, val sourceDetail: String?, val quality: Quality, val recordedAt: Long,
    val changeReason: String
)

@Entity(tableName = "product_aliases", foreignKeys = [
    ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProductVersionEntity::class, parentColumns = ["productId", "id"], childColumns = ["productId", "versionId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["productId", "versionId"]), Index(value = ["merchant", "text"])])
data class ProductAliasEntity(@PrimaryKey val id: String, val productId: String, val versionId: String?,
    val text: String, val merchant: String?, val barcode: String?, val packageAmount: Long?,
    val quality: Quality, val source: Source, val recordedAt: Long)

@Entity(tableName = "storage_locations")
data class StorageLocationEntity(@PrimaryKey val id: String, val name: String, val confirmed: Boolean,
    val usableCapacityMl: Long? = null, val temperatureCelsius: Double? = null)

/** An operation is inserted in the same transaction as all effects; only committed operations exist. */
@Entity(tableName = "operations")
data class OperationEntity(@PrimaryKey val id: String, val kind: String, val fingerprint: String,
    val recordedAt: Long, val resultId: String)

@Entity(tableName = "batches", foreignKeys = [
    ForeignKey(entity = ProductVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = StorageLocationEntity::class, parentColumns = ["id"], childColumns = ["locationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["originOperationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = RecipeVersionEntity::class, parentColumns = ["id"], childColumns = ["recipeVersionId"], onDelete = ForeignKey.RESTRICT)
    , ForeignKey(entity = BatchEntity::class, parentColumns = ["id"], childColumns = ["parentBatchId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("versionId"), Index("locationId"), Index("originOperationId"), Index("recipeVersionId"), Index("parentBatchId")])
data class BatchEntity(@PrimaryKey val id: String, val versionId: String, val locationId: String,
    val originOperationId: String, val acquiredOn: String?, val bestBefore: String?, val useBy: String?,
    val openedAt: Long? = null, val thawedAt: Long? = null,
    val release: ReleaseStatus = ReleaseStatus.UNCHECKED, val safetyNote: String? = null, val recipeVersionId: String? = null,
    val parentBatchId: String? = null)

@Entity(tableName = "stock_movements", foreignKeys = [
    ForeignKey(entity = BatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = StockMovementEntity::class, parentColumns = ["id"], childColumns = ["reversesId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("batchId"), Index("operationId"), Index(value = ["reversesId"], unique = true)])
data class StockMovementEntity(@PrimaryKey val id: String, val batchId: String, val operationId: String,
    val delta: Long, val kind: MovementKind, val occurredAt: Long?, val source: Source,
    val reason: String, val recordedAt: Long, val reversesId: String? = null, val occurredOn: String? = null)

@Entity(tableName = "operation_reversals", foreignKeys = [
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["originalOperationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["reversalOperationId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["reversalOperationId"], unique = true)])
data class OperationReversalEntity(@PrimaryKey val originalOperationId: String, val reversalOperationId: String,
    val date: String, val reason: String)

@Entity(tableName = "recipe_output_products", foreignKeys = [
    ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["productId"], unique = true)])
data class RecipeOutputProductEntity(@PrimaryKey val recipeId: String, val productId: String)

@Entity(tableName = "productions", foreignKeys = [
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = RecipeVersionEntity::class, parentColumns = ["id"], childColumns = ["recipeVersionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BatchEntity::class, parentColumns = ["id"], childColumns = ["outputBatchId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("recipeVersionId"), Index(value = ["outputBatchId"], unique = true)])
data class ProductionEntity(@PrimaryKey val operationId: String, val recipeVersionId: String, val outputBatchId: String,
    val actualPortions: Long, val producedOn: String, val storageNote: String, val deviationNote: String?)

@Entity(tableName = "recipes")
data class RecipeEntity(@PrimaryKey val id: String, val createdAt: Long)

@Entity(tableName = "recipe_versions", foreignKeys = [
    ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["recipeId", "revision"], unique = true)])
data class RecipeVersionEntity(@PrimaryKey val id: String, val recipeId: String, val revision: Int,
    val name: String, val yieldPortions: Long, val portionWeightGrams: Long?, val steps: String,
    val activeMinutes: Int?, val totalMinutes: Int?, val mealPrep: Boolean, val storageInstructions: String?,
    val safetySource: String?, val quality: Quality, val recordedAt: Long, val changeReason: String)

@Entity(tableName = "recipe_ingredients", primaryKeys = ["recipeVersionId", "position"], foreignKeys = [
    ForeignKey(entity = RecipeVersionEntity::class, parentColumns = ["id"], childColumns = ["recipeVersionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProductVersionEntity::class, parentColumns = ["id"], childColumns = ["productVersionId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("productVersionId")])
data class RecipeIngredientEntity(val recipeVersionId: String, val position: Int, val productVersionId: String,
    val amount: Long?, val unit: BaseUnit, val note: String?)

@Entity(tableName = "consumption_events", foreignKeys = [
    ForeignKey(entity = ProductVersionEntity::class, parentColumns = ["id"], childColumns = ["productVersionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ConsumptionEntity::class, parentColumns = ["id"], childColumns = ["correctsId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("productVersionId"), Index(value = ["operationId"], unique = true), Index("correctsId"), Index("date")])
data class ConsumptionEntity(@PrimaryKey val id: String, val operationId: String, val productVersionId: String,
    val date: String, val occurredAt: Long?, val meal: String, val amount: Long?, val source: Source,
    val quality: Quality, val note: String?, val correctsId: String? = null)

@Entity(tableName = "consumption_allocations", primaryKeys = ["consumptionId", "batchId"], foreignKeys = [
    ForeignKey(entity = ConsumptionEntity::class, parentColumns = ["id"], childColumns = ["consumptionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("batchId")])
data class ConsumptionAllocationEntity(val consumptionId: String, val batchId: String, val amount: Long)

@Entity(tableName = "diary_days")
data class DiaryDayEntity(@PrimaryKey val date: String, val status: DayStatus, val confirmedAt: Long?)

// A plan's identity is stable; each row in plans is an immutable complete version.
@Entity(tableName = "plan_series")
data class PlanSeriesEntity(@PrimaryKey val id: String, val createdAt: Long)

@Entity(tableName = "plans", foreignKeys = [
    ForeignKey(entity = PlanSeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["seriesId", "revision"], unique = true)])
data class PlanEntity(@PrimaryKey val id: String, val revision: Int, val startsOn: String, val endsOn: String,
    val status: EntryStatus, val source: Source, val recordedAt: Long,
    val seriesId: String? = null, val title: String? = null, val changeReason: String? = null)

@Entity(tableName = "planned_entries", foreignKeys = [
    ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProductVersionEntity::class, parentColumns = ["id"], childColumns = ["productVersionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = RecipeVersionEntity::class, parentColumns = ["id"], childColumns = ["recipeVersionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = PlannedEntryEntity::class, parentColumns = ["id"], childColumns = ["preparationEntryId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("planId"), Index("productVersionId"), Index("recipeVersionId"), Index("preparationEntryId")])
data class PlannedEntryEntity(@PrimaryKey val id: String, val planId: String, val date: String, val kind: PlanKind,
    val productVersionId: String?, val recipeVersionId: String?, val amount: Long?, val unit: BaseUnit,
    val preparationEntryId: String? = null, val label: String? = null)

@Entity(tableName = "receipts")
data class ReceiptEntity(@PrimaryKey val id: String, val merchant: String?, val purchasedOn: String?,
    val totalCents: Long?, val source: Source, val quality: Quality, val recordedAt: Long)

@Entity(tableName = "receipt_lines", foreignKeys = [
    ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProductVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["stockOperationId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("receiptId"), Index("versionId"), Index(value = ["stockOperationId"], unique = true)])
data class ReceiptLineEntity(@PrimaryKey val id: String, val receiptId: String, val originalText: String?,
    val interpretedName: String?, val versionId: String?, val amount: Long?, val unit: BaseUnit?,
    val totalCents: Long?, val quality: Quality, val stockOperationId: String?)

@Entity(tableName = "price_observations", foreignKeys = [
    ForeignKey(entity = ProductVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("versionId")])
data class PriceObservationEntity(@PrimaryKey val id: String, val versionId: String, val merchant: String?,
    val observedOn: String, val amount: Long, val totalCents: Long, val source: Source, val quality: Quality)

@Entity(tableName = "budgets")
data class BudgetEntity(@PrimaryKey val id: String, val startsOn: String, val endsOn: String,
    val amountCents: Long?, val quality: Quality)

@Entity(tableName = "body_measurements", foreignKeys = [
    ForeignKey(entity = BodyMeasurementEntity::class, parentColumns = ["id"], childColumns = ["correctsId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("correctsId")])
data class BodyMeasurementEntity(@PrimaryKey val id: String, val measuredAt: Long, val weightKg: Double?,
    val waistCm: Double?, val bodyFatPercent: Double?, val muscleKg: Double?, val waterPercent: Double?,
    val device: String?, val method: String, val conditions: String?, val source: Source, val quality: Quality,
    val excludedFromTrend: Boolean, val recordedAt: Long, val correctsId: String?)

@Entity(tableName = "training_events")
data class TrainingEventEntity(@PrimaryKey val id: String, val occurredAt: Long, val sport: String,
    val durationMinutes: Int?, val intensity: String?, val perceivedExertion: Int?, val estimatedKcal: Double?,
    val energySource: String?, val note: String?, val source: Source, val quality: Quality, val recordedAt: Long)

@Entity(tableName = "training_sets", foreignKeys = [
    ForeignKey(entity = TrainingEventEntity::class, parentColumns = ["id"], childColumns = ["trainingId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("trainingId")])
data class TrainingSetEntity(@PrimaryKey val id: String, val trainingId: String, val exercise: String,
    val position: Int, val repetitions: Int?, val loadKg: Double?)
