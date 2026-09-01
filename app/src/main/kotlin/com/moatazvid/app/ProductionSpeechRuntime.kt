package com.moatazvid.app

import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.moatazvid.core.DurationUs
import com.moatazvid.core.SourceId
import com.moatazvid.core.StreamId
import com.moatazvid.core.TimeRangeUs
import com.moatazvid.core.TimeUs
import com.moatazvid.editor.BackgroundJobType
import com.moatazvid.editor.BackgroundJobUiState
import com.moatazvid.speech.*
import com.moatazvid.speech.android.TranscriptionWorker
import com.moatazvid.speech.android.TranscriptionWorkerRegistry
import com.moatazvid.speech.android.WhisperCppBridge
import com.moatazvid.speech.android.WorkerOutcome
import com.moatazvid.storage.room.*
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** End-to-end local speech composition: model install -> MediaCodec PCM -> whisper.cpp -> Room. */
class ProductionSpeechRuntime(
    private val context: Context,
    private val repository: ProductionProjectRepository,
) {
    private val dao = repository.database.speechDao()
    private val modelRoot = File(context.filesDir, "speech-models").apply { mkdirs() }
    private val models = RoomWhisperModelManager(dao, modelRoot)
    private val store = RoomTranscriptionStore(dao)
    private val native = RootResolvingWhisperBridge(modelRoot)
    private val provider = LocalWhisperProvider(models, native, store, ::deviceCapabilities)
    private val decoder = AndroidMediaPcmDecoder(context)
    private val installer = FileModelInstaller(modelRoot.toPath(), HttpResumableModelSource())
    private val mutableJobs = MutableStateFlow<Map<String, SpeechJobSnapshot>>(emptyMap())
    private val running = ConcurrentHashMap.newKeySet<String>()

    val jobs: Flow<List<BackgroundJobUiState>> = mutableJobs.map { state ->
        state.values.sortedByDescending { it.updatedAt }.map { job ->
            BackgroundJobUiState(
                id = job.jobId,
                type = BackgroundJobType.TRANSCRIPTION,
                progressPermille = job.progressPermille,
                statusText = job.statusText,
                cancellable = job.status !in setOf(TranscriptionJobStatus.COMPLETED, TranscriptionJobStatus.FAILED, TranscriptionJobStatus.CANCELLED),
            )
        }
    }

    init {
        TranscriptionWorkerRegistry.runner = { jobId -> runPersistedJob(jobId) }
    }

    suspend fun ensureCatalog(): WhisperModelPack {
        val existing = dao.modelPack(DEFAULT_MODEL.id.value)
        val file = File(modelRoot, DEFAULT_MODEL.relativePath)
        val status = when {
            file.exists() && file.length() == DEFAULT_MODEL.sizeBytes -> ModelPackStatus.INSTALLED
            existing?.status == ModelPackStatus.CORRUPT.name -> ModelPackStatus.CORRUPT
            else -> ModelPackStatus.NOT_INSTALLED
        }
        val pack = DEFAULT_MODEL.copy(status = status, activeLeaseCount = existing?.activeLeaseCount ?: 0)
        dao.upsertModelPack(pack.toEntity())
        return pack
    }

    suspend fun modelPacks(): List<WhisperModelPack> {
        ensureCatalog()
        return models.list()
    }

    suspend fun installDefaultModel(progress: (ModelInstallProgress) -> Unit = {}): SpeechResult<WhisperModelPack> {
        val pack = ensureCatalog()
        if (pack.status == ModelPackStatus.INSTALLED) return SpeechResult.Success(pack)
        dao.upsertModelPack(pack.copy(status = ModelPackStatus.DOWNLOADING).toEntity())
        val request = ModelInstallRequest(pack, modelRoot.usableSpace, resumeAllowed = true)
        return when (val result = installer.install(request, progress)) {
            is SpeechResult.Success -> {
                val installed = result.value.copy(status = ModelPackStatus.INSTALLED)
                dao.upsertModelPack(installed.toEntity())
                SpeechResult.Success(installed)
            }
            is SpeechResult.Failure -> {
                val state = if (result.error is SpeechError.CorruptedModel) ModelPackStatus.CORRUPT else ModelPackStatus.NOT_INSTALLED
                dao.upsertModelPack(pack.copy(status = state).toEntity())
                result
            }
        }
    }

    suspend fun queueTranscription(sourceId: SourceId, language: LanguageCode = LanguageCode.AUTO): SpeechResult<TranscriptionJobId> {
        val pack = ensureCatalog()
        if (pack.status != ModelPackStatus.INSTALLED) return SpeechResult.Failure(SpeechError.ModelNotInstalled(pack.id))
        val source = repository.database.mediaDao().source(sourceId.value)
            ?: return SpeechResult.Failure(SpeechError.CorruptedAudio(sourceId, "Source is missing"))
        val jobId = TranscriptionJobId("transcribe_${UUID.randomUUID()}")
        val now = System.currentTimeMillis()
        val job = TranscriptionJob(
            id = jobId,
            sourceId = sourceId,
            streamId = null,
            modelPackId = pack.id,
            language = language,
            sourceFingerprint = source.quickFingerprint,
            status = TranscriptionJobStatus.QUEUED,
            currentChunk = 0,
            totalChunks = null,
            progressPermille = 0,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            errorCode = null,
        )
        store.createJob(job)
        publish(job, "بانتظار بدء التفريغ المحلي")
        val work = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(Data.Builder().putString(TranscriptionWorker.KEY_JOB_ID, jobId.value).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("transcription:${jobId.value}", ExistingWorkPolicy.KEEP, work)
        return SpeechResult.Success(jobId)
    }

    suspend fun transcribeNow(sourceId: SourceId, language: LanguageCode = LanguageCode.AUTO): SpeechResult<TranscriptBundle> {
        val queued = queueTranscription(sourceId, language)
        val jobId = when (queued) {
            is SpeechResult.Success -> queued.value
            is SpeechResult.Failure -> return queued
        }
        val outcome = runPersistedJob(jobId.value)
        if (outcome != WorkerOutcome.SUCCESS) {
            val row = dao.job(jobId.value)
            return SpeechResult.Failure(SpeechError.NativeRuntimeFailure(row?.errorCode ?: "Transcription failed"))
        }
        return transcriptForSource(sourceId)?.let { SpeechResult.Success(it) }
            ?: SpeechResult.Failure(SpeechError.NativeRuntimeFailure("Transcript was not persisted"))
    }

    suspend fun runPersistedJob(jobIdValue: String): WorkerOutcome {
        if (!running.add(jobIdValue)) return WorkerOutcome.RETRY
        try {
            val row = dao.job(jobIdValue) ?: return WorkerOutcome.FAILURE
            val jobId = TranscriptionJobId(row.jobId)
            val sourceId = SourceId(row.sourceId)
            val uri = repository.sourceUri(sourceId) ?: run {
                fail(row, "Source URI unavailable")
                return WorkerOutcome.FAILURE
            }
            val pack = ensureCatalog()
            if (pack.status != ModelPackStatus.INSTALLED || pack.id.value != row.modelPackId) {
                fail(row, "Speech model is not installed")
                return WorkerOutcome.FAILURE
            }
            val preparing = row.copy(status = TranscriptionJobStatus.PREPARING_AUDIO.name, updatedAtEpochMs = System.currentTimeMillis())
            dao.upsertJob(preparing)
            publish(preparing.toDomain(), "أجهز الصوت محليًا…")
            val audio = when (val decoded = decoder.decode(sourceId, uri)) {
                is SpeechResult.Success -> decoded.value
                is SpeechResult.Failure -> {
                    fail(preparing, decoded.error.toString())
                    return WorkerOutcome.FAILURE
                }
            }
            val request = TranscriptionRequest(
                jobId = jobId,
                sourceId = sourceId,
                streamId = row.streamId?.let(::StreamId),
                sourceFingerprint = row.sourceFingerprint,
                modelPackId = ModelPackId(row.modelPackId),
                language = runCatching { LanguageCode(row.languageTag) }.getOrDefault(LanguageCode.AUTO),
                audio = audio,
                resumeFromChunk = dao.checkpoint(row.jobId)?.completedChunkExclusive ?: row.currentChunk,
            )
            when (val started = provider.transcribe(request)) {
                is SpeechResult.Failure -> {
                    fail(preparing, started.error.toString())
                    return WorkerOutcome.FAILURE
                }
                is SpeechResult.Success -> Unit
            }
            val terminal = provider.observe(jobId).first { event ->
                when (event) {
                    is TranscriptionEvent.Started -> {
                        publish(row.toDomain().copy(status = TranscriptionJobStatus.RUNNING), "أفرغ الكلام محليًا…")
                        false
                    }
                    is TranscriptionEvent.Progress -> {
                        val duration = audio.estimatedDuration?.value?.coerceAtLeast(1L)
                        val permille = duration?.let { ((event.processed.value * 1000L) / it).toInt().coerceIn(0, 999) } ?: 0
                        val current = dao.job(row.jobId) ?: row
                        dao.upsertJob(current.copy(
                            status = TranscriptionJobStatus.RUNNING.name,
                            currentChunk = event.completedChunks,
                            totalChunks = event.totalChunks,
                            progressPermille = permille,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ))
                        publish(current.toDomain().copy(status = TranscriptionJobStatus.RUNNING, currentChunk = event.completedChunks, totalChunks = event.totalChunks, progressPermille = permille), "أفرغ الكلام محليًا… ${permille / 10}%")
                        false
                    }
                    is TranscriptionEvent.Partial -> false
                    is TranscriptionEvent.Completed, is TranscriptionEvent.Failed, is TranscriptionEvent.Cancelled -> true
                }
            }
            return when (terminal) {
                is TranscriptionEvent.Completed -> {
                    val completed = (dao.job(row.jobId) ?: row).copy(status = TranscriptionJobStatus.COMPLETED.name, progressPermille = 1000, updatedAtEpochMs = System.currentTimeMillis(), errorCode = null)
                    dao.upsertJob(completed)
                    publish(completed.toDomain(), "اكتمل التفريغ المحلي")
                    WorkerOutcome.SUCCESS
                }
                is TranscriptionEvent.Cancelled -> {
                    val cancelled = (dao.job(row.jobId) ?: row).copy(status = TranscriptionJobStatus.CANCELLED.name, updatedAtEpochMs = System.currentTimeMillis())
                    dao.upsertJob(cancelled)
                    publish(cancelled.toDomain(), "أُلغي التفريغ")
                    WorkerOutcome.FAILURE
                }
                is TranscriptionEvent.Failed -> {
                    fail(dao.job(row.jobId) ?: row, terminal.error.toString())
                    WorkerOutcome.FAILURE
                }
                else -> WorkerOutcome.FAILURE
            }
        } catch (failure: Throwable) {
            dao.job(jobIdValue)?.let { fail(it, failure.message ?: failure.javaClass.simpleName) }
            return WorkerOutcome.RETRY
        } finally {
            running.remove(jobIdValue)
        }
    }

    suspend fun cancel(jobId: String): SpeechResult<Unit> {
        val row = dao.job(jobId) ?: return SpeechResult.Failure(SpeechError.NativeRuntimeFailure("Unknown transcription job"))
        val result = provider.cancel(TranscriptionJobId(jobId))
        if (result is SpeechResult.Failure) {
            val cancelled = row.copy(status = TranscriptionJobStatus.CANCELLED.name, updatedAtEpochMs = System.currentTimeMillis())
            dao.upsertJob(cancelled)
            publish(cancelled.toDomain(), "أُلغي التفريغ")
            WorkManager.getInstance(context).cancelUniqueWork("transcription:$jobId")
            return SpeechResult.Success(Unit)
        }
        WorkManager.getInstance(context).cancelUniqueWork("transcription:$jobId")
        return result
    }

    suspend fun transcriptForSource(sourceId: SourceId): TranscriptBundle? {
        val row = dao.readyTranscriptForSource(sourceId.value) ?: return null
        return store.load(TranscriptId(row.transcriptId))
    }

    suspend fun transcriptForProject(projectId: com.moatazvid.core.ProjectId): TranscriptBundle? {
        val sources = repository.database.mediaDao().sources(projectId.value)
        for (source in sources) transcriptForSource(SourceId(source.sourceId))?.let { return it }
        return null
    }

    suspend fun allTranscriptsForProject(projectId: com.moatazvid.core.ProjectId): List<TranscriptBundle> {
        val sourceIds = repository.database.mediaDao().sources(projectId.value).map { it.sourceId }
        if (sourceIds.isEmpty()) return emptyList()
        val rows = dao.readyTranscriptsForSources(sourceIds)
        return rows.distinctBy { it.sourceId }.mapNotNull { store.load(TranscriptId(it.transcriptId)) }
    }

    private suspend fun fail(row: TranscriptionJobEntity, detail: String) {
        val failed = row.copy(status = TranscriptionJobStatus.FAILED.name, errorCode = detail.take(240), updatedAtEpochMs = System.currentTimeMillis())
        dao.upsertJob(failed)
        publish(failed.toDomain(), "فشل التفريغ المحلي")
    }

    private fun publish(job: TranscriptionJob, text: String) {
        mutableJobs.value = mutableJobs.value + (job.id.value to SpeechJobSnapshot(job.id.value, job.status, job.progressPermille, text, job.updatedAtEpochMs))
    }

    private fun deviceCapabilities(): DeviceCapabilities {
        val activity = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.CRITICAL
            else -> ThermalStatus.UNKNOWN
        } else ThermalStatus.UNKNOWN
        return DeviceCapabilities(
            totalRamBytes = info.totalMem,
            availableRamBytes = info.availMem,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            thermalStatus = thermal,
            batteryLow = false,
        )
    }

    companion object {
        val DEFAULT_MODEL = WhisperModelPack(
            id = ModelPackId("whisper-base-multilingual"),
            displayName = "Whisper Base Multilingual",
            version = "whisper.cpp-main",
            sizeBytes = 147_951_465L,
            requiredRamBytes = 600_000_000L,
            multilingual = true,
            languages = emptySet(),
            sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            relativePath = "ggml-base.bin",
            license = "MIT",
            sourceUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            status = ModelPackStatus.NOT_INSTALLED,
        )
    }
}

private data class SpeechJobSnapshot(
    val jobId: String,
    val status: TranscriptionJobStatus,
    val progressPermille: Int,
    val statusText: String,
    val updatedAt: Long,
)

private class RootResolvingWhisperBridge(private val root: File) : WhisperNativeBridge {
    private val delegate = WhisperCppBridge()
    override suspend fun loadModel(absolutePath: String, threads: Int): SpeechResult<Long> =
        delegate.loadModel(File(root, absolutePath).canonicalPath, threads)
    override suspend fun transcribe(modelHandle: Long, mono16Khz: FloatArray, language: String, wordTimestamps: Boolean, cancelled: () -> Boolean) =
        delegate.transcribe(modelHandle, mono16Khz, language, wordTimestamps, cancelled)
    override suspend fun unloadModel(modelHandle: Long) = delegate.unloadModel(modelHandle)
}

private class HttpResumableModelSource : ResumableModelSource {
    override suspend fun open(url: String, offset: Long): InputStream = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        connection.connect()
        if (connection.responseCode !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            connection.disconnect()
            error("Model download HTTP ${connection.responseCode}")
        }
        if (offset > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            error("Model server did not honor resume range")
        }
        object : InputStream() {
            private val delegate = connection.inputStream
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
            override fun close() { try { delegate.close() } finally { connection.disconnect() } }
        }
    }
}

private class RoomWhisperModelManager(
    private val dao: SpeechDao,
    private val root: File,
) : ModelManager {
    private val leases = ConcurrentHashMap<String, ModelPackId>()

    override suspend fun list(): List<WhisperModelPack> = dao.modelPacks().map { it.toDomain(root) }
    override suspend fun get(id: ModelPackId): WhisperModelPack? = dao.modelPack(id.value)?.toDomain(root)

    override suspend fun acquire(id: ModelPackId): SpeechResult<ModelLease> {
        val pack = get(id) ?: return SpeechResult.Failure(SpeechError.ModelNotInstalled(id))
        if (pack.status != ModelPackStatus.INSTALLED || !File(root, pack.relativePath).isFile) return SpeechResult.Failure(SpeechError.ModelNotInstalled(id))
        val token = UUID.randomUUID().toString()
        leases[token] = id
        dao.updateLeaseCount(id.value, pack.activeLeaseCount + 1)
        return SpeechResult.Success(ModelLease(pack.copy(activeLeaseCount = pack.activeLeaseCount + 1), token))
    }

    override suspend fun release(lease: ModelLease) {
        val id = leases.remove(lease.token) ?: return
        val pack = get(id) ?: return
        val next = (pack.activeLeaseCount - 1).coerceAtLeast(0)
        dao.updateLeaseCount(id.value, next)
        if (next == 0 && pack.status == ModelPackStatus.PENDING_DELETE) requestDelete(id)
    }

    override suspend fun markCorrupt(id: ModelPackId, reason: String) {
        dao.updateModelStatus(id.value, ModelPackStatus.CORRUPT.name)
    }

    override suspend fun requestDelete(id: ModelPackId): SpeechResult<Unit> {
        val pack = get(id) ?: return SpeechResult.Success(Unit)
        if (pack.activeLeaseCount > 0) {
            dao.updateModelStatus(id.value, ModelPackStatus.PENDING_DELETE.name)
            return SpeechResult.Success(Unit)
        }
        File(root, pack.relativePath).delete()
        dao.updateModelStatus(id.value, ModelPackStatus.NOT_INSTALLED.name)
        return SpeechResult.Success(Unit)
    }
}

private class RoomTranscriptionStore(private val dao: SpeechDao) : TranscriptionStore {
    override suspend fun createJob(job: TranscriptionJob) { dao.upsertJob(job.toEntity()) }
    override suspend fun updateJob(job: TranscriptionJob) { dao.upsertJob(job.toEntity()) }

    override suspend fun checkpoint(checkpoint: TranscriptionCheckpoint, segments: List<TranscriptSegment>, words: List<TranscriptWord>) {
        dao.upsertCheckpoint(checkpoint.toEntity())
        if (segments.isNotEmpty()) dao.insertSegments(segments.map { it.toEntity() })
        if (words.isNotEmpty()) dao.insertWords(words.map { it.toEntity() })
        dao.job(checkpoint.jobId.value)?.let { row ->
            dao.upsertJob(row.copy(
                status = TranscriptionJobStatus.RUNNING.name,
                currentChunk = checkpoint.completedChunkExclusive,
                updatedAtEpochMs = checkpoint.updatedAtEpochMs,
            ))
        }
    }

    override suspend fun loadCheckpoint(jobId: TranscriptionJobId): TranscriptionCheckpoint? = dao.checkpoint(jobId.value)?.toDomain()

    override suspend fun finalize(bundle: TranscriptBundle) {
        dao.upsertTranscript(bundle.toEntity())
        if (bundle.segments.isNotEmpty()) dao.insertSegments(bundle.segments.map { it.toEntity() })
        if (bundle.words.isNotEmpty()) dao.insertWords(bundle.words.map { it.toEntity() })
    }

    override suspend fun load(transcriptId: TranscriptId): TranscriptBundle? {
        val transcript = dao.transcript(transcriptId.value) ?: return null
        val segments = dao.segments(transcriptId.value).map { it.toDomain() }
        val words = dao.words(transcriptId.value).map { it.toDomain() }
        val model = dao.modelPack(transcript.modelId)
        val domain = Transcript(
            id = TranscriptId(transcript.transcriptId),
            sourceId = SourceId(transcript.sourceId),
            streamId = transcript.streamId?.let(::StreamId),
            language = runCatching { LanguageCode(transcript.languageTag ?: "auto") }.getOrDefault(LanguageCode.AUTO),
            status = runCatching { TranscriptStatus.valueOf(transcript.status) }.getOrDefault(TranscriptStatus.READY),
            modelPackId = ModelPackId(transcript.modelId),
            sourceFingerprint = transcript.sourceFingerprint,
            revision = transcript.revision,
            createdAtEpochMs = transcript.createdAtEpochMs,
            updatedAtEpochMs = transcript.createdAtEpochMs,
        )
        val duration = words.lastOrNull()?.sourceRange?.endExclusive ?: segments.lastOrNull()?.sourceRange?.endExclusive ?: TimeUs(0)
        return TranscriptBundle(
            transcript = domain,
            metadata = TranscriptMetadata(
                transcriptId = domain.id,
                providerId = transcript.engine,
                modelName = model?.displayName ?: transcript.modelId,
                modelVersion = transcript.modelVersion,
                wordTimestampQuality = TimestampQuality.TOKEN_DERIVED,
                detectedLanguageConfidence = null,
                durationProcessed = DurationUs(duration.value),
            ),
            segments = segments,
            words = words,
        )
    }

    override suspend fun invalidateForSource(sourceId: SourceId, newFingerprint: String) {
        dao.invalidateChangedSource(sourceId.value, newFingerprint)
    }
}

private class AndroidMediaPcmDecoder(private val context: Context) {
    suspend fun decode(sourceId: SourceId, uri: Uri): SpeechResult<PcmChunkSource> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { track = index; format = candidate; break }
            }
            if (track < 0 || format == null) return@withContext SpeechResult.Failure(SpeechError.NoAudioTrack(sourceId))
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext SpeechResult.Failure(SpeechError.NoAudioTrack(sourceId))
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val converted = mutableListOf<FloatArray>()
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Missing decoder input buffer")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.nativeOrder())
                            val raw = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(info.size / 4) { output.float.coerceIn(-1f, 1f) }
                                else -> FloatArray(info.size / 2) { (output.short / 32768f).coerceIn(-1f, 1f) }
                            }
                            val aligned = raw.size - (raw.size % channels.coerceAtLeast(1))
                            if (aligned > 0) converted += AudioPreprocessor.toMono16Khz(if (aligned == raw.size) raw else raw.copyOf(aligned), sampleRate, channels)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (converted.sumOf { it.size } == 0) return@withContext SpeechResult.Failure(SpeechError.CorruptedAudio(sourceId, "Decoder produced no PCM"))
            SpeechResult.Success(SegmentedPcmChunkSource(converted))
        } catch (failure: Throwable) {
            SpeechResult.Failure(SpeechError.CorruptedAudio(sourceId, failure.message ?: "Audio decode failed"))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}

private class SegmentedPcmChunkSource(
    private val blocks: List<FloatArray>,
    private val chunkDurationUs: Long = 30_000_000L,
    private val overlapUs: Long = 1_500_000L,
) : PcmChunkSource {
    override val sampleRateHz: Int = 16_000
    override val channels: Int = 1
    private val offsets = IntArray(blocks.size + 1).also { values ->
        blocks.indices.forEach { index -> values[index + 1] = values[index] + blocks[index].size }
    }
    private val totalSamples = offsets.last()
    override val estimatedDuration = DurationUs(totalSamples.toLong() * 1_000_000L / sampleRateHz)

    override suspend fun chunks(startAtIndex: Int): Flow<PcmChunk> = kotlinx.coroutines.flow.flow {
        val chunkSamples = (chunkDurationUs * sampleRateHz / 1_000_000L).toInt()
        val overlapSamples = (overlapUs * sampleRateHz / 1_000_000L).toInt()
        val step = chunkSamples - overlapSamples
        var index = startAtIndex.coerceAtLeast(0)
        while (index.toLong() * step < totalSamples) {
            val start = index * step
            val end = minOf(totalSamples, start + chunkSamples)
            emit(PcmChunk(index, TimeUs(start.toLong() * 1_000_000L / sampleRateHz), copyRange(start, end), if (index == 0) DurationUs(0) else DurationUs(overlapUs)))
            index++
        }
    }

    private fun copyRange(start: Int, end: Int): FloatArray {
        val output = FloatArray(end - start)
        var blockIndex = locate(start)
        var cursor = start
        var out = 0
        while (cursor < end && blockIndex < blocks.size) {
            val block = blocks[blockIndex]
            val blockStart = offsets[blockIndex]
            val localStart = (cursor - blockStart).coerceAtLeast(0)
            val count = minOf(block.size - localStart, end - cursor)
            block.copyInto(output, out, localStart, localStart + count)
            out += count
            cursor += count
            blockIndex++
        }
        return output
    }

    private fun locate(sample: Int): Int {
        var low = 0
        var high = blocks.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                sample < offsets[mid] -> high = mid - 1
                sample >= offsets[mid + 1] -> low = mid + 1
                else -> return mid
            }
        }
        return low.coerceIn(0, blocks.lastIndex)
    }
}

private fun WhisperModelPack.toEntity() = SpeechModelPackEntity(
    modelPackId = id.value,
    displayName = displayName,
    version = version,
    sizeBytes = sizeBytes,
    requiredRamBytes = requiredRamBytes,
    multilingual = multilingual,
    languagesJson = JSONArray(languages.map { it.tag }).toString(),
    sha256 = sha256,
    relativePath = relativePath,
    license = license,
    sourceUrl = sourceUrl,
    status = status.name,
    activeLeaseCount = activeLeaseCount,
)

private fun SpeechModelPackEntity.toDomain(root: File) = WhisperModelPack(
    id = ModelPackId(modelPackId),
    displayName = displayName,
    version = version,
    sizeBytes = sizeBytes,
    requiredRamBytes = requiredRamBytes,
    multilingual = multilingual,
    languages = runCatching { JSONArray(languagesJson).let { array -> (0 until array.length()).map { LanguageCode(array.getString(it)) }.toSet() } }.getOrDefault(emptySet()),
    sha256 = sha256,
    relativePath = relativePath,
    license = license,
    sourceUrl = sourceUrl,
    status = if (status == ModelPackStatus.INSTALLED.name && !File(root, relativePath).isFile) ModelPackStatus.NOT_INSTALLED else runCatching { ModelPackStatus.valueOf(status) }.getOrDefault(ModelPackStatus.NOT_INSTALLED),
    activeLeaseCount = activeLeaseCount,
)

private fun TranscriptionJob.toEntity() = TranscriptionJobEntity(
    jobId = id.value,
    sourceId = sourceId.value,
    streamId = streamId?.value,
    modelPackId = modelPackId.value,
    languageTag = language.tag,
    sourceFingerprint = sourceFingerprint,
    status = status.name,
    currentChunk = currentChunk,
    totalChunks = totalChunks,
    progressPermille = progressPermille,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    errorCode = errorCode,
)

private fun TranscriptionJobEntity.toDomain() = TranscriptionJob(
    id = TranscriptionJobId(jobId), sourceId = SourceId(sourceId), streamId = streamId?.let(::StreamId),
    modelPackId = ModelPackId(modelPackId), language = runCatching { LanguageCode(languageTag) }.getOrDefault(LanguageCode.AUTO),
    sourceFingerprint = sourceFingerprint, status = runCatching { TranscriptionJobStatus.valueOf(status) }.getOrDefault(TranscriptionJobStatus.FAILED),
    currentChunk = currentChunk, totalChunks = totalChunks, progressPermille = progressPermille,
    createdAtEpochMs = createdAtEpochMs, updatedAtEpochMs = updatedAtEpochMs, errorCode = errorCode,
)

private fun TranscriptionCheckpoint.toEntity() = TranscriptionCheckpointEntity(
    jobId.value, sourceFingerprint, completedChunkExclusive, committedWordCount, committedSegmentCount,
    lastCommittedSourceTime.value, updatedAtEpochMs,
)
private fun TranscriptionCheckpointEntity.toDomain() = TranscriptionCheckpoint(
    TranscriptionJobId(jobId), sourceFingerprint, completedChunkExclusive, committedWordCount, committedSegmentCount,
    TimeUs(lastCommittedSourceTimeUs), updatedAtEpochMs,
)

private fun TranscriptBundle.toEntity() = TranscriptEntity(
    transcriptId = transcript.id.value,
    sourceId = transcript.sourceId.value,
    streamId = transcript.streamId?.value,
    languageTag = transcript.language.tag,
    engine = metadata.providerId,
    modelId = transcript.modelPackId.value,
    modelVersion = metadata.modelVersion,
    status = transcript.status.name,
    sourceFingerprint = transcript.sourceFingerprint,
    relativeArtifactPath = null,
    revision = transcript.revision,
    createdAtEpochMs = transcript.createdAtEpochMs,
)

private fun TranscriptSegment.toEntity() = TranscriptSegmentEntity(
    id.value, transcriptId.value, sourceId.value, index, sourceRange.start.value, sourceRange.endExclusive.value,
    text, normalizedSearchText, speakerId, confidence,
)
private fun TranscriptSegmentEntity.toDomain() = TranscriptSegment(
    TranscriptSegmentId(segmentId), TranscriptId(transcriptId), SourceId(sourceId), segmentIndex,
    TimeRangeUs(TimeUs(startUs), TimeUs(endUs)), text, normalizedSearchText, speakerId, confidence,
)
private fun TranscriptWord.toEntity() = TranscriptWordEntity(
    id.value, transcriptId.value, segmentId.value, sourceId.value, index, text, normalizedSearchText,
    sourceRange.start.value, sourceRange.endExclusive.value, confidence, language.tag, speakerId, type.name,
)
private fun TranscriptWordEntity.toDomain() = TranscriptWord(
    TranscriptWordId(wordId), TranscriptId(transcriptId), TranscriptSegmentId(segmentId), SourceId(sourceId), wordIndex,
    text, normalizedSearchText, TimeRangeUs(TimeUs(startUs), TimeUs(endUs)), confidence,
    runCatching { LanguageCode(languageTag) }.getOrDefault(LanguageCode.AUTO), speakerId,
    runCatching { TranscriptWordType.valueOf(wordType) }.getOrDefault(TranscriptWordType.WORD),
)
