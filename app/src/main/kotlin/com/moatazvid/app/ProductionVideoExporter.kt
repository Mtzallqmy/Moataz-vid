package com.moatazvid.app

import android.content.Context
import android.net.Uri
import com.moatazvid.core.ProjectColorMode
import com.moatazvid.core.ProjectId
import com.moatazvid.media.*
import com.moatazvid.media.media3.*
import com.moatazvid.videouse.VideoUseSelfEvaluationReport
import java.util.UUID
import kotlinx.coroutines.flow.first

class ProductionVideoExporter(
    context: Context,
    private val repository: ProductionProjectRepository,
) {
    data class Progress(val stage: JobStage, val percent: Double?)
    data class Completed(
        val destinationUri: String,
        val verification: OutputVerification,
        val selfEvaluation: VideoUseSelfEvaluationReport,
    )

    private val appContext = context.applicationContext
    private val graphMapper = ProductionRenderGraphMapper()
    private val inputResolver = ProductionMedia3InputResolver(repository)
    private val compositionMapper = Media3CompositionMapper(inputResolver)
    private val compositionFactory = Media3RuntimeCompositionFactory(appContext)
    private val transformer = AndroidTransformerFacade(appContext, compositionFactory)
    private val codecDetector = AndroidCodecCapabilityDetector()
    private val outputTargets = AndroidAtomicOutputTargetFactory(appContext)
    private val verifier = OutputVerifier(AndroidOutputInspector(appContext))
    private val selfEvaluator = AndroidVideoUseSelfEvaluator(appContext)
    private val sessionMemory = ProductionVideoUseSessionMemory(repository)

    suspend fun export(
        projectId: ProjectId,
        destination: Uri,
        onProgress: (Progress) -> Unit = {},
    ): Result<Completed> = runCatching {
        val project = requireNotNull(repository.loadEditableProject(projectId)) { "Project not found" }
        var graph = graphMapper.map(project)
        require(graph.videoLayers.isNotEmpty()) { "The project has no video track to export" }

        var settings = ExportSettings(
            width = graph.canvas.width,
            height = graph.canvas.height,
            frameRate = graph.canvas.frameRate,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.AAC,
            videoBitrate = recommendedBitrate(graph.canvas.width, graph.canvas.height, graph.canvas.frameRate.asDouble()),
            audioBitrate = 192_000,
            hdrPolicy = when (graph.canvas.colorMode) {
                ProjectColorMode.HDR_KEEP -> HdrPolicy.KEEP_HDR
                ProjectColorMode.HDR_TO_SDR -> HdrPolicy.TONE_MAP_TO_SDR
                ProjectColorMode.SDR -> HdrPolicy.SDR
            },
        )
        if (!codecDetector.canEncode(settings) && graph.canvas.colorMode == ProjectColorMode.HDR_KEEP) {
            graph = graph.copy(canvas = graph.canvas.copy(colorMode = ProjectColorMode.HDR_TO_SDR))
            settings = settings.copy(hdrPolicy = HdrPolicy.TONE_MAP_TO_SDR)
        }
        check(codecDetector.canEncode(settings)) {
            "No compatible H.264 encoder for ${settings.width}x${settings.height}@${settings.frameRate.asDouble()}"
        }

        // Exactly one final lossy Media3 Transformer pass. The preview and exporter share the same
        // normalized composition so what the user approves is what the encoder receives.
        val composition = VideoUseMedia3Policy.normalize(compositionMapper.map(graph, preferProxy = false))
        val preflight = VideoUseMedia3Policy.inspect(composition)
        check(preflight.passed) { "Render preflight failed: ${preflight.issues.joinToString()}" }

        val target = outputTargets.saf(destination)
        val id = "export-${UUID.randomUUID()}"
        onProgress(Progress(JobStage.QUEUED, 0.0))
        val handle = when (val start = transformer.export(id, composition, target.temporaryUri, settings)) {
            is MediaResult.Success -> start.value
            is MediaResult.Failure -> {
                target.abort()
                error(start.error.toString())
            }
        }
        val terminal = transformer.progress(handle.id).first { value ->
            onProgress(Progress(value.stage, value.percent))
            value.stage in setOf(JobStage.COMPLETED, JobStage.FAILED, JobStage.CANCELLED)
        }
        if (terminal.stage != JobStage.COMPLETED) {
            target.abort()
            error("Export ${terminal.stage.name.lowercase()}")
        }

        onProgress(Progress(JobStage.FINALIZING, 100.0))
        val verification = verifier.verify(target.temporaryUri, graph, settings)
        if (!verification.valid) {
            target.abort()
            error("Export verification failed: ${verification.issues.joinToString()}")
        }

        val cutTimes = graph.videoLayers
            .map { it.placement.start.value + it.placement.duration.value }
            .filter { it > 0 && it < graph.duration.value }
            .distinct()
            .sorted()
        val selfEvaluation = selfEvaluator.evaluate(
            uri = target.temporaryUri,
            expectedDurationUs = graph.duration.value,
            cutTimesUs = cutTimes,
            pass = 1,
        )
        if (!selfEvaluation.passed) {
            target.abort()
            error("Self-evaluation failed: ${selfEvaluation.issues.joinToString { it.code }}")
        }

        check(target.commit()) { "Could not publish the verified export" }
        sessionMemory.recordSelfEvaluation(projectId, selfEvaluation)
        Completed(target.publishedUri ?: destination.toString(), verification, selfEvaluation)
    }

    private fun recommendedBitrate(width: Int, height: Int, fps: Double): Long =
        (width.toLong() * height * fps * 0.09).toLong().coerceIn(4_000_000L, 40_000_000L)
}
