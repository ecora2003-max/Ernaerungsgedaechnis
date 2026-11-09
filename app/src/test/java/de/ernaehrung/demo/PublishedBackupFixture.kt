package de.ernaehrung.demo

import de.ernaehrung.demo.data.backup.BackupArchive
import org.json.JSONObject

/** Build a synthetic OLD archive using the actual published schema, not a
 * current archive with a misleading old version/hash and newer columns. */
internal fun publishedBackupFixture(current: BackupArchive, version: Int): BackupArchive {
    require(version in 13..18)
    val text = requireNotNull(object {}.javaClass.classLoader!!.getResourceAsStream(
        "de.ernaehrung.demo.data.local.FoodDatabase/$version.json")).bufferedReader().use { it.readText() }
    val schema = JSONObject(text).getJSONObject("database")
    val entities = schema.getJSONArray("entities")
    val tables = (0 until entities.length()).map { index ->
        val entity = entities.getJSONObject(index)
        val table = current.tables.single { it.name == entity.getString("tableName") }
        val fields = entity.getJSONArray("fields")
        val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        val indices = columns.map { name -> table.columns.indexOf(name).also { require(it >= 0) } }
        table.copy(columns = columns, rows = table.rows.map { row -> indices.map { row[it] } })
    }
    return current.copy(databaseVersion = version, identityHash = schema.getString("identityHash"), tables = tables)
}
