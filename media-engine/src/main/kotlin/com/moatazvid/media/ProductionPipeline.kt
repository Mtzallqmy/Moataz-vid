package com.moatazvid.media

import com.moatazvid.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User-facing quality level; concrete bitrate/profile is resolved per device. */
enum class ExportQuality { ECONOMY, BALANCED, HIGH, CUSTOM }
enum class ExportPreset(val width: Int, val height: Int) { HD_720(1280, 720), FULL_HD_1080(1920, 1080), UHD_2160(3840, 2160) }
enum class DevicePerformanceTier { LOW, MID, HIGH }
enum class ThermalLevel { NORMAL, WARM, HOT, CRITICAL }
enum class PerformanceMode { AUTO, BATTERY_SAVER, BALANCED, MAX_PERFORMANCE }

data class EncoderCapability(
    val codec: VideoCodec,
    val maximumWidth: Int,
    val maximumHeight: Int,
    val maximumFps: Double,
    val hardwareAccelerated: Boolean,
    val hdrEncoding: Boolean,
)

data class DeviceMediaCapabilities(
    val abi: String,
    val apiLevel: Int,
    val memoryClassMb: Int,
    val encoders: List<EncoderCapability>,
    val hevcDecode: Boolean,
    val hdrDisplay: Boolean,
    val fingerprint: String,
) {
    fun canEncode(codec: VideoCodec, width: Int, height: Int, fps: Rational, hdr: Boolean): Boolean = encoders.any {
        it.codec == codec && width <= it.maximumWidth && height <= it.maximumHeight && fps.asDouble() <= it.maximumFps && (!hdr || it.hdrEncoding)
    }

    fun performanceTier(): DevicePerformanceTier {
        val h264 = encoders.filter { it.codec == VideoCodec.H264 && it.hardwareAccelerated }
        val pixelsPerSecond = h264.maxOfOrNull { it.maximumWidth.toLong() * it.maximumHeight * it.maximumFps.toLong() } ?: 0L
        return when {
            memoryClassMb < 384 || pixelsPerSecond < 1920L * 1080 * 30 -> DevicePerformanceTier.LOW
            memoryClassMb >= 768 && pixelsPerSecond >= 3840L * 2160 * 30 -> DevicePerformanceTier.HIGH
            else -> DevicePerformanceTier.MID
        }
    }
}

object ExportSettingsResolver {
    fun resolve(
        graph: RenderGraph,
        capabilities: DeviceMediaCapabilities,
        preset: ExportPreset,
        quality: ExportQuality,
        requestedFps: Rational? = null,
        codec: VideoCodec = VideoCodec.H264,
        hdrPolicy: HdrPolicy = HdrPolicy.SDR,
        customBitrate: Long? = null,
    ): ExportSettings {
        val fps = requestedFps ?: graph.canvas.frameRate
        val portrait = graph.canvas.height > graph.canvas.width
        val width = if (portrait) preset.height else preset.width
        val height = if (portrait) preset.width else preset.height
        require(capabilities.canEncode(codec, width, height, fps, hdrPolicy == HdrPolicy.KEEP_HDR)) { "Requested encoder configuration is not supported" }
        val pixels = width.toLong() * height
        val base = when (quality) {
            ExportQuality.ECONOMY -> 0.055
            ExportQuality.BALANCED -> 0.085
            ExportQuality.HIGH -> 0.13
            ExportQuality.CUSTOM -> 0.085
        }
        val bitrate = customBitrate ?: (pixels * fps.asDouble() * base).toLong().coerceIn(2_000_000, 80_000_000)
        return ExportSettings(
            container = ContainerFormat.MP4,
            videoCodec = codec,
            audioCodec = AudioCodec.AAC,
            width = width,
            height = height,
            frameRate = fps,
            fpsPolicy = if (requestedFps == null) FpsPolicy.PROJECT else FpsPolicy.EXPLICIT,
            qualityMode = if (quality == ExportQuality.CUSTOM) QualityMode.BITRATE else QualityMode.QUALITY,
            videoBitrate = bitrate,
            audioBitrate = if (quality == ExportQuality.ECONOMY) 128_000 else 192_000,
            hdrPolicy = hdrPolicy,
        )
    }
}

enum class ExportValidationCode {
    SOURCE_MISSING, PERMISSION_LOST, INVALID_TIMELINE, UNSUPPORTED_CODEC, MISSING_ASSET,
    OUTPUT_NOT_WRITABLE, INSUFFICIENT_STORAGE, FONT_MISSING, HDR_UNRESOLVED, EFFECT_UNSUPPORTED
}
data class ExportValidationIssue(val code: ExportValidationCode, val message: String, val blocking: Boolean = true)
data class ExportValidationResult(val issues: List<ExportValidationIssue>) { val valid: Boolean get() = issues.none { it.blocking } }

interface ExportEnvironment {
    suspend fun sourceIssues(graph: RenderGraph): List<ExportValidationIssue>
    suspend fun availableBytes(): Long
    suspend fun isOutputWritable(uri: String): Boolean
    suspend fun creativeIssues(graph: RenderGraph): List<ExportValidationIssue>
}

class ExportValidator(private val environment: ExportEnvironment) {
    suspend fun validate(graph: RenderGraph, outputUri: String, settings: ExportSettings, estimate: ExportEstimate?, capabilities: DeviceMediaCapabilities): ExportValidationResult {
        val issues = environment.sourceIssues(graph).toMutableList()
        if (graph.videoLayers.isEmpty()) issues += ExportValidationIssue(ExportValidationCode.INVALID_TIMELINE, "No video layer")
        if (!environment.isOutputWritable(outputUri)) issues += ExportValidationIssue(ExportValidationCode.OUTPUT_NOT_WRITABLE, outputUri)
        if (!capabilities.canEncode(settings.videoCodec, settings.width, settings.height, settings.frameRate, settings.hdrPolicy == HdrPolicy.KEEP_HDR)) {
            issues += ExportValidationIssue(ExportValidationCode.UNSUPPORTED_CODEC, "${settings.videoCodec} ${settings.width}x${settings.height}@${settings.frameRate.asDouble()}")
        }
        estimate?.let {
            val available = environment.availableBytes()
            if (available < it.requiredWorkingBytes) issues += ExportValidationIssue(ExportValidationCode.INSUFFICIENT_STORAGE, "Need ${it.requiredWorkingBytes - available} more bytes")
        }
        issues += environment.creativeIssues(graph)
        return ExportValidationResult(issues)
    }
}

data class OutputVerification(
    val valid: Boolean,
    val sizeBytes: Long,
    val duration: DurationUs?,
    val width: Int?,
    val height: Int?,
    val frameRate: Rational?,
    val videoCodec: String?,
    val audioCodec: String?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val avDriftUs: Long?,
    val issues: List<String>,
)

interface OutputInspector {
    suspend fun inspect(uri: String): OutputVerification
}

class OutputVerifier(private val inspector: OutputInspector, private val maximumAvDriftUs: Long = 120_000) {
    suspend fun verify(uri: String, expected: RenderGraph, settings: ExportSettings): OutputVerification {
        val actual = inspector.inspect(uri)
        val issues = actual.issues.toMutableList()
        if (actual.sizeBytes <= 0) issues += "EMPTY_OUTPUT"
        if (!actual.hasVideo) issues += "VIDEO_STREAM_MISSING"
        actual.duration?.let { if (kotlin.math.abs(it.value - expected.duration.value) > 250_000) issues += "DURATION_MISMATCH" }
        if (actual.width != null && actual.width != settings.width) issues += "WIDTH_MISMATCH"
        if (actual.height != null && actual.height != settings.height) issues += "HEIGHT_MISMATCH"
        actual.avDriftUs?.let { if (kotlin.math.abs(it) > maximumAvDriftUs) issues += "AV_DRIFT" }
        return actual.copy(valid = issues.isEmpty(), issues = issues.distinct())
    }
}

enum class ProductionExportState { VALIDATING, QUEUED, RENDERING, VERIFYING, FINALIZING, COMPLETED, CANCELLED, FAILED }
data class ProductionExportResult(
    val state: ProductionExportState,
    val handle: MediaJobHandle? = null,
    val validation: ExportValidationResult? = null,
    val verification: OutputVerification? = null,
    val error: MediaEngineError? = null,
)

interface AtomicOutputTarget {
    val temporaryUri: String
    val finalUri: String
    suspend fun commit(): Boolean
    suspend fun abort()
}

/** Final export orchestration; partial files remain temporary until local verification passes. */
class ExportCoordinator(
    private val engine: MediaEngine,
    private val validator: ExportValidator,
    private val verifier: OutputVerifier,
    private val resourceCoordinator: ResourceCoordinator,
) {
    suspend fun start(id: String, graph: RenderGraph, target: AtomicOutputTarget, settings: ExportSettings, capabilities: DeviceMediaCapabilities): ProductionExportResult {
        val estimate = when (val value = engine.estimateExport(graph, settings)) {
            is MediaResult.Success -> value.value
            is MediaResult.Failure -> null
        }
        val validation = validator.validate(graph, target.temporaryUri, settings, estimate, capabilities)
        if (!validation.valid) return ProductionExportResult(ProductionExportState.FAILED, validation = validation, error = validation.toMediaError())
        return resourceCoordinator.withHeavyJob(HeavyJobType.FINAL_EXPORT) {
            when (val export = engine.export(ExportRequest(id, graph, target.temporaryUri, settings, overwriteAllowed = true))) {
                is MediaResult.Failure -> {
                    target.abort()
                    ProductionExportResult(ProductionExportState.FAILED, validation = validation, error = export.error)
                }
                is MediaResult.Success -> ProductionExportResult(ProductionExportState.RENDERING, handle = export.value, validation = validation)
            }
        }
    }

    suspend fun finalize(graph: RenderGraph, target: AtomicOutputTarget, settings: ExportSettings, handle: MediaJobHandle): ProductionExportResult {
        val verification = verifier.verify(target.temporaryUri, graph, settings)
        if (!verification.valid) {
            target.abort()
            return ProductionExportResult(ProductionExportState.FAILED, handle, verification = verification, error = MediaEngineError.InvalidTimeline(verification.issues))
        }
        if (!target.commit()) {
            target.abort()
            return ProductionExportResult(ProductionExportState.FAILED, handle, verification = verification, error = MediaEngineError.Media3UnsupportedOperation("atomic output commit"))
        }
        return ProductionExportResult(ProductionExportState.COMPLETED, handle, verification = verification)
    }

    suspend fun cancel(handle: MediaJobHandle, target: AtomicOutputTarget): ProductionExportResult {
        engine.cancel(handle.id)
        target.abort()
        return ProductionExportResult(ProductionExportState.CANCELLED, handle, error = MediaEngineError.ExportCancelled)
    }

    private fun ExportValidationResult.toMediaError(): MediaEngineError {
        val storage = issues.firstOrNull { it.code == ExportValidationCode.INSUFFICIENT_STORAGE }
        if (storage != null) return MediaEngineError.OutOfStorage(1, 0)
        return MediaEngineError.InvalidTimeline(issues.map { "${it.code}:${it.message}" })
    }
}

data class ProxyRecord(val sourceId: SourceId, val sourceFingerprint: String, val preset: ProxyPreset, val outputRef: String, val ready: Boolean)
interface ProxyStore {
    suspend fun find(sourceId: SourceId, fingerprint: String): ProxyRecord?
    suspend fun save(record: ProxyRecord)
    suspend fun delete(record: ProxyRecord)
}

data class ProxyDecision(val required: Boolean, val preset: ProxyPreset?, val reason: String)
class ProxyDecisionPolicy {
    fun decide(probe: MediaProbe, tier: DevicePerformanceTier, activeEffects: Int = 0): ProxyDecision {
        val edge = maxOf(probe.codedWidth ?: 0, probe.codedHeight ?: 0)
        val fps = probe.frameRate?.asDouble() ?: 30.0
        val complexity = edge >= 3840 || fps > 30.5 || probe.hdr || (probe.bitrate ?: 0) > 25_000_000 || activeEffects >= 3 || probe.variableFrameRate
        if (!complexity) return ProxyDecision(false, null, "Direct preview is within device policy")
        val preset = when (tier) {
            DevicePerformanceTier.LOW -> ProxyPreset.LOW_480P
            DevicePerformanceTier.MID -> ProxyPreset.EDIT_720P
            DevicePerformanceTier.HIGH -> if (edge >= 3840 || fps >= 50.0) ProxyPreset.EDIT_720P else ProxyPreset.EDIT_1080P
        }
        return ProxyDecision(true, preset, "Source complexity exceeds direct-preview policy")
    }
}

class ProxyManager(private val engine: MediaEngine, private val store: ProxyStore, private val policy: ProxyDecisionPolicy = ProxyDecisionPolicy()) {
    suspend fun ensure(source: MediaInput.Original, fingerprint: String, probe: MediaProbe, tier: DevicePerformanceTier, activeEffects: Int, outputRef: String): MediaResult<MediaJobHandle>? {
        val existing = store.find(source.sourceId, fingerprint)
        if (existing?.ready == true) return null
        existing?.takeIf { it.sourceFingerprint != fingerprint }?.let { store.delete(it) }
        val decision = policy.decide(probe, tier, activeEffects)
        val preset = decision.preset ?: return null
        val result = engine.generateProxy(ProxyRequest(source, probe, preset, outputRef))
        if (result is MediaResult.Success) store.save(ProxyRecord(source.sourceId, fingerprint, preset, outputRef, ready = false))
        return result
    }

    suspend fun markReady(sourceId: SourceId, fingerprint: String) {
        val record = store.find(sourceId, fingerprint) ?: return
        store.save(record.copy(ready = true))
    }
}

data class MemoryBudget(
    val maximumThumbnailDecodes: Int,
    val previewLongEdge: Int,
    val maximumConcurrentNativeJobs: Int,
    val preferProxy: Boolean,
    val unloadSpeechModelBeforeExport: Boolean,
)

class MemoryBudgetManager {
    fun budget(memoryClassMb: Int, pressure: Float, tier: DevicePerformanceTier): MemoryBudget {
        val constrained = pressure >= 0.7f || memoryClassMb < 384 || tier == DevicePerformanceTier.LOW
        return MemoryBudget(
            maximumThumbnailDecodes = if (constrained) 2 else if (tier == DevicePerformanceTier.HIGH) 6 else 4,
            previewLongEdge = if (constrained) 720 else if (tier == DevicePerformanceTier.HIGH) 1440 else 1080,
            maximumConcurrentNativeJobs = if (constrained) 1 else 2,
            preferProxy = constrained || tier == DevicePerformanceTier.LOW,
            unloadSpeechModelBeforeExport = true,
        )
    }
}

enum class HeavyJobType { FINAL_EXPORT, PROXY, TRANSCRIPTION, LOCAL_LLM, THUMBNAILS }
interface ResourceParticipant {
    suspend fun suspendFor(job: HeavyJobType)
    suspend fun resumeAfter(job: HeavyJobType)
}

/** Serializes expensive media/AI jobs and gives participants a chance to release RAM. */
class ResourceCoordinator(private val participants: List<ResourceParticipant> = emptyList()) {
    private val heavyJobMutex = Mutex()
    suspend fun <T> withHeavyJob(type: HeavyJobType, block: suspend () -> T): T = heavyJobMutex.withLock {
        participants.forEach { it.suspendFor(type) }
        try { block() } finally { participants.asReversed().forEach { it.resumeAfter(type) } }
    }
}

data class ThermalDecision(val allowNonessentialJobs: Boolean, val maximumConcurrency: Int, val warningRequired: Boolean)
object ThermalPolicy {
    fun decide(level: ThermalLevel): ThermalDecision = when (level) {
        ThermalLevel.NORMAL -> ThermalDecision(true, 2, false)
        ThermalLevel.WARM -> ThermalDecision(true, 1, false)
        ThermalLevel.HOT -> ThermalDecision(false, 1, true)
        ThermalLevel.CRITICAL -> ThermalDecision(false, 0, true)
    }
}

data class JobRecord(val id: String, val type: HeavyJobType, val state: JobStage, val resumable: Boolean, val workerAlive: Boolean)
enum class ReconciledJobState { KEEP_RUNNING, RESCHEDULE, INTERRUPTED }
object JobReconciler {
    fun reconcile(job: JobRecord): ReconciledJobState = when {
        job.state != JobStage.QUEUED && job.state != JobStage.PROBING && job.state != JobStage.DECODING && job.state != JobStage.COMPOSITING && job.state != JobStage.ENCODING && job.state != JobStage.MUXING && job.state != JobStage.FINALIZING -> ReconciledJobState.KEEP_RUNNING
        job.workerAlive -> ReconciledJobState.KEEP_RUNNING
        job.resumable -> ReconciledJobState.RESCHEDULE
        else -> ReconciledJobState.INTERRUPTED
    }
}

data class DiagnosticReport(
    val appVersion: String,
    val apiLevel: Int,
    val abi: String,
    val performanceTier: DevicePerformanceTier,
    val backends: List<BackendKind>,
    val encoderSummary: List<String>,
    val recentOperationIds: List<String>,
    val safeErrors: List<String>,
)

class DiagnosticReportGenerator {
    fun generate(appVersion: String, capabilities: DeviceMediaCapabilities, recentOperationIds: List<String>, safeErrors: List<String>): DiagnosticReport = DiagnosticReport(
        appVersion = appVersion,
        apiLevel = capabilities.apiLevel,
        abi = capabilities.abi,
        performanceTier = capabilities.performanceTier(),
        backends = listOf(BackendKind.MEDIA3, BackendKind.FFMPEG),
        encoderSummary = capabilities.encoders.map { "${it.codec}:${it.maximumWidth}x${it.maximumHeight}@${it.maximumFps}:${if (it.hardwareAccelerated) "hw" else "sw"}" },
        recentOperationIds = recentOperationIds.takeLast(20),
        safeErrors = safeErrors.takeLast(20),
    )
}
