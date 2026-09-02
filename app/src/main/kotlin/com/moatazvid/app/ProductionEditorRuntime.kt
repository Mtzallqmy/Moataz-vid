package com.moatazvid.app

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import com.moatazvid.ai.editor.*
import com.moatazvid.ai.provider.*
import com.moatazvid.core.*
import com.moatazvid.editor.*
import com.moatazvid.media.*
import com.moatazvid.media.media3.*
import com.moatazvid.speech.*
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProductionEditorGateway(
    private val repository: ProductionProjectRepository,
) : EditorProjectGateway {
    override suspend fun load(projectId: ProjectId): AiEditableProject? = repository.loadEditableProject(projectId)
    override suspend fun transcript(projectId: ProjectId): TranscriptBundle? = null
    override fun observeJobs(projectId: ProjectId): Flow<List<BackgroundJobUiState>> = flowOf(emptyList())
    override suspend fun importMedia(projectId: ProjectId, uri: String, transcribe: Boolean): Result<SourceId> =
        runCatching { repository.importMedia(projectId, Uri.parse(uri)) }
}

class SharedPreferencesEditorStatePersistence(context: Context) : EditorStatePersistence {
    private val preferences = context.applicationContext.getSharedPreferences("editor-state-v1", Context.MODE_PRIVATE)

    override suspend fun save(state: RestoredEditorState) {
        preferences.edit()
            .putLong(key(state.projectId, "playhead"), state.playheadUs)
            .putString(key(state.projectId, "zoom"), state.pixelsPerSecond.toString())
            .putString(key(state.projectId, "scroll"), state.scrollOffsetPx.toString())
            .putString(key(state.projectId, "selection"), state.selectedClipIds.joinToString("\u001f"))
            .putString(key(state.projectId, "pending"), state.pendingEditId)
            .apply()
    }

    override suspend fun restore(projectId: ProjectId): RestoredEditorState? {
        if (!preferences.contains(key(projectId, "playhead"))) return null
        return RestoredEditorState(
            projectId = projectId,
            playheadUs = preferences.getLong(key(projectId, "playhead"), 0L),
            pixelsPerSecond = preferences.getString(key(projectId, "zoom"), null)?.toDoubleOrNull()?.coerceIn(4.0, 2_000.0) ?: 64.0,
            scrollOffsetPx = preferences.getString(key(projectId, "scroll"), null)?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            selectedClipIds = preferences.getString(key(projectId, "selection"), "").orEmpty().split('\u001f').filter(String::isNotBlank),
            pendingEditId = preferences.getString(key(projectId, "pending"), null),
        )
    }

    private fun key(projectId: ProjectId, suffix: String) = "${projectId.value}:$suffix"
}

/** Resolves only URIs already owned by the local project database; model output never supplies media URIs. */
class ProductionMedia3InputResolver(
    private val repository: ProductionProjectRepository,
) : Media3InputResolver {
    override suspend fun tokenFor(input: MediaInput, preferProxy: Boolean): String = when (input) {
        is MediaInput.Original -> {
            val proxy = if (preferProxy) repository.database.cacheDao().proxies(input.sourceId.value)
                .firstOrNull { it.status == "READY" } else null
            val proxyUri = proxy?.let { repository.database.mediaDao().fileRef(it.fileRefId)?.uriString }
            proxyUri ?: requireNotNull(repository.sourceUri(input.sourceId)) { "Missing source ${input.sourceId.value}" }.toString()
        }
        is MediaInput.Proxy -> {
            val proxy = repository.database.cacheDao().proxies(input.sourceId.value).firstOrNull { it.proxyId == input.proxyId }
            proxy?.let { repository.database.mediaDao().fileRef(it.fileRefId)?.uriString }
                ?: requireNotNull(repository.sourceUri(input.sourceId)) { "Missing source ${input.sourceId.value}" }.toString()
        }
        is MediaInput.Asset -> requireNotNull(repository.assetUri(input.assetId)) { "Missing asset ${input.assetId.value}" }.toString()
    }

    override suspend fun sourceDurationUs(input: MediaInput, preferProxy: Boolean): Long? = when (input) {
        is MediaInput.Original -> repository.database.mediaDao().source(input.sourceId.value)?.durationUs
        is MediaInput.Proxy -> repository.database.mediaDao().source(input.sourceId.value)?.durationUs
        is MediaInput.Asset -> null
    }
}

/** Converts the editable domain state into the shared preview/export RenderGraph. */
class ProductionRenderGraphMapper {
    private val creative = CreativeRenderMapper(Media3Engine.DEFAULT_BOUND_FEATURES)

    fun map(project: AiEditableProject): RenderGraph {
        val snapshot = project.snapshot
        val trackById = snapshot.tracks.associateBy { it.id }
        val videos = snapshot.items.filter { it.enabled && it.type == TimelineItemType.VIDEO && it.sourceId != null && it.sourceRange != null }.map { item ->
            val property = project.clipProperties[item.id] ?: ClipEditProperties()
            val sourceRange = requireNotNull(item.sourceRange)
            VideoLayer(
                id = item.id,
                trackId = item.trackId,
                input = MediaInput.Original(requireNotNull(item.sourceId)),
                sourceRange = sourceRange,
                placement = TimelinePlacement(item.timelineStart, item.timelineDuration),
                transform = property.transform,
                opacity = 1f,
                speed = constantSpeed(sourceRange, property.speed),
                effects = emptyList(),
                includeSourceAudio = trackById[item.trackId]?.muted != true,
            )
        }
        val audio = snapshot.items.filter { it.enabled && it.type in setOf(TimelineItemType.AUDIO, TimelineItemType.MUSIC) && it.sourceId != null && it.sourceRange != null }.map { item ->
            val property = project.clipProperties[item.id] ?: ClipEditProperties()
            val sourceRange = requireNotNull(item.sourceRange)
            val fadeIn = property.fades.filter { it.first == FadeType.AUDIO_IN }.maxOfOrNull { it.second.value } ?: 0L
            val fadeOut = property.fades.filter { it.first == FadeType.AUDIO_OUT }.maxOfOrNull { it.second.value } ?: 0L
            AudioLayer(
                id = item.id,
                trackId = item.trackId,
                input = MediaInput.Original(requireNotNull(item.sourceId)),
                sourceRange = sourceRange,
                placement = TimelinePlacement(item.timelineStart, item.timelineDuration),
                gainDb = property.gainDb,
                pan = 0f,
                muted = trackById[item.trackId]?.muted == true,
                preservePitch = property.preservePitch,
                speed = constantSpeed(sourceRange, property.speed),
                fadeIn = DurationUs(fadeIn.coerceAtMost(item.timelineDuration.value)),
                fadeOut = DurationUs(fadeOut.coerceAtMost((item.timelineDuration.value - fadeIn).coerceAtLeast(0))),
                role = if (item.type == TimelineItemType.MUSIC) AudioRole.MUSIC else AudioRole.DIALOGUE,
            )
        }
        val duration = project.duration.value.coerceAtLeast(50_000L)
        val base = RenderGraph(
            projectId = snapshot.project.id,
            sequenceId = snapshot.sequence.id,
            timelineRevision = project.revision,
            canvas = OutputCanvas(
                snapshot.sequence.canvasWidth,
                snapshot.sequence.canvasHeight,
                snapshot.sequence.frameRate,
                snapshot.sequence.colorMode,
                0xFF000000,
            ),
            videoLayers = videos,
            audioLayers = audio,
            overlays = emptyList(),
            transitions = emptyList(),
            duration = DurationUs(duration),
        )
        return creative.apply(
            base = base,
            elements = project.creativeElements.filter { it.enabled },
            clipEffects = project.creativeEffects,
            transitions = project.creativeTransitions,
        ).graph
    }

    private fun constantSpeed(range: TimeRangeUs, speed: Double) = SpeedCurve(
        listOf(SpeedSegmentNode(range, speed.coerceIn(.25, 4.0), speed.coerceIn(.25, 4.0), SpeedInterpolation.CONSTANT))
    )
}

class ProductionEditorPlayer(
    context: Context,
    private val repository: ProductionProjectRepository,
    private val surfaceView: SurfaceView,
    private val scope: CoroutineScope,
) : EditorPlayer {
    private val mutableState = MutableStateFlow(PlaybackUiState())
    override val state: Flow<PlaybackUiState> = mutableState

    private val graphMapper = ProductionRenderGraphMapper()
    private val inputResolver = ProductionMedia3InputResolver(repository)
    private val compositionMapper = Media3CompositionMapper(inputResolver)
    private val compositionFactory = Media3RuntimeCompositionFactory(context.applicationContext)
    private val facade: CompositionPlayerFacade = AndroidCompositionPlayerFacade(context.applicationContext, compositionFactory)
    private var sessionId: String? = null
    private var currentProject: AiEditableProject? = null
    private var ticker: Job? = null
    private var muted = false

    override suspend fun prepare(project: AiEditableProject, useProxy: Boolean, quality: PreviewQuality): MediaResult<Unit> = mediaCall("prepare preview") {
        currentProject = project
        mutableState.value = mutableState.value.copy(status = PlaybackStatus.BUFFERING, duration = project.duration, quality = quality)
        if (!hasPlayableMedia(project)) {
            mutableState.value = mutableState.value.copy(status = PlaybackStatus.READY, currentTime = TimeUs(0), duration = project.duration)
            return@mediaCall
        }
        val id = "editor-${project.snapshot.project.id.value}-${UUID.randomUUID()}"
        sessionId?.let { runCatching { facade.release(it) } }
        facade.prepare(id, compositionMapper.map(graphMapper.map(project), preferProxy = useProxy), PreviewSurface(surfaceView))
        sessionId = id
        facade.setMuted(id, muted)
        mutableState.value = mutableState.value.copy(status = PlaybackStatus.READY, currentTime = TimeUs(0), duration = project.duration, muted = muted)
        startTicker()
    }

    override suspend fun preview(project: AiEditableProject): MediaResult<Unit> = mediaCall("update preview") {
        currentProject = project
        val id = sessionId
        if (id == null || !hasPlayableMedia(project)) {
            prepare(project, useProxy = true, quality = mutableState.value.quality)
            return@mediaCall
        }
        facade.replace(id, compositionMapper.map(graphMapper.map(project), preferProxy = true))
        mutableState.value = mutableState.value.copy(duration = project.duration)
    }

    override suspend fun play() {
        val id = sessionId ?: return
        runCatching { facade.play(id) }.onSuccess { mutableState.value = mutableState.value.copy(status = PlaybackStatus.PLAYING) }
    }

    override suspend fun pause() {
        val id = sessionId ?: return
        runCatching { facade.pause(id) }.onSuccess { mutableState.value = mutableState.value.copy(status = PlaybackStatus.PAUSED) }
    }

    override suspend fun seekTo(time: TimeUs) {
        val clamped = TimeUs(time.value.coerceIn(0L, mutableState.value.duration.value.coerceAtLeast(0L)))
        sessionId?.let { runCatching { facade.seek(it, clamped) } }
        mutableState.value = mutableState.value.copy(currentTime = clamped)
    }

    override suspend fun setMuted(muted: Boolean) {
        this.muted = muted
        sessionId?.let { runCatching { facade.setMuted(it, muted) } }
        mutableState.value = mutableState.value.copy(muted = muted)
    }

    override suspend fun release() {
        ticker?.cancel()
        ticker = null
        sessionId?.let { runCatching { facade.release(it) } }
        sessionId = null
        mutableState.value = PlaybackUiState(status = PlaybackStatus.IDLE)
    }

    private fun hasPlayableMedia(project: AiEditableProject) = project.snapshot.items.any {
        it.enabled && it.sourceId != null && it.type in setOf(TimelineItemType.VIDEO, TimelineItemType.AUDIO, TimelineItemType.MUSIC)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(100)
                val id = sessionId ?: continue
                runCatching {
                    val position = facade.currentPosition(id)
                    val playing = facade.isPlaying(id)
                    mutableState.value = mutableState.value.copy(
                        currentTime = TimeUs(position.value.coerceAtMost(mutableState.value.duration.value)),
                        status = if (playing) PlaybackStatus.PLAYING else if (mutableState.value.status == PlaybackStatus.BUFFERING) PlaybackStatus.READY else PlaybackStatus.PAUSED,
                    )
                }
            }
        }
    }

    private suspend inline fun mediaCall(operation: String, crossinline block: suspend () -> Unit): MediaResult<Unit> = try {
        block()
        MediaResult.Success(Unit)
    } catch (failure: Throwable) {
        mutableState.value = mutableState.value.copy(status = PlaybackStatus.ERROR)
        MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("$operation: ${failure.message ?: failure.javaClass.simpleName}"))
    }
}

/** Minimal production read-tools implementation; deterministic edits remain fully local when no provider is configured. */
class ProductionAiDataSource(
    private val repository: ProductionProjectRepository,
) : AiEditorDataSource {
    override suspend fun project(projectId: ProjectId): AiEditableProject? = repository.loadEditableProject(projectId)
    override suspend fun silence(projectId: ProjectId): List<SilenceRange> = emptyList()
    override suspend fun transcriptWords(projectId: ProjectId): List<TranscriptWord> = emptyList()
    override suspend fun takeGroups(projectId: ProjectId): List<TakeCandidateGroup> = emptyList()
    override suspend fun resolvePreservedTopic(projectId: ProjectId, userText: String): List<ProtectedRange> = emptyList()
    override suspend fun saveConstraint(constraint: ProjectConstraint) = Unit

    override suspend fun projectInfo(projectId: ProjectId): ContextFragment {
        val value = requireNotNull(project(projectId))
        return fragment(ContextSection.PROJECT_INFO, "project", "title=${value.snapshot.project.title}; revision=${value.revision}; durationUs=${value.duration.value}")
    }

    override suspend fun timelineSummary(projectId: ProjectId): ContextFragment {
        val value = requireNotNull(project(projectId))
        val content = value.snapshot.items.sortedBy { it.timelineStart.value }.joinToString("\n") {
            "${it.id.value}|${it.type}|track=${it.trackId.value}|start=${it.timelineStart.value}|duration=${it.timelineDuration.value}|source=${it.sourceId?.value.orEmpty()}"
        }
        return fragment(ContextSection.TIMELINE, "timeline", content)
    }

    override suspend fun clipDetails(projectId: ProjectId, clipId: ClipId?): ContextFragment? {
        if (clipId == null) return null
        val value = project(projectId)?.snapshot?.items?.firstOrNull { it.id == clipId } ?: return null
        return fragment(ContextSection.CLIP_DETAILS, clipId.value, value.toString())
    }

    override suspend fun searchTranscript(projectId: ProjectId, query: String) = fragment(ContextSection.TRANSCRIPT_SEARCH, "transcript-search", "No transcript available")
    override suspend fun transcriptRange(projectId: ProjectId, range: TimeRangeUs?) = null
    override suspend fun wordBoundaries(projectId: ProjectId, around: TimeUs?) = null
    override suspend fun silenceRanges(projectId: ProjectId) = fragment(ContextSection.SILENCE, "silence", "No silence analysis available")
    override suspend fun duplicateCandidates(projectId: ProjectId, query: String?) = fragment(ContextSection.DUPLICATES, "duplicates", "No duplicate analysis available")
    override suspend fun audioAnalysis(projectId: ProjectId) = fragment(ContextSection.AUDIO_ANALYSIS, "audio", "No cached audio analysis available")
    override suspend fun sceneBoundaries(projectId: ProjectId) = fragment(ContextSection.SCENES, "scenes", "No cached scene analysis available")
    override suspend fun constraints(projectId: ProjectId) = fragment(ContextSection.CONSTRAINTS, "constraints", project(projectId)?.constraints.orEmpty().joinToString("\n"))
    override suspend fun protectedRanges(projectId: ProjectId) = fragment(ContextSection.PROTECTED_RANGES, "protected", project(projectId)?.protectedRanges.orEmpty().joinToString("\n"))
    override suspend fun recentHistory(projectId: ProjectId): ContextFragment {
        val value = requireNotNull(project(projectId))
        return fragment(ContextSection.HISTORY, "history", "revision=${value.revision}")
    }
    override suspend fun visualSamples(projectId: ProjectId, range: TimeRangeUs?) = fragment(ContextSection.VISUAL_SAMPLES, "visual", "Visual samples are local-only in this runtime")

    private fun fragment(section: ContextSection, label: String, content: String) = ContextFragment(
        section, label, content, ((content.length + 3) / 4).toLong().coerceAtLeast(1), dataOnly = true,
    )
}

object UnconfiguredEditingModelResolver : EditingModelResolver {
    override suspend fun resolve(requirements: TaskRequirements, role: ModelRole): LlmResult<EditingModel> =
        LlmResult.Failure(LlmError.ProviderUnavailable(ProviderId("unconfigured"), null, null))
}

object UnconfiguredEditPlanClient : EditPlanProposalClient {
    private fun <T> unavailable(): LlmResult<T> = LlmResult.Failure(LlmError.ProviderUnavailable(ProviderId("unconfigured"), null, null))
    override suspend fun propose(model: EditingModel, context: AiTaskContext, previous: EditPlan?, feedback: String?): LlmResult<EditPlan> = unavailable()
    override suspend fun repair(model: EditingModel, invalid: EditPlan, errors: List<PlanValidationError>, validIds: Set<String>, attempt: Int): LlmResult<EditPlan> = unavailable()
    override suspend fun analyze(model: EditingModel, context: AiTaskContext): LlmResult<String> = unavailable()
}
