package com.moatazvid.media

import com.moatazvid.core.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

interface FfmpegHandleResolver {
    suspend fun input(input: MediaInput): TrustedInputHandle
    suspend fun output(outputUri: String): TrustedOutputHandle
}

class FfmpegFallbackEngine(
    private val bridge: FfmpegNativeBridge,
    private val handles: FfmpegHandleResolver,
    private val capabilities: EngineCapabilities,
) : MediaEngine {
    private val progress = ConcurrentHashMap<String, MutableStateFlow<MediaEngineProgress>>()

    override suspend fun probeMedia(input: MediaInput): MediaResult<MediaProbe> = runCatching {
        bridge.probe(handles.input(input).resolverToken)
    }.fold({ MediaResult.Success(it) }, { MediaResult.Failure(MediaEngineError.FfmpegFailure("probe", null, it.message.orEmpty())) })

    override suspend fun prepareProject(graph: RenderGraph, mode: PreparationMode): MediaResult<PreparedProject> {
        val missing = CapabilityResolver().requiredFeatures(graph) - capabilities.ffmpegFeatures
        return if (missing.isEmpty()) MediaResult.Success(PreparedProject(graph.timelineRevision, BackendKind.FFMPEG, emptyList()))
        else MediaResult.Failure(MediaEngineError.InvalidTimeline(missing.map { "FFmpeg missing ${it.name}" }))
    }

    override suspend fun preparePreview(graph: RenderGraph, surface: PreviewSurface): MediaResult<PreviewSession> =
        MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("FFmpeg is not the interactive preview backend"))
    override suspend fun updatePreview(sessionId: String, graph: RenderGraph): MediaResult<PreviewUpdate> =
        MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("FFmpeg preview update"))
    override suspend fun seek(sessionId: String, position: TimeUs): MediaResult<Unit> =
        MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("FFmpeg preview seek"))
    override suspend fun renderPreviewRange(request: PreviewRenderRequest): MediaResult<MediaJobHandle> =
        MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("FFmpeg preview range binding pending"))

    override suspend fun export(request: ExportRequest): MediaResult<MediaJobHandle> {
        val state = MutableStateFlow(MediaEngineProgress(request.id, JobStage.QUEUED, 0.0, null, null, null))
        progress[request.id] = state
        return runCatching {
            val inputs = (request.graph.videoLayers.map { it.input } + request.graph.audioLayers.map { it.input })
                .distinctBy { it.stableId }
                .map { handles.input(it) }
            val execution = FfmpegExecutionRequest(
                executionId = request.id,
                inputs = inputs,
                output = handles.output(request.outputUri),
                graph = typedGraph(request.graph),
                codecs = CodecSelection(request.settings.videoCodec, request.settings.audioCodec, request.settings.container),
            )
            val result = bridge.execute(execution) { state.value = it }
            if (result.exitCode != 0) error("FFmpeg exit ${result.exitCode}: ${result.safeLog}")
            state.value = state.value.copy(stage = JobStage.COMPLETED, percent = 100.0)
            MediaJobHandle(request.id, BackendKind.FFMPEG, resumable = false)
        }.fold({ MediaResult.Success(it) }, {
            state.value = state.value.copy(stage = JobStage.FAILED)
            MediaResult.Failure(MediaEngineError.FfmpegFailure("export", null, it.message.orEmpty()))
        })
    }

    override suspend fun generateProxy(request: ProxyRequest): MediaResult<MediaJobHandle> =
        MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("Prefer Media3 proxy generation"))
    override suspend fun generateThumbnail(request: ThumbnailRequest): MediaResult<MediaJobHandle> =
        MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("Prefer Media3 thumbnail generation"))
    override suspend fun cancel(jobId: String): MediaResult<Unit> = runCatching {
        bridge.cancel(jobId)
        progress[jobId]?.value = progress[jobId]?.value?.copy(stage = JobStage.CANCELLED) ?: return@runCatching
    }.fold({ MediaResult.Success(Unit) }, { MediaResult.Failure(MediaEngineError.FfmpegFailure("cancel", null, it.message.orEmpty())) })
    override fun observeProgress(jobId: String): Flow<MediaEngineProgress> = progress.getOrPut(jobId) {
        MutableStateFlow(MediaEngineProgress(jobId, JobStage.QUEUED, null, null, null, null))
    }.asStateFlow()
    override suspend fun estimateExport(graph: RenderGraph, settings: ExportSettings): MediaResult<ExportEstimate> {
        val bytes = (((settings.videoBitrate + settings.audioBitrate) * (graph.duration.value / 1_000_000.0)) / 8).toLong()
        return MediaResult.Success(ExportEstimate(bytes, bytes * 2 + 256L * 1024 * 1024, null, BackendKind.FFMPEG))
    }
    override suspend fun getCapabilities(): EngineCapabilities = capabilities

    private fun typedGraph(graph: RenderGraph): TypedFilterGraph = TypedFilterGraph(buildList {
        graph.videoLayers.forEachIndexed { index, layer ->
            add(FfmpegFilterNode.Trim(index, layer.sourceRange.start.value, layer.sourceRange.endExclusive.value))
            layer.speed.constantSpeedOrNull?.takeIf { it != 1.0 }?.let { add(FfmpegFilterNode.SetPts(it)) }
        }
        graph.audioLayers.forEach { layer ->
            if (!layer.muted && layer.gainDb != 0f) add(FfmpegFilterNode.Volume(layer.gainDb))
            if (layer.fadeIn.value > 0) add(FfmpegFilterNode.Fade(FadeType.AUDIO_IN, 0, layer.fadeIn.value))
            if (layer.fadeOut.value > 0) add(FfmpegFilterNode.Fade(FadeType.AUDIO_OUT, layer.placement.duration.value - layer.fadeOut.value, layer.fadeOut.value))
        }
        graph.transitions.filter { it.type == TransitionType.CROSSFADE }.forEach {
            add(FfmpegFilterNode.CrossFade(it.duration.value))
        }
    })
}

