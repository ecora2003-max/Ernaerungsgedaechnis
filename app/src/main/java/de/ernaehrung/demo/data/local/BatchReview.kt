package de.ernaehrung.demo.data.local

import androidx.room.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** An immutable, explicitly reviewed description. No quantity, location or identity rewrite. */
@Entity(tableName = "batch_reviews", foreignKeys = [
    ForeignKey(entity = BatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BatchReviewEntity::class, parentColumns = ["batchId", "id"], childColumns = ["batchId", "previousId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["batchId", "revision"], unique = true), Index(value = ["batchId", "id"], unique = true),
    Index(value = ["batchId", "previousId"]), Index(value = ["previousId"], unique = true), Index(value = ["operationId"], unique = true)])
data class BatchReviewEntity(@PrimaryKey val id: String, val batchId: String, val revision: Int,
    val previousId: String?, val operationId: String, val acquiredOn: String?, val bestBefore: String?, val useBy: String?,
    val openedAt: Long?, val thawedAt: Long?, val openedAtText: String?, val thawedAtText: String?,
    val release: ReleaseStatus, val safetyNote: String?,
    val reason: String, val conditionsConfirmed: Boolean, val source: Source, val quality: Quality, val recordedAt: Long)

// CASE (not COALESCE) retains an explicitly cleared value in the latest review.
internal const val CURRENT_BATCHES_SQL = """SELECT b.id, b.versionId, b.locationId, b.originOperationId,
    CASE WHEN v.id IS NULL THEN b.acquiredOn ELSE v.acquiredOn END AS acquiredOn,
    CASE WHEN v.id IS NULL THEN b.bestBefore ELSE v.bestBefore END AS bestBefore,
    CASE WHEN v.id IS NULL THEN b.useBy ELSE v.useBy END AS useBy,
    CASE WHEN v.id IS NULL THEN b.openedAt ELSE v.openedAt END AS openedAt,
    CASE WHEN v.id IS NULL THEN b.thawedAt ELSE v.thawedAt END AS thawedAt,
    CASE WHEN v.id IS NULL THEN b.`release` ELSE v.`release` END AS `release`,
    CASE WHEN v.id IS NULL THEN b.safetyNote ELSE v.safetyNote END AS safetyNote,
    b.recipeVersionId, b.parentBatchId FROM batches b LEFT JOIN batch_reviews v
    ON v.batchId=b.id AND v.revision=(SELECT MAX(revision) FROM batch_reviews WHERE batchId=b.id)"""

@DatabaseView(value = CURRENT_BATCHES_SQL, viewName = "current_batches")
data class CurrentBatchView(@Embedded val batch: BatchEntity)

internal fun BatchEntity.withReview(review: BatchReviewEntity?) = if (review == null) this else copy(
    acquiredOn = review.acquiredOn, bestBefore = review.bestBefore, useBy = review.useBy,
    openedAt = review.openedAt, thawedAt = review.thawedAt, release = review.release, safetyNote = review.safetyNote)

internal data class BatchReviewInput(val batchId: String, val expectedPreviousId: String?,
    val acquiredOn: String?, val bestBefore: String?, val useBy: String?, val openedAtText: String?, val thawedAtText: String?,
    val release: ReleaseStatus, val safetyNote: String?, val reason: String, val conditionsConfirmed: Boolean)
internal data class BatchReviewSnapshot(val original: BatchEntity, val current: BatchEntity,
    val history: List<BatchReviewEntity>, val product: ProductVersionEntity, val location: StorageLocationEntity,
    val amount: Long, val originReversed: Boolean) {
    val latest get() = history.maxByOrNull { it.revision }
}

/** Explicit offset required. Date-only input must not invent midnight or an offset. */
internal object BatchReviewTimes {
    fun parse(value: String): Long? {
        if (value.isBlank()) return null
        val instant = OffsetDateTime.parse(value.trim()).toInstant()
        require(instant.nano % 1_000_000 == 0) { "Chargenzeiten höchstens mit Millisekunden angeben; keine stille Rundung." }
        return instant.toEpochMilli().also { require(it >= 0L) { "Chargenzeit vor unterstütztem Zeitbereich." } }
    }
    fun format(value: Long?): String = value?.let { Instant.ofEpochMilli(it).atOffset(java.time.ZoneOffset.UTC).toString() }.orEmpty()
}

internal class BatchReviewOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    fun watch(id: String) = db.invalidationTracker.createFlow("batches", "batch_reviews", "product_versions",
        "storage_locations", "operations", "operation_reversals", "stock_movements").map {
        db.withTransaction {
            val original = requireNotNull(dao.originalBatch(id)) { "Charge nicht gefunden." }
            val history = dao.batchReviews(id)
            BatchReviewSnapshot(original, original.withReview(history.maxByOrNull { it.revision }), history,
                requireNotNull(dao.version(original.versionId)), requireNotNull(dao.location(original.locationId)),
                dao.balance(id), dao.reversal(original.originOperationId) != null)
        }
    }.distinctUntilChanged()

    suspend fun save(key: String, input: BatchReviewInput, confirmed: Boolean): String = db.withTransaction {
        require(confirmed) { "Diese Chargenangaben ausdrücklich bestätigen." }
        require(input.reason.isNotBlank() && input.reason.length <= 2_000) { "Nachvollziehbaren Prüfgrund angeben (höchstens 2000 Zeichen)." }
        require(input.safetyNote == null || input.safetyNote.length <= 2_000) { "Chargennotiz ist zu lang." }
        listOf(input.acquiredOn, input.bestBefore, input.useBy).forEach { it?.let(WriteRules::date) }
        val openedAt = BatchReviewTimes.parse(input.openedAtText.orEmpty())
        val thawedAt = BatchReviewTimes.parse(input.thawedAtText.orEmpty())
        val hash = WriteRules.fingerprint("BATCH_REVIEW_V1", input.batchId, input.expectedPreviousId,
            input.acquiredOn, input.bestBefore, input.useBy, input.openedAtText, input.thawedAtText, input.release,
            input.safetyNote, input.reason, input.conditionsConfirmed)
        WriteRules.replay(dao, key, hash)?.let { return@withTransaction it }
        val original = requireNotNull(dao.originalBatch(input.batchId)) { "Charge nicht gefunden." }
        require(dao.reversal(original.originOperationId) == null) { "Herkunft storniert; nur die unveränderte Historie bleibt lesbar." }
        val previous = dao.latestBatchReview(original.id)
        require(previous?.id == input.expectedPreviousId) { "Chargenprüfung inzwischen geändert. Neu lesen und erneut prüfen." }
        val at = now()
        require(at >= 0L && (previous == null || previous.recordedAt <= at)) { "Prüfzeit liegt vor der letzten Chargenprüfung." }
        WriteRules.batchOriginAt(dao, original, at)
        val creation = requireNotNull(dao.operation(original.originOperationId)).recordedAt
        val createdOn = AppCalendar.today(Instant.ofEpochMilli(creation))
        input.acquiredOn?.let { require(!LocalDate.parse(it).isAfter(createdOn)) { "Zugangsdatum liegt nach der bereits aufgezeichneten Chargenentstehung." } }
        listOf(openedAt, thawedAt).forEach { value ->
            value?.let {
                require(it in 0L..at) { "Öffnen/Auftauen darf nicht in der Zukunft liegen." }
                input.acquiredOn?.let { date -> require(!AppCalendar.today(Instant.ofEpochMilli(it)).isBefore(LocalDate.parse(date))) {
                    "Öffnen/Auftauen liegt vor dem geprüften Zugang." } }
            }
        }
        if (input.release == ReleaseStatus.RELEASED) {
            require(input.conditionsConfirmed) { "Für eine Freigabe tatsächliche Lagerbedingungen und Eignung ausdrücklich prüfen." }
            require(dao.location(original.locationId)?.confirmed == true) { "Lagerort nicht bestätigt." }
            input.useBy?.let { require(!LocalDate.parse(it).isBefore(AppCalendar.today(Instant.ofEpochMilli(at)))) { "Überschrittenes Verbrauchsdatum erlaubt keine Freigabe." } }
        }
        val id = WriteRules.id()
        dao.insertOperation(OperationEntity(key, "BATCH_REVIEW", hash, at, id))
        dao.insertBatchReview(BatchReviewEntity(id, original.id, (previous?.revision ?: 0) + 1, previous?.id, key,
            input.acquiredOn, input.bestBefore, input.useBy, openedAt, thawedAt, input.openedAtText, input.thawedAtText, input.release,
            input.safetyNote, input.reason.trim(), input.conditionsConfirmed, Source.USER, Quality.CONFIRMED, at))
        id
    }
}
