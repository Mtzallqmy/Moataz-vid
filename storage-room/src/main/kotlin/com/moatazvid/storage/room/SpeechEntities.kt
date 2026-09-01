package com.moatazvid.storage.room

import androidx.room3.*

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(TranscriptEntity::class, ["transcriptId"], ["transcriptId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["transcriptId", "segmentIndex"], unique = true), Index(value = ["sourceId", "startUs"])],
)
data class TranscriptSegmentEntity(
    @PrimaryKey val segmentId: String,
    val transcriptId: String,
    val sourceId: String,
    val segmentIndex: Int,
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val normalizedSearchText: String,
    val speakerId: String?,
    val confidence: Float?,
)

@Entity(
    tableName = "transcript_words",
    foreignKeys = [ForeignKey(TranscriptEntity::class, ["transcriptId"], ["transcriptId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["transcriptId", "wordIndex"], unique = true), Index(value = ["sourceId", "startUs"]), Index("normalizedSearchText")],
)
data class TranscriptWordEntity(
    @PrimaryKey val wordId: String,
    val transcriptId: String,
    val segmentId: String,
    val sourceId: String,
    val wordIndex: Int,
    val text: String,
    val normalizedSearchText: String,
    val startUs: Long,
    val endUs: Long,
    val confidence: Float?,
    val languageTag: String,
    val speakerId: String?,
    val wordType: String,
)

@Entity(
    tableName = "transcription_jobs",
    foreignKeys = [ForeignKey(MediaSourceEntity::class, ["sourceId"], ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index("status")],
)
data class TranscriptionJobEntity(
    @PrimaryKey val jobId: String,
    val sourceId: String,
    val streamId: String?,
    val modelPackId: String,
    val languageTag: String,
    val sourceFingerprint: String,
    val status: String,
    val currentChunk: Int,
    val totalChunks: Int?,
    val progressPermille: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val errorCode: String?,
)

@Entity(
    tableName = "transcription_checkpoints",
    foreignKeys = [ForeignKey(TranscriptionJobEntity::class, ["jobId"], ["jobId"], onDelete = ForeignKey.CASCADE)],
)
data class TranscriptionCheckpointEntity(
    @PrimaryKey val jobId: String,
    val sourceFingerprint: String,
    val completedChunkExclusive: Int,
    val committedWordCount: Int,
    val committedSegmentCount: Int,
    val lastCommittedSourceTimeUs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "speech_model_packs", indices = [Index("status")])
data class SpeechModelPackEntity(
    @PrimaryKey val modelPackId: String,
    val displayName: String,
    val version: String,
    val sizeBytes: Long,
    val requiredRamBytes: Long,
    val multilingual: Boolean,
    val languagesJson: String,
    val sha256: String,
    val relativePath: String,
    val license: String,
    val sourceUrl: String,
    val status: String,
    val activeLeaseCount: Int,
)

@Dao
interface SpeechDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertJob(job: TranscriptionJobEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCheckpoint(checkpoint: TranscriptionCheckpointEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTranscript(transcript: TranscriptEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSegments(segments: List<TranscriptSegmentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWords(words: List<TranscriptWordEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertModelPack(pack: SpeechModelPackEntity)

    @Query("SELECT * FROM transcription_jobs WHERE jobId = :jobId") suspend fun job(jobId: String): TranscriptionJobEntity?
    @Query("SELECT * FROM transcription_jobs WHERE sourceId = :sourceId ORDER BY createdAtEpochMs DESC") suspend fun jobsForSource(sourceId: String): List<TranscriptionJobEntity>
    @Query("SELECT * FROM transcription_jobs WHERE status IN ('QUEUED','PREPARING_AUDIO','RUNNING','PAUSED') ORDER BY updatedAtEpochMs") suspend fun activeJobs(): List<TranscriptionJobEntity>
    @Query("SELECT * FROM transcription_checkpoints WHERE jobId = :jobId") suspend fun checkpoint(jobId: String): TranscriptionCheckpointEntity?

    @Query("SELECT * FROM transcripts WHERE transcriptId = :transcriptId") suspend fun transcript(transcriptId: String): TranscriptEntity?
    @Query("SELECT * FROM transcripts WHERE sourceId = :sourceId AND status = 'READY' ORDER BY createdAtEpochMs DESC LIMIT 1") suspend fun readyTranscriptForSource(sourceId: String): TranscriptEntity?
    @Query("SELECT * FROM transcripts WHERE sourceId IN (:sourceIds) AND status = 'READY' ORDER BY createdAtEpochMs DESC") suspend fun readyTranscriptsForSources(sourceIds: List<String>): List<TranscriptEntity>
    @Query("SELECT * FROM transcript_segments WHERE transcriptId = :transcriptId ORDER BY segmentIndex") suspend fun segments(transcriptId: String): List<TranscriptSegmentEntity>
    @Query("SELECT * FROM transcript_words WHERE transcriptId = :transcriptId ORDER BY wordIndex") suspend fun words(transcriptId: String): List<TranscriptWordEntity>
    @Query("SELECT * FROM transcript_words WHERE transcriptId = :transcriptId AND normalizedSearchText LIKE '%' || :normalized || '%' ORDER BY wordIndex LIMIT :limit")
    suspend fun searchWords(transcriptId: String, normalized: String, limit: Int): List<TranscriptWordEntity>
    @Query("UPDATE transcripts SET status = 'STALE' WHERE sourceId = :sourceId AND sourceFingerprint != :newFingerprint")
    suspend fun invalidateChangedSource(sourceId: String, newFingerprint: String): Int

    @Query("SELECT * FROM speech_model_packs ORDER BY requiredRamBytes") suspend fun modelPacks(): List<SpeechModelPackEntity>
    @Query("SELECT * FROM speech_model_packs WHERE modelPackId = :modelPackId") suspend fun modelPack(modelPackId: String): SpeechModelPackEntity?
    @Query("UPDATE speech_model_packs SET status = :status WHERE modelPackId = :modelPackId") suspend fun updateModelStatus(modelPackId: String, status: String): Int
    @Query("UPDATE speech_model_packs SET activeLeaseCount = :count WHERE modelPackId = :modelPackId") suspend fun updateLeaseCount(modelPackId: String, count: Int): Int
    @Query("DELETE FROM speech_model_packs WHERE modelPackId = :modelPackId") suspend fun deleteModelPack(modelPackId: String): Int
}
