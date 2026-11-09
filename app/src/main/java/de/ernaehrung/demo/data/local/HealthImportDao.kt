package de.ernaehrung.demo.data.local

import androidx.room.*

/** Only FoodStore's health transaction implementation uses this write API. */
@Dao
internal interface HealthImportDao {
    @Query("SELECT * FROM health_import_state WHERE sourcePackage=:source")
    suspend fun state(source: String): HealthImportStateEntity?
    @Query("SELECT * FROM health_import_runs WHERE id=:id") suspend fun run(id: String): HealthImportRunEntity?
    @Query("SELECT * FROM health_import_runs WHERE sourcePackage=:source ORDER BY recordedAt,id")
    suspend fun runs(source: String): List<HealthImportRunEntity>
    @Query("""SELECT h.* FROM health_source_record_versions h WHERE h.sourcePackage=:source
        AND h.revision=(SELECT MAX(v.revision) FROM health_source_record_versions v
            WHERE v.sourcePackage=h.sourcePackage AND v.kind=h.kind AND v.externalId=h.externalId)
        ORDER BY h.startAt DESC,h.kind,h.externalId""")
    suspend fun latest(source: String): List<HealthRecordVersionEntity>
    @Query("SELECT * FROM health_source_record_versions WHERE sourcePackage=:source ORDER BY kind,externalId,revision")
    suspend fun history(source: String): List<HealthRecordVersionEntity>
    @Query("SELECT * FROM health_source_record_versions WHERE sourcePackage=:source AND kind=:kind AND externalId=:id ORDER BY revision")
    suspend fun historyFor(source: String, kind: String, id: String): List<HealthRecordVersionEntity>
    @Query("""SELECT * FROM health_source_record_versions WHERE sourcePackage=:source AND kind='WORKOUT'
        AND externalId=:id ORDER BY revision DESC LIMIT 1""")
    suspend fun latestWorkout(source: String, id: String): HealthRecordVersionEntity?
    @Query("""SELECT r.* FROM health_import_runs r JOIN health_source_record_versions h
        ON h.sourcePackage=r.sourcePackage AND h.kind='WORKOUT' AND h.externalId=r.workoutExternalId
        AND h.revision=r.workoutRecordRevision AND h.fingerprint=r.workoutRecordFingerprint
        WHERE r.sourcePackage=:source AND r.kind='WORKOUT_DURATION_V1' AND h.visible=1
        AND h.revision=(SELECT MAX(v.revision) FROM health_source_record_versions v
            WHERE v.sourcePackage=h.sourcePackage AND v.kind=h.kind AND v.externalId=h.externalId)
        AND (:knownOnly=0 OR (r.workoutDurationStatus='AVAILABLE' AND r.workoutDurationMillis IS NOT NULL))
        AND NOT EXISTS (SELECT 1 FROM health_import_runs n WHERE n.sourcePackage=r.sourcePackage
            AND n.kind=r.kind AND n.workoutExternalId=r.workoutExternalId
            AND n.workoutRecordRevision=r.workoutRecordRevision AND n.workoutRecordFingerprint=r.workoutRecordFingerprint
            AND (:knownOnly=0 OR (n.workoutDurationStatus='AVAILABLE' AND n.workoutDurationMillis IS NOT NULL))
            AND n.workoutDurationAttempt>r.workoutDurationAttempt)""")
    suspend fun currentWorkoutDurations(source: String, knownOnly: Boolean): List<HealthImportRunEntity>
    @Query("""SELECT * FROM health_import_runs WHERE sourcePackage=:source AND kind='WORKOUT_DURATION_V1'
        AND workoutExternalId=:id ORDER BY workoutRecordRevision,workoutDurationAttempt,id""")
    suspend fun workoutDurationHistory(source: String, id: String): List<HealthImportRunEntity>
    @Query("""SELECT MAX(workoutDurationAttempt) FROM health_import_runs WHERE sourcePackage=:source
        AND kind='WORKOUT_DURATION_V1' AND workoutExternalId=:id AND workoutRecordRevision=:revision
        AND workoutRecordFingerprint=:fingerprint""")
    suspend fun lastWorkoutDurationAttempt(source: String, id: String, revision: Long, fingerprint: String): Long?
    @Upsert suspend fun saveState(state: HealthImportStateEntity)
    @Insert suspend fun insertRun(run: HealthImportRunEntity)
    @Insert suspend fun insertVersions(versions: List<HealthRecordVersionEntity>)
    @Query("DELETE FROM health_source_record_versions WHERE sourcePackage=:source") suspend fun purgeRecords(source: String)
    // Retain only opaque retry markers. Erasing their keys would let an old
    // delete request erase a later cache, or an old import silently reactivate.
    // No record IDs, source values, windows, dates or digests survive redaction.
    @Query("""UPDATE health_import_runs SET kind='REDACTED', fingerprint='', recordedAt=0,
        authorizationRevision=0, capturedAt=NULL, windowStart=NULL, windowEnd=NULL,
        day=NULL, dayZone=NULL, dayStart=NULL, dayEnd=NULL, aggregatedSteps=NULL,
        changedRecords=0, hiddenRecords=0, workoutExternalId=NULL, workoutRecordRevision=NULL,
        workoutRecordFingerprint=NULL, workoutDurationMillis=NULL, workoutDurationStatus=NULL, workoutDurationAttempt=NULL
        WHERE sourcePackage=:source AND kind!='PURGE'""")
    suspend fun redactRuns(source: String)
}
