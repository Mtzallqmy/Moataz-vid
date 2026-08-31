package com.moatazvid.speech

import com.moatazvid.core.*

@JvmInline value class TranscriptId(val value: String)
@JvmInline value class TranscriptSegmentId(val value: String)
@JvmInline value class TranscriptWordId(val value: String)
@JvmInline value class TranscriptionJobId(val value: String)
@JvmInline value class ModelPackId(val value: String)

data class LanguageCode(val tag: String) {
    init { require(tag == "auto" || tag.matches(Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) }
    val isRightToLeft: Boolean get() = tag.substringBefore('-') in setOf("ar", "fa", "ur", "he")
    companion object {
        val AUTO = LanguageCode("auto")
        val ARABIC = LanguageCode("ar")
        val ENGLISH = LanguageCode("en")
    }
}

enum class TranscriptStatus { PENDING, RUNNING, PARTIAL, READY, FAILED, CANCELLED, STALE }
enum class TranscriptWordType { WORD, PUNCTUATION, AUDIO_EVENT, OTHER }

data class Transcript(
    val id: TranscriptId,
    val sourceId: SourceId,
    val streamId: StreamId?,
    val language: LanguageCode,
    val status: TranscriptStatus,
    val modelPackId: ModelPackId,
    val sourceFingerprint: String,
    val revision: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class TranscriptSegment(
    val id: TranscriptSegmentId,
    val transcriptId: TranscriptId,
    val sourceId: SourceId,
    val index: Int,
    val sourceRange: TimeRangeUs,
    val text: String,
    val normalizedSearchText: String,
    val speakerId: String?,
    val confidence: Float?,
)

data class TranscriptWord(
    val id: TranscriptWordId,
    val transcriptId: TranscriptId,
    val segmentId: TranscriptSegmentId,
    val sourceId: SourceId,
    val index: Int,
    val text: String,
    val normalizedSearchText: String,
    val sourceRange: TimeRangeUs,
    val confidence: Float?,
    val language: LanguageCode,
    val speakerId: String?,
    val type: TranscriptWordType,
) {
    init { require(index >= 0); confidence?.let { require(it in 0f..1f) } }
}

data class TranscriptMetadata(
    val transcriptId: TranscriptId,
    val providerId: String,
    val modelName: String,
    val modelVersion: String,
    val wordTimestampQuality: TimestampQuality,
    val detectedLanguageConfidence: Float?,
    val durationProcessed: DurationUs,
)

enum class TimestampQuality { NATIVE_WORD, TOKEN_DERIVED, ALIGNED_APPROXIMATE, SEGMENT_ONLY }

data class TranscriptBundle(
    val transcript: Transcript,
    val metadata: TranscriptMetadata,
    val segments: List<TranscriptSegment>,
    val words: List<TranscriptWord>,
) {
    init {
        require(words.zipWithNext().all { (a, b) -> a.index < b.index && a.sourceRange.start <= b.sourceRange.start })
        require(segments.zipWithNext().all { (a, b) -> a.index < b.index && a.sourceRange.start <= b.sourceRange.start })
    }
}

