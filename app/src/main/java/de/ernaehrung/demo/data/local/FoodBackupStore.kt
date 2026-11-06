package de.ernaehrung.demo.data.local

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import de.ernaehrung.demo.data.backup.*

internal enum class BackupRestoreStatus { RESTORED, ALREADY_PRESENT }
internal data class BackupRestoreResult(val status: BackupRestoreStatus, val digest: String, val rows: Int)

/** Backup SQL is produced only from the exact local schema, never from a file.
 * Restore inserts original IDs in ONE FoodStore transaction. Existing data is
 * never updated/replaced/deleted; a known retry never resurrects later purges.
 */
internal class FoodBackupStore(private val db: FoodDatabase, private val now: () -> Long) {
    companion object {
        internal val FITNESS_TABLES = setOf("health_import_state", "health_import_runs", "health_source_record_versions",
            "body_measurements", "body_measurement_runs", "body_measurement_versions", "training_events", "training_sets",
            "manual_workout_runs", "manual_workout_versions", "manual_workout_sets")
        // Deliberately pinned. Adding a domain table must update and test backup coverage.
        internal val TABLES = setOf("alias_decisions",
            "storage_capacity_reviews",
            "batch_reviews",
            "batches",
            "body_measurement_runs",
            "body_measurement_versions",
            "body_measurements",
            "budgets",
            "consumption_allocations",
            "consumption_events",
            "diary_days",
            "expense_status_events",
            "health_import_runs",
            "health_import_state",
            "health_source_record_versions",
            "manual_workout_runs",
            "manual_workout_sets",
            "manual_workout_versions",
            "operation_reversals",
            "operations",
            "paid_price_observations",
            "plan_executions",
            "plan_positions",
            "plan_series",
            "planned_entries",
            "plans",
            "price_observations",
            "product_aliases",
            "product_versions",
            "productions",
            "products",
            "purchase_events",
            "receipt_lines",
            "receipt_scan_resolutions",
            "receipt_scans",
            "receipt_stock_bookings",
            "receipts",
            "recipe_ingredients",
            "recipe_output_products",
            "recipe_versions",
            "recipes",
            "shopping_marks",
            "stock_movements",
            "storage_locations",
            "training_events",
            "training_sets")
    }
    private data class Column(val name: String, val type: String, val required: Boolean, val primaryKey: Int)
    private val dao = db.foodDao()

    suspend fun capture(applicationId: String, fitnessIncluded: Boolean, confirmed: Boolean): BackupArchive = db.withTransaction {
        require(confirmed) { "Lokale Sicherung und ihren Datenumfang ausdrücklich bestätigen." }
        val sql = db.openHelper.writableDatabase
        val schema = schema(sql)
        requireConsistent(sql)
        var count = 0
        val tables = schema.entries.sortedBy { it.key }.map { (name, columns) ->
            val rows = if (!fitnessIncluded && name in FITNESS_TABLES) emptyList() else readRows(sql, name, columns)
            count += rows.size; require(count <= 1_000_000) { "Sicherung enthält zu viele Datensätze." }
            BackupTable(name, columns.map { it.name }, rows)
        }
        BackupArchive(applicationId, now(), sql.version, identity(sql), fitnessIncluded, tables).also { validate(it, schema) }
    }

    suspend fun validateForPreview(archive: BackupArchive, applicationId: String) = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        require(archive.applicationId == applicationId) { "Sicherung gehört zu einer anderen App-Variante." }
        compatibleArchive(archive, sql, schema(sql))
        archive.databaseVersion != sql.version
    }

    suspend fun restore(key: String, candidate: BackupArchive, expectedDigest: String,
        applicationId: String, confirmed: Boolean): BackupRestoreResult {
        require(confirmed) { "Diese genaue Sicherung ausdrücklich zur Wiederherstellung bestätigen." }
        // Serialize once to freeze caller-owned collections before dispatch/transaction.
        val frozen = BackupArchiveCodec.encode(candidate)
        val digest = BackupArchiveCodec.digest(frozen)
        require(digest == expectedDigest) { "Sicherungsprüfung geändert. Datei erneut prüfen und bestätigen." }
        val original = BackupArchiveCodec.decode(frozen)
        frozen.fill(0)
        val hash = WriteRules.fingerprint("LOCAL_BACKUP_RESTORE_V1", applicationId, digest)
        return db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val schema = schema(sql)
            require(original.applicationId == applicationId) { "Sicherung gehört zu einer anderen App-Variante." }
            // The approved digest/fingerprint above is ALWAYS the original file.
            // Only this pinned, additive 13->14 bridge can add an empty table.
            val archive = compatibleArchive(original, sql, schema)
            WriteRules.replay(dao, key, hash)?.let { return@withTransaction BackupRestoreResult(BackupRestoreStatus.ALREADY_PRESENT, digest, 0) }
            if (dao.backupRestoration(hash) != null)
                return@withTransaction BackupRestoreResult(BackupRestoreStatus.ALREADY_PRESENT, digest, 0)
            // An unchanged existing copy (or a superset with later additions) also needs no writes.
            if (archive.tables.all { alreadyPresent(sql, it, schema.getValue(it.name)) })
                return@withTransaction BackupRestoreResult(BackupRestoreStatus.ALREADY_PRESENT, digest, 0)
            require(schema.keys.none { table -> sql.query("SELECT 1 FROM `${table}` LIMIT 1").use { it.moveToFirst() } }) {
                "Vorhandene lokale Daten bleiben erhalten. Eine abweichende Sicherung kann hier nur in einen leeren Datenbestand übernommen werden." }
            require(key.isNotBlank()) { "Vorgangskennung fehlt." }
            // Keep all FKs enabled. Defer their checks until all original cross/self references exist.
            sql.execSQL("PRAGMA defer_foreign_keys=ON")
            var count = 0
            archive.tables.forEach { table ->
                val columns = schema.getValue(table.name)
                val insert = "INSERT INTO `${table.name}` (${columns.joinToString { "`${it.name}`" }}) VALUES (${columns.joinToString { "?" }})"
                sql.compileStatement(insert).use { statement ->
                    table.rows.forEach { original ->
                        val row = if (table.name == "health_import_state") quarantineHealthControl(original, columns) else original
                        statement.clearBindings()
                        row.forEachIndexed { index, cell -> when (cell) {
                            BackupCell.Unknown -> statement.bindNull(index + 1)
                            is BackupCell.Integer -> statement.bindLong(index + 1, cell.value)
                            is BackupCell.Real -> statement.bindDouble(index + 1, cell.value)
                            is BackupCell.Text -> statement.bindString(index + 1, cell.value)
                            is BackupCell.Blob -> statement.bindBlob(index + 1, cell.bytes())
                        } }
                        statement.executeInsert(); count++
                    }
                }
            }
            requireConsistent(sql)
            // SQLite may coerce a supplied value (for example NaN to NULL).
            // Verify every original typed field before commit; never accept a lossy restore.
            require(archive.tables.all { table ->
                val expected = if (table.name == "health_import_state") table.copy(rows = table.rows.map {
                    quarantineHealthControl(it, schema.getValue(table.name))
                }) else table
                alreadyPresent(sql, expected, schema.getValue(table.name))
            }) { "Sicherungswerte wurden nicht verlustfrei übernommen. Die gesamte Wiederherstellung wurde zurückgerollt." }
            val at = now(); require(at >= 0)
            dao.insertOperation(OperationEntity(key, "LOCAL_BACKUP_RESTORE_V1", hash, at, digest))
            BackupRestoreResult(BackupRestoreStatus.RESTORED, digest, count)
        }
    }

    private fun schema(sql: SupportSQLiteDatabase): Map<String, List<Column>> {
        val actual = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')")
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        require(actual == TABLES) { "Lokale Datenbank benötigt eine ausdrücklich unterstützte Sicherungsversion." }
        return TABLES.associateWith { name -> sql.query("PRAGMA table_info(`${name}`)").use { cursor -> buildList {
            while (cursor.moveToNext()) add(Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0, cursor.getInt(5)))
        } } }
    }
    private fun compatibleArchive(original: BackupArchive, sql: SupportSQLiteDatabase,
        currentSchema: Map<String, List<Column>>): BackupArchive {
        val archive = if (original.databaseVersion == sql.version && original.identityHash == identity(sql)) original
        else {
            require(sql.version == 14 && original.databaseVersion == 13 &&
                original.identityHash == "ddbdbf0fb475ae9d3a5c9a40b4284aa3" &&
                original.tables.map { it.name }.toSet() == TABLES - "storage_capacity_reviews" && original.tables.size == TABLES.size - 1) {
                "Datenbankschema dieser Sicherung wird noch nicht unterstützt. Keine Daten wurden geändert." }
            original.copy(databaseVersion = 14, identityHash = identity(sql), tables = original.tables +
                BackupTable("storage_capacity_reviews", currentSchema.getValue("storage_capacity_reviews").map { it.name }, emptyList()))
        }
        validate(archive, currentSchema)
        return archive
    }
    private fun identity(sql: SupportSQLiteDatabase): String = sql.query("SELECT identity_hash FROM room_master_table WHERE id=42")
        .use { require(it.moveToFirst()); it.getString(0) }
    private fun readRows(sql: SupportSQLiteDatabase, table: String, columns: List<Column>): List<List<BackupCell>> {
        val order = columns.filter { it.primaryKey > 0 }.sortedBy { it.primaryKey }
        require(order.isNotEmpty())
        return sql.query("SELECT ${columns.joinToString { "`${it.name}`" }} FROM `${table}` ORDER BY ${order.joinToString { "`${it.name}`" }}")
            .use { cursor -> buildList { while (cursor.moveToNext()) { require(size < 1_000_000); add(row(cursor, columns.size)) } } }
    }
    private fun row(cursor: Cursor, count: Int): List<BackupCell> = List(count) { column -> when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> BackupCell.Unknown
        Cursor.FIELD_TYPE_INTEGER -> BackupCell.Integer(cursor.getLong(column))
        Cursor.FIELD_TYPE_FLOAT -> BackupCell.Real(cursor.getDouble(column).toRawBits())
        Cursor.FIELD_TYPE_STRING -> BackupCell.Text(cursor.getString(column))
        Cursor.FIELD_TYPE_BLOB -> BackupCell.Blob(cursor.getBlob(column))
        else -> error("Nicht unterstützter SQLite-Feldtyp.")
    } }
    private fun validate(archive: BackupArchive, schema: Map<String, List<Column>>) {
        require(archive.tables.map { it.name }.toSet() == schema.keys && archive.tables.size == schema.size) { "Sicherung enthält nicht die erwarteten Tabellen." }
        archive.tables.forEach { table ->
            val columns = schema.getValue(table.name)
            require(table.columns == columns.map { it.name }) { "Sicherung enthält unerwartete Spalten." }
            require(archive.fitnessIncluded || table.name !in FITNESS_TABLES || table.rows.isEmpty()) { "Fitnessdaten passen nicht zum bestätigten Sicherungsumfang." }
            val keys = columns.indices.filter { columns[it].primaryKey > 0 }
            require(table.rows.map { row -> keys.map { row[it] } }.toSet().size == table.rows.size) { "Sicherung enthält doppelte Identitäten." }
            table.rows.forEach { cells ->
                require(cells.size == columns.size)
                cells.zip(columns).forEach { (cell, column) ->
                    require(if (cell == BackupCell.Unknown) !column.required else when (column.type.uppercase()) {
                        "INTEGER" -> cell is BackupCell.Integer
                        "REAL" -> cell is BackupCell.Real
                        "TEXT" -> cell is BackupCell.Text
                        "BLOB" -> cell is BackupCell.Blob
                        else -> false
                    }) { "Sicherungsfeld passt nicht verlustfrei zum Datenbankschema." }
                }
            }
        }
    }
    private fun alreadyPresent(sql: SupportSQLiteDatabase, table: BackupTable, columns: List<Column>): Boolean {
        if (table.rows.isEmpty()) return true
        val pending = table.rows.toMutableSet()
        sql.query("SELECT ${columns.joinToString { "`${it.name}`" }} FROM `${table.name}`").use { cursor ->
            while (cursor.moveToNext()) pending.remove(row(cursor, columns.size))
        }
        return pending.isEmpty()
    }
    private fun quarantineHealthControl(original: List<BackupCell>, columns: List<Column>): List<BackupCell> = original.toMutableList().apply {
        val enabled = columns.indexOfFirst { it.name == "enabled" }
        val revision = columns.indexOfFirst { it.name == "authorizationRevision" }
        require(enabled >= 0 && revision >= 0)
        this[enabled] = BackupCell.Integer(0)
        this[revision] = BackupCell.Integer(Math.addExact((this[revision] as BackupCell.Integer).value, 1L))
    }
    private fun requireConsistent(sql: SupportSQLiteDatabase) {
        sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Fremdschlüssel der Sicherung sind nicht vollständig erhalten." } }
        sql.query("SELECT batchId FROM stock_movements GROUP BY batchId HAVING SUM(delta)<0").use {
            require(!it.moveToFirst()) { "Sicherung würde negative Bestände erhalten." } }
    }
}
