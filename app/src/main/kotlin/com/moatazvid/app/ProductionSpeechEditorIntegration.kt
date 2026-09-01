package com.moatazvid.app

import com.moatazvid.ai.editor.*
import com.moatazvid.core.*
import com.moatazvid.editor.BackgroundJobUiState
import com.moatazvid.editor.EditorProjectGateway
import com.moatazvid.speech.*
import com.moatazvid.storage.room.ProjectConstraintEntity
import com.moatazvid.storage.room.ProtectedRangeEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

class ProductionSpeechEditorGateway(
    private val repository: ProductionProjectRepository,
    private val speech: ProductionSpeechRuntime,
) : EditorProjectGateway {
    private val base = ProductionEditorGateway(repository)

    override suspend fun load(projectId: ProjectId): AiEditableProject? = base.load(projectId)
    override suspend fun transcript(projectId: ProjectId): TranscriptBundle? = speech.transcriptForProject(projectId)
    override fun observeJobs(projectId: ProjectId): Flow<List<BackgroundJobUiState>> = speech.jobs

    override suspend fun importMedia(projectId: ProjectId, uri: String, transcribe: Boolean): Result<SourceId> {
        val imported = base.importMedia(projectId, uri, transcribe)
        imported.getOrNull()?.let { sourceId ->
            if (transcribe) speech.queueTranscription(sourceId)
        }
        return imported
    }
}

/** Makes persisted local transcripts/analysis available to deterministic edits and cloud planning. */
class ProductionSpeechAiDataSource(
    private val repository: ProductionProjectRepository,
    private val speech: ProductionSpeechRuntime,
) : AiEditorDataSource {
    private val base = ProductionAiDataSource(repository)
    private val search = TranscriptSearchEngine()
    private val duplicates = DuplicateDetector()
    private val fillers = FillerDetector()
    private val packed = PackedTranscriptBuilder()

    override suspend fun project(projectId: ProjectId): AiEditableProject? = base.project(projectId)

    override suspend fun silence(projectId: ProjectId): List<SilenceRange> = transcripts(projectId).flatMap { bundle ->
        val words = bundle.words.filter { it.type == TranscriptWordType.WORD }.sortedBy { it.sourceRange.start.value }
        if (words.isEmpty()) return@flatMap emptyList()
        buildList {
            val leadingEnd = words.first().sourceRange.start.value
            if (leadingEnd >= MIN_SILENCE_US) add(SilenceRange(bundle.transcript.sourceId, TimeRangeUs(TimeUs(0), TimeUs(leadingEnd)), -60.0))
            words.zipWithNext().forEach { (left, right) ->
                val start = left.sourceRange.endExclusive.value
                val end = right.sourceRange.start.value
                if (end - start >= MIN_SILENCE_US) add(SilenceRange(bundle.transcript.sourceId, TimeRangeUs(TimeUs(start), TimeUs(end)), -60.0))
            }
            val processedEnd = bundle.metadata.durationProcessed.value
            val trailingStart = words.last().sourceRange.endExclusive.value
            if (processedEnd - trailingStart >= MIN_SILENCE_US) add(SilenceRange(bundle.transcript.sourceId, TimeRangeUs(TimeUs(trailingStart), TimeUs(processedEnd)), -60.0))
        }
    }

    override suspend fun transcriptWords(projectId: ProjectId): List<TranscriptWord> = transcripts(projectId).flatMap { it.words }

    override suspend fun takeGroups(projectId: ProjectId): List<TakeCandidateGroup> {
        val editable = project(projectId) ?: return emptyList()
        val bundles = transcripts(projectId)
        val segmentById = bundles.flatMap { it.segments }.associateBy { it.id }
        val words = bundles.flatMap { it.words }
        return bundles.flatMap { duplicates.detect(it.segments) }.mapNotNull { duplicate ->
            val first = segmentById[duplicate.first] ?: return@mapNotNull null
            val second = segmentById[duplicate.second] ?: return@mapNotNull null
            val firstCandidate = candidateFor(editable, first, words) ?: return@mapNotNull null
            val secondCandidate = candidateFor(editable, second, words) ?: return@mapNotNull null
            if (firstCandidate.clipId == secondCandidate.clipId) return@mapNotNull null
            TakeCandidateGroup("duplicate_${first.id.value}_${second.id.value}", listOf(firstCandidate, secondCandidate))
        }.distinctBy { it.candidates.map(TakeCandidate::clipId).toSet() }
    }

    override suspend fun resolvePreservedTopic(projectId: ProjectId, userText: String): List<ProtectedRange> {
        val words = transcriptWords(projectId)
        if (words.isEmpty()) return emptyList()
        val normalized = ArabicTextNormalizer.normalize(userText)
        val meaningful = normalized.split(' ').filter { it.length >= 3 }.takeLast(8)
        val query = meaningful.joinToString(" ")
        if (query.isBlank()) return emptyList()
        return search.search(words, TranscriptSearchQuery(query, fuzzy = true, limit = 8)).map { hit ->
            ProtectedRange(
                id = "protected_${UUID.randomUUID()}",
                projectId = projectId,
                sourceId = hit.sourceId,
                sourceRange = hit.sourceRange,
                reason = userText,
            )
        }
    }

    override suspend fun saveConstraint(constraint: ProjectConstraint) {
        val now = System.currentTimeMillis()
        repository.database.projectDao().upsertConstraint(
            ProjectConstraintEntity(
                constraintId = constraint.id.value,
                projectId = constraint.projectId.value,
                type = constraint.type.name,
                priority = constraint.priority.name,
                payloadJson = JSONObject()
                    .putOpt("sourceId", constraint.sourceId?.value)
                    .putOpt("startUs", constraint.sourceRange?.start?.value)
                    .putOpt("endUs", constraint.sourceRange?.endExclusive?.value)
                    .toString(),
                summary = constraint.text,
                source = constraint.source.name,
                enabled = constraint.active,
                createdAtEpochMs = constraint.createdAtEpochMs,
                updatedAtEpochMs = now,
            )
        )
        if (constraint.sourceId != null && constraint.sourceRange != null) {
            repository.database.projectDao().upsertProtectedRange(
                ProtectedRangeEntity(
                    protectedRangeId = "protected_${constraint.id.value}",
                    projectId = constraint.projectId.value,
                    scope = "SOURCE",
                    sourceId = constraint.sourceId.value,
                    sequenceId = null,
                    startUs = constraint.sourceRange.start.value,
                    endUs = constraint.sourceRange.endExclusive.value,
                    protectionFlags = 1L,
                    reason = constraint.text,
                    createdBy = constraint.createdBy,
                    enabled = constraint.active,
                    createdAtEpochMs = constraint.createdAtEpochMs,
                )
            )
        }
    }

    override suspend fun projectInfo(projectId: ProjectId): ContextFragment = base.projectInfo(projectId)
    override suspend fun timelineSummary(projectId: ProjectId): ContextFragment = base.timelineSummary(projectId)
    override suspend fun clipDetails(projectId: ProjectId, clipId: ClipId?): ContextFragment? = base.clipDetails(projectId, clipId)

    override suspend fun searchTranscript(projectId: ProjectId, query: String): ContextFragment {
        val words = transcriptWords(projectId)
        val content = if (query.isBlank()) {
            packed.render(packed.build(words).take(40))
        } else {
            search.search(words, TranscriptSearchQuery(query, fuzzy = true, limit = 30)).joinToString("\n") { hit ->
                "${hit.sourceId.value}|${hit.sourceRange.start.value}-${hit.sourceRange.endExclusive.value}|score=${"%.2f".format(hit.score)}|${hit.text}"
            }
        }
        return fragment(ContextSection.TRANSCRIPT_SEARCH, "transcript-search", content.ifBlank { "No matching transcript text" })
    }

    override suspend fun transcriptRange(projectId: ProjectId, range: TimeRangeUs?): ContextFragment? {
        val words = transcriptWords(projectId)
        if (words.isEmpty()) return null
        val selected = if (range == null) words else words.filter { it.sourceRange.overlaps(range) }
        if (selected.isEmpty()) return null
        return fragment(ContextSection.TRANSCRIPT_RANGE, "transcript-range", packed.render(packed.build(selected).take(60)))
    }

    override suspend fun wordBoundaries(projectId: ProjectId, around: TimeUs?): ContextFragment? {
        val words = transcriptWords(projectId).filter { it.type == TranscriptWordType.WORD }
        if (words.isEmpty()) return null
        val selected = if (around == null) words.take(80) else words.sortedBy { kotlin.math.abs(it.sourceRange.start.value - around.value) }.take(40).sortedBy { it.sourceRange.start.value }
        val content = selected.joinToString("\n") { "${it.sourceId.value}|${it.sourceRange.start.value}|${it.sourceRange.endExclusive.value}|${it.text}" }
        return fragment(ContextSection.WORD_BOUNDARIES, "word-boundaries", content)
    }

    override suspend fun silenceRanges(projectId: ProjectId): ContextFragment {
        val ranges = silence(projectId)
        val content = ranges.joinToString("\n") { "${it.sourceId.value}|${it.sourceRange.start.value}-${it.sourceRange.endExclusive.value}|gapUs=${it.sourceRange.duration.value}" }
        return fragment(ContextSection.SILENCE, "silence", content.ifBlank { "No transcript-timestamp silence gaps >= ${MIN_SILENCE_US}us" })
    }

    override suspend fun duplicateCandidates(projectId: ProjectId, query: String?): ContextFragment {
        val bundles = transcripts(projectId)
        val content = bundles.flatMap { duplicates.detect(it.segments) }.joinToString("\n") {
            "${it.first.value}|${it.second.value}|similarity=${"%.3f".format(it.similarity)}|preferred=${it.preferred?.value.orEmpty()}"
        }
        return fragment(ContextSection.DUPLICATES, "duplicates", content.ifBlank { "No transcript duplicate candidates" })
    }

    override suspend fun audioAnalysis(projectId: ProjectId): ContextFragment {
        val bundles = transcripts(projectId)
        val content = bundles.joinToString("\n") { bundle ->
            val duration = bundle.metadata.durationProcessed.value.coerceAtLeast(1L)
            val spoken = bundle.words.filter { it.type == TranscriptWordType.WORD }.sumOf { it.sourceRange.duration.value }.coerceAtMost(duration)
            val silence = silenceForBundle(bundle).sumOf { it.sourceRange.duration.value }.coerceAtMost(duration)
            "${bundle.transcript.sourceId.value}|durationUs=$duration|speechTimestampRatio=${"%.3f".format(spoken.toDouble() / duration)}|silenceGapRatio=${"%.3f".format(silence.toDouble() / duration)}"
        }
        return fragment(ContextSection.AUDIO_ANALYSIS, "audio", content.ifBlank { "No local transcript analysis available" })
    }

    override suspend fun sceneBoundaries(projectId: ProjectId): ContextFragment {
        val editable = project(projectId)
        val content = editable?.snapshot?.items?.filter { it.type == TimelineItemType.VIDEO }?.sortedBy { it.timelineStart.value }?.joinToString("\n") {
            "clip=${it.id.value}|timeline=${it.timelineStart.value}-${it.timelineStart.value + it.timelineDuration.value}|source=${it.sourceId?.value.orEmpty()}"
        }.orEmpty()
        return fragment(ContextSection.SCENES, "timeline-video-boundaries", content.ifBlank { "No video clip boundaries" })
    }

    override suspend fun constraints(projectId: ProjectId): ContextFragment {
        val rows = repository.database.projectDao().constraints(projectId.value)
        return fragment(ContextSection.CONSTRAINTS, "constraints", rows.joinToString("\n") { "${it.type}|${it.priority}|${it.summary}" }.ifBlank { "No saved constraints" })
    }

    override suspend fun protectedRanges(projectId: ProjectId): ContextFragment {
        val rows = repository.database.projectDao().protectedRanges(projectId.value)
        return fragment(ContextSection.PROTECTED_RANGES, "protected", rows.joinToString("\n") { "${it.sourceId.orEmpty()}|${it.startUs}-${it.endUs}|${it.reason}" }.ifBlank { "No protected ranges" })
    }

    override suspend fun recentHistory(projectId: ProjectId): ContextFragment = base.recentHistory(projectId)
    override suspend fun visualSamples(projectId: ProjectId, range: TimeRangeUs?): ContextFragment = base.visualSamples(projectId, range)

    private suspend fun transcripts(projectId: ProjectId) = speech.allTranscriptsForProject(projectId)

    private fun candidateFor(project: AiEditableProject, segment: TranscriptSegment, words: List<TranscriptWord>): TakeCandidate? {
        val clip = project.snapshot.items.firstOrNull { item -> item.sourceId == segment.sourceId && item.sourceRange?.overlaps(segment.sourceRange) == true } ?: return null
        val segmentWords = words.filter { it.segmentId == segment.id }
        return TakeCandidate(
            clipId = clip.id,
            text = segment.text,
            duration = segment.sourceRange.duration,
            audioScore = (segment.confidence ?: 0.7f).toDouble(),
            visualScore = null,
            speechConfidence = (segment.confidence ?: 0.7f).toDouble(),
            fillerCount = fillers.detect(segmentWords).size,
            slipCount = 0,
            silenceRatio = 0.0,
        )
    }

    private fun silenceForBundle(bundle: TranscriptBundle): List<SilenceRange> {
        val words = bundle.words.filter { it.type == TranscriptWordType.WORD }.sortedBy { it.sourceRange.start.value }
        if (words.isEmpty()) return emptyList()
        return words.zipWithNext().mapNotNull { (left, right) ->
            val start = left.sourceRange.endExclusive.value
            val end = right.sourceRange.start.value
            if (end - start >= MIN_SILENCE_US) SilenceRange(bundle.transcript.sourceId, TimeRangeUs(TimeUs(start), TimeUs(end)), -60.0) else null
        }
    }

    private fun fragment(section: ContextSection, label: String, content: String) = ContextFragment(
        section = section,
        label = label,
        content = content,
        estimatedTokens = ((content.length + 3) / 4).toLong().coerceAtLeast(1),
        dataOnly = true,
    )

    companion object { private const val MIN_SILENCE_US = 500_000L }
}
