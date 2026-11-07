package de.ernaehrung.demo.data.local

import androidx.room.*
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Private intent history, not a food operation. Purge retains value-free run
 * tombstones so an old proposal/confirmation cannot resurrect personal values.
 */
@Entity(tableName = "personal_restrictions", foreignKeys = [
    ForeignKey(entity = PersonalRestrictionEntity::class, parentColumns = ["id"], childColumns = ["previousId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = PersonalRestrictionEntity::class, parentColumns = ["id"], childColumns = ["proposalId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["revision"], unique = true), Index(value = ["operationId"], unique = true),
    Index("previousId"), Index("proposalId")])
data class PersonalRestrictionEntity(@PrimaryKey val id: String, val revision: Int, val previousId: String?,
    val operationId: String, val kind: String, val status: String, val proposalId: String?,
    val rawText: String?, val reason: String?, val zoneId: String?, val payloadHash: String?, val recordedAt: Long)

@Dao
internal interface PersonalRestrictionDao {
    @Query("SELECT * FROM personal_restrictions ORDER BY revision") suspend fun history(): List<PersonalRestrictionEntity>
    @Query("SELECT * FROM personal_restrictions WHERE operationId=:key") suspend fun run(key: String): PersonalRestrictionEntity?
    @Insert suspend fun insert(row: PersonalRestrictionEntity)
    @Query("UPDATE personal_restrictions SET status='ERASED',rawText=NULL,reason=NULL,zoneId=NULL,payloadHash=NULL") suspend fun redact()
}

internal object PersonalRestrictionRules {
    const val PROPOSE = "PROPOSE"
    const val CONFIRM = "CONFIRM"
    const val ERASE = "ERASE"
    const val PROPOSED = "PROPOSED"
    const val CONFIRMED = "CONFIRMED"
    const val ERASED = "ERASED"
    fun tokens(raw: String): Set<String> {
        require(raw.length <= 4096) { "Vorgaben auf höchstens 4096 Zeichen begrenzen." }
        if (raw.isBlank()) return emptySet()
        val codes = raw.split(',').map(String::trim)
        require(codes.size <= 64 && codes.all { it.isNotEmpty() && RecipeDraftHostGate.isSupportedRestriction(it) }) {
            "Vorgaben als unterstützte Kennungen eingeben: ALLERGEN:SOY, FOOD:food-id oder DIET:VEGAN. Leere Einträge entfernen."
        }
        return codes.toSet()
    }
    fun reason(raw: String) { require(raw.isNotBlank() && raw.length <= 2000) { "Grund angeben (höchstens 2000 Zeichen)." } }
    fun sourceBinding(id: String?) = id?.let { WriteRules.fingerprint("PERSONAL_RESTRICTIONS_SOURCE_V1", it) }
    /** Internal action scope only: the external v1 preview digest stays unchanged.
     * Before any confirmed/erased profile the legacy signature remains exact.
     */
    fun actionDigest(previewDigest: String, binding: String?): String {
        require(previewDigest.matches(Regex("[0-9a-f]{64}")))
        return if (binding == null) previewDigest
        else WriteRules.fingerprint("PERSONAL_RESTRICTIONS_RECIPE_ACTION_V1", previewDigest, binding)
    }
}

internal class PersonalRestrictionSnapshot(val history: List<PersonalRestrictionEntity>) {
    val latest get() = history.lastOrNull()
    val active get() = history.lastOrNull { it.status == PersonalRestrictionRules.CONFIRMED }
    val tokens get() = active?.rawText?.let(PersonalRestrictionRules::tokens).orEmpty()
    // Bind only immutable random identity. No hash of erased medical values is
    // retained. An erase era also invalidates pre-profile approvals after reboot.
    val binding get() = PersonalRestrictionRules.sourceBinding(active?.id ?:
        history.lastOrNull { it.kind == PersonalRestrictionRules.ERASE }?.id)
    val token get() = WriteRules.fingerprint("PERSONAL_RESTRICTIONS_REVIEW_V1", history.map { row ->
        listOf(row.id, row.revision, row.previousId, row.operationId, row.kind, row.status, row.proposalId,
            row.rawText, row.reason, row.zoneId, row.payloadHash, row.recordedAt)
    })
    fun versionBinding(id: String) = WriteRules.fingerprint("PERSONAL_RESTRICTIONS_VERSION_V1",
        history.single { it.id == id }.let { listOf(it.id,it.revision,it.previousId,it.kind,it.status,
            it.rawText,it.reason,it.zoneId,it.payloadHash,it.recordedAt) })
    init {
        val ids = history.map { it.id }
        require(ids.distinct().size == history.size && history.map { it.operationId }.distinct().size == history.size)
        history.forEachIndexed { index, row ->
            require(row.revision == index + 1 && row.recordedAt >= 0 && row.id.isNotBlank() && row.operationId.isNotBlank())
            val previous = history.getOrNull(index - 1)
            require(row.previousId == previous?.id && (previous == null || row.recordedAt >= previous.recordedAt))
            require(row.kind in setOf(PersonalRestrictionRules.PROPOSE, PersonalRestrictionRules.CONFIRM, PersonalRestrictionRules.ERASE))
            when (row.kind) {
                PersonalRestrictionRules.PROPOSE -> require(row.proposalId == null)
                PersonalRestrictionRules.CONFIRM -> require(row.proposalId == previous?.id && previous?.kind == PersonalRestrictionRules.PROPOSE)
                PersonalRestrictionRules.ERASE -> require(row.status == PersonalRestrictionRules.ERASED && row.proposalId == null)
            }
            if (row.status == PersonalRestrictionRules.ERASED) {
                require(row.rawText == null && row.reason == null && row.zoneId == null && row.payloadHash == null)
            } else {
                require(row.kind != PersonalRestrictionRules.ERASE && row.payloadHash?.matches(Regex("[0-9a-f]{64}")) == true)
                PersonalRestrictionRules.tokens(requireNotNull(row.rawText))
                PersonalRestrictionRules.reason(requireNotNull(row.reason)); ZoneId.of(requireNotNull(row.zoneId))
                if (row.kind == PersonalRestrictionRules.PROPOSE) {
                    require(row.status == PersonalRestrictionRules.PROPOSED && row.proposalId == null)
                    require(row.payloadHash == WriteRules.fingerprint("PERSONAL_RESTRICTIONS_PROPOSE_V1", row.previousId,row.rawText,row.reason))
                } else {
                    require(row.status == PersonalRestrictionRules.CONFIRMED && row.proposalId == previous?.id &&
                        previous?.kind == PersonalRestrictionRules.PROPOSE && previous.status == PersonalRestrictionRules.PROPOSED &&
                        row.rawText == previous.rawText && row.reason == previous.reason)
                    require(row.payloadHash == WriteRules.fingerprint("PERSONAL_RESTRICTIONS_CONFIRM_V1", row.proposalId,versionBinding(row.proposalId!!)))
                }
            }
        }
        val erasedThrough = history.indexOfLast { it.kind == PersonalRestrictionRules.ERASE }
        require(history.take(erasedThrough + 1).all { it.status == PersonalRestrictionRules.ERASED } &&
            history.drop(erasedThrough + 1).none { it.status == PersonalRestrictionRules.ERASED })
    }
}

internal data class PersonalRestrictionInput(val previousId: String?, val rawText: String, val reason: String)
internal data class PersonalRestrictionOutcome(val versionId: String?, val erased: Boolean = false, val revision: Int = 0)
internal class PersonalRestrictionOperations(private val db: FoodDatabase, private val now: () -> Long,
    private val zone: () -> String = { ZoneId.systemDefault().id }) {
    private val dao get() = db.personalRestrictionDao()
    suspend fun snapshot() = PersonalRestrictionSnapshot(dao.history())
    fun watch() = db.invalidationTracker.createFlow("personal_restrictions", emitInitialState = true)
        .map { db.withTransaction { snapshot() } }.distinctUntilChanged()
    private suspend fun replay(key: String, kind: String, hash: String?): PersonalRestrictionOutcome? {
        val old = dao.run(key) ?: return null
        require(old.kind == kind) { "Vorgangskennung gehört zu einer anderen Aktion." }
        if (old.status == PersonalRestrictionRules.ERASED) return PersonalRestrictionOutcome(null, true, old.revision)
        require(old.payloadHash == hash) { "Gleiche Vorgangskennung mit geänderten Vorgaben." }
        return PersonalRestrictionOutcome(old.id, revision = old.revision)
    }
    private fun time(previous: PersonalRestrictionEntity?) = now().also {
        require(it >= 0 && (previous == null || it >= previous.recordedAt)) { "Zeitpunkt liegt vor dem bisherigen Profilstand." }
    }
    suspend fun propose(key: String, input: PersonalRestrictionInput, requested: Boolean) = db.withTransaction {
        require(requested) { "Vorschlag ausdrücklich lokal speichern." }
        val hash = WriteRules.fingerprint("PERSONAL_RESTRICTIONS_PROPOSE_V1", input.previousId,input.rawText,input.reason)
        replay(key,PersonalRestrictionRules.PROPOSE,hash)?.let { return@withTransaction it }
        require(key.isNotBlank()); PersonalRestrictionRules.tokens(input.rawText); PersonalRestrictionRules.reason(input.reason)
        val previous = snapshot().latest
        require(previous?.id == input.previousId) { "Vorgaben inzwischen geändert. Neu lesen und prüfen." }
        val row = PersonalRestrictionEntity(WriteRules.id(), Math.addExact(previous?.revision ?: 0,1),previous?.id,key,
            PersonalRestrictionRules.PROPOSE,PersonalRestrictionRules.PROPOSED,null,input.rawText,input.reason,
            zone().also { ZoneId.of(it) },hash,time(previous))
        dao.insert(row); PersonalRestrictionOutcome(row.id, revision = row.revision)
    }
    suspend fun confirm(key: String, proposalId: String, binding: String, confirmed: Boolean) = db.withTransaction {
        require(confirmed) { "Eigene Vorgaben separat ausdrücklich bestätigen." }
        val hash = WriteRules.fingerprint("PERSONAL_RESTRICTIONS_CONFIRM_V1",proposalId,binding)
        replay(key,PersonalRestrictionRules.CONFIRM,hash)?.let { return@withTransaction it }
        require(key.isNotBlank())
        val data = snapshot(); val proposed = requireNotNull(data.latest)
        require(proposed.id == proposalId && proposed.status == PersonalRestrictionRules.PROPOSED && data.versionBinding(proposalId) == binding) {
            "Vorschlag geändert. Aktuelle Fassung erneut prüfen."
        }
        val row = proposed.copy(id = WriteRules.id(), revision = Math.addExact(proposed.revision,1), previousId = proposed.id,
            operationId = key, kind = PersonalRestrictionRules.CONFIRM,status = PersonalRestrictionRules.CONFIRMED,
            proposalId = proposed.id,payloadHash = hash,recordedAt = time(proposed),zoneId = zone().also { ZoneId.of(it) })
        dao.insert(row); PersonalRestrictionOutcome(row.id, revision = row.revision)
    }
    suspend fun erase(key: String, reviewedToken: String, confirmed: Boolean) = db.withTransaction {
        require(confirmed) { "Diesen privaten Profilverlauf ausdrücklich löschen." }
        replay(key,PersonalRestrictionRules.ERASE,null)?.let { return@withTransaction it }
        require(key.isNotBlank())
        val data = snapshot(); require(data.token == reviewedToken) { "Löschumfang geändert. Neu prüfen." }
        val previous = data.latest; val at = time(previous)
        dao.redact()
        val row = PersonalRestrictionEntity(WriteRules.id(),Math.addExact(previous?.revision ?: 0,1),previous?.id,key,
            PersonalRestrictionRules.ERASE,PersonalRestrictionRules.ERASED,null,null,null,null,null,at)
        dao.insert(row); PersonalRestrictionOutcome(row.id,true,row.revision)
    }
}
