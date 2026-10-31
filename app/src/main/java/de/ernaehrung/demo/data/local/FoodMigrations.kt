package de.ernaehrung.demo.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FoodMigrations {
    /** Purely additive. Legacy paid receipts do not prove line-level merchandise basis. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS paid_price_observations (
                id TEXT NOT NULL, operationId TEXT NOT NULL, purchaseOperationId TEXT NOT NULL,
                receiptLineId TEXT NOT NULL, versionId TEXT NOT NULL, retailerId TEXT NOT NULL,
                storeId TEXT, purchasedOn TEXT NOT NULL, purchaseUnits INTEGER NOT NULL,
                unitPriceCents INTEGER NOT NULL, linePaidCents INTEGER NOT NULL,
                allocatedDiscountCents INTEGER NOT NULL, depositPerUnitCents INTEGER,
                basisReason TEXT NOT NULL, recordedAt INTEGER NOT NULL, PRIMARY KEY(id),
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(purchaseOperationId) REFERENCES purchase_events(operationId) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(receiptLineId) REFERENCES receipt_lines(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(versionId) REFERENCES product_versions(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_paid_price_observations_operationId ON paid_price_observations(operationId)")
            db.execSQL("CREATE INDEX index_paid_price_observations_purchaseOperationId ON paid_price_observations(purchaseOperationId)")
            db.execSQL("CREATE UNIQUE INDEX index_paid_price_observations_receiptLineId ON paid_price_observations(receiptLineId)")
            db.execSQL("CREATE INDEX index_paid_price_observations_versionId ON paid_price_observations(versionId)")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE alias_decisions (aliasId TEXT NOT NULL, operationId TEXT NOT NULL, kind TEXT NOT NULL,
                supersedesId TEXT, active INTEGER NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(aliasId),
                FOREIGN KEY(aliasId) REFERENCES product_aliases(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(supersedesId) REFERENCES product_aliases(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_alias_decisions_operationId ON alias_decisions(operationId)")
            db.execSQL("CREATE UNIQUE INDEX index_alias_decisions_supersedesId ON alias_decisions(supersedesId)")
            db.execSQL("""CREATE TABLE receipt_scans (operationId TEXT NOT NULL, rawText TEXT NOT NULL, method TEXT NOT NULL,
                parserVersion TEXT NOT NULL, imageHash TEXT, PRIMARY KEY(operationId),
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_receipt_scans_imageHash ON receipt_scans(imageHash)")
            db.execSQL("""CREATE TABLE receipt_scan_resolutions (scanId TEXT NOT NULL, operationId TEXT NOT NULL,
                receiptId TEXT, reason TEXT NOT NULL, PRIMARY KEY(scanId),
                FOREIGN KEY(scanId) REFERENCES receipt_scans(operationId) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(receiptId) REFERENCES receipts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_receipt_scan_resolutions_operationId ON receipt_scan_resolutions(operationId)")
            db.execSQL("CREATE UNIQUE INDEX index_receipt_scan_resolutions_receiptId ON receipt_scan_resolutions(receiptId)")
        }
    }
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No old receipt, line, price, checklist or movement is silently declared paid.
            db.execSQL("""CREATE TABLE purchase_events (operationId TEXT NOT NULL, receiptId TEXT NOT NULL,
                currency TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(operationId),
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(receiptId) REFERENCES receipts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_purchase_events_receiptId ON purchase_events(receiptId)")
            db.execSQL("""CREATE TABLE receipt_stock_bookings (lineId TEXT NOT NULL, operationId TEXT NOT NULL,
                basisDescription TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(lineId),
                FOREIGN KEY(lineId) REFERENCES receipt_lines(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_receipt_stock_bookings_operationId ON receipt_stock_bookings(operationId)")
            db.execSQL("""CREATE TABLE expense_status_events (operationId TEXT NOT NULL, startsOn TEXT NOT NULL,
                endsOn TEXT NOT NULL, revision INTEGER NOT NULL, previousId TEXT, complete INTEGER NOT NULL,
                dataToken TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(operationId),
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(previousId) REFERENCES expense_status_events(operationId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_expense_status_events_startsOn_endsOn_revision ON expense_status_events(startsOn,endsOn,revision)")
            db.execSQL("CREATE UNIQUE INDEX index_expense_status_events_previousId ON expense_status_events(previousId)")
        }
    }
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Preserve every old value. Nullability now permits genuinely unknown quantities/prices.
            db.execSQL("""CREATE TABLE price_observations_new (id TEXT NOT NULL, versionId TEXT NOT NULL, merchant TEXT,
                observedOn TEXT NOT NULL, amount INTEGER, totalCents INTEGER, source TEXT NOT NULL, quality TEXT NOT NULL,
                wholePack INTEGER, basisConfirmed INTEGER, basisDescription TEXT, validFrom TEXT, validUntil TEXT, currency TEXT,
                supersedesId TEXT, operationId TEXT, recordedAt INTEGER, reason TEXT, PRIMARY KEY(id),
                FOREIGN KEY(versionId) REFERENCES product_versions(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(supersedesId) REFERENCES price_observations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("""INSERT INTO price_observations_new(id,versionId,merchant,observedOn,amount,totalCents,source,quality)
                SELECT id,versionId,merchant,observedOn,amount,totalCents,source,quality FROM price_observations""")
            db.execSQL("DROP TABLE price_observations")
            db.execSQL("ALTER TABLE price_observations_new RENAME TO price_observations")
            db.execSQL("CREATE INDEX index_price_observations_versionId ON price_observations(versionId)")
            db.execSQL("CREATE UNIQUE INDEX index_price_observations_supersedesId ON price_observations(supersedesId)")
            db.execSQL("CREATE UNIQUE INDEX index_price_observations_operationId ON price_observations(operationId)")
            db.execSQL("ALTER TABLE budgets ADD COLUMN label TEXT")
            db.execSQL("ALTER TABLE budgets ADD COLUMN currency TEXT")
            db.execSQL("ALTER TABLE budgets ADD COLUMN supersedesId TEXT REFERENCES budgets(id) ON UPDATE NO ACTION ON DELETE RESTRICT")
            db.execSQL("ALTER TABLE budgets ADD COLUMN operationId TEXT REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT")
            db.execSQL("ALTER TABLE budgets ADD COLUMN recordedAt INTEGER")
            db.execSQL("ALTER TABLE budgets ADD COLUMN reason TEXT")
            db.execSQL("CREATE UNIQUE INDEX index_budgets_supersedesId ON budgets(supersedesId)")
            db.execSQL("CREATE UNIQUE INDEX index_budgets_operationId ON budgets(operationId)")
            db.execSQL("""CREATE TABLE shopping_marks (operationId TEXT NOT NULL, versionId TEXT NOT NULL,
                startsOn TEXT NOT NULL, endsOn TEXT NOT NULL, purchaseOn TEXT NOT NULL, offerId TEXT, checked INTEGER NOT NULL,
                itemToken TEXT NOT NULL, revision INTEGER NOT NULL, previousId TEXT, PRIMARY KEY(operationId),
                FOREIGN KEY(operationId) REFERENCES operations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(versionId) REFERENCES product_versions(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(offerId) REFERENCES price_observations(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(previousId) REFERENCES shopping_marks(operationId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_shopping_marks_versionId_startsOn_endsOn_purchaseOn_revision ON shopping_marks(versionId,startsOn,endsOn,purchaseOn,revision)")
            db.execSQL("CREATE INDEX index_shopping_marks_offerId ON shopping_marks(offerId)")
            db.execSQL("CREATE UNIQUE INDEX index_shopping_marks_previousId ON shopping_marks(previousId)")
        }
    }
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
