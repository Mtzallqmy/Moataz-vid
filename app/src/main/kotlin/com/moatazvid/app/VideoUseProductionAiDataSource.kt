package com.moatazvid.app

import com.moatazvid.ai.editor.AiEditorDataSource
import com.moatazvid.ai.editor.ContextFragment
import com.moatazvid.ai.editor.ContextSection
import com.moatazvid.core.ProjectId
import com.moatazvid.core.TimeRangeUs
import com.moatazvid.videouse.VideoUsePackedTranscriptBuilder

/**
 * Presents the AI with video-use's packed transcript as its primary reading view while delegating
 * all project, analysis, constraint and search capabilities to the production speech data source.
 */
class VideoUseProductionAiDataSource(
    private val delegate: ProductionSpeechAiDataSource,
) : AiEditorDataSource by delegate {
    private val packed = VideoUsePackedTranscriptBuilder()

    override suspend fun searchTranscript(projectId: ProjectId, query: String): ContextFragment {
        if (query.isNotBlank()) return delegate.searchTranscript(projectId, query)
        val words = delegate.transcriptWords(projectId)
        val content = packed.render(packed.build(words).take(MAX_PACKED_PHRASES))
        return fragment(
            ContextSection.TRANSCRIPT_SEARCH,
            "video-use-packed-transcript",
            content.ifBlank { "No word-level transcript is available yet" },
        )
    }

    override suspend fun transcriptRange(projectId: ProjectId, range: TimeRangeUs?): ContextFragment? {
        val words = delegate.transcriptWords(projectId)
        val selected = if (range == null) words else words.filter { it.sourceRange.overlaps(range) }
        if (selected.isEmpty()) return null
        val content = packed.render(packed.build(selected).take(MAX_RANGE_PHRASES))
        return fragment(ContextSection.TRANSCRIPT_RANGE, "video-use-transcript-range", content)
    }

    private fun fragment(section: ContextSection, label: String, content: String) = ContextFragment(
        section = section,
        label = label,
        content = content,
        estimatedTokens = ((content.length + 3) / 4).toLong().coerceAtLeast(1),
        dataOnly = true,
    )

    companion object {
        private const val MAX_PACKED_PHRASES = 240
        private const val MAX_RANGE_PHRASES = 100
    }
}
