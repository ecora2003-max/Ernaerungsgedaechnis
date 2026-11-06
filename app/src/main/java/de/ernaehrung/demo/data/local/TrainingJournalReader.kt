package de.ernaehrung.demo.data.local

import androidx.room.withTransaction
import de.ernaehrung.demo.data.fitness.FitnessHealthPolicy
import de.ernaehrung.demo.data.fitness.FitnessRecordKind
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class TrainingJournalSnapshot(val legacy: List<TrainingEventEntity>, val sets: List<TrainingSetEntity>,
    val imported: List<HealthRecordVersionEntity>, val lastSyncAt: Long?, val importEnabled: Boolean,
    val lastWindowStart: Long?, val lastWindowEnd: Long?)

/** Existing immutable local data only. No provider, permissions or writer. */
internal class TrainingJournalReader(private val db: FoodDatabase) {
    fun observe() = db.invalidationTracker.createFlow("training_events", "training_sets",
        "health_source_record_versions", "health_import_state", "health_import_runs").map {
        db.withTransaction {
            val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
            val state = db.healthDao().state(origin)
            val success = state?.lastSuccessRunId?.let { db.healthDao().run(it) }
            TrainingJournalSnapshot(db.foodDao().legacyTrainingEvents(), db.foodDao().legacyTrainingSets(),
                db.healthDao().latest(origin).filter { it.kind == FitnessRecordKind.WORKOUT.name },
                success?.recordedAt, state?.enabled == true, success?.windowStart, success?.windowEnd)
        }
    }.distinctUntilChanged()
}
