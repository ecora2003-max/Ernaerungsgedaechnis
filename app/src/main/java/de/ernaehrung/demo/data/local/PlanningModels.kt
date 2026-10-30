package de.ernaehrung.demo.data.local

data class PlanEntryInput(val key: String, val date: String, val kind: PlanKind,
    val productVersionId: String? = null, val recipeVersionId: String? = null,
    val amount: Long?, val unit: BaseUnit, val preparationKey: String? = null, val label: String? = null,
    val positionId: String? = null) {
    companion object {
        fun from(entry: PlannedEntryEntity) = PlanEntryInput(entry.id, entry.date, entry.kind,
            entry.productVersionId, entry.recipeVersionId, entry.amount, entry.unit, entry.preparationEntryId, entry.label, entry.positionId)
    }
}
data class PlanInput(val title: String, val startsOn: String, val endsOn: String,
    val entries: List<PlanEntryInput>, val reason: String, val carryExecutionsConfirmed: Boolean = false)
data class PlanRecord(val version: PlanEntity, val entries: List<PlannedEntryEntity>)

enum class PlanningIssue(val label: String) {
    UNKNOWN_AMOUNT("Menge ungeklärt"), INVALID_REFERENCE("Planbezug oder Einheit ungeklärt"),
    QUANTITY_RANGE("Berechnete Menge außerhalb des Mengenbereichs"),
    STORAGE_UNCHECKED("Lagerplatz, Behälter und Bedingungen der geplanten Portionen ungeprüft"),
    PLANNED_ONLY("Nur geplante Portionen; Herstellung, Haltbarkeit und Freigabe sind noch zu prüfen"),
    PAST_PREPARATION("Vergangener Kochplan ist kein Nachweis vorhandener Portionen"),
    UNKNOWN_EXECUTION("Ausgeführte oder verbleibende Menge ungeklärt"),
    EXECUTION_REFERENCE_CHANGED("Herstellungsbezug einer Ist-Zuordnung verändert oder storniert; Herkunft prüfen")
}
enum class StockExclusionReason(val label: String) {
    NOT_RELEASED("Gesperrt oder ungeklärt"), ORIGIN_REVERSED("Herkunft storniert"),
    LOCATION_UNCONFIRMED("Lagerort nicht bestätigt"), NOT_YET_ACQUIRED("Zugang erst nach dem Termin"),
    USE_BY_EXPIRED("Verbrauchsdatum vor dem Termin"),
    BEST_BEFORE_REVIEW("MHD vor dem Termin; gesonderte Prüfung nötig"),
    FUTURE_SHELF_LIFE_UNKNOWN("Haltbarkeit zum zukünftigen Termin ungeklärt")
}
data class PlannedAllocation(val batchId: String, val amount: Long)
data class ExcludedStock(val batchId: String, val amount: Long, val reason: StockExclusionReason)
data class DemandLine(val versionId: String, val name: String, val unit: BaseUnit,
    val required: Long?, val covered: Long, val missing: Long?,
    val allocations: List<PlannedAllocation>, val excluded: List<ExcludedStock>,
    val issues: Set<PlanningIssue> = emptySet())
data class PlannedEntryResult(val planId: String, val entry: PlannedEntryEntity, val name: String,
    val demands: List<DemandLine> = emptyList(), val projectedPortions: Long? = null,
    val missingPortions: Long? = null, val issues: Set<PlanningIssue> = emptySet(),
    val execution: PlanExecutionStatus? = null, val actualPortionsCovered: Long = 0,
    val portionAllocations: List<PlannedAllocation> = emptyList(), val excludedPortions: List<ExcludedStock> = emptyList())
data class ShoppingNeed(val versionId: String, val name: String, val unit: BaseUnit,
    val knownMissing: Long, val unknownRequirements: Int)
data class PlanningReport(val asOf: String, val startsOn: String, val endsOn: String,
    val plans: List<PlanRecord>, val versions: List<PlanEntity>, val entries: List<PlannedEntryResult>,
    val shopping: List<ShoppingNeed>, val hasUnknownRequirements: Boolean,
    val detachedExecutionEntries: List<PlannedEntryEntity> = emptyList()) {
    companion object { const val CALCULATION_VERSION = "demand-2" }
}
