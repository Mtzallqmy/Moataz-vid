package com.moatazvid.speech

import com.moatazvid.core.SourceId

enum class AnalysisStage { AUDIO_PREPARE, TRANSCRIBE, INDEX, SILENCE, AUDIO_QUALITY, DUPLICATES, FILLERS, CAPTION_DRAFTS, PACKED_TRANSCRIPT }
data class AnalysisCacheKey(val sourceId: SourceId, val sourceFingerprint: String, val analyzerVersion: Int, val modelVersion: String?)
data class AnalysisStageResult(val stage: AnalysisStage, val cacheKey: AnalysisCacheKey, val artifactId: String?, val warnings: List<String> = emptyList())

interface AnalysisStageRunner {
    val stage: AnalysisStage
    suspend fun run(context: AnalysisContext): AnalysisStageResult
}
data class AnalysisContext(val sourceId: SourceId, val sourceFingerprint: String, val transcript: TranscriptBundle?, val cancellationRequested: () -> Boolean)

/** Deterministic local orchestration. The app schedules this through WorkManager; no stage mutates Timeline. */
class AnalysisPipeline(private val runners: List<AnalysisStageRunner>) {
    init { require(runners.map { it.stage }.distinct().size == runners.size) }
    suspend fun run(context: AnalysisContext, requested: Set<AnalysisStage>): List<AnalysisStageResult> {
        val results = mutableListOf<AnalysisStageResult>()
        runners.filter { it.stage in requested }.forEach { runner ->
            if (context.cancellationRequested()) return results
            results += runner.run(context)
        }
        return results
    }
}

object AnalysisInvalidationPolicy {
    fun invalidateForSourceFingerprintChange(old: AnalysisCacheKey, newFingerprint: String): Boolean = old.sourceFingerprint != newFingerprint
    fun invalidateForTimelineRevisionChange(@Suppress("UNUSED_PARAMETER") oldRevision: Long, @Suppress("UNUSED_PARAMETER") newRevision: Long): Boolean = false
    fun invalidatePackedTranscriptForPolicyChange(oldPolicyHash: String, newPolicyHash: String): Boolean = oldPolicyHash != newPolicyHash
}
