package com.moatazvid.speech

import com.moatazvid.core.*
import kotlinx.coroutines.flow.Flow

interface SpeechProvider {
    val id: String
    suspend fun transcribe(request: TranscriptionRequest): SpeechResult<TranscriptionHandle>
    suspend fun cancel(jobId: TranscriptionJobId): SpeechResult<Unit>
    suspend fun getCapabilities(): SpeechCapabilities
    suspend fun getModelInfo(modelPackId: ModelPackId): SpeechModelInfo?
    suspend fun estimateRequirements(request: TranscriptionRequest): RequirementEstimate
    fun observe(jobId: TranscriptionJobId): Flow<TranscriptionEvent>
}

data class SpeechCapabilities(
    val supportsWordTimestamps: Boolean,
    val supportsLanguageDetection: Boolean,
    val supportsDiarization: Boolean,
    val supportsAudioEvents: Boolean,
    val supportsStreaming: Boolean,
    val supportsCheckpointResume: Boolean,
)

data class SpeechModelInfo(
    val id: ModelPackId,
    val displayName: String,
    val version: String,
    val multilingual: Boolean,
    val quantization: String?,
    val sizeBytes: Long,
)

data class TranscriptionRequest(
    val jobId: TranscriptionJobId,
    val sourceId: SourceId,
    val streamId: StreamId?,
    val sourceFingerprint: String,
    val modelPackId: ModelPackId,
    val language: LanguageCode,
    val audio: PcmChunkSource,
    val resumeFromChunk: Int = 0,
)

data class RequirementEstimate(
    val ramBytes: Long,
    val temporaryStorageBytes: Long,
    val estimatedRealtimeFactor: Double?,
    val deviceSuitable: Boolean,
    val recommendedModelPackId: ModelPackId?,
    val warnings: List<String>,
)

data class TranscriptionHandle(val jobId: TranscriptionJobId, val resumable: Boolean)

sealed interface TranscriptionEvent {
    data class Started(val jobId: TranscriptionJobId, val totalChunks: Int?) : TranscriptionEvent
    data class Progress(val jobId: TranscriptionJobId, val completedChunks: Int, val totalChunks: Int?, val processed: DurationUs) : TranscriptionEvent
    data class Partial(val jobId: TranscriptionJobId, val checkpoint: TranscriptionCheckpoint, val segments: List<TranscriptSegment>, val words: List<TranscriptWord>) : TranscriptionEvent
    data class Completed(val jobId: TranscriptionJobId, val bundle: TranscriptBundle) : TranscriptionEvent
    data class Failed(val jobId: TranscriptionJobId, val error: SpeechError) : TranscriptionEvent
    data class Cancelled(val jobId: TranscriptionJobId, val lastCheckpoint: TranscriptionCheckpoint?) : TranscriptionEvent
}

sealed interface SpeechResult<out T> {
    data class Success<T>(val value: T) : SpeechResult<T>
    data class Failure(val error: SpeechError) : SpeechResult<Nothing>
}

sealed interface SpeechError {
    data class NoAudioTrack(val sourceId: SourceId) : SpeechError
    data class CorruptedAudio(val sourceId: SourceId, val detail: String) : SpeechError
    data class ModelNotInstalled(val modelPackId: ModelPackId) : SpeechError
    data class CorruptedModel(val modelPackId: ModelPackId) : SpeechError
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : SpeechError
    data class InsufficientMemory(val requiredBytes: Long, val availableBytes: Long, val suggested: ModelPackId?) : SpeechError
    data class NativeRuntimeFailure(val safeDetail: String) : SpeechError
    data object Cancelled : SpeechError
}

interface PcmChunkSource {
    val sampleRateHz: Int
    val channels: Int
    val estimatedDuration: DurationUs?
    suspend fun chunks(startAtIndex: Int = 0): Flow<PcmChunk>
}

data class PcmChunk(
    val index: Int,
    val sourceStart: TimeUs,
    val samplesMono16Khz: FloatArray,
    val overlapBefore: DurationUs,
) {
    init { require(index >= 0); require(samplesMono16Khz.all { it.isFinite() && it in -1f..1f }) }
    val duration: DurationUs get() = DurationUs(samplesMono16Khz.size * 1_000_000L / 16_000L)
}

