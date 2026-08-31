package com.moatazvid.media

import com.moatazvid.core.*
import kotlinx.coroutines.flow.Flow

interface MediaEngine {
    suspend fun probeMedia(input: MediaInput): MediaResult<MediaProbe>
    suspend fun prepareProject(graph: RenderGraph, mode: PreparationMode): MediaResult<PreparedProject>
    suspend fun preparePreview(graph: RenderGraph, surface: PreviewSurface): MediaResult<PreviewSession>
    suspend fun updatePreview(sessionId: String, graph: RenderGraph): MediaResult<PreviewUpdate>
    suspend fun seek(sessionId: String, position: TimeUs): MediaResult<Unit>
    suspend fun renderPreviewRange(request: PreviewRenderRequest): MediaResult<MediaJobHandle>
    suspend fun export(request: ExportRequest): MediaResult<MediaJobHandle>
    suspend fun generateProxy(request: ProxyRequest): MediaResult<MediaJobHandle>
    suspend fun generateThumbnail(request: ThumbnailRequest): MediaResult<MediaJobHandle>
    suspend fun cancel(jobId: String): MediaResult<Unit>
    fun observeProgress(jobId: String): Flow<MediaEngineProgress>
    suspend fun estimateExport(graph: RenderGraph, settings: ExportSettings): MediaResult<ExportEstimate>
    suspend fun getCapabilities(): EngineCapabilities
}

enum class PreparationMode { PREVIEW, EXPORT }
data class PreparedProject(val graphRevision: Long, val selectedBackend: BackendKind, val warnings: List<String>)
data class PreviewSurface(val token: Any)
data class PreviewSession(val id: String, val graphRevision: Long, val wysiwyg: WysiwygLevel)
data class PreviewUpdate(val graphRevision: Long, val rebuilt: Boolean)
enum class WysiwygLevel { EXACT, APPROXIMATE, PROXY_COLOR_DIFFERENCE, UNSUPPORTED_EFFECT_PLACEHOLDER }

data class PreviewRenderRequest(val graph: RenderGraph, val range: TimeRangeUs, val maxWidth: Int)
data class ThumbnailRequest(val input: MediaInput, val sourceTime: TimeUs, val width: Int, val outputRef: String)
data class ProxyRequest(val source: MediaInput.Original, val probe: MediaProbe, val preset: ProxyPreset, val outputRef: String)

data class ExportRequest(
    val id: String,
    val graph: RenderGraph,
    val outputUri: String,
    val settings: ExportSettings,
    val overwriteAllowed: Boolean = false,
)

data class MediaJobHandle(val id: String, val backend: BackendKind, val resumable: Boolean)

enum class BackendKind { MEDIA3, FFMPEG }
enum class JobStage { QUEUED, PROBING, DECODING, COMPOSITING, ENCODING, MUXING, FINALIZING, COMPLETED, CANCELLED, FAILED }

data class MediaEngineProgress(
    val jobId: String,
    val stage: JobStage,
    val percent: Double?,
    val processingFps: Double?,
    val processedDuration: DurationUs?,
    val etaMillis: Long?,
)

sealed interface MediaResult<out T> {
    data class Success<T>(val value: T) : MediaResult<T>
    data class Failure(val error: MediaEngineError) : MediaResult<Nothing>
}

sealed interface MediaEngineError {
    data class UnsupportedCodec(val mime: String, val operation: String) : MediaEngineError
    data class DecoderFailure(val sourceId: String, val detail: String) : MediaEngineError
    data class EncoderFailure(val codec: String, val detail: String) : MediaEngineError
    data class MissingSource(val sourceId: String) : MediaEngineError
    data class PermissionLost(val sourceId: String) : MediaEngineError
    data class OutOfStorage(val requiredBytes: Long, val availableBytes: Long) : MediaEngineError
    data class InvalidTimeline(val violations: List<String>) : MediaEngineError
    data class Media3UnsupportedOperation(val operation: String) : MediaEngineError
    data class FfmpegFailure(val operation: String, val exitCode: Int?, val safeLog: String) : MediaEngineError
    data object ExportCancelled : MediaEngineError
    data class OomRisk(val estimatedBytes: Long, val safeLimitBytes: Long) : MediaEngineError
    data class HdrUnsupported(val mode: ProjectColorMode) : MediaEngineError
}

