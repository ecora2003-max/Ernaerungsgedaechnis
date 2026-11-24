package de.ernaehrung.demo.data.local

import androidx.room.*

internal data class HealthSourceInterval(val sourcePackage: String, val startAt: Long, val endAt: Long)

/** Only FoodStore's health-extra operations write these tables. */
@Dao
internal interface HealthExtraDao {
    @Query("SELECT * FROM health_extra_state WHERE id='health-extra-v1'")
    suspend fun state(): HealthExtraStateEntity?
    @Query("SELECT * FROM health_extra_runs WHERE id=:key") suspend fun run(key: String): HealthExtraRunEntity?
    @Query("SELECT * FROM health_extra_runs") suspend fun runs(): List<HealthExtraRunEntity>
    @Query("SELECT * FROM health_extra_versions") suspend fun versions(): List<HealthExtraVersionEntity>
    @Query("SELECT * FROM health_extra_samples") suspend fun samples(): List<HealthExtraSampleEntity>
    @Query("""SELECT h.* FROM health_extra_versions h
        WHERE (:kind IS NULL OR h.kind=:kind) AND h.revision=(SELECT MAX(v.revision)
        FROM health_extra_versions v WHERE v.sourcePackage=h.sourcePackage AND v.kind=h.kind AND v.externalId=h.externalId)
        ORDER BY h.startAt DESC,h.sourcePackage,h.kind,h.externalId""")
    suspend fun latest(kind: String? = null): List<HealthExtraVersionEntity>
    @Query("""SELECT h.sourcePackage, h.startAt, h.endAt FROM health_extra_versions h
        WHERE h.kind='ACTIVE_ENERGY' AND h.visible=1 AND h.scalarBits IS NOT NULL
        AND h.startAt<:end AND h.endAt>:start AND h.endAt>h.startAt
        AND NOT EXISTS (SELECT 1 FROM health_extra_versions newer
            WHERE newer.sourcePackage=h.sourcePackage AND newer.kind=h.kind AND newer.externalId=h.externalId
            AND newer.revision>h.revision)""")
    suspend fun activeEnergyIntervals(start: Long, end: Long): List<HealthSourceInterval>
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
        kinds=NULL,windowDays=NULL,endDay=NULL,historyOptIn=NULL,requestedAt=NULL,capturedAt=NULL,windowStart=NULL,windowEnd=NULL,zone=NULL,
        day=NULL,dayStart=NULL,dayEnd=NULL,steps=NULL,stepSources=NULL,changedRecords=0,hiddenRecords=0 WHERE kind!='PURGE'""")
    suspend fun redactRuns()
}
