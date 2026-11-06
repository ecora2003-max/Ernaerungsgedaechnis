package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.recognition.AliasText
import de.ernaehrung.demo.data.recognition.Gtin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant

internal enum class DataQualityCategory(val label: String) {
    PRODUCT("Produktnährwerte"), ALIAS("Zuordnungen"), RECEIPT("Bons"),
    STORAGE("Lager & Haltbarkeit"), DAY("Ernährungstage"), QUANTITY("Mengen & Einheiten")
}
internal enum class DataQualityTarget(val label: String) {
    CATALOG("Produkt im Katalog prüfen"), ALIASES("Aliasentscheidungen prüfen"), SCAN("Bonentwurf prüfen"),
    PURCHASES("Bezahlte Käufe prüfen"), STOCK("Lager und Ereignisse prüfen"),
    NUTRITION("Diesen Ernährungstag prüfen"), KITCHEN("Rezeptfassung prüfen")
}
internal data class DataQualityIssue(val id: String, val category: DataQualityCategory, val title: String,
    val details: List<String>, val reference: String, val target: DataQualityTarget, val argument: String? = null)
internal data class DataQualitySnapshot(val checkedOn: String, val products: Int, val activeBatches: Int,
    val knownDays: Int, val issues: List<DataQualityIssue>)

/** Derived local facts, not a second truth, medical assessment or automatic correction.
 * All input reads (including recognition and historical nutrition) share ONE Room transaction.
 */
internal class DataQualityReader(private val db: FoodDatabase, private val now: () -> Long) {
    fun observe() = db.invalidationTracker.createFlow("products", "product_versions", "product_aliases", "alias_decisions",
        "receipt_scans", "receipt_scan_resolutions", "receipts", "receipt_lines", "purchase_events", "receipt_stock_bookings",
        "batches", "batch_reviews", "storage_locations", "stock_movements", "operation_reversals", "operations", "diary_days",
        "consumption_events", "productions", "recipe_versions", "recipe_ingredients").map { read() }.distinctUntilChanged()

    suspend fun read(): DataQualitySnapshot = db.withTransaction {
        val dao = db.foodDao()
        val versions = dao.allVersions()
        val byId = versions.associateBy { it.id }
        val current = versions.groupBy { it.productId }.values.map { it.maxBy(ProductVersionEntity::revision) }
        val issues = mutableListOf<DataQualityIssue>()
        current.forEach { version ->
            // Produced foods have a derived, version-bound recipe basis, not a missing label.
            if (dao.productionsForVersion(version.id).isEmpty()) {
                val missing = Nutrient.entries.filter { version.nutrient(it) == null }
                val invalid = Nutrient.entries.filter { version.nutrient(it)?.let { v -> !v.isFinite() || v < 0 } == true }
                if (missing.isNotEmpty() || invalid.isNotEmpty()) issues += DataQualityIssue("product:${version.id}",
                    DataQualityCategory.PRODUCT, version.name, buildList {
                        if (missing.isNotEmpty()) add("Nicht überliefert: ${missing.joinToString { it.label }}")
                        if (invalid.isNotEmpty()) add("Ungültige Angaben: ${invalid.joinToString { it.label }}")
                        add("Version ${version.revision} · ${version.source.label} · ${version.quality.label}")
                        add("Neue Produktfassungen ändern keine früheren Verzehre oder Chargen.")
                    }, version.id, DataQualityTarget.CATALOG, version.productId)
            }
        }
        val recognition = RecognitionReader(db)
        val recognitionData = recognition.data()
        val activeAliases = recognitionData.aliases.filter { !it.replaced && it.decision?.active != false }
        data class AliasScope(val kind: AliasKind, val text: String, val merchant: String?)
        val scopes = activeAliases.mapNotNull { row ->
            val kind = row.decision?.kind?.let { runCatching { AliasKind.valueOf(it) }.getOrNull() }
                ?: if (row.alias.barcode != null) AliasKind.BARCODE else AliasKind.RECEIPT
            if (kind == AliasKind.BARCODE) {
                val canonical = row.alias.barcode?.let { runCatching { Gtin.parse(it).canonical }.getOrNull() }
                if (canonical == null) {
                    issues += DataQualityIssue("alias-invalid:${row.alias.id}", DataQualityCategory.ALIAS,
                        row.alias.text, listOf("Gespeicherter Barcode nicht als gültige GTIN prüfbar.",
                            "${row.alias.source.label} · ${row.alias.quality.label}"), row.alias.id, DataQualityTarget.ALIASES)
                    null
                } else AliasScope(kind, canonical, null)
            } else AliasScope(kind, AliasText.normalize(row.alias.text), AliasText.merchant(row.alias.merchant))
        }.distinct().sortedWith(compareBy<AliasScope> { it.kind.name }.thenBy { it.text }.thenBy { it.merchant })
        scopes.forEach { scope ->
            if (scope.text.isNotBlank()) {
                // Reuse the actual matcher: wildcard merchant scopes, GTIN canonicalization,
                // current versions, retired decisions and candidate deduplication must agree.
                val match = recognition.match(scope.text, scope.merchant, scope.kind)
                if (match.status == AliasMatchStatus.AMBIGUOUS || match.status == AliasMatchStatus.REVIEW)
                    issues += DataQualityIssue("alias:${WriteRules.fingerprint(scope.kind, scope.text, scope.merchant)}",
                        DataQualityCategory.ALIAS, scope.text, listOf(
                            if (match.status == AliasMatchStatus.AMBIGUOUS) "Mehrere Artikelversionen passen; keine automatische Zuordnung."
                            else "Artikelversion oder ausdrückliche Bestätigung prüfen.",
                            "Händler: ${scope.merchant ?: "ohne Händlerbindung"}",
                            "Kandidaten: ${match.candidates.joinToString { "${it.version.name} · v${it.version.revision}" }}"),
                        match.candidates.mapNotNull { it.alias?.id }.joinToString(), DataQualityTarget.ALIASES)
            }
        }
        recognitionData.scans.filter { it.resolution == null }.forEach { view ->
            issues += DataQualityIssue("scan:${view.scan.operationId}", DataQualityCategory.RECEIPT,
                "Offener Bonentwurf", listOf("${view.scan.method} · Parser ${view.scan.parserVersion}",
                    "Noch keine Annahme oder Verwerfung. Der Entwurf ist keine Zahlung oder Lagerbuchung."),
                view.scan.operationId, DataQualityTarget.SCAN, view.scan.operationId)
        }
        dao.receipts().forEach { receipt ->
            val purchase = dao.purchaseForReceipt(receipt.id)
            if (purchase == null || dao.reversal(purchase.operationId) == null) {
                val header = buildList {
                    if (receipt.merchant.isNullOrBlank()) add("Händler unbekannt")
                    if (receipt.purchasedOn == null) add("Kaufdatum unbekannt")
                    if (receipt.totalCents == null) add("Belegsumme unbekannt")
                    if (purchase == null) add("Keine ausdrückliche Zahlung erfasst")
                }
                if (header.isNotEmpty()) issues += DataQualityIssue("receipt:${receipt.id}", DataQualityCategory.RECEIPT,
                    receipt.merchant ?: "Beleg ohne Händler", header + "${receipt.source.label} · ${receipt.quality.label}",
                    receipt.id, DataQualityTarget.PURCHASES)
                dao.receiptLines(receipt.id).forEach { line ->
                    val missing = buildList {
                        if (line.versionId == null) add("Artikelversion unbekannt")
                        if (line.amount == null) add("Menge unbekannt")
                        if (line.unit == null) add("Einheit unbekannt")
                        if (line.totalCents == null) add("Positionspreis unbekannt")
                    }
                    if (missing.isNotEmpty()) issues += DataQualityIssue("receipt-line:${line.id}", DataQualityCategory.RECEIPT,
                        line.interpretedName ?: line.originalText ?: "Bonposition", missing +
                            "Teilwissen bleibt erhalten. Pfand, Rabatt oder andere Nichtwaren benötigen keine erfundene Lebensmittelzuordnung.",
                        "${receipt.id} / ${line.id}", DataQualityTarget.PURCHASES)
                    val version = line.versionId?.let(byId::get)
                    if (version != null && line.unit != null && line.unit != version.baseUnit)
                        issues += DataQualityIssue("receipt-unit:${line.id}", DataQualityCategory.QUANTITY,
                            line.interpretedName ?: version.name, listOf("Belegeinheit ${line.unit.label}; Artikelbasis ${version.baseUnit.label}.",
                                "Keine automatische Umrechnung oder Lagerbuchung."), line.id, DataQualityTarget.PURCHASES)
                }
            }
        }
        val locations = dao.allLocations().associateBy { it.id }
        val activeBatches = dao.planningBatches().filter { it.stockAmount > 0 && !it.originReversed }
        val today = AppCalendar.today(Instant.ofEpochMilli(now())).toString()
        activeBatches.forEach { row ->
            val batch = row.batch
            val missing = buildList {
                if (locations[batch.locationId]?.confirmed != true) add("Lagerort nicht ausdrücklich bestätigt")
                if (batch.acquiredOn == null) add("Zugangsdatum unbekannt")
                if (batch.bestBefore == null && batch.useBy == null) add("Haltbarkeit unbekannt")
                if (batch.release == ReleaseStatus.UNCHECKED) add("Verwendungsstatus ungeklärt")
                if (batch.release == ReleaseStatus.BLOCKED) add("Charge ausdrücklich gesperrt")
                batch.useBy?.takeIf { it < today }?.let { add("Verbrauchsdatum $it liegt vor $today") }
                batch.bestBefore?.takeIf { it < today }?.let { add("MHD $it liegt vor $today; keine automatische Sicherheitsentscheidung") }
            }
            if (missing.isNotEmpty()) issues += DataQualityIssue("batch:${batch.id}", DataQualityCategory.STORAGE,
                "${row.productName} · ${row.locationName}", missing + "Physisch: ${Amounts.format(row.stockAmount)} ${row.unit.label}",
                "${batch.id} / ${batch.versionId}", DataQualityTarget.STOCK, batch.id)
        }
        val nutrition = NutritionReader(db)
        val dates = dao.knownNutritionDates()
        dates.forEach { date ->
            val day = nutrition.day(date)
            if (day.status != DayStatus.COMPLETE || day.meals.any { it.nutrition.hasGaps })
                issues += DataQualityIssue("day:$date", DataQualityCategory.DAY, date, buildList {
                    add(when (day.status) {
                        DayStatus.OPEN -> "Erfassung offen"
                        DayStatus.PARTIAL -> "Erfassung teilweise"
                        DayStatus.COMPLETE -> "Erfassung vollständig bestätigt; Nährwertlücken bleiben davon unabhängig"
                    })
                    val missing = Nutrient.entries.filter { n -> day.meals.any { !it.nutrition[n].complete } }
                    if (missing.isNotEmpty()) add("Ungeklärte Beiträge: ${missing.joinToString { it.label }}")
                    add("${day.meals.size} tatsächlich erfasste Verzehre; Pläne zählen nicht als Zufuhr.")
                }, date, DataQualityTarget.NUTRITION, date)
            day.meals.filter { it.event.amount == null || it.event.amount <= 0 }.forEach { meal ->
                issues += DataQualityIssue("meal-amount:${meal.event.id}", DataQualityCategory.QUANTITY,
                    "${meal.productName} · $date", listOf(if (meal.event.amount == null) "Verzehrmenge unbekannt" else "Verzehrmenge ohne positive Berechnungsbasis",
                        "${meal.event.source.label} · ${meal.event.quality.label}; ursprüngliche Version ${meal.event.productVersionId}"),
                    meal.event.id, DataQualityTarget.NUTRITION, date)
            }
        }
        dao.currentRecipes().forEach { recipe ->
            dao.ingredients(recipe.id).forEach { ingredient ->
                val product = byId[ingredient.productVersionId]
                val missing = buildList {
                    if (ingredient.amount == null || ingredient.amount <= 0) add("Zutatenmenge ohne bekannte positive Berechnungsbasis")
                    if (product == null) add("Artikelversion nicht verfügbar")
                    else if (ingredient.unit != product.baseUnit) add("Zutatenbasis ${ingredient.unit.label}; Artikelbasis ${product.baseUnit.label}")
                }
                if (missing.isNotEmpty()) issues += DataQualityIssue("ingredient:${recipe.id}:${ingredient.position}", DataQualityCategory.QUANTITY,
                    "${recipe.name} · ${product?.name ?: "Zutat"}", missing + "Rezeptversion ${recipe.revision}; keine geschätzte Umrechnung.",
                    "${recipe.id} / ${ingredient.position}", DataQualityTarget.KITCHEN)
            }
        }
        DataQualitySnapshot(today, current.size, activeBatches.size, dates.size,
            issues.sortedWith(compareBy<DataQualityIssue> { it.category.ordinal }.thenBy { it.id }))
    }
}
