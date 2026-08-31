package com.moatazvid.speech

import com.moatazvid.core.*

enum class TranscriptionJobStatus { QUEUED, PREPARING_AUDIO, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class TranscriptionJob(
    val id: TranscriptionJobId,
    val sourceId: SourceId,
    val streamId: StreamId?,
    val modelPackId: ModelPackId,
    val language: LanguageCode,
    val sourceFingerprint: String,
    val status: TranscriptionJobStatus,
    val currentChunk: Int,
    val totalChunks: Int?,
    val progressPermille: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val errorCode: String?,
) {
    init { require(currentChunk >= 0); require(progressPermille in 0..1000) }
}

data class TranscriptionCheckpoint(
    val jobId: TranscriptionJobId,
    val sourceFingerprint: String,
    val completedChunkExclusive: Int,
    val committedWordCount: Int,
    val committedSegmentCount: Int,
    val lastCommittedSourceTime: TimeUs,
    val updatedAtEpochMs: Long,
)

interface TranscriptionStore {
    suspend fun createJob(job: TranscriptionJob)
    suspend fun updateJob(job: TranscriptionJob)
    suspend fun checkpoint(checkpoint: TranscriptionCheckpoint, segments: List<TranscriptSegment>, words: List<TranscriptWord>)
    suspend fun loadCheckpoint(jobId: TranscriptionJobId): TranscriptionCheckpoint?
    suspend fun finalize(bundle: TranscriptBundle)
    suspend fun load(transcriptId: TranscriptId): TranscriptBundle?
    suspend fun invalidateForSource(sourceId: SourceId, newFingerprint: String)
}

