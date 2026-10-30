package de.ernaehrung.demo.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class CatalogRow(@Embedded val version: ProductVersionEntity, val stockAmount: Long)
data class BatchRow(@Embedded val batch: BatchEntity, val productName: String, val unit: BaseUnit,
    val locationName: String, val stockAmount: Long, val originReversed: Boolean)
data class MovementRow(@Embedded val movement: StockMovementEntity, val productName: String, val unit: BaseUnit)
data class OperationRow(@Embedded val operation: OperationEntity, val reversedBy: String?)
data class IngredientRow(@Embedded val ingredient: RecipeIngredientEntity, val productName: String)
data class OperationDetails(val operation: OperationEntity, val reversal: OperationReversalEntity?,
    val movements: List<MovementRow>, val consumption: ConsumptionEntity?)

/** Internal write API: application callers must use FoodStore's validated transactions. No replace/upsert of history. */
@Dao
internal interface FoodDao {
    @Insert suspend fun insertProduct(value: ProductEntity)
    @Insert suspend fun insertVersion(value: ProductVersionEntity)
    @Insert suspend fun insertAlias(value: ProductAliasEntity)
    @Insert suspend fun insertLocation(value: StorageLocationEntity)
    @Insert suspend fun insertOperation(value: OperationEntity)
    @Insert suspend fun insertBatch(value: BatchEntity)
    @Insert suspend fun insertMovement(value: StockMovementEntity)
    @Insert suspend fun insertConsumption(value: ConsumptionEntity)
    @Insert suspend fun insertAllocation(value: ConsumptionAllocationEntity)
    @Upsert suspend fun saveDay(value: DiaryDayEntity)
    @Insert suspend fun insertPlanSeries(value: PlanSeriesEntity)
    @Insert suspend fun insertPlan(value: PlanEntity)
    @Insert suspend fun insertPlannedEntry(value: PlannedEntryEntity)
    @Insert suspend fun insertRecipe(value: RecipeEntity)
    @Insert suspend fun insertRecipeVersion(value: RecipeVersionEntity)
    @Insert suspend fun insertIngredient(value: RecipeIngredientEntity)
    @Insert suspend fun insertReversal(value: OperationReversalEntity)
    @Insert suspend fun insertOutputProduct(value: RecipeOutputProductEntity)
    @Insert suspend fun insertProduction(value: ProductionEntity)

    @Query("SELECT * FROM plans ORDER BY revision DESC, id") suspend fun allPlans(): List<PlanEntity>
    @Query("SELECT * FROM planned_entries ORDER BY date, id") suspend fun allPlannedEntries(): List<PlannedEntryEntity>
    @Query("SELECT * FROM plans WHERE id = :id") suspend fun plan(id: String): PlanEntity?
    @Query("SELECT * FROM plans WHERE seriesId = :id ORDER BY revision DESC LIMIT 1") suspend fun latestPlan(id: String): PlanEntity?

    @Query("SELECT * FROM operation_reversals WHERE originalOperationId = :id") suspend fun reversal(id: String): OperationReversalEntity?
    @Query("SELECT * FROM recipe_versions WHERE id = :id") suspend fun recipeVersion(id: String): RecipeVersionEntity?
    @Query("SELECT * FROM recipe_versions WHERE recipeId = :id ORDER BY revision DESC LIMIT 1") suspend fun latestRecipeVersion(id: String): RecipeVersionEntity?
    @Query("SELECT * FROM recipe_output_products WHERE recipeId = :id") suspend fun outputProduct(id: String): RecipeOutputProductEntity?
    @Query("SELECT * FROM productions WHERE operationId = :id") suspend fun production(id: String): ProductionEntity?
    @Query("""SELECT p.* FROM productions p JOIN batches b ON b.id = p.outputBatchId
        WHERE b.versionId = :versionId""") suspend fun productionsForVersion(versionId: String): List<ProductionEntity>
    @Query("""SELECT c.* FROM consumption_events c JOIN operations o ON o.id = c.operationId
        LEFT JOIN operation_reversals r ON r.originalOperationId = c.operationId
        WHERE c.date = :date AND r.originalOperationId IS NULL ORDER BY COALESCE(c.occurredAt,o.recordedAt),c.id""")
    suspend fun activeMeals(date: String): List<ConsumptionEntity>
    @Query("""SELECT * FROM operations WHERE resultId = :date AND kind IN ('DAY_OPEN','DAY_PARTIAL','DAY_COMPLETE') ORDER BY id""")
    suspend fun dayStatusEvents(date: String): List<OperationEntity>
    @Query("SELECT * FROM consumption_events WHERE operationId = :id") suspend fun consumptionForOperation(id: String): ConsumptionEntity?
    @Query("""SELECT COUNT(*) FROM consumption_events c LEFT JOIN operation_reversals r
        ON r.originalOperationId = c.operationId WHERE r.originalOperationId IS NULL""") suspend fun activeConsumptionCount(): Int
    @Query("""SELECT EXISTS(SELECT 1 FROM stock_movements m
        WHERE m.batchId = :batchId AND m.operationId != :original
        AND NOT EXISTS(SELECT 1 FROM operation_reversals r WHERE r.originalOperationId = m.operationId OR r.reversalOperationId = m.operationId))""")
    suspend fun hasActiveDependentMovement(batchId: String, original: String): Boolean

    @Query("""SELECT o.*, r.reversalOperationId AS reversedBy FROM operations o
        LEFT JOIN operation_reversals r ON r.originalOperationId = o.id
        WHERE o.kind IN ('RECEIPT', 'CONSUMPTION', 'TRANSFER', 'WASTE', 'STOCK_COUNT', 'PRODUCTION', 'REVERSAL')
        ORDER BY o.recordedAt DESC, o.id LIMIT 30""") fun observeOperations(): Flow<List<OperationRow>>
    @Query("SELECT * FROM recipe_versions r WHERE r.revision = (SELECT MAX(revision) FROM recipe_versions WHERE recipeId = r.recipeId) ORDER BY r.name")
    fun observeRecipes(): Flow<List<RecipeVersionEntity>>
    @Query("SELECT i.*, v.name AS productName FROM recipe_ingredients i JOIN product_versions v ON v.id = i.productVersionId ORDER BY i.position")
    fun observeIngredients(): Flow<List<IngredientRow>>
    @Query("SELECT * FROM product_versions ORDER BY name, revision DESC") fun observeVersions(): Flow<List<ProductVersionEntity>>

    @Query("SELECT * FROM operations WHERE id = :id") suspend fun operation(id: String): OperationEntity?
    @Query("SELECT * FROM product_versions WHERE id = :id") suspend fun version(id: String): ProductVersionEntity?
    @Query("SELECT * FROM product_versions WHERE productId = :id ORDER BY revision DESC LIMIT 1") suspend fun latestVersion(id: String): ProductVersionEntity?
    @Query("SELECT * FROM product_versions WHERE productId = :id ORDER BY revision") suspend fun versions(id: String): List<ProductVersionEntity>
    @Query("SELECT * FROM storage_locations WHERE id = :id") suspend fun location(id: String): StorageLocationEntity?
    @Query("SELECT * FROM batches WHERE id = :id") suspend fun batch(id: String): BatchEntity?
    @Query("SELECT COALESCE(SUM(delta), 0) FROM stock_movements WHERE batchId = :id") suspend fun balance(id: String): Long
    // v1 movements have no occurredOn; the linked consumption still has its known calendar date.
    @Query("""SELECT MAX(COALESCE(m.occurredOn, c.date)) FROM stock_movements m
        LEFT JOIN consumption_events c ON c.operationId = m.operationId WHERE m.batchId = :id""")
    suspend fun latestMovementDate(id: String): String?
    @Query("SELECT * FROM stock_movements WHERE operationId = :id ORDER BY id") suspend fun movements(id: String): List<StockMovementEntity>
    @Query("SELECT * FROM consumption_events WHERE id = :id") suspend fun consumption(id: String): ConsumptionEntity?
    @Query("SELECT COUNT(*) FROM consumption_events") suspend fun consumptionCount(): Int
    @Query("SELECT COUNT(*) FROM operations") suspend fun operationCount(): Int
    @Query("SELECT * FROM diary_days WHERE date = :date") suspend fun day(date: String): DiaryDayEntity?
    @Query("SELECT * FROM recipe_ingredients WHERE recipeVersionId = :id ORDER BY position") suspend fun ingredients(id: String): List<RecipeIngredientEntity>

    @Query("""SELECT v.*, (SELECT COALESCE(SUM(m.delta), 0) FROM stock_movements m
        JOIN batches b ON b.id = m.batchId JOIN product_versions old ON old.id = b.versionId
        WHERE old.productId = v.productId) AS stockAmount FROM product_versions v
        WHERE v.revision = (SELECT MAX(revision) FROM product_versions WHERE productId = v.productId)
        ORDER BY v.name COLLATE NOCASE, v.id""")
    fun observeCatalog(): Flow<List<CatalogRow>>

    @Query("""SELECT b.*, v.name AS productName, v.baseUnit AS unit, l.name AS locationName,
        (SELECT COALESCE(SUM(delta), 0) FROM stock_movements WHERE batchId = b.id) AS stockAmount,
        EXISTS(SELECT 1 FROM operation_reversals r WHERE r.originalOperationId = b.originOperationId) AS originReversed
        FROM batches b JOIN product_versions v ON v.id = b.versionId
        JOIN storage_locations l ON l.id = b.locationId ORDER BY b.id""")
    fun observeBatches(): Flow<List<BatchRow>>

    @Query("""SELECT b.*, v.name AS productName, v.baseUnit AS unit, l.name AS locationName,
        (SELECT COALESCE(SUM(delta), 0) FROM stock_movements WHERE batchId = b.id) AS stockAmount,
        EXISTS(SELECT 1 FROM operation_reversals r WHERE r.originalOperationId = b.originOperationId) AS originReversed
        FROM batches b JOIN product_versions v ON v.id = b.versionId
        JOIN storage_locations l ON l.id = b.locationId ORDER BY b.id""")
    suspend fun planningBatches(): List<BatchRow>

    @Query("SELECT * FROM storage_locations ORDER BY name") fun observeLocations(): Flow<List<StorageLocationEntity>>
    @Query("""SELECT m.*, v.name AS productName, v.baseUnit AS unit FROM stock_movements m
        JOIN batches b ON b.id = m.batchId JOIN product_versions v ON v.id = b.versionId
        ORDER BY m.recordedAt DESC, m.id DESC LIMIT 30""")
    fun observeMovements(): Flow<List<MovementRow>>
    @Query("""SELECT m.*, v.name AS productName, v.baseUnit AS unit FROM stock_movements m
        JOIN batches b ON b.id = m.batchId JOIN product_versions v ON v.id = b.versionId
        WHERE m.operationId = :id ORDER BY m.id""") suspend fun movementDetails(id: String): List<MovementRow>
}

@Database(entities = [ProductEntity::class, ProductVersionEntity::class, ProductAliasEntity::class,
    StorageLocationEntity::class, OperationEntity::class, BatchEntity::class, StockMovementEntity::class,
    RecipeEntity::class, RecipeVersionEntity::class, RecipeIngredientEntity::class,
    ConsumptionEntity::class, ConsumptionAllocationEntity::class, DiaryDayEntity::class,
    PlanEntity::class, PlannedEntryEntity::class, ReceiptEntity::class, ReceiptLineEntity::class,
    PriceObservationEntity::class, BudgetEntity::class, BodyMeasurementEntity::class,
    TrainingEventEntity::class, TrainingSetEntity::class, OperationReversalEntity::class,
    RecipeOutputProductEntity::class, ProductionEntity::class, PlanSeriesEntity::class], version = 3, exportSchema = true)
@TypeConverters(ValueConverters::class)
abstract class FoodDatabase : RoomDatabase() {
    internal abstract fun foodDao(): FoodDao

    companion object {
        // Separate from future production storage and from immutable UI fixtures. Never seed personal data.
        const val TEST_DATABASE_NAME = "food-local-sandbox.db"
        @Volatile private var instance: FoodDatabase? = null
        fun localSandbox(context: Context): FoodDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FoodDatabase::class.java, TEST_DATABASE_NAME)
                .addMigrations(FoodMigrations.MIGRATION_1_2, FoodMigrations.MIGRATION_2_3)
                // No fallbackToDestructiveMigration: future versions require explicit preserving migrations.
                .build().also { instance = it }
        }
    }
}
