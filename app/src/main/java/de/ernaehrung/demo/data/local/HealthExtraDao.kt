package de.ernaehrung.demo.data.local

import androidx.room.*

/** Only FoodStore's health-extra operations write these tables. */
@Dao
internal interface HealthExtraDao {
    @Query("SELECT * FROM health_extra_state WHERE id='health-extra-v1'")
    suspend fun state(): HealthExtraStateEntity?
    @Query("SELECT * FROM health_extra_runs WHERE id=:key") suspend fun run(key: String): HealthExtraRunEntity?
    @Query("""SELECT h.* FROM health_extra_versions h WHERE h.revision=(SELECT MAX(v.revision)
        FROM health_extra_versions v WHERE v.sourcePackage=h.sourcePackage AND v.kind=h.kind AND v.externalId=h.externalId)
        ORDER BY h.startAt DESC,h.sourcePackage,h.kind,h.externalId""")
    suspend fun latest(): List<HealthExtraVersionEntity>
    @Query("""SELECT s.* FROM health_extra_samples s WHERE s.revision=(SELECT MAX(v.revision)
        FROM health_extra_versions v WHERE v.sourcePackage=s.sourcePackage AND v.kind=s.kind AND v.externalId=s.externalId)
        ORDER BY s.sourcePackage,s.kind,s.externalId,s.position""")
    suspend fun latestSamples(): List<HealthExtraSampleEntity>
    @Query("SELECT * FROM health_extra_versions WHERE sourcePackage=:source AND kind=:kind AND externalId=:id ORDER BY revision")
    suspend fun history(source: String, kind: String, id: String): List<HealthExtraVersionEntity>
    @Query("SELECT * FROM health_extra_samples WHERE sourcePackage=:source AND kind=:kind AND externalId=:id ORDER BY revision,position")
    suspend fun historySamples(source: String, kind: String, id: String): List<HealthExtraSampleEntity>
    @Upsert suspend fun saveState(state: HealthExtraStateEntity)
    @Insert suspend fun insertRun(run: HealthExtraRunEntity)
    @Insert suspend fun insertVersions(rows: List<HealthExtraVersionEntity>)
    @Insert suspend fun insertSamples(rows: List<HealthExtraSampleEntity>)
    @Query("DELETE FROM health_extra_samples") suspend fun purgeSamples()
    @Query("DELETE FROM health_extra_versions") suspend fun purgeVersions()
    @Query("""UPDATE health_extra_runs SET kind='REDACTED',fingerprint='',recordedAt=0,authorizationRevision=0,
        kinds=NULL,windowDays=NULL,endDay=NULL,historyOptIn=NULL,capturedAt=NULL,windowStart=NULL,windowEnd=NULL,zone=NULL,
        day=NULL,dayStart=NULL,dayEnd=NULL,steps=NULL,stepSources=NULL,changedRecords=0,hiddenRecords=0 WHERE kind!='PURGE'""")
    suspend fun redactRuns()
}
