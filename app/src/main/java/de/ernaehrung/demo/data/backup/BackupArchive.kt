package de.ernaehrung.demo.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Typed SQLite cells: no JSON-number coercion, null substitution or decimal rounding. */
internal sealed interface BackupCell {
    data object Unknown : BackupCell
    data class Integer(val value: Long) : BackupCell
    data class Real(val bits: Long) : BackupCell { val value get() = Double.fromBits(bits) }
    data class Text(val value: String) : BackupCell
    class Blob(value: ByteArray) : BackupCell {
        private val bytes = value.copyOf()
        fun bytes() = bytes.copyOf()
        override fun equals(other: Any?) = other is Blob && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}
internal data class BackupTable(val name: String, val columns: List<String>, val rows: List<List<BackupCell>>)
internal data class BackupArchive(val applicationId: String, val createdAt: Long, val databaseVersion: Int,
    val identityHash: String, val fitnessIncluded: Boolean, val tables: List<BackupTable>)

/** Version 1 preserves field types and the exact schema identity. No SQL from a file is executed. */
internal object BackupArchiveCodec {
    const val VERSION = 1
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_FIELD_BYTES = 8 * 1024 * 1024
    private const val MAX_TABLES = 128
    private const val MAX_COLUMNS = 128
    private const val MAX_ROWS = 1_000_000
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun encode(archive: BackupArchive): ByteArray {
        require(archive.createdAt >= 0 && archive.databaseVersion > 0)
        require(archive.applicationId.isNotBlank() && archive.identityHash.isNotBlank())
        require(archive.tables.size in 1..MAX_TABLES)
        require(archive.tables.map { it.name }.distinct().size == archive.tables.size)
        var rows = 0L
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(VERSION); out.text(archive.applicationId); out.writeLong(archive.createdAt)
            out.writeInt(archive.databaseVersion); out.text(archive.identityHash); out.writeBoolean(archive.fitnessIncluded)
            out.writeInt(archive.tables.size)
            archive.tables.forEach { table ->
                require(identifier.matches(table.name))
                require(table.columns.size in 1..MAX_COLUMNS && table.columns.distinct().size == table.columns.size)
                require(table.columns.all(identifier::matches))
                rows += table.rows.size; require(rows <= MAX_ROWS)
                out.text(table.name); out.writeInt(table.columns.size); table.columns.forEach { out.text(it) }
                out.writeInt(table.rows.size)
                table.rows.forEach { row ->
                    require(row.size == table.columns.size)
                    row.forEach { cell -> when (cell) {
                        BackupCell.Unknown -> out.writeByte(0)
                        is BackupCell.Integer -> { out.writeByte(1); out.writeLong(cell.value) }
                        is BackupCell.Real -> { out.writeByte(2); out.writeLong(cell.bits) }
                        is BackupCell.Text -> { out.writeByte(3); out.text(cell.value) }
                        is BackupCell.Blob -> { out.writeByte(4); out.bytes(cell.bytes()) }
                    } }
                    require(buffer.size() <= MAX_BYTES) { "Sicherung überschreitet die unterstützte Größe." }
                }
            }
        }
        return buffer.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): BackupArchive {
        require(bytes.size in 1..MAX_BYTES) { "Sicherungsinhalt hat keine unterstützte Größe." }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == VERSION) { "Nicht unterstützte Sicherungsversion." }
        val app = input.text(); val at = input.readLong(); val version = input.readInt(); val hash = input.text()
        require(app.isNotBlank() && hash.isNotBlank() && at >= 0L && version > 0)
        val fitnessByte = input.readUnsignedByte(); require(fitnessByte in 0..1)
        val count = input.readInt(); require(count in 1..MAX_TABLES)
        var totalRows = 0L
        val tables = List(count) {
            val name = input.text(); require(identifier.matches(name))
            val columns = input.readInt(); require(columns in 1..MAX_COLUMNS)
            val names = List(columns) { input.text().also { require(identifier.matches(it)) } }
            require(names.distinct().size == names.size)
            val rowCount = input.readInt(); require(rowCount >= 0)
            totalRows += rowCount; require(totalRows <= MAX_ROWS)
            // Even all-null cells require one byte each; reject forged huge counts before allocation.
            require(rowCount.toLong() * columns <= input.available().toLong())
            BackupTable(name, names, List(rowCount) { List(columns) {
                when (input.readUnsignedByte()) {
                    0 -> BackupCell.Unknown
                    1 -> BackupCell.Integer(input.readLong())
                    2 -> BackupCell.Real(input.readLong())
                    3 -> BackupCell.Text(input.text())
                    4 -> BackupCell.Blob(input.bytes())
                    else -> error("Unbekannter SQLite-Feldtyp in Sicherung.")
                }
            } })
        }
        require(tables.map { it.name }.distinct().size == tables.size)
        require(input.available() == 0) { "Unerwartete zusätzliche Sicherungsdaten." }
        return BackupArchive(app, at, version, hash, fitnessByte == 1, tables)
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun DataOutputStream.bytes(value: ByteArray) {
        require(value.size <= MAX_FIELD_BYTES)
        writeInt(value.size); write(value)
    }
    private fun DataOutputStream.text(value: String) {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        bytes(ByteArray(encoded.remaining()).also { encoded.get(it) })
    }
    private fun DataInputStream.bytes(): ByteArray {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES && size <= available())
        return ByteArray(size).also { readFully(it) }
    }
    private fun DataInputStream.text(): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes())).toString()
}
