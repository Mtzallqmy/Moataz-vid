package com.moatazvid.ai.editor

import com.moatazvid.ai.provider.*
import com.moatazvid.core.*
import com.moatazvid.speech.*

enum class ContextSection { PROJECT_INFO, TIMELINE, CLIP_DETAILS, TRANSCRIPT_SEARCH, TRANSCRIPT_RANGE, WORD_BOUNDARIES, SILENCE, DUPLICATES, AUDIO_ANALYSIS, SCENES, CONSTRAINTS, PROTECTED_RANGES, HISTORY, VISUAL_SAMPLES }
data class ContextFragment(val section: ContextSection, val label: String, val content: String, val estimatedTokens: Long, val dataOnly: Boolean = true)
data class AiTaskContext(
    val projectId: ProjectId, val projectRevision: Long, val intent: AiIntent, val userInstruction: String,
    val fragments: List<ContextFragment>, val omittedSections: Set<ContextSection>, val estimatedTokens: Long,
    val promptVersion: String,
)

data class ContextBudget(
    val modelContext: Long, val reservedOutput: Long, val toolOverhead: Long, val schemaOverhead: Long,
) { val availableInput: Long get() = (modelContext - reservedOutput - toolOverhead - schemaOverhead).coerceAtLeast(0) }

class ContextBudgetManager(private val estimator: TokenBudgetEstimator = ConservativeTokenBudgetEstimator()) {
    fun fit(fragments: List<ContextFragment>, budget: ContextBudget): Pair<List<ContextFragment>, Set<ContextSection>> {
        val ordered = fragments.sortedBy { priority(it.section) }
        var used = 0L
        val accepted = mutableListOf<ContextFragment>()
        val omitted = mutableSetOf<ContextSection>()
        ordered.forEach { fragment -> if (used + fragment.estimatedTokens <= budget.availableInput) { accepted += fragment; used += fragment.estimatedTokens } else omitted += fragment.section }
        return accepted to omitted
    }
    private fun priority(section: ContextSection) = when (section) {
        ContextSection.PROTECTED_RANGES, ContextSection.CONSTRAINTS -> 0
        ContextSection.PROJECT_INFO, ContextSection.TRANSCRIPT_SEARCH -> 1
        ContextSection.TIMELINE, ContextSection.WORD_BOUNDARIES, ContextSection.SILENCE, ContextSection.DUPLICATES -> 2
        else -> 3
    }
}

interface AiProjectReadTools {
    suspend fun projectInfo(projectId: ProjectId): ContextFragment
    suspend fun timelineSummary(projectId: ProjectId): ContextFragment
    suspend fun clipDetails(projectId: ProjectId, clipId: ClipId?): ContextFragment?
    suspend fun searchTranscript(projectId: ProjectId, query: String): ContextFragment
    suspend fun transcriptRange(projectId: ProjectId, range: TimeRangeUs?): ContextFragment?
    suspend fun wordBoundaries(projectId: ProjectId, around: TimeUs?): ContextFragment?
    suspend fun silenceRanges(projectId: ProjectId): ContextFragment
    suspend fun duplicateCandidates(projectId: ProjectId, query: String?): ContextFragment
    suspend fun audioAnalysis(projectId: ProjectId): ContextFragment
    suspend fun sceneBoundaries(projectId: ProjectId): ContextFragment
    suspend fun constraints(projectId: ProjectId): ContextFragment
    suspend fun protectedRanges(projectId: ProjectId): ContextFragment
    suspend fun recentHistory(projectId: ProjectId): ContextFragment
    suspend fun visualSamples(projectId: ProjectId, range: TimeRangeUs?): ContextFragment
}

enum class VisionPermissionMode { LOCAL_ONLY, ASK_EACH_TIME, ALLOW_SELECTED_PROVIDER }
data class VisionPermissionPolicy(val mode: VisionPermissionMode, val allowedProviderId: ProviderId? = null) {
    fun canSend(providerId: ProviderId, capability: TriState, userApprovedThisRequest: Boolean): Boolean = capability == TriState.YES && when (mode) {
        VisionPermissionMode.LOCAL_ONLY -> false
        VisionPermissionMode.ASK_EACH_TIME -> userApprovedThisRequest
        VisionPermissionMode.ALLOW_SELECTED_PROVIDER -> allowedProviderId == providerId
    }
}

class AiContextBuilder(private val tools: AiProjectReadTools, private val budgets: ContextBudgetManager = ContextBudgetManager()) {
    suspend fun build(projectId: ProjectId, revision: Long, message: String, intent: IntentResult, budget: ContextBudget): AiTaskContext {
        val fragments = mutableListOf<ContextFragment>()
        fragments += tools.projectInfo(projectId)
        when (intent.intent) {
            AiIntent.EDIT_PROJECT, AiIntent.CAPTION_EDIT, AiIntent.AUDIO_EDIT, AiIntent.VISUAL_EDIT, AiIntent.STRUCTURE_EDIT -> {
                // video-use primary reading view: packed transcript first, timeline/visual detail second.
                fragments += tools.searchTranscript(projectId, "")
                fragments += tools.timelineSummary(projectId)
                fragments += tools.constraints(projectId)
                fragments += tools.protectedRanges(projectId)
                tools.wordBoundaries(projectId, null)?.let(fragments::add)
            }
            AiIntent.FIND_CONTENT -> fragments += tools.searchTranscript(projectId, intent.entities["query"].orEmpty())
            AiIntent.EXPLAIN_EDIT -> fragments += tools.recentHistory(projectId)
            else -> Unit
        }
        val lower = message.lowercase()
        if ("صمت" in lower || "silence" in lower) {
            fragments += tools.silenceRanges(projectId)
            tools.wordBoundaries(projectId, null)?.let(fragments::add)
        }
        if ("تكرار" in lower || "أفضل" in lower || "take" in lower) fragments += tools.duplicateCandidates(projectId, intent.entities["query"])
        if (intent.intent == AiIntent.AUDIO_EDIT) fragments += tools.audioAnalysis(projectId)
        val (accepted, omitted) = budgets.fit(fragments.distinctBy { it.section to it.label }, budget)
        return AiTaskContext(projectId, revision, intent.intent, message, accepted, omitted, accepted.sumOf { it.estimatedTokens }, PromptRepository.CURRENT_VERSION)
    }
}
