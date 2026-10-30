package de.ernaehrung.demo.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class CatalogRow(@Embedded val version: ProductVersionEntity, val stockAmount: Long)
data class BatchRow(@Embedded val batch: BatchEntity, val productName: String, val unit: BaseUnit,
    val locationName: String, val stockAmount: Long)
data class MovementRow(@Embedded val movement: StockMovementEntity, val productName: String, val unit: BaseUnit)

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
    @Insert suspend fun insertPlan(value: PlanEntity)
    @Insert suspend fun insertPlannedEntry(value: PlannedEntryEntity)
    @Insert suspend fun insertRecipe(value: RecipeEntity)
    @Insert suspend fun insertRecipeVersion(value: RecipeVersionEntity)
    @Insert suspend fun insertIngredient(value: RecipeIngredientEntity)

    @Query("SELECT * FROM operations WHERE id = :id") suspend fun operation(id: String): OperationEntity?
    @Query("SELECT * FROM product_versions WHERE id = :id") suspend fun version(id: String): ProductVersionEntity?
    @Query("SELECT * FROM product_versions WHERE productId = :id ORDER BY revision DESC LIMIT 1") suspend fun latestVersion(id: String): ProductVersionEntity?
    @Query("SELECT * FROM product_versions WHERE productId = :id ORDER BY revision") suspend fun versions(id: String): List<ProductVersionEntity>
    @Query("SELECT * FROM storage_locations WHERE id = :id") suspend fun location(id: String): StorageLocationEntity?
    @Query("SELECT * FROM batches WHERE id = :id") suspend fun batch(id: String): BatchEntity?
    @Query("SELECT COALESCE(SUM(delta), 0) FROM stock_movements WHERE batchId = :id") suspend fun balance(id: String): Long
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
        (SELECT COALESCE(SUM(delta), 0) FROM stock_movements WHERE batchId = b.id) AS stockAmount
        FROM batches b JOIN product_versions v ON v.id = b.versionId
        JOIN storage_locations l ON l.id = b.locationId ORDER BY b.id""")
    fun observeBatches(): Flow<List<BatchRow>>

    @Query("SELECT * FROM storage_locations ORDER BY name") fun observeLocations(): Flow<List<StorageLocationEntity>>
    @Query("""SELECT m.*, v.name AS productName, v.baseUnit AS unit FROM stock_movements m
        JOIN batches b ON b.id = m.batchId JOIN product_versions v ON v.id = b.versionId
        ORDER BY m.recordedAt DESC, m.id DESC LIMIT 30""")
    fun observeMovements(): Flow<List<MovementRow>>
}

@Database(entities = [ProductEntity::class, ProductVersionEntity::class, ProductAliasEntity::class,
    StorageLocationEntity::class, OperationEntity::class, BatchEntity::class, StockMovementEntity::class,
    RecipeEntity::class, RecipeVersionEntity::class, RecipeIngredientEntity::class,
    ConsumptionEntity::class, ConsumptionAllocationEntity::class, DiaryDayEntity::class,
    PlanEntity::class, PlannedEntryEntity::class, ReceiptEntity::class, ReceiptLineEntity::class,
    PriceObservationEntity::class, BudgetEntity::class, BodyMeasurementEntity::class,
    TrainingEventEntity::class, TrainingSetEntity::class], version = 1, exportSchema = true)
@TypeConverters(ValueConverters::class)
abstract class FoodDatabase : RoomDatabase() {
    internal abstract fun foodDao(): FoodDao

    companion object {
        // Separate from future production storage and from immutable UI fixtures. Never seed personal data.
        const val TEST_DATABASE_NAME = "food-local-sandbox.db"
        @Volatile private var instance: FoodDatabase? = null
        fun localSandbox(context: Context): FoodDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FoodDatabase::class.java, TEST_DATABASE_NAME)
                // No fallbackToDestructiveMigration: future versions require explicit preserving migrations.
                .build().also { instance = it }
        }
    }
}
