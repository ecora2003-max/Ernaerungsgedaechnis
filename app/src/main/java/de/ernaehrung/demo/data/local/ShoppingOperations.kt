package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.local.WriteRules.fingerprint
import de.ernaehrung.demo.data.local.WriteRules.id
import de.ernaehrung.demo.data.local.WriteRules.replay

internal class ShoppingOperations(private val db: FoodDatabase, private val now: () -> Long) {
    private val dao = db.foodDao()
    suspend fun price(key: String, input: PriceInput, previousId: String?): String = db.withTransaction {
        require(input.reason.isNotBlank()) { "Quelle/Änderungsgrund der Preisangabe fehlt." }
        WriteRules.date(input.observedOn)
        input.amount?.let(Amounts::positive); input.totalCents?.let(Money::valid)
        input.validFrom?.let(WriteRules::date); input.validUntil?.let(WriteRules::date)
        require(input.validFrom == null || input.validUntil == null || input.validFrom <= input.validUntil) { "Preisgültigkeit endet vor ihrem Beginn." }
        require(!input.basisConfirmed || input.wholePack && input.amount != null && !input.basisDescription.isNullOrBlank()) {
            "Eine ganze Kaufpackung, ihre nutzbare Basismenge und deren Grundlage ausdrücklich angeben."
        }
        require(!input.priceConfirmed || input.totalCents != null) { "Ein unbekannter Preis kann nicht als geprüft bestätigt werden." }
        val hash = fingerprint("PRICE_1", previousId, input.versionId, input.merchant, input.observedOn, input.amount,
            input.totalCents, input.wholePack, input.basisConfirmed, input.basisDescription, input.validFrom, input.validUntil,
            input.priceConfirmed, input.reason, input.source)
        replay(dao, key, hash)?.let { return@withTransaction it }
        WriteRules.actualDate(input.observedOn, now())
        requireNotNull(dao.version(input.versionId)) { "Artikelversion nicht gefunden." }
        val prices = dao.prices()
        previousId?.let { previous ->
            val old = requireNotNull(prices.find { it.id == previous }) { "Bisherige Preisfassung nicht gefunden." }
            require(prices.none { it.supersedesId == previous }) { "Preisfassung wurde inzwischen ersetzt. Neueste Fassung prüfen." }
            require(old.versionId == input.versionId) { "Andere Artikelversion benötigt eine eigene Preisbeobachtung." }
        }
        val result = id()
        dao.insertOperation(OperationEntity(key, "PRICE", hash, now(), result))
        dao.insertPriceObservation(PriceObservationEntity(result, input.versionId, input.merchant?.trim()?.takeIf(String::isNotBlank),
            input.observedOn, input.amount, input.totalCents, input.source, if (input.priceConfirmed) Quality.CONFIRMED else Quality.UNKNOWN,
            input.wholePack, input.basisConfirmed, input.basisDescription?.trim()?.takeIf(String::isNotBlank), input.validFrom,
            input.validUntil, "EUR", previousId, key, now(), input.reason.trim()))
        result
    }
    suspend fun budget(key: String, input: BudgetInput, previousId: String?): String = db.withTransaction {
        require(input.label.isNotBlank() && input.reason.isNotBlank()) { "Budgetname und Änderungsgrund fehlen." }
        WriteRules.date(input.startsOn); WriteRules.date(input.endsOn)
        require(input.startsOn <= input.endsOn) { "Budget endet vor seinem Beginn." }
        input.amountCents?.let(Money::valid)
        require(!input.confirmed || input.amountCents != null) { "Ein unbekannter Budgetbetrag bleibt ungeklärt." }
        val hash = fingerprint("BUDGET_1", previousId, input.label, input.startsOn, input.endsOn, input.amountCents, input.confirmed, input.reason)
        replay(dao, key, hash)?.let { return@withTransaction it }
        val budgets = dao.budgets()
        previousId?.let { previous ->
            val old = requireNotNull(budgets.find { it.id == previous }) { "Bisheriges Budget nicht gefunden." }
            require(budgets.none { it.supersedesId == previous }) { "Budget wurde inzwischen ersetzt. Neueste Fassung prüfen." }
            require(old.startsOn == input.startsOn && old.endsOn == input.endsOn) { "Anderer Zeitraum benötigt ein eigenes Budget." }
        }
        val result = id()
        dao.insertOperation(OperationEntity(key, "BUDGET", hash, now(), result))
        dao.insertBudget(BudgetEntity(result, input.startsOn, input.endsOn, input.amountCents,
            if (input.confirmed) Quality.CONFIRMED else Quality.UNKNOWN, input.label.trim(), "EUR", previousId, key, now(), input.reason.trim()))
        result
    }
    suspend fun mark(key: String, input: ShoppingMarkInput): String = db.withTransaction {
        val scope = input.scope
        val hash = fingerprint("SHOPPING_MARK_1", scope.startsOn, scope.endsOn, scope.purchaseOn, scope.budgetId,
            input.versionId, input.offerId, input.checked, input.expectedSnapshot)
        replay(dao, key, hash)?.let { return@withTransaction it }
        val reader = ShoppingReader(db, now)
        val current = reader.report(scope)
        val item = requireNotNull(current.items.find { it.need.versionId == input.versionId }) { "Für diese Artikelversion besteht kein aktueller Fehlbedarf im Ausschnitt." }
        require(item.snapshot == input.expectedSnapshot) { "Bedarf, Preis oder Listenstand wurde geändert. Aktuelle Vorschläge erneut prüfen." }
        require(input.offerId == null || item.alternatives.any { it.id == input.offerId }) { "Preisfassung passt nicht oder wurde inzwischen ersetzt." }
        val target = reader.report(scope, mapOf(input.versionId to input.offerId)).items.single { it.need.versionId == input.versionId }
        val previous = dao.shoppingMarks(scope.startsOn, scope.endsOn, scope.purchaseOn).filter { it.versionId == input.versionId }.maxByOrNull { it.revision }
        dao.insertOperation(OperationEntity(key, "SHOPPING_MARK", hash, now(), key))
        dao.insertShoppingMark(ShoppingMarkEntity(key, input.versionId, scope.startsOn, scope.endsOn, scope.purchaseOn,
            input.offerId, input.checked, target.contentToken, (previous?.revision ?: 0) + 1, previous?.operationId))
        key
    }
}
