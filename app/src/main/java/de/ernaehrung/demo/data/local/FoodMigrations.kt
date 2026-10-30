package de.ernaehrung.demo.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FoodMigrations {
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS plan_positions (id TEXT NOT NULL, seriesId TEXT, createdAt INTEGER NOT NULL,
                PRIMARY KEY(id), FOREIGN KEY(seriesId) REFERENCES plan_series(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE INDEX index_plan_positions_seriesId ON plan_positions(seriesId)")
            db.execSQL("ALTER TABLE planned_entries ADD COLUMN positionId TEXT REFERENCES plan_positions(id) ON UPDATE NO ACTION ON DELETE RESTRICT")
            // No inferred lineage between previously copied versions. Every old row gets its own identity.
            db.execSQL("INSERT INTO plan_positions(id,seriesId,createdAt) SELECT e.id,p.seriesId,p.recordedAt FROM planned_entries e JOIN plans p ON p.id=e.planId")
            db.execSQL("UPDATE planned_entries SET positionId=id")
            db.execSQL("CREATE UNIQUE INDEX index_planned_entries_positionId_id ON planned_entries(positionId,id)")
            db.execSQL("CREATE UNIQUE INDEX index_planned_entries_planId_positionId ON planned_entries(planId,positionId)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS plan_executions (operationId TEXT NOT NULL, actualOperationId TEXT NOT NULL,
                positionId TEXT NOT NULL, entryId TEXT NOT NULL, revision INTEGER NOT NULL, assigned INTEGER NOT NULL,
                previousId TEXT, reason TEXT NOT NULL, PRIMARY KEY(operationId),
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(actualOperationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(positionId,entryId) REFERENCES planned_entries(positionId,id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(previousId) REFERENCES plan_executions(operationId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_plan_executions_actualOperationId_revision ON plan_executions(actualOperationId,revision)")
            db.execSQL("CREATE INDEX index_plan_executions_positionId_entryId ON plan_executions(positionId,entryId)")
            db.execSQL("CREATE INDEX index_plan_executions_entryId ON plan_executions(entryId)")
            db.execSQL("CREATE UNIQUE INDEX index_plan_executions_previousId ON plan_executions(previousId)")
        }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS plan_series (id TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("ALTER TABLE plans ADD COLUMN seriesId TEXT REFERENCES plan_series(id) ON UPDATE NO ACTION ON DELETE RESTRICT")
            db.execSQL("ALTER TABLE plans ADD COLUMN title TEXT")
            db.execSQL("ALTER TABLE plans ADD COLUMN changeReason TEXT")
            // Legacy rows had no shared identity. Never guess that overlapping plans replace each other.
            db.execSQL("INSERT INTO plan_series(id, createdAt) SELECT id, recordedAt FROM plans")
            db.execSQL("UPDATE plans SET seriesId = id")
            db.execSQL("CREATE UNIQUE INDEX index_plans_seriesId_revision ON plans(seriesId, revision)")
            db.execSQL("ALTER TABLE planned_entries ADD COLUMN preparationEntryId TEXT REFERENCES planned_entries(id) ON UPDATE NO ACTION ON DELETE RESTRICT")
            db.execSQL("ALTER TABLE planned_entries ADD COLUMN label TEXT")
            db.execSQL("CREATE INDEX index_planned_entries_preparationEntryId ON planned_entries(preparationEntryId)")
        }
    }
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Additive only. Old timestamps/provenance remain unknown rather than being invented.
            db.execSQL("ALTER TABLE batches ADD COLUMN parentBatchId TEXT REFERENCES batches(id) ON UPDATE NO ACTION ON DELETE RESTRICT")
            db.execSQL("CREATE INDEX index_batches_parentBatchId ON batches(parentBatchId)")
            db.execSQL("ALTER TABLE stock_movements ADD COLUMN occurredOn TEXT")
            db.execSQL("""CREATE TABLE IF NOT EXISTS operation_reversals (
                originalOperationId TEXT NOT NULL, reversalOperationId TEXT NOT NULL, date TEXT NOT NULL, reason TEXT NOT NULL,
                PRIMARY KEY(originalOperationId),
                FOREIGN KEY(originalOperationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(reversalOperationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_operation_reversals_reversalOperationId ON operation_reversals(reversalOperationId)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS recipe_output_products (
                recipeId TEXT NOT NULL, productId TEXT NOT NULL, PRIMARY KEY(recipeId),
                FOREIGN KEY(recipeId) REFERENCES recipes(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(productId) REFERENCES products(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_recipe_output_products_productId ON recipe_output_products(productId)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS productions (
                operationId TEXT NOT NULL, recipeVersionId TEXT NOT NULL, outputBatchId TEXT NOT NULL,
                actualPortions INTEGER NOT NULL, producedOn TEXT NOT NULL, storageNote TEXT NOT NULL, deviationNote TEXT,
                PRIMARY KEY(operationId),
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(recipeVersionId) REFERENCES recipe_versions(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(outputBatchId) REFERENCES batches(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE INDEX index_productions_recipeVersionId ON productions(recipeVersionId)")
            db.execSQL("CREATE UNIQUE INDEX index_productions_outputBatchId ON productions(outputBatchId)")
        }
    }
}
