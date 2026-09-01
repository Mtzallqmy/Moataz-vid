package com.moatazvid.media.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.moatazvid.media.AudioGainPoint
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Deterministic PCM gain automation shared by CompositionPlayer and Transformer.
 * Supports attenuation/boost, item fades and speech ducking. Samples are peak-protected so a
 * positive gain request cannot wrap or produce invalid PCM values.
 */
@OptIn(UnstableApi::class)
class MoatazGainAudioProcessor(
    private val baseGainDb: Float,
    automation: List<AudioGainPoint>,
    private val itemDurationUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
) : BaseAudioProcessor() {
    private val automation = automation.sortedBy { it.timeUs }
    private var processedFrames = 0L

    init {
        require(baseGainDb in -60f..24f)
        require(itemDurationUs > 0)
        require(fadeInUs >= 0 && fadeOutUs >= 0 && fadeInUs + fadeOutUs <= itemDurationUs)
        require(this.automation.all { it.timeUs in 0..itemDurationUs })
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = baseGainDb != 0f || automation.isNotEmpty() || fadeInUs > 0 || fadeOutUs > 0

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        processedFrames = 0L
    }

    override fun onReset() {
        processedFrames = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * inputAudioFormat.channelCount
        val completeBytes = inputBuffer.remaining() - (inputBuffer.remaining() % frameSize)
        if (completeBytes <= 0) return
        val output = replaceOutputBuffer(completeBytes)
        val frameCount = completeBytes / frameSize
        repeat(frameCount) { frameIndex ->
            val absoluteFrame = processedFrames + frameIndex
            val timeUs = absoluteFrame * 1_000_000L / inputAudioFormat.sampleRate
            val factor = gainFactorAt(timeUs)
            repeat(inputAudioFormat.channelCount) {
                if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                    val value = inputBuffer.float
                    output.putFloat((value * factor).coerceIn(-1f, 1f))
                } else {
                    val value = inputBuffer.short.toInt()
                    val scaled = (value * factor).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    output.putShort(scaled.toShort())
                }
            }
        }
        processedFrames += frameCount
        output.flip()
    }

    private fun gainFactorAt(timeUs: Long): Float {
        val automationDb = automationDbAt(timeUs)
        val db = (baseGainDb + automationDb).coerceIn(-80f, 24f)
        val dbFactor = 10.0.pow(db / 20.0).toFloat()
        return dbFactor * fadeFactorAt(timeUs)
    }

    private fun automationDbAt(timeUs: Long): Float {
        if (automation.isEmpty()) return 0f
        if (timeUs <= automation.first().timeUs) return automation.first().gainDb
        if (timeUs >= automation.last().timeUs) return automation.last().gainDb
        val rightIndex = automation.indexOfFirst { it.timeUs >= timeUs }
        if (rightIndex <= 0) return automation.first().gainDb
        val left = automation[rightIndex - 1]
        val right = automation[rightIndex]
        if (right.timeUs == left.timeUs) return right.gainDb
        val t = (timeUs - left.timeUs).toDouble() / (right.timeUs - left.timeUs).toDouble()
        return (left.gainDb + (right.gainDb - left.gainDb) * t).toFloat()
    }

    private fun fadeFactorAt(timeUs: Long): Float {
        var factor = 1f
        if (fadeInUs > 0 && timeUs < fadeInUs) factor *= (timeUs.toDouble() / fadeInUs).toFloat().coerceIn(0f, 1f)
        val fadeOutStart = itemDurationUs - fadeOutUs
        if (fadeOutUs > 0 && timeUs > fadeOutStart) {
            factor *= ((itemDurationUs - timeUs).toDouble() / fadeOutUs).toFloat().coerceIn(0f, 1f)
        }
        return factor
    }
}
