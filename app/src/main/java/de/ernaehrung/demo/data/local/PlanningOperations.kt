package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import de.ernaehrung.demo.data.local.WriteRules.fingerprint
import de.ernaehrung.demo.data.local.WriteRules.id
import de.ernaehrung.demo.data.local.WriteRules.replay

internal class PlanningOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()

    suspend fun save(key: String, input: PlanInput, previousVersionId: String?): String = db.withTransaction {
        require(input.title.isNotBlank() && input.reason.isNotBlank()) { "Plantitel und Änderungsgrund fehlen." }
        WriteRules.date(input.startsOn); WriteRules.date(input.endsOn)
        require(ChronoUnit.DAYS.between(LocalDate.parse(input.startsOn), LocalDate.parse(input.endsOn)) in 0..55) {
            "Planzeitraum muss zwischen einem und 56 Tagen liegen."
        }
        require(input.entries.size <= 200) { "Höchstens 200 Einträge je Planversion." }
        require(input.entries.all { it.key.isNotBlank() } && input.entries.map { it.key }.distinct().size == input.entries.size) {
            "Planeinträge brauchen verschiedene eindeutige Schlüssel."
        }
        val hash = fingerprint("PLAN_V1", previousVersionId, input.title, input.startsOn, input.endsOn, input.reason,
            input.entries.map { listOf(it.key, it.date, it.kind, it.productVersionId, it.recipeVersionId, it.amount, it.unit, it.preparationKey, it.label) })
        replay(dao, key, hash)?.let { return@withTransaction it }
        val previous = previousVersionId?.let { latest(it) }
        val byKey = input.entries.associateBy { it.key }
        input.entries.forEach { entry ->
            WriteRules.date(entry.date)
            require(entry.date in input.startsOn..input.endsOn) { "Eintrag liegt außerhalb des Planzeitraums." }
            entry.amount?.let(Amounts::positive)
            when (entry.kind) {
                PlanKind.DIRECT_CONSUMPTION -> {
                    require(entry.recipeVersionId == null && entry.preparationKey == null) { "Direkter Produktplan darf keinen Kochbezug haben." }
                    product(entry)
                }
                PlanKind.PREPARATION -> {
                    require(entry.productVersionId == null && entry.preparationKey == null && entry.unit == BaseUnit.PORTION) { "Zubereitung in Portionen und mit Rezeptversion planen." }
                    requireNotNull(entry.recipeVersionId?.let { dao.recipeVersion(it) }) { "Rezeptversion nicht gefunden." }
                }
                PlanKind.PREPARED_PORTION -> {
                    require(entry.unit == BaseUnit.PORTION && entry.recipeVersionId == null) { "Fertigportion braucht einen Chargenartikel oder verknüpften Kochplan." }
                    if (entry.preparationKey != null) {
                        require(entry.productVersionId == null) { "Entweder vorhandenen Artikel oder geplante Herstellung wählen." }
                        val prep = requireNotNull(byKey[entry.preparationKey]) { "Verknüpfte Zubereitung fehlt in dieser Planversion." }
                        require(prep.kind == PlanKind.PREPARATION && prep.date <= entry.date) { "Portionenverzehr muss auf eine Zubereitung am selben oder einem früheren Tag verweisen." }
                    } else product(entry)
                }
            }
        }
        val series = previous?.seriesId ?: id().also { dao.insertPlanSeries(PlanSeriesEntity(it, now())) }
        val version = id()
        dao.insertPlan(PlanEntity(version, (previous?.revision ?: 0) + 1, input.startsOn, input.endsOn,
            EntryStatus.CONFIRMED, Source.USER, now(), series, input.title.trim(), input.reason.trim()))
        val ids = input.entries.associate { it.key to id() }
        input.entries.sortedBy { it.preparationKey != null }.forEach { entry ->
            dao.insertPlannedEntry(PlannedEntryEntity(ids.getValue(entry.key), version, entry.date, entry.kind,
                entry.productVersionId, entry.recipeVersionId, entry.amount, entry.unit,
                entry.preparationKey?.let(ids::getValue), entry.label?.trim()?.takeIf { it.isNotEmpty() }))
        }
        dao.insertOperation(OperationEntity(key, "PLAN_SAVE", hash, now(), version))
        version
    }

    suspend fun cancel(key: String, previousVersionId: String, reason: String): String = db.withTransaction {
        require(reason.isNotBlank()) { "Grund für das Verwerfen fehlt." }
        val hash = fingerprint("PLAN_CANCEL_V1", previousVersionId, reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        val previous = latest(previousVersionId)
        require(previous.status != EntryStatus.CANCELLED) { "Plan ist bereits verworfen." }
        val version = id()
        dao.insertPlan(previous.copy(id = version, revision = previous.revision + 1,
            status = EntryStatus.CANCELLED, recordedAt = now(), changeReason = reason.trim()))
        val entries = dao.allPlannedEntries().filter { it.planId == previous.id }
        val ids = entries.associate { it.id to id() }
        entries.sortedBy { it.preparationEntryId != null }.forEach {
            dao.insertPlannedEntry(it.copy(id = ids.getValue(it.id), planId = version,
                preparationEntryId = it.preparationEntryId?.let(ids::getValue)))
        }
        dao.insertOperation(OperationEntity(key, "PLAN_CANCEL", hash, now(), version))
        version
    }

    private suspend fun latest(versionId: String): PlanEntity {
        val previous = requireNotNull(dao.plan(versionId)) { "Planversion nicht gefunden." }
        val series = requireNotNull(previous.seriesId) { "Alte Planidentität zuerst prüfen." }
        require(dao.latestPlan(series)?.id == previous.id) { "Plan wurde inzwischen verändert. Neueste Version laden und Änderungen erneut prüfen." }
        return previous
    }
    private suspend fun product(entry: PlanEntryInput) {
        val product = requireNotNull(entry.productVersionId?.let { dao.version(it) }) { "Artikelversion nicht gefunden." }
        require(product.baseUnit == entry.unit) { "Planeinheit passt nicht zur Artikelversion. Keine unbekannte Umrechnung möglich." }
    }
}
