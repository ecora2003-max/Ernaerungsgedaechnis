package de.ernaehrung.demo.data.local

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Keep the v1 fingerprint encoding stable: persisted retry keys must survive upgrades. */
internal object WriteRules {
    fun id(): String = UUID.randomUUID().toString()
    fun fingerprint(vararg parts: Any?): String {
        fun encode(value: Any?): String = when (value) {
            null -> "N"
            is List<*> -> "L${value.size}[${value.joinToString("") { encode(it) }}]"
            else -> value.toString().let { "S${it.length}:$it" }
        }
        return MessageDigest.getInstance("SHA-256").digest(encode(parts.toList()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    suspend fun replay(dao: FoodDao, key: String, fingerprint: String): String? {
        require(key.isNotBlank()) { "Vorgangs-ID fehlt." }
        val existing = dao.operation(key) ?: return null
        require(existing.fingerprint == fingerprint) { "Vorgangs-ID wurde bereits mit anderen Angaben verwendet. Bitte Vorgang prüfen." }
        return existing.resultId
    }
    fun date(value: String) {
        require(runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)) { "Datum im Format JJJJ-MM-TT angeben." }
    }
    fun actualDate(value: String, now: Long) {
        date(value)
        require(LocalDate.parse(value) <= Instant.ofEpochMilli(now).atZone(ZoneId.of("Europe/Berlin")).toLocalDate()) {
            "Ein tatsächliches Ereignis darf nicht in der Zukunft liegen. Dafür die Planung verwenden."
        }
    }
    suspend fun available(dao: FoodDao, batchId: String, amount: Long, date: String, foodUse: Boolean): BatchEntity {
        Amounts.positive(amount)
        val batch = requireNotNull(dao.batch(batchId)) { "Charge nicht gefunden." }
        require(dao.balance(batchId) >= amount) { "Nicht genügend Bestand. Es wurde nichts gebucht." }
        batchDate(dao, batch, date)
        if (foodUse) {
            require(batch.release == ReleaseStatus.RELEASED) { "Charge ist gesperrt oder ungeklärt." }
            require(batch.useBy == null || batch.useBy >= date) { "Verbrauchsdatum überschritten." }
        }
        return batch
    }
    /**
     * A physical batch is usable only after its own creating operation and
     * concrete product version existed. A different operation's timestamp
     * cannot stand in for the batch's provenance.
     */
    suspend fun batchOriginAt(dao: FoodDao, batch: BatchEntity, recordedAt: Long) {
        val origin = requireNotNull(dao.operation(batch.originOperationId)) {
            "Herkunftsvorgang der Charge fehlt."
        }
        require(origin.kind in setOf("RECEIPT", "TRANSFER", "PRODUCTION") &&
            origin.resultId == batch.id) {
            "Herkunftsvorgang gehört nicht zu dieser Charge."
        }
        require(origin.recordedAt in 0L..recordedAt) {
            "Vorgang liegt vor der tatsächlichen Erfassung der Charge."
        }
        val version = requireNotNull(dao.version(batch.versionId)) {
            "Artikelversion der Charge fehlt."
        }
        require(version.recordedAt in 0L..recordedAt) {
            "Vorgang liegt vor der erfassten Artikelversion der Charge."
        }
    }

    suspend fun batchDate(dao: FoodDao, batch: BatchEntity, date: String) {
        require(dao.reversal(batch.originOperationId) == null) { "Die Herkunft dieser Charge wurde storniert. Einen neuen bestätigten Zugang erfassen." }
        require(batch.acquiredOn == null || batch.acquiredOn <= date) { "Ereignis liegt vor dem Zugang." }
        val latest = dao.latestMovementDate(batch.id)
        require(latest == null || latest <= date) { "Spätere Chargenbuchungen vorhanden. Rückdatierung würde die Chronik verfälschen; als heutige Korrektur erfassen." }
    }
}
