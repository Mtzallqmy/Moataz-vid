package com.moatazvid.media.media3

import com.moatazvid.core.TimeUs
import com.moatazvid.media.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class Media3Engine(
    private val mapper: Media3CompositionMapper,
    private val transformer: TransformerFacade,
    private val player: CompositionPlayerFacade,
    private val detector: CodecCapabilityDetector,
    private val probeService: Media3ProbeService,
    private val thumbnailService: Media3ThumbnailService,
    private val proxyService: Media3ProxyService,
    /** Features actually implemented by the concrete Android binding, not capabilities inferred from Media3 APIs. */
    private val supportedFeatures: Set<RenderFeature> = DEFAULT_BOUND_FEATURES,
) : MediaEngine {
    override suspend fun probeMedia(input: MediaInput): MediaResult<MediaProbe> = probeService.probe(input)

    override suspend fun prepareProject(graph: RenderGraph, mode: PreparationMode): MediaResult<PreparedProject> = runCatching {
        assertBound(graph)
        mapper.map(graph, preferProxy = mode == PreparationMode.PREVIEW)
        PreparedProject(graph.timelineRevision, BackendKind.MEDIA3, emptyList())
    }.fold({ MediaResult.Success(it) }, { MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation(it.message ?: "mapping")) })

    override suspend fun preparePreview(graph: RenderGraph, surface: PreviewSurface): MediaResult<PreviewSession> = runCatching {
        assertBound(graph)
        val id = UUID.randomUUID().toString()
        player.prepare(id, mapper.map(graph, preferProxy = true), surface)
        PreviewSession(id, graph.timelineRevision, WysiwygLevel.EXACT)
    }.fold({ MediaResult.Success(it) }, { MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation(it.message ?: "preview")) })

    override suspend fun updatePreview(sessionId: String, graph: RenderGraph): MediaResult<PreviewUpdate> = runCatching {
        assertBound(graph)
        player.replace(sessionId, mapper.map(graph, preferProxy = true))
        PreviewUpdate(graph.timelineRevision, rebuilt = true)
    }.fold({ MediaResult.Success(it) }, { MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation(it.message ?: "preview update")) })

    override suspend fun seek(sessionId: String, position: TimeUs): MediaResult<Unit> = runCatching {
        player.seek(sessionId, position)
    }.fold({ MediaResult.Success(Unit) }, { MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("seek")) })

    override suspend fun renderPreviewRange(request: PreviewRenderRequest): MediaResult<MediaJobHandle> =
        MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("preview range cache not bound"))

    override suspend fun export(request: ExportRequest): MediaResult<MediaJobHandle> {
        val missing = CapabilityResolver().requiredFeatures(request.graph) - supportedFeatures
        if (missing.isNotEmpty()) return MediaResult.Failure(MediaEngineError.InvalidTimeline(missing.map { "Media3 binding missing ${it.name}" }))
        if (!detector.canEncode(request.settings)) {
            return MediaResult.Failure(MediaEngineError.UnsupportedCodec(request.settings.videoCodec.name, "export"))
        }
        return transformer.export(request.id, mapper.map(request.graph, preferProxy = false), request.outputUri, request.settings)
    }

    override suspend fun generateProxy(request: ProxyRequest): MediaResult<MediaJobHandle> = proxyService.generate(request)
    override suspend fun generateThumbnail(request: ThumbnailRequest): MediaResult<MediaJobHandle> = thumbnailService.generate(request)
    override suspend fun cancel(jobId: String): MediaResult<Unit> = runCatching { transformer.cancel(jobId) }
        .fold({ MediaResult.Success(Unit) }, { MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("cancel")) })
    override fun observeProgress(jobId: String): Flow<MediaEngineProgress> = transformer.progress(jobId)
    override suspend fun estimateExport(graph: RenderGraph, settings: ExportSettings): MediaResult<ExportEstimate> {
        val seconds = graph.duration.value / 1_000_000.0
        val bytes = ((settings.videoBitrate + settings.audioBitrate) * seconds / 8.0).toLong()
        return MediaResult.Success(ExportEstimate(bytes, bytes * 2 + 256L * 1024 * 1024, null, BackendKind.MEDIA3))
    }
    override suspend fun getCapabilities(): EngineCapabilities = EngineCapabilities(
        media3Features = supportedFeatures,
        ffmpegFeatures = emptySet(),
        codecs = detector.detect(),
    )

    private fun assertBound(graph: RenderGraph) {
        val missing = CapabilityResolver().requiredFeatures(graph) - supportedFeatures
        require(missing.isEmpty()) { "Unbound Media3 features: ${missing.joinToString { it.name }}" }
    }

    companion object {
        /** Conservative defaults. Creative overlay/effect/transition features must be opted in by a concrete renderer binding. */
        val DEFAULT_BOUND_FEATURES: Set<RenderFeature> = setOf(
            RenderFeature.TRIM,
            RenderFeature.CONCATENATE,
            RenderFeature.CROP,
            RenderFeature.SCALE,
            RenderFeature.ROTATE,
            RenderFeature.CONSTANT_SPEED,
            RenderFeature.AUDIO_MIX,
            RenderFeature.KEEP_HDR,
            RenderFeature.TONE_MAP_HDR,
            RenderFeature.PROXY,
        )
    }
}

interface Media3ProbeService { suspend fun probe(input: MediaInput): MediaResult<MediaProbe> }
interface Media3ThumbnailService { suspend fun generate(request: ThumbnailRequest): MediaResult<MediaJobHandle> }
interface Media3ProxyService { suspend fun generate(request: ProxyRequest): MediaResult<MediaJobHandle> }
