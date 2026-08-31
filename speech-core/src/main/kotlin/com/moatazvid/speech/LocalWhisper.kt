package com.moatazvid.speech

import com.moatazvid.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class NativeWord(val text: String, val startUs: Long, val endUs: Long, val probability: Float?, val type: TranscriptWordType = TranscriptWordType.WORD)
data class NativeSegment(val text: String, val startUs: Long, val endUs: Long, val probability: Float?, val words: List<NativeWord>)
data class NativeTranscription(val language: String, val languageProbability: Float?, val segments: List<NativeSegment>)

interface WhisperNativeBridge {
    suspend fun loadModel(absolutePath: String, threads: Int): SpeechResult<Long>
    suspend fun transcribe(modelHandle: Long, mono16Khz: FloatArray, language: String, wordTimestamps: Boolean, cancelled: () -> Boolean): SpeechResult<NativeTranscription>
    suspend fun unloadModel(modelHandle: Long)
}

interface IdGenerator { fun next(prefix: String): String }
class MonotonicIdGenerator : IdGenerator {
    private var value = 0L
    @Synchronized override fun next(prefix: String): String = "${prefix}_${++value}"
}

/** Converts arbitrary interleaved PCM to Whisper's 16 kHz mono float input. */
object AudioPreprocessor {
    fun toMono16Khz(interleaved: FloatArray, inputRate: Int, channels: Int): FloatArray {
        require(inputRate > 0 && channels > 0 && interleaved.size % channels == 0)
        val frames = interleaved.size / channels
        val mono = FloatArray(frames) { frame ->
            var sum = 0f
            repeat(channels) { sum += interleaved[frame * channels + it] }
            (sum / channels).coerceIn(-1f, 1f)
        }
        if (inputRate == 16_000) return mono
        val outputSize = (frames.toLong() * 16_000 / inputRate).toInt()
        return FloatArray(outputSize) { out ->
            val position = out.toDouble() * inputRate / 16_000.0
            val left = position.toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val fraction = (position - left).toFloat()
            mono[left] * (1f - fraction) + mono[right] * fraction
        }
    }
}

data class ChunkingPolicy(val chunkDuration: DurationUs = DurationUs(30_000_000), val overlap: DurationUs = DurationUs(1_500_000)) {
    init { require(chunkDuration.value > overlap.value && overlap.value >= 0) }
}

class FloatPcmChunkSource(
    private val samples: FloatArray,
    private val policy: ChunkingPolicy = ChunkingPolicy(),
) : PcmChunkSource {
    override val sampleRateHz = 16_000
    override val channels = 1
    override val estimatedDuration = DurationUs(samples.size * 1_000_000L / sampleRateHz)
    override suspend fun chunks(startAtIndex: Int): Flow<PcmChunk> = flow {
        val chunkSamples = (policy.chunkDuration.value * sampleRateHz / 1_000_000).toInt()
        val overlapSamples = (policy.overlap.value * sampleRateHz / 1_000_000).toInt()
        val step = chunkSamples - overlapSamples
        var index = startAtIndex
        while (index * step < samples.size) {
            val start = index * step
            val end = minOf(samples.size, start + chunkSamples)
            emit(PcmChunk(index, TimeUs(start * 1_000_000L / sampleRateHz), samples.copyOfRange(start, end), if (index == 0) DurationUs(0) else policy.overlap))
            index++
        }
    }
}

class TranscriptOverlapReconciler {
    fun accept(chunk: PcmChunk, native: NativeTranscription, committedThrough: TimeUs): List<NativeSegment> {
        val absolute = native.segments.map { segment ->
            segment.copy(startUs = segment.startUs + chunk.sourceStart.value, endUs = segment.endUs + chunk.sourceStart.value,
                words = segment.words.map { it.copy(startUs = it.startUs + chunk.sourceStart.value, endUs = it.endUs + chunk.sourceStart.value) })
        }
        return absolute.mapNotNull { segment ->
            val words = segment.words.filter { it.endUs > committedThrough.value }
            if (words.isEmpty() && segment.endUs <= committedThrough.value) null else segment.copy(words = words)
        }
    }
}

class LocalWhisperProvider(
    private val models: ModelManager,
    private val native: WhisperNativeBridge,
    private val store: TranscriptionStore,
    private val device: suspend () -> DeviceCapabilities,
    private val ids: IdGenerator = MonotonicIdGenerator(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SpeechProvider {
    override val id = "local_whisper_cpp"
    private val events = ConcurrentHashMap<TranscriptionJobId, MutableSharedFlow<TranscriptionEvent>>()
    private val cancelled = ConcurrentHashMap<TranscriptionJobId, AtomicBoolean>()

    override suspend fun transcribe(request: TranscriptionRequest): SpeechResult<TranscriptionHandle> {
        val pack = models.get(request.modelPackId) ?: return SpeechResult.Failure(SpeechError.ModelNotInstalled(request.modelPackId))
        if (pack.status != ModelPackStatus.INSTALLED) return SpeechResult.Failure(SpeechError.ModelNotInstalled(pack.id))
        val suitability = estimateRequirements(request)
        if (!suitability.deviceSuitable) return SpeechResult.Failure(SpeechError.InsufficientMemory(pack.requiredRamBytes, device().availableRamBytes, suitability.recommendedModelPackId))
        val flow = events.getOrPut(request.jobId) { MutableSharedFlow(replay = 1, extraBufferCapacity = 8) }
        val flag = AtomicBoolean(false)
        cancelled[request.jobId] = flag
        scope.launch { execute(request, pack, flow, flag) }
        return SpeechResult.Success(TranscriptionHandle(request.jobId, resumable = true))
    }

    override suspend fun cancel(jobId: TranscriptionJobId): SpeechResult<Unit> {
        cancelled[jobId]?.set(true) ?: return SpeechResult.Failure(SpeechError.NativeRuntimeFailure("Unknown transcription job"))
        return SpeechResult.Success(Unit)
    }

    override suspend fun getCapabilities() = SpeechCapabilities(true, true, false, false, true, true)
    override suspend fun getModelInfo(modelPackId: ModelPackId): SpeechModelInfo? = models.get(modelPackId)?.let {
        SpeechModelInfo(it.id, it.displayName, it.version, it.multilingual, null, it.sizeBytes)
    }
    override suspend fun estimateRequirements(request: TranscriptionRequest): RequirementEstimate {
        val pack = models.get(request.modelPackId)
        val capabilities = device()
        val suitable = pack != null && pack.requiredRamBytes <= (capabilities.availableRamBytes * 0.65).toLong() && capabilities.thermalStatus !in setOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL)
        val suggested = DeviceCapabilityEstimator().selectModel(models.list(), capabilities)?.id
        return RequirementEstimate(pack?.requiredRamBytes ?: 0, 16L * 1024 * 1024, null, suitable, suggested,
            buildList { if (!suitable) add("Device memory or thermal state is not suitable for this model") })
    }
    override fun observe(jobId: TranscriptionJobId): Flow<TranscriptionEvent> = events.getOrPut(jobId) { MutableSharedFlow(replay = 1, extraBufferCapacity = 8) }.asSharedFlow()

    private suspend fun execute(request: TranscriptionRequest, pack: WhisperModelPack, flow: MutableSharedFlow<TranscriptionEvent>, flag: AtomicBoolean) {
        var lease: ModelLease? = null
        var handle: Long? = null
        var checkpoint = store.loadCheckpoint(request.jobId)
        val transcriptId = TranscriptId("transcript_${request.jobId.value}")
        val allSegments = mutableListOf<TranscriptSegment>()
        val allWords = mutableListOf<TranscriptWord>()
        try {
            val now = clock()
            store.createJob(TranscriptionJob(request.jobId, request.sourceId, request.streamId, request.modelPackId, request.language,
                request.sourceFingerprint, TranscriptionJobStatus.RUNNING, checkpoint?.completedChunkExclusive ?: request.resumeFromChunk, null, 0, now, now, null))
            lease = when (val result = models.acquire(pack.id)) { is SpeechResult.Success -> result.value; is SpeechResult.Failure -> throw SpeechFailure(result.error) }
            handle = when (val result = native.loadModel(pack.relativePath, device().cpuCores.coerceIn(1, 8))) { is SpeechResult.Success -> result.value; is SpeechResult.Failure -> throw SpeechFailure(result.error) }
            flow.emit(TranscriptionEvent.Started(request.jobId, null))
            var completed = checkpoint?.completedChunkExclusive ?: request.resumeFromChunk
            var committedThrough = checkpoint?.lastCommittedSourceTime ?: TimeUs(0)
            request.audio.chunks(completed).collect { chunk ->
                if (flag.get()) throw CancellationException("cancelled")
                val result = when (val nativeResult = native.transcribe(handle, chunk.samplesMono16Khz, request.language.tag, true, flag::get)) {
                    is SpeechResult.Success -> nativeResult.value
                    is SpeechResult.Failure -> throw SpeechFailure(nativeResult.error)
                }
                val accepted = TranscriptOverlapReconciler().accept(chunk, result, committedThrough)
                val newSegments = accepted.map { segment ->
                    val segmentId = TranscriptSegmentId(ids.next("segment"))
                    TranscriptSegment(segmentId, transcriptId, request.sourceId, allSegments.size,
                        TimeRangeUs(TimeUs(segment.startUs), TimeUs(maxOf(segment.endUs, segment.startUs + 1))), segment.text,
                        ArabicTextNormalizer.normalize(segment.text), null, segment.probability).also { entity ->
                        segment.words.forEach { word ->
                            allWords += TranscriptWord(TranscriptWordId(ids.next("word")), transcriptId, segmentId, request.sourceId, allWords.size,
                                word.text, ArabicTextNormalizer.normalize(word.text), TimeRangeUs(TimeUs(word.startUs), TimeUs(maxOf(word.endUs, word.startUs + 1))),
                                word.probability, LanguageCode(if (result.language == "auto") request.language.tag else result.language), null, word.type)
                        }
                    }
                }
                allSegments += newSegments
                completed = chunk.index + 1
                committedThrough = TimeUs(maxOf(committedThrough.value, chunk.sourceStart.value + chunk.duration.value - chunk.overlapBefore.value))
                checkpoint = TranscriptionCheckpoint(request.jobId, request.sourceFingerprint, completed, allWords.size, allSegments.size, committedThrough, clock())
                store.checkpoint(checkpoint!!, newSegments, allWords.takeLast(accepted.sumOf { it.words.size }))
                flow.emit(TranscriptionEvent.Partial(request.jobId, checkpoint!!, newSegments, allWords.takeLast(accepted.sumOf { it.words.size })))
                flow.emit(TranscriptionEvent.Progress(request.jobId, completed, null, DurationUs(committedThrough.value)))
            }
            val finished = clock()
            val transcript = Transcript(transcriptId, request.sourceId, request.streamId, request.language, TranscriptStatus.READY, pack.id, request.sourceFingerprint, 1, now, finished)
            val bundle = TranscriptBundle(transcript, TranscriptMetadata(transcriptId, id, pack.displayName, pack.version, TimestampQuality.NATIVE_WORD, null,
                request.audio.estimatedDuration ?: DurationUs(committedThrough.value)), allSegments, allWords)
            store.finalize(bundle)
            flow.emit(TranscriptionEvent.Completed(request.jobId, bundle))
        } catch (_: CancellationException) {
            flow.emit(TranscriptionEvent.Cancelled(request.jobId, checkpoint))
        } catch (failure: SpeechFailure) {
            flow.emit(TranscriptionEvent.Failed(request.jobId, failure.error))
        } catch (failure: Throwable) {
            flow.emit(TranscriptionEvent.Failed(request.jobId, SpeechError.NativeRuntimeFailure(failure.message ?: "Native transcription failed")))
        } finally {
            handle?.let { native.unloadModel(it) }
            lease?.let { models.release(it) }
            cancelled.remove(request.jobId)
        }
    }

    private class SpeechFailure(val error: SpeechError) : RuntimeException()
}
