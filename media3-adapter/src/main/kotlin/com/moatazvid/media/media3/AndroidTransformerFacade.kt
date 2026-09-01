package com.moatazvid.media.media3

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.moatazvid.media.AudioCodec
import com.moatazvid.media.BackendKind
import com.moatazvid.media.ExportSettings
import com.moatazvid.media.JobStage
import com.moatazvid.media.MediaEngineError
import com.moatazvid.media.MediaEngineProgress
import com.moatazvid.media.MediaJobHandle
import com.moatazvid.media.MediaResult
import com.moatazvid.media.VideoCodec
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Concrete Media3 Transformer binding. All Transformer access stays on the main application thread. */
@OptIn(UnstableApi::class)
class AndroidTransformerFacade(
    private val context: Context,
    private val compositionFactory: Media3RuntimeCompositionFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TransformerFacade {
    private data class RunningExport(
        val transformer: Transformer,
        val progress: MutableStateFlow<MediaEngineProgress>,
        val pollingJob: Job,
    )

    private val jobs = ConcurrentHashMap<String, RunningExport>()
    private val completedProgress = ConcurrentHashMap<String, MutableStateFlow<MediaEngineProgress>>()

    override suspend fun export(
        jobId: String,
        composition: Media3CompositionSpec,
        outputUri: String,
        settings: ExportSettings,
    ): MediaResult<MediaJobHandle> = withContext(Dispatchers.Main.immediate) {
        if (jobId.isBlank()) return@withContext MediaResult.Failure(MediaEngineError.EncoderFailure(settings.videoCodec.name, "Blank export job id"))
        if (jobs.containsKey(jobId)) return@withContext MediaResult.Failure(MediaEngineError.EncoderFailure(settings.videoCodec.name, "Duplicate export job id"))
        if (settings.videoCodec == VideoCodec.AV1) return@withContext MediaResult.Failure(MediaEngineError.UnsupportedCodec("video/av01", "Media3 Transformer V1 export"))
        if (settings.audioCodec != AudioCodec.AAC) return@withContext MediaResult.Failure(MediaEngineError.UnsupportedCodec(settings.audioCodec.name, "Media3 Transformer V1 export"))

        val outputPath = resolveLocalOutputPath(outputUri)
            ?: return@withContext MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation("Transformer requires an app-local temporary file path"))
        runCatching { File(outputPath).parentFile?.mkdirs() }

        val runtimeComposition = runCatching { compositionFactory.build(composition) }.getOrElse {
            return@withContext MediaResult.Failure(MediaEngineError.Media3UnsupportedOperation(it.message ?: "composition build"))
        }
        val progress = MutableStateFlow(MediaEngineProgress(jobId, JobStage.QUEUED, 0.0, null, null, null))
        val transformer = createTransformer(jobId, settings, progress)
        val polling = scope.launch { pollProgress(jobId, transformer, progress) }
        jobs[jobId] = RunningExport(transformer, progress, polling)

        try {
            progress.value = progress.value.copy(stage = JobStage.ENCODING, percent = 0.0)
            transformer.start(runtimeComposition, outputPath)
            MediaResult.Success(MediaJobHandle(jobId, BackendKind.MEDIA3, resumable = false))
        } catch (failure: Throwable) {
            polling.cancel()
            jobs.remove(jobId)
            progress.value = progress.value.copy(stage = JobStage.FAILED)
            completedProgress[jobId] = progress
            MediaResult.Failure(MediaEngineError.EncoderFailure(settings.videoCodec.name, failure.javaClass.simpleName))
        }
    }

    override suspend fun cancel(jobId: String) = withContext(Dispatchers.Main.immediate) {
        val running = jobs.remove(jobId) ?: return@withContext
        running.pollingJob.cancel()
        runCatching { running.transformer.cancel() }
        running.progress.value = running.progress.value.copy(stage = JobStage.CANCELLED, percent = null, etaMillis = null)
        completedProgress[jobId] = running.progress
    }

    override fun progress(jobId: String): Flow<MediaEngineProgress> =
        jobs[jobId]?.progress?.asStateFlow()
            ?: completedProgress[jobId]?.asStateFlow()
            ?: MutableStateFlow(MediaEngineProgress(jobId, JobStage.QUEUED, null, null, null, null)).asStateFlow()

    private fun createTransformer(
        jobId: String,
        settings: ExportSettings,
        progress: MutableStateFlow<MediaEngineProgress>,
    ): Transformer {
        val videoSettings = VideoEncoderSettings.Builder()
            .setBitrate(settings.videoBitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .build()
        val audioSettings = AudioEncoderSettings.Builder()
            .setBitrate(settings.audioBitrate)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoSettings)
            .setRequestedAudioEncoderSettings(audioSettings)
            .build()
        return Transformer.Builder(context)
            .setVideoMimeType(
                when (settings.videoCodec) {
                    VideoCodec.H264 -> MimeTypes.VIDEO_H264
                    VideoCodec.HEVC -> MimeTypes.VIDEO_H265
                    VideoCodec.AV1 -> error("AV1 rejected before Transformer creation")
                }
            )
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    finish(jobId, progress, JobStage.COMPLETED)
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    finish(jobId, progress, JobStage.FAILED)
                }
            })
            .build()
    }

    private fun finish(jobId: String, progress: MutableStateFlow<MediaEngineProgress>, stage: JobStage) {
        val running = jobs.remove(jobId)
        running?.pollingJob?.cancel()
        progress.value = progress.value.copy(stage = stage, percent = if (stage == JobStage.COMPLETED) 100.0 else progress.value.percent, etaMillis = null)
        completedProgress[jobId] = progress
        // Bounded in-process history; persistent export state belongs to storage-room.
        if (completedProgress.size > 32) completedProgress.keys.firstOrNull()?.let(completedProgress::remove)
    }

    private suspend fun pollProgress(jobId: String, transformer: Transformer, output: MutableStateFlow<MediaEngineProgress>) {
        val holder = ProgressHolder()
        while (jobs.containsKey(jobId)) {
            withContext(Dispatchers.Main.immediate) {
                when (transformer.getProgress(holder)) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> output.value = output.value.copy(stage = JobStage.ENCODING, percent = holder.progress.toDouble())
                    Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> output.value = output.value.copy(stage = JobStage.PROBING, percent = null)
                    Transformer.PROGRESS_STATE_UNAVAILABLE -> output.value = output.value.copy(stage = JobStage.ENCODING, percent = null)
                }
            }
            delay(500)
        }
    }

    private fun resolveLocalOutputPath(value: String): String? {
        val uri = Uri.parse(value)
        return when {
            uri.scheme.isNullOrBlank() -> value
            uri.scheme.equals("file", ignoreCase = true) -> uri.path
            else -> null
        }
    }
}
