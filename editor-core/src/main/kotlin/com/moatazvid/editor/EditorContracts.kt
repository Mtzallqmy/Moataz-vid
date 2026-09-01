package com.moatazvid.editor

import com.moatazvid.ai.editor.*
import com.moatazvid.core.*
import com.moatazvid.media.MediaResult
import com.moatazvid.speech.*
import kotlinx.coroutines.flow.Flow

interface EditorPlayer {
    val state: Flow<PlaybackUiState>
    suspend fun prepare(project: AiEditableProject, useProxy: Boolean, quality: PreviewQuality): MediaResult<Unit>
    suspend fun preview(project: AiEditableProject): MediaResult<Unit>
    suspend fun play(); suspend fun pause(); suspend fun seekTo(time: TimeUs); suspend fun setMuted(muted: Boolean); suspend fun release()
}

interface EditorProjectGateway {
    suspend fun load(projectId: ProjectId): AiEditableProject?
    suspend fun transcript(projectId: ProjectId): TranscriptBundle?
    fun observeJobs(projectId: ProjectId): Flow<List<BackgroundJobUiState>>
    suspend fun importMedia(projectId: ProjectId, uri: String, transcribe: Boolean): Result<SourceId>
}

interface ThumbnailRepository { suspend fun visibleThumbnails(sourceId: SourceId, sourceRange: TimeRangeUs, pixelWidth: Int): List<String> }
interface WaveformRepository { suspend fun visibleWaveform(sourceId: SourceId, sourceRange: TimeRangeUs, pixelWidth: Int): FloatArray }
interface EditorStatePersistence { suspend fun save(state: RestoredEditorState); suspend fun restore(projectId: ProjectId): RestoredEditorState? }

sealed interface ManualEditResult { data class Success(val commit: CommitResult.Success) : ManualEditResult; data class Failure(val message: String) : ManualEditResult }
