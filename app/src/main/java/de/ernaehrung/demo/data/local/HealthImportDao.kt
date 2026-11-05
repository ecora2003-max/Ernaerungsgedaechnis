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
        changedRecords=0, hiddenRecords=0 WHERE sourcePackage=:source AND kind!='PURGE'""")
    suspend fun redactRuns(source: String)
}
