package com.moatazvid.storage.room

import androidx.room3.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(project: ProjectEntity)
    @Update suspend fun update(project: ProjectEntity)
    @Query("SELECT * FROM projects WHERE projectId = :id") suspend fun get(id: String): ProjectEntity?
    @Query("SELECT * FROM projects WHERE projectId = :id") fun observe(id: String): Flow<ProjectEntity?>
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC") fun observeAll(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC") suspend fun all(): List<ProjectEntity>
    @Query("UPDATE projects SET title = :title, updatedAtEpochMs = :updatedAtEpochMs, rowRevision = rowRevision + 1 WHERE projectId = :id")
    suspend fun rename(id: String, title: String, updatedAtEpochMs: Long): Int
    @Query("DELETE FROM projects WHERE projectId = :id") suspend fun delete(id: String): Int
}

@Dao
interface TimelineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSequence(sequence: SequenceEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTracks(tracks: List<TrackEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertClips(clips: List<ClipEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertClipProperties(properties: ClipPropertiesEntity)
    @Update suspend fun updateSequence(sequence: SequenceEntity)
    @Update suspend fun updateTracks(tracks: List<TrackEntity>)
    @Update suspend fun updateClips(clips: List<ClipEntity>)
    @Delete suspend fun deleteClips(clips: List<ClipEntity>)
    @Query("SELECT * FROM sequences WHERE sequenceId = :sequenceId") suspend fun sequence(sequenceId: String): SequenceEntity?
    @Query("SELECT * FROM tracks WHERE sequenceId = :sequenceId ORDER BY type, orderIndex") suspend fun tracks(sequenceId: String): List<TrackEntity>
    @Query("SELECT * FROM clips WHERE sequenceId = :sequenceId ORDER BY trackId, timelineStartUs") suspend fun clips(sequenceId: String): List<ClipEntity>
    @Query("SELECT * FROM clip_properties WHERE clipId IN (:clipIds)") suspend fun clipProperties(clipIds: List<String>): List<ClipPropertiesEntity>
    @Query("UPDATE sequences SET revision = :resultRevision, updatedAtEpochMs = :updatedAt WHERE sequenceId = :sequenceId AND revision = :expectedRevision")
    suspend fun compareAndSetRevision(sequenceId: String, expectedRevision: Long, resultRevision: Long, updatedAt: Long): Int
}

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFileRef(ref: FileReferenceEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSource(source: MediaSourceEntity)
    @Update suspend fun updateFileRef(ref: FileReferenceEntity)
    @Query("SELECT * FROM media_sources WHERE projectId = :projectId") suspend fun sources(projectId: String): List<MediaSourceEntity>
    @Query("SELECT * FROM media_sources WHERE sourceId = :sourceId") suspend fun source(sourceId: String): MediaSourceEntity?
    @Query("SELECT * FROM file_references WHERE fileRefId = :id") suspend fun fileRef(id: String): FileReferenceEntity?
}

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(transaction: EditTransactionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setCursor(cursor: HistoryCursorEntity)
    @Query("SELECT * FROM history_cursors WHERE sequenceId = :sequenceId") suspend fun cursor(sequenceId: String): HistoryCursorEntity?
    @Query("SELECT * FROM edit_transactions WHERE transactionId = :id") suspend fun get(id: String): EditTransactionEntity?
    @Query("SELECT * FROM edit_transactions WHERE parentTransactionId = :parent AND branchId = :branch ORDER BY resultRevision LIMIT 1")
    suspend fun child(parent: String?, branch: String): EditTransactionEntity?
}

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProxy(proxy: ProxyEntity)
    @Query("SELECT * FROM proxies WHERE sourceId = :sourceId") suspend fun proxies(sourceId: String): List<ProxyEntity>
    @Query("SELECT * FROM proxies WHERE status IN ('QUEUED','RUNNING')") suspend fun activeProxies(): List<ProxyEntity>
    @Query("DELETE FROM proxies WHERE proxyId IN (:ids)") suspend fun deleteProxies(ids: List<String>): Int
    @Query("SELECT * FROM analysis_records WHERE projectId = :projectId") suspend fun analyses(projectId: String): List<AnalysisEntity>
}

@Dao
interface ExportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(record: ExportRecordEntity)
    @Update suspend fun update(record: ExportRecordEntity)
    @Query("SELECT * FROM export_records WHERE exportId = :exportId") suspend fun get(exportId: String): ExportRecordEntity?
    @Query("SELECT * FROM export_records WHERE status IN ('QUEUED','RUNNING','VALIDATING','RENDERING','VERIFYING','FINALIZING') ORDER BY createdAtEpochMs")
    suspend fun activeJobs(): List<ExportRecordEntity>
    @Query("SELECT * FROM export_records WHERE projectId = :projectId ORDER BY createdAtEpochMs DESC")
    fun observeHistory(projectId: String): Flow<List<ExportRecordEntity>>
}
