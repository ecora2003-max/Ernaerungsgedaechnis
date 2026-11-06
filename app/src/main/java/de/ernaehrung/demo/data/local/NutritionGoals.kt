package de.ernaehrung.demo.data.local

import androidx.room.*
import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Personal ranges, never an inferred requirement or a consumption event. */
@Entity(tableName = "nutrition_goal_runs")
data class NutritionGoalRunEntity(@PrimaryKey val id: String, val kind: String, val payloadHash: String?,
    val outcome: String, val versionId: String?, val recordedAt: Long)

@Entity(tableName = "nutrition_goal_versions", foreignKeys = [
    ForeignKey(entity = NutritionGoalRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = NutritionGoalVersionEntity::class, parentColumns = ["id"], childColumns = ["previousId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = NutritionGoalVersionEntity::class, parentColumns = ["id"], childColumns = ["proposalId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["revision"], unique = true), Index(value = ["runId"], unique = true),
    Index("previousId"), Index("proposalId")])
data class NutritionGoalVersionEntity(@PrimaryKey val id: String, val revision: Int, val previousId: String?,
    val runId: String, val status: String, val proposalId: String?, val effectiveFrom: String,
    val reason: String, val recordedAt: Long, val zoneId: String)

@Entity(tableName = "nutrition_goal_values", primaryKeys = ["versionId", "nutrient"], foreignKeys = [
    ForeignKey(entity = NutritionGoalVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT)
])
data class NutritionGoalValueEntity(val versionId: String, val nutrient: String, val minimumText: String?, val maximumText: String?)

@Dao
internal interface NutritionGoalDao {
    @Query("SELECT * FROM nutrition_goal_runs WHERE id=:id") suspend fun run(id: String): NutritionGoalRunEntity?
    @Insert suspend fun insertRun(row: NutritionGoalRunEntity)
    @Insert suspend fun insertVersion(row: NutritionGoalVersionEntity)
    @Insert suspend fun insertValues(rows: List<NutritionGoalValueEntity>)
    @Query("SELECT * FROM nutrition_goal_versions ORDER BY revision") suspend fun versions(): List<NutritionGoalVersionEntity>
    @Query("SELECT * FROM nutrition_goal_values ORDER BY versionId,nutrient") suspend fun values(): List<NutritionGoalValueEntity>
    @Query("DELETE FROM nutrition_goal_values") suspend fun eraseValues()
    @Query("DELETE FROM nutrition_goal_versions WHERE id=:id") suspend fun eraseVersion(id: String)
    @Query("UPDATE nutrition_goal_runs SET payloadHash=NULL,outcome='ERASED',versionId=NULL WHERE kind!='ERASE'") suspend fun redactRuns()
}

internal data class NutritionGoalRange(val nutrient: Nutrient, val minimumText: String?, val maximumText: String?) {
    val minimum get() = NutritionGoalRules.decimal(minimumText)
    val maximum get() = NutritionGoalRules.decimal(maximumText)
    fun validate() {
        require(minimumText != null || maximumText != null) { "Mindestens eine Bereichsgrenze angeben oder die Zeile leer lassen." }
        val low = minimum; val high = maximum
        require(low == null || high == null || low <= high) { "Untergrenze überschreitet Obergrenze." }
    }
}
internal object NutritionGoalRules {
    const val PROPOSED = "PROPOSED"
    const val CONFIRMED = "CONFIRMED"
    fun decimal(raw: String?): BigDecimal? = raw?.let {
        require(it.length <= 80 && it.trim().matches(Regex("[0-9]+([.,][0-9]+)?"))) {
            "Nichtnegative Dezimalzahl eingeben oder Grenze leer lassen."
        }
        BigDecimal(it.trim().replace(',', '.'))
    }
    fun validate(ranges: List<NutritionGoalRange>) {
        require(ranges.size <= Nutrient.entries.size && ranges.map { it.nutrient }.distinct().size == ranges.size)
        ranges.forEach { it.validate() }
    }
    enum class Result { UNKNOWN, INCOMPLETE, KNOWN_PART_ABOVE, BELOW, WITHIN, ABOVE }
    fun assess(range: NutritionGoalRange, result: NutrientResult, day: DayStatus): Result {
        range.validate()
        val value = result.knownAmount?.takeIf { result.knownCount > 0 && it.signum() >= 0 } ?: return Result.UNKNOWN
        val high = range.maximum
        if (day != DayStatus.COMPLETE || !result.complete || result.gaps.isNotEmpty()) {
            return if (high != null && value > high) Result.KNOWN_PART_ABOVE else Result.INCOMPLETE
        }
        return when {
            range.minimum?.let { value < it } == true -> Result.BELOW
            high != null && value > high -> Result.ABOVE
            else -> Result.WITHIN
        }
    }
}
internal data class NutritionGoalSnapshot(val versions: List<NutritionGoalVersionEntity>, val values: List<NutritionGoalValueEntity>) {
    private val valuesByVersion = values.groupBy { it.versionId }
    init {
        val ordered = versions.sortedBy { it.revision }
        require(ordered.map { it.id }.distinct().size == ordered.size && values.all { row -> versions.any { it.id == row.versionId } })
        ordered.forEachIndexed { index, version ->
            require(version.revision == index + 1 && version.previousId == ordered.getOrNull(index - 1)?.id &&
                version.recordedAt >= 0L && version.reason.isNotBlank() && version.reason.length <= 2000 &&
                version.status in setOf(NutritionGoalRules.PROPOSED, NutritionGoalRules.CONFIRMED)) { "Persönliche Zielhistorie inkonsistent." }
            WriteRules.date(version.effectiveFrom); ZoneId.of(version.zoneId)
            require(index == 0 || ordered[index - 1].recordedAt <= version.recordedAt)
            if (version.status == NutritionGoalRules.CONFIRMED) require(version.proposalId == version.previousId &&
                ordered.getOrNull(index - 1)?.status == NutritionGoalRules.PROPOSED) { "Zielbestätigung ohne erhaltenen Vorschlag." }
            NutritionGoalRules.validate(ranges(version.id))
        }
    }
    val latest get() = versions.maxByOrNull { it.revision }
    fun ranges(id: String) = valuesByVersion[id].orEmpty().map {
        NutritionGoalRange(Nutrient.valueOf(it.nutrient), it.minimumText, it.maximumText)
    }.sortedBy { it.nutrient.ordinal }
    fun versionBinding(id: String): String {
        val v = requireNotNull(versions.singleOrNull { it.id == id })
        return WriteRules.fingerprint("PERSONAL_NUTRITION_GOAL_V1", v.id, v.revision, v.previousId,
            v.runId, v.status, v.proposalId, v.effectiveFrom, v.reason, v.recordedAt, v.zoneId,
            ranges(id).map { listOf(it.nutrient.name, it.minimumText, it.maximumText) })
    }
    val token get() = WriteRules.fingerprint("PERSONAL_NUTRITION_GOALS_SNAPSHOT_V1",
        versions.sortedBy { it.revision }.map { versionBinding(it.id) })
    fun active(date: String, asOf: Long): NutritionGoalVersionEntity? {
        WriteRules.date(date)
        return versions.filter { it.status == NutritionGoalRules.CONFIRMED && it.effectiveFrom <= date &&
            it.recordedAt in 0L..asOf }.maxWithOrNull(compareBy<NutritionGoalVersionEntity> { it.effectiveFrom }.thenBy { it.revision })
    }
}
internal data class NutritionGoalDraftInput(val previousId: String?, val effectiveFrom: String,
    val reason: String, val ranges: List<NutritionGoalRange>)
internal data class NutritionGoalOutcome(val versionId: String?, val erased: Boolean = false)

/** Private profile intent ledger, available only through FoodStore. Separate runs
 * retain value-free privacy tombstones without writing a food/stock operation.
 */
internal class NutritionGoalOperations(private val db: FoodDatabase, private val now: () -> Long,
    private val zone: () -> String = { ZoneId.systemDefault().id }) {
    private val dao get() = db.nutritionGoalDao()
    suspend fun snapshot() = NutritionGoalSnapshot(dao.versions(), dao.values())
    fun watch() = db.invalidationTracker.createFlow("nutrition_goal_runs", "nutrition_goal_versions", "nutrition_goal_values", emitInitialState = true)
        .map { db.withTransaction { snapshot() } }.distinctUntilChanged()
    private suspend fun replay(key: String, kind: String, hash: String): NutritionGoalOutcome? {
        val old = dao.run(key) ?: return null
        require(old.kind == kind) { "Vorgangskennung gehört zu einer anderen Aktion." }
        if (old.outcome == "ERASED") return NutritionGoalOutcome(null, true)
        require(old.payloadHash == hash) { "Gleiche Vorgangskennung mit geänderten Angaben." }
        require(old.versionId != null && dao.versions().any { it.id == old.versionId }) { "Vorgang ohne erhaltene Zielfassung." }
        return NutritionGoalOutcome(old.versionId)
    }
    private fun time(previous: NutritionGoalVersionEntity?): Long = now().also {
        require(it >= 0 && (previous == null || it >= previous.recordedAt)) { "Zeitpunkt liegt vor der bisherigen Zielfassung." }
    }
    private fun effective(date: String, at: Long) {
        WriteRules.date(date)
        require(date >= AppCalendar.today(Instant.ofEpochMilli(at)).toString()) { "Ziele nicht rückwirkend aktivieren. Heute oder ein künftiges Datum wählen." }
    }
    suspend fun propose(key: String, input: NutritionGoalDraftInput, saveRequested: Boolean): NutritionGoalOutcome = db.withTransaction {
        require(saveRequested) { "Vorschlag ausdrücklich lokal speichern." }
        NutritionGoalRules.validate(input.ranges)
        require(input.reason.isNotBlank() && input.reason.length <= 2000) { "Grund für diesen Vorschlag angeben." }
        val hash = WriteRules.fingerprint("PERSONAL_NUTRITION_GOAL_PROPOSAL_V1", input.previousId,
            input.effectiveFrom, input.reason, input.ranges.sortedBy { it.nutrient.ordinal }
                .map { listOf(it.nutrient.name, it.minimumText, it.maximumText) })
        replay(key, "PROPOSE", hash)?.let { return@withTransaction it }
        val previous = snapshot().latest
        require(previous?.id == input.previousId) { "Zielstand inzwischen geändert. Neu lesen und prüfen." }
        val at = time(previous); effective(input.effectiveFrom, at)
        val id = WriteRules.id()
        dao.insertRun(NutritionGoalRunEntity(key, "PROPOSE", hash, "STORED", id, at))
        dao.insertVersion(NutritionGoalVersionEntity(id, Math.addExact(previous?.revision ?: 0, 1), previous?.id,
            key, NutritionGoalRules.PROPOSED, null, input.effectiveFrom, input.reason, at, zone().also { ZoneId.of(it) }))
        dao.insertValues(input.ranges.map { NutritionGoalValueEntity(id, it.nutrient.name, it.minimumText, it.maximumText) })
        NutritionGoalOutcome(id)
    }
    suspend fun confirm(key: String, proposalId: String, reviewedBinding: String, confirmed: Boolean): NutritionGoalOutcome = db.withTransaction {
        require(confirmed) { "Diese persönlichen Zielbereiche separat ausdrücklich bestätigen." }
        val hash = WriteRules.fingerprint("PERSONAL_NUTRITION_GOAL_CONFIRM_V1", proposalId, reviewedBinding)
        replay(key, "CONFIRM", hash)?.let { return@withTransaction it }
        val data = snapshot(); val proposal = requireNotNull(data.latest)
        require(proposal.id == proposalId && proposal.status == NutritionGoalRules.PROPOSED &&
            data.versionBinding(proposalId) == reviewedBinding) { "Vorschlag inzwischen geändert. Aktuellen Stand erneut prüfen." }
        val ranges = data.ranges(proposalId); NutritionGoalRules.validate(ranges)
        val at = time(proposal); effective(proposal.effectiveFrom, at)
        val id = WriteRules.id()
        dao.insertRun(NutritionGoalRunEntity(key, "CONFIRM", hash, "STORED", id, at))
        dao.insertVersion(proposal.copy(id = id, revision = Math.addExact(proposal.revision, 1), previousId = proposal.id,
            runId = key, status = NutritionGoalRules.CONFIRMED, proposalId = proposal.id, recordedAt = at,
            zoneId = zone().also { ZoneId.of(it) }))
        dao.insertValues(ranges.map { NutritionGoalValueEntity(id, it.nutrient.name, it.minimumText, it.maximumText) })
        NutritionGoalOutcome(id)
    }
    suspend fun erase(key: String, reviewedToken: String, confirmed: Boolean): NutritionGoalOutcome = db.withTransaction {
        require(confirmed) { "Persönliche Zielvorschläge und Zielhistorie unwiderruflich löschen bestätigen." }
        dao.run(key)?.let { require(it.kind == "ERASE"); return@withTransaction NutritionGoalOutcome(null, true) }
        val data = snapshot()
        require(data.token == reviewedToken) { "Zielhistorie inzwischen geändert. Löschumfang erneut prüfen." }
        val at = time(data.latest)
        dao.eraseValues()
        data.versions.sortedByDescending { it.revision }.forEach { dao.eraseVersion(it.id) }
        dao.redactRuns()
        dao.insertRun(NutritionGoalRunEntity(key, "ERASE", null, "ERASED", null, at))
        NutritionGoalOutcome(null, true)
    }
}
